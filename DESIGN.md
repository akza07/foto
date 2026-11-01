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
6. Open album stack (nested folder levels)
7. Albums grid (home, bottom)

**Back behavior summary:**

- System back in tutorial dismisses tutorial.
- System back in search closes search.
- System back with selection active exits selection.
- System back in Folders returns to Settings root; in Trash returns to Settings root; in Settings root returns to underlying gallery/album/viewer.
- System back in an album level plays the reverse deck-collapse animation and returns to its parent folder; at the top level it returns to the Albums grid.
- System back in viewer plays shrink-back-to-tile animation then shows grid/album.
- Overlays are single-level; the album directory stack is the exception and pops one folder level at a time.

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
- **Safe deletes:** every delete is undoable. MediaStore files go to Android's system trash (API 30+) — instant, no byte copies. SAF documents (added folders, `.nomedia` content), which have no platform trash, are copied into Foto's private trash first. Both kinds offer Undo.
- **Non-destructive editing:** crop always writes a new copy beside the original; the source file is never modified.
- **Strict gesture disambiguation:** viewer swipes lock to one axis; only a steep (≥2:1) vertical drag opens the drawer, so diagonal swipes page instead of doing both.

---

## 3. Theming, Palette, Typography, Shape, Edge-to-Edge

### 3.1 Color palette (exact hex)

| Token | Hex | Used for |
|---|---|---|
| `AmoledBlack` | `#000000` | Every screen background; behind status/nav bars; radial backing; drawer icon wells |
| `Surface` | `#09090B` | Cards, sheets, tiles, headers, pills (counter, sync, toast base is inverted — see §4), switch off-thumb contrast, viewer chrome |
| `RaisedSurface` | `#111113` | Lifted trash-row cards; separates list rows from AMOLED black without leaving the zinc family |
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
- Delete-progress modal: title 18sp SemiBold `#FAFAFA`; count 13sp `#A1A1AA`.
- Crop editor: title 20sp SemiBold; ratio chips 13sp; hint/footer 12sp muted; action labels 14sp; save error 13sp Medium red.
- Tutorial title: 24sp SemiBold centered; body: 14sp line-height 21sp muted centered; Skip: 15sp Medium muted; CTA: 15sp SemiBold black on light.

### 3.3 Shape & spacing

- Tiles, empty previews: `RoundedCornerShape(12dp)`.
- Cards, sheets: 12dp (settings group, trash row), 16dp (dialogs, delete-progress/busy modals, option dialogs, info sheet, bottom drawer top 16dp only, crop action buttons).
- Trash rows: 12dp `RaisedSurface` + 1dp `Border`, 8dp inner padding, 52dp thumbnail at 9dp corners.
- Delete-progress card: 16dp corners, 24dp inner padding, 4dp progress bar, 1dp `Border`.
- Crop editor: ratio chips are 50dp pills (14dp/8dp padding); Cancel/Save-copy buttons are 48dp tall, 16dp corners; crop canvas has 20dp horizontal / 8dp vertical breathing room.
- Pills/toasts/counter/sync/hint/speed-chip container: `CircleShape` / `RoundedCornerShape(50)`.
- Drawer handle: 40×4dp, 2dp corners, `#A1A1AA` at 40%.
- Buttons in dialogs: 10dp corners, 44dp tall; CTA tutorial: 12dp corners, 50dp tall; drawer icon wells: 14dp corners, 54dp box.
- Grid: adaptive columns (`Small` min 88dp / `Medium` 112dp default / `Large` 144dp), 8dp horizontal+vertical spacing, 12dp side padding, top = status-bar + 56dp bar + 12dp, bottom = 12dp + nav-bar (or 68dp bottom bar / 288dp side rail + 12dp + nav-bar in selection).
- Selection action bar: bottom placement is ~56dp of visible content + nav-bar inset; the default side placement is a 76dp-wide rail (four stacked actions) against the chosen physical edge, 12dp off the edge and above the nav-bar inset. Windows shorter than 600dp (landscape phones) fall back to the bottom bar. Grids reserve the bar's height (68dp bottom / up to 288dp for the rail, sized to cover large font scales) + 12dp + nav-bar while selecting so the last row clears it.
- Headers: 56dp tall + status-bar padding; header horizontal padding 20dp (Albums), 4dp (album/search/settings/folders/trash inner row) + 8dp start inset for viewer top bar.

### 3.4 Edge-to-edge & system bars

