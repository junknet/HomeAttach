# Repository Guidelines

## Project Structure & Module Organization

HomeAttach is a single-module Android project. The app module lives in `app/`; Gradle configuration is in `settings.gradle.kts`, root `build.gradle.kts`, `app/build.gradle.kts`, and `gradle/libs.versions.toml`. Kotlin source is under `app/src/main/java/com/homeattach/app/`:

- `data/` stores app settings.
- `ssh/` owns SSH connection and remote session logic.
- `terminal/` integrates the Termux terminal view.
- `ui/` contains Jetpack Compose screens and `ui/theme/`.

Android resources are in `app/src/main/res/`. PC-side helpers are in `server/`: the `tsess*` scripts must remain executable shell scripts, and `server/sharepty/` holds the C source of the sharepty supervisor (fork of dtach; build with `make`, test with `make test`).

## Build, Test, and Development Commands

- `./gradlew :app:assembleDebug` builds the debug APK.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` installs the latest debug build on a connected device.
- `./gradlew :app:testDebugUnitTest` runs JVM unit tests when `app/src/test/` exists.
- `./gradlew :app:connectedDebugAndroidTest` runs instrumentation and Compose UI tests on a connected emulator or device.
- `./gradlew :app:lintDebug` runs Android lint for the debug variant.

Use the checked-in Gradle wrapper, not a system Gradle install.

## Coding Style & Naming Conventions

Use Kotlin with 4-space indentation and explicit, descriptive names. Keep packages under `com.homeattach.app`. Compose UI entry points should use `PascalCase` names ending in `Screen` or `View` where appropriate, matching existing files such as `SessionListScreen.kt` and `SshTerminalView.kt`. Keep SSH and terminal transport code out of UI files; route cross-layer behavior through the existing package boundaries.

Do not hardcode credentials. App secrets belong in runtime settings backed by Android secure storage, never in source or resources.

## Testing Guidelines

Place JVM tests in `app/src/test/java/...` and instrumentation or Compose tests in `app/src/androidTest/java/...`. Name tests after the behavior under test, for example `SshClientTest` or `SessionListScreenTest`. For SSH/session behavior, prefer tests around parsing, command construction, and failure handling; validate real device behavior with `connectedDebugAndroidTest` before merging UI or terminal changes.

## Commit & Pull Request Guidelines

Git history currently contains only `Initial commit: HomeAttach Android app`. Continue with concise, imperative commit subjects, for example `Add session deletion confirmation`. Keep each commit focused on one behavior.

Pull requests should include a short summary, test commands run, device/emulator details for Android changes, and screenshots or screen recordings for visible Compose UI changes. Link related issues when available.

## Configuration Notes

`local.properties`, generated build outputs, keystores, private SSH keys, and device-specific host settings must stay out of version control. If server script paths change, update the Android SSH command code and README setup instructions together.
