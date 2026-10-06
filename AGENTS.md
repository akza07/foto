## Workspace Permissions & Capabilities

- **Project Access**: Full read, write, and directory listing permissions are granted for this project directory (`/home/akza/Projects/phooto`) and all its subdirectories.
- **Tool Execution**: Full permission to use any available tools (including file operations, searches, terminal command execution, and subagent orchestration) within the scope of this project.
- **Scope Restriction**: All tool invocations, reads, and writes must remain confined to the current project directory.

## On task completion
If task is completed, then produce an output to `apk-output/foto-arm64-android16-release.apk` so that user can install, test and provide feedback for further improvement.
Then perform `cp apk-output/foto-arm64-android16-release.apk /media/XYZ/DiffusionArt/foto-arm64-android16-release.apk` for the system to copy the file to the device.

- **App identity**: application ID `fool.akza.foto`, launcher name `Foto`.

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