- `windowBackground` black; status/nav bars transparent; light icons (not dark); content draws behind both.
- Status-bar icon tint is enforced at the window level in `MainActivity` (light icons regardless of the system light/dark theme, because the app is always black). The full-screen viewer runs immersive: both system bars are hidden while it is open (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, restored on close) so photos and videos own the whole screen, rather than tinting the icons per photo (a content-aware tint visibly flickered). While the viewer is open, `WindowInsetsGuard` pins the window's status/navigation insets to their pre-hide values, so the platform's hide/show animation cannot reflow the grid, headers or viewer chrome in the middle of the tile-morph transition.
- Every screen insets via status-bar top padding and navigation-bar bottom padding. Frosted bars sit in the status zone; grids/lists add top padding to clear them and scroll underneath.
- Optional 120Hz: when “Prefer 120Hz” is on and hardware supports >60.5Hz, window prefers 120Hz and root votes 120fps via frame-rate hint; applied live, no restart.

### 3.5 Launcher icon

- Adaptive icon (`mipmap-anydpi`: `ic_launcher.xml` / `ic_launcher_round.xml`) built from three **vector** layers — `ic_launcher_background` (flat `#09090B`), `ic_launcher_foreground`, `ic_launcher_monochrome` — plus raster mipmaps per density for legacy launchers. Sources in `artwork/`: `foto-launcher.svg` (full squircle), `foto-launcher-round.svg` (full circle), `foto-launcher-foreground.svg` (layer), `foto-launcher-monochrome.svg` (themed layer). All four share one 512-unit geometry.
- Flat, zinc/shadcn, no gradients: a near-black squircle (`#09090B`, `#3F3F46` hairline) holding a single rounded zinc card (`#18181B` fill, `#27272A` hairline, ~29% corner radius) with a white `#FAFAFA` photo glyph inside it — rounded frame, sun dot, rounded mountain peaks.
- Foreground artwork stays within the 66dp safe zone of the 108dp layer, so no launcher mask clips the card; ~52% of the layer's width.
- Monochrome (themed) icon: the same glyph alone (frame + sun + peaks), all-white, tinted by the system; the card is dropped for a cleaner silhouette at small sizes.

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

- Full-screen black 60% scrim, centered card: `#09090B`, 16dp corners, 1dp `Border` stroke, 8dp shadow, 20dp horizontal / 16dp vertical padding.
- Row: 20dp spinner (2dp stroke, `#FAFAFA`) + 12dp gap + label 14sp Medium `#FAFAFA`.
- Enter/exit: scrim fades 180/140ms; card scales 0.94→1 / 0.96 and fades in 220/180ms.
- Blocks input while copy/move/save runs.

### 4.5 Delete-progress modal

- Shown for bulk deletes that include SAF documents (MediaStore-only batches use the platform prompt). Full-screen black 68% scrim; taps are swallowed.
- Centered card: full-width with 32dp side margins, `#09090B`, 16dp corners, 1dp `Border`, 8dp shadow, 24dp inner padding.
- Title “Deleting items…” 18sp SemiBold; 14dp gap; 4dp rounded progress bar (white fill on `#27272A` track); 10dp gap; “N of M” 13sp muted.
- The bar chases the real count with a frame-driven exponential ease (τ ≈ 110ms, 50ms once every item is done), snapping exactly onto the target, so a burst of per-file updates reads as one continuous sweep instead of a re-started tween per item. State is keyed by a per-run id: a new batch starts from 0 rather than animating back from the previous run’s fraction. The displayed count is interpolated from the same fraction. On completion it holds at 100% for 360ms before the modal exits. Library reloads are deferred while a batch runs (the batch reloads once at the end), so MediaStore churn can’t stall the progress loop mid-delete.
- Enter: scrim 180ms fade + card 0.94→1 scale / 220ms + fade. Exit: 0.96 scale / 180ms + 140ms fade.

### 4.6 Confirmation dialog (shared)

- Full-screen black 70% scrim; tap outside dismisses (no ripple).
- Card: full-width with 32dp side margins, 16dp corners, `#09090B`, 1dp `Border` stroke, no shadow. Inner 24dp.
- Title 18sp SemiBold, 8dp gap, message 14sp muted 20sp line-height, 24dp gap, two equal buttons 44dp tall, 10dp corners, 12dp gap.
- Cancel (left): transparent fill, `#FAFAFA` 14sp Medium “Cancel”.
- Confirm (right): red `#EF4444` + white 14sp SemiBold for destructive; else light `#FAFAFA` + black for neutral.
- Enter: scrim fades 180ms; card scales 0.92→1 over 240ms `FastOutSlowIn` + fades 180ms. No exit animation.

### 4.7 Option dialog (sort/grid)

- Same scrim/card geometry as confirm (70% scrim, 32dp margins, 16dp corners, 1dp `Border`, 20dp inner, 18sp title + 8dp gap). Enter: same bloom as the confirm dialog (180ms scrim fade, card 0.92→1 scale / 240ms + 180ms fade).
- Each option: full-width 8dp corners, 8dp horizontal / 12dp vertical padding. Label 15sp (current: `#FAFAFA` SemiBold; others: muted Normal) + current shows 18dp Check `#FAFAFA` (“Selected”).
- Tap selects, persists, closes (sort also reloads gallery).

