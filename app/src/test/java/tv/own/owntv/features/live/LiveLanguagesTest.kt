package tv.own.owntv.features.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.sport.SportLanguage

/**
 * Roadmap point 7: the pure Live-TV language logic — country-tag derivation (via
 * `SportLanguages`), the playlist scan into language keys + Other, and the visibility filter.
 * No Android, no DAO. The Sport matcher itself is untouched (see `SportLanguagesTest`).
 */
class LiveLanguagesTest {

    // --- languageKeyOf: the tag forms a provider actually writes ---

    @Test
    fun colonBarAndSlashTagsMapToTheirLanguage() {
        assertEquals(SportLanguage.GERMAN.name, LiveLanguages.languageKeyOf("DE: SKY SPORT BUNDESLIGA 1 HD"))
        assertEquals(SportLanguage.ENGLISH.name, LiveLanguages.languageKeyOf("UK| SKY SPORTS MAIN EVENT"))
        assertEquals(SportLanguage.ENGLISH.name, LiveLanguages.languageKeyOf("UK | Sky Sports"))
        assertEquals(SportLanguage.FRENCH.name, LiveLanguages.languageKeyOf("FR/CANAL+ SPORT"))
        assertEquals(SportLanguage.DUTCH.name, LiveLanguages.languageKeyOf("NL: ESPN 1"))
        assertEquals(SportLanguage.ITALIAN.name, LiveLanguages.languageKeyOf("IT: SKY SPORT ARENA"))
        assertEquals(SportLanguage.POLISH.name, LiveLanguages.languageKeyOf("PL: CANAL+ SPORT"))
        assertEquals(SportLanguage.TURKISH.name, LiveLanguages.languageKeyOf("TR: BEIN SPORTS 1"))
    }

    @Test
    fun taglessUnknownAndAmbiguousTagsHaveNoLanguage() {
        assertNull(LiveLanguages.languageKeyOf("Sky Sport Bundesliga 1"))
        // Known country, but no language mapped.
        assertNull(LiveLanguages.languageKeyOf("AL: SUPER SPORT 3"))
        // Brand/quality mark, not a country tag.
        assertNull(LiveLanguages.languageKeyOf("VIP: CANAL+ PREMIER LEAGUE"))
        // Ambiguous two-letter tag with no mapping.
        assertNull(LiveLanguages.languageKeyOf("AM: MUSIC CHANNEL"))
    }

    // --- languageKeyOf with a provider group: groups carry the tag, not the channel names ---

    @Test
    fun aTaggedGroupPlacesEvenUntaggedChannels() {
        assertEquals(
            SportLanguage.DUTCH.name,
            LiveLanguages.languageKeyOf("ESPN 1 HD", "NL| Sports"),
        )
        assertEquals(
            SportLanguage.ITALIAN.name,
            LiveLanguages.languageKeyOf("Sky Sport 24", "IT| Sport"),
        )
        assertEquals(
            SportLanguage.POLISH.name,
            LiveLanguages.languageKeyOf("Eleven Sports 1", "PL| Sport"),
        )
        assertEquals(
            SportLanguage.TURKISH.name,
            LiveLanguages.languageKeyOf("beIN Sports 1", "TR| Spor"),
        )
    }

    @Test
    fun theGroupTagWinsOverTheChannelNameTag() {
        assertEquals(
            SportLanguage.DUTCH.name,
            LiveLanguages.languageKeyOf("DE: ESPN 1 HD", "NL| Sports"),
        )
    }

    @Test
    fun anUntaggedGroupFallsBackToTheChannelNameTag() {
        assertEquals(
            SportLanguage.GERMAN.name,
            LiveLanguages.languageKeyOf("DE: SKY SPORT 1", "Sports"),
        )
        assertEquals(
            SportLanguage.GERMAN.name,
            LiveLanguages.languageKeyOf("DE: SKY SPORT 1", null),
        )
        assertNull(LiveLanguages.languageKeyOf("Untagged Channel", "Sports"))
    }

