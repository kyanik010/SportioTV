package tv.own.owntv.features.sport

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.SettingsRepository

/**
 * The ids of the profile's **live** playlists, resolved for [SportsMatcher].
 *
 * This exists as a one-method seam for tests. [SportsMatcher] needs three things: the channel and
 * guide DAOs (already plain interfaces, trivial to fake) and the set of live source ids — but that
 * last one is produced by `SettingsRepository`/`SourceRepository`, which are concrete classes wired
 * to an Android `Context`/`DataStore`. The project carries no MockK/Mockito/Robolectric, so a JVM
 * unit test cannot build those repositories. Pulling the lookup behind this interface removes the
 * Android dependency from the matcher's constructor: production binds
 * [ProfileLiveSourceIdsProvider], a test hands in a lambda. No behaviour changes — this is the same
 * query that used to be a private method on the matcher.
 */
fun interface LiveSourceIdsProvider {
    /** Live playlist ids for the active profile, in the order the provider reports them. */
    suspend fun liveSourceIds(): List<Long>
}

/**
 * The real [LiveSourceIdsProvider]: the active profile's sources, narrowed to the profile's default
 * playlist when one is set, then to those with live sync enabled. Every call is guarded so a cold or
 * half-configured install yields an empty list rather than an exception — an empty result simply
 * means "nothing to match against".
 */
class ProfileLiveSourceIdsProvider(
    private val settings: SettingsRepository,
    private val sourceRepository: SourceRepository,
) : LiveSourceIdsProvider {

    override suspend fun liveSourceIds(): List<Long> {
        val pid = settings.activeProfileId.first()
        if (pid < 0) return emptyList()
        val sources = runCatching { sourceRepository.observeSources(pid).first() }.getOrNull().orEmpty()
        val defaultId = runCatching { settings.defaultSourceId.first() }.getOrDefault(-1L)
        return scopeLiveSourceIds(sources, defaultId)
    }
}

/**
 * The profile's live playlists as ids: narrowed to [defaultId] when one is set (the source
 * switcher top-right), then to those with live sync enabled. Pure, so the sport hub's
 * source-change observer and unit tests share exactly this logic with the provider above.
 */
fun scopeLiveSourceIds(sources: List<SourceEntity>, defaultId: Long): List<Long> {
    val scoped = if (defaultId > 0) sources.filter { it.id == defaultId } else sources
    return scoped.filter { it.syncLive }.map { it.id }
}
