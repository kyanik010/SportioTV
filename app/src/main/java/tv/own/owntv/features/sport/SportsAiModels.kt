package tv.own.owntv.features.sport

import java.util.Locale

/**
 * Countries the AI recommendation can ask DeepSeek about.
 *
 * [code] is the ISO 3166-1 alpha-2 code sent to the API and stored in `SportsPrefs`. Both the
 * picker label and the English prompt name are derived from the platform locale at runtime, so there
 * are no country-name literals to maintain in this fork. `UK` is kept as an alias because IPTV
 * providers and models use it far more often than the ISO code `GB`.
 */
enum class AiCountry(val code: String) {
    GERMANY("DE"),
    AUSTRIA("AT"),
    SWITZERLAND("CH"),
    UNITED_KINGDOM("GB"),
    IRELAND("IE"),
    UNITED_STATES("US"),
    CANADA("CA"),
    FRANCE("FR"),
    ITALY("IT"),
    SPAIN("ES"),
    PORTUGAL("PT"),
    NETHERLANDS("NL"),
    BELGIUM("BE"),
    POLAND("PL"),
    TURKEY("TR"),
    SWEDEN("SE"),
    NORWAY("NO"),
    DENMARK("DK"),
    BRAZIL("BR"),
    MEXICO("MX"),
    ARGENTINA("AR"),
    AUSTRALIA("AU"),
    JAPAN("JP"),
    INDIA("IN"),
    SAUDI_ARABIA("SA"),
    SOUTH_AFRICA("ZA"),
    ;

    /** Localised country name for the TV picker (falls back to the ISO code). */
    fun displayName(locale: Locale = Locale.getDefault()): String =
        runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }
            .getOrDefault("")
            .ifBlank { code }

    /** English name for the model prompt, derived from the same ISO code. */
    val promptName: String
        get() = displayName(Locale.ENGLISH)

    companion object {
        /**
         * Resolve the model's answer. Accepts the ISO code, the English name and the common `UK` /
         * `USA` spellings, so a slightly off-schema answer is still usable.
         */
        fun fromCodeOrName(value: String): AiCountry? {
            val clean = value.trim()
            if (clean.isEmpty()) return null
            val upper = clean.uppercase(Locale.ROOT)
            AiCountry.entries.firstOrNull { it.code == upper }?.let { return it }
            when (upper) {
                "UK", "GREAT BRITAIN" -> return UNITED_KINGDOM
                "USA", "UNITED STATES OF AMERICA" -> return UNITED_STATES
            }
            return AiCountry.entries.firstOrNull {
                it.promptName.equals(clean, ignoreCase = true)
            }
        }
    }
}

/** One broadcaster the model named for one country, before the local catalogue lookup. */
data class AiBroadcast(
    val country: AiCountry,
    val channel: String,
)

/**
 * A broadcaster that the AI named **and** that exists in the user's own live playlists. This is the
 * only shape the UI offers for playback — the AI never supplies a stream URL, logo or channel id.
 */
data class AiSuggestion(
    val channelId: Long,
    val channelName: String,
    val logoUrl: String?,
    val country: AiCountry,
    /** The model's own broadcaster name, shown as the secondary line in the result list. */
    val broadcaster: String,
    /** Local match confidence; used only for ordering, never shown. */
    val score: Int,
    /** Quality marker parsed from the provider name (4K/FHD/HD/SD/MOBILE/RAW), if any. */
    val qualityLabel: String? = null,
)

/** Typed failures, so the Compose layer can resolve one localized sentence per case. */
enum class AiFailure {
    NO_KEY,
    INVALID_KEY,
    INSUFFICIENT_CREDIT,
    RATE_LIMITED,
    NETWORK,
    EMPTY,
    /** The AI functions are switched off in the settings — nothing was sent. */
    DISABLED,
    UNKNOWN,
}

/** A failed AI request; the message is deliberately not user-facing (the UI maps [failure]). */
class AiRequestException(
    val failure: AiFailure,
    cause: Throwable? = null,
) : Exception(cause)
