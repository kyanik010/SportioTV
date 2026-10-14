package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * The sport hub's live-playlist scoping, nailed down on the JVM without Android.
 *
 * [scopeLiveSourceIds] is the single place that turns the profile's sources into the ids the
 * matcher resolves against — shared by [ProfileLiveSourceIdsProvider] (per match call) and the
 * [SportsViewModel] source-change observer (cache invalidation). A set default source (the
 * switcher top-right) narrows to that one playlist; sources with live sync off never match.
 */
class SportsSourcesTest {

    @Test
    fun setDefaultNarrowsToThatSource() {
        val sources = listOf(source(1), source(2), source(3))

        assertEquals(listOf(2L), scopeLiveSourceIds(sources, 2L))
    }

    @Test
    fun noDefaultKeepsEveryLiveSource() {
        val sources = listOf(source(1), source(2))

        assertEquals(listOf(1L, 2L), scopeLiveSourceIds(sources, -1L))
    }

    @Test
    fun sourcesWithLiveSyncOffNeverMatch() {
        val sources = listOf(source(1, syncLive = false), source(2))

        assertEquals(listOf(2L), scopeLiveSourceIds(sources, -1L))
        assertTrue(scopeLiveSourceIds(sources, 1L).isEmpty())
    }

    @Test
    fun unknownDefaultYieldsNothing() {
        val sources = listOf(source(1), source(2))

        assertTrue(scopeLiveSourceIds(sources, 99L).isEmpty())
    }

    @Test
    fun emptySourcesYieldNothing() {
        assertTrue(scopeLiveSourceIds(emptyList(), -1L).isEmpty())
        assertTrue(scopeLiveSourceIds(emptyList(), 1L).isEmpty())
    }

    private fun source(id: Long, syncLive: Boolean = true) = SourceEntity(
        id = id,
        name = "source $id",
        type = SourceType.M3U,
        url = "http://provider.invalid/$id.m3u",
        syncLive = syncLive,
    )
}
