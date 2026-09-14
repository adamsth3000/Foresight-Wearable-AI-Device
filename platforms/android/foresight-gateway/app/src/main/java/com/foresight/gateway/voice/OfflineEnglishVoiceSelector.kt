package com.foresight.gateway.voice

import java.util.Locale

/** Selects a local English voice without allowing network-only TTS fallback. */
internal object OfflineEnglishVoiceSelector {
    data class Candidate(
        val name: String,
        val locale: Locale,
        val requiresNetwork: Boolean,
    )

    data class Selection(
        val candidate: Candidate,
        val isBritishEnglish: Boolean,
    )

    fun select(candidates: Collection<Candidate>): Selection? {
        val offlineEnglish = candidates
            .asSequence()
            .filter { candidate ->
                candidate.locale.language.equals(Locale.ENGLISH.language, ignoreCase = true) &&
                    !candidate.requiresNetwork
            }
            .sortedWith(compareBy<Candidate>({ it.name }, { it.locale.toLanguageTag() }))
            .toList()
        val britishEnglish = offlineEnglish.firstOrNull { candidate ->
            candidate.locale.country.equals(Locale.UK.country, ignoreCase = true)
        }
        return when {
            britishEnglish != null -> Selection(britishEnglish, isBritishEnglish = true)
            offlineEnglish.isNotEmpty() -> Selection(offlineEnglish.first(), isBritishEnglish = false)
            else -> null
        }
    }
}
