# Foto — DESIGN.md

> Exhaustive visual + behavioral specification for the current Foto Android gallery app.
> Purpose: allow pixel- and behavior-faithful reproduction later without reading code.
> No code. Only appearance, layout, content, functionality, motion, and states.
> App identity: application ID `fool.akza.foto`, launcher/store name `Foto`.

---

## 1. Product Concept & Navigation Model

**What it is:** a local-only, AMOLED-first photo + video gallery for Android. No network, no cloud, no EXIF editing. Single-activity app; all screens are layered overlays in one root `Box`, not separate navigation destinations.

**Z-order / back priority (top wins):**

1. First-run tutorial overlay (if unseen)
2. Search screen
3. Active selection mode (clears selection first)
4. Settings root → Folders sub-screen → Trash sub-screen (innermost wins)
5. Full image/video viewer
6. Open album (folder) view
7. Albums grid (home, bottom)

**Back behavior summary:**

- System back in tutorial dismisses tutorial.
- System back in search closes search.
- System back with selection active exits selection.
- System back in Folders returns to Settings root; in Trash returns to Settings root; in Settings root returns to underlying gallery/album/viewer.
- System back in album view plays reverse deck-collapse animation then shows Albums grid.
- System back in viewer plays shrink-back-to-tile animation then shows grid/album.
- No back-stack history beyond one level per overlay.

**External entry:** `ACTION_VIEW` (`intent.data`) and `ACTION_SEND` (`EXTRA_STREAM`) for `image/*` and `video/*` open directly in the viewer with no tile-morph (fade only), over whatever gallery state exists.

**Rotation:** allowed (no orientation lock). Layout adapts via adaptive grid; viewer re-measures and re-clamps zoom offsets. No activity recreation on rotate/font-scale/locale/density/uiMode changes.

---

## 2. Design Principles

- **AMOLED-first dark-only:** every screen background is pure black `#000000`. System theme (light/dark) is ignored. Status bar and navigation bar are transparent with the app black extending behind them, so there is no visible seam.
- **shadcn / zinc neutrality:** surfaces are near-black zinc, borders are subtle zinc, text is near-white or muted gray. Destructive is a single red.
- **Rounded + bordered:** tiles 12dp corners, cards/sheets 12–16dp, pills/circles fully round. Almost every card/tile has a 1dp `#27272A` outline.
- **Frosted chrome:** top app bars are translucent gradient scrims plus 18dp backdrop blur. Content scrolls underneath.
- **Calm motion:** 180–320ms tweens with `FastOutSlowInEasing`, spring blooms for radial/zoom, per-frame transforms in the draw phase (60fps, no jank).
- **Skeleton-first loading:** never show empty gaps; show shimmer placeholders while decoding.
- **Safe deletes:** every delete goes to Foto's own private trash first and offers Undo.

---

## 3. Theming, Palette, Typography, Shape, Edge-to-Edge

### 3.1 Color palette (exact hex)

| Token | Hex | Used for |
|---|---|---|
| `AmoledBlack` | `#000000` | Every screen background; behind status/nav bars; radial backing; drawer icon wells |
| `Surface` | `#09090B` | Cards, sheets, tiles, headers, pills (counter, sync, toast base is inverted — see §4), switch off-thumb contrast, viewer chrome |
| `EmptySurface` | `#18181B` | Lifted placeholder: tiles/viewer pages with no preview, tutorial mock cards; never confused with pure black |
| `Border` | `#27272A` | All 1dp outlines, separators/dividers, switch tracks/borders, seeker track, sync pill border, tutorial dots (inactive) |
| `TextPrimary` | `#FAFAFA` | Primary text, primary icons, light button fills, active tutorial dot, seeker fill/thumb, spinner |
| `TextMuted` | `#A1A1AA` | Secondary text, captions, placeholders, inactive icons, buffered seeker segment, broken-image icon |
| `DeleteRed` | `#EF4444` | Delete icons/labels, destructive confirm fill, delete hover in radial menu |
| White (system) | `#FFFFFF` | Destructive button label, video badge play arrow, radial selected icon/label, seeker on dark pill |
| Black scrim variants | Black 25–85% alpha | See each component; e.g. busy 60%, dialog scrim 70%, drawer scrim 55%, info scrim 60%, video error 85%, badge 60%, unselected badge 50%, radial disc 42→66% |

Material3 dark scheme mapping: primary `#FAFAFA`, onPrimary `#09090B`, secondary `#A1A1AA`, onSecondary `#FAFAFA`, background `#000000`, onBackground `#FAFAFA`, surface/surfaceVariant `#09090B`, onSurface `#FAFAFA`, onSurfaceVariant `#A1A1AA`, outline/outlineVariant `#27272A`.

### 3.2 Typography

No custom font family; system default with Material3 defaults. Exact usages:

