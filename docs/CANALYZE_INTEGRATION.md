# canalyze integration — install analytics and client diagnostics (2026-11-12)

> **Status:** wired and committed. The library compiles; a release APK was built
> and bytecode-verified (the SDK classes and the crash handler survive R8). **Not
> yet verified on a real device** — no device was attached during the work.
>
> **What this is:** the canalyze SDK is now a dependency of `TMessagesProj`, the
> library every flavor depends on, so all five app modules get install analytics
> and client diagnostics without repeating the wiring. This document records what
> was added, the contracts the client must honour, and what still needs checking
> on a device.

---

## 1. What was added

| file | change |
|---|---|
| `TMessagesProj/libs/canalyze-sdk.aar` | the SDK, ~39 KB, zero runtime dependencies |
| `TMessagesProj/build.gradle` | `api files('libs/canalyze-sdk.aar')` |
| `TMessagesProj/proguard-rules.pro` | `-keep class io.canalyze.sdk.**` |
| `ApplicationLoader.java` | `initCanalyze()` at the end of `onCreate()` |
| `BuildVars.java` | `CANALYZE_APP_KEYS[]`, `CANALYZE_ENDPOINT` |
| `ConnectionsManager.java` | MTProto outcome reported from `sendRequestInternal` |
| `FileLog.java` | `e(Throwable)` reports before its `LOGS_ENABLED` gate |
| `ANRDetector.java` | a frozen UI thread is reported once per freeze |
| `SendMessagesHelper.java` | `retrySendMessage` reports a retry |

The dependency and the init call both live in `TMessagesProj` on purpose: the
five app modules only override `onGetApplicationId()`, so a per-flavor call
would be five places to forget.

## 2. Why the library, not the app modules

`TMessagesProj` is the one module every flavor depends on. Putting the AAR and
the `init` there means:

- one dependency line, not five;
- one init call, not five;
- a new flavor gets analytics automatically.

The app id passed to the SDK is the flavor's own package
(`ApplicationLoader.getApplicationId()`), so each store's data stays separate
while the shared signing key still joins them to one person.

## 3. The keys — read this before rotating

`BuildVars.CANALYZE_APP_KEYS` is an **array**, and the order matters:

```java
public static String[] CANALYZE_APP_KEYS = {
        "ca_771f537e…", // v2-rollout  (current)
        "ca_8c145f18…", // migrated    (revoked)
};
```

The server accepts several keys per app. The SDK tries them in order, stops at
the first that is not `401`, and remembers that index for the life of the
process. A `401` on one key is **not** a failure of the batch — it means that
key is revoked and the next may still be live. Only when every key is rejected
is the batch dropped.

**A rotation therefore produces no 401 at all**, as long as the build carries
both keys. The rollout is:

1. `POST /v1/admin/apps/thecat/keys` — add the new key, old one keeps working.
2. Ship a build with **both** keys, new first.
3. Once the fleet has moved, revoke the old key by its hash.

`POST /v1/admin/apps/{id}/rotate` is the emergency path: it revokes **every**
existing key immediately. Use it only when a key has leaked.

The last active key cannot be revoked — an app with no key can never ingest
again.

## 4. What is collected

Install identity and device signals (the fingerprint), plus diagnostics:

| kind | source |
|---|---|
| `crash` | the default uncaught handler, installed at init |
| `anr` | `ANRDetector`, once per freeze |
| `error` | `FileLog.e(Throwable)` |
| `mtproto` | `ConnectionsManager.sendRequestInternal` — method, error, DC, network |
| `slow` | the same hook, for a request that succeeded but took ≥ 5 s |
| `loop` | the same operation ≥ 20× in a minute |
| `retry` | `SendMessagesHelper.retrySendMessage` |

**Diagnostics are folded before sending.** A retry loop that fired 10,000 times
is one report with `count: 10000`, not 10,000 reports. The server stores that
count and every read sums it. This is the difference between a bug report and a
write flood on the analytics server.

Never collected: contacts, SMS, location, installed package list, MAC, IMEI,
serial, advertising ID.

