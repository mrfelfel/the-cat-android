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
  -ldflags='-linkmode=external -extldflags=-Wl,-z,max-page-size=16384' \
  -o "$AAR" \
  -javapkg org.telegram.thecat.catnet \
  github.com/mrfelfel/the-cat-network-base/mobile

# The Java API must land in the package CatNetResolver imports. Without
# -javapkg gomobile emits go.* and mobile.* at the jar root, which still packs
# into a valid AAR and only fails later, in the app's javac. This check is what
# turns that hour-long failure into an instant one.
if ! unzip -p "$AAR" classes.jar > "$TMP/classes.jar"; then
  echo "ERROR: the AAR has no classes.jar"
  exit 1
fi
unzip -Z1 "$TMP/classes.jar" | grep -qx 'org/telegram/thecat/catnet/mobile/Client.class' || {
  echo "ERROR: the AAR does not expose org.telegram.thecat.catnet.mobile.Client"
  exit 1
}

if ! unzip -Z1 "$AAR" | grep -qx 'jni/arm64-v8a/libgojni.so'; then
  echo "ERROR: the AAR has no native libs for arm64-v8a; gomobile produced nothing"
  exit 1
fi

# 16 KB page alignment. Play rejects a submission whose native libraries are
# aligned to 4 KB while the app targets a recent SDK, and the failure is only
# visible on upload. gomobile accepts -ldflags but Go links internally by
# default, so -extldflags alone reaches no external linker and is silently
# dropped: the flags above need -linkmode=external to have any effect. A build
# that regresses to 4 KB must fail here, not at Play.
unzip -p "$AAR" 'jni/arm64-v8a/libgojni.so' > "$TMP/libgojni.so"
if ! python3 -I - "$TMP/libgojni.so" <<'PY'
import struct, sys
d = open(sys.argv[1], 'rb').read()
is64 = d[4] == 2
if is64:
    phoff, phentsize, phnum = (struct.unpack_from('<Q', d, 0x20)[0],
                               struct.unpack_from('<H', d, 0x36)[0],
                               struct.unpack_from('<H', d, 0x38)[0])
    align_off = 48
else:
    phoff, phentsize, phnum = (struct.unpack_from('<I', d, 0x1C)[0],
                               struct.unpack_from('<H', d, 0x2A)[0],
                               struct.unpack_from('<H', d, 0x2C)[0])
    align_off = 28
aligns = []
for i in range(phnum):
    off = phoff + i * phentsize
    if struct.unpack_from('<I', d, off)[0] == 1:
        fmt = '<Q' if is64 else '<I'
        aligns.append(struct.unpack_from(fmt, d, off + align_off)[0])
if not aligns or any(a < 0x4000 for a in aligns):
    print('ERROR: libgojni.so is not 16 KB aligned: %s' % [hex(a) for a in aligns])
    sys.exit(1)
PY
then
  exit 1
fi

mkdir -p "$(dirname "$OUT")"
mv -f "$AAR" "$OUT"
echo "wrote $OUT ($(du -h "$OUT" | cut -f1))"