- Albums title: 28sp SemiBold, tracking −0.5sp, `#FAFAFA`.
- Settings/Trash/Folders header title: 22sp SemiBold `#FAFAFA`.
- Section label: 12sp SemiBold `#A1A1AA`, tracking 0.8sp, already title-case text.
- Card title: 15sp Medium `#FAFAFA`; card subtitle: 13sp `#A1A1AA`, line-height 18sp, 2dp gap below title.
- Tile label: 13sp Medium `#FAFAFA`, 1 line ellipsis; tile sub-label: 11sp `#A1A1AA`, 1 line.
- Album sub-header title: 16sp SemiBold `#FAFAFA`; sub: 12sp `#A1A1AA`.
- Selection count: 16sp SemiBold `#FAFAFA`; action labels: 11sp Medium `#A1A1AA`.
- Viewer counter: 13sp Medium `#FAFAFA`.
- Drawer item label: 12sp Medium `#FAFAFA`.
- Radial label: 11sp Medium `#FAFAFA` (selected white), shadow black 95% blur 7; hint pill: 13sp Medium.
- Dialog title: 18sp SemiBold `#FAFAFA`; message: 14sp `#A1A1AA`, line-height 20sp; buttons: 14sp Medium/SemiBold.
- Toast/Undo: 14sp Medium black on light pill; Undo action 14sp Bold.
- Sync pill: 13sp Medium `#FAFAFA` + 12sp `#A1A1AA` count.
- Search input/placeholder: 16sp (`#FAFAFA` / `#A1A1AA`); group header: 13sp SemiBold `#A1A1AA`; scope/hint/empty: 13–14sp `#A1A1AA` centered.
- Info sheet title: 18sp SemiBold; rows: 14sp (`#A1A1AA` label left, `#FAFAFA` value right).
- Video HUD times: 12sp Medium (`#FAFAFA` current, `#A1A1AA` total); speed chip: 12sp SemiBold `#A1A1AA`; error title 14sp Medium, message 12sp muted.
- Trash row name: 14sp Medium `#FAFAFA`; size/summary: 12sp `#A1A1AA`.
- Tutorial title: 24sp SemiBold centered; body: 14sp line-height 21sp muted centered; Skip: 15sp Medium muted; CTA: 15sp SemiBold black on light.

### 3.3 Shape & spacing

- Tiles, empty previews: `RoundedCornerShape(12dp)`.
- Cards, sheets: 12dp (settings group, trash row), 16dp (dialogs, option dialogs, info sheet, bottom drawer top 16dp only).
- Pills/toasts/counter/sync/hint/speed-chip container: `CircleShape` / `RoundedCornerShape(50)`.
- Drawer handle: 40×4dp, 2dp corners, `#A1A1AA` at 40%.
- Buttons in dialogs: 10dp corners, 44dp tall; CTA tutorial: 12dp corners, 50dp tall; drawer icon wells: 14dp corners, 54dp box.
- Grid: adaptive columns (`Small` min 88dp / `Medium` 112dp default / `Large` 144dp), 8dp horizontal+vertical spacing, 12dp side padding, top = status-bar + 56dp bar + 12dp, bottom = 12dp + nav-bar (or 96dp action bar + 12dp + nav-bar in selection).
- Headers: 56dp tall + status-bar padding; header horizontal padding 20dp (Albums), 4dp (album/search/settings/folders/trash inner row) + 8dp start inset for viewer top bar.

### 3.4 Edge-to-edge & system bars

- `windowBackground` black; status/nav bars transparent; light icons (not dark); content draws behind both.
- Every screen insets via status-bar top padding and navigation-bar bottom padding. Frosted bars sit in the status zone; grids/lists add top padding to clear them and scroll underneath.
- Optional 120Hz: when “Prefer 120Hz” is on and hardware supports >60.5Hz, window prefers 120Hz and root votes 120fps via frame-rate hint; applied live, no restart.

### 3.5 Launcher icon

- Adaptive icon (`mipmap-anydpi`) + monochrome variant; raster mipmaps per density; source SVGs in `artwork/` (`foto-launcher-colorful.svg`, `foto-launcher-monochrome.svg`; older zinc sources retained as `foto-launcher.svg`).
- Full-color icon (512 viewBox): dark iridescent squircle (`#1E1B4B`→`#090A15` with aurora glow + gradient edge stroke) holding a 3D photo-deck — rose and violet tilted back cards behind a white polaroid hero card with a sunset/mountain photo viewport and a spectral lens-dot accent.
- Monochrome (themed) icon (108 viewBox, all-white): same composition simplified — peeking back card, polaroid frame with photo-window cutout, sun dot, mountain polygon, lens-dot hole on the chin.

---

## 4. Global Components

### 4.1 Frosted app bar (shared scaffold)

- Height 56dp + status-bar inset. Full-width, top-aligned.
- Two layers: vertical gradient scrim black 60% → 25% (top opaque → transparent downward) + backdrop blur 18dp with black 35% tint.
- When covered by another overlay, blur is disabled (scrim only) to save GPU; re-enabled when settled (album header waits until deck phase ≥0.98).
- Content helper: top padding = status-bar + 56dp + optional extra.

### 4.2 Toast (shadcn toast)

- Bottom-center, 96dp above bottom, 8dp extra bottom inset.
- Light pill: `#FAFAFA` fill, full-round (50), 8dp shadow, 20dp horizontal / 10dp vertical text padding.
- Text: 14sp Medium black.
- Motion: fade + slide up half-height on enter; fade + slide down on exit. Auto-dismiss 2s. Polite live-region for accessibility.

### 4.3 Undo bar (“Moved to trash”)

