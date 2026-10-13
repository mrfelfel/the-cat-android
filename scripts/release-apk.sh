#!/usr/bin/env bash
# The Cat — one-shot release APK build for macOS.
#
#   scripts/release-apk.sh            build + sign + verify + copy to dist/
#   scripts/release-apk.sh --skip-build   re-sign the existing APK only
#
# Produces: dist/TheCat-<version>-ZeroHub.apk  (v1+v2+v3, ZeroHub cert)
#
# Environment quirks this script papers over:
#   - ~/.gradle/gradle.properties may point at a dead SOCKS proxy -> disabled via -D
#   - dl.google.com is unreachable from some networks -> Aliyun mirror init script
#   - Gradle's own signing step has produced unsigned APKs under disk pressure
#     -> we ALWAYS re-sign the output with apksigner and verify before shipping

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# --- Config (kept in sync with gradle.properties) ---
prop() { grep "^$1=" gradle.properties | head -1 | cut -d= -f2-; }
VERSION="$(prop APP_VERSION_NAME)"
KEY_ALIAS="$(prop RELEASE_KEY_ALIAS)"
KEY_PASS="$(prop RELEASE_KEY_PASSWORD)"
STORE_PASS="$(prop RELEASE_STORE_PASSWORD)"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
APKSIGNER="$SDK/build-tools/36.0.0/apksigner"
NDK_DIR="$SDK/ndk/27.2.12479018"
KEYSTORE="TMessagesProj/config/release.keystore"
APK_SRC="TMessagesProj_AppStandalone/build/outputs/apk/afat/release/app.apk"
OUT_DIR="dist"
OUT="$OUT_DIR/TheCat-${VERSION}-ZeroHub.apk"

fail() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

# JDK: Gradle 8.11 needs a modern JDK (17+); the real build ran on Homebrew's
# openjdk. Honor an explicit override, else prefer Homebrew, else java_home.
JAVA_HOME="${RELEASE_JAVA_HOME:-}"
if [ -z "$JAVA_HOME" ]; then
    for cand in /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk; do
        [ -x "$cand/bin/java" ] && JAVA_HOME="$cand" && break
    done
fi
[ -n "$JAVA_HOME" ] || JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
[ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] || fail "No JDK found (set RELEASE_JAVA_HOME)"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# Dead SOCKS proxy in ~/.gradle/gradle.properties breaks every download.
GRADLE_FLAGS=(-DsocksProxyHost= -DsocksProxyPort=)

# --- Preflight ---
step "Preflight"
[ -x "$JAVA_HOME/bin/java" ] || fail "JDK not found at $JAVA_HOME (set RELEASE_JAVA_HOME)"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

[ -d "$SDK/platforms/android-36" ] || fail "Platform android-36 missing (sdkmanager 'platforms;android-36')"
[ -d "$SDK/build-tools/36.0.0" ]   || fail "Build-tools 36.0.0 missing"
[ -x "$APKSIGNER" ]                || fail "apksigner missing at $APKSIGNER"
[ -d "$NDK_DIR" ]                  || fail "NDK 27.2.12479018 missing (see NDK_HINT below)"
[ -f "$KEYSTORE" ]                 || fail "Keystore missing: $KEYSTORE"
[ -f TMessagesProj/lib/jlatexmath/jlatexmath/build.gradle ] \
    || fail "Submodules not initialized — run: git submodule update --init --recursive"

echo "sdk.dir=$SDK" > local.properties

# Gradle cannot reach dl.google.com on some networks; route Google Maven
# through the Aliyun mirror via a user-level init script (idempotent).
if ! curl -sI -m 5 https://dl.google.com/dl/android/maven2/ >/dev/null 2>&1; then
    MIRROR="$HOME/.gradle/init.d/google-mirror.gradle"
    if [ ! -f "$MIRROR" ]; then
        step "Installing Aliyun Google-Maven mirror (dl.google.com unreachable)"
        mkdir -p "$HOME/.gradle/init.d"
        cat > "$MIRROR" <<'EOF'
// Auto-added by scripts/release-apk.sh: dl.google.com unreachable here.
def aliyunGoogle = { repos ->
    try {
        repos.maven { m -> m.name = 'aliyun-google-mirror'
            m.url = 'https://maven.aliyun.com/repository/google' }
    } catch (Throwable ignored) {}
}
settingsEvaluated { settings ->
    try { settings.pluginManagement.repositories.gradlePluginPortal() } catch (Throwable ignored) {}
    aliyunGoogle(settings.pluginManagement.repositories)
    try { aliyunGoogle(settings.dependencyResolutionManagement.repositories) } catch (Throwable ignored) {}
}
allprojects { project ->
    aliyunGoogle(project.buildscript.repositories)
    aliyunGoogle(project.repositories)
}
EOF
        echo "    wrote $MIRROR"
    fi
fi

# Disk pressure corrupted Gradle's signing once; warn before starting.
FREE_KB="$(df -Pk "$REPO_ROOT" | awk 'NR==2{print $4}')"
if [ "$FREE_KB" -lt $((5 * 1024 * 1024)) ]; then
    fail "Less than 5 GB free on the build volume ($((FREE_KB / 1024 / 1024)) GB) — clean up first"
fi

# --- Build ---
if [ "${1:-}" = "--skip-build" ]; then
    step "Skipping build (--skip-build)"
    [ -f "$APK_SRC" ] || fail "No APK at $APK_SRC — run without --skip-build first"
else
    step "Building :TMessagesProj_AppStandalone:assembleAfatRelease"
    ./gradlew :TMessagesProj_AppStandalone:assembleAfatRelease "${GRADLE_FLAGS[@]}"
fi
[ -f "$APK_SRC" ] || fail "Build produced no APK at $APK_SRC"

# --- Sign (always; Gradle's own signature is not trusted to be intact) ---
step "Signing with $KEYSTORE (alias: $KEY_ALIAS)"
SIGNED="$(mktemp -t thecat-apk).apk"
trap 'rm -f "$SIGNED"' EXIT
"$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$SIGNED" "$APK_SRC"
cp "$SIGNED" "$APK_SRC"

# --- Verify before shipping ---
step "Verifying"
"$APKSIGNER" verify --verbose --print-certs "$APK_SRC" | grep -E \
    'Verifies$|Verified using v[123] scheme|certificate DN|key size' || fail "Verification failed"

mkdir -p "$OUT_DIR"
cp "$APK_SRC" "$OUT"
echo
echo "DONE  $(du -h "$OUT" | cut -f1)  ->  $OUT"
