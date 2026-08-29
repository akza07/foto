# Phooto — App Documentation

> An AMOLED-first, edge-to-edge photo gallery for Android, built with **Jetpack Compose**
> and Material 3, styled after **shadcn/ui** (dark zinc palette).

---

## 1. Overview

**Phooto** is a local media gallery app that:

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
| Haze (backdrop blur) | 1.7.2 |
| `minSdk` / `targetSdk` / `compileSdk` | 29 / 37 / 37 |
| Application ID | `com.phouton.phooto` |

> Note: The project uses AGP 9's **built-in Kotlin** — no `org.jetbrains.kotlin.android`
> plugin. The Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) is applied
> with a version matching the bundled Kotlin (2.2.10).

---

## 3. Architecture

```
app/src/main/java/com/phouton/phooto/
├── MainActivity.kt            # Entry point: edge-to-edge, Compose previews
├── ui/
│   ├── PermissionGate.kt      # Runtime permission request screen
│   ├── GalleryScreen.kt       # Albums grid, folder (album) view, transitions
│   ├── FullImageViewer.kt     # Pager-based full-screen image viewer
│   ├── SettingsScreen.kt      # Settings + Content Discovery
│   └── theme/Theme.kt         # AMOLED shadcn palette + MaterialTheme
└── data/
    ├── MediaItem.kt           # MediaItem / MediaFolder models
    ├── MediaRepository.kt     # MediaStore + SAF tree browsing, Content Discovery
    └── SettingsRepository.kt  # SharedPreferences-backed settings (StateFlow)
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

The app is **dark-only** (`PhootoTheme` always uses `darkColorScheme`; system theme is
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
- **Long-press / swipe up:** opens the bottom action drawer.
- **Swipe down (at 1×):** dismisses the viewer.
- **Top-left:** back button + counter pill (`3/12`).
- **Drawer actions:** Delete (red), Share, Info, Crop ("Coming soon" shadcn toast).
  - **Delete:** shadcn confirmation modal (red accent) → direct delete with
    `MediaStore.createDeleteRequest` fallback.
  - **Share:** `ACTION_SEND` chooser with URI grant.
  - **Info:** sheet with name, size, and date.

### 5.5 Settings — Content Discovery
- **Gear icon** → Settings screen (slide transition).
- **Respect .nomedia** (toggle): hides MediaStore-indexed media whose directory (or
  ancestor) has a `.nomedia` marker. Folders you explicitly add are always shown.
- **Require explicit permission** (toggle): switches between allow-list and deny-list.
- **Directory list** (always available; behavior depends on the flag):
  - **ON (allow-list):** only media inside selected directories is shown; each selected
    directory (and subdirectory with media) appears as an album. Empty list → nothing shown.
  - **OFF (deny-list):** all media is shown except the selected directories.
- **SAF directory browsing:** in allow-list mode, each granted directory is enumerated
  directly through the Storage Access Framework (recursive tree browse), so content
  inside `.nomedia` folders that Android's media scanner never indexes still appears.
  MediaStore items under the same directories are merged in and deduplicated by path.
- Directory picker uses the system **Storage Access Framework** (`OpenDocumentTree`) with
  `takePersistableUriPermission` (read + write) so access survives restarts and deletes
  keep working.
- Settings persist in SharedPreferences (`SettingsRepository`, exposed via `StateFlow`).

---

## 6. Build Commands

### Prerequisites
- JDK 17+, Android SDK (with platform 37), `ANDROID_HOME` set.
- Release signing: `keystore.properties` at the repo root (git-ignored) with:
  ```properties
  storeFile=keystore/phooto-release.jks
  storePassword=…
  keyAlias=phooto
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
apk-output/phooto-arm64-android16-release.apk
```

- **Size:** ~2.4 MB (R8-optimized from a ~70 MB debug build).
- **Targets Android 16** (targetSdk 37), minSdk 29.
- **ABIs:** arm64-v8a (plus armeabi-v7a / x86 / x86_64).
- **Signed:** V2-signed with `keystore/phooto-release.jks` (CN=Phooto).

> ⚠️ Keep `keystore/phooto-release.jks` and its passwords safe. You must use the **same
> key** for every future release to update the app on users' devices.

---

## 8. Known Notes / Out of Scope

- Crop is a placeholder ("Coming soon" toast).
- `.nomedia` content is shown only inside directories the user explicitly adds. Content
  outside granted directories stays hidden because Android's media scanner never indexes
  it — showing it device-wide would require `MANAGE_EXTERNAL_STORAGE` ("All files access"),
  which Phooto intentionally does not request.
- Selected-directory matching assumes the primary external volume (`primary:` tree URIs).
- No network features, no cloud backup, no EXIF editing.
