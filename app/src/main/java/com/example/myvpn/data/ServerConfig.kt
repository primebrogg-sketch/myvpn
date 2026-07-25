package com.example.myvpn.data

import java.util.UUID

enum class Protocol {
    VLESS_REALITY,
    HYSTERIA2,
    SHADOWSOCKS,
    TROJAN,
    VMESS,
    TUIC,
    WIREGUARD,
    UNKNOWN
}

data class ServerConfig(
    val raw: String,
    val address: String,
    val port: Int,
    val name: String,
    val protocol: Protocol,

    // VLESS / VMESS / Trojan / Reality
    val uuid: String = "",
    val alterId: Int = 0,
    val security: String = "",
    val network: String = "",
    val tls: String = "",
    val sni: String = "",
    val fingerprint: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val flow: String = "",
    val alpn: String = "",
    val allowInsecure: Boolean = false,

    // Shadowsocks
    val method: String = "",
    val password: String = "",
    val plugin: String = "",

    // Hysteria2 / TUIC
    val obfs: String = "",
    val obfsPassword: String = "",
    val congestionController: String = "",

    // WireGuard
    val privateKey: String = "",
    val peerPublicKey: String = "",
    val presharedKey: String = "",
    val localAddress: String = "",
    val dns: String = "",
    val mtu: Int = 1280,
    val keepalive: Int = 25,

    val id: String = UUID.randomUUID().toString(),
)