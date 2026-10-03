package com.serverwatch.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { ServerWatchApp() }
    }

    @Composable
    private fun ServerWatchApp() {
        val context = this@MainActivity
        val scope = rememberCoroutineScope()
        var servers by remember { mutableStateOf(Prefs.loadServers(context)) }
        var states by remember { mutableStateOf<Map<String, ServerState>>(emptyMap()) }
        var adding by remember { mutableStateOf(false) }
        var running by remember { mutableStateOf(false) }

        fun refresh() {
            scope.launch(Dispatchers.IO) {
                val next = servers.associate { s ->
                    s.name to runCatching { ServerState(s, Api.fetch(s)) }
                        .getOrElse { ServerState(s, null, it.message ?: "Connection failed") }
                }
                states = next
            }
        }

        LaunchedEffect(servers) { if (servers.isNotEmpty()) refresh() }

        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Scaffold(
                topBar = { TopAppBar(
                    title = { Text("ServerWatch") },
                    actions = { TextButton(onClick = { refresh() }, enabled = servers.isNotEmpty()) { Text("Refresh") } }
                ) },
                floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Text("+") } }
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Servers", servers.size.toString(), Modifier.weight(1f))
                        StatCard("Critical", states.values.count { it.status?.health == "CRITICAL" || it.error != null }.toString(), Modifier.weight(1f))
                        StatCard("Warning", states.values.count { it.status?.health == "WARNING" }.toString(), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    if (servers.isEmpty()) {
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
                            Text("No servers configured", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(6.dp))
                            Text("Add a server running the ServerWatch Linux agent.")
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { adding = true }) { Text("Add server") }
                        }}
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                            items(servers, key = { it.name }) { s ->
                                ServerCard(states[s.name], onDelete = {
                                    val updated = servers.filterNot { it.name == s.name }
                                    servers = updated
                                    Prefs.saveServers(context, updated)
                                })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            running = !running
                            if (running) ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
                            else context.stopService(Intent(context, MonitoringService::class.java))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (running) "Stop background monitoring" else "Start background monitoring") }
                }
            }
        }

        if (adding) AddServerDialog(
            onDismiss = { adding = false },
            onSave = { name, url, token ->
                val updated = servers.filterNot { it.name == name } + ServerConfig(name, url.trimEnd('/'), token)
                servers = updated
                Prefs.saveServers(context, updated)
                adding = false
            }
        )
    }

    @Composable private fun StatCard(label: String, value: String, modifier: Modifier) {
        Card(modifier) { Column(Modifier.padding(12.dp)) { Text(value, style = MaterialTheme.typography.headlineSmall); Text(label, style = MaterialTheme.typography.labelMedium) } }
    }

    @Composable private fun ServerCard(state: ServerState?, onDelete: () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val s = state?.status
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(state?.config?.name ?: "Server", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            state?.error != null -> "DOWN"
                            s?.health != null -> s.health
                            else -> "..."
                        },
                        color = when {
                            state?.error != null || s?.health == "CRITICAL" -> MaterialTheme.colorScheme.error
                            s?.health == "WARNING" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (state?.error != null) Text(state.error ?: "Connection failed", color = MaterialTheme.colorScheme.error)
                if (s != null) {
                    Text("CPU " + String.format(Locale.US, "%.1f", s.cpuPercent) + "%   RAM " + String.format(Locale.US, "%.1f", s.ramPercent) + "%   SWAP " + String.format(Locale.US, "%.1f", s.swapPercent) + "%")
                    val maxDisk = s.disks.maxOfOrNull { it.percent } ?: 0.0
                    Text("Disk " + String.format(Locale.US, "%.1f", maxDisk) + "%   Uptime " + formatUptime(s.uptimeSeconds))
                    val net = s.networks.filter { it.name != "lo" }.sumOf { it.rxMbps }
                    val tx = s.networks.filter { it.name != "lo" }.sumOf { it.txMbps }
                    Text("Network RX " + String.format(Locale.US, "%.1f", net) + " Mbps   TX " + String.format(Locale.US, "%.1f", tx) + " Mbps")
                    val problems = s.services.count { !it.active } + s.docker.count { it.state != "running" }
                    if (problems > 0) Text("Problems: " + problems + " service/container(s)", color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDelete) { Text("Remove") } }
            }
        }
    }

    @Composable private fun AddServerDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("http://") }
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add server") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("Agent URL") }, singleLine = true)
                OutlinedTextField(token, { token = it }, label = { Text("Agent token") }, singleLine = true)
                Text("Example: http://192.168.1.20:8787", style = MaterialTheme.typography.bodySmall)
            }},
            confirmButton = { Button(enabled = name.isNotBlank() && url.startsWith("http") && token.isNotBlank(), onClick = { onSave(name.trim(), url.trim(), token.trim()) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    private fun formatUptime(seconds: Long): String {
        val d = seconds / 86400
        val h = (seconds % 86400) / 3600
        val m = (seconds % 3600) / 60
        return if (d > 0) d.toString() + "d " + h + "h" else h.toString() + "h " + m + "m"
    }
}
