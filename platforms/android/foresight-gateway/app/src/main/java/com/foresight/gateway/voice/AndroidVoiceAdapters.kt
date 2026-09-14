package com.foresight.gateway.voice

import android.content.Context
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AndroidAudioCueOutput : AudioCueOutput {
    private val handler = Handler(Looper.getMainLooper())
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
    override fun playListeningCue(onComplete: () -> Unit) {
        Log.i(TAG, "VOICE_CUE_START")
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, CUE_DURATION_MILLIS.toInt())
        handler.postDelayed(onComplete, CUE_DURATION_MILLIS + CUE_GUARD_MILLIS)
    }
    override fun close() { toneGenerator.release() }

    private companion object {
        const val TAG = "ForesightVoice"
        const val CUE_DURATION_MILLIS = 100L
        const val CUE_GUARD_MILLIS = 150L
    }
}

class VoskAudioInputAdapter(
    private val context: Context,
    private val elapsedRealtimeMillis: () -> Long,
    private val timing: VoiceListeningTiming = VoiceListeningTiming(),
) : AudioInputAdapter, RecognitionListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var callback: ((Result<UserUtterance>) -> Unit)? = null
    private var recognizer: Recognizer? = null
    private var startTimeout: Runnable? = null
    private var commandTimeout: Runnable? = null
    private var trailingSilence: Runnable? = null
    private var latestText = ""
    private var listeningStartedAtElapsedMillis = 0L
    private var timingPolicy = VoiceListeningTimingPolicy(timing)
    private val modelLoader: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var modelReadiness = VoiceInputReadiness.MODEL_LOADING

    init {
        Log.i(TAG, "VOICE_VOSK_MODEL_STATE=${VoiceInputReadiness.MODEL_LOADING}")
        warmModel()
    }

    override fun readiness(): VoiceInputReadiness = modelReadiness

    override fun prepareOnce(onMicrophoneReady: () -> Unit, onResult: (Result<UserUtterance>) -> Unit) {
        check(callback == null) { "A Vosk recognition turn is already active." }
        when (modelReadiness) {
            VoiceInputReadiness.MODEL_LOADING -> {
                onResult(Result.failure(VoiceInputException(VoiceInputFailure.MODEL_LOADING)))
                return
            }
            VoiceInputReadiness.FAILED -> {
                onResult(Result.failure(VoiceInputException(VoiceInputFailure.MODEL_UNAVAILABLE)))
                return
            }
            VoiceInputReadiness.READY -> Unit
        }
        timingPolicy = VoiceListeningTimingPolicy(timing)
        latestText = ""
        Log.i(TAG, "VOICE_PREPARE_REQUEST elapsedMs=${elapsedRealtimeMillis()}")
        callback = onResult
        prepareRecognizer(requireNotNull(model), onMicrophoneReady)
    }

    private fun warmModel() {
        val assetRoot = VoskModelDirectoryProvider.MODEL_ASSET_ROOT
        val destination = java.io.File(context.filesDir, "models/$assetRoot")
        Log.i(TAG, "VOICE_VOSK_MODEL_REQUEST assetRoot=$assetRoot destination=$destination")
        modelLoader.execute {
            val result = VoskModelDirectoryProvider.prepare(context)
            mainHandler.post {
                when (result) {
                    is VoskModelPrepareResult.Reused -> loadExtractedModel(result.directory, "cached")
                    is VoskModelPrepareResult.Extracted -> loadExtractedModel(result.directory, "extracted")
                    is VoskModelPrepareResult.Failed -> failModelLoad(result)
                }
            }
        }
    }

    private fun loadExtractedModel(directory: java.io.File, source: String) {
        runCatching { Model(directory.absolutePath) }
            .onSuccess { loaded ->
                model = loaded
                modelReadiness = VoiceInputReadiness.READY
                Log.i(TAG, "VOICE_VOSK_MODEL_READY source=$source directory=$directory")
                Log.i(TAG, "VOICE_VOSK_MODEL_STATE=${VoiceInputReadiness.READY}")
            }
            .onFailure { error ->
                Log.w(TAG, "VOICE_VOSK_MODEL_FAILED stage=model_open directory=$directory type=${error.javaClass.simpleName}")
                modelReadiness = VoiceInputReadiness.FAILED
                Log.i(TAG, "VOICE_VOSK_MODEL_STATE=${VoiceInputReadiness.FAILED}")
            }
    }

    private fun failModelLoad(result: VoskModelPrepareResult.Failed) {
        Log.w(
            TAG,
            "VOICE_VOSK_MODEL_FAILED stage=${result.stage} assetRoot=${VoskModelDirectoryProvider.MODEL_ASSET_ROOT} " +
                "destination=${result.directory} missingRelativePath=${result.missingRelativePath}",
        )
        modelReadiness = VoiceInputReadiness.FAILED
        Log.i(TAG, "VOICE_VOSK_MODEL_STATE=${VoiceInputReadiness.FAILED}")
    }

    private fun prepareRecognizer(loaded: Model, onMicrophoneReady: () -> Unit) {
        runCatching {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            Log.i(
                TAG,
                "VOICE_AUDIORECORD_INIT source=VOICE_RECOGNITION sampleRate=${SAMPLE_RATE_HZ.toInt()} " +
                    "channel=MONO encoding=PCM_16BIT minBufferSize=$minBufferSize bufferSize=$AUDIO_RECORD_BUFFER_BYTES",
            )
            Recognizer(loaded, SAMPLE_RATE_HZ).also { recognizer = it }.let { preparedRecognizer ->
                Log.i(TAG, "VOICE_VOSK_RECOGNIZER_CREATED")
                SpeechService(preparedRecognizer, SAMPLE_RATE_HZ)
            }.also {
                speechService = it
            }
            Log.i(TAG, "VOICE_MIC_READY elapsedMs=${elapsedRealtimeMillis()}")
            onMicrophoneReady()
        }.onFailure { error ->
            Log.w(TAG, "VOICE_EXCEPTION stage=audio_record_init class=${error.javaClass.simpleName}")
            finish(Result.failure(VoiceInputException(VoiceInputFailure.MICROPHONE_UNAVAILABLE)), "mic_prepare_failed")
        }
    }

    override fun startListening() {
        val service = speechService ?: return
        listeningStartedAtElapsedMillis = elapsedRealtimeMillis()
        Log.i(TAG, "VOICE_LISTEN_ARMED elapsedMs=$listeningStartedAtElapsedMillis")
        if (!service.startListening(this)) {
            finish(Result.failure(VoiceInputException(VoiceInputFailure.MICROPHONE_START_FAILED)), "mic_start_failed")
            return
        }
        Log.i(TAG, "VOICE_CUE_COMPLETE elapsedMs=$listeningStartedAtElapsedMillis")
        Log.i(TAG, "VOICE_LISTEN_START elapsedMs=$listeningStartedAtElapsedMillis")
        Log.i(TAG, "VOICE_AUDIORECORD_STARTED elapsedMs=$listeningStartedAtElapsedMillis")
        startTimeout = Runnable { handleDecision(timingPolicy.onStartTimeout()) }
            .also {
                Log.i(TAG, "VOICE_TIMEOUT_SCHEDULED type=NO_SPEECH delayMs=${timing.maxStartMillis}")
                mainHandler.postDelayed(it, timing.maxStartMillis)
            }
        commandTimeout = Runnable { handleDecision(timingPolicy.onCommandTimeout()) }
            .also {
                Log.i(TAG, "VOICE_TIMEOUT_SCHEDULED type=COMMAND delayMs=${timing.maxCommandMillis}")
                mainHandler.postDelayed(it, timing.maxCommandMillis)
            }
    }

    override fun onPartialResult(hypothesis: String?) {
        val text = extractText(hypothesis)
        Log.i(TAG, "VOICE_CALLBACK_PARTIAL empty=${text.isBlank()}")
        handleDecoderText(text)
    }
    override fun onResult(hypothesis: String?) {
        val text = extractText(hypothesis)
        Log.i(TAG, "VOICE_CALLBACK_RESULT empty=${text.isBlank()}")
        handleDecoderText(text)
    }
    override fun onFinalResult(hypothesis: String?) {
        val text = extractText(hypothesis)
        Log.i(TAG, "VOICE_CALLBACK_FINAL empty=${text.isBlank()}")
        if (text.isBlank() && latestText.isBlank()) {
            Log.i(TAG, "VOICE_FINAL_EMPTY_IGNORED elapsedMs=${elapsedRealtimeMillis()}")
            return
        }
        if (text.isNotBlank()) latestText = text
        finishSuccess("vosk_final")
    }
    override fun onError(exception: Exception?) {
        Log.w(TAG, "VOICE_VOSK_RECOGNITION_FAILED type=${exception?.javaClass?.simpleName ?: "Unknown"}")
        finish(Result.failure(VoiceInputException(VoiceInputFailure.RECOGNITION_FAILED)), "recognition_failed")
    }
    override fun onTimeout() { finish(Result.failure(IllegalStateException("Voice command timed out.")), "vosk_timeout") }

    override fun cancel() { finish(Result.failure(InterruptedException("Voice command cancelled.")), "cancelled") }

    private fun handleDecoderText(text: String) {
        if (text.isBlank()) return
        latestText = text
        when (val decision = timingPolicy.onDecoderText(text)) {
            VoiceListeningDecision.KeepListening -> Unit
            is VoiceListeningDecision.ScheduleTrailingSilence -> {
                startTimeout?.let(mainHandler::removeCallbacks)
                startTimeout = null
                trailingSilence?.let(mainHandler::removeCallbacks)
                trailingSilence = Runnable {
                    Log.i(TAG, "VOICE_TRAILING_SILENCE elapsedMs=${elapsedRealtimeMillis()}")
                    finishSuccess("trailing_silence")
                }.also { mainHandler.postDelayed(it, decision.delayMillis) }
                Log.i(TAG, "VOICE_SPEECH_STARTED elapsedMs=${elapsedRealtimeMillis()}")
            }
            VoiceListeningDecision.EndNoSpeech,
            VoiceListeningDecision.EndCommandTimeout -> Unit
        }
    }

    private fun handleDecision(decision: VoiceListeningDecision) {
        when (decision) {
            VoiceListeningDecision.EndNoSpeech -> finish(
                Result.failure(VoiceInputException(VoiceInputFailure.NO_SPEECH_TIMEOUT)),
                "no_speech_timeout",
            )
            VoiceListeningDecision.EndCommandTimeout -> {
                if (latestText.isNotBlank()) finishSuccess("command_timeout")
                else finish(Result.failure(VoiceInputException(VoiceInputFailure.COMMAND_TIMEOUT)), "command_timeout")
            }
            else -> Unit
        }
    }

    private fun finishSuccess(reason: String) = finish(
        Result.success(UserUtterance(latestText, null, elapsedRealtimeMillis(), VoiceInputSource.PUSH_TO_TALK)),
        reason,
    )

    private fun extractText(hypothesis: String?): String = runCatching {
        JSONObject(hypothesis.orEmpty()).optString("text")
            .ifBlank { JSONObject(hypothesis.orEmpty()).optString("partial") }
            .trim()
    }.getOrDefault("")

    private fun finish(result: Result<UserUtterance>, reason: String) {
        val completion = callback ?: return
        callback = null
        listOfNotNull(startTimeout, commandTimeout, trailingSilence).forEach(mainHandler::removeCallbacks)
        startTimeout = null
        commandTimeout = null
        trailingSilence = null
        Log.i(TAG, "VOICE_COMPLETE reason=$reason elapsedMs=${elapsedRealtimeMillis()}")
        Log.i(TAG, "VOICE_LISTEN_END reason=$reason elapsedMs=${elapsedRealtimeMillis()}")
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        recognizer?.close()
        recognizer = null
        mainHandler.post { completion(result) }
    }

    override fun close() {
        cancel()
        modelLoader.shutdownNow()
        model?.close()
        model = null
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000f
        const val AUDIO_RECORD_BUFFER_BYTES = 6_400
        const val TAG = "ForesightVoice"
    }
}

