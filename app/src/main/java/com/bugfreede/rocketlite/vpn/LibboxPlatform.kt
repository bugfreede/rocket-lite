package com.bugfreede.rocketlite.vpn

import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import libbox.InterfaceUpdateListener
import libbox.NetworkInterfaceIterator
import libbox.Notification
import libbox.PlatformInterface
import libbox.RoutePrefix
import libbox.RoutePrefixIterator
import libbox.StringIterator
import libbox.TunOptions
import libbox.WIFIState
import java.net.InetAddress
import libbox.NetworkInterface as BoxInterface
import java.net.NetworkInterface as JavaNetInterface

private class StringListIterator(private val items: List<String>) : StringIterator {
    private var index = 0
    override fun hasNext(): Boolean = index < items.size
    override fun len(): Int = items.size
    override fun next(): String = items[index++]
}

private class InterfaceListIterator(private val items: List<BoxInterface>) : NetworkInterfaceIterator {
    private var index = 0
    override fun hasNext(): Boolean = index < items.size
    override fun next(): BoxInterface = items[index++]
}

private fun StringIterator?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>()
    while (hasNext()) out.add(next())
    return out
}

private fun RoutePrefixIterator?.toList(): List<RoutePrefix> {
    if (this == null) return emptyList()
    val out = ArrayList<RoutePrefix>()
    while (hasNext()) out.add(next())
    return out
}

/**
 * Android side of the libbox contract, following the same shape as the
 * official sing-box for Android client: the core asks us to open the TUN,
 * protect its sockets, and report network interfaces.
 */
class LibboxPlatform(private val service: RocketVpnService) : PlatformInterface {

    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    private var tunFd: ParcelFileDescriptor? = null
    private var listener: InterfaceUpdateListener? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun openTun(options: TunOptions): Int {
        val builder = service.Builder()
        builder.setSession("RocketLite")
        builder.setMtu(options.mtu)

        options.inet4Address.toList().forEach { builder.addAddress(it.address(), it.prefix()) }
        options.inet6Address.toList().forEach { builder.addAddress(it.address(), it.prefix()) }

        try {
            val dns = options.dnsServerAddress?.value
            if (!dns.isNullOrBlank()) builder.addDnsServer(dns)
        } catch (_: Exception) {
        }

        if (options.autoRoute) {
            val routes4 = options.inet4RouteAddress.toList()
            if (routes4.isEmpty()) builder.addRoute("0.0.0.0", 0)
            else routes4.forEach { builder.addRoute(it.address(), it.prefix()) }
            val routes6 = options.inet6RouteAddress.toList()
            if (routes6.isEmpty()) {
                if (options.inet6Address.toList().isNotEmpty()) builder.addRoute("::", 0)
            } else {
                routes6.forEach { builder.addRoute(it.address(), it.prefix()) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                options.inet4RouteExcludeAddress.toList().forEach { prefix ->
                    try {
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(prefix.address()), prefix.prefix()))
                    } catch (_: Exception) {
                    }
                }
                options.inet6RouteExcludeAddress.toList().forEach { prefix ->
                    try {
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(prefix.address()), prefix.prefix()))
                    } catch (_: Exception) {
                    }
                }
            }
        }

        options.includePackage.toStringList().forEach { pkg ->
            try {
                builder.addAllowedApplication(pkg)
            } catch (_: Exception) {
            }
        }
        options.excludePackage.toStringList().forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: Exception) {
            }
        }

        val pfd = builder.establish() ?: throw Exception("VpnService establish() 返回 null")
        tunFd = pfd
        return pfd.detachFd()
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!service.protect(fd)) throw Exception("protect($fd) 失败")
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun includeAllNetworks(): Boolean = false

    override fun underNetworkExtension(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): Int = -1

    override fun packageNameByUid(uid: Int): String {
        val packages = service.packageManager.getPackagesForUid(uid)
        return packages?.firstOrNull() ?: throw Exception("找不到 uid=$uid 的包名")
    }

    override fun uidByPackageName(packageName: String): Int {
        return try {
            service.packageManager.getApplicationInfo(packageName, 0).uid
        } catch (e: Exception) {
            throw Exception("找不到包 $packageName 的 uid")
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val result = ArrayList<BoxInterface>()
        val defaultDns = defaultDnsServers()
        val all = try {
            JavaNetInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        for (ni in all) {
            if (!ni.isUp) continue
            val item = BoxInterface()
            item.index = ni.index
            item.mtu = ni.mtu
            item.name = ni.name
            var flags = 0
            if (ni.isUp) flags = flags or 0x1
            if (ni.isLoopback) flags = flags or 0x8
            if (ni.isPointToPoint) flags = flags or 0x10
            if (ni.supportsMulticast()) flags = flags or 0x8000
            item.flags = flags
            item.type = when {
                ni.name.startsWith("wlan") -> libbox.Libbox.InterfaceTypeWIFI
                ni.name.startsWith("rmnet") || ni.name.startsWith("ccmni") ->
                    libbox.Libbox.InterfaceTypeCellular
                ni.name.startsWith("eth") -> libbox.Libbox.InterfaceTypeEthernet
                else -> libbox.Libbox.InterfaceTypeOther
            }
            val addresses = ArrayList<String>()
            for (addr in ni.interfaceAddresses) {
                val host = addr.address.hostAddress ?: continue
                addresses.add("$host/${addr.networkPrefixLength}")
            }
            item.addresses = StringListIterator(addresses)
            item.dnsServer = StringListIterator(if (ni.isLoopback) emptyList() else defaultDns)
            item.metered = false
            result.add(item)
        }
        return InterfaceListIterator(result)
    }

    private fun defaultDnsServers(): List<String> {
        return try {
            val network = connectivity.activeNetwork ?: return emptyList()
            connectivity.getLinkProperties(network)?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        this.listener = listener
        notifyDefaultInterface()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notifyDefaultInterface()
            override fun onLost(network: Network) = notifyDefaultInterface()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                notifyDefaultInterface()
        }
        callback = cb
        try {
            connectivity.registerDefaultNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        callback?.let {
            try {
                connectivity.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        this.listener = null
    }

    private fun notifyDefaultInterface() {
        val current = listener ?: return
        try {
            val network = connectivity.activeNetwork ?: return
            val link = connectivity.getLinkProperties(network) ?: return
            val name = link.interfaceName ?: return
            val index = try {
                JavaNetInterface.getByName(name)?.index ?: -1
            } catch (_: Exception) {
                -1
            }
            val caps = connectivity.getNetworkCapabilities(network)
            val metered = caps?.let {
                !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } ?: false
            current.updateDefaultInterface(name, index, metered, false)
        } catch (_: Exception) {
        }
    }

    override fun readWIFIState(): WIFIState? {
        return try {
            val wifi = service.getSystemService(WifiManager::class.java)
            val info = wifi.connectionInfo ?: return null
            val ssid = info.ssid?.trim('"') ?: return null
            if (ssid.isBlank() || ssid == "<unknown ssid>") null
            else WIFIState(ssid, info.bssid ?: "")
        } catch (_: Exception) {
            null
        }
    }

    override fun sendNotification(notification: Notification) {
        Log.i("RocketLite", "core notification: ${notification.title} ${notification.body}")
    }

    override fun clearDNSCache() {
    }

    override fun writeLog(message: String) {
        Log.d("RocketLiteCore", message)
    }
}
