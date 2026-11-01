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

## Versioning

Every major feature or user-visible behaviour change must increment the app
version, and the bump must be part of the same change that ships it.

- **Source of truth**: `app/build.gradle.kts` → `android.defaultConfig.versionCode`
  (monotonic integer) and `versionName` (`major.minor`, shown in Settings → About
  and used in the APK metadata). Do not introduce a second version source.
- **`versionCode`**: increment for every release APK that ships any change. Never
  reuse or decrease it — Android relies on it for updates.
- **`versionName`**: increment for every major feature or behaviour change —
  minor (`1.1` → `1.2`) for a feature release, major for a redesign or a
  compatibility break. Small internal fixes may bump only `versionCode`.
- **Ordering**: bump before `./gradlew :app:assembleRelease`, so the APK, the
  About row and the artifact copied to `apk-output/` all report the same version.
- **Commit body**: mention the new version (e.g. “Bump to 1.2 (versionCode 3)”) so
  the shipped artifact stays traceable.

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
