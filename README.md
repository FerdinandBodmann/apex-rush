# APEX RUSH 🏁

Arcade car racing game for Android — a **single-file Kotlin app** with a pseudo-3D (OutRun-style)
renderer, deep car customization, upgrades, AI opponents, drift & nitro mechanics, and saved
progress. **No assets, no external dependencies** — every graphic is drawn procedurally with
`android.graphics.Canvas`.

## Features

- 🖥️ **Pseudo-3D renderer** — curves, hills, roadside objects, day/sunset/night sky themes
- 🚗 **Garage & deep customization** — chassis, paint, rims, decals, spoiler, tint, and more
- ⬆️ **Upgrade system** — engine, handling, nitro; performance changes how the car drives
- 🏎️ **3 themed tracks** with AI opponents
- 💨 **Drift & nitro mechanics** with a points system
- 💾 **Saved progress** — money, upgrades, and customization stored locally (JSON)
- 📦 **Zero dependencies** — builds offline against the platform SDK alone

## Build

### One-shot script

```bash
./build.sh
```

The script verifies JDK 17+ and the Android SDK, accepts SDK licenses, bootstraps the Gradle
wrapper if it is missing (downloads Gradle 8.7 into `~/.cache/apex-rush-build`), and runs
`assembleDebug`. The APK ends up at `app/build/outputs/apk/debug/app-debug.apk`.

### Manual

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle wrapper --gradle-version 8.7   # once
./gradlew assembleDebug
```

## Controls

| Touch | Action |
| --- | --- |
| Left / right half of screen | Steer |
| Bottom-right button | Accelerate |
| Nitro button | Boost (consumes nitro) |
| Menu buttons | Garage, upgrades, track select |

## Tech stack

| Piece | Choice |
| --- | --- |
| Language | Kotlin 2.0.20 |
| Build | Gradle 8.7 / AGP 8.5.2 |
| SDK | compileSdk 34, minSdk 24, targetSdk 34 |
| JDK | 17+ |
| Dependencies | none |

## Project layout

```
app/src/main/java/com/bodmannferdinand/apexrush/MainActivity.kt   # the whole game
app/src/main/AndroidManifest.xml
app/build.gradle.kts
build.gradle.kts / settings.gradle.kts / gradle.properties
gradle/wrapper/gradle-wrapper.properties
build.sh                        # one-shot build script
.github/workflows/android-build.yml   # CI: builds the APK on every push
```

## CI

Every push to `main` triggers the GitHub Actions workflow, which builds a debug APK and uploads
it as the `apex-rush-debug-apk` artifact — grab it from the **Actions** tab, no local toolchain
needed.
