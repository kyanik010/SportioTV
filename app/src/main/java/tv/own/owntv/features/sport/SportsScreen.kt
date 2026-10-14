package tv.own.owntv.features.sport

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.core.companion.CompanionSelectItem
import tv.own.owntv.core.companion.CompanionServerState
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.epg.GuideVideo
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.StageCompanionStatus
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageGroupLabel
import tv.own.owntv.ui.stage.StageMenu
import tv.own.owntv.ui.stage.StageMenuHeader
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.StageTab
import tv.own.owntv.core.theme.SportioPalette
import tv.own.owntv.core.theme.teamColor
import tv.own.owntv.ui.theme.LocalStagePalette
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Sports hub (Stage): league rail, a Live / Upcoming / Past tabbed fixture list, and a detail pane
 * with venue, broadcasts, standings and the matched user channels — one tap
 * on a channel starts playback through the shared LiveViewModel zap path.
 */
@Composable
fun SportsScreen(
    onPlayChannel: (Long, List<ChannelEntity>, String?) -> Unit,
    onChildFocused: () -> Unit,
    previewEnabled: Boolean = true,
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    onContentScrolled: (Boolean) -> Unit = {},
    contentStart: Dp = 150.mpx,
    modifier: Modifier = Modifier,
) {
    val vm: SportsViewModel = koinViewModel()
    val liveVm: LiveViewModel = koinViewModel()
    val leagues by vm.leagues.collectAsStateWithLifecycle()
    val activeLeagues by vm.activeLeagues.collectAsStateWithLifecycle()
    val selectedLeagueId by vm.selectedLeagueId.collectAsStateWithLifecycle()
    val selectedFixture by vm.selectedFixture.collectAsStateWithLifecycle()
    val matchesState by vm.matches.collectAsStateWithLifecycle()
    val standings by vm.standings.collectAsStateWithLifecycle()
    val standingsLoading by vm.standingsLoading.collectAsStateWithLifecycle()
    val myTeams by vm.myTeamFixtures.collectAsStateWithLifecycle()
    val followedTeams by vm.followedTeams.collectAsStateWithLifecycle()
    val followedPlayers by vm.followedPlayers.collectAsStateWithLifecycle()
    val livePreviewSetting by liveVm.livePreviewEnabled.collectAsStateWithLifecycle()
    val previewArmed by liveVm.previewArmed.collectAsStateWithLifecycle()
    val effectivePreview = previewEnabled && livePreviewSetting
    val preferredLanguages by vm.preferredLanguages.collectAsStateWithLifecycle()
    val languageAsked by vm.languageAsked.collectAsStateWithLifecycle()
    val channelLimit by vm.channelLimit.collectAsStateWithLifecycle()
    val excludedLanguages by vm.excludedLanguages.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }
    val remoteState by vm.remoteState.collectAsStateWithLifecycle()

    val favouritesSelected = selectedLeagueId == SportsViewModel.FavouritesId
    val fixtures by vm.currentFixtures.collectAsStateWithLifecycle()
    val boardError by vm.currentError.collectAsStateWithLifecycle()
    val boardLoading by vm.currentLoading.collectAsStateWithLifecycle()

    // Live / Demnächst / Vergangen filter for the fixtures list.
    val tab by vm.tab.collectAsStateWithLifecycle()
    val visibleFixtures = remember(fixtures, tab) { fixturesFor(fixtures, tab) }

    // Phone picker payload: labels/regions resolved here, where stringResource is available.
    val phoneTitle = stringResource(R.string.sport_phone_title)
    val phoneTeamGroup = stringResource(R.string.sport_phone_teams)
    val phonePlayerGroup = stringResource(R.string.sport_phone_players)
    val phoneLeagueItems = SportsLeagues.map { league ->
        CompanionSelectItem(
            id = league.id,
            label = stringResource(league.labelRes),
            group = stringResource(league.region.labelRes),
            selected = league.id in activeLeagues,
        )
    }
    val phoneLeagueLabels = SportsLeagues.associate { it.id to stringResource(it.labelRes) }

    val leagueFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    fun leagueRequester(id: String) = leagueFocus.getOrPut(id) { FocusRequester() }
    val gameFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    fun gameRequester(id: String) = gameFocus.getOrPut(id) { FocusRequester() }
    val matchFocus = remember { mutableStateMapOf<Long, FocusRequester>() }
    fun matchRequester(id: Long) = matchFocus.getOrPut(id) { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val addLeaguesFocus = remember { FocusRequester() }
    val favouritesFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    suspend fun focusGame(id: String) {
        if (runCatching { gameRequester(id).requestFocus() }.isSuccess) return
        val index = visibleFixtures.indexOfFirst { it.id == id }
        if (index >= 0) runCatching { listState.scrollToItem(index) }
        withFrameNanos { }
        runCatching { gameRequester(id).requestFocus() }
    }

    val entry: () -> Boolean = {
        val firstFixture = visibleFixtures.firstOrNull() ?: fixtures.firstOrNull()
        if (firstFixture != null) runCatching { gameRequester(firstFixture.id).requestFocus() }.isSuccess
        else runCatching { leagueRequester(leagues.firstOrNull()?.league?.id.orEmpty()).requestFocus() }.isSuccess
    }
    DisposableEffect(onEntryHook, fixtures) {
        onEntryHook?.invoke(entry)
        onDispose { onEntryHook?.invoke(null) }
    }

    // Detail always populated: pick the live game first, else the next upcoming one.
    LaunchedEffect(fixtures) {
        if (selectedFixture == null && fixtures.isNotEmpty()) {
            val pick = fixtures.firstOrNull { it.state == FixtureState.LIVE }
                ?: fixtures.filter { it.state == FixtureState.UPCOMING }.minByOrNull { it.dateMs }
                ?: fixtures.maxByOrNull { it.dateMs }
            vm.selectFixture(pick)
        }
    }

    LaunchedEffect(restoreFocus) {
        if (!restoreFocus) return@LaunchedEffect
        withFrameNanos { }
        val id = selectedFixture?.id
        if (id != null) focusGame(id) else entry()
        onRestored()
    }

    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24 } }
    LaunchedEffect(scrolled) { onContentScrolled(scrolled) }

    // A different (or no) game selected: drop the previous game's in-pane preview stream. Tracked as a
    // change, NOT a dispose handler, on purpose — going full-screen hides the whole browse UI and disposes
    // this screen, and stopping here would abort the very channel that was just started (the trap
    // LiveScreen documents). The null sentinel keeps a fresh composition (first entry, or back from the
    // full-screen/mini player) from stopping the stream that is still playing.
    var previewedFixtureId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedFixture?.id) {
        val id = selectedFixture?.id
        if (previewedFixtureId != null && previewedFixtureId != id) liveVm.stopPreview()
        previewedFixtureId = id
    }

    // The first time a fixture's channels are ready, ask which languages to show — before the list
    // can be read. Choosing (or dismissing) records the answer, so this runs at most once.
    LaunchedEffect(matchesState, languageAsked) {
        val content = matchesState as? SportsViewModel.MatchesState.Content ?: return@LaunchedEffect
        if (!languageAsked && content.matches.isNotEmpty()) showLanguage = true
    }

    BoxWithConstraints(modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) onChildFocused() }) {
        val screenW = maxWidth
        fun fx(px: Int) = screenW * (px / 1920f)
        Row(
            Modifier
                .padding(start = contentStart + 20.mpx, top = 52.mpx, end = 28.mpx)
                .fillMaxSize(),
        ) {
            // League rail.
            Column(Modifier.width(fx(330)).fillMaxHeight()) {
                Text(stringResource(R.string.sport_title), style = stageText(46, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Favourites aggregates every active league plus the user's followed teams.
                StageSurface(
                    onClick = { vm.selectFavourites() },
                    radius = 18.mpx,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.mpx).focusRequester(favouritesFocus),
                    idle = if (favouritesSelected) Modifier.background(stageAccent.accent.copy(alpha = 0.14f), RoundedCornerShape(18.mpx)) else Modifier,
                ) { focused ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.mpx, vertical = 12.mpx),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.mpx),
                    ) {
                        OwnTVIcon(
                            OwnTVIcon.STAR,
                            if (focused) stageAccent.onAccent else if (favouritesSelected) stageAccent.accent else StageColors.NavText,
                            Modifier.size(24.mpx),
                        )
                        Text(
                            stringResource(R.string.sport_favourites),
                            style = stageText(20, if (favouritesSelected) 700 else 500),
                            color = if (focused) stageAccent.onAccent else StageColors.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(stringResource(R.string.sport_leagues), style = stageText(20, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.mpx, bottom = 10.mpx))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.mpx), modifier = Modifier.fillMaxHeight()) {
                    items(leagues, key = { it.league.id }) { league ->
                        val selected = league.league.id == selectedLeagueId
                        StageSurface(
                            onClick = { vm.selectLeague(league.league.id) },
                            onLongClick = { vm.toggleLeagueFollowed(league.league.id) },
                            radius = 18.mpx,
                            modifier = Modifier.fillMaxWidth().focusRequester(leagueRequester(league.league.id)),
                            idle = if (selected) Modifier.background(stageAccent.accent.copy(alpha = 0.14f), RoundedCornerShape(18.mpx)) else Modifier,
                        ) { focused ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.mpx, vertical = 12.mpx),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.mpx),
                            ) {
                                OwnTVIcon(
                                    OwnTVIcon.SPORT,
                                    if (focused) stageAccent.onAccent else if (selected) stageAccent.accent else StageColors.NavText,
                                    Modifier.size(24.mpx),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(league.league.labelRes), style = stageText(20, if (selected) 700 else 500), color = if (focused) stageAccent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (league.liveCount > 0) {
                                    LiveDot(league.liveCount.toString())
                                } else if (league.loading) {
                                    Text(stringResource(R.string.sport_loading), style = stageText(14, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    item(key = "add-leagues") {
                        StageSurface(
                            onClick = { showSettings = true },
                            radius = 18.mpx,
                            modifier = Modifier.fillMaxWidth().focusRequester(addLeaguesFocus),
                        ) { focused ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.mpx, vertical = 12.mpx),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.mpx),
                            ) {
                                OwnTVIcon(
                                    OwnTVIcon.ADD,
                                    if (focused) stageAccent.onAccent else StageColors.NavText,
                                    Modifier.size(24.mpx),
                                )
                                Text(
                                    stringResource(R.string.sport_add_leagues),
                                    style = stageText(20, 500),
                                    color = if (focused) stageAccent.onAccent else StageColors.Muted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            // Fixtures (extra top padding like the detail pane: shell clock/weather overlays the top).
            Column(Modifier.weight(1.35f).fillMaxHeight().padding(start = 20.mpx, end = 20.mpx, top = 64.mpx)) {
                val leagueName = if (favouritesSelected) {
                    stringResource(R.string.sport_favourites)
                } else {
                    leagues.firstOrNull { it.league.id == selectedLeagueId }
                        ?.let { stringResource(it.league.labelRes) }.orEmpty()
                }
                Text(leagueName, style = stageText(30, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.mpx), modifier = Modifier.fillMaxWidth().padding(top = 10.mpx)) {
                    StageSurface(
                        onClick = { showSettings = true },
                        radius = 16.mpx,
                        modifier = Modifier.focusRequester(settingsFocus),
                    ) { focused ->
                        Row(Modifier.padding(horizontal = 14.mpx, vertical = 8.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                            OwnTVIcon(OwnTVIcon.SETTINGS, if (focused) stageAccent.onAccent else StageColors.Text, Modifier.size(20.mpx))
                            Text(stringResource(R.string.sport_settings), style = stageText(17, 600), color = if (focused) stageAccent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    StageSurface(
                        onClick = { vm.refreshSelected() },
                        radius = 16.mpx,
                        modifier = Modifier.focusRequester(refreshFocus),
                    ) { focused ->
                        Row(Modifier.padding(horizontal = 14.mpx, vertical = 8.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                            OwnTVIcon(OwnTVIcon.REFRESH, if (focused) stageAccent.onAccent else StageColors.Text, Modifier.size(20.mpx))
                            Text(stringResource(R.string.sport_retry), style = stageText(17, 600), color = if (focused) stageAccent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (favouritesSelected && myTeams.isNotEmpty()) {
                    Text(stringResource(R.string.sport_my_teams), style = stageText(20, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.mpx, bottom = 8.mpx))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                        items(myTeams, key = { it.id }) { fixture ->
                            StageSurface(
                                onClick = { vm.selectFixture(fixture) },
                                radius = 14.mpx,
                                focusStyle = StageFocus.FX,
                                modifier = Modifier.focusRequester(gameRequester(fixture.id)),
                            ) { focused ->
                                FixtureChip(fixture, followedTeams, focused)
                            }
                        }
                    }
                }
                // Live / Demnächst / Vergangen — the tabs filter the list right below them.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.mpx),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.mpx),
                ) {
                    FixtureTabPill(stringResource(R.string.sport_tab_live), tab == SportsViewModel.FixtureTab.LIVE) {
                        vm.setTab(SportsViewModel.FixtureTab.LIVE)
                    }
                    FixtureTabPill(stringResource(R.string.sport_tab_upcoming), tab == SportsViewModel.FixtureTab.UPCOMING) {
                        vm.setTab(SportsViewModel.FixtureTab.UPCOMING)
                    }
                    FixtureTabPill(stringResource(R.string.sport_tab_past), tab == SportsViewModel.FixtureTab.PAST) {
                        vm.setTab(SportsViewModel.FixtureTab.PAST)
                    }
                }
                when {
                    boardLoading && fixtures.isEmpty() -> {
                        Box(Modifier.fillMaxWidth().padding(top = 60.mpx), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.sport_loading), style = stageText(22, 500), color = StageColors.Muted)
                        }
                    }
                    boardError && fixtures.isEmpty() -> {
                        Column(Modifier.fillMaxWidth().padding(top = 60.mpx), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.mpx)) {
                            Text(stringResource(R.string.sport_load_failed), style = stageText(22, 500), color = StageColors.Muted)
                            StageSurface(onClick = { vm.refreshSelected() }, radius = 16.mpx) { focused ->
                                Text(stringResource(R.string.sport_retry), style = stageText(19, 600), color = if (focused) stageAccent.onAccent else StageColors.Text, modifier = Modifier.padding(horizontal = 22.mpx, vertical = 10.mpx))
                            }
                        }
                    }
                    fixtures.isEmpty() -> {
                        Box(Modifier.fillMaxWidth().padding(top = 60.mpx), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.sport_no_games), style = stageText(22, 500), color = StageColors.Muted)
                        }
                    }
                    visibleFixtures.isEmpty() -> {
                        Box(Modifier.fillMaxWidth().padding(top = 60.mpx), contentAlignment = Alignment.Center) {
                            Text(stringResource(emptyTabLabel(tab)), style = stageText(22, 500), color = StageColors.Muted)
                        }
                    }
                    else -> {
                        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxHeight().padding(top = 12.mpx)) {
                            items(visibleFixtures, key = { it.id }) { fixture ->
                                val selected = fixture.id == selectedFixture?.id
                                StageSurface(
                                    onClick = { vm.selectFixture(fixture) },
                                    radius = 18.mpx,
                                    modifier = Modifier.fillMaxWidth().focusRequester(gameRequester(fixture.id)),
                                    idle = if (selected) Modifier.background(stageAccent.accent.copy(alpha = 0.14f), RoundedCornerShape(18.mpx)) else Modifier,
                                ) { focused ->
                                    FixtureCard(fixture, followedTeams, focused, selected)
                                }
                            }
                            item { Spacer(Modifier.height(40.mpx)) }
                        }
                    }
                }
            }
            // Detail pane (extra top padding: shell clock/weather overlays the top-right).
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 64.mpx)) {
                val fixture = selectedFixture
                if (fixture == null) {
                    Text(stringResource(R.string.sport_details), style = stageText(30, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.mpx))
                    Text(stringResource(R.string.sport_no_games), style = stageText(20, 500), color = StageColors.Muted)
                } else {
                    val zapTitle = fixtureZapTitle(fixture)
                    FixtureDetail(
                        fixture = fixture,
                        followedTeams = followedTeams,
                        followedPlayers = followedPlayers,
                        matchesState = matchesState,
                        preferredLanguages = preferredLanguages,
                        standings = standings,
                        standingsLoading = standingsLoading,
                        matchRequester = ::matchRequester,
                        onPlayMatch = { match, list -> vm.playMatch(match, list) { id, entities -> onPlayChannel(id, entities, zapTitle) } },
                        onToggleTeam = { leagueId, abbrev -> vm.toggleTeamFollowed(leagueId, abbrev) },
                        onTogglePlayer = { leagueId, playerId -> vm.togglePlayerFollowed(leagueId, playerId) },
                        sportsVm = vm,
                        liveVm = liveVm,
                        effectivePreview = effectivePreview,
                        previewArmed = previewArmed,
                    )
                }
            }
        }
        if (showSettings) {
            SportsSettingsDialog(
                leagues = SportsLeagues,
                active = activeLeagues,
                channelLimit = channelLimit,
                excluded = excludedLanguages,
                onDecreaseLimit = { vm.setChannelLimit(channelLimit - 1) },
                onIncreaseLimit = { vm.setChannelLimit(channelLimit + 1) },
                onToggleExclude = { vm.toggleExcludedLanguage(it) },
                onToggleLeague = { vm.toggleLeagueFollowed(it) },
                onOpenLanguage = {
                    showSettings = false
                    showLanguage = true
                },
                onManageOnPhone = {
                    showSettings = false
                    showPhone = true
                    vm.startPhonePicker(
                        CompanionLink.DEFAULT_PORT,
                        phoneTitle,
                        phoneLeagueItems,
                        phoneLeagueLabels,
                        phoneTeamGroup,
                        phonePlayerGroup,
                    )
                },
                onDismiss = { showSettings = false },
            )
        }
        if (showLanguage) {
            SportLanguageDialog(
                selected = preferredLanguages,
                onChange = { vm.setPreferredLanguages(it) },
                onDismiss = {
                    showLanguage = false
                    vm.markLanguageAsked()
                },
            )
        }
        if (showPhone) {
            SportsPhoneDialog(
                state = remoteState,
                onDismiss = {
                    showPhone = false
                    vm.stopPhonePicker()
                },
            )
        }
    }
}

/** The fixtures shown for [tab]: Live / Upcoming (soonest first) / Past (most recent first, bounded). */
private fun fixturesFor(fixtures: List<Fixture>, tab: SportsViewModel.FixtureTab): List<Fixture> = when (tab) {
    SportsViewModel.FixtureTab.LIVE -> fixtures.filter { it.state == FixtureState.LIVE }.sortedBy { it.dateMs }
    SportsViewModel.FixtureTab.UPCOMING -> fixtures.filter { it.state == FixtureState.UPCOMING }.sortedBy { it.dateMs }
    SportsViewModel.FixtureTab.PAST -> fixtures.filter { it.state == FixtureState.FINISHED }.sortedByDescending { it.dateMs }.take(30)
}

/** Empty-state text for [tab]. */
private fun emptyTabLabel(tab: SportsViewModel.FixtureTab): Int = when (tab) {
    SportsViewModel.FixtureTab.LIVE -> R.string.sport_no_live_games
    SportsViewModel.FixtureTab.UPCOMING -> R.string.sport_no_upcoming_games
    SportsViewModel.FixtureTab.PAST -> R.string.sport_no_past_games
}

/** One tab in the fixtures column (Live / Demnächst / Vergangen). */
@Composable
private fun FixtureTabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = stageAccent
    StageSurface(
        onClick = onClick,
        radius = 14.mpx,
        idle = if (selected) Modifier.background(accent.accent.copy(alpha = 0.14f), RoundedCornerShape(14.mpx)) else Modifier,
    ) { focused ->
        Text(
            label,
            style = stageText(18, if (selected) 700 else 500),
            color = if (focused) accent.onAccent else if (selected) accent.accent else StageColors.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.mpx, vertical = 9.mpx),
        )
    }
}

private fun dayLabel(dateMs: Long, today: String, tomorrow: String, yesterday: String): String? {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(dateMs).atZone(zone).toLocalDate()
    val now = LocalDate.now(zone)
    return when (date) {
        now -> null
        now.plusDays(1) -> tomorrow
        now.minusDays(1) -> yesterday
        else -> date.format(DateTimeFormatter.ofPattern(DayPattern))
    }
}

@Composable
private fun LiveDot(count: String) {
    Box(
        Modifier
            .background(stageAccent.accent, RoundedCornerShape(12.mpx))
            .padding(horizontal = 10.mpx, vertical = 4.mpx),
        contentAlignment = Alignment.Center,
    ) {
        Text(count, style = stageText(15, 700), color = stageAccent.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TeamLogo(url: String?, size: Dp) {
    if (url.isNullOrBlank()) {
        Spacer(Modifier.size(size))
    } else {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
    }
}

/**
 * A club's 6-px bar in a theme-safe tone: ESPN's raw `colorHex` can be near-black and would vanish
 * on the dark page, so the hue is bucketed into [SportioPalette.Team] and the theme-correct tone
 * of the pair is used (brand guide §5.6). Team colours never leak into buttons, focus or text.
 */
@Composable
private fun teamColor(hex: String?): Color? {
    val clean = hex?.takeIf { it.isNotBlank() }?.removePrefix(HashPrefix) ?: return null
    val argb = runCatching { (ColorPrefix + clean).toLong(16) }.getOrNull()?.toInt() ?: return null
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    val (hue, sat, value) = Triple(hsv[0], hsv[1], hsv[2])
    val pair = if (sat < 0.18f || value < 0.12f || (sat < 0.3f && value > 0.85f)) {
        SportioPalette.Team.Slate
    } else when (hue) {
        in 12f..38f -> SportioPalette.Team.Orange
        in 38f..66f -> SportioPalette.Team.Amber
        in 66f..150f -> SportioPalette.Team.Green
        in 150f..185f -> SportioPalette.Team.Sky
        in 185f..235f -> SportioPalette.Team.Blue
        in 235f..265f -> SportioPalette.Team.Indigo
        in 265f..300f -> SportioPalette.Team.Violet
        in 300f..345f -> SportioPalette.Team.Crimson
        else -> SportioPalette.Team.Crimson
    }
    return Color(teamColor(pair, !LocalStagePalette.current.isLight))
}

@Composable
private fun FixtureCard(fixture: Fixture, followedTeams: Set<String>, focused: Boolean, selected: Boolean) {
    val accent = stageAccent
    val titleColor = if (focused) accent.onAccent else StageColors.Text
    val dimColor = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim
    val today = stringResource(R.string.sport_today_word)
    val tomorrow = stringResource(R.string.sport_tomorrow_word)
    val yesterday = stringResource(R.string.sport_yesterday_word)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.mpx, vertical = 12.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.mpx),
    ) {
        Box(Modifier.width(5.mpx).height(52.mpx).background(teamColor(fixture.home?.colorHex) ?: Color.Transparent, RoundedCornerShape(3.mpx)))
        Column(Modifier.weight(1f)) {
            val home = fixture.home
            val away = fixture.away
            if (home != null && away != null) {
                listOf(home, away).forEach { team ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx)) {
                        TeamLogo(team.logoUrl, 32.mpx)
                        Text(team.abbreviation, style = stageText(22, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            team.score ?: fixture.statusLine,
                            style = stageText(22, 700),
                            color = if (fixture.state == FixtureState.LIVE) accent.accent else titleColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else if (fixture.format == FixtureFormat.VERSUS) {
                // A duel (tennis/MMA): both athletes on an equal footing, same colour; the winner is
                // bold with a check, never a different colour (names are not scores).
                fixture.entries.take(2).forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx)) {
                        TeamLogo(entry.logoUrl, 32.mpx)
                        Text(
                            entry.name,
                            style = stageText(22, if (entry.winner) 700 else 500),
                            color = titleColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        entry.detail?.let { detail ->
                            Text(detail, style = stageText(22, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (entry.winner) OwnTVIcon(OwnTVIcon.CHECK, accent.accent, Modifier.size(18.mpx))
                    }
                }
            } else if (fixture.format == FixtureFormat.EVENT) {
                // A single occasion (F1 Grand Prix): the race name + circuit, no participants at all.
                Text(
                    fixture.title ?: fixture.statusLine,
                    style = stageText(22, 700), color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                fixture.venue?.let { venue ->
                    Text(venue, style = stageText(16, 500), color = dimColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                // A leaderboard (golf): the leader large, the next chasers small.
                Text(
                    fixture.entries.firstOrNull()?.name ?: fixture.statusLine,
                    style = stageText(22, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                fixture.entries.drop(1).take(2).forEach { entry ->
                    Text(entry.name, style = stageText(17, 500), color = dimColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val day = if (fixture.state == FixtureState.UPCOMING) dayLabel(fixture.dateMs, today, tomorrow, yesterday) else null
            val subLine = day ?: fixture.statusLine.takeIf { fixture.state == FixtureState.FINISHED }
            if (subLine != null) {
                Text(
                    subLine,
                    style = stageText(15, 500),
                    color = dimColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.mpx),
                )
            }
        }
        if (fixture.state == FixtureState.LIVE) {
            LiveDot(fixture.statusLine)
        } else if (selected) {
            OwnTVIcon(OwnTVIcon.CHECK, accent.accent, Modifier.size(22.mpx))
        }
    }
}

@Composable
private fun FixtureChip(fixture: Fixture, followedTeams: Set<String>, focused: Boolean) {
    val accent = stageAccent
    val home = fixture.home
    val away = fixture.away
    val titleColor = if (focused) accent.onAccent else StageColors.Text
    val scoreColor = if (fixture.state == FixtureState.LIVE) accent.accent else if (focused) accent.onAccent else StageColors.Dim
    Row(
        Modifier.padding(horizontal = 14.mpx, vertical = 9.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.mpx),
    ) {
        if (home != null && away != null) {
            TeamLogo(home.logoUrl, 24.mpx)
            Text(home.abbreviation, style = stageText(17, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fixtureScoreLine(fixture), style = stageText(17, 600), color = scoreColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TeamLogo(away.logoUrl, 24.mpx)
            Text(away.abbreviation, style = stageText(17, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            // A duel/field entry (tennis/MMA/golf): the chip names the athletes, then the status.
            val names = fixture.entries.take(2).joinToString(" ${stringResource(R.string.sport_versus)} ") { it.name }
            Text(names, style = stageText(17, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fixture.statusLine, style = stageText(17, 600), color = scoreColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun fixtureScoreLine(fixture: Fixture): String {
    val home = fixture.home?.score
    val away = fixture.away?.score
    return if (home != null && away != null) stringResource(R.string.sport_score, home, away) else fixture.statusLine
}

/** Overlay heading for the in-player fixture channel list (roadmap point 6): the matchup, the
 *  event name, or the leader — never blank. Dynamic (team names), so no string resource. */
@Composable
private fun fixtureZapTitle(fixture: Fixture): String {
    val versus = stringResource(R.string.sport_versus)
    val home = fixture.home
    val away = fixture.away
    return when {
        home != null && away != null -> "${home.abbreviation} $versus ${away.abbreviation}"
        fixture.format == FixtureFormat.VERSUS ->
            fixture.entries.take(2).joinToString(" $versus ") { it.name }.ifBlank { fixture.statusLine }
        fixture.format == FixtureFormat.EVENT -> fixture.title ?: fixture.statusLine
        else -> fixture.entries.firstOrNull()?.name ?: fixture.statusLine
    }
}

@Composable
private fun FixtureDetail(
    fixture: Fixture,
    followedTeams: Set<String>,
    followedPlayers: Set<String>,
    matchesState: SportsViewModel.MatchesState,
    preferredLanguages: Set<SportLanguage>,
    standings: List<LeagueTable>,
    standingsLoading: Boolean,
    matchRequester: (Long) -> FocusRequester,
    onPlayMatch: (ChannelMatch, List<ChannelMatch>) -> Unit,
    onToggleTeam: (String, String) -> Unit,
    onTogglePlayer: (String, String) -> Unit,
    sportsVm: SportsViewModel,
    liveVm: LiveViewModel,
    effectivePreview: Boolean,
    previewArmed: Boolean,
) {
    val accent = stageAccent
    // The matcher already filtered by the preferred languages (before its cap) — show them as-is.
    val matches = (matchesState as? SportsViewModel.MatchesState.Content)?.matches.orEmpty()
    var entities by remember(fixture.id) { mutableStateOf(emptyList<ChannelEntity>()) }
    var focusedChannelId by remember(fixture.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(fixture.id, matches) {
        entities = if (matches.isEmpty()) emptyList() else sportsVm.entitiesFor(matches)
        if (focusedChannelId == null) focusedChannelId = matches.firstOrNull()?.channelId
    }
    val previewEntity = entities.firstOrNull { it.id == focusedChannelId } ?: entities.firstOrNull()
    val previewEpg = matches.firstOrNull { it.channelId == previewEntity?.id }?.epgTitle
    LaunchedEffect(previewEntity?.id, effectivePreview, previewArmed) {
        val entity = previewEntity ?: return@LaunchedEffect
        if (!effectivePreview || !previewArmed) return@LaunchedEffect
        delay(700)
        liveVm.playPreview(entity)
    }
    Column(modifier = Modifier.fillMaxHeight()) {
        if (previewEntity != null) {
            GuideVideo(
                channel = previewEntity,
                channelLine = previewEntity.name,
                nowTitle = previewEpg,
                previewEngine = liveVm.previewEngine,
                showVideo = effectivePreview && previewArmed,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.mpx),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.weight(1f)) {
        item(key = "detail" + fixture.id) {
            Column(verticalArrangement = Arrangement.spacedBy(8.mpx)) {
                Text(stringResource(R.string.sport_details), style = stageText(30, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val homeTeam = fixture.home
                val awayTeam = fixture.away
                if (homeTeam != null && awayTeam != null) {
                    TeamScoreRow(homeTeam, fixture.state)
                    TeamScoreRow(awayTeam, fixture.state)
                } else if (fixture.format == FixtureFormat.VERSUS) {
                    // A duel (tennis/MMA): the two athletes as equal sides, same colour; the winner is
                    // bold with a check.
                    fixture.entries.take(2).forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                            TeamLogo(entry.logoUrl, 40.mpx)
                            Text(
                                entry.name,
                                style = stageText(22, if (entry.winner) 700 else 600),
                                color = StageColors.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            entry.detail?.let { detail ->
                                Text(detail, style = stageText(20, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (entry.winner) OwnTVIcon(OwnTVIcon.CHECK, accent.accent, Modifier.size(20.mpx))
                        }
                    }
                } else if (fixture.format == FixtureFormat.EVENT) {
                    // A single occasion (F1 Grand Prix): the race name; the location follows below.
                    Text(
                        fixture.title ?: fixture.statusLine,
                        style = stageText(24, 700), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    // A leaderboard (golf): rank · athlete · value.
                    fixture.entries.take(10).forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                            Text(entry.order?.toString().orEmpty(), style = stageText(16, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(30.mpx))
                            TeamLogo(entry.logoUrl, 26.mpx)
                            Text(
                                entry.name,
                                style = stageText(19, if (entry.winner) 700 else 500),
                                color = if (entry.winner) accent.accent else StageColors.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            entry.detail?.let { detail ->
                                Text(detail, style = stageText(18, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (entry.winner) OwnTVIcon(OwnTVIcon.CHECK, accent.accent, Modifier.size(18.mpx))
                        }
                    }
                }
                if (fixture.state != FixtureState.UPCOMING || fixture.statusLine.isNotBlank()) {
                    Text(fixture.statusLine, style = stageText(18, 600), color = if (fixture.state == FixtureState.LIVE) accent.accent else StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                fixture.venue?.let { venue ->
                    Text(stringResource(R.string.sport_venue), style = stageText(15, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(venue, style = stageText(18, 500), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (fixture.broadcasts.isNotEmpty()) {
                    Text(stringResource(R.string.sport_broadcast), style = stageText(15, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    fixture.broadcasts.take(4).forEach { name ->
                        Text(name, style = stageText(18, 500), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (homeTeam != null && awayTeam != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                        FollowButton(fixture.leagueId, homeTeam.abbreviation, followedTeams.contains(SportsPrefs.teamKey(fixture.leagueId, homeTeam.abbreviation)), Modifier.weight(1f)) {
                            onToggleTeam(fixture.leagueId, homeTeam.abbreviation)
                        }
                        FollowButton(fixture.leagueId, awayTeam.abbreviation, followedTeams.contains(SportsPrefs.teamKey(fixture.leagueId, awayTeam.abbreviation)), Modifier.weight(1f)) {
                            onToggleTeam(fixture.leagueId, awayTeam.abbreviation)
                        }
                    }
                } else if (fixture.format == FixtureFormat.VERSUS) {
                    // A duel (tennis / MMA): follow the athlete. Deliberately NOT a leaderboard
                    // (golf/F1/racing): following a single driver or a golfer makes no sense.
                    val duelists = fixture.entries.filter { it.id != null }.take(2)
                    if (duelists.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                            duelists.forEach { entry ->
                                val playerId = entry.id ?: return@forEach
                                FollowButton(
                                    fixture.leagueId,
                                    entry.name,
                                    followedPlayers.contains(SportsPrefs.playerKey(fixture.leagueId, playerId)),
                                    Modifier.weight(1f),
                                ) {
                                    onTogglePlayer(fixture.leagueId, playerId)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "channels" + fixture.id) {
            Column(verticalArrangement = Arrangement.spacedBy(8.mpx), modifier = Modifier.padding(top = 8.mpx)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sport_channels), style = stageText(22, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val refreshing = matchesState == SportsViewModel.MatchesState.Loading
                    StageSurface(
                        onClick = { sportsVm.refreshMatches() },
                        radius = 14.mpx,
                        enabled = !refreshing,
                    ) { focused ->
                        Row(Modifier.padding(horizontal = 12.mpx, vertical = 7.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                            OwnTVIcon(OwnTVIcon.REFRESH, if (focused) accent.onAccent else StageColors.Text, Modifier.size(18.mpx))
                            Text(stringResource(R.string.sport_refresh_channels), style = stageText(16, 600), color = if (focused) accent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (preferredLanguages.isNotEmpty()) {
                    val languageLabels = SportLanguages.order
                        .filter { it in preferredLanguages }
                        .map { stringResource(it.labelRes) }
                    Text(
                        stringResource(R.string.sport_language_filter, languageLabels.joinToString()),
                        style = stageText(14, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                when (matchesState) {
                    SportsViewModel.MatchesState.Idle, SportsViewModel.MatchesState.Loading -> {
                        Text(stringResource(R.string.sport_loading), style = stageText(17, 500), color = StageColors.Dim)
                    }
                    is SportsViewModel.MatchesState.Content -> {
                        val list = matches
                        if (list.isEmpty()) {
                            Text(stringResource(R.string.sport_no_channel), style = stageText(17, 500), color = StageColors.Dim)
                        } else {
                            list.forEach { match ->
                                StageSurface(
                                    onClick = { onPlayMatch(match, list) },
                                    radius = 14.mpx,
                                    modifier = Modifier.fillMaxWidth().focusRequester(matchRequester(match.channelId))
                                        .onFocusChanged {
                                            if (it.hasFocus) {
                                                focusedChannelId = match.channelId
                                                entities.firstOrNull { entity -> entity.id == match.channelId }?.let { entity ->
                                                    liveVm.onChannelFocused(entity)
                                                }
                                            }
                                        },
                                ) { focused ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 14.mpx, vertical = 10.mpx),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.mpx),
                                    ) {
                                        OwnTVIcon(OwnTVIcon.PLAY, if (focused) accent.onAccent else accent.accent, Modifier.size(22.mpx))
                                        Column(Modifier.weight(1f)) {
                                            Text(match.channelName, style = stageText(19, 600), color = if (focused) accent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            match.epgTitle?.let { title ->
                                                Text(title, style = stageText(14, 500), color = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        Text(stringResource(R.string.sport_play), style = stageText(15, 600), color = if (focused) accent.onAccent else StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (standingsLoading) {
            item(key = "table-loading") {
                Text(stringResource(R.string.sport_loading), style = stageText(17, 500), color = StageColors.Dim, modifier = Modifier.padding(top = 8.mpx))
            }
        }
        standings.forEach { table ->
            item(key = "table" + fixture.leagueId + (table.groupName.orEmpty())) {
                Column(verticalArrangement = Arrangement.spacedBy(6.mpx), modifier = Modifier.padding(top = 8.mpx)) {
                    Text(stringResource(R.string.sport_standings), style = stageText(22, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    table.groupName?.let { name ->
                        Text(name, style = stageText(15, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    table.rows.take(10).forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                            Text(row.rank, style = stageText(16, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(30.mpx))
                            TeamLogo(row.logoUrl, 26.mpx)
                            Text(row.teamAbbrev.ifBlank { row.teamName }, style = stageText(17, 600), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            row.stats.take(3).forEach { stat ->
                                Text(stat, style = stageText(16, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(44.mpx))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(60.mpx)) }
        }
    }
}

/**
 * Sport settings: three tabs (leagues, channels, languages) instead of one endless list. All
 * callbacks and states are passed through unchanged — only the grouping is new. The preferred
 * languages stay in [SportLanguageDialog] (opened from the languages tab); the phone picker in
 * the leagues tab (it manages leagues, teams and players).
 */
@Composable
private fun SportsSettingsDialog(
    leagues: List<SportLeague>,
    active: Set<String>,
    channelLimit: Int,
    excluded: Set<SportLanguage>,
    onDecreaseLimit: () -> Unit,
    onIncreaseLimit: () -> Unit,
    onToggleExclude: (SportLanguage) -> Unit,
    onToggleLeague: (String) -> Unit,
    onOpenLanguage: () -> Unit,
    onManageOnPhone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onDismiss() }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim)) {
            Column(
                Modifier
                    .padding(start = 560.mpx, top = 120.mpx, bottom = 24.mpx)
                    .width(640.mpx)
                    .trapAllFocusExit()
                    .focusGroup(),
            ) {
                var tab by remember { mutableIntStateOf(0) }
                var regionTab by remember { mutableIntStateOf(0) }
                StageMenu(Modifier.verticalScroll(rememberScrollState())) {
                    StageMenuHeader(
                        title = stringResource(R.string.sport_settings),
                        subtitle = null,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.mpx),
                    ) {
                        StageTab(
                            label = stringResource(R.string.sport_settings_tab_leagues),
                            count = active.size.toString(),
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            modifier = Modifier.weight(1f).focusRequester(firstFocus),
                        )
                        StageTab(
                            label = stringResource(R.string.sport_settings_tab_channels),
                            count = channelLimit.toString(),
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            modifier = Modifier.weight(1f),
                        )
                        StageTab(
                            label = stringResource(R.string.sport_settings_tab_languages),
                            count = excluded.size.toString(),
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    when (tab) {
                        0 -> {
                            val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
                            val chevron = stringResource(R.string.sport_sublevel_chevron)
                            StageMenuItem(
                                text = stringResource(R.string.sport_manage_on_phone),
                                icon = OwnTVIcon.PHONE,
                                value = chevron,
                                onClick = onManageOnPhone,
                            )
                            // Region switcher (top level): globe icon + ‹ n › value set it apart from
                            // the league rows below; Left/Right cycle the region, OK steps forward.
                            StageGroupLabel(stringResource(R.string.sport_region_title).uppercase(locale))
                            val region = RegionOrder[regionTab.coerceIn(RegionOrder.indices)]
                            val regionLeagues = leagues.filter { it.region == region }
                            val regionActive = regionLeagues.count { it.id in active }
                            StageMenuItem(
                                text = stringResource(region.labelRes),
                                value = stringResource(R.string.sport_region_cycler_value, regionActive.toString()),
                                icon = OwnTVIcon.NETWORK,
                                onClick = { regionTab = (regionTab + 1) % RegionOrder.size },
                                modifier = Modifier.onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionLeft -> {
                                            regionTab = (regionTab + RegionOrder.size - 1) % RegionOrder.size
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            regionTab = (regionTab + 1) % RegionOrder.size
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            )
                            Text(
                                stringResource(R.string.sport_region_switch_hint),
                                style = stageText(15, 500),
                                color = StageColors.Dim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, top = 8.mpx, bottom = 4.mpx),
                            )
                            // Sublevel: the checklist shows only the leagues of the region above.
                            StageGroupLabel(stringResource(region.labelRes).uppercase(locale))
                            regionLeagues.forEach { league ->
                                StageMenuItem(
                                    text = stringResource(league.labelRes),
                                    icon = OwnTVIcon.SPORT,
                                    checked = league.id in active,
                                    onClick = { onToggleLeague(league.id) },
                                )
                            }
                        }
                        1 -> {
                            // Channels per game as a slider: Left/Right adjust within
                            // SportsPrefs.Min/MaxChannelLimit, OK steps up; the value stays visible.
                            // (Replaces the former Fewer/More stepper rows; both callbacks are kept.)
                            StageMenuItem(
                                text = stringResource(R.string.sport_channel_limit_title),
                                value = channelLimit.toString(),
                                onClick = { if (channelLimit < SportsPrefs.MaxChannelLimit) onIncreaseLimit() },
                                modifier = Modifier.onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionLeft -> {
                                            if (channelLimit > SportsPrefs.MinChannelLimit) onDecreaseLimit()
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            if (channelLimit < SportsPrefs.MaxChannelLimit) onIncreaseLimit()
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            )
                            val span = (SportsPrefs.MaxChannelLimit - SportsPrefs.MinChannelLimit).coerceAtLeast(1)
                            val fraction = (channelLimit - SportsPrefs.MinChannelLimit).coerceIn(0, span) / span.toFloat()
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.mpx, end = 14.mpx, top = 8.mpx)
                                    .height(6.mpx)
                                    .background(StageColors.ControlFill, RoundedCornerShape(3.mpx)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fraction)
                                        .background(stageAccent.accent, RoundedCornerShape(3.mpx)),
                                )
                            }
                            Text(
                                stringResource(R.string.sport_channel_limit_hint),
                                style = stageText(15, 500),
                                color = StageColors.Dim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, top = 8.mpx),
                            )
                            Text(
                                pluralStringResource(R.plurals.sport_language_quota, SportsMatcher.MinPreferredCount, SportsMatcher.MinPreferredCount),
                                style = stageText(15, 500),
                                color = StageColors.Dim,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, top = 4.mpx),
                            )
                        }
                        else -> {
                            val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
                            // Preferred languages live in their own dialog (sublevel, ›); Always hide
                            // below is an inline checklist — the group labels keep the two apart.
                            StageGroupLabel(stringResource(R.string.sport_preferred_title).uppercase(locale))
                            StageMenuItem(
                                text = stringResource(R.string.sport_language),
                                icon = OwnTVIcon.LANGUAGE,
                                value = stringResource(R.string.sport_sublevel_chevron),
                                onClick = onOpenLanguage,
                            )
                            // Always hide: excluded languages never appear, not even as exact hits.
                            StageGroupLabel(stringResource(R.string.sport_exclude_title).uppercase(locale))
                            Text(
                                stringResource(R.string.sport_exclude_subtitle),
                                style = stageText(15, 500),
                                color = StageColors.Dim,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, bottom = 4.mpx),
                            )
                            SportLanguages.order.forEach { language ->
                                StageMenuItem(
                                    text = stringResource(language.labelRes),
                                    icon = OwnTVIcon.LANGUAGE,
                                    checked = language in excluded,
                                    onClick = { onToggleExclude(language) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Language selection for the matched channels: pick one or more languages (or "all"). Reached from
 * the Sport settings and shown automatically the first time channels are matched. The choice filters
 * the channel list by the country tag in a channel's name — see [SportLanguages].
 */
@Composable
private fun SportLanguageDialog(
    selected: Set<SportLanguage>,
    onChange: (Set<SportLanguage>) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onDismiss() }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim)) {
            Column(
                Modifier
                    .padding(start = 560.mpx, top = 150.mpx, bottom = 24.mpx)
                    .width(640.mpx)
                    .trapAllFocusExit()
                    .focusGroup(),
            ) {
                StageMenu(Modifier.verticalScroll(rememberScrollState())) {
                    StageMenuHeader(
                        title = stringResource(R.string.sport_language),
                        subtitle = stringResource(R.string.sport_language_subtitle),
                    )
                    StageMenuItem(
                        text = stringResource(R.string.sport_language_all),
                        icon = OwnTVIcon.LANGUAGE,
                        checked = selected.isEmpty(),
                        onClick = { onChange(emptySet()) },
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                    SportLanguages.order.forEach { language ->
                        StageMenuItem(
                            text = stringResource(language.labelRes),
                            icon = OwnTVIcon.LANGUAGE,
                            checked = language in selected,
                            onClick = { onChange(toggleLanguage(selected, language)) },
                        )
                    }
                }
            }
        }
    }
}

/** Multi-select: from "all" the first pick narrows to that one; dropping the last one means all. */
private fun toggleLanguage(selected: Set<SportLanguage>, language: SportLanguage): Set<SportLanguage> = when {
    selected.isEmpty() -> setOf(language)
    language in selected -> selected - language
    else -> selected + language
}

/**
 * "Manage on phone": shows the companion QR + PIN while the pick list is served. Closes itself once
 * the selection was applied (the server stops a moment later) or on Back.
 */
@Composable
private fun SportsPhoneDialog(
    state: CompanionServerState,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    var sawListening by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        when (state) {
            is CompanionServerState.Listening -> sawListening = true
            CompanionServerState.Idle -> if (sawListening) onDismiss()
            else -> Unit
        }
    }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim)) {
            Column(
                Modifier
                    .padding(start = 560.mpx, top = 150.mpx, bottom = 24.mpx)
                    .width(720.mpx)
                    .trapAllFocusExit()
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(20.mpx),
            ) {
                Text(
                    stringResource(R.string.sport_manage_on_phone),
                    style = stageText(30, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                StageCompanionStatus(state)
            }
        }
    }
}

@Composable
private fun TeamScoreRow(team: SportTeam, state: FixtureState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.mpx), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.width(5.mpx).height(40.mpx).background(teamColor(team.colorHex) ?: Color.Transparent, RoundedCornerShape(3.mpx)))
        TeamLogo(team.logoUrl, 44.mpx)
        Text(team.name, style = stageText(22, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        team.score?.let { score ->
            Text(score, style = stageText(30, 800), color = if (state == FixtureState.LIVE) stageAccent.accent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FollowButton(leagueId: String, abbrev: String, followed: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val accent = stageAccent
    StageSurface(
        onClick = onToggle,
        radius = 14.mpx,
        focusStyle = StageFocus.FX,
        modifier = modifier,
        idle = if (followed) Modifier.background(accent.accent.copy(alpha = 0.14f), RoundedCornerShape(14.mpx)) else Modifier,
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.mpx, vertical = 9.mpx),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.mpx),
        ) {
            OwnTVIcon(OwnTVIcon.STAR, if (focused) accent.onAccent else if (followed) accent.accent else StageColors.Dim, Modifier.size(20.mpx))
            Column(Modifier.weight(1f)) {
                Text(abbrev, style = stageText(17, 700), color = if (focused) accent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (followed) R.string.sport_unfollow else R.string.sport_follow),
                    style = stageText(13, 500),
                    color = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val DayPattern = "EEE d.M."
private const val ColorPrefix = "FF"
private const val HashPrefix = "#"

/** Top-to-bottom order of the picker's region groups. */
private val RegionOrder = listOf(
    LeagueRegion.GERMANY,
    LeagueRegion.EUROPE,
    LeagueRegion.INTERNATIONAL,
    LeagueRegion.AMERICAS,
    LeagueRegion.REST,
)

private val LeagueRegion.labelRes: Int
    get() = when (this) {
        LeagueRegion.GERMANY -> R.string.sport_region_germany
        LeagueRegion.EUROPE -> R.string.sport_region_europe
        LeagueRegion.INTERNATIONAL -> R.string.sport_region_international
        LeagueRegion.AMERICAS -> R.string.sport_region_americas
        LeagueRegion.REST -> R.string.sport_region_rest
    }
