#!/usr/bin/env bash
# Builds TMessagesProj/libs/catnet.aar from the network-base submodule.
#
#   scripts/build-catnet-aar.sh
#
# network-base does not commit its AAR (build output), but the Gradle build
# links it as a plain file, so nothing would ever rebuild it when the Go source
# moves — the APK would keep shipping the old address finder. This is the one
# place that turns network-base/mobile into the AAR, used by both the catnet-aar
# CI job and a local run. Needs go and the Android NDK; gomobile's version is
# pinned by network-base/go.mod.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/network-base/mobile"
OUT="$ROOT/TMessagesProj/libs/catnet.aar"

[ -d "$SRC" ] || { echo "network-base submodule missing; run: git submodule update --init network-base"; exit 1; }
[ -n "${ANDROID_NDK_HOME:-}${ANDROID_NDK_ROOT:-}" ] || {
  echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT must point at the NDK"; exit 1;
}

cd "$ROOT/network-base"

# Run the gomobile pinned in go.mod rather than whatever is on PATH: a
# different x/mobile can emit an AAR whose Java bindings CatNetResolver no
# longer matches. `go tool` builds it in the module cache from that pin.
echo "gomobile: $(go list -m -f '{{.Version}}' golang.org/x/mobile)"

TMP="${TMPDIR:-/tmp}/catnet-aar-$$"; mkdir -p "$TMP"
trap 'rm -rf "$TMP"' EXIT

# gomobile finds gobind only through PATH, and `go tool` does not put it
# there. Install it from the same pin so gomobile and gobind cannot mismatch.
GOBIN="$TMP/bin" go install golang.org/x/mobile/cmd/gobind
export PATH="$TMP/bin:$PATH"

# Bind by import path from the module root. From inside mobile/ with ".", the
# tool reports success and writes a small AAR with no jni/ and no classes.
AAR="$TMP/catnet.aar"
go tool gomobile bind \
  -target=android \
  -androidapi 21 \
  -o "$AAR" \
  github.com/mrfelfel/the-cat-network-base/mobile

if ! unzip -Z1 "$AAR" | grep -qx 'jni/arm64-v8a/libgojni.so'; then
  echo "ERROR: the AAR has no native libs for arm64-v8a; gomobile produced nothing"
  exit 1
fi

mkdir -p "$(dirname "$OUT")"
mv -f "$AAR" "$OUT"
echo "wrote $OUT ($(du -h "$OUT" | cut -f1))"
