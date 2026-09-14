package com.foresight.gateway.vision.geolocation

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.foresight.gateway.voice.conversation.ConversationVisualFrame
import com.foresight.gateway.voice.conversation.comparison.StreetViewVisualComparisonResult
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** DIRECT_ANDROID_MVP only. Production must obtain signed URLs from a trusted remote authorizer. */
data class StreetViewCandidate(
    val candidateId: String,
    val panoramaId: String,
    val captureDate: String? = null,
    val panoramaLatitude: Double? = null,
    val panoramaLongitude: Double? = null,
    val headingDegrees: Float,
    val pitchDegrees: Float = 0f,
    val fovDegrees: Float = 80f,
)

data class StreetViewReferenceImage(
    val candidate: StreetViewCandidate,
    val imageBytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val fetchedAtMillis: Long,
)

class StreetViewReferenceBatch(private var references: MutableList<StreetViewReferenceImage>) : AutoCloseable {
    private var transferred = false
    private var closed = false
    fun images(): List<StreetViewReferenceImage> { check(!closed) { "Street View batch is closed" }; return references.toList() }
    fun transferOwnership() { check(!closed && !transferred) { "Street View batch cannot transfer ownership" }; transferred = true }
    override fun close() { if (closed) return; references.forEach { it.imageBytes.fill(0) }; references.clear(); closed = true }
}

data class StreetViewReferenceRequest(val candidates: List<StreetViewCandidate>) {
    init { require(candidates.size <= StreetViewCandidateGenerator.MAX_IMAGES) }
}

object StreetViewCandidateGenerator {
    const val MAX_PANORAMAS = 1
    const val MAX_IMAGES = 3
    fun generate(metadata: StreetViewMetadata, headingDegrees: Float?): List<StreetViewCandidate> {
        val pano = metadata.panoId ?: return emptyList()
        val base = headingDegrees ?: 0f
        return listOf(0f, -30f, 30f).mapIndexed { index, offset ->
            StreetViewCandidate("candidate-$index", pano, metadata.captureDate, headingDegrees = normalize(base + offset))
        }
    }
    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f
}

interface AndroidApiIdentityProvider { fun packageName(): String; fun certificateSha1(): String }
class PackageAndroidApiIdentityProvider(private val context: Context) : AndroidApiIdentityProvider {
    override fun packageName(): String = context.packageName
    override fun certificateSha1(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val bytes = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return ""
        return MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02X".format(it) }
    }
}

interface StreetViewRequestAuthorizer { fun apiKey(): String? }
class DirectAndroidMvpStreetViewAuthorizer(private val mapsApiKey: () -> String?) : StreetViewRequestAuthorizer { override fun apiKey(): String? = mapsApiKey() }

interface StreetViewReferenceProvider : AutoCloseable {
    fun acquire(request: StreetViewReferenceRequest, onComplete: (Result<StreetViewReferenceBatch>) -> Unit)
    fun cancelActiveRequest()
}

data class StreetViewAcquisitionResult(
    val attempted: Boolean,
    val panoramaAvailable: Boolean,
    val requestedCandidateCount: Int,
    val successfulReferenceCount: Int,
    val failureCount: Int,
    val failureTypes: List<String> = emptyList(),
    val captureDateAvailable: Boolean,
    val comparisonResult: StreetViewVisualComparisonResult? = null,
)

class StreetViewAcquisitionCoordinator(
    private val provider: StreetViewReferenceProvider,
    private val mapsKeyPresent: () -> Boolean,
    private val consumer: StreetViewReferenceConsumer = DiagnosticStreetViewReferenceConsumer(),
) : AutoCloseable {
    fun acquireIfEligible(kind: VisualGeolocationQueryKind, frame: ConversationVisualFrame?, locationAvailable: Boolean, headingDegrees: Float?, metadata: StreetViewMetadata, onComplete: (StreetViewAcquisitionResult) -> Unit) {
        val reason = when {
            kind != VisualGeolocationQueryKind.VISUAL_PLACE -> "not_visual_place"
            frame == null -> "no_live_frame"
            !locationAvailable -> "no_location"
            headingDegrees == null -> "no_heading"
            !metadata.available || metadata.panoId == null -> "no_panorama"
            !mapsKeyPresent() -> "key_not_configured"
            else -> null
        }
        if (reason != null) { Log.i("ForesightGeo", "FORESIGHT_STREETVIEW_TRIGGER eligible=false reason=$reason"); onComplete(StreetViewAcquisitionResult(false, metadata.available, 0, 0, 0, captureDateAvailable = metadata.captureDate != null)); return }
        val liveFrame = requireNotNull(frame)
        val candidates = StreetViewCandidateGenerator.generate(metadata, headingDegrees)
        Log.i("ForesightGeo", "FORESIGHT_STREETVIEW_TRIGGER eligible=true reason=qualified")
        provider.acquire(StreetViewReferenceRequest(candidates)) { result ->
            result.fold(
                onSuccess = { batch ->
                    val count = batch.images().size
                    try {
                        batch.transferOwnership()
                        Log.i("ForesightGeo", "FORESIGHT_STREETVIEW_BATCH_CREATED referenceCount=$count")
                        Log.i("ForesightGeo", "FORESIGHT_STREETVIEW_BATCH_TRANSFERRED referenceCount=$count")
                        consumer.consume(batch, liveFrame) { comparison ->
                            comparison?.let {
                                Log.i("ForesightGeo", "FORESIGHT_STREETVIEW_COMPARE_RESULT outcome=${it.outcome} confidence=${it.confidence}")
                            }
                            onComplete(
                                StreetViewAcquisitionResult(
                                    true,
                                    true,
                                    candidates.size,
                                    count,
                                    candidates.size - count,
                                    captureDateAvailable = metadata.captureDate != null,
                                    comparisonResult = comparison,
                                ),
                            )
                        }
                    } catch (error: Throwable) {
                        batch.close(); Log.w("ForesightGeo", "FORESIGHT_STREETVIEW_OWNERSHIP_ERROR type=${error.javaClass.simpleName}")
                        onComplete(StreetViewAcquisitionResult(true, true, candidates.size, 0, candidates.size, listOf(error.javaClass.simpleName), metadata.captureDate != null))
                    }
                },
                onFailure = { error -> onComplete(StreetViewAcquisitionResult(true, true, candidates.size, 0, candidates.size, listOf(error.javaClass.simpleName), metadata.captureDate != null)) },
            )
        }
    }
    fun cancelActiveRequest() {
        provider.cancelActiveRequest()
        consumer.cancelActiveConsumption()
    }

    override fun close() {
        cancelActiveRequest()
        consumer.close()
        provider.close()
    }
}