- Identical container/motion/pill to toast, but 6s visible.
- Row: “Moved to trash” 14sp Medium black (20dp start) + 4dp gap + “Undo” 14sp Bold black in 50dp-clipped clickable with 12dp horizontal / 10dp vertical padding + 8dp trailing spacer.
- Tapping Undo restores; timeout dismisses.

### 4.4 Busy overlay (“Copying/Moving…”)

- Full-screen black 60% scrim, centered card: `#09090B`, 16dp corners, 8dp shadow, 20dp horizontal / 16dp vertical padding.
- Row: 20dp spinner (2dp stroke, `#FAFAFA`) + 12dp gap + label 14sp Medium `#FAFAFA`.
- Blocks input while copy/move runs.

### 4.5 Confirmation dialog (shared)

- Full-screen black 70% scrim; tap outside dismisses (no ripple).
- Card: full-width with 32dp side margins, 16dp corners, `#09090B`, no shadow. Inner 24dp.
- Title 18sp SemiBold, 8dp gap, message 14sp muted 20sp line-height, 24dp gap, two equal buttons 44dp tall, 10dp corners, 12dp gap.
- Cancel (left): transparent fill, `#FAFAFA` 14sp Medium “Cancel”.
- Confirm (right): red `#EF4444` + white 14sp SemiBold for destructive; else light `#FAFAFA` + black for neutral.
- Enter: scrim fades 180ms; card scales 0.92→1 over 240ms `FastOutSlowIn` + fades 180ms. No exit animation.

### 4.6 Option dialog (sort/grid)

- Same scrim/card geometry as confirm (70% scrim, 32dp margins, 16dp corners, 20dp inner, 18sp title + 8dp gap).
- Each option: full-width 8dp corners, 8dp horizontal / 12dp vertical padding. Label 15sp (current: `#FAFAFA` SemiBold; others: muted Normal) + current shows 18dp Check `#FAFAFA` (“Selected”).
- Tap selects, persists, closes (sort also reloads gallery).

### 4.7 Thumbnail & shimmer

- Images: center-crop; animated GIF/WebP plays inline. Video tiles/posters: fast frame-0 poster, crossfades 120ms when async loads.
- While loading (not error, not cached): shimmer — linear gradient `[base60%, base, base60%]`, 400px bright band sweeping horizontally, 1200ms linear infinite. Base `#09090B` (images) / `#18181B` (video posters).
- Error / empty slot: `EmptyPreview` — `#18181B` 12dp (0dp in viewer) + centered `BrokenImage` outlined icon muted at 32% size.

### 4.8 Video badge

- 26dp circle, black 60%, centered 18dp white `PlayArrow` (“Video”). Bottom-end of tile with 8dp inset, drawn on top.

### 4.9 Thumbnail sync pill (“Caching previews”)

- Top-center, 64dp below status-bar top, above Albums + album. Content-sized pill: 50dp round, `#09090B`, 1dp `#27272A` stroke, 12dp shadow. Row 16dp horizontal / 9dp vertical.
- Leading: 15dp circular progress (2dp round, white on `#27272A` track) while working; else 15dp `Sync` outlined white. 10dp gap → “Caching previews · NN%” 13sp Medium white. 8dp gap → “done/total” 12sp muted.
- Slides+fades from top on enter/exit; visible only while indexing; hides at 100%.

### 4.10 File-size formatting

- ≥1GB: `%.2f GB`; ≥1MB: `%.1f MB`; ≥1KB: `%.0f KB`; else `%d B`. US locale.

---

## 5. Permission Gate (first launch)

**When shown:** if `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` (Android 13+) or `READ_EXTERNAL_STORAGE` (Android 10–12) missing. Android 14 partial photo access counts as granted (no card; MediaStore returns subset). Re-checked on every resume.

**Layout:** full black box, 24dp padding, centered card max 360dp wide, `#09090B`, 12dp corners, 8dp vertical gaps.

- Top column (24dp padding, centered, 16dp gaps, 8dp top spacer): 56dp black tile 14dp corners with 28dp `PhotoLibrary` outlined white; title “Allow access to your photos” 18sp SemiBold white centered; message “Foto needs access …” 14sp muted 20sp line-height centered; 4dp spacer.
- Bottom column (24dp sides, 8dp top, 24dp bottom, 12dp gaps): primary button full-width 44dp 12dp shape white fill black text “Grant access” SemiBold → system permission request; secondary outlined button (only if rationale should show) same size, white text, 1dp `#27272A` border “Open settings” → app details settings.
- Deny without rationale: only Grant button.

---

## 6. Albums Grid (Home)

### 6.1 Header

- Frosted bar (scrim + blur). Row: status-bar padding + 56dp + 20dp sides, centered vertically.
- Normal: left “Albums” 28sp SemiBold white −0.5sp tracking (weight 1); right two icon buttons 24dp white: `Search` outlined (“Search”) + `Settings` outlined (“Settings”).
- Selection mode (replaces title): left `Close` outlined (“Exit selection”) → exits; 4dp gap + “N selected” 16sp SemiBold white (weight 1); right `SelectAll` outlined (“Select all”) → toggles all.

### 6.2 Folder stack tile

