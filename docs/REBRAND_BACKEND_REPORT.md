# Backend rebrand report — what must be replaced server-side

Date: 2026-10-16 · Branch: `cat/rebrand` · Base: Telegram 12.10.1 fork

The client (this repo) has applied these replacements. The backend must apply **the same rules** to any text it generates/sends itself: the server's langpack, service-message texts, errors, push notifications, webview texts — anywhere the server directly reaches the client/user.

## Replacement map (Rules)

| # | Pattern | Replacement | Scope |
|---|------|---------|-------|
| 1 | `The Cat` | `کت` | **Persian only** (`values-fa`) — «کت پرمیوم», «در کت», «پروفایل کت» |
| 2 | `Telegram` / `telegram` (displayed) | `The Cat` / `the cat` | English (hardcoded + resources) |
| 3 | `Telegram` / `telegram` (displayed) | `کت` | Persian |
| 4 | `Fragment` (displayed, not the URL) | `کت مارکت` | both languages |
| 5 | `TON` (displayed) | `پولک` | both languages |
| 6 | `blockchain` / «بلاکچین» in combination with TON | Persian: «بانک مجازی پولک» · English: English equivalent (suggested: *Polka virtual bank* — final decision is yours) | both languages |

### Exceptions (do not touch)

- **URLs**: `telegram.org`, `core.telegram.org`, `desktop.telegram.org`, `web.telegram.org`, `t.me/...`, `fragment.com/...` — leave untouched (they are live links; if a domain is being changed, prepare DNS/server first, then the client).
- **Technical identifiers** (also untouched in the client): `TelegramWebviewProxy` (mini-app JS bridge — changing it breaks every web app), `window.Telegram.WebView`, `Telegram-Android` user-agent, account type `org.telegram.messenger`, `TelegramConnectionService`, `TelegramMediaSession`, internal storage paths `Pictures/Telegram` (changing them loses existing user data), and the official Telegram server name in `TheCatServers.telegramServer()` (it is the identity of a real server, not a UI brand).
- **`TelegramFeaturesUrl`**: currently `https://t.me/The CatTips[FA]` in both languages (space inside the URL — **already broken**). Either create a new channel and provide the URL, or remove it temporarily.

## What the client did (reference for server reconciliation)

- `values-fa/strings.xml`: **~448 entries** changed (The Cat→کت, Fragment→کت مارکت, TON→پولک, «بلاکچین TON»→«بانک مجازی پولک»).
- `values/strings.xml`: **~283 entries** changed (Telegram→The Cat, Fragment→کت مارکت, TON→پولک). URLs protected.
- Displayable Java hardcodes (full list below).
- Both XML files are valid; no displayed `Telegram`/`TON`/`Fragment` remains (except the `<string name="Fragment">` key name itself, which is only a key).

## What the server must replace?

### 1. Langpack (most important)

If the server serves a langpack (Persian or any other language), every entry the client reads from `values-fa` may be overridden by the server. **If the server langpack is not fixed, the client's Persian changes are ineffective.** Full list of changed keys (exported, ready for handover):

- `REBRAND_fa_changed_keys.txt` — 448 Persian keys
- `REBRAND_en_changed_keys.txt` — 283 English keys

Note: keys with TON in the *name* (`Gift2ExportTON*`, `*TONText`, …) had their **names unchanged** — only their values. The server should also change only the value, not the key (otherwise old clients break).

### 2. Texts the server sends directly (TL-based guess — review)

- **Service messages / text updates** if the server sends prebuilt text instead of letting the client construct it from TL.
- **The only things the client shows the server as raw text**: username/chat titles, bot texts, `payments` results and gifts (gift name, auction description). If these contain "Telegram"/"Fragment"/"TON", only the server can change them.
- **`TL_fragment.collectibleInfo`** (username/phone purchase sheet): texts come from client resources (already replaced); just make sure the `url` returned by the server points at the correct domain (your CatMarket / the real fragment).
- **Error-string messages** like `TONES_SAVED_TOO_MANY` and payment gateway errors — these are not code; if the server sends human-readable text, replace them.
- **Push notification body** if the server builds it itself (rather than fetching from the dialogue).

### 3. Other language translations

The client only swapped `values` (EN) and `values-fa`. The other languages in the repo (`values-ar/de/es/it/ko/nl/pt-rBR/ru/uk`) still contain English "Telegram"/"Fragment"/"TON". Either:
- the server langpack is also fixed for these languages (recommended), or
- the client falls back to English naturally — Android takes missing values from `values/`; but *existing* old values in those files remain.

### 4. Website/deeplinks

- The `fragment.com/ads/topup` and `fragment.com/stars` links remain in client resources (URL). If your CatMarket is the replacement, bring the service up **first**, then change these URLs in a separate commit.
- `telegram.org/...` links (FAQ, TOS, privacy): if you have replacement documents, apply the same pattern; otherwise do not touch.

## Java hardcodes the client changed (reference)

| File | Before | After |
|------|-----|-----|
| `NewContactBottomSheet.java` | `This phone number is not on Telegram.` / `...is on Telegram.` | `... on The Cat.` |
| `PasscodeView.java` | `Unlock to use Telegram` | `Unlock to use The Cat` |
| `LaunchActivity.java` | `This is a Telegram link...` / fallback name `"Telegram"` | `The Cat` |
| `LaunchActivity.java` | `This link can only be opened with an official Telegram account...` | `... official The Cat account...` |
| `ContactsController.java` | `Telegram Profile/Voice Call/Video Call` | `The Cat ...` |
| `ThemePreviewActivity.java` | `Telegram Beta Chat` | `The Cat Beta Chat` |
| `MessagePreviewView.java` | `Subscribe to **Telegram Premium**...` | `**The Cat Premium**...` |
| `LoginActivity.java` | support email body `But Telegram says it's...` | `But The Cat says it's...` |

### Intentionally remaining hardcodes (do not touch / decide later)

- `StarsIntroActivity` / `ChannelMonetizationLayout` / `FragmentUsernameBottomSheet`: the string `"TON"` used as **icon-replaced text** (`ColoredImageSpan` sits on "TON" and shows the gem icon) — the user effectively never reads TON; even if they do, the icon replaces it. Changing these risks breaking the span; only with care and if needed.
- `TheCatServers.java`: `s.name = "Telegram"` for the official Telegram server — the real server's identity.
- Storage paths `Telegram` / account types — upgrade data compatibility.

## Server verification checklist

- [ ] Persian langpack: zero displayed `Telegram`, zero `The Cat` (must be `کت`), zero `Fragment` (must be `کت مارکت`), zero `TON` (must be `پولک`)
- [ ] English langpack: zero displayed `Telegram`, `Fragment`→`کت مارکت`, `TON`→`پولک`
- [ ] telegram.org/fragment.com URLs per the domain decision — either live or replaced
- [ ] Server-side gateway errors / pushes replaced
- [ ] Tips channel name (`TelegramFeaturesUrl`) decided
- [ ] `t.me/The CatTips` — broken URL fixed/removed
