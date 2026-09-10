package com.phouton.phooto.ui

import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import com.phouton.phooto.ui.theme.TextMuted
import com.phouton.phooto.ui.theme.TextPrimary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// HUD colors tuned for contrast on the floating pill (zinc-700/500 tracks vs white fill).
private val SeekTrackColor = Color(0xFF3F3F46)   // zinc-700 — visible but subtle track
private val SeekBufferedColor = Color(0xFF71717A) // zinc-500 — buffered segment
private val SeekFillColor = Color(0xFFFAFAFA)     // white — played segment
private val DeleteRed = Color(0xFFEF4444)

/** Playback speeds offered by the speed chip, in menu order. */
private val PlaybackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Full-screen video playback page used inside [FullImageViewer].
 *
 * Minimal modern HUD:
 * - Single tap toggles play/pause, double-tap left/right seeks ±10 s,
 *   long-press opens the action sheet (same as images).
 * - Swipe up/down gestures are handled by the host viewer (sheet / dismiss),
 *   so this page owns no vertical-drag detector and the bottom pill never
 *   triggers navigation.
 * - Bottom: floating pill with play, times, seeker, mute, speed (0.5×–2×), and delete.
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
    onLongPress: () -> Unit = {},
    onDelete: () -> Unit = {},
    entryProgress: () -> Float = { 1f },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val player = remember(active, item.uri) {
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
    // taps play (user decision — no autoplay). Keyed on uri so a reused
    // page slot (after a delete shifts items) loads the new file.
    LaunchedEffect(player, item.uri) {
        if (player != null) {
            player.setMediaItem(Media3MediaItem.fromUri(item.uri))
            player.prepare()
            player.playWhenReady = false
        }
    }

    // Inactive (adjacent) page: poster only.
    if (player == null) {
        Box(modifier = modifier.fillMaxSize()) {
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
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }

    fun showControls() {
        controlsVisible = true
        scope.launch { controlsAlpha.snapTo(1f) }
        hideJob.value?.cancel()
        hideJob.value = scope.launch {
            delay(ControlsHideDelayMs)
            // An open speed menu pins the HUD; paused keeps it visible.
            if (!player.isPlaying || speedMenuExpanded) return@launch
            controlsVisible = false
            controlsAlpha.animateTo(0f, tween(200))
        }
    }

    fun hideControlsNow() {
        hideJob.value?.cancel()
        controlsVisible = false
        scope.launch { controlsAlpha.animateTo(0f, tween(200)) }
    }

    /** Hide the HUD now (after a menu action closes it mid-playback). */
    fun hideControlsSoon() {
        hideJob.value?.cancel()
        if (player.isPlaying) {
            hideJob.value = scope.launch {
                delay(ControlsHideDelayMs)
                if (!player.isPlaying || speedMenuExpanded) return@launch
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
            speedMenuExpanded = false
        }
        if (controlsVisible) controlsAlpha.snapTo(1f) else controlsAlpha.animateTo(0f, tween(200))
    }
    LaunchedEffect(Unit) { showControls() }

    Box(
        modifier = modifier
            .fillMaxSize(),
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

        // ---- Overlay column: gesture area (above) + floating pill (below) ----
        // The gesture layer lives in a weight(1f) box above the pill so taps on
        // pill buttons can never also trigger the full-screen single-tap. The
        // video surface remains full-bleed underneath. Vertical swipes are
        // handled by the host viewer (up = actions sheet, down = dismiss).
        Column(modifier = Modifier.fillMaxSize()) {
            // Gesture area: tap play/pause, double-tap seek ±10 s, long-press sheet.
            val haptics = LocalHapticFeedback.current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                showControls()
                                onLongPress()
                            },
                            onDoubleTap = { offset ->
                                showControls()
                                if (offset.x < size.width / 2f) {
                                    seekBackState.onClick()
                                } else {
                                    seekForwardState.onClick()
                                }
                            },
                            onTap = {
                                if (controlsVisible) {
                                    hideControlsNow()
                                } else {
                                    showControls()
                                }
                            },
                        )
                    },
            ) {
                // Minimal center button, fades with the HUD.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = controlsAlpha.value },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .clickable(
                                enabled = controlsVisible,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                showControls()
                                playPauseState.onClick()
                            },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        shadowElevation = 0.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (playPauseState.showPlay) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = if (playPauseState.showPlay) "Play" else "Pause",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }
            }

            // Bottom floating pill: single-row minimal controls.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = controlsAlpha.value }
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.68f),
                    shadowElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        showControls()
                                        playPauseState.onClick()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (playPauseState.showPlay) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    contentDescription = if (playPauseState.showPlay) "Play" else "Pause",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                            Text(
                                text = formatMs((progressState.currentPositionProgress * progressState.durationMs).toLong()),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            VideoSeeker(
                                progress = progressState.currentPositionProgress,
                                buffered = progressState.bufferedPositionProgress,
                                seekEnabled = progressState.changingProgressEnabled,
                                onSeek = {
                                    showControls()
                                    progressState.updateCurrentPositionProgress(it)
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatMs(progressState.durationMs),
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
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
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Box {
                                Text(
                                    text = formatSpeed(playbackSpeed),
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            showControls()
                                            speedMenuExpanded = true
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
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
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        showControls()
                                        onDelete()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Delete",
                                    tint = DeleteRed,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
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

/** Minimal scrubber: thin track, white fill, mid-tone buffered, always-visible thumb. */
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
    val thumbSize = 8.dp
    val density = LocalDensity.current
    var seekerWidthPx by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }

    val animatedTrackHeight by animateDpAsState(
        targetValue = if (scrubbing) activeTrackHeight else trackHeight,
        animationSpec = tween(150),
        label = "seeker track height",
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
        // Thumb (always visible, minimal).
        val thumbTravelPx = with(density) { (seekerWidthPx - thumbSize.toPx()).coerceAtLeast(0f) }
        val thumbOffset = with(density) { (progress.coerceIn(0f, 1f) * thumbTravelPx).toDp() }
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .align(Alignment.CenterStart)
                .size(thumbSize)
                .clip(CircleShape)
                .background(SeekFillColor),
        )
    }
}

/** "1x" style label: whole numbers have no decimal, others stay as-is. */
private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

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
