package com.example.myvpn.vpn

import android.util.Log
import com.example.myvpn.data.PublicSources
import com.example.myvpn.data.ServerConfig
import com.example.myvpn.data.SubscriptionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class ServerPool {

    private val manualServers = mutableListOf<ServerConfig>()
    private val manualRawLinks = mutableSetOf<String>()

    private val defaultServers: List<ServerConfig> = PublicSources.rawConfigs
        .mapNotNull { SubscriptionParser.parseLine(it) }
        .also { Log.d("MyVPN/ServerPool", "Parsed ${it.size} default servers") }

    val isManual: (ServerConfig) -> Boolean get() = { it.raw in manualRawLinks }
    val manualCount: Int get() = manualRawLinks.size

    fun addManualServer(shareLink: String) {
        SubscriptionParser.parseLine(shareLink)?.let {
            manualServers.add(it)
            manualRawLinks.add(it.raw)
            Log.d("MyVPN/ServerPool", "Manual server added: ${it.name} (${it.protocol})")
        }
    }

    fun getServers(): List<ServerConfig> {
        return (defaultServers + manualServers)
            .distinctBy { it.raw }
    }

    suspend fun rankByLatency(
        servers: List<ServerConfig> = getServers(),
        timeoutMs: Int = 5000,
    ): List<Pair<ServerConfig, Long>> = coroutineScope {
        if (servers.isEmpty()) return@coroutineScope emptyList()

        val results = servers.map { server ->
            async(Dispatchers.IO) {
                val latency = measureLatency(server.address, server.port, timeoutMs)
                server to latency
            }
        }.awaitAll()

        val alive = results.filter { it.second >= 0 }
            .sortedBy { it.second }

        Log.d("MyVPN/ServerPool", "Ranked: ${alive.size}/${servers.size} alive")
        alive
    }

    private fun measureLatency(host: String, port: Int, timeoutMs: Int): Long {
        return try {
            val start = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        } catch (e: Exception) {
            -1L
        }
    }
}
