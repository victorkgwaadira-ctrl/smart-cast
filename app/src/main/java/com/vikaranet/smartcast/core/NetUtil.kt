package com.vikaranet.smartcast.core

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /**
     * The phone's address on the local network. Prefers Wi-Fi, then the phone's own
     * hotspot (so it works with no router at all), then any private address.
     */
    fun localIpv4(): Inet4Address? {
        val candidates = ArrayList<Pair<Int, Inet4Address>>()
        val ifaces = try { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        for (nif in ifaces) {
            val up = try { nif.isUp && !nif.isLoopback } catch (_: Exception) { false }
            if (!up) continue
            val name = nif.name.lowercase()
            for (addr in nif.inetAddresses.toList()) {
                if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                val rank = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("swlan") || name.startsWith("ap") || name.contains("softap") -> 1
                    name.startsWith("eth") -> 2
                    addr.isSiteLocalAddress && !name.startsWith("rmnet") && !name.startsWith("ccmni") && !name.startsWith("tun") -> 3
                    else -> continue
                }
                candidates.add(rank to addr)
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }
}
