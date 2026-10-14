package tv.own.owntv.features.sport

import androidx.annotation.StringRes
import java.util.Locale
import tv.own.owntv.R

/**
 * A broadcast language a user channel can be in, inferred from the two-letter country tag a provider
 * puts at the start of the name ("DE: SKY SPORT…", "UK| SKY SPORTS…", "SG: HUB PREMIER 1").
 *
 * A provider's country tag is not the audio language 1:1 — Germany/Austria/Switzerland all carry
 * German, the UK/US/Singapore all carry English — which is why several countries map to one
 * [SportLanguage] (see [SportLanguages.CountryLanguages]).
 */
enum class SportLanguage(@StringRes val labelRes: Int) {
    GERMAN(R.string.sport_language_german),
    ENGLISH(R.string.sport_language_english),
    SPANISH(R.string.sport_language_spanish),
    PORTUGUESE(R.string.sport_language_portuguese),
    FRENCH(R.string.sport_language_french),
    DUTCH(R.string.sport_language_dutch),
    ITALIAN(R.string.sport_language_italian),
    POLISH(R.string.sport_language_polish),
    TURKISH(R.string.sport_language_turkish),
    ARABIC(R.string.sport_language_arabic),
}

/**
 * Pure, stateless helpers that turn a provider's channel-name country tag into a [SportLanguage] and
 * filter the Sports-Hub channel matches by the viewer's preferred languages.
 *
 * The tag is the leading two-letter code followed by a separator — "DE:", "DE|", "UK |", "FR/". A
 * name with no such tag is left alone; with a filter active, a tag naming any other language is
 * dropped, so a German-only viewer no longer sees the Italian, Albanian or Serbian feeds of the
 * same match. The user picks the languages once (see `SportsPrefs` / the Sport settings).
 *
 * Like [SportNameMatch], everything here is dependency-free and unit-testable without Android.
 */
object SportLanguages {

    /** Display order in the picker and in the "Language: …" label. */
    val order: List<SportLanguage> = listOf(
        SportLanguage.GERMAN,
        SportLanguage.ENGLISH,
        SportLanguage.SPANISH,
        SportLanguage.PORTUGUESE,
        SportLanguage.FRENCH,
        SportLanguage.DUTCH,
        SportLanguage.ITALIAN,
        SportLanguage.POLISH,
        SportLanguage.TURKISH,
        SportLanguage.ARABIC,
    )

    /** An empty set means "all languages" — no filter. The default matches the app's audience. */
    val default: Set<SportLanguage> = setOf(SportLanguage.GERMAN)

    /**
     * The country tag at the start of [channelName], upper-cased, or null when there is none. Only a
     * two-letter code immediately followed by a separator counts ([CountryTag]).
     */
    fun countryCodeOf(channelName: String): String? =
        CountryTag.find(channelName)?.groupValues?.get(1)?.uppercase(Locale.ROOT)

    /** The [SportLanguage] a country tag stands for, or null when the tag is not a known country. */
    fun languageOfCountry(countryCode: String): SportLanguage? =
        CountryLanguages[countryCode.uppercase(Locale.ROOT)]

    /** Convenience: the language of a channel name, or null when it has no recognisable tag. */
    fun languageOfChannel(channelName: String): SportLanguage? =
        countryCodeOf(channelName)?.let(::languageOfCountry)

    /**
     * The matches to show for [languages]. An empty [languages] means "all" and returns [matches]
     * unchanged. Otherwise only channels whose country tag maps to one of [languages] are kept: a
     * channel with no tag, or a tag that is not a known country, is dropped. This is deliberately
     * strict — a provider prefixes a foreign feed with a quality/brand mark ("VIP: CANAL+…",
     * "SAT: ARENA…", "8K: beIN…") that is not a country, and those must not fill the limited slots
     * ahead of a real selected-language feed. If the filter would leave nothing, the full list is
     * returned, so the pane is never empty.
     *
     * Exact per-event channels ([ChannelMatch.isExact] — the name itself carries both sides, e.g. a
     * tennis PPV named for the duel) always survive: they are the event itself, not a
     * foreign-language feed of it.
     */
    fun filter(matches: List<ChannelMatch>, languages: Set<SportLanguage>): List<ChannelMatch> {
        if (languages.isEmpty()) return matches
        val kept = matches.filter { it.isExact || keeps(it.channelName, languages) }
        return kept.ifEmpty { matches }
    }