### 4.8 Thumbnail & shimmer

- Images: center-crop; animated GIF/WebP plays inline. Video tiles/posters: fast frame-0 poster, crossfades 120ms when async loads.
- While loading (not error, not cached): shimmer — linear gradient `[base60%, base, base60%]`, 400px bright band sweeping horizontally, 1200ms linear infinite. Base `#09090B` (images) / `#18181B` (video posters).
- Error / empty slot: `EmptyPreview` — `#18181B` 12dp (0dp in viewer) + centered `BrokenImage` outlined icon muted at 32% size.

### 4.9 Video badge

- 26dp circle, black 60%, centered 18dp white `PlayArrow` (“Video”). Bottom-end of tile with 8dp inset, drawn on top.

### 4.10 Thumbnail sync pill (“Caching previews”)

- Top-center, 64dp below status-bar top, above Albums + album. Content-sized pill: 50dp round, `#09090B`, 1dp `#27272A` stroke, 12dp shadow. Row 16dp horizontal / 9dp vertical.
- Leading: 15dp circular progress (2dp round, white on `#27272A` track) while `done < total`; else 15dp `Sync` outlined white. 10dp gap → “Caching previews” 13sp Medium white. 8dp gap → `done/total` 12sp muted.
- No percentage is shown: the total can grow while discovery is still running, so a percentage would jump backwards. The count is the only progress readout.
- Slides+fades from top on enter/exit; visible only while indexing; hides when `done == total`.

### 4.11 Pull-to-refresh

- Available on the Albums grid and every album level. Drag down from the top; Material3 `PullToRefreshBox` with the default circular indicator.
- Indicator container `#09090B`, spinner `#FAFAFA`; positioned at top-center, below the frosted header (status bar + 56dp + 8dp) so it never overlaps the title row.
- Release triggers a media/preview reload. The indicator stays for at least 300ms so an instant provider response still reads as a refresh. Empty states are scrollable `LazyColumn`s, so pull-to-refresh works even when the grid has no items.
- This reloads library content only; it does not clear the thumbnail cache (that is Settings → Storage → Clear thumbnail cache).

### 4.12 File-size formatting

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
- Normal: left “Albums” 28sp SemiBold white −0.5sp tracking (weight 1); right three 24dp white icon buttons: `FilterList` (“Filter”) opens a dark dropdown with “Sort order” and “Grid size” sections (check on the current option), then `Search` (“Search”) and `Settings` (“Settings”).
- Selection mode (replaces title): left `Close` outlined (“Exit selection”) → exits; 4dp gap + “N selected” 16sp SemiBold white (weight 1); right `SelectAll` outlined (“Select all”) → toggles all. Filter/search/settings are hidden.

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
- Empty library: centered “No media found” 14sp muted, inside a scrollable `LazyColumn` so pull-to-refresh still works.
- Pull-to-refresh: drag down anywhere at the top; indicator sits below the frosted header (see §4.11).
- Deletes animate reflow + a 180ms fade; grid bottom reserves 68dp for the bottom bar, or up to 288dp for a side rail, while the selection bar is visible so the last row clears it.

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
- Normal: `ArrowBack` (auto-mirrored rounded) white (“Back”) → animated close; 4dp gap; column: folder name 16sp SemiBold 1 line + “N items” 12sp muted; right `FilterList` 24dp white opens the same sort/grid dropdown as Albums.
- Selection: `Close` → exit; “N selected” 16sp SemiBold (weight 1); `SelectAll` → select all display items / deselect. Filter is hidden.
- Content top padding clears it (56dp+12dp+status-bar). Empty album: centered “No items” 14sp muted.

### 7.3 Nested folders & pull-to-refresh
- Child folders appear first in the album grid using the same stack tile; tapping one pushes another album level with the same deck-unpack motion. Back pops one level and returns to the parent folder.
- Pull down on any album level to reload media and previews; the indicator sits below the frosted header (see §4.11). Empty states are scrollable so refresh still works.
- There are no tag/content filters in the album grid; search is name-only (see §8).

---

## 8. Search Screen

**Opens:** magnifier in Albums header. Slides+fades from right (parent). System back closes. No shared-element; viewer opens via tile-zoom.

**Header (frosted, black bg):** row status-bar+56dp+4dp sides: back arrow white (“Back”) → close; decorative muted `Search` icon; input weight 1 (12dp horizontal padding box): overlay placeholder “Search file names” 16sp muted when empty + auto-focused text field 16sp white, white cursor, Search IME; when non-empty right `Close` muted (“Clear”) clears query.

