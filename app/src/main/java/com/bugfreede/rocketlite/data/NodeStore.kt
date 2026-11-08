package com.bugfreede.rocketlite.data

import android.content.Context
import android.content.SharedPreferences
import com.bugfreede.rocketlite.model.NodeType
import com.bugfreede.rocketlite.model.ProxyNode
import org.json.JSONArray
import org.json.JSONObject

/** Persists nodes as a JSON array in SharedPreferences. Small, synchronous, no extra deps. */
object NodeStore {
    private const val PREFS = "rocketlite_nodes"
    private const val KEY = "nodes_json"

    private var prefs: SharedPreferences? = null
    private var cache: MutableList<ProxyNode>? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun load(): List<ProxyNode> {
        cache?.let { return it.toList() }
        val raw = prefs?.getString(KEY, null)
        val list = mutableListOf<ProxyNode>()
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    list.add(fromJson(arr.getJSONObject(i)))
                }
            } catch (e: Exception) {
                // Corrupt storage should not crash the app; start empty.
            }
        }
        cache = list
        return list.toList()
    }

    /** Adds nodes, de-duplicated by raw link. Returns how many were actually added. */
    @Synchronized
    fun add(nodes: List<ProxyNode>): Int {
        val current = load().toMutableList()
        val known = current.map { it.rawLink }.toMutableSet()
        var added = 0
        for (node in nodes) {
            if (known.add(node.rawLink)) {
                current.add(node)
                added++
            }
        }
        if (added > 0) save(current)
        cache = current
        return added
    }

    @Synchronized
    fun remove(id: String) {
        val current = load().toMutableList()
        if (current.removeAll { it.id == id }) {
            save(current)
            cache = current
        }
    }

    @Synchronized
    fun find(id: String?): ProxyNode? = id?.let { wanted -> load().firstOrNull { it.id == wanted } }

    private fun save(nodes: List<ProxyNode>) {
        val arr = JSONArray()
        nodes.forEach { arr.put(toJson(it)) }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
    }

    private fun toJson(node: ProxyNode): JSONObject {
        val params = JSONObject()
        node.params.forEach { (k, v) -> params.put(k, v) }
        return JSONObject()
            .put("id", node.id)
            .put("type", node.type.name)
            .put("name", node.name)
            .put("host", node.host)
            .put("port", node.port)
            .put("raw", node.rawLink)
            .put("params", params)
    }

    private fun fromJson(o: JSONObject): ProxyNode {
        val paramsObj = o.optJSONObject("params")
        val params = mutableMapOf<String, String>()
        if (paramsObj != null) {
            val keys = paramsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                params[k] = paramsObj.optString(k)
            }
        }
        return ProxyNode(
            id = o.optString("id"),
            type = try { NodeType.valueOf(o.optString("type")) } catch (e: Exception) { NodeType.SHADOWSOCKS },
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port"),
            rawLink = o.optString("raw"),
            params = params,
        )
    }
}
