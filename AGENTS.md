# AGENTS.md — The Cat Android

Project-state briefing for AI agents. `CLAUDE.md` has build mechanics and R8 rules; this file is the big picture — what's going on, what we've done, what our commits say, and what to build.

## What this is

The Cat: a censorship-resilient messenger. Telegram-Android **12.10.1** fork (upstream base `62b56a07c`), rebranded from OwpenGram to The Cat, pinned to a **single self-hosted Cat server**. The mission is internet resilience: the client must stay reachable under DNS blocking, SNI filtering, and captive portals.

Maker: Felfel (Mohammad Javad Yahyahi). Ecosystem: Shalgham & Sefr ta Sad.

## Repos

- **This working repo** (`github.com/mrfelfel/thecat`, PRIVATE — keep it private): full history including upstream merge lineage. Active branch: `cat/rebrand`.
- `github.com/mrfelfel/the-cat-android` (public): clean single-commit export for distribution — no upstream/owpengram history, no signing secrets.
- `github.com/mrfelfel/the-cat-network-base` (public, open source): cloned at `network-base/`. Committing to it is fine — it can live here as a git submodule or as the primary source, either way its changes are ours to make. Public by design: everything in it is open, EXCEPT secrets (signing keys never).

## Build priority (user directive, 2026-06-30)

1. **Direct (standalone) ARM is THE build.** `scripts/release-apk.sh --arm` → `dist/TheCat-<ver>-ARM-ZeroHub.apk`. The direct Android ARM release is what matters.
2. Bazaar (`scripts/release-apk-bazaar.sh`) only when a Bazaar build is explicitly needed.
3. Fat (all-ABIs) builds on request.

## What's in the tree right now (working tree, uncommitted)

- **catnet integration** (the-cat-network-base): resilient server-address discovery. `TMessagesProj/libs/catnet.aar` (gomobile, 4 ABIs) + `CatNetResolver.java` wrapper + seed-first wiring in `ApplicationLoader`/`TheCatServers`. Seed-first = hardcoded `DEFAULT_HOST:DEFAULT_PORT` stays the fallback; if discovery resolves, the endpoint swaps in at runtime; if discovery fails the app connects exactly as before — the change can only add resilience. Discovery layers: seed probe → static IPs (GitHub raw) → DoT/DoH → plain UDP DNS → DNS TXT → Apps Script relay; `servers.json` is AES-256-GCM encrypted + Ed25519 signed so no mirror or captive portal can poison addresses.
- **severity dimension** (`store`/`dev`) on the `TMessagesProj_AppStandalone` and `TMessagesProj_AppBazaar` flavor axes: `store` = production applicationId + `THE_CAT_DEV_SERVER=false`; `dev` = `com.thecat.messenger.dev`, dev server, no push (google-services tasks disabled for dev).
- Jalali (Shamsi) calendar for Persian UI — vendored jalaali port + FastDatePrinter hook; wire/storage stay Gregorian.
- Launcher/notification icon refresh, `TheCatChannel` channel-injection asset, `scripts/inject-channels.sh` for per-channel APKs.
- Release scripts: `prop()` falls back to `~/.gradle/gradle.properties` (signing creds live there, never in the repo).

## What we've done recently (2026-06)

- Published the clean public repo `the-cat-android`: one commit, zero owpengram/upstream history, no signing keystore or passwords. Public RSA key + server IP stay (public by nature). `google-services.json` stays — the user's boundary is only the app signing key.
- Stripped internal working MDs (`BAZAAR_*.md`, `REBRAND_*.md`, ...) from the published tree and gitignored them so they don't go out again.
- Implemented **catnet**: built the AAR (pinned x/mobile under disk pressure), wrote the seed-first wrapper, wired it into startup, verified on the built APK (`libgojni.so` + `org.telegram.thecat.catnet.mobile.**` + `go.Seq` survive R8).
- Rebranded client UI strings to The Cat; pinned chat-list header name; header star opens the status dialog directly.
- Removed the multi-server surface and scoped-cache layer (user decision: single Cat server, stock loaders). The 13.0.2 upstream merge was tried and **reverted** (2026-10-15) — stay on 12.10.1.
- Cafe Bazaar IAB (Poolakey) for premium/stars/gifts with offline queue + pending-purchases UI. Standalone flavor keeps the invoice/WebView payment path.

