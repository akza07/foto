# Foto — App Documentation

> An AMOLED-first, edge-to-edge photo gallery for Android, built with **Jetpack Compose**
> and Material 3, styled after **shadcn/ui** (dark zinc palette).

---

## 1. Overview

**Foto** is a local media gallery app that:

- Requests read access to images + videos on the device.
- Shows media organized as **albums (folders)** and loose files.
- Provides a full-image viewer with pinch-zoom, double-tap zoom, two-finger rotation,
  paging, and share/delete/info/crop actions.
- Includes a **Settings** screen with a "Content Discovery" engine that can restrict or
  exclude media by directory (allow-list / deny-list) and respect `.nomedia` markers.

**Design philosophy:** pitch-black AMOLED background (`#000000`), shadcn/zinc neutral
surfaces, subtle borders, rounded corners, smooth spring/tween transitions, and edge-to-edge
rendering where the app's black extends behind the system status/navigation bars.

---

## 2. Tech Stack & Versions

| Component | Version |
|---|---|
| Android Gradle Plugin (AGP) | 9.3.2 (built-in Kotlin) |
| Gradle | 9.5.0 |
| Kotlin (built-in via AGP) | 2.2.10 |
| Compose Compiler plugin | 2.2.10 |
| Compose BOM | 2026.06.00 |
| Material 3 | via BOM |
| Activity Compose | 1.13.0 |
| Lifecycle Runtime Compose | 2.11.0 |
| Coil 3 (`coil-compose`, `coil-video`) | 3.3.0 |
| Media3 (`media3-exoplayer`, `media3-ui-compose`) | 1.11.0 |
| Haze (backdrop blur) | 1.7.2 |
| `minSdk` / `targetSdk` / `compileSdk` | 29 / 37 / 37 |
| Application ID | `fool.akza.foto` |

> Note: The project uses AGP 9's **built-in Kotlin** — no `org.jetbrains.kotlin.android`
> plugin. The Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) is applied
> with a version matching the bundled Kotlin (2.2.10).

---

## 3. Architecture

```
app/src/main/java/fool/akza/foto/
├── MainActivity.kt            # Entry point: edge-to-edge, share/view intents, previews
├── AppLog.kt                  # Logcat tag for previously-silent failures
├── ui/
│   ├── PermissionGate.kt      # Runtime permission request screen
│   ├── GalleryScreen.kt       # Albums grid, folder (album) view, transitions
│   ├── SearchScreen.kt        # Name search with month-grouped results
│   ├── FullImageViewer.kt     # Pager-based full-screen image viewer
│   ├── VideoPlayerPage.kt     # ExoPlayer video playback page (shadcn controls)
│   ├── VideoPlaybackController.kt # Viewer-owned ExoPlayer + preload manager
│   ├── SettingsScreen.kt      # Settings + Content Discovery
│   ├── SettingsComponents.kt  # Shared settings rows / group card
│   ├── SelectionUi.kt         # Multi-select state + action bar/badge
│   ├── ConfirmDialog.kt       # Shared confirmation modal
│   ├── Feedback.kt            # Toast / undo bar / busy overlay
│   ├── Thumb.kt               # Thumbnail + skeleton-shimmer composables
│   ├── InfoSheet.kt           # Media info bottom sheet
│   └── theme/Theme.kt         # AMOLED shadcn palette + MaterialTheme
└── data/
    ├── MediaItem.kt           # MediaItem / MediaFolder models
    ├── MediaRepository.kt     # MediaStore + SAF tree browsing, Content Discovery
    ├── GalleryGrouping.kt     # Testable grouping/filter/sort logic
    ├── GalleryEvents.kt       # SharedFlow reload events
    ├── SettingsRepository.kt  # SharedPreferences-backed settings (StateFlow)
    └── thumbnails/            # Coil-backed thumbnail + video-poster caches
```

### Navigation model
Single-activity Compose app. Screens are rendered as **overlays in a root `Box`** in
`GalleryScreen`, with `BackHandler` priority:

**Settings → Image viewer → Folder (album) → Albums grid**

| Overlay | Trigger | Enter transition | Exit transition |
|---|---|---|---|
| Settings | gear icon (top-right of Albums header) | slide in from right + fade | slide out right + fade |
| Album grid | tap a folder stack | "deck unpack": tiles fly from the stack into grid cells | tiles collapse back into the stack |
| Image viewer | tap a grid tile | image scales from the tile rect to full screen (rounded-corner morph) | image shrinks back into the tile |

---

## 4. Design System

### 4.1 Colors (shadcn/zinc on AMOLED)