- Column full-width 6dp gap: square box (1:1) + name + count.
- Stack: up to 3 layers, all 12dp round, 1dp `#27272A`, `#09090B` bg:
  - Front: newest preview full-size, no scrim.
  - Middle (if ≥3): second preview offset 4,4dp, 85% alpha (15% darkening).
  - Back (if ≥3): third preview offset 8,8dp, 65% alpha (35% darkening).
  - Flush (untilted) offsets; center-crop; empty slot = `EmptyPreview`; video cover shows badge.
- Label: folder name 13sp Medium white 1 line; sub: “1 item / N items” 11sp muted.
- Tap: opens album with deck-unpack from this tile rect. Long-press: haptic + enters/toggles folder selection. In selection: tap toggles, no ripple; accessibility “name, N items”.

### 6.3 Loose image/video tile

- Square 1:1, 12dp clip, `#09090B` bg. Photo = thumb (crop); video = poster + badge (same 26dp badge, bottom-end 8dp, z on top).
- Same gestures as folder (tap opens viewer at index; long-press selects). Accessibility “displayName/Photo/Video[, selected]”.

### 6.4 States

- Loading (`null`): centered white spinner.
- Empty library: centered “No media found” 14sp muted.
- Deletes animate reflow only (no fade); grid bottom reserves 96dp when selection bar visible so last row clears it.

---

## 7. Album (Folder) View

**Opens:** tap folder → deck-unpack animation (see §7.1). Shows immediate previews then swaps to queried items when loaded. Root Albums stays mounted underneath.

### 7.1 Deck-unpack animation

- Forward 320ms, reverse 260ms, `FastOutSlowInEasing`. Single shared phase 0→1 (forward) / 1→0 (reverse); tiles derive pose; scroll never replays.
- Per tile: stagger = (index/20 clamped)×0.6; local p = (phase−stagger)/(1−stagger) clamped.
  - Translation: from (source tile rect + collapsed offset − target cell) × (1−p).
  - Collapsed offsets: index0 0,0; index1 4,4dp; index2 8,8dp (matches stack); beyond: ±2dp scatter.
  - Alpha: first 3 (stack cards) 1 once laid out else 0; others fade 0→1 over p×1.5.
  - Scrim fades: idx1 15%×(1−p), idx2 35%×(1−p). zIndex 100−index (cover on top).
- Container bg black alpha=phase (reveals parent). Header alpha=phase, slides 8dp up on exit. Blur off during flight (scrim only), on at phase ≥0.98.
- Back: tiles collapse into stack; after reverse+24ms delay pops stack. No morph if opened via external intent (static tiles).

### 7.2 Sub-header
- Frosted overlay top, same scrim/conditional blur, status-bar + 56dp + 4dp sides.
- Normal: `ArrowBack` (auto-mirrored rounded) white (“Back”) → animated close; 4dp gap; column: folder name 16sp SemiBold 1 line + “N items” 12sp muted.
- Selection: `Close` → exit; “N selected” 16sp SemiBold (weight 1); `SelectAll` → select all display items / deselect.
- Content top padding clears it (56dp+12dp+status-bar). Empty album: centered “No items” 14sp muted.

### 7.3 Tag pills (content filter)
- Full-span row at the top of the grid (scrolls with content, below the app bar): horizontally scrollable pills, 8dp spacing, 2dp vertical padding. Shown once the first tags land; while background indexing runs, a leading “Finding tags…” 12sp muted caption shows.
- Pill: 50dp round, `#09090B` with 1dp `#27272A` border, “Label · N” 12sp Medium white (N = matching photos); selected = white fill + black text. Tap toggles the filter (tap again clears); a11y “Filter by X[, selected]”.
- Filter keeps only photos whose tags include the concept (score ≥ 0.20); folders always stay visible. Tapping a tile opens the viewer over the filtered list. Empty-after-filter: same “No items” state.

---

## 8. Search Screen

**Opens:** magnifier in Albums header. Slides+fades from right (parent). System back closes. No shared-element; viewer opens via tile-zoom.

**Header (frosted, black bg):** row status-bar+56dp+4dp sides: back arrow white (“Back”) → close; decorative muted search icon; input weight 1 (12dp padding box): overlay placeholder “Search file names” 16sp muted when empty + auto-focused text field 16sp white, white cursor, Search IME; when non-empty right `Close` muted (“Clear”) clears query.

**Mode switch:** below the frosted header, full-width row (16dp sides, 12dp top clearing the bar): two pills “Names” | “Content” (50dp round, selected = white fill + black 13sp Medium, unselected = black fill + white text + 1dp border).

**Behavior (Names):** trims query; empty = no list. Tokenized AND-match over name + type + album + path (case-insensitive). Tap tile hides keyboard, opens viewer.

**Behavior (Content):** query is embedded on-device (300ms debounce) and indexed photos rank by cosine similarity (floor 0.20, top 200). Photos without vectors are excluded and counted in a 12sp muted footnote (“N photos not indexed yet” — indexing happens as albums open). While embedding/loading: centered spinner + “Indexing photos for content search…”.

**Results:** adaptive grid (same density/spacing as gallery; top clears frosted bar; 12dp sides; 8dp spacing). First full-span caption “Matches file names across your whole library” 13sp muted (4dp start/top). Groups by “MMMM yyyy” from date-added else “Unknown date” (encounter order); per-group full-span header 13sp SemiBold muted (4dp start, 8dp top, 2dp bottom). Tiles identical to gallery (12dp, badge, shimmer/error, no selection).

