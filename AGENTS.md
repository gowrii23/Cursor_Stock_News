# AGENTS.md

## Cursor Cloud specific instructions

### Product overview

**BSE Blueprint Screener** is a personal Android APK (Kotlin UI + embedded Python via Chaquopy). There is no backend server or docker-compose stack. End-to-end development means building the APK and optionally exercising the Python screener pipeline on the host.

### Required tooling

| Tool | Version / path | Notes |
|------|----------------|-------|
| JDK | 17+ (Java 21 works) | Required by Android Gradle Plugin |
| Python | 3.12 at `/usr/bin/python3.12` | Required by Chaquopy `buildPython` in `app/build.gradle.kts` |
| Android SDK | `$HOME/android-sdk` | Set `ANDROID_HOME`; create `local.properties` with `sdk.dir=$ANDROID_HOME` (gitignored) |
| SDK packages | platform 34, build-tools 34.0.0, platform-tools, ndk 26.1.10909125 | Install via `sdkmanager` |

### Build and verify

```bash
export ANDROID_HOME=$HOME/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
# Create local.properties if missing (not committed):
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug          # Build debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug              # Lint (warnings only; no errors expected)
./gradlew :app:testDebugUnitTest      # Unit tests (currently NO-SOURCE)
```

### Host-side Python pipeline (no device needed)

From `app/src/main/python/`, run the screener in demo mode (offline, seeds watchlist):

```bash
pip3 install numpy pandas feedparser requests certifi
cd app/src/main/python && python3.12 pipeline.py
```

This exercises the same logic embedded in the APK via Chaquopy.

### Device / emulator

- Install APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- First launch seeds demo data offline; pull-to-refresh tries live Yahoo/Pulse APIs.
- **Cloud VM caveat:** `/dev/kvm` is typically unavailable, so the Android emulator will not run here. Use a physical device via ADB, or validate with the host Python pipeline + APK build.

### Gotchas

- `local.properties` is gitignored — agents must generate it pointing at `$ANDROID_HOME`.
- Chaquopy downloads Android-specific Python wheels during `./gradlew :app:assembleDebug`; first build is slow (~1–2 min).
- No automated unit/instrumentation tests exist in the repo yet (`testDebugUnitTest` is NO-SOURCE).
- Lint reports ~59 warnings (hardcoded strings, obsolete deps); zero errors.
