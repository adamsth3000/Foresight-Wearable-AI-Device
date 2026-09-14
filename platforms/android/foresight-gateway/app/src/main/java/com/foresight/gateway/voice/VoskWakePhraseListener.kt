package com.foresight.gateway.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.Executors

/** A foreground-only Vosk session with an exact three-phrase grammar. */
class VoskWakePhraseListener(private val context: Context) : WakePhraseListener, RecognitionListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var sessionId = 0L
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var onReady: (() -> Unit)? = null
    private var onDetected: ((WakePhraseType) -> Unit)? = null
    private var onFailure: ((Throwable) -> Unit)? = null
    private var detected = false

    override fun start(onReady: () -> Unit, onDetected: (WakePhraseType) -> Unit, onFailure: (Throwable) -> Unit) {
        stop()
        val id = ++sessionId
        this.onReady = onReady
        this.onDetected = onDetected
        this.onFailure = onFailure
        detected = false
        loader.execute {
            val result = VoskModelDirectoryProvider.prepare(context)
            mainHandler.post {
                if (id != sessionId) return@post
                val directory = when (result) {
                    is VoskModelPrepareResult.Reused -> result.directory
                    is VoskModelPrepareResult.Extracted -> result.directory
                    is VoskModelPrepareResult.Failed -> {
                        fail(IllegalStateException("Wake model preparation failed: ${result.stage}"))
                        return@post
                    }
                }
                runCatching {
                    Model(directory.absolutePath).also { model = it }.let {
                        Recognizer(it, SAMPLE_RATE_HZ, WakePhraseGrammar.recognizerGrammarJson()).also { recognizer = it }
                    }.let { SpeechService(it, SAMPLE_RATE_HZ).also { service -> speechService = service } }
                }.onFailure(::fail).onSuccess { service ->
                    if (!service.startListening(this)) {
                        fail(IllegalStateException("Wake microphone could not start."))
                        return@onSuccess
                    }
                    this.onReady?.invoke()
                }
            }
        }
    }

    override fun onPartialResult(hypothesis: String?) = Unit
    override fun onResult(hypothesis: String?) = accept(hypothesis)
    override fun onFinalResult(hypothesis: String?) = accept(hypothesis)
    override fun onError(exception: Exception?) = fail(exception ?: IllegalStateException("Wake recognition failed."))
    override fun onTimeout() = fail(IllegalStateException("Wake recognition timed out."))

    private fun accept(hypothesis: String?) {
        val text = runCatching { JSONObject(hypothesis.orEmpty()).optString("text") }.getOrDefault("")
        val phrase = WakePhraseGrammar.match(text) ?: return
        if (detected) return
        detected = true
        mainHandler.post { onDetected?.invoke(phrase) }
    }

    private fun fail(error: Throwable) {
        val callback = onFailure ?: return
        stop()
        mainHandler.post { callback(error) }
    }

    override fun stop() {
        sessionId++
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        onReady = null
        onDetected = null
        onFailure = null
        detected = false
    }

    override fun close() {
        stop()
        loader.shutdownNow()
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000f
    }
}
