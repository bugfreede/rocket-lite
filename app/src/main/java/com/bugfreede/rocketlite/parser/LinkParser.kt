package com.bugfreede.rocketlite.parser

import com.bugfreede.rocketlite.model.NodeType
import com.bugfreede.rocketlite.model.ProxyNode
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

/** Parses ss://, vmess://, vless:// and trojan:// share links, plus base64 subscription bodies. */
object LinkParser {

    fun parse(raw: String): ProxyNode? {
        val link = raw.trim()
        return try {
            when {
                link.startsWith("ss://") -> parseShadowsocks(link)
                link.startsWith("vmess://") -> parseVmess(link)
                link.startsWith("vless://") -> parseVless(link)
                link.startsWith("trojan://") -> parseTrojan(link)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** A subscription body is either plain newline-separated links or one base64 blob of them. */
    fun parseMany(body: String): List<ProxyNode> {
        val text = body.trim()
        val decoded = if (looksLikeBase64(text)) decodeBase64(text) ?: text else text
        return decoded.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parse(it) }
            .toList()
    }

    // ---- Shadowsocks (SIP002 + legacy) ----

    private fun parseShadowsocks(link: String): ProxyNode? {
        val rest = link.removePrefix("ss://")
        val name = rest.substringAfter('#', "").urlDecode()
        val main = rest.substringBefore('#')
        val params = mutableMapOf<String, String>()

        val hostPort: String
        if ('@' in main) {
            val userInfo = main.substringBefore('@')
            hostPort = main.substringAfter('@').substringBefore('?').substringBefore('/')
            val decoded = decodeBase64(userInfo) ?: userInfo.urlDecode()
            params["method"] = decoded.substringBefore(':')
            params["password"] = decoded.substringAfter(':', "")
            val query = main.substringAfter('?', "")
            query.split('&').forEach { kv ->
                if ('=' in kv) params[kv.substringBefore('=')] = kv.substringAfter('=').urlDecode()
            }
        } else {
            val decoded = decodeBase64(main.substringBefore('?')) ?: return null
            val creds = decoded.substringBefore('@')
            hostPort = decoded.substringAfter('@', "")
            params["method"] = creds.substringBefore(':')
            params["password"] = creds.substringAfter(':', "")
        }
        val (host, port) = splitHostPort(hostPort) ?: return null
        return ProxyNode(
            type = NodeType.SHADOWSOCKS,
            name = name.ifBlank { host },
            host = host, port = port, rawLink = link, params = params,
        )
    }

    // ---- VMess (base64 JSON) ----

    private fun parseVmess(link: String): ProxyNode? {
        val json = decodeBase64(link.removePrefix("vmess://")) ?: return null
        val o = JSONObject(json)
        val host = o.optString("add")
        val port = o.optString("port").toIntOrNull() ?: return null
        if (host.isBlank()) return null
        val params = mutableMapOf(
            "uuid" to o.optString("id"),
            "alter_id" to o.optString("aid", "0"),
            "security" to o.optString("scy", "auto"),
            "network" to o.optString("net", "tcp"),
            "ws_host" to o.optString("host"),
            "ws_path" to o.optString("path"),
            "tls" to o.optString("tls"),
            "sni" to o.optString("sni"),
        )
        return ProxyNode(
            type = NodeType.VMESS,
            name = o.optString("ps").ifBlank { host },
            host = host, port = port, rawLink = link, params = params,
        )
    }

    // ---- VLESS ----

    private fun parseVless(link: String): ProxyNode? {
        val rest = link.removePrefix("vless://")
        val name = rest.substringAfter('#', "").urlDecode()
        val main = rest.substringBefore('#')
        val uuid = main.substringBefore('@')
        val afterAt = main.substringAfter('@', "")
        val hostPort = afterAt.substringBefore('?').substringBefore('/')
        val (host, port) = splitHostPort(hostPort) ?: return null
        val params = mutableMapOf("uuid" to uuid)
        afterAt.substringAfter('?', "").split('&').forEach { kv ->
            if ('=' in kv) params[kv.substringBefore('=')] = kv.substringAfter('=').urlDecode()
        }
        return ProxyNode(
            type = NodeType.VLESS,
            name = name.ifBlank { host },
            host = host, port = port, rawLink = link, params = params,
        )
    }

    // ---- Trojan ----

    private fun parseTrojan(link: String): ProxyNode? {
        val rest = link.removePrefix("trojan://")
        val name = rest.substringAfter('#', "").urlDecode()
        val main = rest.substringBefore('#')
        val password = main.substringBefore('@').urlDecode()
        val afterAt = main.substringAfter('@', "")
        val hostPort = afterAt.substringBefore('?').substringBefore('/')
        val (host, port) = splitHostPort(hostPort) ?: return null
        val params = mutableMapOf("password" to password)
        afterAt.substringAfter('?', "").split('&').forEach { kv ->
            if ('=' in kv) params[kv.substringBefore('=')] = kv.substringAfter('=').urlDecode()
        }
        return ProxyNode(
            type = NodeType.TROJAN,
            name = name.ifBlank { host },
            host = host, port = port, rawLink = link, params = params,
        )
    }

    // ---- helpers ----

    private fun splitHostPort(s: String): Pair<String, Int>? {
        val clean = s.trim().trim('/')
        if (clean.startsWith('[')) { // [ipv6]:port
            val end = clean.indexOf(']')
            if (end < 0) return null
            val host = clean.substring(1, end)
            val port = clean.substring(end + 1).removePrefix(":").toIntOrNull() ?: return null
            return host to port
        }
        val host = clean.substringBeforeLast(':')
        val port = clean.substringAfterLast(':').toIntOrNull() ?: return null
        if (host.isBlank()) return null
        return host to port
    }

    private fun looksLikeBase64(s: String): Boolean {
        if (s.contains("://")) return false
        val compact = s.filterNot { it.isWhitespace() }
        return compact.length % 4 != 1 && compact.all { it.isLetterOrDigit() || it in "+/=_-" }
    }

    fun decodeBase64(s: String): String? {
        val cleaned = s.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return try {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun String.urlDecode(): String =
        try { URLDecoder.decode(this, "UTF-8") } catch (e: Exception) { this }
}
