package tv.own.owntv.features.sport

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * The AI recommendation's two local pieces: parsing the model's JSON and resolving its broadcaster
 * names against the user's channel catalogue. No network, no Android, no database.
 */
class SportsAiTest {

    @Test
    fun parserAcceptsTheDocumentedShapeAndTheUkAlias() {
        val content = """
            {"broadcasts":[
              {"country":"UK","channel":"Sky Sports Main Event"},
              {"country":"DE","channel":"Sky Sport Bundesliga 1"}
            ]}
        """.trimIndent()

        val parsed = AiResponseParser.parse(
            content,
            listOf(AiCountry.UNITED_KINGDOM, AiCountry.GERMANY),
        )

        assertEquals(
            listOf(
                AiBroadcast(AiCountry.UNITED_KINGDOM, "Sky Sports Main Event"),
                AiBroadcast(AiCountry.GERMANY, "Sky Sport Bundesliga 1"),
            ),
            parsed,
        )
    }

    @Test
    fun parserStripsFencesDropsOtherCountriesAndDedupes() {
        val content = """
            ```json
            {"broadcasts":[
              {"country":"DE","channel":"Sky Sport Bundesliga 1"},
              {"country":"AT","channel":"ORF 1"},
              {"country":"DE","channel":"sky sport bundesliga 1"},
              {"country":"DE","channel":"   "}
            ]}
            ```
        """.trimIndent()

        val parsed = AiResponseParser.parse(content, listOf(AiCountry.GERMANY))

        assertEquals(listOf(AiBroadcast(AiCountry.GERMANY, "Sky Sport Bundesliga 1")), parsed)
    }

    @Test
    fun parserAcceptsABareArray() {
        val parsed = AiResponseParser.parse(
            """[{"country":"US","channel":"NBC Sports"}]""",
            listOf(AiCountry.UNITED_STATES),
        )

        assertEquals(listOf(AiBroadcast(AiCountry.UNITED_STATES, "NBC Sports")), parsed)
    }

    @Test
    fun matcherFindsTheFullBroadcasterNameInATaggedChannel() = runBlocking {
        val store = FakeStore(
            listOf(
                channel(1, "DE: SKY SPORT BUNDESLIGA 1 HD"),
                channel(2, "DE: SKY SPORT NEWS"),
                channel(3, "DE: SKY SPORT TENNIS"),
            ),
        )

        val matches = matcher(store).match(
            listOf(AiBroadcast(AiCountry.GERMANY, "Sky Sport Bundesliga 1")),
            limit = 20,
        )

        assertEquals(listOf("DE: SKY SPORT BUNDESLIGA 1 HD"), matches.map { it.channelName })
    }

    @Test
    fun matcherDropsWrongCountryTagsButKeepsUntaggedFeeds() = runBlocking {
        val store = FakeStore(
            listOf(
                channel(1, "AT: SKY SPORT BUNDESLIGA 1"),
                channel(2, "CA: SKY SPORT BUNDESLIGA 1"),
                channel(3, "SKY BUNDESLIGA 1"),
                channel(4, "DE: SKY SPORT BUNDESLIGA 1"),
            ),
        )

        val matches = matcher(store).match(
            listOf(
                AiBroadcast(AiCountry.GERMANY, "Sky Sport Bundesliga 1"),
                AiBroadcast(AiCountry.GERMANY, "Sky Bundesliga 1"),
            ),
            limit = 20,
        )

        assertEquals(
            setOf("DE: SKY SPORT BUNDESLIGA 1", "SKY BUNDESLIGA 1"),
            matches.map { it.channelName }.toSet(),
        )
    }

    @Test
    fun matcherDropsTheReportedDaZnAndPremierSportsCountryMismatches() = runBlocking {
        val store = FakeStore(
            listOf(
                channel(1, "DE: DAZN 1 MOBILE"),
                channel(2, "DE: DAZN 1 HD"),
                channel(3, "CA: DAZN 1"),
                channel(4, "HK: PREMIER SPORTS 1"),
            ),
        )

        val matches = matcher(store).match(
            listOf(
                AiBroadcast(AiCountry.GERMANY, "DAZN 1"),
                AiBroadcast(AiCountry.GERMANY, "Premier Sports 1"),
            ),
            limit = 20,
        )

        assertEquals(listOf("DE: DAZN 1 HD"), matches.map { it.channelName })
        assertEquals(listOf("HD"), matches.map { it.qualityLabel })
    }

    @Test
    fun matcherPrefersTheBestQualityVariantOfTheSameFeed() = runBlocking {
        val store = FakeStore(
            listOf(
                channel(1, "DE: DAZN 1 SD"),
                channel(2, "DE: DAZN 1 MOBILE"),
                channel(3, "DE: DAZN 1 HD"),
                channel(4, "DE: DAZN 1 4K"),
            ),
        )

        val matches = matcher(store).match(
            listOf(AiBroadcast(AiCountry.GERMANY, "DAZN 1")),
            limit = 20,
        )

        assertEquals(listOf("DE: DAZN 1 4K"), matches.map { it.channelName })
        assertEquals(listOf("4K"), matches.map { it.qualityLabel })
    }