**Behavior:** names only. The query is trimmed; an empty query shows no results. Case-insensitive substring match over the display name only (not album, path, type, or image content). Results update immediately — there is no mode switch, debounce, or on-device embedding. Tapping a tile hides the keyboard and opens the viewer at that result.

**Results:** adaptive grid (same density/spacing as gallery; top clears frosted bar; 12dp sides; 8dp spacing). First full-span caption “Matches file names across your whole library” 13sp muted (4dp start/top). Groups by “MMMM yyyy” from date-added else “Unknown date” (encounter order); per-group full-span header 13sp SemiBold muted (4dp start, 8dp top, 2dp bottom). Tiles identical to gallery (12dp, badge, shimmer/error, no selection).

**Empty states (centered, 14sp muted, 24dp sides):** blank query → “Searches file names across all photos and videos. Image contents and text are not searched.”; no matches → “No file names match this search”.

---

## 9. Full Image Viewer

**Opens:** tap any tile → image scales from tile rect to full-screen with corner morph (12dp→0). No rect (share intent) → fade only (from 0.94 scale snap). Horizontal pager, transparent pages, single black backdrop alpha = entry progress. Keyed by URI (stable across delete/restore). Adjacent pages preloaded (Coil images; video posters into LRU).

### 9.1 Enter/exit morph

- Entry 280ms, exit 240ms, `FastOutSlowIn`. Pager scales/translates from tile rect center/size to full container; corners 12dp×(1−p). Top chrome alpha = p×controls; gradient slides with p; top row lifts −12dp×(1−p).
- Per-page correction keeps fitted content matched to tile’s square crop at p=0, neutral at p=1 (bridge poster underneath alpha 1−p). Exit reveals grid in lockstep.

### 9.2 Top bar (top-left only; no top-right menu)

- Container: status-bar inset + 8dp top/start, 8dp spacing row. The viewer runs immersive (bars hidden), so the inset resolves to 0 and the row sits 8dp from the screen edge; behind it a full-width 112dp vertical scrim black 62%→transparent, alpha p×controls.
- Back: 40dp circle `#09090B` 8dp shadow, 22dp auto-mirrored `ArrowBack` `#FAFAFA` → animated close (disabled when chrome hidden).
- Counter pill: 50dp round `#09090B` no border, “3/12” 13sp Medium white, 14dp horizontal / 8dp vertical padding.
- Chrome fades 200ms; images toggle on single tap, auto-hide 3s after show (unless drawer open); videos follow video HUD.

### 9.3 Bottom action drawer

- Full black 55% scrim fades in/out independently (tap closes). Sheet bottom-center full-width, top 16dp corners, `#09090B`, nav-bar padding, 12dp top / 24dp bottom.
- Handle 40×4dp muted-40% centered, 20dp gap, single `SpaceEvenly` row.
- Items: Share, Info, Delete, Crop, Wallpaper (Crop and Wallpaper omitted on video). Wallpaper is sheet-only — it was removed from the long-press wheel as a low-frequency action. Each: 54dp black well 14dp corners with 26dp outlined icon + 6dp gap + 12sp Medium white label. Delete red; others white. Tap closes drawer then runs action. The sheet slides+fades from the bottom; the scrim only fades and never slides.

### 9.4 Gestures (images)

- **Swipe down (1× only):** dismisses viewer. **Swipe up:** opens drawer (haptic). Threshold 56dp vertical; single finger; second finger aborts. Once past touch slop, only a gesture at least 2× more vertical than horizontal counts as vertical; everything else is left entirely to the pager. Vertical consumes both axes and drains the rest of the gesture, so a diagonal (e.g. bottom-right → top-left) swipe pages instead of opening the drawer, and a vertical swipe never pages. Disabled when zoomed/drawer/radial/leaving/multi-touch/removing.
- **Single tap:** toggles chrome. **Double-tap:** if zoomed → animate reset to 1×; else animate to 3× anchored at tap (target offset = (tap−center)×−2), spring `NoBouncy/MediumLow`, cancels fling. Dead during multi-touch.
- **Pinch:** 1–5×, centroid-anchored, pan only when ≠1×, offsets clamped ±W×(scale−1)/2, ±H×(scale−1)/2. 2+ fingers suppress taps/swipes/pager; re-keys input; cancels fling on start/page change.
- **Momentum:** 1:1 drag; on release if >250px/s and >1.01× → exponential decay (friction 0.6) clamped to bounds.
- **Long-press anywhere:** radial menu (see §10). **No rotation gesture** (ignored).
- Pager locked when zoomed (>1.01×), 2+ fingers, radial open, delete/restore slide, or delete in progress.

### 9.5 Delete / share / info / crop / wallpaper

