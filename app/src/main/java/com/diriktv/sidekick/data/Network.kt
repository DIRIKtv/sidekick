package com.diriktv.sidekick.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network as AndroidNetwork
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Watches the TV's internet connection so screens that failed while it was down can try again
 * on their own as soon as it's back, instead of waiting for the viewer.
 */
object Network {
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread when the connection comes back after being lost. */
    val onReconnected = CopyOnWriteArraySet<() -> Unit>()

    /** False while Android reports no working connection. */
    @Volatile var online = true
        private set

    fun init(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        online = runCatching {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true)
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                private val up = HashSet<AndroidNetwork>()

                override fun onAvailable(network: AndroidNetwork) {
                    val wasOnline = synchronized(up) { up.isNotEmpty().also { up += network } } || online
                    online = true
                    // Give DHCP and DNS a moment before everyone retries at once.
                    if (!wasOnline) main.postDelayed({ if (online) onReconnected.forEach { it() } }, 1_500)
                }

                override fun onLost(network: AndroidNetwork) {
                    val none = synchronized(up) { up -= network; up.isEmpty() }
                    if (none) online = false
                }
            })
        }
    }
}