**Empty states (centered, 14sp muted, 24dp sides):** blank query → “Searches file names across all photos and videos. Image contents and text are not searched.”; no matches → “No file names match this search”.

---

## 9. Full Image Viewer

**Opens:** tap any tile → image scales from tile rect to full-screen with corner morph (12dp→0). No rect (share intent) → fade only (from 0.94 scale snap). Horizontal pager, transparent pages, single black backdrop alpha = entry progress. Keyed by URI (stable across delete/restore). Adjacent pages preloaded (Coil images; video posters into LRU).

### 9.1 Enter/exit morph

- Entry 280ms, exit 240ms, `FastOutSlowIn`. Pager scales/translates from tile rect center/size to full container; corners 12dp×(1−p). Top chrome alpha = p×controls; gradient slides with p; top row lifts −12dp×(1−p).
- Per-page correction keeps fitted content matched to tile’s square crop at p=0, neutral at p=1 (bridge poster underneath alpha 1−p). Exit reveals grid in lockstep.

### 9.2 Top bar (top-left only; no top-right menu)

- Container: status-bar + 8dp top/start, 8dp spacing row. Behind: full-width 112dp vertical scrim black 62%→transparent, alpha p×controls.
- Back: 40dp circle `#09090B` 8dp shadow, 22dp auto-mirrored `ArrowBack` `#FAFAFA` → animated close (disabled when chrome hidden).
- Counter pill: 50dp round `#09090B` no border, “3/12” 13sp Medium white, 14dp horizontal / 8dp vertical padding.
- Chrome fades 200ms; images toggle on single tap, auto-hide 3s after show (unless drawer open); videos follow video HUD.

### 9.3 Bottom action drawer

- Full black 55% scrim (tap closes). Sheet bottom-center full-width, top 16dp corners, `#09090B`, nav-bar padding, 12dp top / 24dp bottom.
- Handle 40×4dp muted-40% centered, 20dp gap, single `SpaceEvenly` row.
- Items: Share, Info, Delete, Crop (Crop omitted on video). Each: 54dp black well 14dp corners with 26dp outlined icon + 6dp gap + 12sp Medium white label. Delete red; others white. Tap closes drawer then runs action. Slides+fades from bottom.

### 9.4 Gestures (images)

- **Swipe down (1× only):** dismisses viewer. **Swipe up:** opens drawer (haptic). Threshold 56dp vertical; single finger; second finger aborts; horizontal beyond touch-slop hands to pager; only vertical consumed. Disabled when zoomed/drawer/radial/leaving/multi-touch/removing.
- **Single tap:** toggles chrome. **Double-tap:** if zoomed → animate reset to 1×; else animate to 3× anchored at tap (target offset = (tap−center)×−2), spring `NoBouncy/MediumLow`, cancels fling. Dead during multi-touch.
- **Pinch:** 1–5×, centroid-anchored, pan only when ≠1×, offsets clamped ±W×(scale−1)/2, ±H×(scale−1)/2. 2+ fingers suppress taps/swipes/pager; re-keys input; cancels fling on start/page change.
- **Momentum:** 1:1 drag; on release if >250px/s and >1.01× → exponential decay (friction 0.6) clamped to bounds.
- **Long-press anywhere:** radial menu (see §10). **No rotation gesture** (ignored).
- Pager locked when zoomed (>1.01×), 2+ fingers, radial open, delete/restore slide, or delete in progress.

### 9.5 Delete / share / info / crop

- Drawer Delete (red): confirm dialog if “Confirm before deleting” on (see §12.3); then file copied to private trash + original removed → “Moved to trash” Undo bar (6s) + slide to neighbor (280ms `FastOutSlowIn`; next if exists else prev; last item dismisses). Undo slides back. Trash screen lists it.
- Share: system chooser with URI grant. Info: bottom sheet (see §11). Crop: “Coming soon” light toast (placeholder).
- Page change updates settled index; list shrink clamps pager; input gated during slide.

---

## 10. Long-Press Radial Menu (ViewerActionWheel)

**Summon:** long-press anywhere in viewer (images+videos, host-level). Haptic, video pauses (no auto-resume), wheel blooms at finger (clamped so 132dp+8dp edge stays on screen; centers if screen too small). Sector set frozen at open; crossing page mid-press doesn’t remap. Background photo blurs 16dp×progress.

**Geometry:** outer 132dp, hub 38dp, icons 26dp on 36dp backing (+10dp) at 0.62×outer radius, label column 78dp. Images: 4 sectors (Delete, Info, Share, Crop in order); videos: 3 (Delete, Info, Share, Delete stays top). Sector 0 centered top (−90°). Hit: <38dp hub → cancel; >132dp → cancel; else angle → nearest sector.

