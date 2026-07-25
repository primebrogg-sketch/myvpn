package com.example.myvpn.vpn

import com.example.myvpn.data.Protocol
import com.example.myvpn.data.ServerConfig
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfigBuilder {

    fun build(servers: List<ServerConfig>): String {
        val outbounds = JSONArray()

        servers.forEachIndexed { i, s ->
            val tag = "proxy-$i"
            val ob = when (s.protocol) {
                Protocol.VLESS_REALITY -> vlessOutbound(s, tag)
                Protocol.TROJAN -> trojanOutbound(s, tag)
                else -> vlessOutbound(s, tag)
            } ?: return@forEachIndexed
            outbounds.put(ob)
        }

        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        outbounds.put(JSONObject().put("type", "block").put("tag", "block"))
        outbounds.put(JSONObject().put("type", "dns").put("tag", "dns-out"))

        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("level", "warn")
                put("timestamp", true)
            })
            put("dns", JSONObject().apply {
                put("servers", JSONArray().put(JSONObject().apply {
                    put("tag", "dns-remote")
                    put("address", "local")
                    put("detour", "direct")
                }))
                put("final", "dns-remote")
                put("strategy", "ipv4_only")
            })
            put("inbounds", JSONArray().put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("interface_name", "tun0")
                put("inet4_address", JSONArray().put("172.19.0.1/30"))
                put("mtu", 1500)
                put("auto_route", true)
                put("strict_route", true)
                put("stack", "system")
                put("sniff", true)
            }))
            put("outbounds", outbounds)
            put("route", JSONObject().apply {
                put("rules", JSONArray().apply {
                    put(JSONObject().apply {
                        put("protocol", "dns")
                        put("outbound", "dns-out")
                    })
                    put(JSONObject().apply {
                        put("ip_cidr", JSONArray().apply {
                            put("10.0.0.0/8")
                            put("172.16.0.0/12")
                            put("192.168.0.0/16")
                            put("100.64.0.0/10")
                            put("169.254.0.0/16")
                            put("224.0.0.0/4")
                            put("255.255.255.255/32")
                        })
                        put("outbound", "direct")
                    })
                })
                put("final", "proxy-0")
                put("auto_detect_interface", true)
                put("override_android_vpn", true)
            })
        }.toString()
    }

    private fun vlessOutbound(server: ServerConfig, tag: String): JSONObject {
        val sec = queryParam(server.raw, "security") ?: "none"
        val tType = queryParam(server.raw, "type") ?: "tcp"
        val isReality = sec == "reality"

        return JSONObject().apply {
            put("type", "vless")
            put("tag", tag)
            put("server", server.address)
            put("server_port", server.port)
            put("uuid", server.uuid.ifEmpty { parseUuid(server.raw) })

            if (isReality) {
                put("flow", server.flow.ifEmpty { queryParam(server.raw, "flow") ?: "xtls-rprx-vision" })
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", server.sni.ifEmpty { getSni(server.raw).ifEmpty { server.address } })
                    put("reality", JSONObject().apply {
                        put("enabled", true)
                        put("public_key", server.publicKey.ifEmpty { queryParam(server.raw, "pbk") ?: "" })
                        put("short_id", server.shortId.ifEmpty { queryParam(server.raw, "sid") ?: "" })
                    })
                })
            } else if (sec == "tls") {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", server.sni.ifEmpty { server.address })
                })
            }

            val wsHost = queryParam(server.raw, "host") ?: ""
            if (tType == "ws") {
                put("transport", JSONObject().apply {
                    put("type", "ws")
                    put("path", queryParam(server.raw, "path") ?: "/")
                    put("headers", JSONObject().put("Host", JSONArray().put(wsHost)))
                })
            }
        }
    }

    private fun trojanOutbound(server: ServerConfig, tag: String): JSONObject {
        val sec = queryParam(server.raw, "security") ?: "tls"
        val tType = queryParam(server.raw, "type") ?: "tcp"
        val tlsEnabled = sec == "tls" || sec == "reality"

        return JSONObject().apply {
            put("type", "trojan")
            put("tag", tag)
            put("server", server.address)
            put("server_port", server.port)
            put("password", server.password.ifEmpty { parsePassword(server.raw) })

            if (tlsEnabled) {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", server.sni.ifEmpty { getSni(server.raw).ifEmpty { server.address } })
                    put("insecure", queryParam(server.raw, "insecure")?.toIntOrNull() == 1)
                })
            }

            val trojanHost = queryParam(server.raw, "host") ?: ""
            if (tType == "xhttp" || tType == "splithttp") {
                put("transport", JSONObject().apply {
                    put("type", "splithttp")
                    put("path", queryParam(server.raw, "path") ?: "/")
                    put("headers", JSONObject().put("Host", JSONArray().put(trojanHost)))
                })
            } else if (tType == "ws") {
                put("transport", JSONObject().apply {
                    put("type", "ws")
                    put("path", queryParam(server.raw, "path") ?: "/")
                    put("headers", JSONObject().put("Host", JSONArray().put(trojanHost)))
                })
            }
        }
    }

    private fun parseUuid(raw: String): String {
        return try { java.net.URI(raw).userInfo ?: "" } catch (e: Exception) { "" }
    }

    private fun parsePassword(raw: String): String {
        return try { java.net.URI(raw).userInfo ?: "" } catch (e: Exception) { "" }
    }

    private fun queryParam(raw: String, param: String): String? {
        val query = try { java.net.URI(raw).query ?: return null } catch (e: Exception) { return null }
        return query.split("&").mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2 && parts[0] == param) java.net.URLDecoder.decode(parts[1], "UTF-8") else null
        }.firstOrNull()
    }

    private fun getSni(raw: String): String = queryParam(raw, "sni") ?: ""
}
