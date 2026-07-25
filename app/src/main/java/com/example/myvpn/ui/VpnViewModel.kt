package com.example.myvpn.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myvpn.data.ServerConfig
import com.example.myvpn.vpn.MyVpnService
import com.example.myvpn.vpn.ServerPool
import com.example.myvpn.vpn.SingBoxConfigBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

sealed class VpnUiState {
    object Idle : VpnUiState()
    data class Searching(val totalFound: Int = 0, val checkedServers: Int = 0, val aliveCount: Int = 0) : VpnUiState()
    data class Ready(
        val servers: List<ServerConfig>,
        val alive: List<Pair<ServerConfig, Long>>,
        val manualAliveCount: Int,
    ) : VpnUiState()
    object Connecting : VpnUiState()
    data class Connected(
        val alive: List<Pair<ServerConfig, Long>>,
        val currentIndex: Int,
        val manualAliveCount: Int,
    ) : VpnUiState() {
        val current get() = alive[currentIndex]
    }
    data class Error(val message: String) : VpnUiState()
}

class VpnViewModel(app: Application) : AndroidViewModel(app) {

    private val pool = ServerPool()
    private var currentIndex = 0
    private var currentRanked: List<Pair<ServerConfig, Long>> = emptyList()
    private var currentManualAliveCount = 0

    private val _state = MutableStateFlow<VpnUiState>(VpnUiState.Idle)
    val state: StateFlow<VpnUiState> = _state

    private var intentionalDisconnect = false

    init {
        loadServers()
        viewModelScope.launch {
            MyVpnService.serviceStatus.collect { status: MyVpnService.ServiceStatus ->
                when (status) {
                    is MyVpnService.ServiceStatus.Connected -> {
                        val ranked = currentRanked
                        if (ranked.isNotEmpty() && _state.value is VpnUiState.Connecting) {
                            _state.value = VpnUiState.Connected(
                                alive = ranked, currentIndex = currentIndex,
                                manualAliveCount = currentManualAliveCount
                            )
                        }
                    }
                    is MyVpnService.ServiceStatus.Failed -> {
                        if (_state.value is VpnUiState.Connecting) {
                            _state.value = VpnUiState.Error(
                                "Ошибка подключения: ${status.message}"
                            )
                        }
                    }
                    is MyVpnService.ServiceStatus.Idle -> {
                        if (_state.value is VpnUiState.Connected && !intentionalDisconnect) {
                            _state.value = VpnUiState.Error("Соединение разорвано")
                        }
                    }
                }
            }
        }
    }

    private fun loadServers() {
        val servers = pool.getServers()
        if (servers.isNotEmpty()) {
            val ranked = servers.map { it to 0L }
            currentRanked = ranked
            _state.value = VpnUiState.Ready(servers = servers, alive = ranked, manualAliveCount = 0)
        }
    }

    private fun List<Pair<ServerConfig, Long>>.sortedManualLast(): List<Pair<ServerConfig, Long>> {
        val (manual, pub) = partition { pool.isManual(it.first) }
        return pub.sortedBy { it.second } + manual.sortedBy { it.second }
    }

    fun refreshAndConnect() {
        intentionalDisconnect = false
        viewModelScope.launch {
            _state.value = VpnUiState.Searching()
            try {
                val servers = pool.getServers()
                if (servers.isEmpty()) {
                    _state.value = VpnUiState.Error("Нет серверов. Добавьте сервер вручную.")
                    return@launch
                }

                // Ранжируем серверы по латентности (проверяем доступность)
                val ranked = withContext(Dispatchers.IO) {
                    pool.rankByLatency(servers, timeoutMs = 3000)
                }

                if (ranked.isEmpty()) {
                    _state.value = VpnUiState.Error("Нет доступных серверов. Проверьте сетевое соединение.")
                    return@launch
                }

                val manualAlive = ranked.count { pool.isManual(it.first) }

                currentRanked = ranked
                currentIndex = 0
                currentManualAliveCount = manualAlive

                _state.value = VpnUiState.Ready(servers = servers, alive = ranked, manualAliveCount = manualAlive)
                connectToRanked(ranked, 0)
            } catch (e: Exception) {
                _state.value = VpnUiState.Error(e.message ?: "Неизвестная ошибка")
            }
        }
    }

    fun refreshOnly() {
        viewModelScope.launch {
            _state.value = VpnUiState.Searching()
            try {
                val servers = pool.getServers()
                if (servers.isEmpty()) {
                    _state.value = VpnUiState.Error("Нет серверов")
                    return@launch
                }
                // Ранжируем серверы по доступности
                val ranked = withContext(Dispatchers.IO) {
                    pool.rankByLatency(servers, timeoutMs = 3000)
                }
                currentRanked = ranked
                currentManualAliveCount = ranked.count { pool.isManual(it.first) }
                _state.value = VpnUiState.Ready(servers = servers, alive = ranked, manualAliveCount = currentManualAliveCount)
            } catch (e: Exception) {
                _state.value = VpnUiState.Error(e.message ?: "Неизвестная ошибка")
            }
        }
    }

    fun switchToServer(index: Int) {
        val ranked = currentRanked
        if (index < 0 || index >= ranked.size) return
        currentIndex = index
        viewModelScope.launch {
            _state.value = VpnUiState.Connecting
            val configJson = SingBoxConfigBuilder.build(listOf(ranked[index].first))
            disconnectService()
            delay(300)
            startVpnService(configJson)
        }
    }

    private fun connectToRanked(ranked: List<Pair<ServerConfig, Long>>, index: Int) {
        val configJson = SingBoxConfigBuilder.build(listOf(ranked[index].first))
        _state.value = VpnUiState.Connecting
        startVpnService(configJson)
    }

    fun addManualServer(shareLink: String) = pool.addManualServer(shareLink)

    fun disconnect() {
        intentionalDisconnect = true
        disconnectService()
        _state.value = VpnUiState.Idle
    }

    private fun disconnectService() {
        val intent = Intent(getApplication(), MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_DISCONNECT
        }
        getApplication<Application>().startService(intent)
    }

    private fun startVpnService(configJson: String) {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
            putExtra(MyVpnService.EXTRA_CONFIG_JSON, configJson)
        }
        ctx.startForegroundService(intent)
    }
}
