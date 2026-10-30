# Server specification — MTProto traffic masking (backward-compatible)

> Audience: the server side — **TESSMTPROTO** (a proprietary, self-built protocol based precisely on MTProto; per the user, 2026-10-31. Older names like "gram srv" in the docs were wrong and have been removed). Goals: (1) **old clients keep working unchanged**, (2) define **our own signature** that the server also accepts, (3) in the long run make traffic look like real TLS.
> Reference for client behavior: `TMessagesProj/jni/tgnet/Connection.cpp` (lines 459–560 for intro construction; `ConnectionSocket.cpp` lines 149–165 for the hello).

---

## 0. One-line summary

Old clients send a 64-byte intro in which `bytes[56..59] = ef ef ef ef` is fixed, and the server picks the EF framing from those 4 bytes; **that fixed 4-byte selector is the signature DPI uses to kill the traffic**. The core server patch: instead of "only accept ef", treat **any selector** as EF framing — then a new client can randomize those 4 bytes, old clients remain untouched, and MTProto security (AES key inside the intro + handshake) does not change at all.

---

## 1. What an old client actually sends (must not break)

Every connection starts with a 64-byte packet (the obfuscated2 intro):

| Part | Value in the current client |
|---|---|
| `bytes[0..55]` | random (after excluding forbidden patterns: HTTP words like `GET `/`POST`/`HEAD`, and the values `eeeeeeee`/`dddddddd`, …) |
| `bytes[56..59]` | **fixed `ef ef ef ef`** ← the known signature (`Connection.cpp:529`) |
| `bytes[60..63]` | random (or datacenterId when a secret is active — we have none) |
| after the intro | **EF** framing: each packet's length as `len/4` (1 byte up to 127, 4-byte input after), **no extra random padding**; the whole stream is AES-CTR keyed from the intro itself |

Security note: after these 64 bytes the stream is XOR/CTR and DPI **cannot see inside it**. The entire signature surface = this intro. The fact that packets are killed by this structure only (and never after decryption) matches the reported symptom — "constant disconnect + 1-second retries": during a failed handshake the client retries every second (`Connection.cpp:711-718`) and the same signature is killed each time.

## 2. Backward-compatibility principles (must-nots)

1. **Any intro whose selector is `ef ef ef ef` must be processed exactly as today** — v1/EF framing stays byte-for-byte identical.
2. **Never reject a connection because of an unknown selector** — rejecting = breaking old/new clients in trial.
3. **Upper layers stay untouched:** server RSA key, auth_key, ping, `getDifference` — all changes live **below** the intro; the auth logic must not be touched.
4. **Deployment order: server first, client after.** The server accepts both modes first, then the new client ships. If the server rolls back, the new client must **fall back to ef** (this is a client-side requirement — included in the new version).
5. Keep a server-side **feature flag** to disable the new framing within seconds without touching the ef path.

## 3. Core server patch (the base of all four ideas)

```text
Before:
  if (intro[56..59] == ef ef ef ef) → parse EF
  else → reject / unknown error

After:
  if (intro[56..59] == ef ef ef ef) → parse EF        # old clients, unchanged
  else                             → parse EF          # any other selector is EF too
  # + branches for the new schemes (below) added gradually
```

**Why this does not weaken security:** the selector is not part of the AES key; the key derives from all 64 bytes, which both sides share. Anyone who can send an intro could send one before anyway — real handshake success is decided by the MTProto layer inside the encryption, not by these 4 bytes. "Accept any selector" only opens the parse boundary; it opens nothing else.

**Suggested implementation shape:** split selector reading into a `classify(intro)` function: `classify` → ef means v1; anything else → v1 or v2 per the scheme rule (below); parse always follows `classify`'s output. Add counting logs next to it (section 6).

## 4. Our own signature schemes (ideas, cheapest to most expensive)

### Scheme A — random selector (cheapest; immediate pick)
- **Client:** turn `bytes[56..59]` into 4 random bytes (3 lines: the EF branch at lines 528–529).
- **Server:** the core patch in section 3.
- **Result:** the public DPI rule that matches `ef ef ef ef` becomes ineffective. Old clients still send ef and the server still accepts it.
- **Limits:** only defeats fixed-byte rules; general behavior (length/timing) remains observable. But that behavior is exactly what kills the current reports.

### Scheme B — EF-v2: masked length + padding
- Remaining EF fingerprint after randomizing the selector: the `len/4` prefix (small values, no padding → extractable length pattern).
- **v2:** mask each packet's length with a byte derived from the intro itself: `wire = (len/4) XOR mask`, where `mask = intro[7] ^ 0xA5` (or any deterministic function of the intro) — both sides hold the same intro, so it derives with zero extra handshake. Plus 0–256 bytes of random padding like the other framings.
- **Server:** when `classify` says v2 → wrap/unwrap with that rule.
- **Old clients:** ef → v1 with no padding/mask, untouched.

