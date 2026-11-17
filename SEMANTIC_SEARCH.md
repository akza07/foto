# On-device semantic search & visual grouping

Status: implemented in the Foto Android app, fully offline.

This document describes the content search stack added on 2026-11-17: the model
runtime, the vector index, the indexing lifecycle, the UI, the accelerator
tuning, and the measured behaviour on the test device.

---

## 1. What it does

- **Text → photo search**: "receipt", "sunset over water", "a dog on a sofa"
  rank real library photos by what they show, not by filename or EXIF.
- **Photo → photo grouping**: photographs of the same scene/burst/pose are
  grouped in **Albums → Similar → Groups**. Exact duplicates still use the
  perceptual hash path and keep their own review flow.
- **Fully offline**: the 388 MB EmbeddingGemma 2 model and the vector database
  live on the device. No network call is made for search or indexing.
- **Filename search is unchanged** and still works with no model installed.

There is no metadata search anymore: camera/lens/EXIF text queries were removed
in favour of content search.

---

## 2. Stack

| Piece | Choice |
|---|---|
| Model | `embeddinggemma-2-text-vision-440m.litertlm` (440M text+vision, 768-D, Matryoshka 256-D) |
| Runtime | `com.google.ai.edge.litertlm:litertlm-android:0.19.1` |
| Vector store | `androidx.appsearch:appsearch:1.1.0` + `appsearch-local-storage` |
| Search expression | `semanticSearch(getEmbeddingParameter(0), min, 1.0, "COSINE")` |
| Schema | hand-built `AppSearchSchema` (no KSP / `@Document` processor) |
| Vector size | 256 floats, L2-normalised |
| Model signature | `embeddinggemma-2-tv-440m-256d` |

