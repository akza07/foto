package fool.akza.foto.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fool.akza.foto.R
import fool.akza.foto.ui.theme.AmoledBlack
import fool.akza.foto.ui.theme.Border
import fool.akza.foto.ui.theme.DeleteRed
import fool.akza.foto.ui.theme.EmptySurface
import fool.akza.foto.ui.theme.Surface as ThemeSurface
import fool.akza.foto.ui.theme.TextMuted
import fool.akza.foto.ui.theme.TextPrimary
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private data class TutorialPage(
    @StringRes val title: Int,
    @StringRes val body: Int,
    val illustration: @Composable () -> Unit,
)

private val TutorialPages = listOf(
    TutorialPage(R.string.tutorial_welcome_title, R.string.tutorial_welcome_body) { WelcomeIllustration() },
    TutorialPage(R.string.tutorial_browse_title, R.string.tutorial_browse_body) { BrowseIllustration() },
    TutorialPage(R.string.tutorial_viewer_title, R.string.tutorial_viewer_body) { ViewerIllustration() },
    TutorialPage(R.string.tutorial_actions_title, R.string.tutorial_actions_body) { ActionsIllustration() },
    TutorialPage(R.string.tutorial_safety_title, R.string.tutorial_safety_body) { SafetyIllustration() },
)

/**
 * First-run walkthrough: a short pager of illustrated pages shown once after
 * permissions are granted (and replayable from Settings). Explanatory only).
 */
@Composable
internal fun TutorialScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { TutorialPages.size })
    val scope = rememberCoroutineScope()
    val onLastPage = pagerState.currentPage == TutorialPages.lastIndex

    BackHandler { onDone() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = stringResource(R.string.tutorial_skip),
                    color = TextMuted,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onDone)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                TutorialPageContent(TutorialPages[page])
            }

            // Progress dots.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(TutorialPages.size) { index ->
                    val active = index == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (active) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (active) TextPrimary else Border),
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        if (onLastPage) {
                            onDone()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    },
                shape = RoundedCornerShape(12.dp),
                color = TextPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            if (onLastPage) R.string.tutorial_start else R.string.tutorial_next,
                        ),
                        color = AmoledBlack,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TutorialPageContent(page: TutorialPage) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        page.illustration()
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(page.title),
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(page.body),
            color = TextMuted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

// ── Illustrations (Compose-drawn; no image assets) ─────────────────────────

@Composable
private fun MockCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(22.dp))
            .background(EmptySurface)
            .border(1.dp, Border, RoundedCornerShape(22.dp)),
    ) {
        content()
    }
}

@Composable
private fun MockTile(modifier: Modifier = Modifier, video: Boolean = false, checked: Boolean = false) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Border),
    ) {
        if (video) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(13.dp),
            )
        }
        if (checked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(TextPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = AmoledBlack,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

@Composable
private fun WelcomeIllustration() {
    MockCard {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            repeat(3) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    repeat(3) { col ->
                        MockTile(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            video = row == 0 && col == 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseIllustration() {
    MockCard {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .height(12.dp)
                        .width(96.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Border),
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            repeat(2) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    repeat(3) { col ->
                        MockTile(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            video = row == 1 && col == 2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerIllustration() {
    MockCard {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight(0.74f)
                    .fillMaxWidth(0.62f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Border),
            )
            // Double-tap zoom ring.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(52.dp)
                    .clip(CircleShape)
                    .border(2.dp, TextPrimary, CircleShape),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(TextPrimary),
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(14.dp)
                    .size(28.dp),
            )
        }
    }
}

@Composable
private fun ActionsIllustration() {
    MockCard {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(180.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val outer = size.minDimension / 2f
                val hub = outer * 0.30f
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = outer,
                    center = c,
                )
                drawCircle(color = Border, radius = outer, center = c, style = Stroke(2f))
                drawCircle(color = Border, radius = hub, center = c, style = Stroke(2f))
                for (i in 0 until 4) {
                    val a = Math.toRadians((-135f + i * 90f).toDouble())
                    drawLine(
                        color = Border,
                        start = Offset(c.x + (cos(a) * hub).toFloat(), c.y + (sin(a) * hub).toFloat()),
                        end = Offset(c.x + (cos(a) * outer).toFloat(), c.y + (sin(a) * outer).toFloat()),
                        strokeWidth = 2f,
                    )
                }
            }
            val actionIcons = listOf(
                Icons.Outlined.Delete to DeleteRed,
                Icons.Outlined.Info to TextPrimary,
                Icons.Outlined.Share to TextPrimary,
                Icons.Outlined.Crop to TextPrimary,
            )
            actionIcons.forEachIndexed { index, (icon, tint) ->
                val a = Math.toRadians((-90f + index * 90f).toDouble())
                val r = 54.dp
                Box(
                    modifier = Modifier
                        .offset(x = r * cos(a).toFloat(), y = r * sin(a).toFloat())
                        .size(26.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
            }
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(20.dp),
            )
            // Finger dot, mid-gesture.
            Box(
                modifier = Modifier
                    .offset(
                        x = 70.dp * cos(Math.toRadians(30.0)).toFloat(),
                        y = 70.dp * sin(Math.toRadians(30.0)).toFloat(),
                    )
                    .size(16.dp)
                    .clip(CircleShape)
                    .border(2.dp, TextPrimary, CircleShape),
            )
        }
    }
}

@Composable
private fun SafetyIllustration() {
    MockCard {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    MockTile(modifier = Modifier.weight(1f).fillMaxHeight(), checked = true)
                    MockTile(modifier = Modifier.weight(1f).fillMaxHeight(), checked = true)
                    MockTile(modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    MockTile(modifier = Modifier.weight(1f).fillMaxHeight())
                    MockTile(modifier = Modifier.weight(1f).fillMaxHeight(), checked = true)
                    MockTile(modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        Icons.Outlined.Delete,
                        Icons.Outlined.ContentCopy,
                        Icons.AutoMirrored.Outlined.DriveFileMove,
                        Icons.Outlined.Share,
                    ).forEach { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (icon == Icons.Outlined.Delete) DeleteRed else TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            // Undo pill.
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(50),
                color = TextPrimary,
            ) {
                Text(
                    text = stringResource(R.string.undo_moved_to_trash) +
                        "  ·  " + stringResource(R.string.action_undo),
                    color = AmoledBlack,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}