### Scheme C — choose framing from inside the intro (no fixed magic)
- Instead of a discoverable magic: `framing = f(intro)` — e.g. `f = (intro[3] & 1) ? v2 : v1`. The new client sets the suitable bit whenever it wants v2; the server just computes the same function. No fixed 4 bytes anywhere (besides legacy ef, checked first).
- Combined with B: legacy = ef→v1; new = any other selector resolved by `f` → v1/v2.

### Scheme D — fake-TLS masking (long-term, strongest)
- Our client **already has** a hello: class `TlsHello` in `ConnectionSocket.cpp:149` (GREASE, SNI, TLS parameters). The server must understand TLS framing: accept a ClientHello, then unwrap TLS records (`17 03 03 ...`) and process the MTProto inside — exactly what `mtproto-proxy` does with `\xdd`/`\xee` secrets.
- **TESSMTPROTO is self-built** (not mtproxy-derived) ⇒ mtproxy's code cannot be borrowed directly for this part; the record layer (ClientHello acceptance + record unwrap) must be implemented **inside TESSMTPROTO**.
- **Middle path without touching the TESSMTPROTO core:** put an `mtproto-proxy` front with port 443 and a real LE certificate in front of the server — from outside the traffic looks like real TLS, and the front can forward MTProto-based traffic to TESSMTPROTO (assuming TESSMTPROTO accepts plain/obfuscated standard MTProto — it does, since that is what today's client sends).
- **Client prerequisites:** the **one-line GREASE fix** in `ConnectionSocket.cpp:158` (the `grease[i] == grease[i+1]` bug → must be `grease[i-1]`; same fix as upstream) — otherwise the hello has duplicate extensions and an out-of-bounds read, exactly what a strict middlebox kills.

### Scheme E — multiple ports + mirrors (complementary, no protocol change)
- The server should listen on several ports (30001 + e.g. 443/8443/a random port) and the catnet registry should publish all of them. Old clients keep using 30001 (full compatibility). This only helps against **port blocking**, not payload inspection — so it complements A–D, it does not replace them.

## 5. Suggested deployment order

| Stage | Server | Client |
|---|---|---|
| 0 | operations checklist (section 7) + log counters (section 6) | VPN test to confirm DPI is the culprit |
| 1 | core patch (section 3: accept any selector + flag) | — old clients tested unchanged |
| 2 | — | Scheme A (random selector) + ef fallback on failure |
| 3 | EF-v2 (scheme B/C) | same |
| 4 | TLS framing or mtproxy front (scheme D) | GREASE fix + type selection |

**Success metric per stage:** (a) the count of legacy-selector (`ef`) connections must not drop after the server deploy; (b) the client reconnect rate (the 1-second storm) must visibly collapse after stage 2.

## 6. Monitoring and testing

- **Server log counters:** connection counts by selector class (`ef` / other), and by "reached auth" vs "died during intro parse". Compare before/after deploy.
- **Test vectors:** capture one old-client connection (the first 64 bytes) before deploy; after deploy replay those bytes to the new server — they must be accepted exactly.
- **Rollback scenario:** feature flag off → behavior exactly as before; no old client should lose a single connection.
- **Network side:** the reporting user's VPN test — if it connects over VPN, payload-DPI is confirmed as the culprit and stage 2 is the cure.

## 7. Server operations checklist (before any code)

- **idle timeout ≥ 600s** (client ping is ~180s; shorter = periodic disconnects).
- **Prompt, reliable ping replies** (ignoring pings = ~20–40s disconnect cycles).
- **Process stability:** every restart means reconnect + `getDifference` for all clients; look for crashes/restarts in the logs.
- Look in the logs for: **accept without completed init** and **repeated DH handshakes from one IP** (the signature of the client's 1-second storm).

## 8. Open questions (to start server work)

1. ~~What is "gram srv"?~~ **Answered (2026-10-31): TESSMTPROTO — a proprietary, self-built protocol based precisely on MTProto.** Consequence for scheme D: implementing the record layer is new work inside TESSMTPROTO, or the mtproto-proxy front option (above) is used.
2. Where exactly does selector parsing happen today, and does it accept anything other than `ef`?
3. Did the disconnect reports start together with a recent server update/restart?
4. Is a domain/certificate available for scheme D? (For the port-443 option with a real certificate.)
