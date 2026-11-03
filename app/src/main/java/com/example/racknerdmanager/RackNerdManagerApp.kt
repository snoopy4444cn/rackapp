package com.example.racknerdmanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

// ============================================================================
// 1. 数据模型 (RackNerd Ctrl / SolusVM Client API Data Models)
// ============================================================================

data class RackNerdNodeConfig(
    val id: String = UUID.randomUUID().toString(),
    val alias: String = "",
    val endpoint: String = "https://ctrl.racknerd.com",
    val apiKey: String = "",
    val apiHash: String = ""
)

data class UsageMetric(
    val totalBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val percent: Int = 0
) {
    val progress: Float
        get() = (percent.coerceIn(0, 100)) / 100f
}

data class RackNerdNodeStatus(
    val nodeId: String,
    val isOnline: Boolean = false,
    val vmStat: String = "unknown",
    val hostname: String = "",
    val ipAddress: String = "",
    val ipList: String = "",
    val bandwidth: UsageMetric = UsageMetric(),
    val disk: UsageMetric = UsageMetric(),
    val memory: UsageMetric = UsageMetric(),
    val lastUpdated: String = "",
    val error: String? = null
)

// ============================================================================
// 2. 伪 XML 响应解析器与网络客户端 (SolusVM Client API Client)
// ============================================================================

object SolusVmParser {
    fun extractTag(raw: String, tag: String): String {
        val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(raw)?.groupValues?.get(1)?.trim() ?: ""
    }

    fun parseUsage(csv: String): UsageMetric {
        if (csv.isBlank()) return UsageMetric()
        val parts = csv.split(",")
        if (parts.size < 4) return UsageMetric()
        val total = parts[0].trim().toLongOrNull() ?: 0L
        val used = parts[1].trim().toLongOrNull() ?: 0L
        val free = parts[2].trim().toLongOrNull() ?: 0L
        val pct = parts[3].trim().replace("%", "").toDoubleOrNull()?.toInt() ?: 0
        return UsageMetric(totalBytes = total, usedBytes = used, freeBytes = free, percent = pct)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) {
            return String.format("%.2f GB", gb)
        }
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format("%.0f MB", mb)
    }
}

class RackNerdApiClient {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun buildCommandUrl(endpoint: String): String {
        val trimmed = endpoint.trim().trimEnd('/')
        return if (trimmed.endsWith("command.php")) {
            trimmed
        } else {
            "$trimmed/api/client/command.php"
        }
    }

    suspend fun fetchNodeInfo(config: RackNerdNodeConfig): RackNerdNodeStatus = withContext(Dispatchers.IO) {
        try {
            val baseUrl = buildCommandUrl(config.endpoint).toHttpUrlOrNull()
                ?: return@withContext RackNerdNodeStatus(
                    nodeId = config.id,
                    error = "面板地址格式无效: ${config.endpoint}"
                )

            // 1. 请求 info (获取流量、硬盘、内存、IP、主机名)
            val infoUrl = baseUrl.newBuilder()
                .addQueryParameter("key", config.apiKey.trim())
                .addQueryParameter("hash", config.apiHash.trim())
                .addQueryParameter("action", "info")
                .addQueryParameter("ipaddr", "true")
                .addQueryParameter("hdd", "true")
                .addQueryParameter("mem", "true")
                .addQueryParameter("bw", "true")
                .build()

            val infoResp = okHttpClient.newCall(Request.Builder().url(infoUrl).get().build()).execute()
            val infoBody = infoResp.body?.string() ?: ""

            val statusTag = SolusVmParser.extractTag(infoBody, "status")
            val statusMsg = SolusVmParser.extractTag(infoBody, "statusmsg")

            if (statusTag.equals("error", ignoreCase = true)) {
                return@withContext RackNerdNodeStatus(
                    nodeId = config.id,
                    error = statusMsg.ifBlank { "API 密钥或 Hash 校验失败" }
                )
            }

            // 2. 请求 status (获取实时在线状态 online / offline)
            val statusUrl = baseUrl.newBuilder()
                .addQueryParameter("key", config.apiKey.trim())
                .addQueryParameter("hash", config.apiHash.trim())
                .addQueryParameter("action", "status")
                .build()

            val statResp = okHttpClient.newCall(Request.Builder().url(statusUrl).get().build()).execute()
            val statBody = statResp.body?.string() ?: ""

            val vmStat = SolusVmParser.extractTag(statBody, "vmstat")
                .ifBlank { SolusVmParser.extractTag(statBody, "statusmsg") }
                .ifBlank { SolusVmParser.extractTag(infoBody, "vmstat") }
                .ifBlank { "online" }

            val hostname = SolusVmParser.extractTag(infoBody, "hostname")
                .ifBlank { SolusVmParser.extractTag(statBody, "hostname") }
            val ipAddress = SolusVmParser.extractTag(infoBody, "ipaddress")
                .ifBlank { SolusVmParser.extractTag(statBody, "ipaddress") }
            val ipAddrList = SolusVmParser.extractTag(infoBody, "ipaddr")

            val bwMetric = SolusVmParser.parseUsage(SolusVmParser.extractTag(infoBody, "bw"))
            val hddMetric = SolusVmParser.parseUsage(SolusVmParser.extractTag(infoBody, "hdd"))
            val memMetric = SolusVmParser.parseUsage(SolusVmParser.extractTag(infoBody, "mem"))

            val isOnline = vmStat.equals("online", ignoreCase = true)

            RackNerdNodeStatus(
                nodeId = config.id,
                isOnline = isOnline,
                vmStat = vmStat,
                hostname = hostname,
                ipAddress = ipAddress,
                ipList = ipAddrList,
                bandwidth = bwMetric,
                disk = hddMetric,
                memory = memMetric,
                error = null
            )
        } catch (e: Exception) {
            RackNerdNodeStatus(
                nodeId = config.id,
                error = "连接失败: ${e.localizedMessage ?: "请检查网络或面板地址"}"
            )
        }
    }