**Appearance:** disc radial black 42%→66%, alpha p, spring bloom (1.2 overshoot allowed). Hovered wedge: white 16% (Delete 85% red). Spokes hub→outer white 26% 1dp. Double rims (outer+hub): black 60% 2dp + white 32% 1dp; hub black 50%. Icon backing black 42% (60% selected); tint: Delete red unselected else white; selected white. Label 11sp Medium + black 95% blur-7 shadow. Center `Close` outlined white 95% (0.84×hub). Hint pill below (centerY+outer+20dp, 240dp centered): 50dp round `#09090B` 90% (Delete red 90%), white 18% 1dp border, “Release to {action}” 13sp Medium, 18dp/9dp padding.

**Interaction:** hold → slide → release to run; release on hub/outside cancels. Open spring `MediumBouncy/MediumLow`; close `NoBouncy/Medium`. Haptics on open + sector change. Close spring finishes before action fires (no hard cut). Tap that released an action is swallowed.

---

## 11. Video Playback Page

Real playback via single ExoPlayer per viewer session (no per-page players). Audio movie usage, focus handled. Preloads ±4 (current 3s, neighbors 1.5s, rest tracks/prepare); fallback plain player if preload build fails. Opens paused (preview, no autoplay). Only settled page attaches surface; adjacent show posters; settling on image stops decoders. Pauses on background. Loop pref toggles `REPEAT_ONE` live. Unsupported codec → clean error (no crash).

**Poster veil + surface:** active page: full-bleed texture (Fit) + black shutter + identical Fit poster veil fading 150ms on first frame (2.5s timeout fades anyway) + entry bridge poster. All inside shared correction layer; HUD outside (never distorts). Memory cache avoids placeholder on re-open.

**Gestures:** single tap on upper area toggles HUD. Double-tap left/right seeks ∓10s + shows HUD. No pinch/double-zoom, no swipe-down dismiss, no swipe-up drawer on video. Pager swipe locked while playing (pause to swipe). Long-press still summons radial (pauses + hides HUD).

**Center button:** 56dp circle black 60%, 30dp white Play/Pause, alpha follows HUD; disabled when hidden.

**Bottom HUD pill:** full-width 12dp margins + nav-bar padding, 20dp corners, black 68%. Inner 8dp/6dp. Row 4dp spacing: 44dp Play circle (26dp icon) + current time 12sp Medium white (`m:ss`/`h:mm:ss`) + seeker (weight 1) + total 12sp muted + 44dp mute (22dp `VolumeUp/Off`) + speed chip (“1x” etc, 12sp SemiBold muted, 10dp padding, 10dp corners → dropdown).

- Seeker: 28dp touch height; track 3dp→5dp while scrubbing (150ms); 8dp corners; layers: track `#27272A`, buffered muted, fill white; 8dp white thumb always visible. Tap jumps; drag scrubs. Disabled when progress locked.
- Speed dropdown: 0.5, 0.75, 1, 1.25, 1.5, 2 in order; current white + 18dp Check (“Selected”), others muted. Select sets speed, closes, reshows HUD.
- Auto-hide: show snaps to 1 + restarts 3s timer (no-ops if paused or speed menu open); hide fades 200ms; pausing forces visible; hiding closes speed menu.

**Error state:** full black 85% overlay: 36dp `VolumeOff` muted + 10dp gap + “This video can’t be played” 14sp Medium white + 4dp gap + “The file format or codec isn’t supported on this device.” 12sp muted centered max 260dp. **No top-right 3-dot menu** — only speed dropdown; top bar remains back + counter top-left.

---

## 12. Info Bottom Sheet

- Full black 60% scrim; tap outside closes. No drag handle, no swipe, no peek — static sheet.
- Sheet: bottom-center, full-width + 12dp outer margin, 16dp corners, `#09090B`, inner 20dp.
- Title “Info” 18sp SemiBold + 12dp gap; rows full-width `SpaceBetween` 4dp vertical: label 14sp muted left, value 14sp white right.
- Rows: Name (or …), Type (MIME else Image/Video), Size (formatted or …), Dimensions `W × H` (if known), Album (if known), Date added (`MMM d, yyyy` or …), Date modified (if >0). Async load.

---

## 13. Multi-Select

**Enter:** long-press tile/folder (haptic) or toggle. Taps toggle; header offers Select all; bottom bar acts across selection (folders expand to contents).

**Tile visuals:** badge top-start 8dp (z 2) 24dp circle: selected = white + 14dp `#09090B` Check (“Selected”); unselected = black 50% + 1dp white border hollow. Unselected tiles dim black 40%. No ripple in selection.

**Action bar (bottom-center):** full-width `#09090B` 12dp elevation + nav-bar padding. Top row 8dp/8dp: `Close` white (“Exit selection”) + “N selected” 16sp SemiBold (weight 1). Bottom row 16dp/4dp `SpaceEvenly`: Delete (outlined, white) / Copy / Move / Share — each 10dp round clickable 12dp/6dp padding: 22dp white icon + 4dp gap + 11sp Medium muted label.

**Behaviors:** Delete → confirm (if enabled) → trash + Undo/toasts (“Deleted N items”). Copy/Move → system directory picker (SAF) + busy overlay (“Copying/Moving N items…”) → toasts (“Copied/Moved N items” or partial “Moved X, copied Y”). Share → chooser with grants.

---

## 14. Settings

**Chrome:** full black; frosted header (blur 18dp + black 35% + 60→25% scrim); title 22sp SemiBold (“Settings” root / “Folders” sub). Back arrow white (“Back”). List: 20dp sides, top = frosted + 20dp, bottom 20dp, 12dp spacing + nav-bar padding.

