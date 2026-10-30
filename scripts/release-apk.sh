#!/usr/bin/env bash
# The Cat — one-shot release APK build for macOS.
#
#   scripts/release-apk.sh                  build + sign + verify BOTH flavors
#   scripts/release-apk.sh --arm            only the ARM-only (small) build
#   scripts/release-apk.sh --fat           only the all-ABIs build
#   scripts/release-apk.sh --skip-build    re-sign existing APKs only
#                                           (combine with --arm/--fat)
#
# Produces (same versionCode + keystore, so either installs over the other
# in place):
#   dist/TheCat-<version>-ZeroHub.apk      (afat: all 4 ABIs)
#   dist/TheCat-<version>-ARM-ZeroHub.apk  (aarm: armeabi-v7a + arm64-v8a,
#                                           ~19 MB smaller — default download)
#
# This is the PRIORITY build (direct / standalone app). Built from the
# store x standalone variants: production applicationId, R8 minified,
# THE_CAT_DEV_SERVER=false. The Bazaar script is only for when a Bazaar
# build is explicitly needed.
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
# Release signing credentials live in ~/.gradle/gradle.properties (kept OUT of the
# repo), so fall back there when the project gradle.properties omits them.
prop() {
    local v=""
    v="$(grep "^$1=" gradle.properties 2>/dev/null | head -1 | cut -d= -f2-)"
    if [ -z "$v" ] && [ -f "$HOME/.gradle/gradle.properties" ]; then
        v="$(grep "^$1=" "$HOME/.gradle/gradle.properties" 2>/dev/null | head -1 | cut -d= -f2-)"
    fi
    printf '%s' "$v"
}
VERSION="$(prop APP_VERSION_NAME)"
KEY_ALIAS="$(prop RELEASE_KEY_ALIAS)"
KEY_PASS="$(prop RELEASE_KEY_PASSWORD)"
STORE_PASS="$(prop RELEASE_STORE_PASSWORD)"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
APKSIGNER="$SDK/build-tools/36.0.0/apksigner"
NDK_DIR="$SDK/ndk/27.2.12479018"
KEYSTORE="TMessagesProj/config/release.keystore"
OUT_DIR="dist"
APK_DIR="TMessagesProj_AppStandalone/build/outputs/apk"
# flavor -> output file
declare -A OUT_NAME=(
    [afat]="TheCat-${VERSION}-ZeroHub.apk"
    [aarm]="TheCat-${VERSION}-ARM-ZeroHub.apk"
)

fail() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

# --- Flavor selection: default both; --arm / --fat narrow it ---
FLAVORS=(afat aarm)
SKIP_BUILD=0
for arg in "$@"; do
    case "$arg" in
        --arm)        FLAVORS=(aarm) ;;
        --fat)        FLAVORS=(afat) ;;
        --skip-build) SKIP_BUILD=1 ;;
        *) fail "Unknown option: $arg (use --arm, --fat, --skip-build)" ;;
    esac
done

# JDK: Gradle 8.11 supports Java 17..23 — newer (e.g. Homebrew openjdk 27
# after a brew upgrade) breaks :buildSrc config. Honor RELEASE_JAVA_HOME,
# else scan candidates and take the first whose major version is 17..23.
jdk_major() {
    "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1
}
JAVA_HOME="${RELEASE_JAVA_HOME:-}"
if [ -z "$JAVA_HOME" ]; then
    for cand in /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk \
                /opt/homebrew/Cellar/openjdk/* /Library/Java/JavaVirtualMachines/*/Contents/Home; do
        [ -x "$cand/bin/java" ] || continue
        ver="$(jdk_major "$cand")"
        if [ -n "$ver" ] && [ "$ver" -ge 17 ] && [ "$ver" -le 23 ]; then
            JAVA_HOME="$cand"
            break
        fi
    done
fi
if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
    ver="$(jdk_major "$JAVA_HOME")"
    if [ -n "$ver" ] && [ "$ver" -gt 23 ]; then
        fail "java_home JDK is $ver but Gradle 8.11 needs <= 23 (set RELEASE_JAVA_HOME)"
    fi
fi
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
# task name helpers: afat -> assembleAfatStoreStandalone,
#                    aarm -> assembleAarmStoreStandalone
# (module has flavor dimensions abi x severity with store/dev; store keeps
#  the production applicationId and THE_CAT_DEV_SERVER=false)
GRADLE_TASKS=()
for flavor in "${FLAVORS[@]}"; do
    cap="$(tr '[:lower:]' '[:upper:]' <<< "${flavor:0:1}")${flavor:1}"
    GRADLE_TASKS+=(":TMessagesProj_AppStandalone:assemble${cap}StoreStandalone")
done

if [ "$SKIP_BUILD" = 1 ]; then
    step "Skipping build (--skip-build)"
    for flavor in "${FLAVORS[@]}"; do
        [ -f "$APK_DIR/${flavor}Store/standalone/app.apk" ] \
            || fail "No APK at $APK_DIR/${flavor}Store/standalone/app.apk — run without --skip-build first"
    done
else
    step "Building ${GRADLE_TASKS[*]}"
    ./gradlew "${GRADLE_TASKS[@]}" "${GRADLE_FLAGS[@]}"
fi

# --- Sign + verify each selected flavor (always re-sign; Gradle's signature is not trusted) ---
mkdir -p "$OUT_DIR"
for flavor in "${FLAVORS[@]}"; do
    APK_SRC="$APK_DIR/${flavor}Store/standalone/app.apk"
    OUT="$OUT_DIR/${OUT_NAME[$flavor]}"
    [ -f "$APK_SRC" ] || fail "Build produced no APK at $APK_SRC"

    step "Signing $flavor with $KEYSTORE (alias: $KEY_ALIAS)"
    SIGNED="$(mktemp -t thecat-apk).apk"
    "$APKSIGNER" sign \
        --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
        --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
        --out "$SIGNED" "$APK_SRC"
    cp "$SIGNED" "$APK_SRC"
    rm -f "$SIGNED"

    step "Verifying $flavor"
    "$APKSIGNER" verify --verbose --print-certs "$APK_SRC" | grep -E \
        'Verifies$|Verified using v[123] scheme|certificate DN|key size' \
        || fail "Verification failed: $APK_SRC"

    cp "$APK_SRC" "$OUT"
    echo "DONE  $(du -h "$OUT" | cut -f1)  ->  $OUT"
done
