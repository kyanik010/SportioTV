package tv.own.owntv.features.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderTagsTest {

    @Test
    fun prefixAndSuperscriptTagAreSplitOff() =
        assertEquals(ProviderName("Sky Cinema Premieren", listOf("HD"), "DE"), ProviderTags.parse("DE| Sky Cinema Premieren ᴴᴰ"))

    @Test
    fun spaceBeforeTheBarIsAllowed() = assertEquals("DE", ProviderTags.parse("DE | Prime ᴿᴬᵂ").tag)

    @Test
    fun eachRunIsOneTag() =
        assertEquals(listOf("HD", "DOLBY"), ProviderTags.parse("WOW Entertainment ᴴᴰ ᴰᴼᴸᴮʸ").tags)

    @Test
    fun lowerCaseModifierLettersFoldToCapitals() = assertEquals(listOf("RAW"), ProviderTags.parse("Joyn ʳᵃᵂ").tags)

    @Test
    fun superscriptDigitsMakeA4kTag() = assertEquals(listOf("4K"), ProviderTags.parse("General ⁴ᴷ").tags)

    @Test
    fun repeatedTagIsKeptOnce() = assertEquals(listOf("HD"), ProviderTags.parse("Sky ᴴᴰ Cinema ᴴᴰ").tags)

    @Test
    fun plainNameIsUntouched() = assertEquals(ProviderName("Sky Cinema", emptyList(), null), ProviderTags.parse("Sky Cinema"))

    @Test
    fun barInsideTheNameIsNotAPrefix() = assertNull(ProviderTags.parse("Sky | Cinema").tag)

    @Test
    fun tagOnlyNameKeepsTheProviderSpelling() = assertEquals("DE| ᴴᴰ", ProviderTags.parse("DE| ᴴᴰ").name)

    @Test
    fun sharedTagWhenAllAgree() = assertEquals(
        "DE",
        ProviderTags.sharedTag(listOf(ProviderTags.parse("DE| A"), ProviderTags.parse("DE| B"))),
    )

    @Test
    fun noSharedTagWhenOneDiffers() = assertNull(
        ProviderTags.sharedTag(listOf(ProviderTags.parse("DE| A"), ProviderTags.parse("UK| B"))),
    )

    @Test
    fun noSharedTagWhenOneHasNone() = assertNull(
        ProviderTags.sharedTag(listOf(ProviderTags.parse("DE| A"), ProviderTags.parse("B"))),
    )

    @Test
    fun channelTagUsesPipePrefix() =
        assertEquals("DE", ProviderTags.channelTag("DE| Sky Cinema Premieren ᴴᴰ"))

    @Test
    fun channelTagAllowsThreeLetterPipePrefix() =
        assertEquals("USA", ProviderTags.channelTag("USA| Fox Sports"))

    @Test
    fun channelTagFallsBackToColonForm() =
        assertEquals("DE", ProviderTags.channelTag("DE: SKY SPORT BUNDESLIGA 1 HD"))

    @Test
    fun channelTagFallsBackToSlashForm() =
        assertEquals("FR", ProviderTags.channelTag("FR/CANAL+ SPORT"))

    @Test
    fun channelTagIsNullWhenUntagged() =
        assertNull(ProviderTags.channelTag("Sky Cinema"))

    @Test
    fun channelTagIgnoresBarInsideName() =
        assertNull(ProviderTags.channelTag("Sky | Cinema"))

    @Test
    fun channelTagReusesGivenParse() =
        assertEquals("UK", ProviderTags.channelTag("UK| Sport", ProviderTags.parse("UK| Sport")))

    // --- the block form ("| DE | …", "┃DE┃ …") another provider writes ---

    @Test
    fun theBlockTagIsSplitOff() =
        assertEquals(ProviderName("DAZN LA LIGA", emptyList(), "DE"), ProviderTags.parse("┃DE┃ DAZN LA LIGA"))

    @Test
    fun theBlockTagAlsoWorksWithThePlainBar() =
        assertEquals(ProviderName("DAZN LA LIGA", emptyList(), "DE"), ProviderTags.parse("| DE | DAZN LA LIGA"))

    @Test
    fun theBlockTagKeepsTheQualityTags() =
        assertEquals(ProviderName("Sky Cinema", listOf("HD"), "DE"), ProviderTags.parse("┃DE┃ Sky Cinema ᴴᴰ"))

    @Test
    fun aBrandTagIsReadAsWritten() =
        assertEquals(ProviderName("NPO 1 HD", emptyList(), "CANAL+"), ProviderTags.parse("┃CANAL+┃ NPO 1 HD"))

    @Test
    fun aLanguageTagIsReadAsWritten() =
        assertEquals(ProviderName("ACTOR", emptyList(), "HINDI"), ProviderTags.parse("┃HINDI┃ ACTOR"))

    @Test
    fun twoTagsInARowKeepTheFirstOne() =
        assertEquals(ProviderName("DUBBED 2023 MOVIES HD", emptyList(), "ENGLISH"), ProviderTags.parse("┃ENGLISH┃HINDI┃ DUBBED 2023 MOVIES HD"))

    @Test
    fun theColonFormKeepsTheTagInTheName() =
        assertEquals(ProviderName("DE: SKY SPORT", emptyList(), "DE"), ProviderTags.parse("DE: SKY SPORT"))

    @Test
    fun sharedTagWorksWithBlockGroups() = assertEquals(
        "DE",
        ProviderTags.sharedTag(listOf(ProviderTags.parse("┃DE┃ A"), ProviderTags.parse("┃DE┃ B"))),
    )
}
