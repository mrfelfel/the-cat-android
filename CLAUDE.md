# The Cat — CLAUDE.md

Telegram fork (12.10.1 base) with a custom Cat server, plus a Cafe Bazaar IAB flavor.

## Build environment (macOS)

- **Gradle user home lives on SADDISK**: `~/.gradle` is a symlink to `/Volumes/SADDISK/gradle-home` (~6 GB cache). The internal Mac disk fills up and breaks Gradle writes ("No space left on device") — do NOT remove the symlink or recreate `~/.gradle` as a real directory. If builds fail with disk errors, check `df -h /System/Volumes/Data` first.
- NDK 27.2.12479018, build-tools 36.0.0, compileSdk 36.

## APK builds

```bash
scripts/release-apk-bazaar.sh --arm    # Bazaar flavor, ARM-only (~52 MB) — preferred for sideload
scripts/release-apk-bazaar.sh --fat    # Bazaar flavor, all 4 ABIs
scripts/release-apk-bazaar.sh          # both
scripts/release-apk.sh                 # standalone flavor (Google/invoice billing path)
```

Outputs land in `dist/TheCat-<version>-Bazaar[-ARM]-ZeroHub.apk`. All flavors share `applicationId` (`com.thecat.messenger`) and the release keystore, so installing over an existing copy replaces it in place.

- **Bazaar flavor** (`TMessagesProj_AppBazaar`): Poolakey/Cafe Bazaar in-app billing; identifiable in-app by the "Pending purchases" item in the side (hamburger) menu.
- **Standalone flavor** (`TMessagesProj_App`): no store bridge; Stars/Premium fall through to the invoice/WebView payment path.

## Store billing (R8-sensitive)

The Bazaar store bridge is a lazily-created virtual override: `ApplicationLoader.getStoreBillingBridge()` returns null in the base; the Bazaar `ApplicationLoaderImpl` overrides it to create and cache a `BazaarBillingBridge`. `StoreBilling.get()/isAvailable()` read it via `applicationLoaderInstance`.

**Keep rules for this chain live in the LIBRARY's `TMessagesProj/proguard-rules.pro`** — the library runs its own R8 pass (`minifyReleaseWithR8`) that cannot see app-module subclasses, and has twice constant-folded unkept variants of this chain to null/false (isAvailable() hardcoded false → every store purchase silently fell through to the invoice WebView). App-module keep rules never reach that pass. Do not move or delete the library-side rules.

Verify any billing change on the built APK before shipping: `apkanalyzer dex code --class org.telegram.messenger.billing.StoreBilling <apk>` — `get()` must end in `invoke-virtual ... getStoreBillingBridge` + `move-result`/`areturn`, not a constant null. Also check `javap` on `TMessagesProj/build/intermediates/shrunk_classes/release/minifyReleaseWithR8/shrunkClasses.jar`.
