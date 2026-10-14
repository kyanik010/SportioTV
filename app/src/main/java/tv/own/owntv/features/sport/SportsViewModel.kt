@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // flatMapLatest for the source observer

package tv.own.owntv.features.sport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import tv.own.owntv.core.companion.CompanionController
import tv.own.owntv.core.companion.CompanionSelectItem
import tv.own.owntv.core.companion.CompanionServerState
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Sports hub state: ESPN boards per league (cached 15 minutes), the active-league set, follow sets,
 * the selected league and fixture, channel matches and standings.
 *
 * Only **active** leagues (the user's selection, default = [DefaultActiveLeagueIds]) are loaded, so
 * the full [SportsLeagues] catalogue can be large without costing a request per league. Playback
 * itself stays in `LiveViewModel` — the screen calls it with a matched channel id, so there is
 * exactly one zap path.
 */
class SportsViewModel(
    private val prefs: SportsPrefs,
    private val api: EspnApi,
    private val matcher: SportsMatcher,
    private val companion: CompanionController,
    private val settings: SettingsRepository,
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    sealed interface BoardState {
        data object Loading : BoardState
        data class Content(val board: EspnApi.Scoreboard) : BoardState
        data object Error : BoardState
    }

    sealed interface MatchesState {
        data object Idle : MatchesState
        data object Loading : MatchesState
        data class Content(val matches: List<ChannelMatch>) : MatchesState
    }

    /** The second column's filter for the selected league. */
    enum class FixtureTab { LIVE, UPCOMING, PAST }

    data class LeagueUi(
        val league: SportLeague,
        val liveCount: Int,
        val loading: Boolean,
    )

    /** Leagues the user activated (shown in the rail and loaded); default = the brand's starter set. */
    val activeLeagues: StateFlow<Set<String>> = prefs.followedLeagues
        .map { ids -> ids.filterTo(LinkedHashSet()) { sportsLeagueById(it) != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultActiveLeagueIds)

    val followedTeams: StateFlow<Set<String>> = prefs.followedTeams
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Followed individual athletes (tennis/MMA duel entries) — never racing/golf field entries. */
    val followedPlayers: StateFlow<Set<String>> = prefs.followedPlayers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val hubVisible: StateFlow<Boolean> = prefs.hubVisible
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Languages the matched channels are filtered to; empty = all. Default: German. */
    val preferredLanguages: StateFlow<Set<SportLanguage>> = prefs.preferredLanguages
        .map { names -> names.mapNotNull { runCatching { SportLanguage.valueOf(it) }.getOrNull() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportLanguages.default)

    /** How many channels one fixture lists (5–50, default 20); changes apply without restart. */
    val channelLimit: StateFlow<Int> = prefs.channelLimit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsMatcher.DefaultChannelLimit)

    /** Languages never shown in a fixture's channel list — a hard "never", even for exact hits. */
    val excludedLanguages: StateFlow<Set<SportLanguage>> = prefs.excludedLanguages
        .map { names -> names.mapNotNull { runCatching { SportLanguage.valueOf(it) }.getOrNull() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** True unless the user switched the language filter to its legacy never-empty fallback. */
    val languageFilterStrict: StateFlow<Boolean> = prefs.languageFilterMode
        .map { it != SportsPrefs.FilterModeLegacy }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** True once the language prompt was answered (or dismissed); it is shown at most once. */
    val languageAsked: StateFlow<Boolean> = prefs.languageAsked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Leagues that hold a followed team or athlete — loaded too, so Favourites can show them. */
    private val followedItemLeagues: StateFlow<Set<String>> = combine(followedTeams, followedPlayers) { teams, players ->
        (teams + players).mapNotNull { it.substringBefore(TeamKeySep).takeIf { id -> sportsLeagueById(id) != null } }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Active leagues plus the ones a followed team/athlete is in — the boards we fetch. */
    private val loadingLeagues: StateFlow<Set<String>> =
        combine(activeLeagues, followedItemLeagues) { active, followed -> active + followed }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultActiveLeagueIds)

    private val boards = MutableStateFlow<Map<String, BoardState>>(emptyMap())
    private val boardAt = HashMap<String, Long>()
    private val standingsCache = HashMap<String, List<LeagueTable>>()
    private val matchesCache = HashMap<String, List<ChannelMatch>>()

    private val _selectedLeagueId = MutableStateFlow(SportsLeagues.first().id)
    val selectedLeagueId: StateFlow<String> = _selectedLeagueId.asStateFlow()

    private val _selectedFixture = MutableStateFlow<Fixture?>(null)
    val selectedFixture: StateFlow<Fixture?> = _selectedFixture.asStateFlow()

    /** Which bucket of the selected league's fixtures is shown (Live / Upcoming / Past). */
    private val _tab = MutableStateFlow(FixtureTab.UPCOMING)
    val tab: StateFlow<FixtureTab> = _tab.asStateFlow()

    /** True once the user picks a tab by hand; the league default then stops overriding it. */
    private var userChoseTab = false

    /** The selected league's board (null until first load), for fixtures + error state. */
    val selectedBoard: StateFlow<BoardState?> =
        combine(boards, _selectedLeagueId) { current, id -> current[id] }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _matches = MutableStateFlow<MatchesState>(MatchesState.Idle)
    val matches: StateFlow<MatchesState> = _matches.asStateFlow()

    private val _standings = MutableStateFlow<List<LeagueTable>>(emptyList())
    val standings: StateFlow<List<LeagueTable>> = _standings.asStateFlow()

    private val _standingsLoading = MutableStateFlow(false)
    val standingsLoading: StateFlow<Boolean> = _standingsLoading.asStateFlow()

    /** The active leagues in catalogue order, with their live counts — the rail. */
    val leagues: StateFlow<List<LeagueUi>> =
        combine(boards, activeLeagues) { current, active ->
            SportsLeagues.filter { it.id in active }.map { league ->
                val board = (current[league.id] as? BoardState.Content)?.board
                LeagueUi(
                    league = league,
                    liveCount = board?.fixtures?.count { it.state == FixtureState.LIVE } ?: 0,
                    loading = current[league.id] is BoardState.Loading,
                )
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SportsLeagues.filter { it.defaultActive }.map { LeagueUi(it, 0, false) },
        )

    /** Fixtures involving followed teams or athletes — the "My teams" chips. */
    val myTeamFixtures: StateFlow<List<Fixture>> =
        combine(boards, followedTeams, followedPlayers) { current, teams, players ->
            if (teams.isEmpty() && players.isEmpty()) return@combine emptyList()
            current.values.filterIsInstance<BoardState.Content>()
                .flatMap { it.board.fixtures }
                .filter { involvesFollowedTeam(it, teams) || involvesFollowedPlayer(it, players) }
                .sortedWith(compareBy<Fixture> { it.state != FixtureState.LIVE }.thenBy { it.dateMs })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Everything the user picked: every fixture of an active league plus games of followed teams or
     * athletes (even when that item's league is not active), live games first.
     */
    val favourites: StateFlow<List<Fixture>> =
        combine(boards, activeLeagues, followedTeams, followedPlayers) { current, active, teams, players ->
            current.values.filterIsInstance<BoardState.Content>()
                .flatMap { it.board.fixtures }
                .filter {
                    it.leagueId in active ||
                        involvesFollowedTeam(it, teams) ||
                        involvesFollowedPlayer(it, players)
                }
                .sortedWith(compareBy<Fixture> { it.state != FixtureState.LIVE }.thenBy { it.dateMs })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The fixtures currently shown: the selected league's, or the Favourites aggregate. */
    val currentFixtures: StateFlow<List<Fixture>> =
        combine(_selectedLeagueId, selectedBoard, favourites) { id, board, favs ->
            if (id == FavouritesId) favs else (board as? BoardState.Content)?.board?.fixtures.orEmpty()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True while the current view still waits for boards (league or Favourites). */
    val currentLoading: StateFlow<Boolean> =
        combine(_selectedLeagueId, selectedBoard, loadingLeagues, boards) { id, board, want, b ->
            if (id == FavouritesId) want.any { b[it] == null || b[it] is BoardState.Loading }
            else board == null || board is BoardState.Loading
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** True when the selected league's board failed; the Favourites aggregate never errors as a whole. */
    val currentError: StateFlow<Boolean> =
        combine(_selectedLeagueId, selectedBoard) { id, board -> id != FavouritesId && board is BoardState.Error }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Load exactly the active leagues (+ leagues of followed teams, for Favourites); re-run when
        // the user changes the selection, so adding/removing a league fetches/drops it live.
        viewModelScope.launch { loadingLeagues.collect { applyLeagues(it) } }
        // A new selection resets the tab to its default (Live if the view has live games, else
        // Upcoming). The boards arrive asynchronously, so re-apply until the user picks by hand.
        viewModelScope.launch {
            _selectedLeagueId.collect {
                userChoseTab = false
                applyDefaultTab()
            }
        }
        viewModelScope.launch { currentFixtures.collect { applyDefaultTab() } }
        // The matcher reads the live playlists fresh on every match call, but the matches are
        // cached per fixture — so a source switcher change (defaultSourceId, top-right) or any
        // source-list change would otherwise leave stale matches standing. Observe the same
        // scoped id list the provider computes and re-resolve the selected fixture on change.
        // No boards reload (fixtures are source-independent), no polling, no preview touch.
        viewModelScope.launch {
            settings.activeProfileId.flatMapLatest { pid ->
                if (pid < 0) flowOf(emptyList())
                else combine(sourceRepository.observeSources(pid), settings.defaultSourceId) { sources, defaultId ->
                    scopeLiveSourceIds(sources, defaultId)
                }
            }.distinctUntilChanged().drop(1).collect { reResolveSelected() }
        }
    }

    fun setTab(tab: FixtureTab) {
        userChoseTab = true
        _tab.value = tab
    }

    private fun applyDefaultTab() {
        if (userChoseTab) return
        val live = currentFixtures.value.count { it.state == FixtureState.LIVE }
        _tab.value = if (live > 0) FixtureTab.LIVE else FixtureTab.UPCOMING
    }

    /** True when [fixture] involves one of the user's followed teams. */
    private fun involvesFollowedTeam(fixture: Fixture, teams: Set<String>): Boolean {
        val home = fixture.home?.abbreviation
        val away = fixture.away?.abbreviation
        return (home != null && teams.contains(SportsPrefs.teamKey(fixture.leagueId, home))) ||
            (away != null && teams.contains(SportsPrefs.teamKey(fixture.leagueId, away)))
    }

    /** True when [fixture] features one of the user's followed athletes (a duel entry). */
    private fun involvesFollowedPlayer(fixture: Fixture, players: Set<String>): Boolean =
        players.isNotEmpty() && fixture.entries.any { entry ->
            entry.id != null && players.contains(SportsPrefs.playerKey(fixture.leagueId, entry.id))
        }

    private suspend fun applyLeagues(want: Set<String>) {
        if (boards.value.keys.any { it !in want }) {
            boards.value = boards.value.filterKeys { it in want }
        }
        standingsCache.keys.retainAll(want)
        val selected = _selectedLeagueId.value
        if (selected != FavouritesId && selected !in want) {
            _selectedLeagueId.value = SportsLeagues.firstOrNull { it.id in want }?.id ?: FavouritesId
            _selectedFixture.value = null
            _matches.value = MatchesState.Idle
        }
        supervisorScope {
            SportsLeagues.filter { it.id in want }.map { league ->
                async { loadBoard(league, force = false) }
            }.awaitAll()
        }
    }

    fun refreshSelected() {
        val id = _selectedLeagueId.value
        if (id == FavouritesId) {
            val want = loadingLeagues.value
            viewModelScope.launch {
                supervisorScope {
                    SportsLeagues.filter { it.id in want }.map { async { loadBoard(it, force = true) } }.awaitAll()
                }
            }
            return
        }
        val league = sportsLeagueById(id) ?: return
        viewModelScope.launch { loadBoard(league, force = true) }
    }

    /** Show the Favourites aggregate (active leagues + followed teams) instead of a single league. */
    fun selectFavourites() {
        _selectedLeagueId.value = FavouritesId
        _selectedFixture.value = null
        _matches.value = MatchesState.Idle
    }

    fun selectLeague(id: String) {
        _selectedLeagueId.value = id
        _selectedFixture.value = null
        _matches.value = MatchesState.Idle
        val league = sportsLeagueById(id) ?: return
        viewModelScope.launch {
            loadBoard(league, force = false)
            loadStandings(league)
        }
    }

    fun selectFixture(fixture: Fixture?) {
        _selectedFixture.value = fixture
        if (fixture == null) {
            _matches.value = MatchesState.Idle
            return
        }
        // In Favourites the "selected league" is the aggregate, so follow the fixture's own league
        // for the standings panel.
        if (_selectedLeagueId.value == FavouritesId) {
            sportsLeagueById(fixture.leagueId)?.let { league -> viewModelScope.launch { loadStandings(league) } }
        }
        matchesCache[fixture.id]?.let {
            _matches.value = MatchesState.Content(it)
            return
        }
        _matches.value = MatchesState.Loading
        viewModelScope.launch {
            val found = runCatching { matcher.match(fixture, matchOptions()) }.getOrDefault(emptyList())
            matchesCache[fixture.id] = found
            if (_selectedFixture.value?.id == fixture.id) _matches.value = MatchesState.Content(found)
        }
    }

    fun toggleLeagueFollowed(leagueId: String) {
        viewModelScope.launch {
            prefs.setLeagueFollowed(leagueId, activeLeagues.value.contains(leagueId).not())
        }
    }

    fun toggleTeamFollowed(leagueId: String, abbrev: String) {
        viewModelScope.launch {
            val key = SportsPrefs.teamKey(leagueId, abbrev)
            prefs.setTeamFollowed(key, followedTeams.value.contains(key).not())
        }
    }

    fun togglePlayerFollowed(leagueId: String, playerId: String) {
        viewModelScope.launch {
            val key = SportsPrefs.playerKey(leagueId, playerId)
            prefs.setPlayerFollowed(key, followedPlayers.value.contains(key).not())
        }
    }

    fun isTeamFollowed(leagueId: String, abbrev: String): Boolean =
        followedTeams.value.contains(SportsPrefs.teamKey(leagueId, abbrev))

    fun isPlayerFollowed(leagueId: String, playerId: String): Boolean =
        followedPlayers.value.contains(SportsPrefs.playerKey(leagueId, playerId))

    /** Resolve matched ids to entities for the zap context, then hand off to the player caller. */
    fun playMatch(match: ChannelMatch, matches: List<ChannelMatch>, onPlay: (Long, List<tv.own.owntv.core.database.entity.ChannelEntity>) -> Unit) {
        viewModelScope.launch {
            val entities = runCatching { matcher.channels(matches.map { it.channelId }) }.getOrDefault(emptyList())
            onPlay(match.channelId, entities)
        }
    }

    /** Entities for the channel matches, in order — for the in-pane preview. */
    suspend fun entitiesFor(matches: List<ChannelMatch>): List<tv.own.owntv.core.database.entity.ChannelEntity> =
        runCatching { matcher.channels(matches.map { it.channelId }) }.getOrDefault(emptyList())

    fun setHubVisible(visible: Boolean) {
        viewModelScope.launch { prefs.setHubVisible(visible) }
    }

    /** Choose the languages matched channels are filtered to; empty = all. Marks the prompt answered. */
    fun setPreferredLanguages(languages: Set<SportLanguage>) {
        viewModelScope.launch {
            prefs.setPreferredLanguages(languages.map { it.name }.toSet())
            // The prefs flow may lag the write, so re-resolve with the just-picked value directly.
            reResolveSelected(matchOptions().copy(languages = languages))
        }
    }

    /** How many channels one fixture lists (5–50); the selected fixture re-resolves at once. */
    fun setChannelLimit(limit: Int) {
        viewModelScope.launch {
            prefs.setChannelLimit(limit)
            reResolveSelected(matchOptions().copy(limit = limit))
        }
    }

    /** Toggle one excluded language; the selected fixture re-resolves at once. */
    fun toggleExcludedLanguage(language: SportLanguage) {
        viewModelScope.launch {
            val current = excludedLanguages.value
            val next = if (language in current) current - language else current + language
            prefs.setExcludedLanguages(next.map { it.name }.toSet())
            reResolveSelected(matchOptions().copy(excludedLanguages = next))
        }
    }

    /** The matcher's filter from the current prefs — one place, so every setter re-resolves alike. */
    private fun matchOptions(): SportMatchOptions = SportMatchOptions(
        languages = preferredLanguages.value,
        excludedLanguages = excludedLanguages.value,
        limit = channelLimit.value,
        strict = languageFilterStrict.value,
    )

    /** Drop the cached channels and match the selected fixture again (per-game refresh button). */
    fun refreshMatches() {
        viewModelScope.launch { reResolveSelected() }
    }

    /**
     * Matches are language-filtered inside the matcher, so the cache is only valid for the options
     * it was built with — drop it and re-resolve the selected fixture.
     */
    private suspend fun reResolveSelected(options: SportMatchOptions = matchOptions()) {
        matchesCache.clear()
        val fixture = _selectedFixture.value ?: return
        _matches.value = MatchesState.Loading
        val found = runCatching { matcher.match(fixture, options) }.getOrDefault(emptyList())
        matchesCache[fixture.id] = found
        if (_selectedFixture.value?.id == fixture.id) _matches.value = MatchesState.Content(found)
    }

    /** Remember that the language prompt was shown, so it does not reappear on the next fixture. */
    fun markLanguageAsked() {
        viewModelScope.launch { prefs.setLanguageAsked() }
    }

    // ---- Phone management (companion pick list) -------------------------------------------------

    /** The companion server's state while the "Manage on phone" panel is open (Idle otherwise). */
    val remoteState: StateFlow<CompanionServerState> = companion.state

    /**
     * Serve the pick list (leagues + every active league's teams and athletes) to a phone on the same
     * Wi-Fi. Teams come from ESPN's `/teams`; athletes from the already-loaded duel fixtures. Chosen
     * ids reconcile the active leagues, followed teams and followed athletes in one submit.
     *
     * Teams and athletes are children of their league ([CompanionSelectItem.parentId]), so the phone
     * page opens one level per league instead of one endless list; [teamGroup] and [playerGroup]
     * label the two tabs inside it.
     */
    fun startPhonePicker(
        port: Int,
        title: String,
        leagueItems: List<CompanionSelectItem>,
        leagueLabels: Map<String, String>,
        teamGroup: String,
        playerGroup: String,
    ) {
        viewModelScope.launch {
            val shownLeagues = activeLeagues.value
            val extra = ArrayList<CompanionSelectItem>()
            shownLeagues.mapNotNull { sportsLeagueById(it) }
                .sortedBy { leagueLabels[it.id] ?: it.id }
                .forEach { league ->
                    runCatching { api.teams(league) }.getOrDefault(emptyList()).forEach { team ->
                        val key = SportsPrefs.teamKey(league.id, team.abbreviation)
                        extra.add(
                            CompanionSelectItem(
                                TeamItemPrefix + key, team.name, teamGroup,
                                key in followedTeams.value, parentId = league.id,
                            ),
                        )
                    }
                    val seen = HashSet<String>()
                    boards.value[league.id].let { it as? BoardState.Content }?.board?.fixtures.orEmpty()
                        .flatMap { it.entries }
                        .filter { it.id != null && !it.id.startsWith("-") }
                        .sortedBy { it.name.lowercase() }
                        .forEach { entry ->
                            val id = entry.id ?: return@forEach
                            val key = SportsPrefs.playerKey(league.id, id)
                            if (seen.add(key)) {
                                extra.add(
                                    CompanionSelectItem(
                                        PlayerItemPrefix + key, entry.name, playerGroup,
                                        key in followedPlayers.value, parentId = league.id,
                                    ),
                                )
                            }
                        }
                }
            companion.startForSelectItems(port, title, leagueItems + extra) { ids ->
                viewModelScope.launch { applyPhoneSelection(ids, shownLeagues) }
            }
        }
    }

    /**
     * Apply a phone submit: the league ids become the active set; the team/athlete ids replace the
     * followed ones **for the leagues whose lists were shown** (follows in other leagues are kept).
     */
    private suspend fun applyPhoneSelection(ids: Set<String>, shownLeagues: Set<String>) {
        val leagueIds = ids.filterNot { it.startsWith(TeamItemPrefix) || it.startsWith(PlayerItemPrefix) }.toSet()
        val teamKeys = ids.filter { it.startsWith(TeamItemPrefix) }.map { it.removePrefix(TeamItemPrefix) }.toSet()
        val playerKeys = ids.filter { it.startsWith(PlayerItemPrefix) }.map { it.removePrefix(PlayerItemPrefix) }.toSet()
        val shownPrefixes = shownLeagues.map { "$it$TeamKeySep" }
        fun shownInLeagues(key: String) = shownPrefixes.any { key.startsWith(it) }
        prefs.setActiveLeagues(leagueIds)
        prefs.setFollowedTeams((followedTeams.value.filterNot { shownInLeagues(it) } + teamKeys).toSet())
        prefs.setFollowedPlayers((followedPlayers.value.filterNot { shownInLeagues(it) } + playerKeys).toSet())
        delay(1_500)
        companion.stop()
    }

    fun stopPhonePicker() = companion.stop()

    private suspend fun loadBoard(league: SportLeague, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force) {
            val at = boardAt[league.id] ?: 0L
            if (now - at < BoardTtlMs && boards.value[league.id] is BoardState.Content) return
            if (boards.value[league.id] is BoardState.Loading) return
        }
        boards.value = boards.value + (league.id to BoardState.Loading)
        val result = runCatching { api.scoreboard(league) }
        result.onFailure { android.util.Log.w("SportsHub", "board failed " + league.id, it) }
        result.onSuccess { android.util.Log.d("SportsHub", "board ok " + league.id + " fixtures=" + it.fixtures.size) }
        boardAt[league.id] = now
        boards.value = boards.value + (
            league.id to
                (result.getOrNull()?.let { BoardState.Content(it) } ?: BoardState.Error)
            )
    }

    private suspend fun loadStandings(league: SportLeague) {
        standingsCache[league.id]?.let {
            _standings.value = it
            return
        }
        _standingsLoading.value = true
        val tables = runCatching { api.standings(league) }.getOrDefault(emptyList())
        standingsCache[league.id] = tables
        _standings.value = tables
        _standingsLoading.value = false
    }

    companion object {
        /** Sentinel rail selection for the Favourites aggregate (no single league). */
        const val FavouritesId = "__favourites__"

        /** Pick-list id prefixes, so one phone submit can carry leagues, teams and athletes. */
        const val TeamItemPrefix = "team:"
        const val PlayerItemPrefix = "player:"

        /** Separator inside [SportsPrefs.teamKey] ("<leagueId>|<abbrev>"). */
        private const val TeamKeySep = '|'

        private const val BoardTtlMs = 15L * 60L * 1000L
    }
}