    /**
     * Strict variant of [filter] without the never-empty fallback: excluded languages are dropped
     * first (an exact per-event channel does NOT survive its language being excluded — excluding is
     * an explicit "never show", filtering is a preference), then only channels in [languages] are
     * kept, with exact per-event channels ([ChannelMatch.isExact]) always surviving the preference
     * step. An empty [languages] means "all" (minus [excluded]). A short list stays short — no
     * foreign feed is padded back in (see `docs/sport-roadmap.md` Punkt 4, Mindestquote).
     */
    fun filterStrict(
        matches: List<ChannelMatch>,
        languages: Set<SportLanguage>,
        excluded: Set<SportLanguage> = emptySet(),
    ): List<ChannelMatch> {
        val kept = matches.filterNot { languageOfChannel(it.channelName) in excluded }
        if (languages.isEmpty()) return kept
        return kept.filter { it.isExact || keeps(it.channelName, languages) }
    }

    private fun keeps(channelName: String, languages: Set<SportLanguage>): Boolean {
        val code = countryCodeOf(channelName) ?: return false
        val language = languageOfCountry(code) ?: return false
        return language in languages
    }

    /** "DE:", "DE|", "UK |", "FR/" — a two-letter code and a separator at the very start. */
    private val CountryTag = Regex("""^\s*([A-Za-z]{2})\s*[:|/]\s*""")

    /**
     * Country tag → language. Codes follow IPTV convention rather than strict ISO: providers write
     * "UK" where ISO says "GB", and Singapore ("SG") is an English feed. Extend this map — not the
     * enum — when a new country turns up; a code that is not here is a language the app cannot place,
     * and its channels are dropped while a filter is active (see [filter]).
     */
    private val CountryLanguages: Map<String, SportLanguage> = mapOf(
        // German
        "DE" to SportLanguage.GERMAN,
        "AT" to SportLanguage.GERMAN,
        "CH" to SportLanguage.GERMAN,
        // English
        "UK" to SportLanguage.ENGLISH,
        "GB" to SportLanguage.ENGLISH,
        "US" to SportLanguage.ENGLISH,
        "SG" to SportLanguage.ENGLISH,
        "IE" to SportLanguage.ENGLISH,
        "AU" to SportLanguage.ENGLISH,
        "NZ" to SportLanguage.ENGLISH,
        "CA" to SportLanguage.ENGLISH,
        "ZA" to SportLanguage.ENGLISH,
        // Spanish
        "ES" to SportLanguage.SPANISH,
        "MX" to SportLanguage.SPANISH,
        "AR" to SportLanguage.SPANISH,
        "CO" to SportLanguage.SPANISH,
        "CL" to SportLanguage.SPANISH,
        "PE" to SportLanguage.SPANISH,
        "VE" to SportLanguage.SPANISH,
        "UY" to SportLanguage.SPANISH,
        // Portuguese
        "PT" to SportLanguage.PORTUGUESE,
        "BR" to SportLanguage.PORTUGUESE,
        // French
        "FR" to SportLanguage.FRENCH,
        "BE" to SportLanguage.FRENCH,
        // Dutch (IPTV convention; "BE" stays French — a Flemish feed needs its own NL tag)
        "NL" to SportLanguage.DUTCH,
        // Italian
        "IT" to SportLanguage.ITALIAN,
        // Polish
        "PL" to SportLanguage.POLISH,
        // Turkish
        "TR" to SportLanguage.TURKISH,
        // Arabic (IPTV convention: provider country tags, not ISO-purist; "AR" stays
        // Argentina/Spanish to avoid breaking the existing ES mapping)
        "SA" to SportLanguage.ARABIC,
        "AE" to SportLanguage.ARABIC,
        "QA" to SportLanguage.ARABIC,
        "EG" to SportLanguage.ARABIC,
        "MA" to SportLanguage.ARABIC,
        "DZ" to SportLanguage.ARABIC,
        "TN" to SportLanguage.ARABIC,
        "IQ" to SportLanguage.ARABIC,
        "JO" to SportLanguage.ARABIC,
        "KW" to SportLanguage.ARABIC,
        "BH" to SportLanguage.ARABIC,
        "OM" to SportLanguage.ARABIC,
        "LB" to SportLanguage.ARABIC,
        "LY" to SportLanguage.ARABIC,
        "SD" to SportLanguage.ARABIC,
        "YE" to SportLanguage.ARABIC,
        "SY" to SportLanguage.ARABIC,
        "PS" to SportLanguage.ARABIC,
    )
}
