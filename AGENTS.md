# AGENTS.md — The Cat Android

Project-state briefing for AI agents. `CLAUDE.md` has build mechanics and R8 rules; this file is the big picture — what's going on, what we've done, what our commits say, and what to build.

## What this is

The Cat: a censorship-resilient messenger. Telegram-Android **12.10.1** fork (upstream base `62b56a07c`), rebranded as The Cat, pinned to a **single self-hosted Cat server**. The mission is internet resilience: the client must stay reachable under DNS blocking, SNI filtering, and captive portals.

Maker: Felfel (Mohammad Javad Yahyahi). Ecosystem: Shalgham & Sefr ta Sad.

## Repos

- **Published repo** (`github.com/mrfelfel/the-cat-android`): THE public repo — a GitHub **fork of DrKLO/Telegram**, with The Cat's work as a clean patch series on top of upstream `12.10.1`. Everything is public here; the only things never committed are secrets (signing keystore and passwords, operator keys).
- `github.com/mrfelfel/the-cat-network-base` (public, open source): cloned at `network-base/`. Committing to it is fine — it can live here as a git submodule or as the primary source, either way its changes are ours to make. Public by design: everything in it is open, EXCEPT secrets (signing keys never).

## Build priority (user directive, 2026-06-30)

1. **Direct (standalone) ARM is THE build.** `scripts/release-apk.sh --arm` → `dist/TheCat-<ver>-ARM-ZeroHub.apk`. The direct Android ARM release is what matters.
2. Bazaar (`scripts/release-apk-bazaar.sh`) only when a Bazaar build is explicitly needed.
3. Fat (all-ABIs) builds on request.

## What's in the tree right now (working tree, uncommitted)

- **Toman-only money display** (`utils/TheCatMoney.java`): every fiat amount renders as whole tomans ("85,000 Toman" / "تومان") — user directive "فقط تومان", no dollars anywhere. Both formatters (`LocaleController.formatCurrencyString`, `BillingController.formatCurrency`) funnel through it; star counts (XTR) and TON stay in their own units. Rate: app-config `usd_toman_rate` (remote, default 85000 toman/USD) + rough USD cross-table for other currencies. Payment INPUT fields stay in the invoice's own currency (display converts, semantics don't).
- **Vazir everywhere**: AnimatedTextView values, all EditTexts, bulletins/toasts, popup items, spans and remaining `Typeface.SANS_SERIF`/`DEFAULT`/`setTypeface(null)` call sites now use Vazir; plain TextViews get it via theme `fontFamily` (API 26+).
- **catnet integration** (the-cat-network-base): resilient server-address discovery. `TMessagesProj/libs/catnet.aar` (gomobile, 4 ABIs) + `CatNetResolver.java` wrapper + seed-first wiring in `ApplicationLoader`/`TheCatServers`. Seed-first = hardcoded `DEFAULT_HOST:DEFAULT_PORT` stays the fallback; if discovery resolves, the endpoint swaps in at runtime; if discovery fails the app connects exactly as before — the change can only add resilience. Discovery layers: seed probe → static IPs (GitHub raw) → DoT/DoH → plain UDP DNS → DNS TXT → Apps Script relay; `servers.json` is AES-256-GCM encrypted + Ed25519 signed so no mirror or captive portal can poison addresses.
- **severity dimension** (`store`/`dev`) on the `TMessagesProj_AppStandalone` and `TMessagesProj_AppBazaar` flavor axes: `store` = production applicationId + `THE_CAT_DEV_SERVER=false`; `dev` = `com.thecat.messenger.dev`, dev server, no push (google-services tasks disabled for dev).
- Jalali (Shamsi) calendar for Persian UI — vendored jalaali port + FastDatePrinter hook; wire/storage stay Gregorian.
- Launcher/notification icon refresh, `TheCatChannel` channel-injection asset, `scripts/inject-channels.sh` for per-channel APKs.
- Release scripts: `prop()` falls back to `~/.gradle/gradle.properties` (signing creds live there, never in the repo).

## What we've done recently (2026-06)

- Everything project-side is published in the fork (docs included); the boundary is only secrets: signing keystore, passwords, operator keys. `google-services.json` stays (public by nature).
- One public repo only — no export/replication flow; changes land in the fork as patch commits.
- Implemented **catnet**: built the AAR (pinned x/mobile under disk pressure), wrote the seed-first wrapper, wired it into startup, verified on the built APK (`libgojni.so` + `org.telegram.thecat.catnet.mobile.**` + `go.Seq` survive R8).
- Rebranded client UI strings to The Cat; pinned chat-list header name; header star opens the status dialog directly.
- Removed the multi-server surface and scoped-cache layer (user decision: single Cat server, stock loaders). The 13.0.2 upstream merge was tried and **reverted** (2026-10-15) — stay on 12.10.1.
- Cafe Bazaar IAB (Poolakey) for premium/stars/gifts with offline queue + pending-purchases UI. Standalone flavor keeps the invoice/WebView payment path.

## Commit history

The published history is a rebuilt patch series: official Telegram `12.10.1` (`62b56a07c`) followed by The Cat's own commits (build setup, rebrand, fonts/Persian, single server + catnet, Bazaar billing, docs, CI). Nothing from before the rebrand exists in it.

## Hard rules (never break these)

- **App signing keystore** (`TMessagesProj/config/release.keystore`) and its passwords must NEVER enter a published repo. Credentials live only in `~/.gradle/gradle.properties` and the GitHub Actions secrets.
- **Zero traces of the pre-rebrand identity** in anything published.
- network-base: never commit `signing-*.key`; operator keys live in `~/.catnet-keys/` mode 0600.
- Do NOT remove the `~/.gradle` → `/Volumes/SADDISK/gradle-home` symlink (internal disk fills up).
- Library-side R8 keep rules in `TMessagesProj/proguard-rules.pro` must not be moved or deleted (the billing chain has been constant-folded to null when they were missing).
- Do not re-add per-server scoped filenames or multi-server UI — that layer was reverted on purpose.

## CI (GitHub Actions — in this repo)

`release.yml` (manual run or `v*` tag) builds the direct ARM APK (priority), direct all-ABIs APK, direct and Bazaar AABs; signs them with the release keystore; verifies catnet (`libgojni.so` + `catnet/mobile` dex classes) in each artifact; publishes a GitHub Release. `version-bump.yml` bumps `APP_VERSION_CODE` on every push to its `main` as a dedicated bot commit (`chore: bump versionCode to N [skip ci]`), since stores reject any submission whose versionCode is not new. Native builds need `submodules: recursive` (ffmpeg, dav1d, libvpx, libyuv, openh264, xiph, tlottie, jlatexmath).

Signing material lives in this repo's Actions secrets (created via `gh secret set`): `KEYSTORE_B64` (base64 of `release.keystore`), `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, `RELEASE_STORE_PASSWORD`. These are the ONLY confidential values in the whole project — never echo them, never put them in code. To ship a release: push to `main` or run the workflow / push a `v*` tag.

## Build mechanics

See `CLAUDE.md`. Key quirks: JDK 17–23 only (Android Studio JBR works; Homebrew JDK 27 breaks Gradle 8.11 `:buildSrc`), NDK 27.2.12479018, build-tools 36.0.0, `RELEASE_JAVA_HOME` honored by the release scripts. After heavy builds, stop the Gradle daemon (`./gradlew --stop`) and kill `KotlinCompileDaemon` — together they hold ~5 GB and fill swap on this 8 GB machine.