- Section label: 12sp SemiBold muted 0.8sp tracking, 8dp top first / 16dp rest + 4dp start + 2dp bottom.
- Group card: full-width 12dp `#09090B`, rows split by 1dp `#27272A` divider inset 16dp.
- Toggle row: whole-row toggle; 16dp/14dp padding; title 15sp Medium + subtitle 13sp/18sp muted 2dp gap (weight 1) + 12dp gap + Switch (checked: black thumb on white track; unchecked: muted thumb on `#27272A` track/border).
- Action row: whole-row click; same title/subtitle (dims muted when disabled); right: loading 18dp spinner 2dp white else trailing text 13sp muted + 20dp icon (chevron `KeyboardArrowRight` muted or custom white) with 8–12dp gaps.

**Root order & exact copy:**

1. **Display**
   - Toggle “Prefer 120Hz refresh rate” / “Run the display at up to 120Hz on supported devices.” (default on).
   - Action+chevron “Sort order” / “How media and folders are ordered.” trailing current label → dialog Newest/Oldest/Name A–Z/Z–A (default Newest).
   - Action+chevron “Grid size” / “Thumbnail density in the grid.” trailing label → dialog Small (88dp) / Medium (112dp default) / Large (144dp).
2. **Playback**
   - Toggle “Loop videos” / “Restart a video when it ends.” (default off).
3. **Behavior**
   - Toggle “Confirm before deleting” / “Show a confirmation dialog before deleting photos and videos.” (default on).
4. **Library** (changes reload gallery)
   - Toggle “Respect .nomedia” / “Hide media in folders marked .nomedia, including inside folders you add.” (default on; hides granted-tree `.nomedia` when on, shows when off; default view already excludes via scanner).
   - Toggle “Only show selected folders” / ON “Show only media inside the folders you add.” / OFF “Show all media except the folders you add.” (default off).
   - Action+chevron “Manage folders” trailing “None / 1 folder / N folders” → Folders sub-screen.
5. **Storage**
   - Action “Clear thumbnail cache” subtitle: “Clearing…” (spinner, disabled) / “Calculating…” / “Nothing cached” (disabled) / size (`%.1f MB`/`%.0f KB`/`%d B`) → non-destructive confirm (“Clear thumbnail cache?” / “Cached previews will be deleted …” / Cancel + Clear light) → clears + reloads.
   - Action+chevron “Trash” subtitle “Checking…” / “Trash is empty” / “N items · size” → Trash screen.
6. **About**
   - Dead row “Foto” / “Version X” (package version, fallback “1.0”), no chevron.
   - Row “Replay tutorial” / “Show the walkthrough again.” no chevron → unmarks tutorial so overlay appears immediately.

**Transitions:** root slides+fades from right over gallery; Folders/Trash slide+fade from right over Settings (opaque black); system back returns inward. Only Library changes re-query media; Display/Playback apply live via state.

### 14.1 Folders sub-screen

Same frosted header (“Folders”). Hint 13sp/18sp muted 4dp start: allow-mode “Only media inside these folders is shown.” / deny-mode “Media inside these folders is hidden. Everything else is shown.” Empty card “No folders added” 14sp muted 16dp. Each row (16dp start / 8dp end / 8dp vertical): display name 15sp Medium 1 line (URI last segment after `:`/`/`, `_`→space; fallback “Selected directory”) + full URI 12sp muted 1 line (weight 1) + `Close` 20dp muted (“Remove”) → removes + reloads. Last card single “Add directory” + `Add` 20dp white → system `OpenDocumentTree` (persist read+write) → adds + reloads; parent count updates.

### 14.2 Trash screen

Private `trash/` (only this app’s deletes), sorted newest-deleted first (date not shown). Same slide/frosted chrome (“Trash” 22sp + back + “Empty” text-button with `DeleteSweep` icon: white when non-empty, muted when empty; while clearing 16dp spinner + “Emptying…”).

- Empty: centered “Trash is empty” 14sp muted. Else list 16dp sides, top status+56+8dp, bottom 8dp, 8dp spacing: summary 12sp muted (“N · size”) 4dp start/bottom.
- Row card 12dp `#09090B` 8dp padding: 52dp thumb 9dp corners (`Crop`; video 14dp Play bottom-end 4dp) + 12dp gap + name 14sp Medium 1 line + size 12sp muted (weight 1) + `Restore` outlined white (“Restore”) → restores + reloads + `onChanged`; `Delete` outlined red (“Delete”) → permanent single delete (no per-item confirm) + reloads.
- “Empty” → destructive confirm (“Empty trash?” / “Items in the trash will be permanently deleted. This can’t be undone.” / Cancel + Empty red) → clears with spinner → reloads. No in-screen undo (Undo lives in gallery pill after delete).

---

## 15. First-Run Tutorial (5 pages)

Overlay on gallery when permissions granted + unseen; replayable via Settings (appears immediately, no restart). Back/Skip/Get Started all complete (persist seen).

