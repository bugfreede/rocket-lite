package com.bugfreede.rocketlite.core

import com.bugfreede.rocketlite.model.NodeType
import com.bugfreede.rocketlite.model.ProxyNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Translates a [ProxyNode] into a sing-box configuration.
 * Phase 2 hands this JSON to libbox; keeping the translation pure and
 * separate makes it testable without the core.
 */
object SingBoxConfig {

    fun build(node: ProxyNode): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("level", "info").put("timestamp", true))
        root.put("dns", dns())
        root.put("inbounds", JSONArray().put(tunInbound()))
        root.put("outbounds", JSONArray()
            .put(proxyOutbound(node))
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
            .put(JSONObject().put("type", "block").put("tag", "block"))
            .put(JSONObject().put("type", "dns").put("tag", "dns-out")))
        root.put("route", route())
        return root.toString(2)
    }

    private fun dns(): JSONObject {
        val servers = JSONArray()
            .put(JSONObject().put("tag", "remote").put("address", "tls://8.8.8.8"))
            .put(JSONObject().put("tag", "local").put("address", "223.5.5.5").put("detour", "direct"))
        return JSONObject().put("servers", servers).put("final", "remote")
    }

    private fun tunInbound(): JSONObject = JSONObject()
        .put("type", "tun")
        .put("tag", "tun-in")
        .put("address", JSONArray().put("172.19.0.1/30"))
        .put("auto_route", true)
        .put("strict_route", true)
        .put("stack", "mixed")
        .put("sniff", true)

    private fun route(): JSONObject {
        val rules = JSONArray()
            .put(JSONObject().put("protocol", "dns").put("outbound", "dns-out"))
            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        return JSONObject()
            .put("rules", rules)
            .put("final", "proxy")
            .put("auto_detect_interface", true)
    }

    private fun proxyOutbound(node: ProxyNode): JSONObject {
        val p = node.params
        val o = JSONObject()
            .put("tag", "proxy")
            .put("server", node.host)
            .put("server_port", node.port)
        when (node.type) {
            NodeType.SHADOWSOCKS -> o
                .put("type", "shadowsocks")
                .put("method", p["method"] ?: "aes-256-gcm")
                .put("password", p["password"] ?: "")
            NodeType.VMESS -> {
                o.put("type", "vmess")
                    .put("uuid", p["uuid"] ?: "")
                    .put("alter_id", p["alter_id"]?.toIntOrNull() ?: 0)
                    .put("security", p["security"]?.ifBlank { "auto" } ?: "auto")
                transport(o, network = p["network"], path = p["ws_path"], host = p["ws_host"])
                if (p["tls"] == "tls") o.put("tls", tls(serverName = p["sni"]))
            }
            NodeType.VLESS -> {
                o.put("type", "vless").put("uuid", p["uuid"] ?: "")
                p["flow"]?.takeIf { it.isNotBlank() }?.let { o.put("flow", it) }
                transport(o, network = p["type"] ?: p["network"], path = p["path"], host = p["host"])
                when (p["security"]) {
                    "reality" -> o.put("tls", tls(
                        serverName = p["sni"],
                        realityPublicKey = p["pbk"], realityShortId = p["sid"],
                        fingerprint = p["fp"] ?: "chrome",
                    ))
                    "tls" -> o.put("tls", tls(serverName = p["sni"]))
                }
            }
            NodeType.TROJAN -> {
                o.put("type", "trojan").put("password", p["password"] ?: "")
                transport(o, network = p["type"] ?: p["network"], path = p["path"], host = p["host"])
                o.put("tls", tls(serverName = p["sni"] ?: p["peer"]))
            }
        }
        return o
    }

    private fun transport(o: JSONObject, network: String?, path: String?, host: String?) {
        if (network != "ws") return
        val ws = JSONObject().put("type", "ws")
        path?.takeIf { it.isNotBlank() }?.let { ws.put("path", it) }
        host?.takeIf { it.isNotBlank() }?.let {
            ws.put("headers", JSONObject().put("Host", it))
        }
        o.put("transport", ws)
    }

    private fun tls(
        serverName: String?,
        realityPublicKey: String? = null,
        realityShortId: String? = null,
        fingerprint: String? = null,
    ): JSONObject {
        val t = JSONObject().put("enabled", true)
        serverName?.takeIf { it.isNotBlank() }?.let { t.put("server_name", it) }
        if (realityPublicKey != null) {
            val reality = JSONObject().put("enabled", true).put("public_key", realityPublicKey)
            realityShortId?.takeIf { it.isNotBlank() }?.let { reality.put("short_id", it) }
            t.put("reality", reality)
            t.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint ?: "chrome"))
        }
        return t
    }
}
