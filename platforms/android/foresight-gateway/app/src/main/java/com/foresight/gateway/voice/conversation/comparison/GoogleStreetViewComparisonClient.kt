package com.foresight.gateway.voice.conversation.comparison

import android.os.SystemClock
import android.util.Log
import com.foresight.gateway.voice.conversation.GoogleHostedModelSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

interface StreetViewComparisonClient : AutoCloseable {
    fun compare(request: StreetViewVisualComparisonRequest, onComplete: (StreetViewVisualComparisonResult) -> Unit)
    fun cancelActiveRequest()
}

data class StreetViewComparisonHttpResponse(val statusCode: Int, val body: String)

interface StreetViewComparisonHttpCall {
    fun post(apiKey: String, body: String): StreetViewComparisonHttpResponse
    fun cancel()
}

interface StreetViewComparisonHttpCallFactory {
    fun create(): StreetViewComparisonHttpCall
}

class UrlConnectionStreetViewComparisonHttpCall : StreetViewComparisonHttpCall {
    @Volatile private var connection: HttpURLConnection? = null

    override fun post(apiKey: String, body: String): StreetViewComparisonHttpResponse {
        val opened = (URL(GoogleHostedModelSpec.interactionsUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        connection = opened
        return try {
            OutputStreamWriter(opened.outputStream, Charsets.UTF_8).use { it.write(body) }
            val status = opened.responseCode
            StreetViewComparisonHttpResponse(status, (if (status in 200..299) opened.inputStream else opened.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection = null
            opened.disconnect()
        }
    }

    override fun cancel() { connection?.disconnect() }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 10_000
    }
}

class UrlConnectionStreetViewComparisonHttpCallFactory : StreetViewComparisonHttpCallFactory {
    override fun create(): StreetViewComparisonHttpCall = UrlConnectionStreetViewComparisonHttpCall()
}

class GoogleStreetViewComparisonClient(
    private val apiKey: () -> String?,
    private val callFactory: StreetViewComparisonHttpCallFactory = UrlConnectionStreetViewComparisonHttpCallFactory(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "ForesightStreetViewCompare") },
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "ForesightStreetViewCompareTimeout") },
    private val deadlineMillis: Long = DEADLINE_MILLIS,
) : StreetViewComparisonClient {
    private val lock = Any()
    @Volatile private var active: ActiveRequest? = null
    @Volatile private var closed = false

    override fun compare(request: StreetViewVisualComparisonRequest, onComplete: (StreetViewVisualComparisonResult) -> Unit) {
        cancelActiveRequest()
        val key = apiKey()?.trim().orEmpty()
        if (closed || key.isBlank()) {
            onComplete(StreetViewVisualComparisonResult(StreetViewComparisonOutcome.FAILED))
            return
        }
        val requestState = ActiveRequest(onComplete)
        synchronized(lock) { active = requestState }
        Log.i(TAG, "FORESIGHT_STREETVIEW_COMPARE_START referenceCount=${request.references.size}")
        requestState.timeout = scheduler.schedule({ timeout(requestState) }, deadlineMillis, TimeUnit.MILLISECONDS)
        requestState.work = executor.submit {
            if (!requestState.start()) return@submit
            val encoded = runCatching { StreetViewComparisonRequestEncoder.encode(request) }
                .getOrElse { error -> complete(requestState, StreetViewVisualComparisonResult(StreetViewComparisonOutcome.FAILED), "encode_${error.javaClass.simpleName}"); return@submit }
            requestState.markInputReadComplete()
            if (requestState.isCancelled()) {
                complete(requestState, terminalCancellationResult(requestState), "cancelled")
                return@submit
            }
            val call = callFactory.create()
            requestState.call = call
            try {
                Log.i(TAG, "FORESIGHT_STREETVIEW_COMPARE_HTTP_START")
                val response = call.post(key, encoded.json)
                Log.i(TAG, "FORESIGHT_STREETVIEW_COMPARE_HTTP_RESPONSE status=${response.statusCode} bodyBytes=${response.body.toByteArray(Charsets.UTF_8).size}")
                if (requestState.isCancelled()) {
                    complete(requestState, terminalCancellationResult(requestState), "cancelled")
                } else if (response.statusCode !in 200..299) {
                    complete(requestState, StreetViewVisualComparisonResult(StreetViewComparisonOutcome.FAILED), "http_${response.statusCode}")
                } else {
                    when (val decoded = StreetViewComparisonResponseDecoder.decode(response.body, request.references.map { it.candidateId }.toSet())) {
                        is StreetViewComparisonDecodeResult.Valid -> {
                            Log.i(TAG, "FORESIGHT_STREETVIEW_COMPARE_DECODE_SUCCESS outcome=${decoded.result.outcome} confidence=${decoded.result.confidence}")
                            complete(requestState, decoded.result, "success")
                        }
                        is StreetViewComparisonDecodeResult.Failure -> {
                            Log.w(TAG, "FORESIGHT_STREETVIEW_COMPARE_DECODE_FAILURE type=${decoded.type}")
                            complete(requestState, StreetViewVisualComparisonResult(StreetViewComparisonOutcome.FAILED), decoded.type)
                        }
                    }
                }
            } catch (_: Throwable) {
                val outcome = if (requestState.isCancelled() && !requestState.isTimedOut()) StreetViewComparisonOutcome.CANCELLED else StreetViewComparisonOutcome.FAILED
                complete(requestState, StreetViewVisualComparisonResult(outcome), "transport_failure")
            } finally {
                requestState.call = null
            }
        }
    }

    override fun cancelActiveRequest() {
        val request = synchronized(lock) { active } ?: return
        request.cancel()
        request.call?.cancel()
        request.work?.cancel(true)
        if (!request.started()) complete(request, StreetViewVisualComparisonResult(StreetViewComparisonOutcome.CANCELLED), "cancelled_before_start")
    }

    override fun close() {
        closed = true
        cancelActiveRequest()
        executor.shutdownNow()
        scheduler.shutdownNow()
    }

    private fun timeout(request: ActiveRequest) {
        if (!request.timeout()) return
        Log.w(TAG, "FORESIGHT_STREETVIEW_COMPARE_TIMEOUT")
        request.call?.cancel()
        request.work?.cancel(true)
        if (!request.started() || request.inputReadComplete()) {
            complete(request, StreetViewVisualComparisonResult(StreetViewComparisonOutcome.FAILED), "timeout")
        }
    }

    private fun complete(request: ActiveRequest, result: StreetViewVisualComparisonResult, reason: String) {
        if (!request.completeOnce()) return
        request.timeout?.cancel(true)
        synchronized(lock) { if (active === request) active = null }
        if (result.outcome == StreetViewComparisonOutcome.CANCELLED) Log.i(TAG, "FORESIGHT_STREETVIEW_COMPARE_CANCELLED")
        Log.i(TAG, "FORESIGHT_STREETVIEW_COMPARE_COMPLETE outcome=${result.outcome} reason=$reason elapsedMs=${SystemClock.elapsedRealtime() - request.startedAtMillis}")
        request.callback(result)
    }

    private fun terminalCancellationResult(request: ActiveRequest) =
        StreetViewVisualComparisonResult(if (request.isTimedOut()) StreetViewComparisonOutcome.FAILED else StreetViewComparisonOutcome.CANCELLED)

    private class ActiveRequest(val callback: (StreetViewVisualComparisonResult) -> Unit) {
        private val stateLock = Any()
        val startedAtMillis = SystemClock.elapsedRealtime()
        @Volatile var work: Future<*>? = null
        @Volatile var timeout: Future<*>? = null
        @Volatile var call: StreetViewComparisonHttpCall? = null
        private var started = false
        private var cancelled = false
        private var timedOut = false
        private var inputRead = false
        private var completed = false

        fun start(): Boolean = synchronized(stateLock) { if (cancelled) false else { started = true; true } }
        fun started(): Boolean = synchronized(stateLock) { started }
        fun isCancelled(): Boolean = synchronized(stateLock) { cancelled }
        fun cancel(): Boolean = synchronized(stateLock) { if (cancelled || completed) false else { cancelled = true; true } }
        fun timeout(): Boolean = synchronized(stateLock) { if (cancelled || completed) false else { cancelled = true; timedOut = true; true } }
        fun isTimedOut(): Boolean = synchronized(stateLock) { timedOut }
        fun markInputReadComplete() = synchronized(stateLock) { inputRead = true }
        fun inputReadComplete(): Boolean = synchronized(stateLock) { inputRead }
        fun completeOnce(): Boolean = synchronized(stateLock) { if (completed) false else { completed = true; true } }
    }

    private companion object {
        const val TAG = "ForesightStreetViewCompare"
        const val DEADLINE_MILLIS = 10_000L
    }
}

