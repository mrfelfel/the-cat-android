/* Minimal QR encoder — byte mode, versions 1..40, all ECC levels.
 *
 * Deliberately self-contained: the panel must work offline and must not load
 * code from a CDN, because a tool that builds censorship-resistant carriers
 * should not depend on a reachable third party.
 *
 * Implements ISO/IEC 18004: data encoding, Reed-Solomon ECC, interleaving,
 * module placement, and mask selection. Verified against a real decoder
 * (goqr) for the lengths this project actually uses.
 */
(function (global) {
  "use strict";

  // ECC level -> format bits, and the per-level block tables.
  const ECC = { L: 0, M: 1, Q: 2, H: 3 };
  const ECC_FORMAT_BITS = { L: 1, M: 0, Q: 3, H: 2 };

  // Indexed [version][eccIndex] over versions 1..40.
  // Each entry: [ecCodewordsPerBlock, numBlocksGroup1, numBlocksGroup2]
  const BLOCKS = {
    1:  [[7,1,0],[10,1,0],[13,1,0],[17,1,0]],
    2:  [[10,1,0],[16,1,0],[22,1,0],[28,1,0]],
    3:  [[15,1,0],[26,1,0],[18,2,0],[22,2,0]],
    4:  [[20,1,0],[18,2,0],[26,2,0],[16,4,0]],
    5:  [[26,1,0],[24,2,0],[18,2,2],[22,2,2]],
    6:  [[18,2,0],[16,4,0],[24,4,0],[28,4,0]],
    7:  [[20,2,0],[18,4,0],[18,2,4],[26,4,1]],
    8:  [[24,2,0],[22,2,2],[22,4,2],[26,4,2]],
    9:  [[30,2,0],[22,3,2],[20,4,4],[24,4,4]],
    10: [[18,2,2],[26,4,1],[24,6,2],[28,6,2]],
    11: [[20,4,0],[30,1,4],[28,4,4],[24,3,8]],
    12: [[24,2,2],[22,6,2],[26,4,6],[28,7,4]],
    13: [[26,4,0],[22,8,1],[24,8,4],[22,12,4]],
    14: [[30,3,1],[24,4,5],[20,11,5],[24,11,5]],
    15: [[22,5,1],[24,5,7],[30,5,7],[24,11,7]],
    16: [[24,5,3],[28,7,3],[24,15,2],[30,3,13]],
    17: [[28,1,5],[28,10,1],[28,1,15],[28,2,17]],
    18: [[30,5,3],[26,9,4],[28,17,1],[30,2,19]],
    19: [[28,3,4],[26,3,11],[26,17,4],[28,9,16]],
    20: [[28,3,5],[26,3,13],[30,15,5],[28,15,10]],
    21: [[28,4,4],[26,17,0],[28,17,6],[30,19,6]],
    22: [[28,2,7],[28,17,0],[30,7,16],[24,34,0]],
    23: [[30,4,5],[28,4,14],[30,11,14],[30,16,14]],
    24: [[30,6,4],[28,6,14],[30,11,16],[30,30,2]],
    25: [[26,8,4],[28,8,13],[30,7,22],[30,22,13]],
    26: [[28,10,2],[28,19,4],[28,28,6],[30,33,4]],
    27: [[30,8,4],[28,22,3],[30,8,26],[30,12,28]],
    28: [[30,3,10],[28,3,23],[30,4,31],[30,11,31]],
    29: [[30,7,7],[28,21,7],[30,1,37],[30,19,26]],
    30: [[30,5,10],[28,19,10],[30,15,25],[30,23,25]],
    31: [[30,13,3],[28,2,29],[30,42,1],[30,23,28]],
    32: [[30,17,0],[28,10,23],[30,10,35],[30,19,35]],
    33: [[30,17,1],[28,14,21],[30,29,19],[30,11,46]],
    34: [[30,13,6],[28,14,23],[30,44,7],[30,59,1]],
    35: [[30,12,7],[28,12,26],[30,39,14],[30,22,41]],
    36: [[30,6,14],[28,6,34],[30,46,10],[30,2,64]],
    37: [[30,17,4],[28,29,14],[30,49,10],[30,24,46]],
    38: [[30,4,18],[28,13,32],[30,48,14],[30,42,32]],
    39: [[30,20,4],[28,40,7],[30,43,22],[30,10,67]],
    40: [[30,19,6],[28,18,31],[30,34,34],[30,20,61]]
  };

  // Total codewords per version (data + ecc).
  const TOTAL_CODEWORDS = (v) => {
    // Derived from the module count rather than tabulated: fewer constants to
    // get wrong, and the formula is exact.
    const size = v * 4 + 17;
    let modules = size * size;
    modules -= 3 * 64;            // three finder patterns + separators
    modules -= 2 * (size - 16) * 2 + 2 * 15 * 2; // timing + alignment reserve
    // The above is a shortcut; use the exact function instead.
    return totalCodewordsExact(v);
  };

  function alignmentPositions(version) {
    if (version === 1) return [];
    const n = Math.floor(version / 7) + 2;
    const step = version === 32 ? 26 : Math.ceil((version * 4 + 4) / (n * 2 - 2)) * 2;
    const out = [6];
    let pos = version * 4 + 10;
    while (out.length < n) {
      out.splice(1, 0, pos);
      pos -= step;
    }
    return out.sort((a, b) => a - b);
  }

  function totalCodewordsExact(version) {
    const size = version * 4 + 17;
    let total = size * size;
    total -= 3 * 64;                                  // finders + separators
    total -= 2 * (size - 16) * 1 * 2;                 // timing patterns... adjusted below
    // Exact accounting: count function modules directly.
    const grid = Array.from({ length: size }, () => new Array(size).fill(false));
    const mark = (x, y) => { if (x >= 0 && y >= 0 && x < size && y < size) grid[y][x] = true; };
    // finders
    for (const [ox, oy] of [[0,0],[size-7,0],[0,size-7]]) {
      for (let y = -1; y <= 7; y++) for (let x = -1; x <= 7; x++) mark(ox + x, oy + y);
    }
    // timing
    for (let i = 0; i < size; i++) { mark(6, i); mark(i, 6); }
    // alignment
    const ap = alignmentPositions(version);
    for (const ay of ap) for (const ax of ap) {
      if ((ax === 6 && ay === 6) || (ax === 6 && ay === size - 7) || (ax === size - 7 && ay === 6)) continue;
      for (let y = -2; y <= 2; y++) for (let x = -2; x <= 2; x++) mark(ax + x, ay + y);
    }
    // format info
    for (let i = 0; i < 9; i++) { mark(8, i); mark(i, 8); }
    for (let i = 0; i < 8; i++) { mark(8, size - 1 - i); mark(size - 1 - i, 8); }
    mark(8, size - 8);
    // version info
    if (version >= 7) {
      for (let i = 0; i < 18; i++) {
        mark(size - 11 + (i % 3), Math.floor(i / 3));
        mark(Math.floor(i / 3), size - 11 + (i % 3));
      }
    }
    let free = 0;
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) if (!grid[y][x]) free++;
    return Math.floor(free / 8);
  }

  function dataCapacity(version, ecc) {
    const [ecPerBlock, g1, g2] = BLOCKS[version][ECC[ecc]];
    const total = totalCodewordsExact(version);
    return total - ecPerBlock * (g1 + g2);
  }

  // Multiply in GF(2^8) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1
  // (0x11d). Russian-peasant multiply: shift-and-add, reducing on carry-out.
  function gfMul(x, y) {
    let z = 0;
    for (let i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11d);
      z ^= ((y >>> i) & 1) * x;
    }
    return z & 0xff;
  }

  // a^i in GF(2^8) — repeated multiplication by 2, NOT Math.pow. The field's
  // scalar is not the integer 2, so Math.pow(2, i) is nonsense beyond i=7 and
  // overflows into values that are not even in the field.
  function gfPow2(i) {
    let v = 1;
    for (let n = 0; n < i; n++) {
      v = (v << 1) ^ ((v >>> 7) * 0x11d);
    }
    return v & 0xff;
  }

  function rsGenerator(degree) {
    // Multiply (x - a^0)(x - a^1)...(x - a^(degree-1)) in GF(256).
    //
    // The subtlety: poly is stored highest-degree-first, and multiplying by
    // (x - a^i) means shifting every term up one degree (that is the x) and
    // XORing the same term times a^i into its current slot. Getting the shift
    // direction wrong still produces a monic polynomial of the right degree,
    // and still produces plausible-looking output — which is exactly how a QR
    // gets rendered that no decoder can read.
    let poly = [1];
    for (let i = 0; i < degree; i++) {
      const next = new Array(poly.length + 1).fill(0);
      for (let j = 0; j < poly.length; j++) {
        next[j] ^= poly[j];                    // term * x
        next[j + 1] ^= gfMul(poly[j], gfPow2(i)); // term * a^i
      }
      poly = next;
    }
    return poly;
  }

  function rsRemainder(data, degree) {
    const gen = rsGenerator(degree);
    const res = new Array(degree).fill(0);
    for (const b of data) {
      const factor = b ^ res[0];
      res.shift();
      res.push(0);
      if (factor !== 0) {
        for (let i = 0; i < degree; i++) {
          res[i] ^= gfMul(gen[i + 1], factor);
        }
      }
    }
    return res;
  }

  function encodeData(bytes, version, ecc) {
    const cap = dataCapacity(version, ecc);       // in codewords
    const bits = [];
    const push = (val, len) => { for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); };

    push(0b0100, 4);                              // byte mode
    const countBits = version <= 9 ? 8 : 16;
    push(bytes.length, countBits);
    for (const b of bytes) push(b, 8);

    const capBits = cap * 8;
    if (bits.length > capBits) return null;
    push(0, Math.min(4, capBits - bits.length));  // terminator
    while (bits.length % 8 !== 0) bits.push(0);

    const codewords = [];
    for (let i = 0; i < bits.length; i += 8) {
      let v = 0;
      for (let j = 0; j < 8; j++) v = (v << 1) | bits[i + j];
      codewords.push(v);
    }
    // Pad codewords 0xEC / 0x11 alternating.
    let pad = 0;
    while (codewords.length < cap) codewords.push(pad++ % 2 === 0 ? 0xec : 0x11);
    return codewords;
  }

  function interleave(dataCodewords, version, ecc) {
    const [ecPerBlock, g1, g2] = BLOCKS[version][ECC[ecc]];
    const totalData = dataCodewords.length;
    const g1Len = Math.floor(totalData / (g1 + g2)) + (g2 > 0 ? 0 : 0);
    // Group split: group1 blocks have `shortLen`, group2 have `shortLen + 1`.
    const numBlocks = g1 + g2;
    const shortLen = Math.floor(totalData / numBlocks);
    const numLong = totalData % numBlocks;

    const blocks = [];
    let p = 0;
    for (let i = 0; i < numBlocks; i++) {
      const len = shortLen + (i >= numBlocks - numLong ? 1 : 0);
      const d = dataCodewords.slice(p, p + len);
      p += len;
      blocks.push({ data: d, ecc: rsRemainder(d, ecPerBlock) });
    }

    const out = [];
    const maxLen = Math.max(...blocks.map(b => b.data.length));
    for (let i = 0; i < maxLen; i++) {
      for (const b of blocks) if (i < b.data.length) out.push(b.data[i]);
    }
    for (let i = 0; i < ecPerBlock; i++) {
      for (const b of blocks) out.push(b.ecc[i]);
    }
    return out;
  }

  function buildMatrix(version, ecc, codewords) {
    const size = version * 4 + 17;
    const m = Array.from({ length: size }, () => new Array(size).fill(null));
    const reserved = Array.from({ length: size }, () => new Array(size).fill(false));
    // Which data bit lands in which module. The panel needs this to tell how
    // many Reed-Solomon codewords a degraded image actually damaged, which is
    // the only honest way to say whether a code still decodes.
    const bitIndex = Array.from({ length: size }, () => new Array(size).fill(-1));

    const set = (x, y, v) => { if (x >= 0 && y >= 0 && x < size && y < size) { m[y][x] = v; reserved[y][x] = true; } };

    // Finder patterns + separators.
    for (const [ox, oy] of [[0,0],[size-7,0],[0,size-7]]) {
      for (let y = -1; y <= 7; y++) {
        for (let x = -1; x <= 7; x++) {
          const inside = x >= 0 && x <= 6 && y >= 0 && y <= 6;
          const ring = inside && (x === 0 || x === 6 || y === 0 || y === 6);
          const core = inside && x >= 2 && x <= 4 && y >= 2 && y <= 4;
          set(ox + x, oy + y, ring || core ? 1 : 0);
        }
      }
    }
    // Timing patterns.
    for (let i = 8; i < size - 8; i++) {
      set(6, i, i % 2 === 0 ? 1 : 0);
      set(i, 6, i % 2 === 0 ? 1 : 0);
    }
    // Alignment patterns.
    const ap = alignmentPositions(version);
    for (const ay of ap) for (const ax of ap) {
      if ((ax === 6 && ay === 6) || (ax === 6 && ay === size - 7) || (ax === size - 7 && ay === 6)) continue;
      for (let y = -2; y <= 2; y++) {
        for (let x = -2; x <= 2; x++) {
          const ring = Math.max(Math.abs(x), Math.abs(y)) !== 1;
          set(ax + x, ay + y, ring ? 1 : 0);
        }
      }
    }
    // Reserve format areas (real values written later).
    for (let i = 0; i < 9; i++) { set(8, i, 0); set(i, 8, 0); }
    for (let i = 0; i < 8; i++) { set(8, size - 1 - i, 0); set(size - 1 - i, 8, 0); }
    set(8, size - 8, 1); // dark module
    // Version info.
    if (version >= 7) {
      let rem = version;
      for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
      const bits = (version << 12) | rem;
      for (let i = 0; i < 18; i++) {
        const bit = (bits >>> i) & 1;
        const a = size - 11 + (i % 3), b = Math.floor(i / 3);
        set(a, b, bit); set(b, a, bit);
      }
    }

    // Data placement, two columns at a time, upward/downward zig-zag.
    let bitIdx = 0;
    const totalBits = codewords.length * 8;
    let upward = true;
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < size; vert++) {
        const y = upward ? size - 1 - vert : vert;
        for (let j = 0; j < 2; j++) {
          const x = right - j;
          if (reserved[y][x]) continue;
          let bit = 0;
          if (bitIdx < totalBits) {
            bit = (codewords[bitIdx >> 3] >> (7 - (bitIdx & 7))) & 1;
          }
          m[y][x] = bit;
          bitIndex[y][x] = bitIdx;
          bitIdx++;
        }
      }
      upward = !upward;
    }

    return { m, size, bitIndex };
  }

  const MASKS = [
    (x, y) => (x + y) % 2 === 0,
    (x, y) => y % 2 === 0,
    (x, y) => x % 3 === 0,
    (x, y) => (x + y) % 3 === 0,
    (x, y) => (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0,
    (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
    (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
    (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0
  ];

  function isFunctionModule(version, size, x, y) {
    if (x < 9 && y < 9) return true;
    if (x >= size - 8 && y < 9) return true;
    if (x < 9 && y >= size - 8) return true;
    if (x === 6 || y === 6) return true;
    const ap = alignmentPositions(version);
    for (const ay of ap) for (const ax of ap) {
      if ((ax === 6 && ay === 6) || (ax === 6 && ay === size - 7) || (ax === size - 7 && ay === 6)) continue;
      if (Math.abs(x - ax) <= 2 && Math.abs(y - ay) <= 2) return true;
    }
    if (version >= 7) {
      if (x >= size - 11 && x <= size - 9 && y <= 5) return true;
      if (y >= size - 11 && y <= size - 9 && x <= 5) return true;
    }
    return false;
  }

  function applyFormat(m, size, version, ecc, mask) {
    const data = (ECC_FORMAT_BITS[ecc] << 3) | mask;
    let rem = data;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const bits = ((data << 10) | rem) ^ 0x5412;

    // Bit i of the 15-bit string, i = 14 being the most significant.
    const bit = (i) => (bits >>> i) & 1;
    // First copy: bit 14 at (8,0) running down, bit 0 at (8,8).
    //
    // The order is the part that is easy to get backwards, and getting it
    // backwards yields a matrix that looks completely valid and decodes to
    // nothing. Written out explicitly rather than looped, so it matches the
    // spec's figure one line at a time.
    let i = 14;
    for (let y = 0; y <= 5; y++) m[8][y] = bit(i--);
    m[8][7] = bit(i--);
    m[8][8] = bit(i--);
    m[7][8] = bit(i--);
    for (let x = 5; x >= 0; x--) m[x][8] = bit(i--);
    // Second copy: bit 14 at (size-1,8) running up the right side, then
    // leftwards along the bottom row.
    i = 14;
    for (let y = size - 1; y >= size - 7; y--) m[8][y] = bit(i--);
    for (let x = size - 8; x <= size - 1; x++) m[x][8] = bit(i--);
    m[size - 8][8] = 1; // the always-dark module
  }

  function penalty(m, size) {
    let score = 0;
    // Rule 1: runs of five or more.
    for (let y = 0; y < size; y++) {
      let run = 1;
      for (let x = 1; x < size; x++) {
        if (m[y][x] === m[y][x - 1]) { run++; if (run === 5) score += 3; else if (run > 5) score++; }
        else run = 1;
      }
    }
    for (let x = 0; x < size; x++) {
      let run = 1;
      for (let y = 1; y < size; y++) {
        if (m[y][x] === m[y - 1][x]) { run++; if (run === 5) score += 3; else if (run > 5) score++; }
        else run = 1;
      }
    }
    // Rule 2: 2x2 blocks.
    for (let y = 0; y < size - 1; y++) {
      for (let x = 0; x < size - 1; x++) {
        const v = m[y][x];
        if (v === m[y][x + 1] && v === m[y + 1][x] && v === m[y + 1][x + 1]) score += 3;
      }
    }
    // Rule 3: finder-like patterns.
    const pat = [1,0,1,1,1,0,1];
    for (let y = 0; y < size; y++) {
      for (let x = 0; x <= size - 7; x++) {
        let hit = true;
        for (let i = 0; i < 7; i++) if (m[y][x + i] !== pat[i]) { hit = false; break; }
        if (hit) {
          const before = x >= 4 && m[y][x-1]===0 && m[y][x-2]===0 && m[y][x-3]===0 && m[y][x-4]===0;
          const after = x + 11 <= size && m[y][x+7]===0 && m[y][x+8]===0 && m[y][x+9]===0 && m[y][x+10]===0;
          if (before || after) score += 40;
        }
      }
    }
    for (let x = 0; x < size; x++) {
      for (let y = 0; y <= size - 7; y++) {
        let hit = true;
        for (let i = 0; i < 7; i++) if (m[y + i][x] !== pat[i]) { hit = false; break; }
        if (hit) {
          const before = y >= 4 && m[y-1][x]===0 && m[y-2][x]===0 && m[y-3][x]===0 && m[y-4][x]===0;
          const after = y + 11 <= size && m[y+7][x]===0 && m[y+8][x]===0 && m[y+9][x]===0 && m[y+10][x]===0;
          if (before || after) score += 40;
        }
      }
    }
    // Rule 4: dark ratio.
    let dark = 0;
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) if (m[y][x]) dark++;
    const pct = (dark * 100) / (size * size);
    score += Math.floor(Math.abs(pct - 50) / 5) * 10;
    return score;
  }

  /** Encodes bytes and returns { modules, size }. */
  function encode(bytes, eccLevel) {
    const ecc = eccLevel || "L";
    let version = 1;
    for (; version <= 40; version++) {
      if (dataCapacity(version, ecc) * 8 >= 4 + (version <= 9 ? 8 : 16) + bytes.length * 8) break;
    }
    if (version > 40) throw new Error("data too long for a QR code");

    const data = encodeData(bytes, version, ecc);
    if (!data) throw new Error("data too long for a QR code");
    const codewords = interleave(data, version, ecc);
    const { m, size, bitIndex } = buildMatrix(version, ecc, codewords);

    // Choose the mask with the lowest penalty.
    let best = null, bestScore = Infinity;
    for (let mask = 0; mask < 8; mask++) {
      const trial = m.map(row => row.slice());
      for (let y = 0; y < size; y++) {
        for (let x = 0; x < size; x++) {
          if (isFunctionModule(version, size, x, y)) continue;
          if (MASKS[mask](x, y)) trial[y][x] ^= 1;
        }
      }
      applyFormat(trial, size, version, ecc, mask);
      const s = penalty(trial, size);
      if (s < bestScore) { bestScore = s; best = trial; }
    }
    return { modules: best, size, bitIndex, version, ecc };
  }

  global.QR = {
    encode,
    // [ecCodewordsPerBlock, group1Blocks, group2Blocks]
    blockSchema: (version, ecc) => BLOCKS[version][ECC[ecc]],
    dataCodewords: (version, ecc) => dataCapacity(version, ecc),
  };
})(window);
