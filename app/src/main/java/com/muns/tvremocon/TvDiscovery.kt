package com.muns.tvremocon

import android.content.Context
import android.net.wifi.WifiManager
import java.util.concurrent.Executors

/**
 * 안드로이드용 SSDP 검색 래퍼: 백그라운드 스레드 + 검색 동안만 MulticastLock.
 * 검색 로직은 SsdpSearch (WEBOS_API.md 6절, 실TV 확인).
 */
class TvDiscovery(context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    fun start(onFound: (TvInfo) -> Unit, onDone: (Int, String?) -> Unit) {
        if (running) return
        running = true
        executor.execute {
            val lock = wifi.createMulticastLock("tvremocon-ssdp").apply { setReferenceCounted(false) }
            var count = 0
            var error: String? = null
            try {
                lock.acquire()
                count = SsdpSearch().search(3000, onFound)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                try { lock.release() } catch (ignored: Exception) {}
                running = false
            }
            onDone(count, error)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