## 5. R8 — the part that would fail silently

The crash handler is installed on a thread R8 cannot see. Without the keep
rules, a **release** build would strip it and every crash would go unreported,
while the debug build looked fine.

The rules are in `TMessagesProj/proguard-rules.pro`:

```proguard
-keep class io.canalyze.sdk.** { *; }
-keepclassmembers class io.canalyze.sdk.** { *; }
-dontwarn io.canalyze.sdk.**
```

They must stay in the **library's** rules, not an app module's: the library runs
its own R8 pass and cannot see app-module rules. Same failure mode as the Bazaar
billing bridge documented in `CLAUDE.md`.

**Verify on any release APK before shipping:**

```bash
unzip -p app.apk classes.dex | strings | grep -c installCrashHandler   # must be ≥ 1
unzip -p app.apk classes.dex | strings | grep -c io/canalyze/sdk       # must be ≥ 1
```

A `0` means R8 stripped the SDK and diagnostics are dead in that build.

## 6. What was verified, and what was not

**Verified:**

- `:TMessagesProj:compileDebugJavaWithJavac` — clean.
- `:TMessagesProj_App:assembleAfatRelease` — built an 89 MB APK.
- The APK contains `io/canalyze/sdk/{Canalyze,Diagnostics,DeviceSignals}` with
  their original names, and `installCrashHandler`, `flushDiagnosticsBlocking`,
  `manifestCid` are all present — R8 kept them.
- The endpoint and key are in the APK.
- The SDK adds **no** permission; its own manifest is only `minSdkVersion`.
- Server side: a 10,000-iteration loop is stored as one row with `count=10000`
  and the summary sums it correctly; the network cut works
  (`mobile: 47, wifi: 9`).
- The key fallback chain: a revoked key returns `401`, the next key returns
  `202`, and the SDK stops there.

**Not verified:**

- A real crash from a real device reaching the server.
- The MTProto hook firing on real traffic.
- No conflict with any other uncaught-exception handler in the app.

## 7. The Framework menu entry is hidden in production

`SettingsActivity` has a "Framework" row (item id 27) that opens
`CatFrameworkFragment` — a component inventory for development, not a
user-facing feature.

It is gated by `BuildVars.LOGS_ENABLED || BuildVars.DEBUG_PRIVATE_VERSION`, the
same condition as the debug entries below it. In a shipped release with logs off
the row is not added at all, so the fragment is unreachable; in a build with logs
on it is present for testing.

Turning logs on in a release is the existing user-visible setting, so the row
appears for anyone who enables it. That is deliberate: it keeps the entry
reachable without a special build.

## 8. Known issue, unrelated

`:TMessagesProj_App:assembleAfatDebug` fails with a manifest merger conflict:

```
Attribute application@label value=(@string/AppNameBeta) from AndroidManifest_SDK23.xml:32
is also present at [:TMessagesProj] AndroidManifest.xml:127 value=(@string/AppName)
```

This is **pre-existing** — it reproduces with the canalyze changes stashed. The
release build does not take that path. Fixing it is a separate change.

## 9. Where the server side lives

The analytics server is a separate repo at `/Volumes/SADDISK/canalyze`, deployed
at `https://canalyze.cr0.ir`. Its docs:

- `docs/diagnostics.md` — the folding rule and the read API
- `docs/app-keys.md` — multi-key rotation
- `docs/api.md` — every endpoint

Read endpoints need the bootstrap token; the SDK's app key only reaches
`POST /v1/ingest`.

## 10. Checklist for the build agent

1. Build the direct ARM release: `scripts/release-apk.sh --arm`.
2. Run the two `strings` checks in §5 against the produced APK.
3. Install on a device and confirm the app starts (the init is wrapped in a
   catch-all, but a startup regression would still show).
4. Force a crash and confirm it reaches the server:
   `GET /v1/stats/diagnostics?app=thecat&days=1` should show a `crash` row.
5. Confirm normal use produces `mtproto` rows only for failures and slow
   requests — not one per request.
