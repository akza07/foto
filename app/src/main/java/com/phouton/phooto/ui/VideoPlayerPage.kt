package com.phouton.phooto.ui

import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.state.rememberMuteButtonState
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickCount
import androidx.media3.ui.compose.state.rememberSeekBackButtonState
import androidx.media3.ui.compose.state.rememberSeekForwardButtonState
import androidx.media3.common.MediaItem as Media3MediaItem
import com.phouton.phooto.data.MediaItem
import com.phouton.phooto.ui.theme.AmoledBlack
import com.phouton.phooto.ui.theme.Surface as ThemeSurface
import com.phouton.phooto.ui.theme.TextMuted
import com.phouton.phooto.ui.theme.TextPrimary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// HUD colors tuned for contrast on the dark scrim (zinc-700/500 tracks vs white fill).
private val SeekTrackColor = Color(0xFF3F3F46)   // zinc-700 — visible but subtle track
private val SeekBufferedColor = Color(0xFF71717A) // zinc-500 — buffered segment
private val SeekFillColor = Color(0xFFFAFAFA)     // white — played segment
private val DeleteRed = Color(0xFFEF4444)        // matches the image drawer's Delete

/** Playback speeds offered by the speed chip, in menu order. */
private val PlaybackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Full-screen video playback page used inside [FullImageViewer].
 *
 * Layout mirrors a minimal mobile player HUD:
 *
 * ```
 *  ┌──────────────────────────────┐
 *  │ [back] 3/12           [⋮]    │  ← back + counter live in [FullImageViewer]
 *  │                              │
 *  │        (video surface)       │
 *  │   [tap = play/pause]         │
 *  │   [double-tap L/R = ∓10 s]   │
 *  │                              │
 *  ├──────────────────────────────┤  ← gradient scrim
 *  │  0:12 ●━━━━━━━━━━━━━━ 0:42   │
 *  │  [🔊]                 [1.0x] │
 *  └──────────────────────────────┘
 * ```
 *
 * - Owns an [ExoPlayer] for the current [MediaItem]; released when the page
 *   stops being active (one decoder at a time).
 * - Opens **paused** as a preview (poster until the first frame renders);
 *   single tap toggles play/pause, double-tap left/right seeks ±10 s.
 * - Bottom HUD: seeker flanked by timestamps, mute toggle, playback-speed chip
 *   (0.5×–2×). Top-right 3-dot menu: Share / Delete / Info.
 * - Controls auto-hide after 3 s while playing; paused keeps them visible.
 * - [onPlayingChanged] hoists play state so the host pager can lock swiping
 *   while the video plays (swiping navigates only while paused).
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerPage(
    item: MediaItem,
    active: Boolean,
    onControlsVisibilityChanged: (Boolean) -> Unit = {},
    onPlayingChanged: (Boolean) -> Unit = {},
    onShare: (MediaItem) -> Unit = {},
    onDelete: (MediaItem) -> Unit = {},
    onInfo: (MediaItem) -> Unit = {},
    entryProgress: () -> Float = { 1f },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val player = remember(active) {
        if (active) {
            ExoPlayer.Builder(context.applicationContext).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus= */ true,
                )
                setSeekBackIncrementMs(10_000L)
                setSeekForwardIncrementMs(10_000L)
            }
        } else {
            null
        }
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    // Pause on background; no auto-resume (gallery convention).
    LifecycleStartEffect(player) {
        onStopOrDispose {
            if (player != null) {
                player.playWhenReady = false
                player.pause()
            }
        }
    }

    // Load PAUSED: the poster/first frame acts as a preview until the user
    // taps play (user decision — no autoplay).
    LaunchedEffect(player) {
        if (player != null) {
            player.setMediaItem(Media3MediaItem.fromUri(item.uri))
            player.prepare()
            player.playWhenReady = false
        }
    }

    // Inactive (adjacent) page: poster only.
    if (player == null) {
        Box(modifier = modifier.fillMaxSize().background(AmoledBlack)) {
            VideoPosterThumb(
                item = item,
                modifier = Modifier.fillMaxSize(),
                // Match the video surface (ContentFrame uses Fit) so paging
                // between videos never changes poster geometry.
                contentScale = ContentScale.Fit,
            )
        }
        return
    }

    // ---- Poster veil: seamless poster → first-frame transition ----
    // The poster IS a real frame of this video, so instead of letting the
    // surface pop in (a hard swap the moment the first frame renders), an
    // identical poster overlay is drawn above the surface and crossfaded out
    // once the first frame is visible. The decoder's prepare() latency can't
    // be avoided, but the swap itself becomes invisible — both on open and
    // when swiping to the next video. coverSurface is media3's own
    // shutter-visibility flag: true until the first frame renders.
    val presentationState = rememberPresentationState(player, keepContentOnReset = true)
    // Keyed on the player too: re-activating a page builds a fresh player and a
    // fresh veil, so returning to a previously-viewed video never shows a stale
    // transparent veil over a not-yet-rendered surface.
    val posterVeilAlpha = remember(item.uri, player) { Animatable(1f) }

    LaunchedEffect(presentationState) {
        snapshotFlow { presentationState.coverSurface }.collect { covered ->
            if (!covered && posterVeilAlpha.value > 0f) {
                posterVeilAlpha.animateTo(0f, tween(FirstFrameFadeMs))
            }
        }
    }

    val playPauseState = rememberPlayPauseButtonState(player)
    val muteState = rememberMuteButtonState(player)
    val seekBackState = rememberSeekBackButtonState(player)
    val seekForwardState = rememberSeekForwardButtonState(player)
    val progressState = rememberProgressStateWithTickCount(player, totalTickCount = 480)

    var controlsVisible by remember { mutableStateOf(true) }
    val hideJob = remember { mutableStateOf<Job?>(null) }
    val controlsAlpha = remember { Animatable(1f) }
    var menuExpanded by remember { mutableStateOf(false) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }

    fun showControls() {
        controlsVisible = true
        scope.launch { controlsAlpha.snapTo(1f) }
        hideJob.value?.cancel()
        hideJob.value = scope.launch {
            delay(ControlsHideDelayMs)
            // An open menu pins the HUD; paused keeps it visible.
            if (!player.isPlaying || menuExpanded || speedMenuExpanded) return@launch
            controlsVisible = false
            controlsAlpha.animateTo(0f, tween(200))
        }
    }

    /** Hide the HUD now (after a menu action closes it mid-playback). */
    fun hideControlsSoon() {
        hideJob.value?.cancel()
        if (player.isPlaying) {
            hideJob.value = scope.launch {
                delay(ControlsHideDelayMs)
                if (!player.isPlaying || menuExpanded || speedMenuExpanded) return@launch
                controlsVisible = false
                controlsAlpha.animateTo(0f, tween(200))
            }
        }
    }

    // Report playback state so the host can lock pager swiping while playing,
    // and keep controls visible whenever the video is paused. Driven by a
    // snapshot flow (ExoPlayer.isPlaying is not Compose state, so a plain
    // LaunchedEffect(isPlaying) key would not observe changes reliably).
    LaunchedEffect(player) {
        snapshotFlow { player.isPlaying }.collect { playing ->
            onPlayingChanged(playing)
            if (!playing) {
                hideJob.value?.cancel()
                controlsVisible = true
                controlsAlpha.snapTo(1f)
            }
        }
    }
    LaunchedEffect(controlsVisible) {
        onControlsVisibilityChanged(controlsVisible)
        // Never leave a stale popup behind a hidden HUD.
        if (!controlsVisible) {
            menuExpanded = false
            speedMenuExpanded = false
        }
        if (controlsVisible) controlsAlpha.snapTo(1f) else controlsAlpha.animateTo(0f, tween(200))
    }
    LaunchedEffect(Unit) { showControls() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBlack),
    ) {
        // ---- Video surface (full-bleed) ----
        // The shutter is a plain black box: the real "poster → frame" veil is
        // the sibling layer above, which crossfades instead of hard-cutting.
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            contentScale = ContentScale.Fit,
            keepContentOnReset = true,
            shutter = {
                Box(Modifier.fillMaxSize().background(AmoledBlack))
            },
        )

        // ---- Poster veil (above the surface, below the HUD) ----
        // Identical poster, letterboxed exactly like the surface (Fit). Fades
        // out only once the first frame is actually rendered, so the static
        // image visually sharpens into the video instead of popping.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = posterVeilAlpha.value },
        ) {
            VideoPosterThumb(
                item = item,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        // ---- Entry-bridge crop (above the veil, below the HUD) ----
        // The grid tile shows this same poster CROPPED to fill its square,
        // while the player letterboxes it (Fit). Crossfade the cropped layer
        // out as the entry zoom completes so the tile's pixels carry seamlessly
        // into the player view — no pop at either end of the animation. It
        // deliberately lives OUTSIDE the shutter: the surface may render its
        // first frame mid-animation, and this bridge must keep fading on its
        // own schedule. Synchronous memory cache ⇒ no placeholder on re-open.
        val ep = entryProgress()
        if (ep < 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - ep },
            ) {
                VideoPosterThumb(
                    item = item,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        // ---- Overlay column: gesture area (above) + HUD (below) ----
        // The gesture layer lives in a weight(1f) box above the HUD so taps on
        // HUD buttons can never also trigger the full-screen single-tap. The
        // video surface remains full-bleed underneath.
        Column(modifier = Modifier.fillMaxSize()) {
            // Gesture area: single-tap play/pause, double-tap seek ±10 s.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                showControls()
                                if (offset.x < size.width / 2f) {
                                    seekBackState.onClick()
                                } else {
                                    seekForwardState.onClick()
                                }
                            },
                            onTap = {
                                showControls()
                                playPauseState.onClick()
                            },
                        )
                    },
            ) {
                // Center play/pause circle: part of the fading overlay (like the
                // mockup). While paused the HUD never hides, so the circle shows
                // the play glyph; while playing it flashes the pause glyph with
                // the controls, then fades to a clean frame.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = controlsAlpha.value },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier
                            .size(80.dp)
                            .shadow(12.dp, CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                            .clip(CircleShape),
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.4f),
                        shadowElevation = 0.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (playPauseState.showPlay) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = if (playPauseState.showPlay) "Play" else "Pause",
                                tint = Color.White,
                                modifier = Modifier.size(44.dp),
                            )
                        }
                    }
                }
            }

            // Bottom HUD: gradient scrim + time/seeker row + volume/speed row.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = controlsAlpha.value },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.45f),
                                1f to Color.Black.copy(alpha = 0.8f),
                            ),
                        ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp),
                    ) {
                        // Seeker flanked by current / total time.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = formatMs((progressState.currentPositionProgress * progressState.durationMs).toLong()),
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            VideoSeeker(
                                progress = progressState.currentPositionProgress,
                                buffered = progressState.bufferedPositionProgress,
                                seekEnabled = progressState.changingProgressEnabled,
                                onSeek = { progressState.updateCurrentPositionProgress(it) },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatMs(progressState.durationMs),
                                color = TextMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        // Volume (left) + playback-speed chip (right).
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        showControls()
                                        muteState.onClick()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (muteState.showMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                                    contentDescription = if (muteState.showMuted) "Unmute" else "Mute",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Box {
                                // "1.0x" chip — bordered pill like the mockup.
                                Text(
                                    text = formatSpeed(playbackSpeed),
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            showControls()
                                            speedMenuExpanded = true
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                                DropdownMenu(
                                    expanded = speedMenuExpanded,
                                    onDismissRequest = { speedMenuExpanded = false },
                                ) {
                                    PlaybackSpeeds.forEach { speed ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = formatSpeed(speed),
                                                    color = if (speed == playbackSpeed) TextPrimary else TextMuted,
                                                )
                                            },
                                            trailingIcon = {
                                                if (speed == playbackSpeed) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        tint = TextPrimary,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            },
                                            onClick = {
                                                playbackSpeed = speed
                                                player.setPlaybackSpeed(speed)
                                                speedMenuExpanded = false
                                                showControls()
                                                hideControlsSoon()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Top-right 3-dot menu: Share / Delete / Info (fades with the HUD) ----
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 8.dp, top = 8.dp)
                .graphicsLayer { alpha = controlsAlpha.value },
        ) {
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .clickable(
                        enabled = controlsVisible,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        showControls()
                        menuExpanded = true
                    },
                shape = CircleShape,
                color = ThemeSurface,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More options",
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Share", color = TextPrimary) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        hideControlsSoon()
                        onShare(item)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = DeleteRed) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = DeleteRed,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        hideControlsSoon()
                        onDelete(item)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Info", color = TextPrimary) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        hideControlsSoon()
                        onInfo(item)
                    },
                )
            }
        }

        // ---- Error state ----
        if (player.playbackState == Player.STATE_IDLE && player.playerError != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AmoledBlack.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeOff,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "This video can't be played",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "The file format or codec isn't supported on this device.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 260.dp),
                    )
                }
            }
        }
    }
}

