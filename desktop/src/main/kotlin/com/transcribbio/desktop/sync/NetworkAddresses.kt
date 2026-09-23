package com.transcribbio.desktop.sync

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Finds the address phones on the same network should use to reach this PC.
 *
 * The old approach took the first *site-local* (192.168.x / 10.x / 172.16-31.x) address,
 * which fails in two common cases:
 * - networks that hand out public addresses (e.g. eduroam gives 147.175.x.x) → nothing
 *   matched and the server advertised itself on 127.0.0.1, unreachable from any phone;
 * - PCs with WSL / Hyper-V / VirtualBox / VPN adapters, whose private addresses Java does
 *   not flag as virtual → it could advertise an address the phone can't reach.
 */
object NetworkAddresses {
    private val VIRTUAL_HINTS = listOf(
        "vethernet", "hyper-v", "wsl", "virtualbox", "vmware", "vpn", "tap-", "tap ", "tun",
        "wireguard", "tailscale", "zerotier", "hamachi", "docker", "npcap", "loopback", "bluetooth",
    )
    private val VPN_HINTS = listOf("vpn", "tap-", "tap ", "tun", "wireguard", "tailscale", "zerotier")

    /** Best address for phones to reach this PC, or null when there's no network at all. */
    fun primaryIpv4(): String? = routeIpv4() ?: candidates().firstOrNull()?.first

    /** All usable IPv4 addresses on physical-looking adapters: (ip, adapter name), private first. */
    fun candidates(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !looksVirtual(it) }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { it to nif.displayName }
            }
            .sortedBy { (addr, _) -> if (addr.isSiteLocalAddress) 0 else 1 }
            .map { (addr, name) -> addr.hostAddress to name }
    }.getOrDefault(emptyList())

    /** True if a VPN-like adapter is up with an address (it may block LAN access from phones). */
    fun vpnActive(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { nif ->
            nif.isUp && !nif.isLoopback && nif.inetAddresses.toList().any { it is Inet4Address } &&
                VPN_HINTS.any { it in "${nif.displayName} ${nif.name}".lowercase() }
        }
    }.getOrDefault(false)

    fun isPrivate(ip: String): Boolean =
        runCatching { (InetAddress.getByName(ip) as? Inet4Address)?.isSiteLocalAddress == true }.getOrDefault(false)

    /** The address the OS routes outbound traffic from — i.e. the real Wi-Fi/Ethernet adapter.
     *  "Connecting" a UDP socket only selects a route; no packet is sent. Ignored when the route
     *  goes through a VPN/virtual adapter, since phones on the LAN can't reach that address. */
    private fun routeIpv4(): String? = runCatching {
        DatagramSocket().use { s ->
            s.connect(InetAddress.getByName("8.8.8.8"), 53)
            val addr = s.localAddress
            if (addr !is Inet4Address || addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress) {
                return@use null
            }
            val nif = NetworkInterface.getByInetAddress(addr)
            if (nif != null && looksVirtual(nif)) null else addr.hostAddress
        }
    }.getOrNull()

    private fun looksVirtual(nif: NetworkInterface): Boolean {
        val n = "${nif.displayName} ${nif.name}".lowercase()
        return VIRTUAL_HINTS.any { it in n }
    }
}
