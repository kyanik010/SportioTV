package tv.own.owntv.ui.theme

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.dp

/** Shared spacing / sizing tokens for the 4-layer TV shell - NOW RESPONSIVE! */
object Dimens {

    // Responsive functions — values are in dp and are converted to Dp by callers.

    fun getSidebarWidth(windowSize: WindowWidthSizeClass): Int =
        when (windowSize) {
            WindowWidthSizeClass.Compact -> 0
            WindowWidthSizeClass.Medium -> 88
            WindowWidthSizeClass.Expanded -> 272
        }

    fun getRailWidth(windowSize: WindowWidthSizeClass): Int =
        when (windowSize) {
            WindowWidthSizeClass.Compact -> 0
            WindowWidthSizeClass.Medium -> 92
            WindowWidthSizeClass.Expanded -> 92
        }

    fun getRailWidthExpanded(windowSize: WindowWidthSizeClass): Int =
        when (windowSize) {
            WindowWidthSizeClass.Compact -> 280
            WindowWidthSizeClass.Medium -> 325
            WindowWidthSizeClass.Expanded -> 325
        }

    fun getChannelListWidth(windowSize: WindowWidthSizeClass): Int? =
        when (windowSize) {
            WindowWidthSizeClass.Compact -> null
            WindowWidthSizeClass.Medium -> null
            WindowWidthSizeClass.Expanded -> 460
        }

    // Existing fixed values retained for compatibility with current call sites.

    val ScreenPaddingH = 32.dp
    val ScreenPaddingV = 24.dp

    // Layer 1 — MD3 navigation panel
    val SidebarWidthExpanded = 272.dp
    val SidebarWidthCollapsed = 88.dp
    /** The strip while the audio capsule is focused/activated — the capsule's tall rendering plus its inset. */
    val TopBarAudioExpandedHeight = 70.dp

    // Layer 2 — category rail
    val RailWidth = 92.dp
    val RailWidthExpanded = 325.dp
    val RailWidthFixed = 272.dp
    val RailPillSize = 56.dp
    val ChannelListWidth = 460.dp

    // MD3 settings tonal icon tile
    val IconTileSize = 42.dp
    val IconTileCorner = 12.dp

    val GapTiny = 4.dp
    val GapSmall = 8.dp
    val GapMedium = 16.dp
    val GapLarge = 24.dp

    // M3 expressive shape scale
    val CornerSmall = 12.dp
    val CornerMedium = 18.dp
    val CornerLarge = 24.dp
    val CardCorner = 20.dp

    val FocusBorderWidth = 2.dp
    val HomeRowPaddingH = 20.dp

    // Hero carousel
    val HeroBaseWidth = 180.dp
    val HeroMetaHeight = 84.dp
    val HeroGap = 14.dp
    val HeroCardCorner = 18.dp
    val HeroPosterCorner = 14.dp
    val HeroMaxCardHeight = 354.dp
    val HeroMinCardHeight = 200.dp
    val HeroOverlayMaxWidth = 400.dp
    val HeroProgressHeight = 3.dp
}
