package com.foresight.gateway.voice

import android.util.Log

interface AudioInputAdapter : AutoCloseable {
    fun readiness(): VoiceInputReadiness
    fun prepareOnce(onMicrophoneReady: () -> Unit, onResult: (Result<UserUtterance>) -> Unit)
    fun startListening()
    fun cancel()
}

interface AudioCueOutput : AutoCloseable { fun playListeningCue(onComplete: () -> Unit) }

interface SpeechOutput : AutoCloseable {
    fun speak(text: String, onComplete: (Result<Unit>) -> Unit)
}

class VoiceTurnController(
    private val microphoneAvailability: () -> MicrophoneAvailability,
    private val audioInput: AudioInputAdapter,
    private val audioCueOutput: AudioCueOutput,
    private val speechOutput: SpeechOutput,
    private val requestRouter: com.foresight.gateway.voice.conversation.VoiceRequestRouter,
    private val onStateChanged: (VoiceRuntimeState) -> Unit,
    private val onResponse: (ForesightResponse) -> Unit,
) : AutoCloseable {
    var state: VoiceRuntimeState = VoiceRuntimeState.IDLE
        private set
    private var preparing = false

    fun conversationReadiness() = requestRouter.readiness()

    fun inputReadiness(): VoiceInputReadiness = audioInput.readiness()

    fun onTrimMemory(level: Int) = requestRouter.onTrimMemory(level)

    fun startPushToTalk(): Boolean {
        if (state != VoiceRuntimeState.IDLE || preparing) return false
        when (val availability = microphoneAvailability()) {
            is MicrophoneAvailability.Unavailable -> {
                completeWithoutSpeech(ForesightResponse(availability.reason, false))
                return false
            }
            MicrophoneAvailability.Available -> {
                val readiness = audioInput.readiness()
                if (readiness != VoiceInputReadiness.READY) {
                    completeWithoutSpeech(
                        ForesightResponse(
                            if (readiness == VoiceInputReadiness.MODEL_LOADING) {
                                VoiceInputFailure.MODEL_LOADING.userMessage
                            } else {
                                VoiceInputFailure.MODEL_UNAVAILABLE.userMessage
                            },
                            false,
                        ),
                    )
                    return false
                }
                preparing = true
                runCatching {
                    audioInput.prepareOnce(
                        onMicrophoneReady = {
                            preparing = false
                            transitionTo(VoiceRuntimeState.LISTENING_FOR_COMMAND)
                            audioCueOutput.playListeningCue(audioInput::startListening)
                        },
                        onResult = ::onRecognitionComplete,
                    )
                }.onFailure {
                    completeWithoutSpeech(ForesightResponse("I couldn't start local voice input.", false))
                }
                return true
            }
        }
    }

    /**
     * Runs a wake-only acknowledgement before the established cue and command capture path.
     * Manual push-to-talk continues through [startPushToTalk] and never invokes this method.
     */
    fun startWakeAcknowledgedTurn(
        acknowledgement: String,
        onAcknowledgementStarted: () -> Unit,
        onAcknowledgementCompleted: () -> Unit,
        onAcknowledgementFailed: () -> Unit,
    ): Boolean {
        if (state != VoiceRuntimeState.IDLE || preparing) return false
        when (val availability = microphoneAvailability()) {
            is MicrophoneAvailability.Unavailable -> {
                completeWithoutSpeech(ForesightResponse(availability.reason, false))
                return false
            }
            MicrophoneAvailability.Available -> {
                if (audioInput.readiness() != VoiceInputReadiness.READY) return false
                transitionTo(VoiceRuntimeState.SPEAKING)
                onAcknowledgementStarted()
                speechOutput.speak(acknowledgement) { result ->
                    if (result.isSuccess) onAcknowledgementCompleted() else onAcknowledgementFailed()
                    beginPreparedCommandTurn()
                }
                return true
            }
        }
    }

    private fun beginPreparedCommandTurn() {
        if (state != VoiceRuntimeState.SPEAKING || preparing) return
        preparing = true
        runCatching {
            audioInput.prepareOnce(
                onMicrophoneReady = {
                    preparing = false
                    transitionTo(VoiceRuntimeState.LISTENING_FOR_COMMAND)
                    audioCueOutput.playListeningCue(audioInput::startListening)
                },
                onResult = ::onRecognitionComplete,
            )
        }.onFailure {
            completeWithoutSpeech(ForesightResponse("I couldn't start local voice input.", false))
        }
    }

    private fun onRecognitionComplete(result: Result<UserUtterance>) {
        if (state != VoiceRuntimeState.LISTENING_FOR_COMMAND && !preparing) return
        preparing = false
        transitionTo(VoiceRuntimeState.PROCESSING)
        val utterance = result.getOrElse {
            completeWithoutSpeech(ForesightResponse(failureMessage(it), false))
            return
        }
        if (utterance.text.isBlank()) {
            completeWithoutSpeech(ForesightResponse("I didn't hear a command.", false))
            return
        }
        requestRouter.route(utterance, ::onConversationComplete)
    }

    private fun onConversationComplete(result: Result<com.foresight.gateway.voice.conversation.ConversationResponse>) {
        if (state != VoiceRuntimeState.PROCESSING) return
        val response = result.fold(
            onSuccess = {
                if (it.modelIdentity.startsWith("google/gemma-3n-E2B-it-litert-lm")) {
                    Log.i(TAG, "FORESIGHT_AI_TTS_START")
                }
                ForesightResponse(it.spokenText, requestSpeech = true)
            },
            onFailure = { error ->
                ForesightResponse(
                    (error as? com.foresight.gateway.voice.conversation.ConversationUnavailableException)?.userMessage
                        ?: "Foresight AI could not answer that right now.",
                    requestSpeech = false,
                )
            },
        )
        onResponse(response)
        if (!response.requestSpeech) {
            transitionTo(VoiceRuntimeState.IDLE)
            return
        }
        transitionTo(VoiceRuntimeState.SPEAKING)
        speechOutput.speak(response.text) { result ->
            if (result.isFailure) {
                onResponse(ForesightResponse("Local speech output is unavailable. Install an offline English text-to-speech voice.", false))
            }
            transitionTo(VoiceRuntimeState.IDLE)
        }
    }

    private fun completeWithoutSpeech(response: ForesightResponse) {
        preparing = false
        onResponse(response)
        transitionTo(VoiceRuntimeState.IDLE)
    }

    private fun transitionTo(next: VoiceRuntimeState) {
        state = next
        onStateChanged(next)
    }

    override fun close() {
        preparing = false
        audioInput.cancel()
        audioInput.close()
        requestRouter.close()
        audioCueOutput.close()
        speechOutput.close()
        transitionTo(VoiceRuntimeState.IDLE)
    }

    private fun failureMessage(error: Throwable): String =
        (error as? VoiceInputException)?.failure?.userMessage
            ?: VoiceInputFailure.RECOGNITION_FAILED.userMessage

    private companion object {
        const val TAG = "VoiceTurnController"
    }
}
