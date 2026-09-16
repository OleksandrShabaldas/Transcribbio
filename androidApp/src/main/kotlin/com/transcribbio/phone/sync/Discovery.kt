package com.transcribbio.phone.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.transcribbio.shared.sync.SyncProtocol
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class DesktopEndpoint(
    val host: String,
    val port: Int,
    val name: String,
    val deviceId: String,
)

/** Discovers the desktop hub advertised over mDNS (`_transcribbio._tcp`). */
class Discovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun discover(timeoutMs: Long = 6000): DesktopEndpoint? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            var discoveryListener: NsdManager.DiscoveryListener? = null

            fun finish(result: DesktopEndpoint?) {
                if (resumed.compareAndSet(false, true)) {
                    runCatching { discoveryListener?.let { nsd.stopServiceDiscovery(it) } }
                    if (cont.isActive) cont.resume(result)
                }
            }

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) { /* keep waiting */ }

                @Suppress("DEPRECATION")
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress ?: return
                    val attrs = serviceInfo.attributes ?: emptyMap()
                    val name = attrs[SyncProtocol.TXT_NAME]?.let { String(it) } ?: serviceInfo.serviceName
                    val deviceId = attrs[SyncProtocol.TXT_DEVICE_ID]?.let { String(it) } ?: ""
                    finish(DesktopEndpoint(host, serviceInfo.port, name, deviceId))
                }
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = finish(null)
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}

                @Suppress("DEPRECATION")
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType?.contains("transcribbio", ignoreCase = true) == true ||
                        serviceInfo.serviceName.contains("Transcribbio", ignoreCase = true)
                    ) {
                        runCatching { nsd.resolveService(serviceInfo, resolveListener) }
                    }
                }
            }

            runCatching {
                nsd.discoverServices(SyncProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            }.onFailure { finish(null) }

            cont.invokeOnCancellation {
                if (resumed.compareAndSet(false, true)) {
                    runCatching { discoveryListener?.let { nsd.stopServiceDiscovery(it) } }
                }
            }
        }
    }
}
