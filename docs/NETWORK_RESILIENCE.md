# Network resilience: adaptive timers and reconnect

## The bug this fixes

A handshake on a slow link could never complete. Not "was slow" — could never
complete, at any speed of reconnect:

1. TCP connect gets 8–12 s (`connectTimeout`), so it usually succeeds.
2. The first byte of the server reply arrives, and `Connection::onReceivedData`
   re-arms the timer to `currentTimeout`.
3. `currentTimeout` is **4** (`Connection.h`) and only ever grows inside
   `if (wasConnected && reason == 2)`. A handshake that has not finished has
   `wasConnected == false`, so it **stays 4 s forever**.
4. On a 2G/3G link, the modern handshake (4 states in `Handshake.cpp`, several
   round trips) cannot finish in 4 s. The timer fires, `closeSocket(2)`, the
   handshake is thrown away (`needResendData = true`) and reconnect restarts it
   with a fresh 4 s budget.

The UI shows "Connecting…" and is telling the truth: it reconnects forever. No
amount of waiting helps, because the budget never grows.

Worse, reconnect was a flat **1 s** with **no jitter**. Every device on an
operator that just dropped came back in lockstep.

## The fix, in three layers

### 1. `NetworkProfile` (Java) — what the link actually is

`ApplicationLoader.getCurrentNetworkType()` only ever returned
MOBILE/WIFI/ROAMING, so native could not tell 2G from 5G. `NetworkProfile`
classifies properly across the whole minSdk-21 range:

| API | Source | Why |
| --- | --- | --- |
| 23+ | `NetworkCapabilities` | survives VPNs and multi-network devices; `NetworkInfo` is deprecated since 29 and can return null |
| 21–22 | `NetworkInfo.getSubtype()` | the only option on those levels |
| any | `TelephonyManager.getNetworkType()` | fallback, and the path a VPN may need to reach the real transport |

Result is one of `SLOW_2G`, `MEDIUM_3G`, `FAST`, `UNKNOWN`. `UNKNOWN` behaves as
`FAST` on purpose: a link we failed to measure must not change behaviour.

### 2. `NetPolicy.h` (native) — the numbers, in one place

Previously timeouts were literals scattered through `Connection.cpp`. They now
come from one header with an explicit invariant:

> **The FAST and UNKNOWN columns are byte-identical to the values this build
> shipped before.** A WiFi or LTE device sees no change at all.

|  | FAST/UNKNOWN | 3G | 2G |
| --- | --- | --- | --- |
| connect, next port | 8 s | 15 s | 30 s |
| connect, first attempt | 12 s | 22 s | 45 s |
| handshake | 4 s | 30 s | 60 s |
| established, attempt 1 | 4 s | 8 s | 15 s |
| established cap | 16 s | 30 s | 45 s |
| reconnect cap | 15 s | 30 s | 60 s |

Established connections keep a short budget on **every** profile: a socket that
delivered data and then went silent is dead, not slow.

### 3. Profile change is a reconnect signal

`ConnectionsManager::setNetworkProfile` compares against the current profile and,
when it differs:

- resets the address/port index (`resetAddressAndPortNum`) — the endpoint that
  failed on 2G may be the only one that works on WiFi, and vice versa
- tells every live connection to re-derive its timer
  (`Connection::onNetworkProfileChanged`) and reset its backoff

This is the "check whether it became 2G or 3G and react" requirement: the
profile is read on every connectivity change, and a change is acted on rather
than merely recorded.

## The distinction that mattered most

Handshake and established are different phases with different meanings:

- **handshake**: happens once per connection, and a slow link legitimately needs
  a long budget. Generous.
- **established then silent**: the link is dead. Short budget.

Before this change both used the same timer, which is why a slow link could
never complete a handshake yet a dead socket could linger.

## Jitter

`reconnectDelay` widens **downward only** (up to 40%, halved to 20%), so the
policy value stays a ceiling and jitter can never make a retry more aggressive
than intended. Without it, a carrier-wide drop causes a synchronized stampede
that keeps the server down.

## What was deliberately not changed

- **The request queue.** `requestsQueue` is plain FIFO (a `std::vector` walked
  front to back, cap `MAX_GENERAL_REQUESTS = 480`). Reordering it risks
  `invokeAfter`/`resendAfter` ordering and message sequencing. It is a separate
  change, to be justified by data, not bundled in here.
- **`FileLoader` slow handling** still keys off the legacy `networkSlow` bool,
  which remains in place.

## Verifying

Fast-path invariance is checkable without a device — the harness in this
conversation printed every FAST/UNKNOWN value against the old literal and they
matched exactly. On-device:

- **Slow link**: Android emulator throttled, or a hotspot limited to 2G/3G, or
  macOS Network Link Conditioner. Expect a single long handshake that succeeds,
  instead of a Connect loop.
- **Healthy link**: nothing should change — watch for the same connect timings
  as the previous build.
- **Profile switch**: toggle WiFi↔mobile mid-connect and confirm a reconnect
  with a re-derived timeout, not a stall.

`AUTH_KEY_UNREGISTERED` must not appear in any of these — that would mean the
client reached a server that does not know it.
