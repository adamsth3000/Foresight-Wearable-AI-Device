package com.foresight.gateway.voice

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineEnglishVoiceSelectorTest {
    @Test
    fun `prefers an offline British English voice`() {
        val selection = OfflineEnglishVoiceSelector.select(
            listOf(
                voice("US offline", Locale.US),
                voice("GB network", Locale.UK, requiresNetwork = true),
                voice("GB offline", Locale.UK),
            ),
        )!!

        assertEquals("GB offline", selection.candidate.name)
        assertTrue(selection.isBritishEnglish)
    }

    @Test
    fun `never selects a network required voice`() {
        val selection = OfflineEnglishVoiceSelector.select(
            listOf(
                voice("GB network", Locale.UK, requiresNetwork = true),
                voice("US offline", Locale.US),
            ),
        )!!

        assertEquals("US offline", selection.candidate.name)
        assertFalse(selection.candidate.requiresNetwork)
        assertFalse(selection.isBritishEnglish)
    }

    @Test
    fun `chooses British English candidates deterministically`() {
        val selection = OfflineEnglishVoiceSelector.select(
            listOf(
                voice("Zeta", Locale.UK),
                voice("Alpha", Locale.UK),
            ),
        )!!

        assertEquals("Alpha", selection.candidate.name)
    }

    @Test
    fun `returns unavailable when no offline English voice exists`() {
        assertNull(
            OfflineEnglishVoiceSelector.select(
                listOf(
                    voice("GB network", Locale.UK, requiresNetwork = true),
                    voice("French offline", Locale.FRANCE),
                ),
            ),
        )
    }

    private fun voice(name: String, locale: Locale, requiresNetwork: Boolean = false) =
        OfflineEnglishVoiceSelector.Candidate(name, locale, requiresNetwork)
}