/** Temporary single owner: logs only structural properties and always releases the batch. */
interface StreetViewReferenceConsumer {
    fun consume(batch: StreetViewReferenceBatch, liveFrame: ConversationVisualFrame, onComplete: (StreetViewVisualComparisonResult?) -> Unit)
    fun cancelActiveConsumption() = Unit
    fun close() = Unit
}

class DiagnosticStreetViewReferenceConsumer : StreetViewReferenceConsumer {
    override fun consume(batch: StreetViewReferenceBatch, liveFrame: ConversationVisualFrame, onComplete: (StreetViewVisualComparisonResult?) -> Unit) {
        Log.i("ForesightGeo", "FORESIGHT_STREETVIEW_CONSUMER_START type=diagnostic")
        var count = 0
        try { count = batch.images().size; Log.i("ForesightGeo", "FORESIGHT_STREETVIEW_CONSUMER_COMPLETE") } finally {
            batch.close(); Log.i("ForesightGeo", "FORESIGHT_STREETVIEW_BATCH_CLOSED referenceCount=$count")
        }
        onComplete(null)
    }
}

data class StreetViewHttpResponse(val statusCode: Int, val contentType: String?, val bytes: ByteArray)
interface StreetViewHttpTransport { fun get(url: String, headers: Map<String, String>): StreetViewHttpResponse }
class UrlConnectionStreetViewTransport : StreetViewHttpTransport {
    override fun get(url: String, headers: Map<String, String>): StreetViewHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 3_000; readTimeout = 5_000; headers.forEach { (k, v) -> setRequestProperty(k, v) } }
        return try { val status = connection.responseCode; StreetViewHttpResponse(status, connection.contentType, (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: byteArrayOf()) } finally { connection.disconnect() }
    }
}

class GoogleStreetViewReferenceProvider(
    private val authorizer: StreetViewRequestAuthorizer,
    private val identity: AndroidApiIdentityProvider,
    private val transport: StreetViewHttpTransport = UrlConnectionStreetViewTransport(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "ForesightStreetView") },
) : StreetViewReferenceProvider {
    @Volatile private var active: Future<*>? = null
    override fun acquire(request: StreetViewReferenceRequest, onComplete: (Result<StreetViewReferenceBatch>) -> Unit) {
        cancelActiveRequest(); val key = authorizer.apiKey()?.trim().orEmpty()
        active = executor.submit {
            Log.i(TAG, "FORESIGHT_STREETVIEW_ACQUIRE_START candidateCount=${request.candidates.size}")
            if (key.isBlank()) { onComplete(Result.failure(IllegalStateException("authorization"))); return@submit }
            val images = mutableListOf<StreetViewReferenceImage>()
            request.candidates.take(StreetViewCandidateGenerator.MAX_IMAGES).forEachIndexed { index, candidate ->
                if (Thread.currentThread().isInterrupted) return@forEachIndexed
                Log.i(TAG, "FORESIGHT_STREETVIEW_FETCH_START index=$index")
                runCatching { transport.get(requestUrl(candidate, key), mapOf("X-Android-Package" to identity.packageName(), "X-Android-Cert" to identity.certificateSha1(), "Accept" to "image/jpeg,image/png")) }
                    .onSuccess { response ->
                        if (response.statusCode !in 200..299 || response.contentType !in setOf("image/jpeg", "image/png") || response.bytes.isEmpty()) throw IllegalStateException("http_${response.statusCode}")
                        images += StreetViewReferenceImage(candidate, response.bytes, response.contentType!!, 512, 512, SystemClock.elapsedRealtime()); Log.i(TAG, "FORESIGHT_STREETVIEW_FETCH_SUCCESS index=$index bytes=${response.bytes.size}")
                    }.onFailure { error -> Log.w(TAG, "FORESIGHT_STREETVIEW_FETCH_FAILURE index=$index type=${if (error is SocketTimeoutException) "timeout" else "failure"}") }
            }
            if (Thread.currentThread().isInterrupted) { images.forEach { it.imageBytes.fill(0) }; Log.i(TAG, "FORESIGHT_STREETVIEW_ACQUIRE_CANCELLED"); return@submit }
            Log.i(TAG, "FORESIGHT_STREETVIEW_ACQUIRE_COMPLETE successCount=${images.size}"); onComplete(Result.success(StreetViewReferenceBatch(images)))
        }
    }
    override fun cancelActiveRequest() { active?.cancel(true); active = null }
    override fun close() { cancelActiveRequest(); executor.shutdownNow() }
    internal fun requestUrl(candidate: StreetViewCandidate, key: String) = "https://maps.googleapis.com/maps/api/streetview?size=512x512&pano=${candidate.panoramaId}&heading=${candidate.headingDegrees}&pitch=0&fov=80&return_error_code=true&key=$key"
    private companion object { const val TAG = "ForesightGeo" }
}