    @Test
    fun groupScanCollectsGroupLanguagesInsteadOfOther() {
        val keys = LiveLanguages.availableKeysWithGroups(
            listOf(
                LiveLanguages.ScanEntry("ESPN 1 HD", "NL| Sports"),
                LiveLanguages.ScanEntry("Sky Sport 24", "IT| Sport"),
                LiveLanguages.ScanEntry("Eleven Sports 1", "PL| Sport"),
                LiveLanguages.ScanEntry("beIN Sports 1", "TR| Spor"),
                LiveLanguages.ScanEntry("Untagged Channel", "Untagged Group"),
            ),
        )
        assertEquals(
            setOf(
                SportLanguage.DUTCH.name,
                SportLanguage.ITALIAN.name,
                SportLanguage.POLISH.name,
                SportLanguage.TURKISH.name,
                LiveLanguages.OTHER,
            ),
            keys,
        )
    }

    // --- availableKeys: the playlist scan ---

    @Test
    fun scanCollectsMappedLanguagesAndOther() {
        val keys = LiveLanguages.availableKeys(
            listOf(
                "DE: SKY SPORT 1",
                "AT: ORF 1",
                "UK| SKY SPORTS NEWS",
                "AL: SUPER SPORT 3",
                "Untagged Channel",
            ),
        )
        assertEquals(
            setOf(SportLanguage.GERMAN.name, SportLanguage.ENGLISH.name, LiveLanguages.OTHER),
            keys,
        )
    }

    @Test
    fun scanWithoutLeftoversHasNoOther() {
        val keys = LiveLanguages.availableKeys(listOf("DE: A", "FR: B"))
        assertEquals(setOf(SportLanguage.GERMAN.name, SportLanguage.FRENCH.name), keys)
        assertFalse(LiveLanguages.OTHER in keys)
    }

    // --- isVisible: the Live list / guide filter ---

    @Test
    fun everythingStaysVisibleByDefault() {
        assertTrue(LiveLanguages.isVisible("DE: SKY", emptySet(), true))
        assertTrue(LiveLanguages.isVisible("UK| SKY", emptySet(), true))
        assertTrue(LiveLanguages.isVisible("IT: SKY", emptySet(), true))
        assertTrue(LiveLanguages.isVisible("Untagged", emptySet(), true))
        assertTrue(LiveLanguages.isVisible("ESPN 1", "NL| Sports", emptySet(), true))
    }

    @Test
    fun excludedLanguagesDisappearButOthersStay() {
        val excluded = setOf(SportLanguage.GERMAN.name)
        assertFalse(LiveLanguages.isVisible("DE: SKY SPORT 1", excluded, true))
        assertFalse(LiveLanguages.isVisible("AT: ORF 1", excluded, true))
        assertTrue(LiveLanguages.isVisible("UK| SKY SPORTS NEWS", excluded, true))
    }

    @Test
    fun otherFollowsItsOwnSwitch() {
        assertTrue(LiveLanguages.isVisible("AL: SUPER SPORT 3", emptySet(), true))
        assertFalse(LiveLanguages.isVisible("AL: SUPER SPORT 3", emptySet(), false))
        assertTrue(LiveLanguages.isVisible("Untagged Channel", emptySet(), true))
        assertFalse(LiveLanguages.isVisible("Untagged Channel", emptySet(), false))
        // An OTHER entry in the excluded set never hides anything — Other is switch-only.
        assertTrue(LiveLanguages.isVisible("Untagged Channel", setOf(LiveLanguages.OTHER), true))
    }

    @Test
    fun groupChannelsFollowTheGroupLanguageSwitch() {
        val excluded = setOf(SportLanguage.DUTCH.name)
        assertFalse(LiveLanguages.isVisible("ESPN 1 HD", "NL| Sports", excluded, true))
        assertTrue(LiveLanguages.isVisible("ESPN 1 HD", "NL| Sports", emptySet(), true))
        assertTrue(LiveLanguages.isVisible("Sky Sport 24", "IT| Sport", excluded, true))
        // Untouched group: the channel-name tag still decides.
        assertFalse(LiveLanguages.isVisible("DE: SKY SPORT 1", "Sports", setOf(SportLanguage.GERMAN.name), true))
    }

    // --- order ---

    @Test
    fun everySportLanguagePlusOtherHasADisplayOrder() {
        val expected = SportLanguage.values().map { it.name } + LiveLanguages.OTHER
        assertEquals(expected, LiveLanguages.order)
    }
}
