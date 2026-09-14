package com.foresight.gateway.voice

import com.foresight.gateway.voice.conversation.GoogleHostedConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedTalkEligibilityTest {
    @Test
    fun `ready hosted conversation is eligible without capture or vision`() {
        val eligibility = HostedTalkEligibilityPolicy.evaluate(
            activityResumed = true,
            runtimeState = VoiceRuntimeState.IDLE,
            inputReadiness = VoiceInputReadiness.READY,
            hostedReadiness = GoogleHostedConnectionState.Ready,
            microphoneAvailability = MicrophoneAvailability.Available,
        )

        assertTrue(eligibility.eligible)
        assertEquals("ready", eligibility.reason)
    }

    @Test
    fun `testing authentication and network states keep hosted talk disabled with distinct reasons`() {
        assertEquals("hosted_ai_testing", eligibilityFor(GoogleHostedConnectionState.Testing).reason)
        assertEquals("hosted_ai_auth_failed", eligibilityFor(GoogleHostedConnectionState.AuthenticationFailed).reason)
        assertEquals("hosted_ai_network_unavailable", eligibilityFor(GoogleHostedConnectionState.NetworkUnavailable).reason)
    }

    @Test
    fun `phone capture microphone conflict blocks otherwise ready hosted talk`() {
        val eligibility = HostedTalkEligibilityPolicy.evaluate(
            activityResumed = true,
            runtimeState = VoiceRuntimeState.IDLE,
            inputReadiness = VoiceInputReadiness.READY,
            hostedReadiness = GoogleHostedConnectionState.Ready,
            microphoneAvailability = MicrophoneAvailability.Unavailable("capture"),
        )

        assertFalse(eligibility.eligible)
        assertEquals("phone_capture_owns_microphone", eligibility.reason)
    }

    private fun eligibilityFor(state: GoogleHostedConnectionState): HostedTalkEligibility =
        HostedTalkEligibilityPolicy.evaluate(
            activityResumed = true,
            runtimeState = VoiceRuntimeState.IDLE,
            inputReadiness = VoiceInputReadiness.READY,
            hostedReadiness = state,
            microphoneAvailability = MicrophoneAvailability.Available,
        )
}