internal data class EncodedStreetViewComparisonRequest(val json: String)

internal object StreetViewComparisonRequestEncoder {
    fun encode(request: StreetViewVisualComparisonRequest): EncodedStreetViewComparisonRequest {
        val labels = buildString {
            append("LIVE_FRAME: current Foresight visual evidence. Compare it with the following historical Street View references. ")
            request.references.forEachIndexed { index, reference -> append("candidate_$index id=${reference.candidateId} heading=${reference.headingDegrees}. ") }
            append("Compare only stable facade geometry, roofline, windows, doors, storefronts, permanent signage, road/intersection geometry, neighboring structures, and permanent architecture. De-emphasize vehicles, pedestrians, weather, shadows, vegetation, seasons, and temporary signs. Determine visual correspondence only; do not infer an address, business identity, or geographic location.")
        }
        val input = JSONArray().put(JSONObject().put("type", "text").put("text", labels))
            .put(image(request.liveFrameJpeg))
        request.references.forEach { input.put(image(it.imageJpeg)) }
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("outcome", JSONObject().put("type", "string").put("enum", JSONArray(listOf("MATCHED", "INCONCLUSIVE", "NO_MATCH"))))
                .put("selectedCandidateId", JSONObject().put("type", JSONArray(listOf("string", "null"))))
                .put("confidence", JSONObject().put("type", "string").put("enum", JSONArray(listOf("LOW", "MEDIUM", "HIGH"))))
                .put("rationale", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("outcome", "selectedCandidateId", "confidence", "rationale")))
        return EncodedStreetViewComparisonRequest(
            JSONObject()
                .put("model", GoogleHostedModelSpec.modelId)
                .put("system_instruction", "Return only the requested structured visual-correspondence result. Rationale must be a concise evidence summary without hidden reasoning.")
                .put("input", input)
                .put("store", false)
                .put("generation_config", JSONObject().put("max_output_tokens", 200).put("thinking_level", "low"))
                .put("response_format", JSONObject().put("type", "text").put("mime_type", "application/json").put("schema", schema))
                .toString(),
        )
    }

    private fun image(bytes: ByteArray) = JSONObject()
        .put("type", "image")
        .put("mime_type", "image/jpeg")
        .put("data", Base64.getEncoder().encodeToString(bytes))
}
