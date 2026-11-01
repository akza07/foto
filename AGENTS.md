## Workspace Permissions & Capabilities

- **Project Access**: Full read, write, and directory listing permissions are granted for this project directory (`/home/akza/Projects/phooto`) and all its subdirectories.
- **Tool Execution**: Full permission to use any available tools (including file operations, searches, terminal command execution, and subagent orchestration) within the scope of this project.
- **Scope Restriction**: All tool invocations, reads, and writes must remain confined to the current project directory.

## Subagent orchestration (Pi agents only)

If the agent running this project is a Pi agent, use the `pi-subagents` skill
(`subagent` tool) to parallelize work instead of doing everything serially in
the main loop. Split independent work into parallel children and keep dependent
stages in a single scripted workflow:

- Fan out independent reading, investigation, review, and validation with
  `subagent({ workflow: "parallel", args: { tasks: [...] } })`; use `worker` for
  changes, `scout`/`researcher` for investigation, and `reviewer`/`oracle` for
  independent review.
- Use a workflow script (a `js workflow` code block in the reply, launched via
  `subagent({ workflow: true })`) when a later step depends on an earlier
  result, or for retried, aggregated, or resumed children.
- Keep one writer per working tree: parallelize reads, reviews, and validation,
  and use `worktree: true` when several children must edit at once.
- Briefs must be self-contained (goal, paths, constraints, success criteria,
  expected output); children start with fresh context.
- The main agent keeps intent, routing, decisions, final acceptance, and the
  release build; it does not delegate the APK output step below.
- Non-Pi agents can ignore this section and use their own native delegation or
  serial execution.

## On task completion
If task is completed, then produce an output to `apk-output/foto-arm64-android16-release.apk` so that user can install, test and provide feedback for further improvement.
Then perform `cp apk-output/foto-arm64-android16-release.apk /media/XYZ/DiffusionArt/foto-arm64-android16-release.apk` for the system to copy the file to the device.

- **App identity**: application ID `fool.akza.foto`, launcher name `Foto`.

### Branch-safe artifacts (parallel sessions)

The two canonical paths above are owned by the **main** branch. A parallel
feature/experiment branch must never write them: doing so silently replaces the
main-branch APK (and its diffusion copy), so the next install runs the wrong
code.

- Detect the branch with `git branch --show-current`. On `main` (or `master`),
  use the canonical names exactly as above.
- On any other branch, write a branch-tagged artifact instead:
  `apk-output/foto-<branch-slug>-<versionName>-arm64-android16-release.apk`
  (slug: lowercase, non-alphanumerics → `-`; detached HEAD → short SHA). If the
  build needs to reach the device, copy it to the diffusion folder under the
  **same branch-tagged name** — never the canonical name.
- Leave `apk-output/foto-arm64-android16-release.apk` and
  `/media/XYZ/DiffusionArt/foto-arm64-android16-release.apk` untouched from
  feature branches. The fixed diffusion name is the release channel for `main`;
  a branch build can still be installed manually from its tagged path.
- Always state the exact artifact path in the completion summary, so it is clear
  which build the user is installing.
- Device testing is shared too: a parallel branch session must not uninstall,
  clear data of, install a differently-signed build over, or drive the emulator
  another session is using. Use a separate AVD/worktree or coordinate first.

## Versioning

Versioning is **date-based**: every release APK that ships changes is stamped
with the build date instead of a semantic major/minor number. Major features,
minor fixes and refactors all use the same scheme.

- **Source of truth**: `app/build.gradle.kts` → `android.defaultConfig.versionCode`
  and `versionName`. Do not introduce a second version source.
- **`versionName`**: the build date as `YYYY.MM.DD` (e.g. `2026.11.01`), shown in
  Settings → About and in the APK metadata. For a second build on the same day,
  append `-rN` to match the daily counter (`2026.11.01-r2` for build 2).
- **`versionCode`**: `YYMMDD * 100 + N` (two-digit year), where `N` is the
  zero-based build number for that day (`00`–`99`). This is a unique,
  monotonically increasing integer (2026-11-01, first build → `26110100`) and
  stays far below both Google Play's 2,100,000,000 limit and lint's
  `HighAppVersionCode` warning. Never reuse or decrease it; the two-digit year
  is valid through 2099.
- **Ordering**: set both before `./gradlew :app:assembleRelease`, so the APK, the
  About row and the artifact copied to `apk-output/` report the same date/build.
- **Commit body**: mention the version (e.g. “Date version 2026.11.01,
  versionCode 26110100”) so the shipped artifact stays traceable.
- **Major features/changes do not get a separate number** — the date and the
  daily build counter are the version. Put the feature name in the commit
  subject/body, not the version.

## Commit messages

Follow the format already used in this repository. Use a conventional subject
line (`type(scope): imperative summary`) followed by a high-level prose
description of what changed and why. Write paragraphs, or a compact `-` list
with no blank lines between items; do not put a blank line between list
entries. The body should read as a summary a reviewer can understand without
the diff, not as a bare bullet dump.

Do not pass one `-m` per bullet: each `-m` becomes a separate paragraph and
leaves a blank line between list items.

## Run in the Android emulator

The installed `Pixel_10a` AVD uses an x86_64 Android image with arm64-v8a compatibility.
Build and launch the debug app with:

```bash
emulator -avd Pixel_10a -no-snapshot
./gradlew :app:assembleDebug
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p fool.akza.foto 1
```

If the emulator is already running, skip the `emulator` command. The `adb` commands
require the ADB server to be able to bind to its local port.

<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->