- Drawer Delete (red): video deletes always confirm; other MediaStore items rely on the provider's trash prompt. SAF documents get the in-app confirm because they go to Foto's private trash instead. MediaStore deletes use `MediaStore.createTrashRequest` (no copy); SAF deletes copy bytes into app storage then remove the document. Both show the “Moved to trash” Undo bar (6s) + slide to neighbor (280ms `FastOutSlowIn`; next if exists else prev; last item dismisses). Undo restores from the matching trash and slides back.
- Share: system chooser with URI grant. Info: bottom sheet (see §11). Crop: full-screen crop editor (see §9.6). Wallpaper: full-screen picker (see §9.7).
- Page change updates settled index; list shrink clamps pager; input gated during slide.

### 9.6 Crop editor

- Full-screen black surface. Top bar: 22dp Close icon (“Cancel”), “Crop” 20sp SemiBold, 22dp Reset icon (disabled until the image loads); Close/Reset dim to muted when saving.
- Ratio chips row: Free / 1:1 / 4:5 / 16:9, 50dp round, 13sp Medium, 14dp/8dp padding; selected = white fill + black text, unselected = transparent + 1dp `Border`.
- Canvas: 20dp horizontal / 8dp vertical breathing room, image Fit centered. Crop border 1.5dp white, thirds grid 1dp white 35%, outside scrim black 58%; corner handles 20dp long × 3dp thick, plus 22dp centered edge handles in Free mode.
- Bottom: hint “Drag the corners to choose what to keep” 12sp muted, then Cancel (outlined 1dp `Border`) and **Save copy** (white fill, black label) as 48dp-tall, 16dp-corner buttons; footer “The original photo is kept” 12sp muted. Save failure is 13sp Medium red. The original is never modified.
- Source is decoded upright (EXIF orientation applied) and downsampled to at most 3072px on its long edge, so large photos do not exhaust memory. The save format follows the source (PNG/WebP preserved; JPEG/HEIC/AVIF/GIF become JPEG) at quality 92, named `<stem>_cropped.<ext>`.
- Crop rectangle is stored in normalized image coordinates. Corner handles resize (edges too in Free mode), dragging inside moves it, and a thirds grid plus dark scrim outside the crop preview the result. Fixed ratios anchor the opposite corner so the crop never distorts.
- Output is written beside the original for normal MediaStore folders (DCIM/Pictures/Download/Documents) or through the same SAF tree grant; otherwise it falls back to `Pictures/Foto/`. While encoding, a blocking **Saving crop…** busy overlay is shown. Saving swaps the viewer to the new copy, reloads the grid, and shows a blocking **Preparing preview…** loader until the reload returns the item and its thumbnail is queued; then “Cropped copy saved”.

### 9.7 Wallpaper picker

- Full-screen black surface layered above the viewer (images only). Top bar 56dp: ghost “✕ Cancel” on the left (17dp `Close` + 13sp Medium) and a centred “Wallpaper Editor” 16sp Medium title.
- Preview: a device-shaped frame (30dp corners, 1dp `Border` hairline) filled by exactly the crop, so the frame *is* the result. It takes the aspect of the screen in the chosen orientation, is fitted into the space above the card, and carries a 26dp hint pill underneath (`Surface` + 1dp `Border`, 13dp `Pinch`, “Pinch to zoom • Drag to reposition” 11sp muted). Only crop guides sit on top: thirds grid (1dp white 20%) and 16dp corner brackets (2dp white 92%) at a 10dp inset — no simulated screen content.
- Fullscreen preview: the Preview action hides every app element and draws the same crop fitted to the window (letterboxed when a landscape crop meets a portrait display); tap or back returns. The system bars are already hidden by the viewer underneath, so the preview inherits the immersive view.
- Control card: shadcn-style `Surface` panel with a 1dp `Border`, 16dp corners, 12dp padding and 10dp gaps. Row 1 is the Lock / Home target track (`EmptySurface`, 12dp corners, 4dp padding) with two equal 36dp tabs (16dp icon + 6dp + 13sp Medium; selected = `Border` fill + white text, 180ms crossfade, `Role.Tab` inside a selectable group). Row 2 has two 40dp options (`EmptySurface` + 1dp `Border`, 12dp corners): “Aspect: Portrait/Landscape” (17dp `AspectRatio`, toggles orientation and refits) and “Preview” (17dp `Visibility`, opens the fullscreen preview). Failures insert 13sp Medium red above the action.
- Action: full-width 46dp white **Set wallpaper** (12dp corners, 18dp `CheckCircle` + 14sp SemiBold black); disabled it becomes `EmptySurface` with a `Border` hairline and muted content. While applying, a blocking **Setting wallpaper…** busy overlay; success closes the picker and shows the “Wallpaper set” toast.
- Gestures: pinch shrinks/enlarges the crop window around the gesture centroid (minimum 8% width) and drag pans the image 1:1, so the crop moves against the finger; the focus is clamped to the frame, so dragging past the edge keeps panning instead of jumping to a bound. The window is always clamped inside the photo and refits on orientation change.
- Motion: the editor fades in while rising 64dp (300ms `FastOutSlowInEasing`) and drops out over 200ms before handing control back, so dismissal and a successful apply animate out instead of vanishing. The frame fades/scales in from 0.97 once the bitmap decodes (`AnimatedContent`, 280ms). Aspect morphs the frame across 300ms with the crop refitted to the animated shape every frame (gestures suspended while it runs, so the photo is never stretched). The target pill slides between tabs in 240ms; the save error expands/collapses; option and primary buttons scale to 0.97 on press; Preview fades in while scaling from 1.04 (and back to 0.98 on exit) as the system bars hide.
- Back handling: the picker owns back presses for as long as it is mounted. While the wallpaper is being applied or the close animation runs the press is swallowed instead of falling through to the viewer's handler (which would close the viewer and drop the user on the grid); the fullscreen preview handles its own press first.
- Nothing is written to storage: the crop is handed to `WallpaperManager.setBitmap(..., FLAG_SYSTEM / FLAG_LOCK / both)` under the normal `SET_WALLPAPER` permission, so the original and the library are untouched. The source is decoded upright at the display's long edge (capped at 2560px) and the crop is scaled to the display before it is handed over: `setBitmap` PNG-encodes the bitmap on the calling thread, so full-resolution crops cost memory and seconds.
- Apply lifecycle: a new wallpaper re-themes the system overlay, which arrives as `CONFIG_ASSETS_PATHS`. `MainActivity` declares `assetsPaths` in `android:configChanges`; without it Android recreates the activity mid-apply, which wipes Compose state and looks like a crash back to the album grid. The bitmap work also catches `Throwable`, so an OOM becomes an inline error instead of a dead process.

