package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tag forms a provider writes at the start of a channel or group name. One place for all of
 * them, so the country badge and the language filter can never disagree about the same name.
 */
class CountryTagsTest {

    // --- the forms a provider writes ---

    @Test
    fun theSeparatorFormIsRead() {
        val tag = CountryTags.tagOf("DE: SKY SPORT BUNDESLIGA 1 HD")
        assertEquals("DE", tag?.content)
        assertEquals("SKY SPORT BUNDESLIGA 1 HD", tag?.name)
        assertEquals(CountryTags.Form.SEPARATOR, tag?.form)
    }

    @Test
    fun theSlashFormIsRead() {
        val tag = CountryTags.tagOf("FR/CANAL+ SPORT")
        assertEquals("FR", tag?.content)
        assertEquals("CANAL+ SPORT", tag?.name)
    }

    @Test
    fun theBarFormIsRead() {
        val tag = CountryTags.tagOf("UK| Sky Sports")
        assertEquals("UK", tag?.content)
        assertEquals("Sky Sports", tag?.name)
        assertEquals(CountryTags.Form.BAR, tag?.form)
    }

    @Test
    fun theThreeLetterBarFormIsRead() {
        val tag = CountryTags.tagOf("USA| Fox Sports")
        assertEquals("USA", tag?.content)
        assertEquals("Fox Sports", tag?.name)
    }

    @Test
    fun theBlockFormIsRead() {
        val tag = CountryTags.tagOf("┃DE┃ DAZN LA LIGA")
        assertEquals("DE", tag?.content)
        assertEquals("DAZN LA LIGA", tag?.name)
        assertEquals(CountryTags.Form.BLOCK, tag?.form)
    }

    @Test
    fun theBlockFormAlsoWorksWithThePlainBar() {
        val tag = CountryTags.tagOf("| DE | DAZN LA LIGA")
        assertEquals("DE", tag?.content)
        assertEquals("DAZN LA LIGA", tag?.name)
    }

    @Test
    fun theBlockFormAlsoWorksWithoutSpaces() {
        val tag = CountryTags.tagOf("|DE|DAZN LA LIGA")
        assertEquals("DE", tag?.content)
        assertEquals("DAZN LA LIGA", tag?.name)
    }

    @Test
    fun aBlockTagMayNameALanguage() {
        val tag = CountryTags.tagOf("┃HINDI┃ ACTOR┃ SHASHI KAPOOR HD")
        assertEquals("HINDI", tag?.content)
        // The second tag is a mark ("ACTOR"), not a second language — it goes with the first.
        assertEquals("SHASHI KAPOOR HD", tag?.name)
    }

    @Test
    fun aBlockTagMayCarryTwoWords() {
        val tag = CountryTags.tagOf("┃CA EN┃ CBC HD")
        assertEquals("CA EN", tag?.content)
        assertEquals("CBC HD", tag?.name)
    }

    @Test
    fun aNameWithoutATagHasNone() {
        assertNull(CountryTags.tagOf("Sky Sport Bundesliga 1"))
        // A brand or quality mark is not a country tag.
        assertNull(CountryTags.tagOf("VIP: CANAL+ PREMIER LEAGUE"))
        assertNull(CountryTags.tagOf("8K: beIN SPORTS 1"))
    }

    @Test
    fun aBarInsideTheNameIsNoTag() {
        assertNull(CountryTags.tagOf("Sky | Cinema"))
        // The old playlist's titles carry their tag at the end, not at the start.
        assertNull(CountryTags.tagOf("ENDED | UCI TRACK WORLD CHAMPIONSHIPS | UK: MAX PPV 1"))
    }

    @Test
    fun theContentIsNormalized() {
        assertEquals("CA EN", CountryTags.tagOf("┃ca en┃ CBC HD")?.normalized)
        assertEquals("DE", CountryTags.tagOf("┃ DE ┃ DAZN")?.normalized)
    }

    @Test
    fun theTagWordsAreLowerCase() {
        assertEquals(listOf("ca", "en"), CountryTags.tagOf("┃CA EN┃ CBC HD")?.words)
        assertTrue(CountryTags.tagOf("┃DE┃ DAZN")!!.words == listOf("de"))
    }
}
