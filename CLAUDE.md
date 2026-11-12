# The Cat — CLAUDE.md

Telegram fork (12.10.1 base) with a custom Cat server, plus a Cafe Bazaar IAB flavor.

## Build environment (macOS)

- **Gradle user home lives on SADDISK**: `~/.gradle` is a symlink to `/Volumes/SADDISK/gradle-home` (~6 GB cache). The internal Mac disk fills up and breaks Gradle writes ("No space left on device") — do NOT remove the symlink or recreate `~/.gradle` as a real directory. If builds fail with disk errors, check `df -h /System/Volumes/Data` first.
- NDK 27.2.12479018, build-tools 36.0.0, compileSdk 36.

## APK builds

**Build priority: the direct (standalone) build is THE priority. Bazaar only when explicitly needed. The direct ARM release is what matters.**

```bash
scripts/release-apk.sh --arm            # <- PRIORITY: direct/standalone, ARM-only (~55 MB)
scripts/release-apk.sh --fat            # direct, all 4 ABIs
scripts/release-apk.sh                  # direct, both
scripts/release-apk-bazaar.sh --arm     # Bazaar flavor — ONLY when a Bazaar build is needed
scripts/release-apk-bazaar.sh           # Bazaar, both
```

Direct outputs land in `dist/TheCat-<version>[-ARM]-ZeroHub.apk` (variants `*StoreStandalone`); Bazaar outputs in `dist/TheCat-<version>-Bazaar[-ARM]-ZeroHub.apk`. All flavors share `applicationId` (`com.thecat.messenger`) and the release keystore, so installing over an existing copy replaces it in place.

CI runs in the **public** repo `mrfelfel/the-cat-android` (`.github/workflows/` there): builds the same artifacts on `v*` tags or manual runs and auto-bumps `APP_VERSION_CODE` on every push to its `main` — releases land on that repo. Details in `AGENTS.md`.

- **Direct / standalone** (`TMessagesProj_AppStandalone`, the priority): no store bridge; Stars/Premium fall through to the invoice/WebView payment path.
- **Bazaar flavor** (`TMessagesProj_AppBazaar`, on demand): Poolakey/Cafe Bazaar in-app billing; identifiable in-app by the "Pending purchases" item in the side (hamburger) menu.

## Store billing (R8-sensitive)

The Bazaar store bridge is a lazily-created virtual override: `ApplicationLoader.getStoreBillingBridge()` returns null in the base; the Bazaar `ApplicationLoaderImpl` overrides it to create and cache a `BazaarBillingBridge`. `StoreBilling.get()/isAvailable()` read it via `applicationLoaderInstance`.

**Keep rules for this chain live in the LIBRARY's `TMessagesProj/proguard-rules.pro`** — the library runs its own R8 pass (`minifyReleaseWithR8`) that cannot see app-module subclasses, and has twice constant-folded unkept variants of this chain to null/false (isAvailable() hardcoded false → every store purchase silently fell through to the invoice WebView). App-module keep rules never reach that pass. Do not move or delete the library-side rules.

## canalyze SDK (R8-sensitive, same failure mode)

`TMessagesProj/libs/canalyze-sdk.aar` is an `api` dependency of the library, and
`ApplicationLoader.initCanalyze()` starts it. Its keep rules are in the
**library's** `proguard-rules.pro` for the same reason as the billing bridge: the
library's own R8 pass cannot see app-module rules, and the crash handler is
installed on a thread R8 cannot see. Without them a release build strips the
handler and every crash goes unreported while debug looks fine.

Verify on any release APK:

```bash
unzip -p app.apk classes.dex | strings | grep -c installCrashHandler   # must be >= 1
unzip -p app.apk classes.dex | strings | grep -c io/canalyze/sdk       # must be >= 1
```

`BuildVars.CANALYZE_APP_KEYS` is an array on purpose — the SDK tries the keys in
order and stops at the first that is not 401, so a rotation never cuts off a
build that carries both. Full details in `docs/CANALYZE_INTEGRATION.md`.

Verify any billing change on the built APK before shipping: `apkanalyzer dex code --class org.telegram.messenger.billing.StoreBilling <apk>` — `get()` must end in `invoke-virtual ... getStoreBillingBridge` + `move-result`/`areturn`, not a constant null. Also check `javap` on `TMessagesProj/build/intermediates/shrunk_classes/release/minifyReleaseWithR8/shrunkClasses.jar`.
