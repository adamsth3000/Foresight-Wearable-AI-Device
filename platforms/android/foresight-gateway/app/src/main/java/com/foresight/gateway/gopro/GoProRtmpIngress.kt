package com.foresight.gateway.gopro

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Service-owned, single-publisher RTMP ingress lifecycle for the narrow GW1-A proof. */
class GoProRtmpIngress(
    private val listener: Listener,
    private val addressProvider: () -> String?,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ForesightGoProIngress").apply { isDaemon = true }
    },
    private val backendFactory: ((NativeIngressEvent, String, GoProStreamMetadata?) -> Unit) -> GoProIngressBackend = { callback ->
        NativeRtmpIngress(callback)
    },
) {
    interface Listener {
        fun onGoProIngressChanged(snapshot: GoProIngressSnapshot)
    }

    private var backend: GoProIngressBackend? = null
    @Volatile
    private var requested = false
    @Volatile
    private var snapshot = GoProIngressSnapshot()

    @Synchronized
    fun start(port: Int = DEFAULT_PORT, path: String = DEFAULT_PATH): GoProIngressSnapshot {
        if (requested) return snapshot
        val host = addressProvider()
        if (host == null) {
            update(GoProIngressSnapshot(GoProSourceStatus.ERROR, detail = "No usable LAN IPv4 address found."))
            return snapshot
        }
        requested = true
        val destination = "rtmp://$host:$port/$path"
        update(GoProIngressSnapshot(GoProSourceStatus.LISTENING, destination, detail = "Starting RTMP listener."))
        val activeBackend: GoProIngressBackend = backend ?: backendFactory(::onNativeEvent).also { created ->
            backend = created
        }
        executor.execute {
            runCatching { activeBackend.run("0.0.0.0", port, path) }
                .onFailure { error ->
                    if (requested) update(GoProIngressSnapshot(GoProSourceStatus.ERROR, destination, detail = error.message))
                }
        }
        return snapshot
    }

    @Synchronized
    fun stop(): GoProIngressSnapshot {
        if (!requested && snapshot.status == GoProSourceStatus.STOPPED) return snapshot
        requested = false
        backend?.stop()
        update(GoProIngressSnapshot(GoProSourceStatus.STOPPED, detail = "RTMP listener stopped."))
        return snapshot
    }

    fun snapshot(): GoProIngressSnapshot = snapshot

    fun close() {
        stop()
        (executor as? ExecutorService)?.shutdownNow()
    }

    private fun onNativeEvent(event: NativeIngressEvent, detail: String, metadata: GoProStreamMetadata?) {
        if (!requested && event != NativeIngressEvent.ERROR) return
        val next = when (event) {
            NativeIngressEvent.LISTENING -> snapshot.copy(status = GoProSourceStatus.LISTENING, detail = detail)
            NativeIngressEvent.PUBLISHER_CONNECTED -> snapshot.copy(status = GoProSourceStatus.PUBLISHER_CONNECTED, detail = detail)
            NativeIngressEvent.STREAM_METADATA -> snapshot.copy(status = GoProSourceStatus.LIVE, metadata = metadata, detail = detail)
            NativeIngressEvent.PUBLISHER_DISCONNECTED -> snapshot.copy(status = GoProSourceStatus.LOST, detail = detail)
            NativeIngressEvent.ERROR -> snapshot.copy(status = GoProSourceStatus.ERROR, detail = detail)
        }
        // android.util.Log is unavailable in local JVM unit tests.
        runCatching { Log.i(TAG, "GoPro RTMP event=$event status=${next.status} detail=$detail") }
        update(next)
    }

    private fun update(next: GoProIngressSnapshot) {
        snapshot = next
        listener.onGoProIngressChanged(next)
    }

    companion object {
        const val DEFAULT_PORT = 1935
        const val DEFAULT_PATH = "gopro"
        private const val TAG = "GoProRtmpIngress"
    }
}