class AndroidOfflineSpeechOutput(context: Context) : SpeechOutput {
    private var textToSpeech: TextToSpeech? = null
    private var initialized = false
    private var pending: ((Result<Unit>) -> Unit)? = null
    private var selectedVoiceName: String? = null

    init {
        textToSpeech = TextToSpeech(context) { status ->
            initialized = status == TextToSpeech.SUCCESS
            if (initialized) {
                Log.i(TAG, "FORESIGHT_TTS_VOICE_REQUEST locale=en-GB")
                configureOfflineEnglishVoice()
            }
        }.also { tts ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = complete(Result.success(Unit))
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = complete(Result.failure(IllegalStateException("Local speech output failed.")))
            })
        }
    }

    override fun speak(text: String, onComplete: (Result<Unit>) -> Unit) {
        val tts = textToSpeech
        if (!initialized || tts == null || !configureOfflineEnglishVoice()) {
            Log.w(TAG, "VOICE_TTS_OFFLINE_UNAVAILABLE")
            onComplete(Result.failure(IllegalStateException("An offline English text-to-speech voice is unavailable.")))
            return
        }
        if (pending != null) {
            onComplete(Result.failure(IllegalStateException("Speech output is already active.")))
            return
        }
        pending = onComplete
        Log.i(TAG, "FORESIGHT_TTS_SPEAK_START")
        val status = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        if (status == TextToSpeech.ERROR) complete(Result.failure(IllegalStateException("Local speech output could not start.")))
    }

    private fun configureOfflineEnglishVoice(): Boolean {
        val tts = textToSpeech ?: return false
        tts.language = Locale.UK
        val voicesByName = tts.voices.orEmpty().associateBy { it.name }
        val selection = OfflineEnglishVoiceSelector.select(
            voicesByName.values.map { candidate: Voice ->
                OfflineEnglishVoiceSelector.Candidate(
                    name = candidate.name,
                    locale = candidate.locale,
                    requiresNetwork = candidate.isNetworkConnectionRequired,
                )
            },
        ) ?: run {
            Log.w(TAG, "FORESIGHT_TTS_VOICE_UNAVAILABLE locale=en-GB")
            return false
        }
        if (!selection.isBritishEnglish) {
            Log.w(TAG, "FORESIGHT_TTS_VOICE_UNAVAILABLE locale=en-GB fallback=${selection.candidate.locale.toLanguageTag()}")
        }
        val voice = voicesByName.getValue(selection.candidate.name)
        val configured = tts.voice?.name == voice.name || tts.setVoice(voice) == TextToSpeech.SUCCESS
        if (configured && selectedVoiceName != voice.name) {
            selectedVoiceName = voice.name
            Log.i(
                TAG,
                "FORESIGHT_TTS_VOICE_SELECTED locale=${voice.locale.toLanguageTag()} offline=true name=${voice.name}",
            )
        }
        return configured
    }

    private fun complete(result: Result<Unit>) { pending?.also { pending = null; it(result) } }
    override fun close() { textToSpeech?.stop(); textToSpeech?.shutdown(); textToSpeech = null; pending = null }

    private companion object { const val TAG = "ForesightVoice" }
}
