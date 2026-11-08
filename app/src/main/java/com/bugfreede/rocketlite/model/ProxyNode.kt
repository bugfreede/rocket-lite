package com.bugfreede.rocketlite.model

import java.util.UUID

enum class NodeType { SHADOWSOCKS, VMESS, VLESS, TROJAN }

/**
 * One proxy node. [params] holds protocol-specific fields using the
 * sing-box outbound vocabulary where possible, so phase 2 can translate
 * a node into a core config without re-parsing the share link.
 */
data class ProxyNode(
    val id: String = UUID.randomUUID().toString(),
    val type: NodeType,
    val name: String,
    val host: String,
    val port: Int,
    val rawLink: String,
    val params: Map<String, String> = emptyMap(),
) {
    val endpoint: String get() = "$host:$port"
}
