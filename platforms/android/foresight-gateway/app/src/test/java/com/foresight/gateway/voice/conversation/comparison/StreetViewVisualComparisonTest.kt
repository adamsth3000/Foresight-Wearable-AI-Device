package com.foresight.gateway.voice.conversation.comparison

import com.foresight.gateway.vision.geolocation.StreetViewCandidate
import com.foresight.gateway.vision.geolocation.StreetViewReferenceBatch
import com.foresight.gateway.vision.geolocation.StreetViewReferenceImage
import com.foresight.gateway.voice.conversation.ConversationVisualFrame
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StreetViewVisualComparisonTest {
    @Test fun `encoder places live frame before ordered references and emits only comparison fields`() {
        val encoded = StreetViewComparisonRequestEncoder.encode(request())
        val root = JSONObject(encoded.json)
        val input = root.getJSONArray("input")

        assertEquals(listOf("generation_config", "input", "model", "response_format", "store", "system_instruction"), root.keys().asSequence().toList().sorted())
        assertEquals(5, input.length())
        assertEquals("text", input.getJSONObject(0).getString("type"))
        assertEquals("image", input.getJSONObject(1).getString("type"))
        assertEquals("AQI=", input.getJSONObject(1).getString("data"))
        assertTrue(input.getJSONObject(0).getString("text").contains("candidate_0 id=candidate-0 heading=0.0"))
        assertTrue(input.getJSONObject(0).getString("text").contains("candidate_2 id=candidate-2 heading=90.0"))
        assertFalse(root.has("tools"))
        assertFalse(root.getBoolean("store"))
        assertEquals("application/json", root.getJSONObject("response_format").getString("mime_type"))
        assertEquals("MATCHED", root.getJSONObject("response_format").getJSONObject("schema").getJSONObject("properties").getJSONObject("outcome").getJSONArray("enum").getString(0))
    }

    @Test fun `decoder accepts a matched supplied candidate`() {
        val decoded = StreetViewComparisonResponseDecoder.decode(response("MATCHED", "candidate-1", "HIGH"), candidateIds())
        val result = (decoded as StreetViewComparisonDecodeResult.Valid).result

        assertEquals(StreetViewComparisonOutcome.MATCHED, result.outcome)
        assertEquals("candidate-1", result.selectedCandidateId)
        assertEquals(StreetViewComparisonConfidence.HIGH, result.confidence)
    }

    @Test fun `decoder accepts inconclusive and no match without selected candidate`() {
        val inconclusive = StreetViewComparisonResponseDecoder.decode(response("INCONCLUSIVE", null, "LOW"), candidateIds()) as StreetViewComparisonDecodeResult.Valid
        val noMatch = StreetViewComparisonResponseDecoder.decode(response("NO_MATCH", null, "MEDIUM"), candidateIds()) as StreetViewComparisonDecodeResult.Valid

        assertNull(inconclusive.result.selectedCandidateId)
        assertNull(noMatch.result.selectedCandidateId)
    }

    @Test fun `decoder rejects missing matched unknown malformed incomplete and unknown enum responses`() {
        assertFailure(response("MATCHED", null, "HIGH"), "matched_without_candidate")
        assertFailure(response("MATCHED", "unknown", "HIGH"), "unknown_candidate")
        assertFailure("{", "malformed_response")
        assertFailure(JSONObject().put("output_text", """{"outcome":"NO_MATCH"}""").toString(), "missing_field")
        assertFailure(response("OTHER", null, "HIGH"), "unsupported_outcome")
        assertFailure(response("NO_MATCH", null, "OTHER"), "unsupported_confidence")
    }

    @Test fun `client returns decoded success and preserves exactly one completion`() {
        val call = RecordingCall(StreetViewComparisonHttpResponse(200, response("MATCHED", "candidate-0", "MEDIUM")))
        val client = GoogleStreetViewComparisonClient({ "key" }, callFactory = object : StreetViewComparisonHttpCallFactory { override fun create() = call })
        val latch = CountDownLatch(1)
        var callbacks = 0
        var result: StreetViewVisualComparisonResult? = null

        client.compare(request()) { callbacks += 1; result = it; latch.countDown() }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, callbacks)
        assertEquals(StreetViewComparisonOutcome.MATCHED, result?.outcome)
        assertFalse(call.cancelled)
        client.close()
    }

    @Test fun `client maps non success responses to failed`() {
        listOf(400, 401, 403, 429, 500).forEach { status ->
            val client = GoogleStreetViewComparisonClient(
                { "key" },
                callFactory = object : StreetViewComparisonHttpCallFactory { override fun create() = RecordingCall(StreetViewComparisonHttpResponse(status, "{}")) },
            )
            val latch = CountDownLatch(1)
            var result: StreetViewVisualComparisonResult? = null
            client.compare(request()) { result = it; latch.countDown() }
            assertTrue("status=$status", latch.await(2, TimeUnit.SECONDS))
            assertEquals("status=$status", StreetViewComparisonOutcome.FAILED, result?.outcome)
            client.close()
        }
    }

    @Test fun `client maps connection failure to failed`() {
        val client = GoogleStreetViewComparisonClient(
            { "key" },
            callFactory = object : StreetViewComparisonHttpCallFactory {
                override fun create() = object : StreetViewComparisonHttpCall {
                    override fun post(apiKey: String, body: String): StreetViewComparisonHttpResponse = throw java.net.ConnectException()
                    override fun cancel() = Unit
                }
            },
        )
        val latch = CountDownLatch(1)
        var result: StreetViewVisualComparisonResult? = null
        client.compare(request()) { result = it; latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(StreetViewComparisonOutcome.FAILED, result?.outcome)
        client.close()
    }

    @Test fun `client cancellation disconnects active call and completes once`() {
        val call = BlockingCall()
        val client = GoogleStreetViewComparisonClient({ "key" }, callFactory = object : StreetViewComparisonHttpCallFactory { override fun create() = call })
        val latch = CountDownLatch(1)
        val completions = AtomicInteger()
        var result: StreetViewVisualComparisonResult? = null
        client.compare(request()) { result = it; completions.incrementAndGet(); latch.countDown() }
        assertTrue(call.started.await(2, TimeUnit.SECONDS))
        client.cancelActiveRequest()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(call.cancelled)
        assertEquals(StreetViewComparisonOutcome.CANCELLED, result?.outcome)
        assertEquals(1, completions.get())
        client.close()
    }

    @Test fun `deadline disconnects active call and fails once`() {
        val call = BlockingCall()
        val client = GoogleStreetViewComparisonClient(
            { "key" },
            callFactory = object : StreetViewComparisonHttpCallFactory { override fun create() = call },
            deadlineMillis = 50,
        )
        val latch = CountDownLatch(1)
        val completions = AtomicInteger()
        var result: StreetViewVisualComparisonResult? = null
        client.compare(request()) { result = it; completions.incrementAndGet(); latch.countDown() }
        assertTrue(call.started.await(2, TimeUnit.SECONDS))
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(call.cancelled)
        assertEquals(StreetViewComparisonOutcome.FAILED, result?.outcome)
        assertEquals(1, completions.get())
        client.close()
    }

    @Test fun `consumer retains batch until completion then closes it`() {
        val client = DeferredClient()
        val consumer = StreetViewVisualComparisonConsumer(client)
        val bytes = byteArrayOf(7, 8)
        val batch = batch(bytes)
        var result: StreetViewVisualComparisonResult? = null

        consumer.consume(batch, liveFrame()) { result = it }

        assertEquals(1, batch.images().size)
        client.complete(StreetViewVisualComparisonResult(StreetViewComparisonOutcome.NO_MATCH, confidence = StreetViewComparisonConfidence.LOW))
        assertEquals(StreetViewComparisonOutcome.NO_MATCH, result?.outcome)
        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(runCatching { batch.images() }.isFailure)
        consumer.close()
    }

    @Test fun `consumer cancellation closes batch after cancellation callback`() {
        val client = DeferredClient()
        val consumer = StreetViewVisualComparisonConsumer(client)
        val bytes = byteArrayOf(7)
        val batch = batch(bytes)
        consumer.consume(batch, liveFrame()) { }

        consumer.cancelActiveConsumption()
        assertFalse(bytes.all { it == 0.toByte() })
        client.complete(StreetViewVisualComparisonResult(StreetViewComparisonOutcome.CANCELLED))

        assertTrue(client.cancelled)
        assertTrue(bytes.all { it == 0.toByte() })
        consumer.close()
    }

    @Test fun `replacement comparison closes the prior batch`() {
        val client = DeferredClient()
        val consumer = StreetViewVisualComparisonConsumer(client)
        val firstBytes = byteArrayOf(1)
        val secondBytes = byteArrayOf(2)
        consumer.consume(batch(firstBytes), liveFrame()) { }
        consumer.consume(batch(secondBytes), liveFrame()) { }

        assertTrue(firstBytes.all { it == 0.toByte() })
        assertFalse(secondBytes.all { it == 0.toByte() })
        client.complete(StreetViewVisualComparisonResult(StreetViewComparisonOutcome.NO_MATCH, confidence = StreetViewComparisonConfidence.LOW))
        assertTrue(secondBytes.all { it == 0.toByte() })
        consumer.close()
    }

    private fun assertFailure(body: String, expected: String) {
        val decoded = StreetViewComparisonResponseDecoder.decode(body, candidateIds()) as StreetViewComparisonDecodeResult.Failure
        assertEquals(expected, decoded.type)
    }

    private fun request() = StreetViewVisualComparisonRequest(
        byteArrayOf(1, 2),
        listOf(
            StreetViewComparisonReference("candidate-0", 0f, byteArrayOf(3)),
            StreetViewComparisonReference("candidate-1", 45f, byteArrayOf(4)),
            StreetViewComparisonReference("candidate-2", 90f, byteArrayOf(5)),
        ),
    )

    private fun candidateIds() = setOf("candidate-0", "candidate-1", "candidate-2")

    private fun response(outcome: String, candidate: String?, confidence: String) = JSONObject()
        .put("output_text", JSONObject()
            .put("outcome", outcome)
            .put("selectedCandidateId", candidate ?: JSONObject.NULL)
            .put("confidence", confidence)
            .put("rationale", "stable geometry")
            .toString())
        .toString()

    private fun liveFrame() = ConversationVisualFrame(byteArrayOf(1), 1, 1, 1L, "GOPRO_PREVIEW")
    private fun batch(bytes: ByteArray) = StreetViewReferenceBatch(mutableListOf(StreetViewReferenceImage(StreetViewCandidate("candidate-0", "pano", headingDegrees = 0f), bytes, "image/jpeg", 1, 1, 0L)))

    private class RecordingCall(private val response: StreetViewComparisonHttpResponse) : StreetViewComparisonHttpCall {
        var cancelled = false
        override fun post(apiKey: String, body: String) = response
        override fun cancel() { cancelled = true }
    }

    private class DeferredClient : StreetViewComparisonClient {
        private var callback: ((StreetViewVisualComparisonResult) -> Unit)? = null
        var cancelled = false
        override fun compare(request: StreetViewVisualComparisonRequest, onComplete: (StreetViewVisualComparisonResult) -> Unit) {
            callback?.invoke(StreetViewVisualComparisonResult(StreetViewComparisonOutcome.CANCELLED))
            callback = onComplete
        }
        override fun cancelActiveRequest() { cancelled = true }
        override fun close() = Unit
        fun complete(result: StreetViewVisualComparisonResult) { callback?.invoke(result); callback = null }
    }

    private class BlockingCall : StreetViewComparisonHttpCall {
        val started = CountDownLatch(1)
        @Volatile var cancelled = false
        override fun post(apiKey: String, body: String): StreetViewComparisonHttpResponse {
            started.countDown()
            while (!cancelled) Thread.sleep(5)
            throw java.net.SocketException("cancelled")
        }
        override fun cancel() { cancelled = true }
    }
}
