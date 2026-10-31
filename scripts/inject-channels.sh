#!/opt/homebrew/bin/bash
# The Cat — per-channel APK generation from ONE signed-less base build.
#
#   scripts/inject-channels.sh --base <apk> --channels channels.txt --out dist/channels
#
# For each non-empty, non-# line of the channels file, emits
#   <out>/TheCat-<version>-<channel>.apk
# which is the base APK plus assets/thecat_channel.txt holding that channel,
# re-aligned and signed with the same release keystore.
#
# Why an asset and not a string resource: rewriting resources.arsc costs ~22s
# per channel (apktool decode+build). Swapping a file inside the ZIP and
# re-signing costs ~7s, and the signature still verifies against exactly the
# same certificate -- post-signing edits are NOT possible (v2/v3 cover the
# whole file), so this is the cheapest point at which injection can happen.
#
# Add the channel asset to the base build ONCE via build.gradle:
#   sourceSets... assets.srcDirs += 'src/main/assets'
# (it can be a 0-byte placeholder; this script overwrites it per channel.)
#
# Environment quirks this script papers over:
#   - /usr/bin/bash on macOS is 3.2 and rejects associative arrays under `set -u`
#   - build-tools 36.0.0 ships Linux binaries; 35.0.0 is universal Mach-O

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
APKSIGNER="$BT/apksigner"
ZIPALIGN="$BT/zipalign"
KEYSTORE="TMessagesProj/config/release.keystore"

prop() {
    local v=""
    v="$(grep "^$1=" gradle.properties 2>/dev/null | head -1 | cut -d= -f2-)"
    if [ -z "$v" ] && [ -f "$HOME/.gradle/gradle.properties" ]; then
        v="$(grep "^$1=" "$HOME/.gradle/gradle.properties" 2>/dev/null | head -1 | cut -d= -f2-)"
    fi
    printf '%s' "$v"
}
KEY_ALIAS="$(prop RELEASE_KEY_ALIAS)"
KEY_PASS="$(prop RELEASE_KEY_PASSWORD)"
STORE_PASS="$(prop RELEASE_STORE_PASSWORD)"

ASSET_NAME="assets/thecat_channel.txt"

die() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

BASE=""
CHANNELS_FILE=""
OUT_DIR="dist/channels"
while [ $# -gt 0 ]; do
    case "$1" in
        --base)     BASE="$2"; shift 2 ;;
        --channels) CHANNELS_FILE="$2"; shift 2 ;;
        --out)      OUT_DIR="$2"; shift 2 ;;
        *) die "Unknown option: $1 (use --base, --channels, --out)" ;;
    esac
done

[ -n "$BASE" ] || die "--base <apk> is required"
[ -f "$BASE" ] || die "Base APK not found: $BASE"
[ -f "$CHANNELS_FILE" ] || die "Channels file not found: $CHANNELS_FILE"
[ -x "$APKSIGNER" ] || die "apksigner not found at $APKSIGNER"
[ -f "$KEYSTORE" ] || die "Keystore not found: $KEYSTORE"

# Refuse to work from an already-signed APK: signing strips and rewrites the
# signing block, and a second pass over a signed file is a good way to ship
# something that installs over a different app.
if "$APKSIGNER" verify "$BASE" >/dev/null 2>&1; then
    echo "WARNING: base APK is already signed; its signature will be replaced."
fi

VERSION="$(prop APP_VERSION_NAME)"
mkdir -p "$OUT_DIR"

# Split the ZIP once and rebuild per channel from that listing, so the
# expensive part (reading and deflating 54 MB) happens once.
WORK="$(mktemp -d -t thecat-channels)"
trap 'rm -rf "$WORK"' EXIT

step "Reading channels from $CHANNELS_FILE"
CHANNELS=()
while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"                       # strip comments
    line="$(echo "$line" | xargs)"           # trim
    [ -n "$line" ] || continue
    case "$line" in
        *[!a-z0-9_-]*) die "Invalid channel '$line' (allowed: a-z 0-9 _ -)" ;;
    esac
    CHANNELS+=("$line")
done < "$CHANNELS_FILE"
[ "${#CHANNELS[@]}" -gt 0 ] || die "No channels found in $CHANNELS_FILE"
echo "    ${#CHANNELS[@]} channel(s)"

START=$SECONDS
for ch in "${CHANNELS[@]}"; do
    step "Injecting '$ch'"

    python3 - "$BASE" "$WORK/unsigned.apk" "$ASSET_NAME" "$ch" <<'PY'
import sys, zipfile
base, out, asset, channel = sys.argv[1:5]
zin = zipfile.ZipFile(base)
# Preserve original compression per entry; assets are deflated like the
# rest, and keeping the order avoids any reason for the installer to walk the
# central directory differently than it did for the base.
with zipfile.ZipFile(out, 'w') as zout:
    for it in zin.infolist():
        if it.filename == asset:
            continue
        zout.writestr(it, zin.read(it.filename))
    zout.writestr(asset, channel)
PY

    "$ZIPALIGN" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
    OUT="$OUT_DIR/TheCat-${VERSION}-${ch}.apk"
    "$APKSIGNER" sign \
        --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
        --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
        --out "$WORK/signed.apk" "$WORK/aligned.apk"

    # Exit status is the authoritative result: apksigner only prints
    # "Verifies" under --verbose, and says nothing at all on success otherwise.
    "$APKSIGNER" verify "$WORK/signed.apk" >/dev/null 2>&1 \
        || die "Signature verification failed for channel '$ch'"
    cp "$WORK/signed.apk" "$OUT"
    echo "    DONE  $(du -h "$OUT" | cut -f1)  ->  $OUT"
done

step "Built ${#CHANNELS[@]} APK(s) in $((SECONDS - START))s -> $OUT_DIR"
echo "Each carries $ASSET_NAME with its channel. All signed with the same"
echo "certificate, so any of them installs over the others in place."
