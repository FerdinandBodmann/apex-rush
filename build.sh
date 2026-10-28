#!/usr/bin/env bash
# ============================================================
#  APEX RUSH — one-shot Android build script
#  Checks the toolchain, bootstraps the Gradle wrapper if
#  missing, and builds the debug APK.
# ============================================================
set -euo pipefail

GRADLE_VERSION="8.7"
API_LEVEL="platforms;android-34"
BUILD_TOOLS="build-tools;34.0.0"
CACHE_DIR="$HOME/.cache/apex-rush-build"

echo "==> APEX RUSH build"

# --- 1. JDK 17+ -------------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: java not found. Install JDK 17+ (e.g. 'sudo apt install openjdk-17-jdk')." >&2
    exit 1
fi
JAVA_MAJOR=$(java -version 2>&1 | head -n1 | sed 's/[^0-9]*\([0-9]*\).*/\1/')
if [ "$JAVA_MAJOR" -lt 17 ]; then
    echo "ERROR: JDK 17+ required, found major version $JAVA_MAJOR." >&2
    exit 1
fi
echo "==> JDK OK (major $JAVA_MAJOR)"

# --- 2. Android SDK ----------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
    echo "ERROR: ANDROID_HOME / ANDROID_SDK_ROOT not set." >&2
    echo "       Install Android Studio or the cmdline-tools, then export ANDROID_HOME." >&2
    exit 1
fi
echo "==> Android SDK: $SDK"

SDKMANAGER=""
for candidate in "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SDK"/cmdline-tools/*/bin/sdkmanager; do
    if [ -x "$candidate" ]; then SDKMANAGER="$candidate"; break; fi
done
if [ -n "$SDKMANAGER" ]; then
    echo "==> Accepting SDK licenses / installing platform 34"
    yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
    yes | "$SDKMANAGER" "$API_LEVEL" "$BUILD_TOOLS" "platform-tools" >/dev/null
else
    echo "==> sdkmanager not found — assuming SDK platform 34 is already installed"
fi

printf 'sdk.dir=%s\n' "$SDK" > local.properties

# --- 3. Gradle wrapper bootstrap --------------------------------------
if [ ! -x ./gradlew ]; then
    echo "==> Bootstrapping Gradle $GRADLE_VERSION (cached in $CACHE_DIR)"
    mkdir -p "$CACHE_DIR"
    DIST="$CACHE_DIR/gradle-$GRADLE_VERSION"
    if [ ! -d "$DIST" ]; then
        ZIP="$CACHE_DIR/gradle-$GRADLE_VERSION-bin.zip"
        if [ ! -f "$ZIP" ]; then
            curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
        fi
        unzip -q "$ZIP" -d "$CACHE_DIR"
    fi
    "$DIST/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION"
fi

# --- 4. Build -----------------------------------------------------------
./gradlew --no-daemon assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "==> SUCCESS: $APK"
ls -lh "$APK"
