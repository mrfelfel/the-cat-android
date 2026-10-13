# 🐱 The Cat for Android

**One familiar app. One server you trust.**

The Cat for Android is a multi-server messenger built on a fast, familiar
experience. Use it against the official network, your own private server, or
any community node — each account independent, all in one app.

> 🔗 Built on **MTProto API layer 229**, on top of Telegram for Android `v12.10.1`
> (forked from [owpengram-android-client](https://github.com/owpengram/owpengram-android-client)).

## 🚀 Default server

The Cat server is built into this client — no address to type, no key to paste.
Install the APK, sign in as usual. The server serves its own identity over
`GET /thecat/server-info` (name, description, icon), so the operator can rebrand
without shipping a new app.

| | |
|---|---|
| Host | `62.220.127.238` |
| Port | `30001` |
| Server repo | [iamxvbaba/gramsrv](https://github.com/iamxvbaba/gramsrv) |

## ✨ Multi-server

The multi-server machinery from the OwpenGram fork is kept under the hood:
accounts are scoped per server (peers, files, emoji cache, ids never bleed
across servers), and removing a server removes every account on it.

- Adding a server takes only `host:port` — the app fetches the RSA key, DC id
  and identity itself from `/thecat/server-info` on that port.
- Operators can hand out a **`thecat://addserver?host=...&port=...`** link;
  it carries nothing but the address.

## 🛠️ Build

Windows: run `build-android.bat` — the interactive script guides you through
API credentials, keystore, SDK and Gradle, and remembers answers in
`.thecat-build.local.json` (gitignored). It builds the
`:TMessagesProj_AppStandalone` flavour — the unrestricted, Firebase-free one.

**Requirements:** JDK 17+, Android SDK (compile/target API 36, build-tools
36.0.0, NDK 27.2.12479018), Git. Minimum supported device API is 21
(Android 5.0).

## 📄 License

Based on [Telegram for Android](https://github.com/DrKLO/Telegram) — licensed
under **GNU GPL v2 or later** ([LICENSE](LICENSE)). Multi-server support from
[owpengram-android-client](https://github.com/owpengram/owpengram-android-client),
also GPL-2.0.