/** Mockup-style scrubber: thin track, white fill, mid-tone buffered, thumb on scrub. */
@Composable
private fun VideoSeeker(
    progress: Float,
    buffered: Float,
    seekEnabled: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackHeight = 3.dp
    val activeTrackHeight = 5.dp
    val thumbSize = 12.dp
    val density = LocalDensity.current
    var seekerWidthPx by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }

    val animatedTrackHeight by animateDpAsState(
        targetValue = if (scrubbing) activeTrackHeight else trackHeight,
        animationSpec = tween(150),
        label = "seeker track height",
    )
    val thumbAlpha by animateFloatAsState(
        targetValue = if (scrubbing) 1f else 0f,
        animationSpec = tween(150),
        label = "seeker thumb alpha",
    )

    Box(
        modifier = modifier
            .height(28.dp) // touch target
            .onSizeChanged { seekerWidthPx = it.width.toFloat() }
            .pointerInput(seekEnabled) {
                if (seekEnabled) {
                    detectTapGestures { offset ->
                        if (seekerWidthPx > 0f) {
                            onSeek((offset.x / seekerWidthPx).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            .pointerInput(seekEnabled) {
                if (seekEnabled) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            scrubbing = true
                            if (seekerWidthPx > 0f) {
                                onSeek((offset.x / seekerWidthPx).coerceIn(0f, 1f))
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            if (seekerWidthPx > 0f) {
                                onSeek((change.position.x / seekerWidthPx).coerceIn(0f, 1f))
                            }
                        },
                        onDragEnd = { scrubbing = false },
                        onDragCancel = { scrubbing = false },
                    )
                }
            },
    ) {
        // Track (grows slightly while scrubbing).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedTrackHeight)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(8.dp))
                .background(SeekTrackColor),
        )
        // Buffered segment.
        Box(
            modifier = Modifier
                .fillMaxWidth(buffered.coerceIn(0f, 1f))
                .height(animatedTrackHeight)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(8.dp))
                .background(SeekBufferedColor),
        )
        // Played fill.
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(animatedTrackHeight)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(8.dp))
                .background(SeekFillColor),
        )
        // Thumb (visible only while scrubbing, like the mockup's hover handle).
        val thumbTravelPx = with(density) { (seekerWidthPx - thumbSize.toPx()).coerceAtLeast(0f) }
        val thumbOffset = with(density) { (progress.coerceIn(0f, 1f) * thumbTravelPx).toDp() }
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .align(Alignment.CenterStart)
                .size(thumbSize)
                .graphicsLayer { alpha = thumbAlpha }
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(SeekFillColor),
        )
    }
}

/** "1.0x" style label: whole numbers get a trailing ".0", others stay as-is. */
private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}.0x" else "${speed}x"

private fun formatMs(ms: Long): String {
    val total = if (ms > 0L) ms else 0L
    val seconds = total / 1000
    val minutes = seconds / 60
    val secs = seconds % 60
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, mins, secs)
    } else {
        "%d:%02d".format(mins, secs)
    }
}

private const val ControlsHideDelayMs = 3000L
// Crossfade for the poster→first-frame veil swap (subtle: the two are
// pixel-aligned, so this reads as sharpening rather than a transition).
private const val FirstFrameFadeMs = 150