Kotlin is raised to **2.4.21** via a KGP classpath override because LiteRT-LM
0.19.1 ships Kotlin 2.4 metadata (AGP 9's built-in Kotlin is 2.2.10).
Compose compiler matches 2.4.21.

The `appsearch-compiler`/KSP route from the original guide is not used: the
project compiles through AGP's built-in Kotlin, and a hand-written schema avoids
an extra annotation-processing toolchain for one document type.

---

## 3. Files

```
data/semantic/
├── SemanticModel.kt      Model file location, SAF import, validation, remove
├── GemmaEmbedder.kt      LiteRT-LM engine lifecycle, backend cascade, embeddings
├── PhotoVectorStore.kt   AppSearch schema, put/remove/search, in-memory mirror
├── SemanticIndexer.kt    Incremental library pass, progress, pacing, resume
├── SemanticSearch.kt     Text query facade + score-margin filtering
├── SemanticScoring.kt    Pure cosine / nearest-best helpers (unit tested)
└── SemanticUtil.kt       Cancellation-safe runCatching

ui/
├── SearchScreen.kt       Name + content results, badges, indexing notice
├── SettingsScreen.kt     Model import/remove, accelerator, indexing policy, counts
├── GalleryScreen.kt      Lifecycle-aware index trigger, Similar refresh trigger
└── BackgroundWorkIndicator.kt  "Indexing photo content" pass row

data/similar/
├── VisualSimilarity.kt   Uses embeddings for Groups/Possible; dHash for copies
└── SimilarityRepository.kt  Rebuilds the report when the vector index changes
```

---

## 4. Model installation

The model is **not bundled** in the APK.

1. Put `embeddinggemma-2-text-vision-440m.litertlm` on the phone (e.g. Downloads).
2. **Settings → Content search → On-device model** → pick the file.
3. The import streams to `files/embeddinggemma-2-text-vision-440m.litertlm.part`,
   validates it with `ModelInfo.from(...)` and `ModelType.EMBEDDING`, then renames
   it atomically. A failed or cancelled import leaves the previous model intact.
4. Replacing with a different byte size wipes the vector index; re-importing the
   same model keeps it.

`SemanticModel.delete(...)` removes the file, wipes the vectors, closes the
engine and invalidates the indexer.

---

## 5. Embedding engine

### Backend selection

`GemmaEmbedder` uses **exactly one** backend per preference — there is no
automatic fallback cascade:

| Preference | Backend |
|---|---|
| **GPU** (default) | GPU for text **and** vision (`Backend.GPU()` / `Backend.GPU()`) |
| **CPU** | CPU only (explicit opt-in) |

Every engine is initialised **and warmed up** with a tiny text embedding, so a
backend that initialises but cannot run a forward pass is rejected before it is
used for real work.

If the selected backend fails, **content search is disabled** — it does not
silently drop to the CPU. The failure is surfaced in Settings and in the search
empty state, and indexing stops instead of failing through the rest of the
library. Changing the preference closes the current engine; the next
search/index initialises the selected backend.

### Image / text processing

- Photos are decoded with `ImageDecoder` (EXIF-correct, HEIC/AVIF aware),
  bounded to **512 px** longest side and re-encoded as JPEG 90 before being
  handed to the vision encoder.
- Text queries use the EmbeddingGemma 2 retrieval prompt:
  `task: search result | query: <query>` (EmbeddingGemma 1's
  `task: search query | text:` format is not used).
- Embeddings are 256-D, L2-normalised (`outputSize = 256`, `normalize = true`).
- `visionTokensPerImage = 70` and `maxInputLength = 256` keep the runtime's GPU
  buffers small.

### Memory lifecycle

- Inference runs at `THREAD_PRIORITY_BACKGROUND`.
- The engine is closed **90 s after the last embedding**. This returns the
  vision encoder's GPU memory (~1 GB on the test device) to the system instead
  of holding it for the whole process lifetime.

### Why no NPU

Test device: MediaTek **MT6896Z** (Dimensity 8000-class, Mali-G610).

- LiteRT-LM logs `NPU accelerator could not be loaded and registered:
  kLiteRtStatusErrorNotFound`.
- The model card ships NPU builds only for **MT6991 / MT6993** (newer flagship
  Dimensity). There is no NPU build for MT6896, so the generic CPU/GPU model is
  the correct artifact.
- The **GPU path is active**: `vision_70` is delegated to **`LITERT_CL`**
  (OpenCL). See the measurements below.

NPU is therefore not offered as a setting; the accelerator picker lists **GPU**
and **CPU**, and the row explains that NPU is unavailable for this model/chip.

---

## 6. Vector index (AppSearch)

Schema `FotoPhotoVector` in DB `foto_semantic`:

| Property | Type | Indexing |
|---|---|---|
| `uri` | String | none |
| `stamp` | String | none |
| `addedAt` | Long | none |
| `embedding` | Embedding (256-D) | `INDEXING_TYPE_SIMILARITY`, no quantisation |

- Document id = photo URI.
- `putAll` batches 8 embeddings.
- `storedStamps()` uses a **projection** of `uri`/`stamp` so reading staleness
  never pulls vectors back.
- `allVectors()` keeps a process-memory mirror for the grouping pass; writes
  invalidate it.
- `generation` (a `StateFlow<Int>`) changes on every write and is the signal
  used by both search and Similar to recompute.

### Text search

1. Embed the query, then run the AppSearch cosine query with a permissive
   provider floor (`0.15`).
2. Recompute the exact cosine from the returned embeddings (the stored vector is
   already in the document).
3. Keep results within **0.05 of the best score** (`SemanticScoring.nearestBest`),
   capped at 60.

The margin is deliberate: text/image scores on this model are compressed (a good
match ≈ 0.70–0.80, unrelated photos still ≈ 0.60), so a fixed cutoff either
leaks the library or cuts a good set short.

`FotoSemantic` debug logging (`adb shell setprop log.tag.FotoSemantic DEBUG`)
prints the top scores for threshold tuning.

---

## 7. Indexing lifecycle

Trigger: `GalleryScreen` while the library is loaded, the model is ready,
indexing is allowed by the policy, and the activity is `RESUMED`
(`repeatOnLifecycle`). Indexing pauses when the app leaves the foreground.

Per pass:

1. Candidates = non-video, distinct URIs, newest first, capped at **800**.
2. Read stored stamps; skip files whose `stamp` matches.
   `stamp` = `embedding config id + lastModified + size + dateAdded`, so a new
   embedding configuration rebuilds the index.
3. Prune stored vectors whose URI is no longer in the library (only when the
   snapshot is complete, never on a provisional first screen).
4. Embed one photo at a time; flush every 8.
5. Progress (`done/total`) is exposed through `SemanticIndexer.progress` and
   shown in Settings and the Albums work pill.

Responsiveness controls:

- **Pacing between photos**: 60 ms while charging, 120 ms on battery, 400 ms in
  power saver.
- **Resource guard every 5 photos**: if the system reports `lowMemory`, or
  available RAM < 700 MB, or the battery is ≤ 15% and not charging, the pass
  waits up to 30 s; if the pressure persists it stops and **resumes on the next
  run** (nothing is lost).
- **Cancellation-safe**: writes happen in batches, so a killed pass keeps every
  completed batch. `CancellationException` is never swallowed.
- **Idle release**: engine closed 90 s after the last embedding.

Not used: WorkManager. Indexing is foreground-only by design so results appear
while the user is in the app and progress is visible; the pass is resumable and
bounded, and a background worker would need a foreground service/notification
for large libraries. This can be revisited if libraries grow much larger.

---

## 8. Visual grouping integration

`analyzeVisualSimilarity` now accepts `embeddings: Map<uri, FloatArray>`:

- Duplicates keep the perceptual path (`dHash` + 32×32 luma + pixel difference);
  that is exactness, not a semantic fallback.
- Groups (≥3) and Possible pairs use embedding cosine **only**. A pair is
  skipped if either photo has no vector — there is no colour/dHash fallback, so
  a missing model leaves Groups empty instead of producing misleading folders.
- Conservative floors:
  - `SemanticGroupFloor = 0.88`
  - `SemanticPairFloor = 0.92`
- Time window (6 h) and aspect constraint still apply, so two unrelated beach
  photos from different days do not merge.
- `SimilarityRepository` keys its cache on the store's `generation`, and
  `GalleryScreen` refreshes it when the indexing pass starts/stops.

---

## 9. Settings

**Settings → Content search** contains:

- **On-device model** — missing / copying % / ready + size; tap to import.
- **Remove model** — frees the model and wipes the vector index.
- **Accelerator** — **GPU** / **CPU**. GPU is required for content search; a
  failure turns the feature off rather than falling back to the CPU. Changing it
  closes the engine; the next search/index builds on the new backend. An init
  failure is surfaced as "The selected accelerator failed to start · content
  search is off".
- **Indexing** — Automatic / Charging only / Paused. Paused leaves the existing
  index searchable.
- **Indexed photos** — count + backend, progress while indexing, "paused" state,
  or a prompt when no model is installed.

---

## 10. Measured on the iQOO (MT6896Z)

First pass: **291 photos**, model already imported.

| | Original tuning | Current tuning |
|---|---|---|
| Image input | 768 px | **512 px** |
| Vision tokens | 140 | **70** |
| Patches / image | ~1071–1170 | **~540** |
| Runtime signature | `vision_140` | **`vision_70` → `LITERT_CL` (OpenCL)** |
| GPU memory (`GL mtrack`) | ~2.05 GB | **~1.03 GB** |
| Native heap | ~164 MB | ~73 MB after idle release |
| Throughput | ~1.7 s/photo | **~1.1 s/photo** |
| After 90 s idle | engine held | **121 MB GL, engine closed** |

The earlier settings pushed the 7.6 GB phone into ~2.5–2.8 GB of swap, which is
what made the whole device lag. The current pass is bounded, paced, memory-aware
and releases the runtime when idle.

Real-library quality checks: "receipt" returned the paper receipts first, then
document/ticket screenshots; "sunset" returned the sunset shots; "a photo from
inside a house" returned the indoor photo first.

---

## 11. Known limitations

- **NPU is unavailable** for this model on MT6896 (see §5). GPU/OpenCL is the
  accelerator, and there is no automatic CPU fallback.
- **First indexing is the expensive step**: 291 photos ≈ 4–6 minutes in the
  foreground at the current pacing. Subsequent runs only embed new/changed
  photos.
- **800-photo cap per pass**: the newest 800 photos are indexed. Older ones can
  be added by raising `MaxPhotosPerPass`.
- **Thresholds are heuristic** until calibrated on a larger real library; the
  debug score log exists for that.
- **Re-encoded image input** (512 px JPEG) can hurt tiny-text retrieval compared
  with the original bytes, but it is what keeps memory/GPU use sane on mobile.
- **Model file is not in the repo**; it is imported on-device and is ~388 MB.
- **No dHash pre-filter during indexing**: every photo stays searchable;
  duplicate cleanup is a separate flow (Similar). The decode cost is already
  paid for the embedding, so skipping duplicates saves little.

---

## 12. Build & verification

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Unit tests: `SemanticScoringTest` covers cosine edge cases and the relative
score cutoff; the existing `GroupedFolderNamingTest` still covers folder naming.

Release artifacts (main branch):

- `apk-output/foto-arm64-android16-release.apk`
- `/media/XYZ/DiffusionArt/foto-arm64-android16-release.apk`

Current version: **2026.11.17-r23**, `versionCode 26111722`.