---

## 10. Long-Press Radial Menu (ViewerActionWheel)

**Summon:** long-press anywhere in viewer (images+videos, host-level). Haptic, video pauses (no auto-resume), wheel blooms at finger (clamped so 132dp+8dp edge stays on screen; centers if screen too small). Sector set frozen at open; crossing page mid-press doesn’t remap. Background photo blurs 16dp×progress.

**Geometry:** outer 132dp, hub 38dp, icons 26dp on 36dp backing (+10dp) at 0.62×outer radius, label column 78dp. Images: 4 sectors (Delete, Info, Share, Crop in order); videos: 3 (Delete, Info, Share, Delete stays top). Wallpaper is deliberately not a sector — it lives only in the bottom action sheet (§9). Sector 0 centered top (−90°). Hit: <38dp hub → cancel; >132dp → cancel; else angle → nearest sector.

**Appearance:** disc radial black 42%→66%, alpha p, spring bloom (1.2 overshoot allowed). Hovered wedge: white 16% (Delete 85% red). Spokes hub→outer white 26% 1dp. Double rims (outer+hub): black 60% 2dp + white 32% 1dp; hub black 50%. Icon backing black 42% (60% selected); tint: Delete red unselected else white; selected white. Label 11sp Medium + black 95% blur-7 shadow. Center `Close` outlined white 95% (0.84×hub). Hint pill below (centerY+outer+20dp, 240dp centered): 50dp round `#09090B` 90% (Delete red 90%), white 18% 1dp border, “Release to {action}” 13sp Medium, 18dp/9dp padding.

**Interaction:** hold → slide → release to run; release on hub/outside cancels. Open spring `MediumBouncy/MediumLow`; close `NoBouncy/Medium`. Haptics on open + sector change. Close spring finishes before action fires (no hard cut). Tap that released an action is swallowed.

---

## 11. Video Playback Page

Real playback via single ExoPlayer per viewer session (no per-page players). Audio movie usage, focus handled. Preloads ±4 (current 3s, neighbors 1.5s, rest tracks/prepare); fallback plain player if preload build fails. Opens paused (preview, no autoplay). Only settled page attaches surface; adjacent show posters; settling on image stops decoders. Pauses on background. Loop pref toggles `REPEAT_ONE` live. Unsupported codec → clean error (no crash).

**Poster veil + surface:** active page: full-bleed texture (Fit) + black shutter + identical Fit poster veil fading 150ms on first frame (2.5s timeout fades anyway) + entry bridge poster. All inside shared correction layer; HUD outside (never distorts). Memory cache avoids placeholder on re-open.

**Gestures:** single tap on upper area toggles HUD. Double-tap left/right seeks ∓10s + shows HUD. No pinch/double-zoom. Vertical swipes are handled by the host viewer: up opens the action drawer, down dismisses. Pager swipe locked while playing (pause to swipe). Long-press still summons radial (pauses + hides HUD).

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

