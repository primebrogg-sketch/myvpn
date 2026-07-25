package com.example.myvpn.data

import android.util.Base64
import java.net.URLDecoder

object SubscriptionParser {

    fun parseSubscriptionBody(body: String): List<ServerConfig> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        val decodedText = tryDecodeBase64(trimmed)
        if (decodedText != null) {
            return parseLines(decodedText)
        }

        val lines = parseLines(trimmed)
        if (lines.isNotEmpty()) return lines

        return trimmed.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val decoded = tryDecodeBase64(line)
                if (decoded != null) parseLines(decoded).firstOrNull()
                else parseLine(line)
            }
    }

    private fun parseLines(text: String): List<ServerConfig> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("//") && !it.startsWith(";") }
            .mapNotNull { parseLine(it) }
    }

    private fun tryDecodeBase64(text: String): String? {
        val clean = text.replace(Regex("\\s+"), "")
        if (clean.length < 16) return null

        val isStdB64 = clean.matches(Regex("^[A-Za-z0-9+/]*={0,2}$"))
        val isUrlB64 = clean.matches(Regex("^[A-Za-z0-9_-]*={0,2}$"))

        if (!isStdB64 && !isUrlB64) return null

        val flags = if (isUrlB64) Base64.URL_SAFE or Base64.NO_PADDING else Base64.DEFAULT
        return try {
            val decoded = String(Base64.decode(clean, flags))
            if (decoded.any { it in " \t\n\r" } || decoded.contains("://")) decoded else null
        } catch (e: Exception) {
            null
        }
    }

    fun parseLine(line: String): ServerConfig? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val protocol = when {
            trimmed.startsWith("vless://") -> Protocol.VLESS_REALITY
            trimmed.startsWith("vmess://") -> Protocol.VMESS
            trimmed.startsWith("ss://") || trimmed.startsWith("shadowsocks://") -> Protocol.SHADOWSOCKS
            trimmed.startsWith("trojan://") -> Protocol.TROJAN
            trimmed.startsWith("hy2://") || trimmed.startsWith("hysteria2://") || trimmed.startsWith("hysteria://") -> Protocol.HYSTERIA2
            trimmed.startsWith("tuic://") -> Protocol.TUIC
            trimmed.startsWith("wireguard://") || trimmed.startsWith("wg://") -> Protocol.WIREGUARD
            else -> null
        } ?: return null

        return try {
            when (protocol) {
                Protocol.VMESS -> parseVmess(trimmed, protocol)
                else -> parseGeneric(trimmed, protocol)
            }
        } catch (e: Exception) { null }
    }

    private fun parseGeneric(raw: String, protocol: Protocol): ServerConfig {
        val afterScheme = raw.substringAfter("://")
        val hostPortPart = afterScheme
            .substringAfter("@", afterScheme)
            .substringBefore("?")
            .substringBefore("#")

        val host = hostPortPart.substringBeforeLast(":")
        val port = hostPortPart.substringAfterLast(":").toIntOrNull() ?: 443

        val name = try {
            URLDecoder.decode(raw.substringAfterLast("#", "Unknown"), "UTF-8")
        } catch (e: Exception) {
            "Unknown"
        }.ifEmpty { host }

        return ServerConfig(
            raw = raw,
            address = host,
            port = port,
            name = name,
            protocol = protocol,
        )
    }

    private fun parseVmess(raw: String, protocol: Protocol): ServerConfig? {
        val b64 = raw.substringAfter("vmess://")
        val decoded = try {
            String(Base64.decode(b64, Base64.DEFAULT))
        } catch (e: Exception) {
            try {
                String(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING))
            } catch (e2: Exception) { return null }
        }

        val json = try { org.json.JSONObject(decoded) } catch (e: Exception) { return null }

        val name = json.optString("ps", "").ifEmpty {
            "${json.optString("add", json.optString("host", "server"))}:${json.optInt("port", 443)}"
        }

        val address = json.optString("add", "").ifEmpty { json.optString("host", "") }

        return ServerConfig(
            raw = raw,
            address = address,
            port = json.optInt("port", 443),
            name = name,
            protocol = protocol,
            uuid = json.optString("id", ""),
            alterId = json.optInt("aid", 0),
            security = json.optString("scy", "auto"),
            network = json.optString("net", "tcp"),
            tls = json.optString("tls", ""),
            sni = json.optString("sni", ""),
            fingerprint = json.optString("fp", ""),
        )
    }
}
