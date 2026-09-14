package com.foresight.gateway.voice.conversation

import android.os.SystemClock
import android.util.Log
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val MAX_OUTPUT_CHARACTERS = 700

object GoogleHostedModelSpec {
    const val modelId = "gemini-3.8-flash"
    const val interactionsUrl = "https://generativelanguage.googleapis.com/v1beta/interactions"
}

sealed interface GoogleHostedConnectionState {
    data object NotConfigured : GoogleHostedConnectionState
    data object Testing : GoogleHostedConnectionState
    data object Ready : GoogleHostedConnectionState
    data object AuthenticationFailed : GoogleHostedConnectionState
    data object NetworkUnavailable : GoogleHostedConnectionState
    data object RateLimited : GoogleHostedConnectionState
    data object ModelUnavailable : GoogleHostedConnectionState
    data object AuthorizationFailed : GoogleHostedConnectionState
    data object ServiceUnavailable : GoogleHostedConnectionState
}

data class GoogleHostedHttpResponse(val statusCode: Int, val body: String)

interface GoogleHostedTransport {
    fun post(apiKey: String, body: String): GoogleHostedHttpResponse
}

class GoogleInteractionsTransport : GoogleHostedTransport {
    override fun post(apiKey: String, body: String): GoogleHostedHttpResponse {
        val connection = (URL(GoogleHostedModelSpec.interactionsUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return GoogleHostedHttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 20_000
    }
}

/** Direct Android-to-Google hosted conversation adapter. It intentionally has no LAN endpoint. */
class GoogleHostedConversationEngine(
    private val apiKey: () -> String?,
    private val transport: GoogleHostedTransport = GoogleInteractionsTransport(),
    private val executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "ForesightGoogleHostedAI") },
) : ConversationEngine {
    @Volatile private var state: GoogleHostedConnectionState = GoogleHostedConnectionState.NotConfigured
    @Volatile private var activeWork: Future<*>? = null
    @Volatile private var closed = false

    fun connectionState(): GoogleHostedConnectionState = state

    fun testConnection(onComplete: (GoogleHostedConnectionState) -> Unit) {
        val key = apiKey()?.trim().orEmpty()
        if (key.isBlank()) {
            transitionTo(GoogleHostedConnectionState.NotConfigured)
            onComplete(state)
            return
        }
        transitionTo(GoogleHostedConnectionState.Testing)
        Log.i(TAG, "GOOGLE_AI_PREFLIGHT_START model=${GoogleHostedModelSpec.modelId}")
        executor.execute {
            val outcome = runCatching { postAndValidatePreflight(key, GoogleInteractionRequestEncoder.preflight()) }
                .fold(
                    onSuccess = {
                        Log.i(TAG, "GOOGLE_AI_PREFLIGHT_SUCCESS")
                        GoogleHostedConnectionState.Ready
                    },
                    onFailure = { error ->
                        val failure = connectionFailure(error)
                        val httpStatus = (error as? GoogleHttpException)?.statusCode
                        Log.w(TAG, "GOOGLE_AI_PREFLIGHT_FAILURE type=${failure.logName()} httpStatus=${httpStatus ?: "none"}")
                        failure
                    },
                )
            transitionTo(outcome)
            onComplete(state)
        }
    }

    override fun readiness(): ConversationReadiness =
        if (!closed && state == GoogleHostedConnectionState.Ready) ConversationReadiness.READY else ConversationReadiness.FAILED

    override suspend fun respond(request: ConversationRequest): ConversationResponse = suspendCoroutine { continuation ->
        val key = apiKey()?.trim().orEmpty()
        if (closed || key.isBlank() || state != GoogleHostedConnectionState.Ready) {
            continuation.resumeWithException(ConversationUnavailableException(connectionMessage(state)))
            return@suspendCoroutine
        }
        val work = Runnable {
            val started = SystemClock.elapsedRealtime()
            runCatching { postAndDecode(key, GoogleInteractionRequestEncoder.conversation(request)) }
                .onSuccess { response ->
                    Log.i(TAG, "FORESIGHT_GOOGLE_AI_RESPONSE elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    Log.i(TAG, "FORESIGHT_GOOGLE_AI_SEARCH_USED used=${response.usedGoogleSearch} citations=${response.groundingSources.size}")
                    Log.i(
                        TAG,
                        "FORESIGHT_GOOGLE_MAPS_GROUNDING used=${response.usedGoogleMaps} " +
                            "sources=${response.groundingSources.count { it.sourceType == GroundingSourceType.GOOGLE_MAPS }}",
                    )
                    Log.i(TAG, "FORESIGHT_GOOGLE_AI_COMPLETE elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    continuation.resume(response)
                }
                .onFailure { error ->
                    if (error is GoogleHttpException && error.statusCode == 400) {
                        Log.w(TAG, "FORESIGHT_GOOGLE_AI_FAILURE type=INVALID_REQUEST")
                        continuation.resumeWithException(ConversationUnavailableException("Foresight AI could not process that request."))
                    } else {
                        transitionTo(connectionFailure(error))
                        Log.w(TAG, "FORESIGHT_GOOGLE_AI_FAILURE type=${state.logName()}")
                        continuation.resumeWithException(ConversationUnavailableException(connectionMessage(state)))
                    }
                }
        }
        if (executor is java.util.concurrent.ExecutorService) activeWork = executor.submit(work) else executor.execute(work)
    }

    override fun cancelActiveRequest() {
        activeWork?.cancel(true)
        activeWork = null
    }

    override fun close() {
        closed = true
        cancelActiveRequest()
        (executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun connectionFailure(error: Throwable): GoogleHostedConnectionState = when (error) {
        is GoogleHttpException -> when (error.statusCode) {
            400 -> GoogleHostedConnectionState.ServiceUnavailable
            401 -> GoogleHostedConnectionState.AuthenticationFailed
            403 -> GoogleHostedConnectionState.AuthorizationFailed
            404 -> GoogleHostedConnectionState.ModelUnavailable
            429 -> GoogleHostedConnectionState.RateLimited
            in 500..599 -> GoogleHostedConnectionState.ServiceUnavailable
            else -> GoogleHostedConnectionState.ServiceUnavailable
        }
        is SocketTimeoutException,
        is java.net.UnknownHostException,
        is java.net.ConnectException -> GoogleHostedConnectionState.NetworkUnavailable
        else -> GoogleHostedConnectionState.ServiceUnavailable
    }

    private fun postAndDecode(key: String, request: GoogleInteractionEncodedRequest): ConversationResponse {
        val response = post(key, request)
        return when (val decoded = GoogleInteractionResponseDecoder.decode(response.body)) {
            is GoogleInteractionDecodeResult.ValidText -> {
                logResponseSchema(decoded.summary)
                Log.i(TAG, "GOOGLE_AI_RESPONSE_DECODE_SUCCESS textPresent=true")
                ConversationResponse(
                    spokenText = decoded.text.take(MAX_OUTPUT_CHARACTERS),
                    displayText = decoded.text.take(MAX_OUTPUT_CHARACTERS),
                    modelIdentity = GoogleHostedModelSpec.modelId,
                    groundingSources = decoded.groundingSources,
                    usedVisualContext = request.usedVisualContext,
                    usedGoogleSearch = decoded.usedGoogleSearch,
                    usedGoogleMaps = request.mapsEnabled,
                )
            }
            is GoogleInteractionDecodeResult.ValidEmpty -> {
                logResponseSchema(decoded.summary)
                Log.w(TAG, "GOOGLE_AI_RESPONSE_DECODE_SUCCESS textPresent=false")
                throw GoogleResponseDecodeException("valid_empty_response")
            }
            is GoogleInteractionDecodeResult.Failure -> {
                logResponseSchema(decoded.summary)
                Log.w(TAG, "GOOGLE_AI_RESPONSE_DECODE_FAILURE reason=${decoded.reason}")
                throw GoogleResponseDecodeException(decoded.reason)
            }
        }
    }

    private fun postAndValidatePreflight(key: String, request: GoogleInteractionEncodedRequest) {
        val response = post(key, request)
        when (val decoded = GoogleInteractionResponseDecoder.decode(response.body)) {
            is GoogleInteractionDecodeResult.ValidText,
            is GoogleInteractionDecodeResult.ValidEmpty -> {
                val summary = when (decoded) {
                    is GoogleInteractionDecodeResult.ValidText -> decoded.summary
                    is GoogleInteractionDecodeResult.ValidEmpty -> decoded.summary
                    else -> error("unreachable")
                }
                logResponseSchema(summary)
                Log.i(TAG, "GOOGLE_AI_RESPONSE_DECODE_SUCCESS textPresent=${decoded is GoogleInteractionDecodeResult.ValidText}")
            }
            is GoogleInteractionDecodeResult.Failure -> {
                logResponseSchema(decoded.summary)
                Log.w(TAG, "GOOGLE_AI_RESPONSE_DECODE_FAILURE reason=${decoded.reason}")
                throw GoogleResponseDecodeException(decoded.reason)
            }
        }
    }

    private fun post(key: String, request: GoogleInteractionEncodedRequest): GoogleHostedHttpResponse {
        Log.i(
            TAG,
            "GOOGLE_AI_REQUEST_SCHEMA purpose=${request.purpose} topLevelKeys=${request.topLevelKeys.joinToString(",")} " +
                "inputCount=${request.inputCount} historyTurns=${request.historyTurns} hasSystemPolicy=${request.hasSystemPolicy} " +
                "hasContext=${request.hasContext} generationConfigKeys=${request.generationConfigKeys.joinToString(",")}",
        )
        Log.i(TAG, "GOOGLE_AI_HTTP_REQUEST_START")
        Log.i(TAG, "FORESIGHT_GOOGLE_AI_REQUEST visual=${request.usedVisualContext} searchEnabled=${request.searchEnabled} mapsEnabled=${request.mapsEnabled}")
        val response = try { transport.post(key, request.json) } finally { request.clearVisualFrame() }
        Log.i(TAG, "GOOGLE_AI_HTTP_RESPONSE status=${response.statusCode} bodyBytes=${response.body.toByteArray(Charsets.UTF_8).size}")
        if (response.statusCode !in 200..299) {
            logApiError(response)
            throw GoogleHttpException(response.statusCode)
        }
        return response
    }

    private fun logApiError(response: GoogleHostedHttpResponse) {
        val error = runCatching { JSONObject(response.body).optJSONObject("error") }.getOrNull()
        val apiStatus = error?.optString("status").orEmpty().ifBlank { "none" }
        val message = error?.optString("message").orEmpty()
        val field = Regex("[Uu]nknown name \\\"([^\\\"]+)\\\"").find(message)?.groupValues?.getOrNull(1)
        val reason = field?.let { "unknown_field=$it" } ?: if (message.isBlank()) "unspecified" else "api_error"
        Log.w(TAG, "GOOGLE_AI_API_ERROR httpStatus=${response.statusCode} apiStatus=$apiStatus reason=$reason")
    }

    private fun logResponseSchema(summary: GoogleInteractionResponseSummary) {
        Log.i(
            TAG,
            "GOOGLE_AI_RESPONSE_SCHEMA topLevelKeys=${summary.topLevelKeys.joinToString(",")} " +
                "steps=${summary.stepCount} outputs=${summary.outputCount}",
        )
    }

    private fun transitionTo(next: GoogleHostedConnectionState) {
        val previous = state
        state = next
        if (previous != next) {
            Log.i(TAG, "GOOGLE_AI_READINESS old=${previous.logName()} new=${next.logName()}")
        }
    }

    private companion object { const val TAG = "ForesightGoogleAI" }
}

internal data class GoogleInteractionEncodedRequest(
    val purpose: String,
    val json: String,
    val topLevelKeys: List<String>,
    val inputCount: Int,
    val historyTurns: Int,
    val hasSystemPolicy: Boolean,
    val hasContext: Boolean,
    val generationConfigKeys: List<String>,
    val usedVisualContext: Boolean,
    val searchEnabled: Boolean,
    val mapsEnabled: Boolean,
    val clearVisualFrame: () -> Unit = {},
)

internal object GoogleInteractionRequestEncoder {
    fun preflight(): GoogleInteractionEncodedRequest = encoded(
        purpose = "preflight",
        input = "Respond with READY.",
        historyTurns = 0,
        hasSystemPolicy = false,
        hasContext = false,
        maxOutputTokens = 8,
        visualFrame = null,
        searchEnabled = false,
        mapsLocation = null,
    )

    fun conversation(request: ConversationRequest): GoogleInteractionEncodedRequest {
        val mapsDecision = MapsGroundingPolicy.decision(
            utterance = request.utterance,
            location = request.context.mapsLocation,
            visualEvidenceAvailable = request.visualFrame != null,
            nowElapsedMillis = request.timestampElapsedMillis,
        )
        val ocrAvailable = request.context.sceneEvidence?.ocrObservation?.regions?.isNotEmpty() == true
        val headingAvailable = request.context.sceneEvidence?.heading?.isAvailable == true
        Log.i("ForesightGoogleAI", "FORESIGHT_GOOGLE_MAPS_DECISION enabled=${mapsDecision.mapsEnabled} reason=${mapsDecision.reason}")
        Log.i("ForesightGoogleAI", "FORESIGHT_PLACE_GROUNDING_DECISION enabled=${mapsDecision.mapsEnabled} reason=${mapsDecision.reason}")
        Log.i(
            "ForesightGoogleAI",
            "FORESIGHT_PLACE_GROUNDING_EVIDENCE visual=${request.visualFrame != null} ocr=$ocrAvailable " +
                "location=${request.context.mapsLocation != null} heading=$headingAvailable maps=${mapsDecision.mapsEnabled} search=${mapsDecision.searchEnabled}",
        )
        return encoded(
        purpose = "conversation",
        input = conversationInput(request),
        historyTurns = request.history.size,
        hasSystemPolicy = true,
        hasContext = true,
        maxOutputTokens = 160,
        visualFrame = request.visualFrame,
        searchEnabled = mapsDecision.searchEnabled,
        mapsLocation = request.context.mapsLocation?.takeIf { mapsDecision.mapsEnabled },
        )
    }

    private fun encoded(
        purpose: String,
        input: String,
        historyTurns: Int,
        hasSystemPolicy: Boolean,
        hasContext: Boolean,
        maxOutputTokens: Int,
        visualFrame: ConversationVisualFrame?,
        searchEnabled: Boolean,
        mapsLocation: com.foresight.gateway.vision.SceneLocation? = null,
    ): GoogleInteractionEncodedRequest {
        val generationConfig = JSONObject().put("max_output_tokens", maxOutputTokens).put("thinking_level", "low")
        val root = JSONObject().apply {
            put("model", GoogleHostedModelSpec.modelId)
            if (hasSystemPolicy) put("system_instruction", ConversationPolicy.systemInstruction)
            put("input", inputContent(input, visualFrame))
            put("store", false)
            put("generation_config", generationConfig)
            if (searchEnabled || mapsLocation != null) put("tools", JSONArray().apply {
                if (searchEnabled) put(JSONObject().put("type", "google_search"))
                mapsLocation?.let { put(JSONObject().put("type", "google_maps").put("latitude", it.latitude).put("longitude", it.longitude)) }
            })
        }
        return GoogleInteractionEncodedRequest(
            purpose = purpose,
            json = root.toString(),
            topLevelKeys = root.keys().asSequence().toList().sorted(),
            inputCount = if (visualFrame == null) 1 else 2,
            historyTurns = historyTurns,
            hasSystemPolicy = hasSystemPolicy,
            hasContext = hasContext,
            generationConfigKeys = generationConfig.keys().asSequence().toList().sorted(),
            usedVisualContext = visualFrame != null,
            searchEnabled = searchEnabled,
            mapsEnabled = mapsLocation != null,
            clearVisualFrame = visualFrame?.let { { it.clear() } } ?: {},
        )
    }

    private fun inputContent(text: String, visualFrame: ConversationVisualFrame?): Any =
        if (visualFrame == null) text else JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", text))
            put(
                JSONObject()
                    .put("type", "image")
                    .put("mime_type", "image/jpeg")
                    .put("data", Base64.encodeToString(visualFrame.jpegBytes, Base64.NO_WRAP)),
            )
        }

    private fun conversationInput(request: ConversationRequest): String = buildString {
        append(ForesightContextRenderer().render(request.context))
        append("\n\nCONVERSATION HISTORY\n")
        if (request.history.isEmpty()) append("none") else request.history.forEach { turn ->
            append(turn.role.name).append(": ").append(turn.text).append('\n')
        }
        append("\nUSER\n").append(request.utterance.trim())
    }
}

internal data class GoogleInteractionResponseSummary(
    val topLevelKeys: List<String>,
    val stepCount: Int,
    val outputCount: Int,
)

internal sealed interface GoogleInteractionDecodeResult {
    data class ValidText(
        val text: String,
        val summary: GoogleInteractionResponseSummary,
        val groundingSources: List<GroundingSource>,
        val usedGoogleSearch: Boolean,
    ) : GoogleInteractionDecodeResult
    data class ValidEmpty(val summary: GoogleInteractionResponseSummary) : GoogleInteractionDecodeResult
    data class Failure(val reason: String, val summary: GoogleInteractionResponseSummary) : GoogleInteractionDecodeResult
}

/** Decodes documented Interactions REST resources without exposing generated content to logs. */
internal object GoogleInteractionResponseDecoder {
    fun decode(body: String): GoogleInteractionDecodeResult {
        val root = runCatching { JSONObject(body) }.getOrElse {
            return GoogleInteractionDecodeResult.Failure("json_parse", EMPTY_SUMMARY)
        }
        val summary = GoogleInteractionResponseSummary(
            topLevelKeys = root.keys().asSequence().toList().sorted(),
            stepCount = root.optJSONArray("steps")?.length() ?: 0,
            outputCount = root.optJSONArray("outputs")?.length() ?: 0,
        )
        if (root.optString("status") in setOf("failed", "cancelled", "requires_action")) {
            return GoogleInteractionDecodeResult.Failure("api_error_response", summary)
        }
        val text = listOfNotNull(
            root.optString("output_text").takeIf { it.isNotBlank() },
            textFromSteps(root.optJSONArray("steps")),
            textFromLegacyOutputs(root.optJSONArray("outputs")),
        ).firstOrNull()?.trim().orEmpty()
        val sources = groundingSources(root.optJSONArray("steps"))
        val searchUsed = root.optJSONArray("steps")?.let { steps -> (0 until steps.length()).any { steps.optJSONObject(it)?.optString("type")?.startsWith("google_search") == true } } == true
        if (text.isNotBlank()) return GoogleInteractionDecodeResult.ValidText(text, summary, sources, searchUsed)
        return if (isInteractionResource(root)) {
            GoogleInteractionDecodeResult.ValidEmpty(summary)
        } else {
            GoogleInteractionDecodeResult.Failure("unexpected_shape", summary)
        }
    }

    private fun textFromSteps(steps: JSONArray?): String? = steps?.let { values ->
        buildList {
            for (index in 0 until values.length()) {
                val step = values.optJSONObject(index) ?: continue
                if (step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                for (contentIndex in 0 until content.length()) {
                    val item = content.optJSONObject(contentIndex) ?: continue
                    if (item.optString("type") == "text") item.optString("text").trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun textFromLegacyOutputs(outputs: JSONArray?): String? = outputs?.let { values ->
        buildList {
            for (index in 0 until values.length()) {
                val output = values.optJSONObject(index) ?: continue
                if (output.optString("type") == "text") output.optString("text").trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun groundingSources(steps: JSONArray?): List<GroundingSource> = buildList {
        for (index in 0 until (steps?.length() ?: 0)) {
            val step = steps?.optJSONObject(index) ?: continue
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (contentIndex in 0 until content.length()) {
                val annotations = content.optJSONObject(contentIndex)?.optJSONArray("annotations") ?: continue
                for (annotationIndex in 0 until annotations.length()) {
                    val annotation = annotations.optJSONObject(annotationIndex) ?: continue
                    if (annotation.optString("type") == "url_citation") add(
                        GroundingSource(annotation.optString("title").ifBlank { null }, annotation.optString("url").ifBlank { null }, GroundingSourceType.GOOGLE_SEARCH),
                    )
                    if (annotation.optString("type") == "place_citation") add(
                        GroundingSource(
                            title = annotation.optString("name").ifBlank { annotation.optString("title").ifBlank { null } },
                            url = annotation.optString("url").ifBlank { null },
                            sourceType = GroundingSourceType.GOOGLE_MAPS,
                            attribution = annotation.optString("attribution").ifBlank { null },
                            placeId = annotation.optString("place_id").ifBlank { null },
                        ),
                    )
                }
            }
        }
    }

    private fun isInteractionResource(root: JSONObject): Boolean =
        root.has("id") && (root.has("status") || root.has("steps") || root.has("outputs"))

    private val EMPTY_SUMMARY = GoogleInteractionResponseSummary(emptyList(), 0, 0)
}

private class GoogleHttpException(val statusCode: Int) : IllegalStateException("Google API HTTP $statusCode")
private class GoogleResponseDecodeException(reason: String) : IllegalStateException(reason)

fun GoogleHostedConnectionState.logName(): String = when (this) {
    GoogleHostedConnectionState.NotConfigured -> "NOT_CONFIGURED"
    GoogleHostedConnectionState.Testing -> "TESTING"
    GoogleHostedConnectionState.Ready -> "READY"
    GoogleHostedConnectionState.AuthenticationFailed -> "AUTHENTICATION_FAILED"
    GoogleHostedConnectionState.RateLimited -> "RATE_LIMITED"
    GoogleHostedConnectionState.ModelUnavailable -> "MODEL_UNAVAILABLE"
    GoogleHostedConnectionState.AuthorizationFailed -> "AUTHORIZATION_FAILED"
    GoogleHostedConnectionState.NetworkUnavailable -> "NETWORK_UNAVAILABLE"
    GoogleHostedConnectionState.ServiceUnavailable -> "SERVICE_ERROR"
}

fun connectionMessage(state: GoogleHostedConnectionState): String = when (state) {
    GoogleHostedConnectionState.NotConfigured -> "Hosted AI: enter a Google API key and test the connection."
    GoogleHostedConnectionState.Testing -> "Hosted AI: checking connection..."
    GoogleHostedConnectionState.AuthenticationFailed -> "Invalid Google API key."
    GoogleHostedConnectionState.NetworkUnavailable -> "No Internet connection."
    GoogleHostedConnectionState.RateLimited -> "Quota/rate limit reached."
    GoogleHostedConnectionState.ModelUnavailable -> "Configured model unavailable."
    GoogleHostedConnectionState.AuthorizationFailed -> "Google AI authorization failed."
    GoogleHostedConnectionState.ServiceUnavailable -> "Google AI service unavailable."
    GoogleHostedConnectionState.Ready -> "Hosted AI: Connected"
}
