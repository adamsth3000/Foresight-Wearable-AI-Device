package com.foresight.gateway.metadata

import android.os.SystemClock
import java.time.Instant

/** Maps this phone's elapsed-realtime clock to an explicit UTC observation anchor. */
data class ClockAnchor(
    val utc: Instant = Instant.now(),
    val elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
)