    @Test
    fun matcherKeepsAMobileOnlyFeedAsLastResort() = runBlocking {
        val store = FakeStore(listOf(channel(1, "DE: DAZN 1 MOBILE")))

        val matches = matcher(store).match(
            listOf(AiBroadcast(AiCountry.GERMANY, "DAZN 1")),
            limit = 20,
        )

        assertEquals(listOf("DE: DAZN 1 MOBILE"), matches.map { it.channelName })
        assertEquals(listOf("MOBILE"), matches.map { it.qualityLabel })
    }

    @Test
    fun matcherFallsBackToTokensWhenTheProviderShortensTheName() = runBlocking {
        val store = FakeStore(listOf(channel(1, "DE: SKY BUNDESLIGA 1")))

        val matches = matcher(store).match(
            listOf(AiBroadcast(AiCountry.GERMANY, "Sky Sport Bundesliga 1")),
            limit = 20,
        )

        assertEquals(listOf("DE: SKY BUNDESLIGA 1"), matches.map { it.channelName })
    }

    @Test
    fun matcherRecognisesAShortBrandChannelNamedByTheModel() = runBlocking {
        val store = FakeStore(listOf(channel(1, "DE: ARD HD"), channel(2, "DE: ARD ALPHA")))

        val matches = matcher(store).match(
            listOf(AiBroadcast(AiCountry.GERMANY, "ARD Das Erste")),
            limit = 20,
        )

        assertEquals(listOf("DE: ARD HD"), matches.map { it.channelName })
    }

    @Test
    fun matcherDropsCategorySeparatorsImportedAsChannels() = runBlocking {
        val store = FakeStore(
            listOf(
                channel(1, "#### SKY SPORT ####"),
                channel(2, "### SKY SPORT BUNDESLIGA ###"),
                channel(3, "DE: SKY SPORT BUNDESLIGA 1"),
            ),
        )

        val matches = matcher(store).match(
            listOf(AiBroadcast(AiCountry.GERMANY, "Sky Sport")),
            limit = 20,
        )

        assertEquals(listOf("DE: SKY SPORT BUNDESLIGA 1"), matches.map { it.channelName })
    }

    @Test
    fun matcherGroupsCountriesAndCapsTheResult() = runBlocking {
        val channels = (1L..5L).map { channel(it, "DE: SKY SPORT $it") } +
            (6L..10L).map { channel(it, "AT: SKY SPORT ${it - 5}") }
        val store = FakeStore(channels)

        val matches = matcher(store).match(
            listOf(
                AiBroadcast(AiCountry.GERMANY, "Sky Sport"),
                AiBroadcast(AiCountry.AUSTRIA, "Sky Sport"),
            ),
            limit = 5,
        )

        assertEquals(5, matches.size)
        assertEquals(
            listOf(
                AiCountry.GERMANY,
                AiCountry.GERMANY,
                AiCountry.GERMANY,
                AiCountry.AUSTRIA,
                AiCountry.AUSTRIA,
            ),
            matches.map { it.country },
        )
    }

    @Test
    fun matcherReturnsNothingWithoutLivePlaylists() = runBlocking {
        val store = FakeStore(listOf(channel(1, "DE: SKY SPORT BUNDESLIGA 1")))
        val matcher = AiChannelMatcher(store.channelDao) { emptyList() }

        assertTrue(
            matcher.match(listOf(AiBroadcast(AiCountry.GERMANY, "Sky Sport Bundesliga 1")), 20)
                .isEmpty(),
        )
    }

    private fun matcher(store: FakeStore): AiChannelMatcher =
        AiChannelMatcher(store.channelDao) { listOf(SourceId) }

    private fun channel(id: Long, name: String) = ChannelEntity(
        id = id,
        sourceId = SourceId,
        name = name,
        streamUrl = "http://provider.invalid/$id.ts",
    )

    private class FakeStore(val channels: List<ChannelEntity>) {
        val channelDao: ChannelDao = proxy(ChannelDao::class.java) { name, args ->
            when (name) {
                "searchList" -> {
                    val query = args!![0] as String
                    val limit = args[2] as Int
                    channels.filter { it.name.contains(query, ignoreCase = true) }.take(limit)
                }
                else -> null
            }
        }
    }

    private companion object {
        const val SourceId = 1L

        fun <T> proxy(type: Class<T>, handler: (String, Array<Any?>?) -> Any?): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
                when (method.name) {
                    "toString" -> "Fake${type.simpleName}"
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === args?.firstOrNull()
                    else -> handler(method.name, args)
                }
            } as T
    }
}