## Commit history (cat/rebrand branch, newest first)

```
3644c8e84 fix: header star opens status dialog directly, enlarge chat-list title
bc2c28817 feat: rebrand client UI strings to The Cat, pin chat-list header name
b4be19a82 feat: remove multi-server surface, stock file loaders, single Cat server
dc554462d Revert "Scope recent custom emoji cache per backend server"
d3a23fc02 Revert "per-server lang packs"
4df3529d3 Revert "updates for server files size limits"
178270d94 Revert "fix for files locations from different servers"
437f82fe6 Revert "fix: scope photo/avatar attach names to server account everywhere"
da088445e chore: update launcher/notification icons, paste Bazaar RSA public key
5be6243ef fix: scope photo/avatar attach names to server account everywhere
9a469ac2c fix: Bazaar crash on startup (R8 mangled cross-module store registration)
952e18827 build: add Bazaar release script, fix R8 false-positive, document builds
a3745f6a0 feat: Cafe Bazaar in-app billing (premium, stars, gifts) with offline queue
33bdd3097 feat: hide server picker and drop Telegram from defaults
acb16866e docs: remove fork/server references from README, add Bazaar IAB report
953a02497 feat: apply Vazir globally and upgrade to Vazirmatn v33.003
0718159d7 feat: Persian language, Vazir font, Cat Room help links and FCM push
fba5f540b fix: complete The Cat branding in launcher, settings, and onboarding
bb0468216 chore: add macOS release build script
9353fc588 feat: rebrand OwpenGram to The Cat
```

The reverts are deliberate: per-server scoping and per-server lang packs were attempted and rolled back on purpose (single Cat server, stock behavior).

## Hard rules (never break these)

- **App signing keystore** (`TMessagesProj/config/release.keystore`) and its passwords must NEVER enter a published repo. Credentials live only in `~/.gradle/gradle.properties`.
- **Zero `owpengram`/`oepwengram` traces** in anything published.
- network-base: never commit `signing-*.key`; operator keys live in `~/.catnet-keys/` mode 0600.
- Do NOT remove the `~/.gradle` → `/Volumes/SADDISK/gradle-home` symlink (internal disk fills up).
- Library-side R8 keep rules in `TMessagesProj/proguard-rules.pro` must not be moved or deleted (the billing chain has been constant-folded to null when they were missing).
- Do not re-add per-server scoped filenames or multi-server UI — that layer was reverted on purpose.

## CI (GitHub Actions, repo `mrfelfel/thecat`)

- **`.github/workflows/release.yml`** — manual run or `v*` tag: builds the direct ARM APK (priority), direct all-ABIs APK, direct AAB, and Bazaar AAB; signs them with the release keystore; verifies catnet (`libgojni.so` + `catnet/mobile` dex classes) in each artifact; publishes a GitHub Release. Trigger: Actions tab (optional tag input) or push a `v*` tag.
- **`.github/workflows/version-bump.yml`** — on every push to `main`, commits `APP_VERSION_CODE += 1` back to `gradle.properties` as a dedicated bot commit (`chore: bump versionCode to N [skip ci]`), because stores reject any submission whose versionCode is not new.
- Signing material lives in repo secrets (created via `gh secret set`): `KEYSTORE_B64` (base64 of `release.keystore`), `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, `RELEASE_STORE_PASSWORD`. These are the ONLY confidential values in the whole project — never echo them, never put them in code.

## Build mechanics

See `CLAUDE.md`. Key quirks: JDK 17–23 only (Android Studio JBR works; Homebrew JDK 27 breaks Gradle 8.11 `:buildSrc`), NDK 27.2.12479018, build-tools 36.0.0, `RELEASE_JAVA_HOME` honored by the release scripts. After heavy builds, stop the Gradle daemon (`./gradlew --stop`) and kill `KotlinCompileDaemon` — together they hold ~5 GB and fill swap on this 8 GB machine.
