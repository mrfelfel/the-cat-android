# Paid checkout in the system browser (`webauth://` hand-off)

The direct (non-Bazaar) payment path has no store to bill through: it uses the
project's own Toman checkout, which fronts an Iranian gateway (Zibal, on
Shaparak). The bank's redirect chain does not work inside an embedded webview,
so that checkout is opened in the **system browser** and the buyer is handed
back to the app on a dedicated scheme.

## Flow

```
app  ──► browser picker ──► system browser ──► bank ──► callback page
                                                          │ settles (server)
                                                          └─► webauth://<host>/?from=checkout ──► app
```

1. `PaymentFormActivity` sees a checkout form that carries its own URL and
   skips the webview (`prepareOpenInBrowser`). It shows a one-line explanation
   and, once the page is fully visible, a **browser picker**: one browser opens
   straight away, several are offered in a bottom sheet, none installed shows a
   bulletin. The payer decides which browser sees their bank.
2. The page is launched with `ACTION_VIEW` + `setPackage`, then the request is
   closed behind a confirmation dialog: come back after paying, the purchase is
   confirmed on its own.
3. The bank returns the browser to the server callback, which verifies and
   settles; the callback page auto-returns to the app with
   `webauth://<host>/?from=checkout`.

## Why a second scheme

The app renders payment pages in its own webview. With one scheme it cannot
tell "the buyer came back from a browser" from "a webview navigated", which is
how a half-finished purchase gets reported as confirmed. `webauth` is ignored
in the app's own webview (`PaymentFormActivity.shouldOverrideUrlLoading`,
`LinkManager.handleWebAuth`), so a return through it proves the system browser
was in the path.

*Rejected:* `thecat://` for the return — a webview page could claim it.
*Rejected:* Custom Tabs — still an embedded webview to the bank.
*Rejected:* a signed token in the return URL — the page emitting it already
runs inside the app; the scheme boundary is cheaper and airtight.

## Client pieces

| file | role |
|---|---|
| `AndroidManifest.xml` | registers `webauth` next to `thecat` |
| `LinkManager` | `handleWebAuth` / `handleCheckoutReturn`; refreshes Stars balance, Premium promo and the full self user on return. Nothing is sent to the server — the purchase is already settled |
| `PaymentFormActivity` | `openInBrowser` step, browser picker, one-shot continuation, webview guard |
| `values/strings.xml`, `values-fa/strings.xml` | `PaymentOpenInBrowser*` |

## Server contract

- The callback page emits `webauth://<app-link host>/?from=checkout` and a
  `/payments/terms?form_id=` support link; settlement stays server-side and is
  idempotent.
- The scheme is configurable (`TELESRV_PUBLIC_APP_WEBAUTH_SCHEME`, default
  `webauth`); the client only has to register the same value.

## Not verified

- A real bank payment through this path.
- On device: the picker and the automatic return (the APK built by CI covers
  compile and packaging only).
