package dev.jaganet.server.services

import java.util.Locale

/** Country names for the apps' server list. */
object Countries {
    private val RU: Locale = Locale.of("ru")
    private val ISO: Set<String> = Locale.getISOCountries().toSet()

    /** The Russian name of an ISO 3166-1 alpha-2 code ("FI" -> "Финляндия"); the code itself if unknown. */
    fun ru(code: String): String {
        val cc = code.trim().uppercase()
        if (cc !in ISO) return cc
        return Locale.of("", cc).getDisplayCountry(RU).takeIf { it.isNotBlank() && it != cc } ?: cc
    }
}
