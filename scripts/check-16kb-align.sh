#!/usr/bin/env bash
# Verify that every native library this build would ship is 16 KB page aligned.
#
#   scripts/check-16kb-align.sh <file-or-dir> [...]
#
# Inputs may be .so files, APKs/AABs, or directories containing any of them.
# Play rejects a submission whose shared libraries are aligned to 4 KB, and the
# rejection only surfaces at upload — so this is checked on the artifact that
# would actually be uploaded, not on the flags that were requested.
#
# The alignment is 0x4000 in every PT_LOAD program header. Reading the ELF
# directly (rather than trusting readelf, which is not on the macOS host) is
# what makes this work the same on a developer machine and in CI.
set -euo pipefail

[ "$#" -gt 0 ] || { echo "usage: $0 <file-or-dir> [...]" >&2; exit 2; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Collect candidate .so files: given .so paths directly, or extracted from
# archives, or found under directories.
collect() {
    local target="$1"
    if [ -d "$target" ]; then
        find "$target" -type f -name '*.so'
    elif [ -f "$target" ]; then
        case "$target" in
            *.so) echo "$target" ;;
            *.apk|*.aab|*.aar)
                # AARs keep them under jni/, APKs and AABs under lib/ and
                # base/lib/. Match any of them rather than enumerating, so a
                # new packager layout cannot silently produce "no libraries".
                local dest="$TMP/$(basename "$target")"
                mkdir -p "$dest"
                unzip -q -o "$target" '*.so' -d "$dest" 2>/dev/null || true
                find "$dest" -type f -name '*.so'
                ;;
        esac
    fi
}

failed=0
checked=0
while IFS= read -r so; do
    [ -n "$so" ] && [ -f "$so" ] || continue
    checked=$((checked + 1))
    if ! python3 -I - "$so" <<'PY'
import struct, sys
path = sys.argv[1]
d = open(path, 'rb').read()
if len(d) < 64 or d[:4] != b'\x7fELF':
    print('%s: not an ELF file' % path)
    sys.exit(1)
is64 = d[4] == 2
if is64:
    phoff = struct.unpack_from('<Q', d, 0x20)[0]
    phentsize = struct.unpack_from('<H', d, 0x36)[0]
    phnum = struct.unpack_from('<H', d, 0x38)[0]
    align_off, fmt = 48, '<Q'
else:
    phoff = struct.unpack_from('<I', d, 0x1C)[0]
    phentsize = struct.unpack_from('<H', d, 0x2A)[0]
    phnum = struct.unpack_from('<H', d, 0x2C)[0]
    align_off, fmt = 28, '<I'
aligns = []
for i in range(phnum):
    off = phoff + i * phentsize
    if struct.unpack_from('<I', d, off)[0] == 1:
        aligns.append(struct.unpack_from(fmt, d, off + align_off)[0])
if not aligns or any(a < 0x4000 for a in aligns):
    print('%s: NOT 16 KB aligned: %s' % (path, [hex(a) for a in aligns]))
    sys.exit(1)
print('%s: 16 KB ok' % path)
PY
    then
        failed=1
    fi
done < <(
    for arg in "$@"; do
        collect "$arg"
    done
)

if [ "$checked" -eq 0 ]; then
    echo "no native libraries found in: $*" >&2
    exit 1
fi
[ "$failed" -eq 0 ] || { echo "16 KB alignment check FAILED" >&2; exit 1; }
echo "16 KB alignment ok on $checked libraries"