**Enter:** long-press tile/folder (haptic) or toggle. Taps toggle; header offers Select all; the action bar acts across selection (folders expand to contents).

**Glide:** keep the finger down after the long-press and drag across tiles. The tile under the finger at press time fixes the direction for the whole gesture: starting on an unselected tile makes it a select sweep (each covered tile joins the selection), starting on an already selected tile makes it a deselect sweep (each covered one leaves). A light haptic ticks on every change. Lifting ends the gesture; another pass needs a fresh long-press and can go the other way. Hit-testing uses the measured window bounds of each composed tile, so padding, scroll and spacing can't shift the target.

**Header count:** shows the number of *media items* the selection resolves to (an album/parent contributes its whole count), so it matches what Delete/Copy/Move/Share act on.

**Tile visuals:** badge top-start 8dp (z 2) 24dp circle: selected = white + 14dp `#09090B` Check (“Selected”); unselected = black 50% + 1dp white border hollow. Unselected tiles dim black 40%. No ripple in selection.

**Action bar (edge chosen in Settings → Display → Selection actions, right side by default):** a compact vertical rail hugging the left or right physical edge (RTL does not mirror it), 76dp wide, `#09090B`, 16dp corners and 12dp elevation, 12dp off the edge and above the nav-bar inset. Four actions stacked Delete / Copy / Move / Share, each a full-width 10dp-round clickable with 4dp/8dp padding: 22dp white icon + 3dp gap + 11sp Medium muted label. Taps that land between the actions are swallowed so they cannot toggle a tile hidden behind the rail. Windows shorter than 600dp (landscape phones, short split-screen) keep the original full-width bar instead, since a rail would fill the screen: single row 16dp/4dp `SpaceEvenly`, same action cells, nav-bar padding. The selection count and Close live only in the top header, so the bar stays short and leaves more grid visible.

**Behaviors:** Delete always asks first for a bulk selection: the confirm dialog shows the resolved item count and whether the delete is recoverable, regardless of the “Confirm before deleting” preference, because one mis-tap can remove a whole album. Confirming runs the system trash request for MediaStore items and the private trash for SAF documents → Undo. Single SAF deletes show a blocking “Deleting…” spinner; bulk deletes show the blocking “Deleting items…” progress modal (see §4.5). Pull down on the root Albums grid or an album grid to reload media and previews; the indicator sits below the frosted header and stays at least 300ms. Selection chrome animates: header cross-fades, badges fade+scale in, action bar slides in from its configured edge (up from the bottom, or in from the left/right). Copy/Move → system directory picker (SAF) + busy overlay (“Copying/Moving N items…”) → toasts (“Copied/Moved N items” or partial “Moved X, copied Y”). Share → chooser with grants.

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
   - Action+chevron “Selection actions” / “Edge the multi-select bar sits on, for one-handed reach.” trailing Right side (default) / Left side / Bottom → moves the multi-select bar to that physical edge; windows shorter than 600dp keep the bottom bar.
2. **Playback**
   - Toggle “Loop videos” / “Restart a video when it ends.” (default off).
3. **Behavior**
   - Toggle “Confirm before deleting” / “Show a confirmation dialog before deleting photos and videos.” (default on; applies to single-item deletes — bulk selection deletes always confirm).
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
   - Row “Share crash log” / “Send the last crash report” appears only when a Java crash was captured (`CrashLog`, written from the default uncaught-exception handler); tapping shares the trace and clears it. Native crashes never reach it.

**Transitions:** root slides+fades from right over gallery; Folders/Trash slide+fade from right over Settings (opaque black); system back returns inward. Only Library changes re-query media; Display/Playback apply live via state.

### 14.1 Folders sub-screen

Same frosted header (“Folders”). Hint 13sp/18sp muted 4dp start: allow-mode “Only media inside these folders is shown.” / deny-mode “Media inside these folders is hidden. Everything else is shown.” Empty card “No folders added” 14sp muted 16dp. Each row (16dp start / 8dp end / 8dp vertical): display name 15sp Medium 1 line (URI last segment after `:`/`/`, `_`→space; fallback “Selected directory”) + full URI 12sp muted 1 line (weight 1) + `Close` 20dp muted (“Remove”) → removes + reloads. Last card single “Add directory” + `Add` 20dp white → system `OpenDocumentTree` (persist read+write) → adds + reloads; parent count updates.

### 14.2 Trash screen

Merged list of Android's system trash (MediaStore `QUERY_ARG_MATCH_TRASHED`, API 30+) and Foto's private SAF trash, newest first. System entries also appear in the platform's own trash UI. Same slide/frosted chrome (“Trash” 22sp + back + “Empty” text-button with `DeleteSweep` icon: white when non-empty, muted when empty; while the Empty request is pending 16dp spinner + “Emptying…”).