Defined in `ui/theme/Theme.kt`:

| Token | Value | Usage |
|---|---|---|
| `AmoledBlack` | `#000000` | Screen background, status/nav bar backdrop |
| `Surface` | `#09090B` | Cards, sheets, tiles, headers |
| `Border` | `#27272A` | Outlines, separators, switch tracks |
| `TextPrimary` | `#FAFAFA` | Primary text, icons |
| `TextMuted` | `#A1A1AA` | Secondary text, captions |
| `DeleteRed` | `#EF4444` | Destructive actions (delete confirm) |

The app is **dark-only** (`FotoTheme` always uses `darkColorScheme`; system theme is
ignored). The theme has **no action bar** and uses transparent status/nav bars with
edge-to-edge rendering.

### 4.2 Shape & spacing
- **Tile corners:** `RoundedCornerShape(12.dp)`
- **Cards / sheets:** `RoundedCornerShape(12–16.dp)`
- **Pills / toasts / FAB-style controls:** `CircleShape` (50% radius)
- **Grid:** `GridCells.Adaptive(minSize = 112.dp)`, `8.dp` spacing, `12.dp` content padding
- **Headers:** `56.dp` tall, status-bar padded

### 4.3 Motion
- Transitions use `tween` with `FastOutSlowInEasing` (~240–320ms) or `spring`
  (`DampingRatioNoBouncy`, `StiffnessMediumLow`) for zoom.
- Shared-phase animations: per-frame transforms run inside `graphicsLayer` (draw phase),
  so animation frames don't recompose — keeping 60fps during the deck-expansion.
- **Haze** (backdrop blur) is applied to the album sub-header only after the expansion
  settles (`phase >= 0.98`), and falls back to a translucent scrim during animation.

---

## 5. Features & Functionality

### 5.1 Permission gate
- On launch, checks `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` (Android 13+) or
  `READ_EXTERNAL_STORAGE` (Android 10–12, `maxSdkVersion=32`).
- Missing → shadcn-style card: gallery icon, title, description, **Grant access** button,
  and an **Open settings** fallback when permanently denied.
- Re-checks on every resume (revoking in Settings is caught).
- Once access is granted, a one-time **first-run tutorial** (5 illustrated pages) explains
  browsing, the viewer gestures, the long-press action wheel, and safe deletes. It can be
  replayed from **Settings → About → Replay tutorial**.

### 5.2 Albums grid (home)
- **Header:** "Albums" title (large, semi-bold, tight tracking) + **gear icon** on the right.
- **Folder tiles:** 3D image stack (front cover + two peeking back cards, darkening scrims).
- **Loose files:** flat rounded image tiles.
- **Responsive:** adaptive column count based on screen width.
- **Skeleton loaders:** shimmer placeholders while images decode (no empty gaps).

### 5.3 Folder / album view
- Tap a folder → **deck-unpack animation**: tiles fly from the stack into their grid cells.
- Frosted sub-header (back arrow + folder name + item count) with Haze backdrop blur.
- Back collapses tiles back into the stack.

### 5.4 Full-image viewer
- **Horizontal pager** for swiping between images (adjacent pages preloaded to avoid flicker).
- **Double-tap:** animated zoom 1×↔3× (spring), anchored at the tap point.
- **Pinch:** zoom (1–5×) + pan (clamped; panning disabled at 1×).
- **Two-finger rotation:** rotates the image in the gesture direction.
- **Long-press (images):** a **radial menu** appears at your finger — keep holding
  and slide to Delete / Info / Share / Crop, then release to run it (release in
  the centre to cancel). One gesture, no button to find.
- **Swipe up:** opens the bottom action drawer.
- **Swipe down (at 1×):** dismisses the viewer.
- **Zoomed panning:** 1:1 while dragging, then a low-friction **momentum glide**
  on release, clamped to the image bounds.
- **Top-left:** back button + counter pill (`3/12`).
- **Drawer actions:** Delete (red), Share, Info, Crop ("Coming soon" shadcn toast).
  - **Delete:** shadcn confirmation modal (red accent) → the file is copied into
    **Foto's own trash** (`filesDir/trash`, isolated from Android's system trash)
    and then its original is removed, so every delete shows an **Undo** bar and can
    be restored later from **Settings → Storage → Trash**.
  - **Share:** `ACTION_SEND` chooser with URI grant.
  - **Info:** sheet with name, size, and date.

### 5.4.1 Video playback
- Tapping a **video** tile opens the viewer with **real playback** via
  **AndroidX Media3 — ExoPlayer** (`media3-exoplayer` + Compose-first
  `media3-ui-compose`; `ContentFrame`, TextureView surface so the
  scale-from-tile transition and overlay controls compose cleanly).
