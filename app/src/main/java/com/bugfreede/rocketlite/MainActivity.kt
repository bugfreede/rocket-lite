package com.bugfreede.rocketlite

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bugfreede.rocketlite.core.CoreState
import com.bugfreede.rocketlite.core.EngineHolder
import com.bugfreede.rocketlite.data.NodeStore
import com.bugfreede.rocketlite.model.ProxyNode
import com.bugfreede.rocketlite.parser.LinkParser
import com.bugfreede.rocketlite.ui.theme.RocketLiteTheme
import com.bugfreede.rocketlite.vpn.RocketVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import android.net.VpnService as AndroidVpnService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NodeStore.init(this)
        setContent {
            RocketLiteTheme {
                MainScreen()
            }
        }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _nodes = MutableStateFlow<List<ProxyNode>>(emptyList())
    val nodes: StateFlow<List<ProxyNode>> = _nodes.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    init {
        NodeStore.init(application)
        _nodes.value = NodeStore.load()
        _selectedId.value = _nodes.value.firstOrNull()?.id
    }

    fun select(id: String) {
        _selectedId.value = id
    }

    fun delete(id: String) {
        NodeStore.remove(id)
        _nodes.value = NodeStore.load()
        if (_selectedId.value == id) _selectedId.value = _nodes.value.firstOrNull()?.id
    }

    /** Imports one link, many links, or a base64 subscription body pasted as text. */
    fun importText(text: String): Int {
        val single = LinkParser.parse(text)
        val parsed = if (single != null) listOf(single) else LinkParser.parseMany(text)
        val added = NodeStore.add(parsed)
        _nodes.value = NodeStore.load()
        if (_selectedId.value == null) _selectedId.value = _nodes.value.firstOrNull()?.id
        return added
    }

    fun importSubscription(url: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val body = withContext(Dispatchers.IO) { fetch(url) }
            val added = if (body == null) 0 else importText(body)
            onResult(added)
        }
    }

    private fun fetch(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "RocketLite/0.1")
        conn.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val nodes by vm.nodes.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    val coreState by EngineHolder.engine.state.collectAsState()
    val coreError by EngineHolder.engine.error.collectAsState()

    var showImport by remember { mutableStateOf(false) }
    var pendingNodeId by remember { mutableStateOf<String?>(null) }

    fun startVpn(id: String) {
        context.startService(
            Intent(context, RocketVpnService::class.java)
                .setAction(RocketVpnService.ACTION_START)
                .putExtra(RocketVpnService.EXTRA_NODE_ID, id)
        )
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingNodeId?.let { startVpn(it) }
        }
        pendingNodeId = null
    }

    fun toggleConnection() {
        if (coreState == CoreState.CONNECTED || coreState == CoreState.CONNECTING) {
            context.startService(
                Intent(context, RocketVpnService::class.java).setAction(RocketVpnService.ACTION_STOP)
            )
            return
        }
        val id = selectedId ?: return
        val prepare = AndroidVpnService.prepare(context)
        if (prepare != null) {
            pendingNodeId = id
            vpnLauncher.launch(prepare)
        } else {
            startVpn(id)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("RocketLite") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showImport = true }) {
                Icon(Icons.Filled.Add, contentDescription = "导入节点")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StatusCard(
                state = coreState,
                error = coreError,
                selected = nodes.firstOrNull { it.id == selectedId },
                onToggle = { toggleConnection() },
            )
            if (nodes.isEmpty()) {
                Text(
                    text = "还没有节点，点右下角 + 粘贴节点链接或订阅导入",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(nodes, key = { it.id }) { node ->
                        NodeRow(
                            node = node,
                            selected = node.id == selectedId,
                            onSelect = { vm.select(node.id) },
                            onDelete = { vm.delete(node.id) },
                        )
                    }
                }
            }
        }
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImportText = { vm.importText(it) },
            onImportSubscription = { url, done -> vm.importSubscription(url, done) },
        )
    }
}

@Composable
private fun StatusCard(
    state: CoreState,
    error: String?,
    selected: ProxyNode?,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val active = state == CoreState.CONNECTED
            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(96.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    Icons.Filled.PowerSettingsNew,
                    contentDescription = "连接或断开",
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = when (state) {
                    CoreState.IDLE -> "未连接"
                    CoreState.CONNECTING -> "连接中…"
                    CoreState.CONNECTED -> "已连接"
                    CoreState.ERROR -> "连接失败"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (state == CoreState.ERROR && !error.isNullOrBlank()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            Text(
                text = selected?.name ?: "未选择节点",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NodeRow(
    node: ProxyNode,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${node.type.name} · ${node.endpoint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除节点")
            }
        }
    }
}

@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onImportText: (String) -> Int,
    onImportSubscription: (String, (Int) -> Unit) -> Unit,
) {
    var linkText by remember { mutableStateOf("") }
    var subUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入节点") },
        text = {
            Column {
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    label = { Text("粘贴节点链接或订阅内容") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = subUrl,
                    onValueChange = { subUrl = it },
                    label = { Text("订阅链接 URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message!!, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(
                        onClick = {
                            val added = onImportText(linkText)
                            message = if (added > 0) "已新增 $added 个节点" else "没有识别到新节点"
                        },
                    ) { Text("导入文本") }
                    TextButton(
                        onClick = {
                            message = "正在拉取订阅…"
                            onImportSubscription(subUrl) { added ->
                                message = if (added > 0) "已新增 $added 个节点" else "没有识别到新节点"
                            }
                        },
                    ) { Text("拉取订阅") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