- Empty: centered “Trash is empty” 14sp muted. Else list 16dp sides, top status+56+8dp, bottom 8dp, 8dp spacing: summary 12sp muted (“N · size”) 4dp start/bottom.
- Row card 12dp `RaisedSurface` (`#111113`) with a 1dp `Border` stroke (grey accent against AMOLED black), 8dp padding: 52dp thumb 9dp corners (real image thumbnail, or video poster with a small play affordance; glyph sits behind as a fallback; private entries read the app-private copy) + 12dp gap + name 14sp Medium 1 line + size 12sp muted (weight 1) + `Restore` outlined white (“Restore”) → system `createTrashRequest(value=false)` or private restore + reloads + `onChanged`; `Delete` outlined red (“Delete”) → system `createDeleteRequest` or a permanent-delete confirm for private entries + reloads. Tapping the row opens the item in a read-only viewer/player (no action wheel, drawer, delete, share, info or crop), zooming from the row thumbnail.
- “Empty” → destructive confirm (“Empty trash?” / “Items in the trash will be permanently deleted. This can’t be undone.” / Cancel + Empty red) → system `createDeleteRequest` for system entries and direct removal of private entries → reloads while “Emptying…”. Provider requests are approved in the system dialog; private entries need no prompt.

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
5. “Select and stay safe” / “Long-press a tile to enter selection mode, then glide your finger across tiles to select many in one motion. Start a new glide on a selected tile to deselect. Delete, copy, move or share them; deletes go to the trash — undo right away, or empty it from Settings.” — 2×3 grid (3 checked: 16dp white circle + 11dp black Check) + bottom Delete(red)/Copy/Move/Share 20dp + floating Undo pill (white 50dp, bottom 16dp, 11sp black “Moved to trash  ·  Undo” 14dp/7dp).

---

## 16. Motion System (repro recipe)

- Tweens `FastOutSlowInEasing` unless noted: dialog scrim 180ms fade / card 240ms scale 0.92→1 + 180ms fade; delete-progress bar frame-chased (τ≈110ms, 50ms to finish) + 360ms hold at 100%; deck forward 320ms / reverse 260ms; viewer entry 280ms / exit 240ms; delete/restore slide 280ms; chrome fade 200ms; poster veil 150ms; poster fade 120ms; seeker thicken 150ms; video HUD hide 200ms; pull-to-refresh minimum 300ms.
- Bottom action drawer: the sheet slides 260ms up / 200ms down and fades; its full-screen scrim only fades (180/140ms). Never slide the scrim with the sheet — a sliding tint reads as a second page entering from the bottom.
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
- Cache: thumbnails + video frame-0 posters (720px) unified (grid = viewer = player first frame, pixel-identical crossfade); LRU 48/96MB → disk → extract → MediaStore → Coil. Progress pill reports done/total only; the total may grow during discovery, so no percentage is shown.
- Crop: always writes a new file; the original is untouched. Working bitmap capped at 3072px; PNG/WebP preserved, other formats become JPEG quality 92; output goes beside the original or falls back to `Pictures/Foto/`.
- Wallpaper: never writes a file. The crop is passed straight to `WallpaperManager.setBitmap` (Home / Lock / Both), so there is nothing to undo or clean up and the library never changes.
- Pull-to-refresh reloads the current library view; it does not clear the thumbnail cache.
- Trash: MediaStore items go to the system trash; SAF documents copy into app-private trash then remove the original. Both are undoable/restorable; uninstall wipes private trash (backup disabled).

---

## 18. Accessibility & Edge Cases

- Content descriptions: Search/Settings/Filter/Back/Clear/Selected/Select all/Exit selection/Video/Restore/Delete/Remove/Crop reset/Lock/Home/Aspect/Preview.
- Live-region polite on toast/undo; switches expose Role.Switch (whole row toggleable); action rows Role.Button; radial hint announces “Release to X”.
- Touch targets: header icons 40dp+ (viewer back 40dp, actions 44dp), drawer wells 54dp, video controls 44dp, tutorial CTA 50dp.
- Empty/error/loading covered per screen (§6.4, §8, §11 error, permission fallback, cache “Nothing cached”, trash “empty”, folders “No folders added”).
- External intents, partial photo access, revoked permissions (resume re-check), unsupported codecs, last-item delete, small-screen radial clamp, zoomed pager lock — all degrade gracefully with no crash.

---

*End of DESIGN.md — reproduce exactly: black `#000000` base, zinc surfaces/borders, 12–16dp cards, 50dp pills, 56dp bars, frosted blur+scrim chrome, deck-unpack + tile-morph + radial + drawer + sheet + slide/fade overlays, and the copy/behaviors above.*
