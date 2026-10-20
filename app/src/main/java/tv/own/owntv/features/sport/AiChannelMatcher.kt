package tv.own.owntv.features.sport

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.live.ProviderTags

/**
 * Matches the AI's broadcaster names against the user's **own** live channel catalogue.
 *
 * The model only supplies names ("Sky Sport Bundesliga 1", "Peacock"); this class searches the
 * playlists, scores each hit on whole-name and token coverage, and returns at most [limit] distinct
 * channels. It never contacts the network and never trusts a model-supplied id or URL: playback is
 * only possible for a [ChannelEntity] found here.
 *
 * The search is deliberately conservative:
 *  - the full broadcaster name is tried first (the cheap, precise probe);
 *  - only when that returns nothing are individual distinctive tokens tried, and the scoring still
 *    requires most of the query's words to be present;
 *  - a recognised country tag must match the country the model named for that broadcaster
 *    (`DE:` for Germany, `UK`/`GB` for the United Kingdom). Untagged channels stay eligible,
 *    because many providers do not tag their feeds at all.
 *
 * Within a country the best feed wins: name evidence, a quality tier parsed from the channel name
 * (4K/FHD/HD/SD/MOBILE/RAW), a country-tag bonus, and duplicate collapsing by normalised channel
 * name. The final list is grouped by country so the user sees one country's feeds at a time instead
 * of an interleaved mix.
 */