- **One player for the whole viewer session.** `VideoPlaybackController` builds
  a single ExoPlayer when the viewer opens and releases it on close, instead of
  building one per settled page. A shared **`DefaultPreloadManager`** preloads
  videos within ±4 of the settled page (3 s for the current item, 1.5 s for the
  immediate neighbors, tracks/prepare for the rest), so a swipe usually lands on
  an already-buffered first frame.
- Opens **paused** as a preview: the cached poster shows until playback starts
  (no autoplay — user decision). Only the settled page attaches the player to a
  surface; adjacent video pages show their cached poster, and settling on an
  image stops the video decoders.
- **Unified frame-0 poster pipeline** (one bitmap per video, used everywhere):
  - `VideoPosterExtractor` grabs **frame 0** (`MediaMetadataRetriever.
    getScaledFrameAtTime`, 720 px long edge) — the exact frame ExoPlayer
    renders paused, so the grid tile, the viewer's poster veil, and the
    player's first frame are pixel-identical and every transition is a
    crossfade between matching pixels (no visible "switch").
  - Persisted to `VideoFrameCache` (`filesDir/frames/<sha1>/poster.jpg`); the
    AoT warm pass (`ThumbCache.enqueueOne`) extracts posters in the background
    with the existing progress pill, so tiles and the viewer read a warm disk
    cache.
  - Read chain (UI): in-memory LRU (`VideoPosterMemory`, sync hit → zero-delay
    paint) → `VideoFrameCache` → one-time extraction → MediaStore last resort.
    Swipe-to-next-video pre-warms neighbors into the LRU.
- **Gestures**:
  - **Single tap** toggles play/pause.
  - **Double-tap left/right** seeks ∓10 s.
  - **Swipe left/right navigates only while paused** — while playing, pager
    swiping is locked so a stray swipe can't skip content; pausing re-enables it.
- **Minimal bottom HUD** (auto-hides after 3 s while playing; paused keeps it
  visible), matching the app's shadcn/zinc styling:
  - **Seeker** row flanked by current / total time (tap/drag to scrub; thin
    white-on-zinc track with buffered segment, thumb appears while scrubbing).
  - **Volume** mute toggle (left) and a **playback-speed chip** ("1.0x", right)
    with a 0.5×–2× dropdown.
- **Top-right 3-dot menu**: Share, Delete (red), Info — reusing the viewer's
  existing share/delete/info flows. Back button + counter pill remain top-left.
- Only the settled page attaches the shared player to a surface (adjacent pages
  show posters); playback pauses when the app backgrounds.
- **Loop videos** (Settings → Behavior): when on, playback repeats the current
  video from the start instead of stopping at the end (`Player.REPEAT_MODE_ONE`).
  Off by default.
- **Video pages disable the image-only gestures** (user decision): no
  swipe-down dismiss, no swipe-up drawer, no pinch/double-tap zoom. Exit via the
  back button, or swipe to an adjacent page while paused.
- Unsupported codecs → clean shadcn error state (no crash).

### 5.4.2 Multi-select
- Long-press a tile or folder to enter selection mode; taps toggle selection,
  the header offers **Select all**, and a floating action bar provides
  **Delete / Copy / Move / Share** across the selection (folders expand to their
  contents). Copy/Move use the system directory picker (SAF).

### 5.5 Settings
- **Gear icon** → Settings screen (slide transition). Grouped into four cards:
  - **Display** — *Prefer 120Hz refresh rate*: asks the system to run up to 120Hz
    on supported devices. Applied live (window attribute + Compose frame-rate vote),
    no restart needed.
  - **Playback** — *Loop videos*: restart a video when it ends (`Player.REPEAT_MODE_ONE`).
  - **Library**:
    - **Respect .nomedia** (toggle): hides media whose directory (or ancestor) has a
      `.nomedia` marker. This applies to directories you explicitly add too, so a
      `.nomedia` folder inside a granted tree is hidden while the toggle is on and
      shown when it is off. In the default view Android's media scanner already
      excludes `.nomedia` content, so there the toggle only affects the allow-list browse.
    - **Only show selected folders** (toggle): switches between allow-list and deny-list.
    - **Manage folders** → a **Folders** sub-screen (slide transition, its own back
      handling) with the directory list and **Add directory**.
  - **Storage** — *Clear thumbnail cache* (shows the current size, confirms before
    clearing) and *Trash* (shows the count/size and opens a Trash screen with
    per-item restore and empty).
