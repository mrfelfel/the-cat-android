# Dev flavor must not probe port 2398

## Symptom

On the dev build (`com.thecat.messenger.dev`), restarting the dev server logged the
user out completely — the login screen appeared and the session was gone. It was
not the ordinary "connection lost, reconnecting" state; the account had to sign in
again with an SMS code.

This started after 2026-11-14, when the prod server began listening on 2398.

## Root cause

`Datacenter.h` has a hardcoded fallback port list. It is **not** a DrKLO default —
upstream watched the same line go `{-1, 443, 5222, -1}` → `{-1, 10443, -1, -1}`
(those are Telegram's own alternate ports), and the fork's lineage changed it to
the Cat server's port instead:

| Commit | Author | Value |
| --- | --- | --- |
| `62b56a07c` (DrKLO 12.10.1 baseline) | DrKLO | `{-1, 443, 5222, -1}` |
| `d28ae7cc0` "patch for custom server" | onysd | `{-1, 10443, -1, -1}` |
| `2fcf0c667` "fixes" | onysd | `{-1, 2398, -1, -1}` |

So the list stopped being "Telegram's alternates" and became "the Cat server's
extra port" — which is why it is a *server layout* fact, not a protocol constant,
and why it belongs behind a flag rather than compiled in for every flavor.

```c
const int32_t *defaultPorts = new int32_t[4] {-1, 2398, -1, -1};
```

`getCurrentPort()` uses `address->port` only when the entry is `-1`. So the client
tries, in order: the configured address/port (index 0), then **2398** (index 1),
then the configured port again. `nextAddressOrPort()` increments the index after
each failed connect.

Both flavors compile the same native library, so a dev build also probes 2398.
The dev server advertises `62.220.127.238:30098` and the prod server advertises
`62.220.127.238:30001` — the **same public IP**, different NodePorts. When prod
gained `TELESRV_LISTEN_EXTRA=0.0.0.0:2398` under `hostNetwork: true`, it took
`62.220.127.238:2398` on the host. Before that commit that port was closed and the
probe failed harmlessly.

The resulting chain when the dev pod is replaced (`strategy: Recreate`):

1. index 0 = `:30098` → connection refused (no endpoint behind the NodePort)
2. `nextAddressOrPort()` → index 1
3. `getCurrentPort()` → `2398`, ignoring the configured port
4. `62.220.127.238:2398` answers — it is **prod**
5. prod does not know the dev auth key → transport `-404` → `clearAuthKey(Temp)`
6. temp handshake re-sends `bindTempAuthKey` with an unknown permanent key
7. prod answers `401 AUTH_KEY_UNREGISTERED`
8. `ConnectionsManager.cpp` sees a 401 on a `ConnectionTypeGeneric` request with
   `currentUserId != 0` and calls `delegate->onLogout()`

Step 8 is the whole problem: the client never distinguishes "this server does not
know me" from "I am logged out", so reaching the wrong server destroys the session.

Nothing in the server, the database, or the auth keys was ever wrong. No
`authorizations` row was deleted, no 401 appeared in the dev logs, the temp key was
still valid, and the RSA fingerprint had not changed.

## Decision

Separate the two concerns that had been conflated in `defaultPorts`:

- The 2398 probe is a **compatibility shim for one server layout** (prod serving
  both 30001 and 2398 so old APKs do not stall). It was never correct as a
  universal client behaviour.
- Whether a build probes it is a **flavor property**, not a protocol constant.

So `Datacenter` gained `probePortsEnabled`, threaded from
`ConnectionsManager::applyServerConfig()` down from JNI, and the Java caller passes
`!isDevFlavorBuild()`.

Every place that can put a `Datacenter` into service has to agree:

| Site | Why |
| --- | --- |
| `Datacenter::getCurrentPort` | returns `address->port` instead of `defaultPorts[i]` |
| `Datacenter::nextAddressOrPort` | never advances the port index past 0 |
| `ConnectionsManager::initDatacenters` | the prod-address seed created **before** `applyServerConfig` also gets the flag |
| `ConnectionsManager::loadConfig` | boot restores saved datacenters through a deserialize constructor, not `initDatacenters` |
| `help.getConfig` handler | creates missing DCs before its `serverOptionsLocked` check |
| `applyServerConfig` | the re-mapping loop sets the flag as it rewrites each address |

Every `new Datacenter(...)` in `ConnectionsManager.cpp` must be followed by
`setProbePortsEnabled(probePortsEnabled)`. `grep -n -A2 "new Datacenter("` is the
check — a site without it leaves a window that still probes 2398.

`ConnectionsManager::probePortsEnabled` defaults to `true`, preserving the store
build's existing behaviour exactly. Only the dev flavor changes.

### The boot window, and why the flag is threaded twice

The flag used to arrive only with `applyServerConfig`, which is too late: the
`ConnectionsManager` Java constructor ends by calling `checkConnection()` →
`native_setNetworkAvailable`, and on a network-available transition the native
side schedules `createGenericConnection()->connect()` for every DC. For the
account `ApplicationLoader` touches first, that ran before
`applyServerToAccount`, so the dev build could still open a 2398 socket if its
own port failed inside that window.

So the flag now travels with the **build**: `native_init` takes a trailing
`probePorts` argument, `ConnectionsManager::init` assigns it, and it does so
*before* `loadConfig()` — that call starts the network thread and creates the
datacenters. `applyServerConfig` still carries its own copy, because a runtime
server switch has to be able to change it.

The Java side passes `!ApplicationLoader.isDevServerBuildStatic()`. That method
is deliberately read through the virtual `isDevServerBuild()` override, not a
`BuildConfig` constant: the library's own R8 pass cannot see the app-module
subclass and would fold it to `false`. The keep rules for this chain are in
`TMessagesProj/proguard-rules.pro` (dev-server block) — a release build must
keep them, exactly like the billing bridge.

### Rejected alternatives

- **Stop serving 2398 on prod.** Would fix the collision but reintroduce the ~8 s
  cold-start stall for every already-installed APK whose probe reaches that port
  (index 1 is tried *after* the configured port fails, and the connect timeout is
  8–12 s). Fixing the client is strictly better: the shim keeps working for the
  builds that need it, and stops existing for the build that is harmed by it.
- **Move dev to a different advertised port.** `defaultPorts[1] = 2398` is compiled
  into the APK, so a different dev port would not change the probed port — the
  client would still reach `62.220.127.238:2398`. Would also require a new APK.
- **Give dev its own public IP.** Correct but disproportionate; it changes DNS,
  certificates, and node networking for a problem that is one boolean wide.
- **Patch `defaultPorts` to `{-1, -1, -1, -1}` globally.** Fixes dev but silently
  drops the prod cold-start improvement for old store builds. The flag keeps both.

## Building it in CI

`.github/workflows/release.yml` builds `assembleAarmDevStandalone` alongside the
store variants and publishes it in the release like the others, as:

```
TheCat-<version>-DEV-Standalone-ARM-ZeroHub.apk
```

The `DEV` in the name is the safeguard: it can never be mistaken for the store
APK at a glance. Two other properties keep it safe to publish:

- **different applicationId** (`com.thecat.messenger.dev`), so it installs side
  by side with the store build instead of replacing it
- **dev backend on `:30098`**, not production on `:30001`

Because the wrong-variant failure is invisible until a device rejects the APK,
the CI step asserts both of its symptoms directly: applicationId must be
`com.thecat.messenger.dev`, and the manifest must contain `READ_CALL_LOG` (only
the standalone sourceSet merges it). It also distinguishes "apkanalyzer failed to
run" from "the APK is wrong", so an infrastructure hiccup is not reported as a
broken build.

The dev APK is copied into `dist/` before the shared `dist/*.apk` loop, so it
gets the same signature and catnet checks as every other artifact rather than a
duplicated set of its own.

## Verifying on device

Build the dev flavor, put the dev server into a stopped state, and watch the client:

- Before: dev client visibly stalls, then lands on the login screen.
- After: dev client stays on `:30098` and reconnects when the server returns. No
  logout, and `AUTH_KEY_UNREGISTERED` must not appear anywhere in the cycle.

Include the boot window: start the client with the dev server stopped and
confirm from the network side that no socket to `2398` is opened during the
first seconds, not just after the app has settled. Prod behaviour is unchanged
— the store build still probes 2398 first, by design (`probePortsEnabled`
defaults to `true` and `native_init` is passed `true` for it).