- Container full black; inner 24dp sides + status/nav padding. Top 56dp row end-aligned: “Skip” 15sp Medium muted pill (12dp/8dp) → done.
- Pager weight 1 swipeable, centered: illustration + 28dp gap + title 24sp SemiBold centered + 10dp gap + body 14sp/21sp muted centered 8dp sides.
- Dots: centered, 20dp top/bottom, 4dp spacing: active 8dp white, inactive 6dp `#27272A`.
- CTA full-width 50dp 12dp white → black 15sp SemiBold: “Next” (0–3 scrolls next) / “Get started” (last → done); 12dp bottom spacer.

**Pages (exact copy; all illustrations Compose-drawn, mock card 86% width 4:3 22dp `#18181B` 1dp border):**

1. “Welcome to Foto” / “A fast, AMOLED-first gallery for the photos and videos already on your device.” — 3×3 tiles (`Border` 9dp; one video tile 13dp Play).
2. “Find your photos” / “Open an album to see everything inside, or tap the search icon to find a file by name.” — mock pill search (12×96dp `Border` + 20dp Search) over 2×3 grid.
3. “View and zoom” / “Swipe between photos, double-tap or pinch to zoom, and swipe down to close.” — portrait rect (`Border` 16dp 62%×74%) + 52dp ring (2dp white) + 8dp dot + 28dp `KeyboardArrowDown` muted.
4. “Actions in one motion” / “Long-press anywhere, slide to Delete, Info, Share or Crop, then release to run it.” — 180dp radial (black-35% disc, `Border` rings/spokes) + 4 icons 90°×54dp (Delete red, others white 22dp/26dp boxes) + center Close 20dp + finger dot (16dp ring 2dp white offset 70dp@30°).
5. “Select and stay safe” / “Long-press a tile to select several, then delete, copy, move or share. Deletes go to the trash — undo right away, or empty it from Settings.” — 2×3 grid (3 checked: 16dp white circle + 11dp black Check) + bottom Delete(red)/Copy/Move/Share 20dp + floating Undo pill (white 50dp, bottom 16dp, 11sp black “Moved to trash  ·  Undo” 14dp/7dp).

---

## 16. Motion System (repro recipe)

- Tweens `FastOutSlowInEasing` unless noted: dialog scrim 180ms fade / card 240ms scale 0.92→1 + 180ms fade; deck forward 320ms / reverse 260ms; viewer entry 280ms / exit 240ms; delete/restore slide 280ms; chrome fade 200ms; poster veil 150ms; poster fade 120ms; seeker thicken 150ms; video HUD hide 200ms.
- Springs: radial open `MediumBouncy/MediumLow`, close `NoBouncy/Medium`, bloom allows 1.2 overshoot; double-tap zoom `NoBouncy/MediumLow`.
- Shimmer 1200ms linear infinite; sync slide from top + fade (defaults); settings/folders/trash/search slide from right + fade; drawer slide from bottom + fade; toast slide half-height + fade.
- Draw-phase transforms (`graphicsLayer`) for deck/viewer/zoom/blur so frames don’t recompose; album blur gated to phase ≥0.98; viewer blur 16dp×radial progress; momentum decay friction 0.6 threshold 0.5; double-tap 1↔3× (pinch 1–5×); swipe threshold 56dp; tap vs long-press via press-timeout + touch-slop.

---

## 17. Content Discovery & Data Behaviors (user-visible)

- Sort: Newest/Oldest/Name A–Z/Z–A applies to folders + items.
- Grid density: Small/Medium/Large changes min tile only, no reload.
- Respect `.nomedia` on: hides marked dirs incl. inside added folders; off: shows them (outside added dirs still hidden — scanner never indexes; would need All-files access which app intentionally avoids).
- Allow-list on: only inside added dirs shown (each with media = album); empty list = nothing. Off: all except added dirs.
- Added dirs browsed via SAF tree (recursive) + MediaStore merged/deduped by path; picker persists read+write so deletes survive restart. Primary-volume matching assumed.
- Cache: thumbnails + video frame-0 posters (720px) unified (grid = viewer = player first frame, pixel-identical crossfade); LRU 48/96MB → disk → extract → MediaStore → Coil. Progress pill counts real work.
- Trash: copy-to-trash then remove original (undoable); isolated from system trash; uninstall wipes all (backup disabled).

---

## 18. Accessibility & Edge Cases

- Content descriptions: Search/Settings/Back/Clear/Selected/Select all/Exit selection/Video/Restore/Delete/Remove.
- Live-region polite on toast/undo; switches expose Role.Switch (whole row toggleable); action rows Role.Button; radial hint announces “Release to X”.
- Touch targets: header icons 40dp+ (viewer back 40dp, actions 44dp), drawer wells 54dp, video controls 44dp, tutorial CTA 50dp.
- Empty/error/loading covered per screen (§6.4, §8, §11 error, permission fallback, cache “Nothing cached”, trash “empty”, folders “No folders added”).
- External intents, partial photo access, revoked permissions (resume re-check), unsupported codecs, last-item delete, small-screen radial clamp, zoomed pager lock — all degrade gracefully with no crash.

---

*End of DESIGN.md — reproduce exactly: black `#000000` base, zinc surfaces/borders, 12–16dp cards, 50dp pills, 56dp bars, frosted blur+scrim chrome, deck-unpack + tile-morph + radial + drawer + sheet + slide/fade overlays, and the copy/behaviors above.*
