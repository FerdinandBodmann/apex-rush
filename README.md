# APEX RUSH 🏁

Arcade car racing game for Android — a **single-file Kotlin app** with a **real OpenGL ES 3D
engine**: GLSL shaders, true perspective rendering, per-fragment fog, Blinn-Phong lighting with
metallic/gloss/matte paint, a procedural sky shader (sun glare, drifting clouds, twinkling stars),
plus deep car customization, upgrades, AI opponents, drift & nitro mechanics and saved progress.
**No assets, no external dependencies.**

## Features

- 🖥️ **OpenGL ES 3D engine** — the track is a real mesh in world space (circuit centerline +
  elevation), rendered with GLSL shaders: procedural asphalt with rumble strips and lane markings,
  distance fog, sun lighting on hills, glowing night edge markers
- 🎨 **Shader car paint** — Blinn-Phong specular + fresnel per paint finish: matte, gloss, metallic,
  chameleon; low-poly 3D cars (body, glass cabin, wheels, spoilers) with per-chassis proportions
- 🌅 **Procedural sky shader** — gradient dome, sun disc with glare, value-noise clouds, twinkling
  stars at night
- 🗺️ **Real circuit maps** — every track's minimap is computed from its actual curvature geometry,
  shown in track select and as a live in-race minimap with player + rival dots
- 🏁 **6 tracks** — Sunset Coast, Neon City, Alpine Pass, Desert Heat, Emerald Forest, Midnight Storm
- 🌧️ **Weather** — rain, lightning flashes, snowfall
- 🚧 **3D track furniture** — start/finish gantries you drive under, chevron corner boards, palms,
  pines, cacti, buildings, streetlights, billboards
- 🚗 **Garage & deep customization** — 3 chassis, 16 paints, 4 finishes, 5 rim styles, spoilers,
  stripes, neon glow
- ⬆️ **Upgrade system** — engine, turbo, tires; performance changes how the car drives
- 💨 **Drift & nitro mechanics**
- 💾 **Saved progress** — money, upgrades, customization and per-track best times

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
| Bottom-right button | Brake |
| Nitro button | Boost (consumes nitro) |
| Menu buttons | Garage, upgrades, track select |

## Tech stack

| Piece | Choice |
| --- | --- |
| Language | Kotlin 2.0.20 |
| 3D renderer | OpenGL ES 2.0 + custom GLSL (GLES20, GLSurfaceView) |
| Build | Gradle 8.7 / AGP 8.5.2 |
| SDK | compileSdk 34, minSdk 24, targetSdk 34 |
| JDK | 17+ |
| Dependencies | none |

## Project layout

```
app/src/main/java/com/bodmannferdinand/apexrush/MainActivity.kt   # the whole game + 3D engine
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
