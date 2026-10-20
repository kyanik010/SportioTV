package tv.own.owntv.features.live

import tv.own.owntv.features.sport.SportLanguage
import tv.own.owntv.features.sport.SportLanguages

/**
 * Broadcast-language filter for Live TV (roadmap point 7): which playlist languages stay visible.
 *
 * The language of a channel is its provider GROUP's tag first ([languageKeyOfGroup], e.g. a group
 * named "┃HINDI┃ INDIA" makes every channel in it Hindi) and the channel-name tag
 * ([SportLanguages.languageOfName], e.g. "DE:", "UK|", "FR/", "┃DE┃") only as a fallback. Real
 * playlists tag the groups, not the channel names — reading the name alone put nearly every channel
 * into [OTHER] and the filter did nothing. Channels with neither a tagged group nor a tagged name,
 * or a tag no language maps to ("┃CANAL+┃", "VIP:", untagged names), are [OTHER]. The Sport matcher
 * is untouched — this object only reads the mapping, it never changes [SportLanguages] behaviour.
 *
 * Persistence lives in `SportsPrefs` (the app's own `sports` DataStore file, not core
 * `SettingsRepository`): Live TV is owned by the app, so a core key would need a core-repo change
 * and release for a purely app-side filter. One source, no duplicate state. An empty excluded set
 * means "everything on", so a newly scanned language appears enabled.
 *
 * Like [SportLanguages], everything here is dependency-free and unit-testable without Android.
 */
object LiveLanguages {

    /** Key for tagless or unmapped channels. Never a [SportLanguage] name; toggled via showOther. */
    const val OTHER = "OTHER"

    /** One scanned channel: its own name plus its provider group's name (null when unknown). */
    data class ScanEntry(val name: String, val groupName: String? = null)

    /** Display order in the picker: the sport languages first, [OTHER] last. */
    val order: List<String> = SportLanguages.order.map { it.name } + OTHER

    /**
     * The language key of [channelName] (a [SportLanguage] name), or null when the channel has no
     * tag or its tag maps to no language — both cases are [OTHER] for the picker.
     */
    fun languageKeyOf(channelName: String): String? =
        SportLanguages.languageOfName(channelName)?.name

    /**
     * The language key of [groupName] (a provider group like "┃HINDI┃ …"), or null when the group
     * has no tag or its tag maps to no language. Shared with the badge ([ProviderTags.channelTag])
     * so the filter and the badge never disagree about a group.
     */
    fun languageKeyOfGroup(groupName: String): String? =
        ProviderTags.channelTag(groupName)?.let(SportLanguages::languageOfTag)?.name

    /**
     * The language key of a channel in [groupName]: the group tag wins (providers tag groups, not
     * channels), the channel-name tag is the fallback for untagged groups. Null means [OTHER].
     */
    fun languageKeyOf(channelName: String, groupName: String?): String? =
        groupName?.let(::languageKeyOfGroup) ?: languageKeyOf(channelName)

    /**
     * The keys the playlist actually contains: one per mapped language found, plus [OTHER] when at
     * least one channel is tagless or unmapped. Pure snapshot math — the caller caches the result
     * (scan at start/sync, never per frame).
     */
    fun availableKeys(channelNames: Collection<String>): Set<String> =
        availableKeysWithGroups(channelNames.map { ScanEntry(it) })

    /**
     * Group-aware variant of [availableKeys]: each entry carries its provider group's name, so
     * channels in a tagged group count toward that group's language even when their own name has
     * no tag.
     */
    fun availableKeysWithGroups(entries: Collection<ScanEntry>): Set<String> {
        val keys = HashSet<String>()
        var other = false
        for (entry in entries) {
            val key = languageKeyOf(entry.name, entry.groupName)
            if (key == null) other = true else keys += key
        }
        if (other) keys += OTHER
        return keys
    }

    /**
     * Whether a channel stays visible. [OTHER] channels follow [showOther] alone (an OTHER entry in
     * [excluded] is ignored — Other is only ever toggled through its own switch).
     */
    fun isVisible(channelName: String, excluded: Set<String>, showOther: Boolean): Boolean {
        val key = languageKeyOf(channelName) ?: return showOther
        return key !in excluded
    }

    /**
     * Group-aware variant of [isVisible]: [groupName] is the channel's provider group (null when
     * unknown) and wins over the channel-name tag, exactly like [languageKeyOf].
     */
    fun isVisible(channelName: String, groupName: String?, excluded: Set<String>, showOther: Boolean): Boolean {
        val key = languageKeyOf(channelName, groupName) ?: return showOther
        return key !in excluded
    }

    /** Whether picker row [key] (a language name or [OTHER]) is currently enabled. */
    fun isEnabled(key: String, excluded: Set<String>, showOther: Boolean): Boolean =
        if (key == OTHER) showOther else key !in excluded

    /** The filter state after a change: the excluded languages and whether "Other" stays visible. */
    data class FilterState(val excluded: Set<String>, val showOther: Boolean)

    /** Whether every language is on right now — the "All languages" row is a switch, not a button. */
    fun allEnabled(excluded: Set<String>, showOther: Boolean): Boolean = excluded.isEmpty() && showOther

    /**
     * The new state when the "All languages" row is pressed: everything off, or everything on.
     *
     * Everything off excludes every language the playlist holds ([options] from the scan) and
     * switches "Other" off too, so the Live list shows nothing. Everything on clears the
     * exclusions and switches "Other" back on. A language the playlist does not hold has no
     * channels and stays untouched.
     */
    fun toggleAll(options: Set<String>, excluded: Set<String>, showOther: Boolean): FilterState =
        if (allEnabled(excluded, showOther)) {
            FilterState(excluded + options.filter { it != OTHER }, false)
        } else {
            FilterState(emptySet(), true)
        }
}
