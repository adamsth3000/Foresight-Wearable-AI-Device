package com.foresight.gateway.voice

import com.foresight.gateway.voice.conversation.GoogleHostedConnectionState

data class HostedTalkEligibility(
    val eligible: Boolean,
    val reason: String,
)

/** Pure presentation policy for enabling a hosted push-to-talk turn. */
object HostedTalkEligibilityPolicy {
    fun evaluate(
        activityResumed: Boolean,
        runtimeState: VoiceRuntimeState,
        inputReadiness: VoiceInputReadiness,
        hostedReadiness: GoogleHostedConnectionState,
        microphoneAvailability: MicrophoneAvailability,
    ): HostedTalkEligibility = when {
        !activityResumed -> HostedTalkEligibility(false, "activity_not_foreground")
        runtimeState != VoiceRuntimeState.IDLE -> HostedTalkEligibility(false, "voice_turn_active")
        inputReadiness == VoiceInputReadiness.MODEL_LOADING -> HostedTalkEligibility(false, "voice_model_loading")
        inputReadiness == VoiceInputReadiness.FAILED -> HostedTalkEligibility(false, "voice_model_unavailable")
        hostedReadiness != GoogleHostedConnectionState.Ready -> HostedTalkEligibility(false, hostedReason(hostedReadiness))
        microphoneAvailability is MicrophoneAvailability.Unavailable -> HostedTalkEligibility(false, "phone_capture_owns_microphone")
        else -> HostedTalkEligibility(true, "ready")
    }

    private fun hostedReason(state: GoogleHostedConnectionState): String = when (state) {
        GoogleHostedConnectionState.NotConfigured -> "hosted_ai_not_configured"
        GoogleHostedConnectionState.Testing -> "hosted_ai_testing"
        GoogleHostedConnectionState.AuthenticationFailed -> "hosted_ai_auth_failed"
        GoogleHostedConnectionState.RateLimited -> "hosted_ai_rate_limited"
        GoogleHostedConnectionState.ModelUnavailable -> "hosted_ai_model_unavailable"
        GoogleHostedConnectionState.AuthorizationFailed -> "hosted_ai_authorization_failed"
        GoogleHostedConnectionState.NetworkUnavailable -> "hosted_ai_network_unavailable"
        GoogleHostedConnectionState.ServiceUnavailable -> "hosted_ai_service_error"
        GoogleHostedConnectionState.Ready -> "ready"
    }
}