class AiChannelMatcher(
    private val channelDao: ChannelDao,
    private val sources: LiveSourceIdsProvider,
) {

    suspend fun match(broadcasts: List<AiBroadcast>, limit: Int): List<AiSuggestion> =
        withContext(Dispatchers.IO) {
            if (broadcasts.isEmpty()) return@withContext emptyList()
            val sourceIds = sources.liveSourceIds()
            if (sourceIds.isEmpty()) return@withContext emptyList()

            val countryOrder = broadcasts.map { it.country }.distinct()
                .sortedBy { AiCountry.entries.indexOf(it) }
            val byCountry = LinkedHashMap<AiCountry, LinkedHashMap<String, AiSuggestion>>()
            for (broadcast in broadcasts) {
                val candidates = search(broadcast.channel, sourceIds)
                    // IPTV playlists often import category separators as channels, e.g.
                    // "#### SPORT ####" or "### NEWS ###". They are not channels and must never
                    // consume one of the suggestion slots.
                    .filterNot { isCategoryMarker(it.name) }
                    // A German broadcaster query must not surface a Canadian or Hong Kong feed
                    // just because the channel name matches. Untagged feeds stay eligible.
                    .filter { channelCountryMatches(it.name, broadcast.country) }
                val ranked = candidates.mapNotNull { channel ->
                    val baseScore = channelScore(channel.name, broadcast.channel)
                    if (baseScore <= 0) {
                        null
                    } else {
                        val quality = channelQuality(channel.name)
                        if (quality.hidden) {
                            null
                        } else {
                            AiSuggestion(
                                channelId = channel.id,
                                channelName = channel.name,
                                logoUrl = channel.logoUrl,
                                country = broadcast.country,
                                broadcaster = broadcast.channel,
                                score = baseScore +
                                    countryBonus(channel.name, broadcast.country) +
                                    quality.score,
                                qualityLabel = quality.label,
                            )
                        }
                    }
                }.sortedByDescending { it.score }.take(MaxPerBroadcaster)
                val countryMatches = byCountry.getOrPut(broadcast.country) { LinkedHashMap() }
                for (suggestion in ranked) {
                    val key = normalizedChannelKey(suggestion.channelName)
                    val existing = countryMatches[key]
                    // Collapse DAZN 1 / DAZN 1 HD / DAZN 1 MOBILE to one row per country; the best
                    // quality/name score keeps the slot.
                    if (existing == null || suggestion.score > existing.score) {
                        countryMatches[key] = suggestion
                    }
                }
            }

            val cappedLimit = limit.coerceIn(
                SportsMatcher.MinChannelLimit,
                SportsMatcher.MaxChannelLimit,
            )
            val perCountry = countryOrder.mapNotNull { country ->
                byCountry[country]?.values?.sortedByDescending { it.score }
            }
            val picked = ArrayList<AiSuggestion>(cappedLimit)
            val used = HashSet<Long>()
            var added = true
            while (picked.size < cappedLimit && added) {
                added = false
                for (matches in perCountry) {
                    val next = matches.firstOrNull { it.channelId !in used } ?: continue
                    used += next.channelId
                    picked += next
                    added = true
                    if (picked.size >= cappedLimit) break
                }
            }
            // Round-robin selects fairly across countries; the final order is grouped by country
            // (Germany, Austria, UK, …) and sorted by score inside each group.
            val countryIndex = countryOrder.withIndex().associate { it.value to it.index }
            picked.sortedWith(
                compareBy<AiSuggestion> { countryIndex[it.country] ?: Int.MAX_VALUE }
                    .thenByDescending { it.score },
            )
        }

    /**
     * Channel candidates for one broadcaster. The full phrase is the precise probe; token fallback
     * only runs when it found nothing (a provider may write "Sky Bundesliga" where the model wrote
     * "Sky Sport Bundesliga"). The cap is deliberately far above the final list so scoring has room.
     */
    private suspend fun search(broadcaster: String, sourceIds: List<Long>): List<ChannelEntity> {
        val query = sanitize(broadcaster)
        if (query.length < MinQueryLength) return emptyList()
        val direct = runCatching { channelDao.searchList(query, sourceIds, SearchLimit) }
            .getOrDefault(emptyList())
        if (direct.isNotEmpty()) return direct

        val tokens = SportNameMatch.normalize(broadcaster).trim()
            .split(' ')
            .filter { it.length >= MinFallbackTokenLength }
            .distinct()
            .sortedByDescending { it.length }
            .take(MaxFallbackTokens)
        val out = LinkedHashMap<Long, ChannelEntity>()
        for (token in tokens) {
            runCatching { channelDao.searchList(token, sourceIds, SearchLimit) }
                .getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.id, it) }
        }
        return out.values.toList()
    }

    /** SQL `LIKE` treats `%` and `_` as wildcards; a model answer must not control that. */
    private fun sanitize(query: String): String =
        query.replace(LikeWildcard, " ").replace(LikeSingleWildcard, " ").trim()

    /**
     * Category separators imported as channels: many providers ship names such as
     * "#### SPORT ####", "### NEWS ###" or "----- KIDS -----". They are navigation markers, not
     * streams. Three or more `#` is the reliable marker seen in practice; a name with no letter or
     * digit at all is the general form of the same import artefact.
     */
    private fun isCategoryMarker(channelName: String): Boolean {
        if (channelName.count { it == HashMark } >= MinCategoryHashes) return true
        return channelName.none { it.isLetterOrDigit() }
    }

    /**
     * True when a recognised provider country tag agrees with the country the model named.
     * Untagged channels pass (providers often omit the tag); a tag that names another country is a
     * hard rejection, which is what removes `CA: DAZN 1` from a German query. Tags that are not ISO
     * country codes (e.g. a provider's `HD:` prefix) are treated as "no country tag".
     */
    private fun channelCountryMatches(channelName: String, country: AiCountry): Boolean {
        val tag = SportLanguages.countryCodeOf(channelName)?.uppercase(Locale.ROOT) ?: return true
        if (tag !in KnownCountryTags) return true
        val wanted = if (country == AiCountry.UNITED_KINGDOM) {
            setOf(UnitedKingdomIso, UnitedKingdomIptv)
        } else {
            setOf(country.code)
        }
        return tag in wanted
    }

    /**
     * Quality heuristic from the provider's own name/tags. A provider's tag can lie, but the
     * explicit words still beat an alphabetical accident:
     *  - 4K/UHD > FHD/1080 > HD/720 > unknown > SD/480/576;
     *  - MOBILE/LOW is a strong penalty, RAW a small one;
     *  - TEST/BACKUP/24-7 are never a real match feed and are hidden entirely.
     */
    private fun channelQuality(channelName: String): ChannelQuality {
        val parsedTags = ProviderTags.parse(channelName).tags
        val normalized = SportNameMatch.normalize(channelName)
        val words = normalized.trim().split(' ')
        fun hasAny(vararg values: String): Boolean = values.any { value ->
            value in words || parsedTags.any { it.equals(value, ignoreCase = true) }
        }
        if (hasAny(TestWord, BackupWord) || normalized.contains(TwentyFourSevenPhrase)) {
            return ChannelQuality(score = 0, hidden = true, label = null)
        }
        val mobile = hasAny(MobileWord, LowWord)
        val raw = hasAny(RawWord)
        val tier = when {
            hasAny(FourKWord, UhdWord, Resolution2160) -> Quality4K
            hasAny(FhdWord, Resolution1080) -> QualityFhd
            hasAny(HdWord, Resolution720) -> QualityHd
            hasAny(SdWord, Resolution576, Resolution480) -> QualitySd
            else -> QualityUnknown
        }
        val score = tier + (if (mobile) LowQualityPenalty else 0) + (if (raw) RawPenalty else 0)
        val label = when {
            mobile -> MobileLabel
            raw -> RawLabel
            tier == Quality4K -> FourKLabel
            tier == QualityFhd -> FhdLabel
            tier == QualityHd -> HdLabel
            tier == QualitySd -> SdLabel
            else -> null
        }
        return ChannelQuality(score, hidden = false, label = label)
    }

    /** Collapse quality variants of the same feed within one country (`DAZN 1`/`DAZN 1 HD`). */
    private fun normalizedChannelKey(channelName: String): String {
        val tags = SportLanguages.tagWordsOf(channelName)
        return SportNameMatch.normalize(channelName).trim()
            .split(' ')
            .filterNot { it in tags || it in GenericChannelWords }
            .joinToString(" ")
            .ifBlank { SportNameMatch.normalize(channelName).trim() }
    }

    /**
     * Score a catalogue channel against the model's broadcaster name:
     *  - exact normalized name (100) and full phrase containment (90) are strong;
     *  - a short brand channel whose words are all named by the model ("ARD Das Erste" vs
     *    `DE: ARD`) is a subset hit (85);
     *  - otherwise most of the broadcaster's meaningful words must be present, so "Sky Sport
     *    Bundesliga" cannot match "Sky Sports News".
     */
    private fun channelScore(candidateName: String, broadcaster: String): Int {
        val candidate = SportNameMatch.normalize(candidateName)
        val query = SportNameMatch.normalize(broadcaster).trim()
        if (query.isEmpty()) return 0
        if (candidate.trim() == query) return ExactNameScore
        if (SportNameMatch.containsPhrase(query, candidate)) return PhraseScore

        val queryTokens = query.split(' ')
            .filter { it.length >= MinScoringTokenLength || it.all(Char::isDigit) }
        if (queryTokens.isEmpty()) return 0
        val candidateCore = coreTokens(candidateName)
        if (candidateCore.isNotEmpty() && candidateCore.all { it in queryTokens }) return SubsetScore

        val hits = queryTokens.count { SportNameMatch.containsWord(it, candidate) }
        val coverage = hits.toFloat() / queryTokens.size
        return when {
            coverage >= StrongCoverage -> PartialScore + (coverage * 10).toInt()
            coverage >= WeakCoverage && hits >= MinWeakHits -> WeakScore
            else -> 0
        }
    }

    /**
     * The meaningful words of a provider channel name: its country tag and generic quality words
     * ("HD", "TV", "Live") are removed first, so `DE: ARD HD` can be recognised as "ARD" when the
     * model writes "ARD Das Erste".
     */
    private fun coreTokens(channelName: String): Set<String> {
        val tags = SportLanguages.tagWordsOf(channelName)
        return SportNameMatch.normalize(channelName).trim()
            .split(' ')
            .filter { it.length >= MinScoringTokenLength }
            .filterNot { it in tags || it in GenericChannelWords }
            .toSet()
    }

    /** Small bonus when the provider's country tag agrees with the country the model named. */
    private fun countryBonus(channelName: String, country: AiCountry): Int {
        val tag = SportLanguages.countryCodeOf(channelName) ?: return 0
        val wanted = if (country == AiCountry.UNITED_KINGDOM) {
            setOf(UnitedKingdomIso, UnitedKingdomIptv)
        } else {
            setOf(country.code)
        }
        return if (tag in wanted) CountryScore else 0
    }

    private data class ChannelQuality(val score: Int, val hidden: Boolean, val label: String?)

    private companion object {
        const val SearchLimit = 80
        const val MaxPerBroadcaster = 4
        const val MaxFallbackTokens = 4
        const val MinQueryLength = 2
        const val MinFallbackTokenLength = 3
        const val MinScoringTokenLength = 2
        const val MinWeakHits = 2
        const val ExactNameScore = 100
        const val PhraseScore = 90
        const val SubsetScore = 85
        const val PartialScore = 70
        const val WeakScore = 55
        const val CountryScore = 8
        const val StrongCoverage = 0.8f
        const val WeakCoverage = 0.6f
        const val UnitedKingdomIso = "GB"
        const val UnitedKingdomIptv = "UK"
        const val LikeWildcard = "%"
        const val LikeSingleWildcard = "_"
        const val HashMark = '#'
        const val MinCategoryHashes = 3
        const val Quality4K = 30
        const val QualityFhd = 24
        const val QualityHd = 18
        const val QualityUnknown = 10
        const val QualitySd = 0
        const val LowQualityPenalty = -50
        const val RawPenalty = -15
        const val TestWord = "test"
        const val BackupWord = "backup"
        const val TwentyFourSevenPhrase = " 24 7 "
        const val FourKWord = "4k"
        const val UhdWord = "uhd"
        const val Resolution2160 = "2160"
        const val FhdWord = "fhd"
        const val Resolution1080 = "1080"
        const val HdWord = "hd"
        const val Resolution720 = "720"
        const val SdWord = "sd"
        const val Resolution576 = "576"
        const val Resolution480 = "480"
        const val MobileWord = "mobile"
        const val LowWord = "low"
        const val RawWord = "raw"
        const val FourKLabel = "4K"
        const val FhdLabel = "FHD"
        const val HdLabel = "HD"
        const val SdLabel = "SD"
        const val MobileLabel = "MOBILE"
        const val RawLabel = "RAW"
        private val KnownCountryTags: Set<String> =
            Locale.getISOCountries().toSet() + setOf(UnitedKingdomIptv)
        private val GenericChannelWords = setOf(
            "hd", "fhd", "uhd", "4k", "sd", "tv", "live", "channel", "feed", "hq",
            "mobile", "low", "raw", "backup", "test", "1080", "720", "576", "480", "2160",
        )
    }
}
