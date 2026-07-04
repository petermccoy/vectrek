package com.vectrek.game.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.InetAddress

/**
 * Local-network game discovery via Android Network Service Discovery (mDNS).
 * The host registers a `_vectrek._udp.` service; joiners browse for them.
 * Both phones need to be on the same Wi-Fi network.
 */
class NsdHelper(context: Context) {

    data class DiscoveredGame(val name: String, val host: InetAddress, val port: Int)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null

    // NsdManager only allows one resolve at a time; queue the rest.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var onFound: ((DiscoveredGame) -> Unit)? = null

    fun register(gameName: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "VecTrek $gameName"
            serviceType = Protocol.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, err: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, err: Int) {}
        }
        regListener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        regListener?.let {
            try {
                nsd.unregisterService(it)
            } catch (_: Exception) {
            }
        }
        regListener = null
    }

    fun startDiscovery(onGameFound: (DiscoveredGame) -> Unit) {
        onFound = onGameFound
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, err: Int) {}
            override fun onStopDiscoveryFailed(type: String, err: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith("_vectrek")) return
                synchronized(resolveQueue) {
                    resolveQueue += info
                }
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        discListener = listener
        nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolveNext() {
        val next: NsdServiceInfo
        synchronized(resolveQueue) {
            if (resolving) return
            next = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
        }
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = info.host
                if (host != null) {
                    val game = DiscoveredGame(info.serviceName, host, info.port)
                    main.post { onFound?.invoke(game) }
                }
                doneResolving()
            }

            override fun onResolveFailed(info: NsdServiceInfo, err: Int) {
                doneResolving()
            }
        })
    }

    private fun doneResolving() {
        synchronized(resolveQueue) { resolving = false }
        resolveNext()
    }

    fun stopDiscovery() {
        onFound = null
        discListener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        discListener = null
    }
}
