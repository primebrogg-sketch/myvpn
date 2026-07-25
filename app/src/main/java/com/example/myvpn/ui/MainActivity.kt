package com.example.myvpn.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myvpn.data.ServerConfig

class MainActivity : ComponentActivity() {
    private val viewModel: VpnViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyVpnTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VpnScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnScreen(viewModel: VpnViewModel) {
    val state by viewModel.state.collectAsState()
    var manualLink by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) viewModel.refreshAndConnect()
    }

    fun connect() {
        val prepareIntent = VpnService.prepare(viewModel.getApplication())
        if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent)
        else viewModel.refreshAndConnect()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MyVPN", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.refreshOnly() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add server")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Connect button + status
            PowerButton(
                state = state,
                onClick = {
                    when (state) {
                        is VpnUiState.Connected -> viewModel.disconnect()
                        is VpnUiState.Ready, is VpnUiState.Idle, is VpnUiState.Error -> connect()
                        else -> {}
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(8.dp))
            StatusText(state = state)
            Spacer(Modifier.height(20.dp))

            // Server list
            val ranked = when (val s = state) {
                is VpnUiState.Ready -> s.alive
                is VpnUiState.Connected -> s.alive
                else -> null
            }
            val servers = when (val s = state) {
                is VpnUiState.Ready -> s.servers
                else -> null
            }
            val hasServers = ranked != null || servers != null
            val currentIdx = if (state is VpnUiState.Connected) (state as VpnUiState.Connected).currentIndex else -1

            if (ranked != null && ranked.isNotEmpty()) {
                Text(
                    "Серверы",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val manualAliveCount = when (val s = state) {
                    is VpnUiState.Ready -> s.manualAliveCount
                    is VpnUiState.Connected -> s.manualAliveCount
                    else -> 0
                }
                val pubCount = ranked.size - manualAliveCount

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(ranked) { i, (server, latency) ->
                        ServerRow(
                            server = server,
                            latency = latency,
                            isActive = i == currentIdx,
                            isManual = i >= pubCount,
                            onClick = {
                                if (state is VpnUiState.Ready || (state is VpnUiState.Connected && i != currentIdx))
                                    viewModel.switchToServer(i)
                            }
                        )
                    }
                }
            } else if (state !is VpnUiState.Searching && state !is VpnUiState.Idle && ranked != null) {
                Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Нет доступных серверов", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showAddDialog) {
        var link by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Добавить сервер") },
            text = {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("vless:// или ss://") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (link.isNotBlank()) {
                        viewModel.addManualServer(link)
                        showAddDialog = false
                    }
                }) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun PowerButton(state: VpnUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isConnected = state is VpnUiState.Connected
    val isConnecting = state is VpnUiState.Connecting
    val isEnabled = state !is VpnUiState.Searching

    val infiniteTransition = rememberInfiniteTransition(label = "power")
    val pulseAnim by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
        label = "pulse"
    )

    val outerColor by animateColorAsState(
        if (isConnected) MaterialTheme.colorScheme.primary
        else if (isConnecting) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "outer"
    )
    val innerColor = if (isConnected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant

    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier.size(160.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!isConnected && isEnabled) {
            Canvas(Modifier.size(160.dp)) {
                val alpha = 0.15f + 0.1f * pulseAnim
                drawCircle(
                    color = primary.copy(alpha = alpha),
                    radius = size.minDimension / 2
                )
                drawCircle(
                    color = primary.copy(alpha = alpha * 0.6f),
                    radius = size.minDimension / 2.3f
                )
            }
        }
        Canvas(
            Modifier
                .size(120.dp)
                .clip(CircleShape)
                .clickable(enabled = isEnabled) { onClick() }
        ) {
            val stroke = 8.dp.toPx()
            drawCircle(
                color = outerColor.copy(alpha = 0.2f),
                radius = size.minDimension / 2
            )
            drawCircle(
                color = outerColor,
                radius = size.minDimension / 2 - stroke / 2,
                style = Stroke(width = stroke)
            )
            val cx = size.width / 2
            val cy = size.height / 2
            val r = size.minDimension / 4.5f
            drawArc(
                color = innerColor,
                startAngle = -90f - 60f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            drawCircle(
                color = innerColor,
                radius = 2.dp.toPx(),
                center = Offset(cx, cy - r)
            )
        }
    }
}

@Composable
private fun StatusText(state: VpnUiState) {
    val (text, color) = when (state) {
        is VpnUiState.Idle -> "Нажмите для подключения" to MaterialTheme.colorScheme.onSurfaceVariant
        is VpnUiState.Searching -> {
            val msg = if (state.totalFound > 0) "Поиск… ${state.aliveCount}/${state.totalFound}"
                else "Поиск серверов…"
            msg to MaterialTheme.colorScheme.tertiary
        }
        is VpnUiState.Ready -> "Готово к подключению" to MaterialTheme.colorScheme.onSurfaceVariant
        is VpnUiState.Connecting -> "Подключение…" to MaterialTheme.colorScheme.tertiary
        is VpnUiState.Connected -> "Подключено" to MaterialTheme.colorScheme.primary
        is VpnUiState.Error -> state.message to MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ServerRow(
    server: ServerConfig,
    latency: Long,
    isActive: Boolean,
    isManual: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Signal bars
            LatencyBars(latency = latency)

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name.take(30),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProtocolBadge(protocol = server.protocol.name.take(8))
                    if (isManual) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                "свой",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            Text(
                text = if (latency < 1000) "${latency}ms" else ">1s",
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    latency < 100 -> Color(0xFF4CAF50)
                    latency < 300 -> Color(0xFFFF9800)
                    else -> MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LatencyBars(latency: Long) {
    val bars = 4
    val activeBars = when {
        latency < 50 -> 4
        latency < 100 -> 3
        latency < 200 -> 2
        else -> 1
    }
    val color = when {
        latency < 100 -> Color(0xFF4CAF50)
        latency < 300 -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.error
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(18.dp)
    ) {
        val heights = listOf(6f, 10f, 14f, 18f)
        heights.forEachIndexed { i, h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .background(
                        color = if (i < activeBars) color else color.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(1.5.dp)
                    )
            )
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    ) {
        Text(
            text = protocol,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