    suspend fun performPowerAction(config: RackNerdNodeConfig, action: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = buildCommandUrl(config.endpoint).toHttpUrlOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("无效面板地址"))

            val actionUrl = baseUrl.newBuilder()
                .addQueryParameter("key", config.apiKey.trim())
                .addQueryParameter("hash", config.apiHash.trim())
                .addQueryParameter("action", action)
                .build()

            val resp = okHttpClient.newCall(Request.Builder().url(actionUrl).get().build()).execute()
            val body = resp.body?.string() ?: ""
            val status = SolusVmParser.extractTag(body, "status")
            val msg = SolusVmParser.extractTag(body, "statusmsg")

            if (status.equals("success", ignoreCase = true)) {
                val label = when (action) {
                    "boot" -> "开机指令已成功下发"
                    "shutdown" -> "关机指令已成功下发"
                    "reboot" -> "重启指令已成功下发"
                    else -> "操作成功 ($msg)"
                }
                Result.success(label)
            } else {
                Result.failure(RuntimeException(msg.ifBlank { "面板返回错误: $body" }))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// ============================================================================
// 3. 本地加密存储 (支持保存多台 RackNerd VPS 凭证)
// ============================================================================

class RackNerdStorage(context: Context) {
    private val gson = Gson()
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "racknerd_secure_nodes",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("racknerd_fallback_nodes", Context.MODE_PRIVATE)
    }

    fun getNodes(): List<RackNerdNodeConfig> {
        val json = prefs.getString(KEY_NODES_JSON, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<RackNerdNodeConfig>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveNodes(nodes: List<RackNerdNodeConfig>) {
        prefs.edit().putString(KEY_NODES_JSON, gson.toJson(nodes)).apply()
    }

    companion object {
        private const val KEY_NODES_JSON = "racknerd_saved_nodes_v1"
    }
}

// ============================================================================
// 4. ViewModel 与状态管理
// ============================================================================

data class RackNerdUiState(
    val nodes: List<RackNerdNodeConfig> = emptyList(),
    val statusMap: Map<String, RackNerdNodeStatus> = emptyMap(),
    val selectedNodeId: String? = null,
    val isRefreshing: Boolean = false,
    val isActionLoading: Boolean = false,
    val toastMessage: String? = null
)

class RackNerdViewModel(private val storage: RackNerdStorage) : ViewModel() {
    private val apiClient = RackNerdApiClient()

    private val _uiState = MutableStateFlow(RackNerdUiState(nodes = storage.getNodes()))
    val uiState: StateFlow<RackNerdUiState> = _uiState.asStateFlow()

    init {
        if (_uiState.value.nodes.isNotEmpty()) {
            refreshAllNodes()
        }
    }

    fun addOrUpdateNode(node: RackNerdNodeConfig) {
        val current = _uiState.value.nodes.toMutableList()
        val idx = current.indexOfFirst { it.id == node.id }
        if (idx >= 0) {
            current[idx] = node
        } else {
            current.add(node)
        }
        storage.saveNodes(current)
        _uiState.value = _uiState.value.copy(
            nodes = current,
            toastMessage = "已保存主机配置"
        )
        refreshSingleNode(node)
    }

    fun removeNode(nodeId: String) {
        val updated = _uiState.value.nodes.filterNot { it.id == nodeId }
        val updatedMap = _uiState.value.statusMap.toMutableMap().apply { remove(nodeId) }
        storage.saveNodes(updated)
        _uiState.value = _uiState.value.copy(
            nodes = updated,
            statusMap = updatedMap,
            selectedNodeId = if (_uiState.value.selectedNodeId == nodeId) null else _uiState.value.selectedNodeId,
            toastMessage = "已从本地列表移除该主机"
        )
    }

    fun selectNode(nodeId: String?) {
        _uiState.value = _uiState.value.copy(selectedNodeId = nodeId)
        if (nodeId != null) {
            _uiState.value.nodes.find { it.id == nodeId }?.let { refreshSingleNode(it) }
        }
    }

    fun refreshAllNodes() {
        val list = _uiState.value.nodes
        if (list.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            val results = list.map { node ->
                async { apiClient.fetchNodeInfo(node) }
            }.awaitAll()

            val newMap = results.associateBy { it.nodeId }
            _uiState.value = _uiState.value.copy(
                isRefreshing = false,
                statusMap = _uiState.value.statusMap + newMap
            )
        }
    }

    fun refreshSingleNode(node: RackNerdNodeConfig) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            val status = apiClient.fetchNodeInfo(node)
            val updatedMap = _uiState.value.statusMap.toMutableMap()
            updatedMap[node.id] = status
            _uiState.value = _uiState.value.copy(
                isRefreshing = false,
                statusMap = updatedMap
            )
        }
    }

    fun executePowerAction(node: RackNerdNodeConfig, action: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isActionLoading = true)
            val res = apiClient.performPowerAction(node, action)
            res.fold(
                onSuccess = { msg ->
                    _uiState.value = _uiState.value.copy(
                        isActionLoading = false,
                        toastMessage = msg
                    )
                    refreshSingleNode(node)
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isActionLoading = false,
                        toastMessage = "操作失败: ${err.localizedMessage}"
                    )
                }
            )
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }
}

class RackNerdViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return RackNerdViewModel(RackNerdStorage(context.applicationContext)) as T
    }
}

// ============================================================================
// 5. Activity 与主题界面 (Jetpack Compose Material 3)
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = rackNerdColorScheme()) {
                val context = LocalContext.current
                val viewModel: RackNerdViewModel = viewModel(factory = RackNerdViewModelFactory(context))
                RackNerdAppScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun rackNerdColorScheme() = lightColorScheme(
    primary = Color(0xFFD9232E),      // RackNerd 标志性深红
    secondary = Color(0xFF1E293B),    // 深灰蓝机柜色
    tertiary = Color(0xFF0284C7),
    background = Color(0xFFF8FAFC),
    surface = Color.White
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RackNerdAppScreen(viewModel: RackNerdViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var editingNode by remember { mutableStateOf<RackNerdNodeConfig?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val selectedNode = uiState.nodes.find { it.id == uiState.selectedNodeId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = selectedNode?.alias?.ifBlank { "VPS 详情" } ?: "RackNerd 云主机管理",
                            fontWeight = FontWeight.Bold
                        )
                        if (selectedNode == null) {
                            Text(
                                text = "新面板 Ctrl.racknerd.com • 已配置 ${uiState.nodes.size} 台主机",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (selectedNode != null) {
                        IconButton(onClick = { viewModel.selectNode(null) }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回列表")
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (selectedNode != null) {
                                viewModel.refreshSingleNode(selectedNode)
                            } else {
                                viewModel.refreshAllNodes()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新状态")
                    }
                    if (selectedNode != null) {
                        IconButton(onClick = { editingNode = selectedNode }) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑凭证")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedNode == null) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("添加 VPS") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (selectedNode == null) {
                NodeListScreen(
                    nodes = uiState.nodes,
                    statusMap = uiState.statusMap,
                    onAddClick = { showAddDialog = true },
                    onSelectNode = { viewModel.selectNode(it.id) }
                )
            } else {
                NodeDetailScreen(
                    node = selectedNode,
                    status = uiState.statusMap[selectedNode.id],
                    isActionLoading = uiState.isActionLoading,
                    onPowerAction = { action -> viewModel.executePowerAction(selectedNode, action) },
                    onEditNode = { editingNode = selectedNode },
                    onRemoveNode = { viewModel.removeNode(selectedNode.id) }
                )
            }
        }
    }

    if (showAddDialog) {
        NodeCredentialDialog(
            initialNode = null,
            onDismiss = { showAddDialog = false },
            onSave = { newNode ->
                viewModel.addOrUpdateNode(newNode)
                showAddDialog = false
            }
        )
    }

    editingNode?.let { nodeToEdit ->
        NodeCredentialDialog(
            initialNode = nodeToEdit,
            onDismiss = { editingNode = null },
            onSave = { updated ->
                viewModel.addOrUpdateNode(updated)
                editingNode = null
            }
        )
    }
}

// ============================================================================
// 6. 子页面 1: 多主机概览列表
// ============================================================================

@Composable
fun NodeListScreen(
    nodes: List<RackNerdNodeConfig>,
    statusMap: Map<String, RackNerdNodeStatus>,
    onAddClick: () -> Unit,
    onSelectNode: (RackNerdNodeConfig) -> Unit
) {
    if (nodes.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Dns,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("尚未添加 RackNerd 主机", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "请登录 RackNerd 新控制面板 (ctrl.racknerd.com)，进入对应 VPS 的 API 选项生成 API Key 与 API Hash，点击下方按钮添加保存。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("添加第一台 VPS")
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(nodes, key = { it.id }) { node ->
            val status = statusMap[node.id]
            NodeSummaryCard(
                node = node,
                status = status,
                onClick = { onSelectNode(node) }
            )
        }
    }
}

@Composable
fun NodeSummaryCard(
    node: RackNerdNodeConfig,
    status: RackNerdNodeStatus?,
    onClick: () -> Unit
) {
    val isOnline = status?.isOnline == true && status.error == null
    val dotColor = when {
        status == null -> Color.Gray
        status.error != null -> Color(0xFFDC2626)
        isOnline -> Color(0xFF16A34A)
        else -> Color(0xFFD97706)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = node.alias.ifBlank { status?.hostname?.ifBlank { "RackNerd VPS" } ?: "RackNerd VPS" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AssistChip(
                    onClick = onClick,
                    label = {
                        val badge = when {
                            status == null -> "加载中"
                            status.error != null -> "连接异常"
                            isOnline -> "ONLINE"
                            else -> status.vmStat.uppercase()
                        }
                        Text(badge, style = MaterialTheme.typography.labelSmall)
                    }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            if (status?.error != null) {
                Text(
                    text = status.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFDC2626)
                )
            } else {
                Text(
                    text = "IP: ${status?.ipAddress?.ifBlank { "获取中..." } ?: "获取中..."}  •  Hostname: ${status?.hostname?.ifBlank { "-" } ?: "-"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                if (status != null && status.bandwidth.totalBytes > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "月度流量: ${SolusVmParser.formatBytes(status.bandwidth.usedBytes)} / ${SolusVmParser.formatBytes(status.bandwidth.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${status.bandwidth.percent}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { status.bandwidth.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }
        }
    }
}

// ============================================================================
// 7. 子页面 2: 单台 VPS 详情、流量/内存/磁盘监控与电源控制
// ============================================================================

@Composable
fun NodeDetailScreen(
    node: RackNerdNodeConfig,
    status: RackNerdNodeStatus?,
    isActionLoading: Boolean,
    onPowerAction: (String) -> Unit,
    onEditNode: () -> Unit,
    onRemoveNode: () -> Unit
) {
    val context = LocalContext.current
    var showRemoveConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 顶部状态与 IP 卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = node.alias.ifBlank { status?.hostname?.ifBlank { "RackNerd VPS" } ?: "RackNerd VPS" },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "面板: ${node.endpoint}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val onlineText = if (status?.isOnline == true) "运行中 (Online)" else (status?.vmStat ?: "未知")
                    Text(
                        text = onlineText,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (status?.isOnline == true) Color(0xFF16A34A) else Color(0xFFD97706),
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("主 IPv4 地址", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = status?.ipAddress?.ifBlank { "-" } ?: "-",
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val ip = status?.ipAddress.orEmpty()
                            if (ip.isNotBlank()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("RackNerd IP", ip))
                                Toast.makeText(context, "IP 地址已复制", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("复制 IP")
                    }
                }
            }
        }

        if (status?.error != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("API 响应异常", color = Color(0xFFB91C1C), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(status.error, style = MaterialTheme.typography.bodySmall, color = Color(0xFF7F1D1D))
                }
            }
        }

        // 电源控制卡片 (Boot / Reboot / Shutdown)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("电源控制 (SolusVM / Ctrl API)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onPowerAction("boot") },
                        enabled = !isActionLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("开机")
                    }
                    OutlinedButton(
                        onClick = { onPowerAction("reboot") },
                        enabled = !isActionLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重启")
                    }
                    OutlinedButton(
                        onClick = { onPowerAction("shutdown") },
                        enabled = !isActionLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("关机")
                    }
                }
                AnimatedVisibility(visible = isActionLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                }
            }
        }

        // 资源配额与使用率卡片 (流量 / 磁盘 / 内存)
        if (status != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("资源使用率与网络详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    HorizontalDivider()

                    UsageProgressRow(
                        title = "月度流量 (Bandwidth)",
                        metric = status.bandwidth
                    )
                    UsageProgressRow(
                        title = "磁盘空间 (Disk / HDD)",
                        metric = status.disk
                    )
                    UsageProgressRow(
                        title = "物理内存 (Memory / RAM)",
                        metric = status.memory
                    )

                    HorizontalDivider()
                    InfoItemRow("主机名 (Hostname)", status.hostname.ifBlank { "-" })
                    InfoItemRow("运行状态码 (vmstat)", status.vmStat)
                    InfoItemRow("绑定 IP 列表", status.ipList.ifBlank { status.ipAddress.ifBlank { "-" } })
                }
            }
        }

        // 凭证管理与移除卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("本地凭证管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onEditNode,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("修改 Key / Hash")
                    }
                    OutlinedButton(
                        onClick = { showRemoveConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("移除此主机")
                    }
                }
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("确认从 App 移除此主机？") },
            text = { Text("这只会删除保存在手机本地的 API Key 与 Hash 凭证，不会影响你在 RackNerd 后台的云主机。") },
            confirmButton = {
                Button(
                    onClick = {
                        showRemoveConfirm = false
                        onRemoveNode()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("确认移除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun UsageProgressRow(title: String, metric: UsageMetric) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("${metric.percent}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { metric.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "已用: ${SolusVmParser.formatBytes(metric.usedBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "剩余: ${SolusVmParser.formatBytes(metric.freeBytes)} / 总计: ${SolusVmParser.formatBytes(metric.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoItemRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.6f)
        )
    }
}

// ============================================================================
// 8. 添加 / 编辑 RackNerd 主机 API Key & Hash 弹窗 (默认新面板 ctrl.racknerd.com)
// ============================================================================

@Composable
fun NodeCredentialDialog(
    initialNode: RackNerdNodeConfig?,
    onDismiss: () -> Unit,
    onSave: (RackNerdNodeConfig) -> Unit
) {
    var alias by remember { mutableStateOf(initialNode?.alias ?: "") }
    var endpoint by remember { mutableStateOf(initialNode?.endpoint ?: "https://ctrl.racknerd.com") }
    var apiKey by remember { mutableStateOf(initialNode?.apiKey ?: "") }
    var apiHash by remember { mutableStateOf(initialNode?.apiHash ?: "") }
    var showHash by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
        title = { Text(if (initialNode == null) "添加 RackNerd VPS" else "编辑 VPS 凭证") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "默认使用 RackNerd 新面板 (https://ctrl.racknerd.com)，也可一键切换至旧版 NerdVM 面板。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = endpoint.contains("ctrl.racknerd.com"),
                        onClick = { endpoint = "https://ctrl.racknerd.com" },
                        label = { Text("新面板 ctrl") }
                    )
                    FilterChip(
                        selected = endpoint.contains("nerdvm.racknerd.com"),
                        onClick = { endpoint = "https://nerdvm.racknerd.com" },
                        label = { Text("旧面板 nerdvm") }
                    )
                }

                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("备注名称 (选填)") },
                    placeholder = { Text("例如: 洛杉矶 DC-02") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("面板地址 (Endpoint)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("例如: XXXXX-XXXXX-XXXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = apiHash,
                    onValueChange = { apiHash = it },
                    label = { Text("API Hash") },
                    placeholder = { Text("粘贴 40 位 SHA1 Hash") },
                    singleLine = true,
                    visualTransformation = if (showHash) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showHash = !showHash }) {
                            Icon(
                                imageVector = if (showHash) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "显示或隐藏 Hash"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        RackNerdNodeConfig(
                            id = initialNode?.id ?: UUID.randomUUID().toString(),
                            alias = alias.trim(),
                            endpoint = endpoint.trim().ifBlank { "https://ctrl.racknerd.com" },
                            apiKey = apiKey.trim(),
                            apiHash = apiHash.trim()
                        )
                    )
                },
                enabled = apiKey.isNotBlank() && apiHash.isNotBlank()
            ) {
                Text("保存并查询")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
