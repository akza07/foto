package fool.akza.foto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fool.akza.foto.ui.theme.AmoledBlack
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Height of every app bar, excluding the status-bar inset. */
val AppBarHeight = 56.dp

/**
 * Backdrop blur + tint shared by every frosted app bar. Kept in one place so the
 * album header, the root Albums header, Settings, Trash and Search all match.
 */
val FrostedHazeStyle = HazeStyle(
    blurRadius = 18.dp,
    tints = listOf(HazeTint(AmoledBlack.copy(alpha = 0.35f))),
)

/** Translucent gradient scrim drawn beneath the blurred app bar. */
val FrostedBarScrim = Brush.verticalGradient(
    listOf(AmoledBlack.copy(alpha = 0.6f), AmoledBlack.copy(alpha = 0.25f)),
)

/**
 * Top content padding a frosted screen's scroll list needs so its first item
 * clears the status bar and the app bar, plus [extra] for the list's own spacing.
 */
@Composable
fun frostedContentTopPadding(extra: Dp = 0.dp): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + AppBarHeight + extra

/**
 * App-bar scaffold that frosts [header] over scrolling content.
 *
 * [content] receives the modifier it must apply to its scrollable root so the bar
 * can sample it as a backdrop; callers reserve enough top content padding to clear
 * the bar so items scroll underneath instead of starting below it.
 *
 * [enabled] turns the (expensive) backdrop blur off while this screen is covered
 * by another, leaving just the scrim.
 *
 * [background] paints the whole screen behind both the content and the header.
 * Screens whose content is a transparent list (Settings, Folders, Trash) must
 * pass an opaque colour so the screen beneath them cannot show through;
 * content-backed screens like the Albums grid leave it null.
 */
@Composable
fun FrostedHeaderScaffold(
    enabled: Boolean = true,
    background: Color? = null,
    header: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val hazeState = rememberHazeState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (background != null) Modifier.background(background) else Modifier),
    ) {
        content(Modifier.fillMaxSize().hazeSource(state = hazeState))
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(brush = FrostedBarScrim)
                .then(
                    if (enabled) {
                        Modifier.hazeEffect(state = hazeState, style = FrostedHazeStyle)
                    } else {
                        Modifier
                    },
                ),
        ) {
            header()
        }
    }
}
