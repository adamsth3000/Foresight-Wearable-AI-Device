package com.foresight.gateway.ui

import com.foresight.gateway.capture.EventMediaSyncAttemptResult
import com.foresight.gateway.capture.EventMediaSyncHistoryEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure, device-local rendering policy for durable sync-history timestamps. */
internal object SyncHistoryPresentation {
    fun heading(
        entry: EventMediaSyncHistoryEntry,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val timestamp = entry.completedUtc ?: entry.startedUtc
        val formatter = DateTimeFormatter.ofPattern("MM/dd/uuuu hh:mm:ss a", locale)
        val outcome = entry.result ?: EventMediaSyncAttemptResult.FAILED
        return "${formatter.format(timestamp.atZone(zoneId))} - ${outcome.name}"
    }

    fun detailTimestamp(timestamp: java.time.Instant, locale: Locale = Locale.getDefault(), zoneId: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("MM/dd/uuuu hh:mm:ss a", locale).format(timestamp.atZone(zoneId))
}
