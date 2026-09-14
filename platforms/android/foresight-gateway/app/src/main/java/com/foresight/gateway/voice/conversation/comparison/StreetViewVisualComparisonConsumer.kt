package com.foresight.gateway.voice.conversation.comparison

import android.util.Log
import com.foresight.gateway.vision.geolocation.StreetViewReferenceBatch
import com.foresight.gateway.vision.geolocation.StreetViewReferenceConsumer
import com.foresight.gateway.voice.conversation.ConversationVisualFrame

class StreetViewVisualComparisonConsumer(
    private val client: StreetViewComparisonClient,
) : StreetViewReferenceConsumer {
    private val lock = Any()
    private val activeBatches = mutableSetOf<StreetViewReferenceBatch>()

    override fun consume(
        batch: StreetViewReferenceBatch,
        liveFrame: ConversationVisualFrame,
        onComplete: (StreetViewVisualComparisonResult?) -> Unit,
    ) {
        val images = try { batch.images() } catch (_: IllegalStateException) { onComplete(StreetViewVisualComparisonResult(StreetViewComparisonOutcome.FAILED)); return }
        val request = StreetViewVisualComparisonRequest(
            liveFrameJpeg = liveFrame.jpegBytes,
            references = images.map { StreetViewComparisonReference(it.candidate.candidateId, it.candidate.headingDegrees, it.imageBytes) },
        )
        synchronized(lock) { activeBatches += batch }
        client.compare(request) { result ->
            var shouldComplete = false
            synchronized(lock) { shouldComplete = activeBatches.remove(batch) }
            if (!shouldComplete) return@compare
            val count = images.size
            batch.close()
            Log.i(TAG, "FORESIGHT_STREETVIEW_BATCH_CLOSED referenceCount=$count")
            onComplete(result)
        }
    }

    override fun cancelActiveConsumption() {
        client.cancelActiveRequest()
    }

    override fun close() {
        cancelActiveConsumption()
        client.close()
    }

    private companion object { const val TAG = "ForesightStreetViewCompare" }
}
