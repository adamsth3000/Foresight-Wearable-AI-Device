package com.foresight.gateway.voice.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.net.UnknownHostException
import java.util.concurrent.Executor
import com.foresight.gateway.vision.SceneLocation
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class GoogleHostedConversationEngineTest {
    @Test
    fun `hosted engine calls Google directly after successful preflight`() {
        val engine = engine(200)
        var preflight: GoogleHostedConnectionState? = null
        engine.testConnection { preflight = it }

        val response = invoke(engine, request()).getOrThrow()

        assertEquals(GoogleHostedConnectionState.Ready, preflight)
        assertEquals(ConversationReadiness.READY, engine.readiness())
        assertEquals(GoogleHostedModelSpec.modelId, response.modelIdentity)
        assertEquals(null, response.proposedAction)
    }

    @Test
    fun `current steps response reaches ready during preflight and supplies conversation text`() {
        val engine = GoogleHostedConversationEngine(
            apiKey = { "private-key" },
            transport = object : GoogleHostedTransport {
                override fun post(apiKey: String, body: String): GoogleHostedHttpResponse =
                    GoogleHostedHttpResponse(200, CURRENT_INTERACTION_RESPONSE)
            },
            executor = Executor { it.run() },
        )

        engine.testConnection { }
        val response = invoke(engine, request()).getOrThrow()

        assertEquals(GoogleHostedConnectionState.Ready, engine.connectionState())
        assertEquals("A concise answer.", response.spokenText)
    }

    @Test
    fun `preflight maps credentials quota and unsupported model distinctly`() {
        assertEquals(GoogleHostedConnectionState.ServiceUnavailable, preflightState(400))
        assertEquals(GoogleHostedConnectionState.AuthenticationFailed, preflightState(401))
        assertEquals(GoogleHostedConnectionState.AuthorizationFailed, preflightState(403))
        assertEquals(GoogleHostedConnectionState.RateLimited, preflightState(429))
        assertEquals(GoogleHostedConnectionState.ModelUnavailable, preflightState(404))
    }

    @Test
    fun `network failure is isolated from voice routing`() {
        val engine = GoogleHostedConversationEngine(
            apiKey = { "private-key" },
            transport = object : GoogleHostedTransport {
                override fun post(apiKey: String, body: String): GoogleHostedHttpResponse = throw UnknownHostException()
            },
            executor = Executor { it.run() },
        )

        var state: GoogleHostedConnectionState? = null
        engine.testConnection { state = it }

        assertEquals(GoogleHostedConnectionState.NetworkUnavailable, state)
        assertEquals(ConversationReadiness.FAILED, engine.readiness())
        assertEquals("No Internet connection.", connectionMessage(checkNotNull(state)))
    }

    @Test
    fun `engine reads a key stored after construction when testing`() {
        var storedKey: String? = null
        val observedKeys = mutableListOf<String>()
        val engine = GoogleHostedConversationEngine(
            apiKey = { storedKey },
            transport = object : GoogleHostedTransport {
                override fun post(apiKey: String, body: String): GoogleHostedHttpResponse {
                    observedKeys += apiKey
                    return GoogleHostedHttpResponse(200, """{"output_text":"READY"}""")
                }
            },
            executor = Executor { it.run() },
        )

        storedKey = "saved-after-engine-construction"
        engine.testConnection { }

        assertEquals(listOf("saved-after-engine-construction"), observedKeys)
        assertEquals(GoogleHostedConnectionState.Ready, engine.connectionState())
    }

    @Test
    fun `hosted payload contains context policy history and never an API key`() {
        val payload = GoogleInteractionRequestEncoder.conversation(request()).json

        assertTrue(payload.contains("You are Foresight"))
        assertTrue(payload.contains("chair x1, confidence 0.91-0.91"))
        assertTrue(payload.contains("Earlier question"))
        assertFalse(payload.contains("private-key"))
        assertFalse(payload.contains("v1/conversation"))
        assertFalse(payload.contains("foresight_policy_version"))
    }

    @Test
    fun `conversation encoder contains only supported interaction fields`() {
        val encoded = GoogleInteractionRequestEncoder.conversation(request())
        val payload = JSONObject(encoded.json)

        assertEquals(listOf("generation_config", "input", "model", "store", "system_instruction", "tools"), encoded.topLevelKeys)
        assertEquals(1, encoded.inputCount)
        assertEquals(1, encoded.historyTurns)
        assertTrue(encoded.hasSystemPolicy)
        assertTrue(encoded.hasContext)
        assertEquals(listOf("max_output_tokens", "thinking_level"), encoded.generationConfigKeys)
        assertFalse(payload.has("foresight_policy_version"))
        assertTrue(payload.optString("system_instruction").isNotBlank())
        assertTrue(payload.optString("input").contains("Earlier question"))
    }

    @Test
    fun `bad request leaves an already ready hosted engine ready`() {
        var calls = 0
        val engine = GoogleHostedConversationEngine(
            apiKey = { "private-key" },
            transport = object : GoogleHostedTransport {
                override fun post(apiKey: String, body: String): GoogleHostedHttpResponse {
                    calls += 1
                    return if (calls == 1) GoogleHostedHttpResponse(200, CURRENT_INTERACTION_RESPONSE)
                    else GoogleHostedHttpResponse(400, """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"Invalid request"}}""")
                }
            },
            executor = Executor { it.run() },
        )

        engine.testConnection { }
        val failure = invoke(engine, request()).exceptionOrNull()

        assertTrue(failure is ConversationUnavailableException)
        assertEquals(GoogleHostedConnectionState.Ready, engine.connectionState())
        assertEquals(ConversationReadiness.READY, engine.readiness())
        assertNotEquals("Invalid Google API key.", (failure as ConversationUnavailableException).userMessage)
    }

    @Test
    fun `visual request encodes a JPEG image and Google Search then clears image bytes`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val encoded = GoogleInteractionRequestEncoder.conversation(
            request().copy(visualFrame = ConversationVisualFrame(bytes, 2, 2, 1L, "GOPRO_PREVIEW")),
        )
        val root = JSONObject(encoded.json)
        val input = root.getJSONArray("input")

        assertTrue(encoded.usedVisualContext)
        assertTrue(encoded.searchEnabled)
        assertEquals("image", input.getJSONObject(1).getString("type"))
        assertEquals("image/jpeg", input.getJSONObject(1).getString("mime_type"))
        assertEquals("google_search", root.getJSONArray("tools").getJSONObject(0).getString("type"))
        encoded.clearVisualFrame()
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `eligible visual place request includes Maps only with image and location`() {
        val context = request().context.copy(mapsLocation = SceneLocation(1.0, 2.0, 8f, null, 10_000L, "gps"))
        val encoded = GoogleInteractionRequestEncoder.conversation(
            request().copy(
                utterance = "What building am I looking at?",
                context = context,
                timestampElapsedMillis = 20_000L,
                visualFrame = ConversationVisualFrame(byteArrayOf(1), 1, 1, 1L, "GOPRO_PREVIEW"),
            ),
        )
        val tools = JSONObject(encoded.json).getJSONArray("tools")

        assertTrue(encoded.mapsEnabled)
        assertEquals("google_search", tools.getJSONObject(0).getString("type"))
        assertEquals("google_maps", tools.getJSONObject(1).getString("type"))
    }

    @Test
    fun `decoder retains Google Search URL citations`() {
        val decoded = GoogleInteractionResponseDecoder.decode(
            """{"id":"int_1","status":"completed","steps":[{"type":"google_search_call"},{"type":"model_output","content":[{"type":"text","text":"Answer","annotations":[{"type":"url_citation","title":"Museum","url":"https://example.test/museum"}]}]}]}""",
        ) as GoogleInteractionDecodeResult.ValidText

        assertTrue(decoded.usedGoogleSearch)
        assertEquals("Museum", decoded.groundingSources.single().title)
        assertEquals("https://example.test/museum", decoded.groundingSources.single().url)
    }

    @Test
    fun `decoder retains Maps place citation separately from search`() {
        val decoded = GoogleInteractionResponseDecoder.decode(
            """{"id":"int_1","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"Answer","annotations":[{"type":"place_citation","name":"Cafe","url":"https://maps.example/cafe","attribution":"Maps","place_id":"place-1"}]}]}]}""",
        ) as GoogleInteractionDecodeResult.ValidText

        val source = decoded.groundingSources.single()
        assertEquals(GroundingSourceType.GOOGLE_MAPS, source.sourceType)
        assertEquals("place-1", source.placeId)
        assertEquals("Maps", source.attribution)
    }

    @Test
    fun `local model absence does not block hosted selection`() {
        val hosted = engine(200).also { it.testConnection {} }
        val local = RecordingEngine(ConversationReadiness.MODEL_NOT_INSTALLED)
        val selector = SelectableConversationEngine({ ConversationBackendMode.HOSTED }, hosted, local)

        assertEquals(ConversationReadiness.READY, selector.readiness())
        assertEquals(GoogleHostedModelSpec.modelId, invoke(selector, request()).getOrThrow().modelIdentity)
        assertEquals(0, local.calls)
    }

    @Test
    fun `decoder accepts metadata and joins model output text blocks`() {
        val decoded = GoogleInteractionResponseDecoder.decode(
            """{"id":"int_1","status":"completed","usage":{"total_tokens":5},"steps":[{"type":"model_output","content":[{"type":"thought","text":"hidden"},{"type":"text","text":"First."},{"type":"text","text":"Second."}]}]}""",
        )

        assertEquals("First.\nSecond.", (decoded as GoogleInteractionDecodeResult.ValidText).text)
    }

    @Test
    fun `decoder distinguishes valid empty malformed and unexpected interactions`() {
        assertTrue(GoogleInteractionResponseDecoder.decode("""{"id":"int_1","status":"completed","steps":[]}""") is GoogleInteractionDecodeResult.ValidEmpty)
        assertEquals("json_parse", (GoogleInteractionResponseDecoder.decode("{") as GoogleInteractionDecodeResult.Failure).reason)
        assertEquals("unexpected_shape", (GoogleInteractionResponseDecoder.decode("{}") as GoogleInteractionDecodeResult.Failure).reason)
        assertEquals("api_error_response", (GoogleInteractionResponseDecoder.decode("""{"id":"int_1","status":"failed"}""") as GoogleInteractionDecodeResult.Failure).reason)
    }

    private fun preflightState(status: Int): GoogleHostedConnectionState {
        val engine = engine(status)
        var state: GoogleHostedConnectionState? = null
        engine.testConnection { state = it }
        return checkNotNull(state)
    }

    private fun engine(status: Int) = GoogleHostedConversationEngine(
        apiKey = { "private-key" },
        transport = object : GoogleHostedTransport {
            override fun post(apiKey: String, body: String): GoogleHostedHttpResponse =
                GoogleHostedHttpResponse(status, if (status in 200..299) CURRENT_INTERACTION_RESPONSE else "{}")
        },
        executor = Executor { it.run() },
    )

    private fun invoke(engine: ConversationEngine, request: ConversationRequest): Result<ConversationResponse> {
        var completed: Result<ConversationResponse>? = null
        suspend { engine.respond(request) }.startCoroutine(object : Continuation<ConversationResponse> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<ConversationResponse>) { completed = result }
        })
        return checkNotNull(completed)
    }

    private fun request() = ConversationRequest(
        utterance = "What is interesting around me?",
        context = ForesightContextSnapshot(
            captureActive = true,
            visualizationMode = "VISION",
            observationAgeMillis = 200L,
            observedObjects = listOf(ObservedObject("chair", 0.91f)),
            selectedVisualContext = null,
            visualConceptMemory = ContextAvailability.UNAVAILABLE,
            timestampElapsedMillis = 1L,
        ),
        history = listOf(ConversationTurn(ConversationTurnRole.USER, "Earlier question", 0L)),
        selectedVisualContext = null,
        timestampElapsedMillis = 1L,
    )

    private class RecordingEngine(private val state: ConversationReadiness) : ConversationEngine {
        var calls = 0
        override fun readiness() = state
        override suspend fun respond(request: ConversationRequest): ConversationResponse {
            calls += 1
            return ConversationResponse("local", modelIdentity = "local")
        }
        override fun cancelActiveRequest() = Unit
        override fun close() = Unit
    }

    private companion object {
        const val CURRENT_INTERACTION_RESPONSE = """{"id":"int_1","model":"gemini-3.8-flash","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"A concise answer."}]}],"usage":{"total_tokens":4}}"""
    }
}