- **Toggle rows** are tappable across their whole width, not just on the switch.
- Only **Library** changes reload the media list; Display/Playback toggles apply
  through `SettingsRepository`'s `StateFlow` without re-querying MediaStore.
- **Directory list behavior** (behavior depends on the allow/deny flag):
  - **ON (allow-list):** only media inside selected directories is shown; each selected
    directory (and subdirectory with media) appears as an album. Empty list → nothing shown.
  - **OFF (deny-list):** all media is shown except the selected directories.
- **SAF directory browsing:** in allow-list mode, each granted directory is enumerated
  directly through the Storage Access Framework (recursive tree browse), so content
  inside `.nomedia` folders that Android's media scanner never indexes is reachable.
  This is subject to **Respect .nomedia**: while that toggle is on, `.nomedia`
  directories (and everything under them) are hidden from the browse.
  MediaStore items under the same directories are merged in and deduplicated by path.
- Directory picker uses the system **Storage Access Framework** (`OpenDocumentTree`) with
  `takePersistableUriPermission` (read + write) so access survives restarts and deletes
  keep working.
- Settings persist in SharedPreferences (`SettingsRepository`, exposed via `StateFlow`).
- **Sort order** (Display): newest/oldest first or name A–Z / Z–A, applied to both
  folders and items.
- **Grid size** (Display): Small / Medium / Large thumbnail density.
- **About**: shows the installed version and a **Replay tutorial** action.

### 5.6 Search & sharing
- **Search** (magnifier in the Albums header): inline name search over the full
  loaded library (MediaStore + SAF-browsed, including `.nomedia` content).
  Results are grouped by month/year and open in the viewer.
- **Open from other apps**: Foto handles `ACTION_VIEW` and `ACTION_SEND` for
  `image/*` and `video/*`, opening the handed-over file in the viewer.
- **Non-destructive delete**: every delete is undoable — the file is copied into
  Foto's **own trash** first (isolated from Android's system trash), then removed.
  The Trash screen (Settings → Storage → Trash) lists what Foto deleted, with
  restore and empty.

---

## 6. Build Commands

### Prerequisites
- JDK 17+, Android SDK (with platform 37), `ANDROID_HOME` set.
- Release signing: `keystore.properties` at the repo root (git-ignored) with:
  ```properties
  storeFile=keystore/foto-release.jks
  storePassword=…
  keyAlias=foto
  keyPassword=…
  ```
  If the file is missing, release builds fall back to the debug keystore.

### Debug build (installable, signed with debug key)
```bash
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Optimized release build (R8 minify + shrink + optimize, release-signed)
```bash
./gradlew :app:assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### Install to a device
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Clean build
```bash
./gradlew clean
```

### Tests / lint
```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:lintDebug           # lint
```

---

## 7. Release Output

The optimized release APK is copied to:

```
apk-output/foto-arm64-android16-release.apk
```

- **Size:** ~3.4 MB (R8-optimized from a ~70 MB debug build).
- **Targets Android 16** (targetSdk 37), minSdk 29.
- **ABIs:** arm64-v8a (`ndk.abiFilters` — add more in `app/build.gradle.kts` to ship others).
- **Signed:** V2-signed with `keystore/foto-release.jks` (certificate DN still `CN=Phooto`; the key pair predates the rename and is retained so the signing identity stays stable).

> ⚠️ Keep `keystore/foto-release.jks` and its passwords safe. You must use the **same
> key** for every future release to update the app on users' devices.

---

## 8. Known Notes / Out of Scope

- **App backup is disabled** (`android:allowBackup="false"`), so an uninstall removes
  every trace of the app (thumbnails, video posters, trash, settings) and a
  reinstall starts clean. Internal caches already live in `filesDir`, which the
  system deletes on uninstall; disabling backup is what prevents Android from
  restoring them on the next install.
- UI strings live in `res/values/strings.xml` (with `plurals` for counts); only the
  default locale is shipped so far.
- Crop is a placeholder ("Coming soon" toast).
- `.nomedia` content is reachable only inside directories the user explicitly adds.
  **Respect .nomedia** controls whether those markers are honored there (on by default):
  with it off, `.nomedia` content inside granted directories is shown. Content outside
  granted directories stays hidden because Android's media scanner never indexes it —
  showing it device-wide would require `MANAGE_EXTERNAL_STORAGE` ("All files access"),
  which Foto intentionally does not request.
- Selected-directory matching assumes the primary external volume (`primary:` tree URIs).
- No network features, no cloud backup, no EXIF editing.
