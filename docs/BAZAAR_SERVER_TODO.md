# Server implementation guide — Bazaar (Cafe Bazaar) payments

> **Client status:** ready and compiled (flavor `TMessagesProj_AppBazaar`) — Premium, Stars top-up, Premium gift and Star gift are all implemented. The Bazaar purchase path on the recent APK is **verified** (bytecode inspected; `StoreBilling.isAvailable()` is live and purchases really reach the Poolakey bridge).
>
> **Server status:** none. **Immediate priority:** the server does not return `store_product` in `payments.getStarsTopupOptions` — the client is currently rejected with `This item is not available for purchase yet.` (Stars purchase without a Bazaar SKU). After that, the receipt-confirmation method (section 1) is needed so paid money can be activated.
>
> This file deliberately gives "what you must build" step by step so it can be executed directly.

---

## 0. One-line summary

Two tasks: (1) in the `payments.getStarsTopupOptions` response (and gift options), fill the `store_product` field of every option with the Bazaar consumable SKU; (2) create a new MTProto method `payments.assignBazaarTransaction` that decodes/verifies the signed Bazaar receipt, protects against **replay**, activates the product according to `purpose`, and returns `Updates`.

---

## 1. Method signature (exactly this)

```
payments.assignBazaarTransaction#62617a01 receipt:JSON purpose:InputStorePaymentPurpose = Updates;
```

| Part | Value |
|------|-------|
| name | `payments.assignBazaarTransaction` |
| constructor id | `0x62617a01` |
| `receipt` | type `JSON` (i.e. the `dataJSON` — a raw JSON string) |
| `purpose` | type `InputStorePaymentPurpose` (exists in the layer) |
| response | `Updates` |

TL-scheme sample:
```tl
payments.assignBazaarTransaction#62617a01 receipt:JSON purpose:InputStorePaymentPurpose = Updates;
```

> **Note:** this constructor is a local/non-upstream value. The client sends exactly `0x62617a01` ([TLRPC.java `TL_payments_assignBazaarTransaction`](../TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java)). If you change the constructor you **must sync the client too**, otherwise the server will not recognize the method.

---

## 2. `receipt` format (JSON) the client sends

The client puts this JSON in `receipt` ([BazaarBillingBridge.buildReceiptJson](../TMessagesProj_AppBazaar/src/main/java/org/telegram/messenger/billing/BazaarBillingBridge.java)):

```json
{
  "productId":     "thecat_premium_monthly",
  "orderId":       "GPA.1234-5678-9012-34567",
  "purchaseToken": "aBcDeF123...",
  "packageName":   "com.thecat.messenger",
  "purchaseTime":  1730000000000,
  "purchaseState": "Purchased",
  "payload":       "1d8ad892...hex...",
  "dataJson":      "{\"orderId\":\"...\", ... }",
  "signature":     "MEUCIQ...base64..."
}
```

### Field explanation

| Field | What it is | What for |
|------|-----|---------|
| `dataJson` | Bazaar's raw signed payload (the Bazaar receipt itself) | **verification is impossible without this and `signature`** |
| `signature` | Bazaar's RSA signature over `dataJson` (base64) | authenticity check |
| `productId` | purchased SKU | check the product is allowed |
| `packageName` | app package name | prevent replay across apps |
| `orderId` / `purchaseToken` | unique transaction ids | **anti-replay** |
| `purchaseState` | state (must be `Purchased`) | reject cancelled purchases |
| `purchaseTime` | time (ms) | logging / time window |
| `payload` | the same HEX purpose the client sent | optional; purpose also comes from the method's `purpose` field |

> If `dataJson` is empty, the client's fallback sends Bazaar's `originalJson` directly as `receipt` — i.e. `receipt` is sometimes the raw signed payload itself. Verification logic must tolerate both: first try `dataJson`+`signature` inside the JSON; if absent, treat the whole JSON as the raw payload.

---

## 3. Verification algorithm

### 3.1 RSA public key
Take it from the **Bazaar panel**: "In-App Billing" header → "RSA key".
`https://pishkhan.cafebazaar.ir/apps/<packageName>/in-app-billing`

The key is fixed and unique per app. For `com.thecat.messenger`.

### 3.2 Signature verification
Algorithm: **`SHA1withRSA`** (Bazaar's standard) over the UTF-8 bytes of the `dataJson` string, with the RSA public key, compared against `signature` (base64-decoded).

Pseudocode:
```
key = loadRsaPublicKey(PANEL_PUBLIC_KEY)          // X.509 / SubjectPublicKeyInfo format
sig = Base64.decode(receipt.signature)
ok  = Signature("SHA1withRSA").verify(dataJson.getBytes(UTF_8), sig, key)
if (!ok) → reject("BAD_SIGNATURE")
```

> Some implementations wrongly use SHA256 instead of `SHA1withRSA` for long payloads. Use **exactly SHA1withRSA**, because that is what Bazaar's IAB uses.

### 3.3 Direct query (recommended, anti-forgery hardening)
The signature alone is enough, but to prevent advanced replay/forgery, when possible also confirm via the **Bazaar Developer API**:
<https://developers.cafebazaar.ir/fa/guidelines/in-app-billing>

Do this **after signature verification** (only for receipts whose signature was valid) so you do not overload Bazaar's API.

### 3.4 Field validation (checklist)
All of these must pass, otherwise `reject`:
- [ ] `signature` over `dataJson` is valid (section 3.2)
- [ ] `packageName == "com.thecat.messenger"`
- [ ] `purchaseState == "Purchased"` (or its equivalent in the raw payload)
- [ ] `productId` is one of the allowed Premium SKUs:
      `thecat_premium_monthly` | `thecat_premium_yearly`
      (or any `store_product` you sent yourself in the promo)
- [ ] amount (`amount` inside `dataJson`) matches the price configured in the Bazaar panel

---

## 4. Anti-replay — **most important section (because the client has an offline queue)**

### 4.1 Why it is critical now
The client has a **persistent outbox queue** ([BazaarReceiptQueue](../TMessagesProj_AppBazaar/src/main/java/org/telegram/messenger/billing/BazaarReceiptQueue.java)):
- If the server is down / lacks the method, receipts are stored and automatically re-sent with backoff.
- Meaning **receipts can arrive late, multiple times, and out of order.**
- So the server must accept each transaction **exactly once**, otherwise one purchase could activate Premium several times.

### 4.2 Rules
- Accept each `purchaseToken` (preferred) or `orderId` **only once**.
- Keep a permanent table/cache, e.g.:

```sql
CREATE TABLE bazaar_transactions (
  purchase_token TEXT PRIMARY KEY,
  order_id       TEXT,
  product_id     TEXT,
  user_id        BIGINT,
  account_slot   INT,
  purpose_type   TEXT,
  processed_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
```

- **Operation order:**
  1. Verify signature + fields
  2. **Check whether `purchaseToken` was already processed?** If yes → reject (`DUPLICATE`); **but** if it was already activated for the same purpose, you may return empty/successful `Updates` so the client clears its queue (idempotent).
  3. Otherwise: activate Premium, then INSERT the `purchaseToken`.
- **Transactional commit:** activation and token insert must be in **one DB transaction**, so a crash in the middle cannot activate Premium without recording or record without activating.

> If you return `DUPLICATE` and the client sees an error, that receipt stays in the queue forever and is re-sent every time. Best practice: for a repeat of an already-successful transaction, **return `Updates` (success)** so the client clears it, but **do not activate Premium again**.

---

## 5. Activation + response

After full verification:
1. Activate Premium according to `purpose` (exactly the logic you already have for `assignPlayMarketTransaction`).
   - `TL_inputStorePaymentPremiumSubscription` → activate the Premium subscription (based on the product's `months`: monthly/yearly)
2. Collect the necessary updates (e.g. `updateUserPremium` / related stars) into one `Updates`.
3. Return the **`Updates`**.

Client:
- `response instanceof Updates` → `processUpdates` + refresh Premium UI + **remove that receipt from the queue**.
- Any other response (error/timeout) → the receipt **stays** in the queue and is re-sent later.

---

## 6. purpose types (Premium + Stars + gifts — all implemented client-side)

| purpose | Product | Type | Client status |
|---------|--------|-----|--------------|
| `TL_inputStorePaymentPremiumSubscription` | Premium subscription | subscription (SUBS) | ✅ implemented |
| `TL_inputStorePaymentStarsTopup` | Stars top-up | **consumable (INAPP)** | ✅ implemented |
| `TL_inputStorePaymentGiftPremium` | Premium gift | **consumable (INAPP)** | ✅ implemented |
| `TL_inputStorePaymentStarsGift` | Star gift | **consumable (INAPP)** | ✅ implemented |

The `receipt` format is identical for all; only `purpose` differs. So write the server path **shared** across all of them from the start.

### 6.1 Consumable vs subscription difference for the server
- **Premium (subscription):** after verification, activate/renew the subscription. **`consume` has nothing to do with the server** (subscriptions are not consumed).
- **Stars/gift (consumable):** after verification, issue the balance/gift. **The client itself consumes the product after receiving `Updates`** so the user can buy again. The server does not need to command a consume — the client consumes after successful confirmation (and if it was in the offline queue, also after the final flush confirmation).

> Anti-replay note for consumables is even more critical: since consume only happens after server confirmation, if the server accepts the same receipt twice, both the balance is issued twice and consume runs twice. So accept **every `purchaseToken` exactly once** (section 4).

---

## 7. Premium SKUs

The client selects by `months` ([BazaarBillingBridge.skuForOption](../TMessagesProj_AppBazaar/src/main/java/org/telegram/messenger/billing/BazaarBillingBridge.java)):

| Period | Default SKU |
|------|-------------|
| Monthly (1 month) | `thecat_premium_monthly` |
| Yearly (12 months) | `thecat_premium_yearly` |

> If you set `TL_premiumSubscriptionOption.store_product`, the client uses **that SKU**. So you can also force custom SKUs (3-month, 6-month, …) from the server.

These SKUs must be defined in the **Bazaar panel** as a **subscription**.

### 7.1 Star and gift SKUs (consumable)
For Stars/gifts, the client reads the SKU **directly from `store_product`** on the option (not the monthly table). That is, the server puts the Bazaar product id in `TL_starsTopupOption.store_product` / `TL_starsGiftOption.store_product` / `TL_premiumGiftOption.store_product` and the client buys exactly that.

So define the consumable SKUs in the **Bazaar panel** as a **product (INAPP)** and send those ids from the server in the options. Example:

| Product | Bazaar panel type | SKU (example, server decides) |
|--------|---------------|------------------------------|
| Stars top-up | consumable (INAPP) | `thecat_stars_100`, `thecat_stars_200`, ... |
| Star gift | consumable (INAPP) | `thecat_stars_gift_50`, ... |
| Premium gift | consumable (INAPP) | `thecat_premium_gift_1m`, `thecat_premium_gift_12m`, ... |

> If `store_product` is empty on an option, the client does not consider that option "store-purchasable" and falls back to the invoice/bot path. So for Stars/gifts to be sold from the Bazaar wallet, **`store_product` must be filled**.

### 7.2 Currency contract for Star options (IRT / Toman)

The server should send this in `TL_starsTopupOption` (and gift options):

| Field | Value |
|------|-------|
| `currency` | `"IRT"` (the non-ISO code the client specifically handles) |
| `amount` | price in **full Tomans** (integers, no Rials, no decimals) |

Example: a 1000-star pack for 250,000 Tomans → `stars=1000, currency="IRT", amount=250000`.

The client formats `amount` with `LocaleController.formatNumber` and appends "Toman" from a string resource (e.g. `250,000 Toman`). Do not use `IRR` — the client displays it with ISO currency structure (symbol + two decimals), which is wrong for Tomans.

> Display note: in the Bazaar flavor the client shows the price **directly from these server fields** (unlike the Google Play flavor, which takes the price from Play Billing). So the amount here is exactly what the user sees and must match the price registered in the Bazaar panel.

---

## 8. Logging (for operational debugging)
- Log every transaction (success/rejected) with `orderId` + `purchaseToken` + `userId` + rejection reason.
- The client also has a **"Pending purchases" screen** (side menu in the Bazaar flavor) showing queued receipts and the server's last error — for diagnosing why a purchase was not activated.

---

## 9. Delivery checklist

- [ ] Method `payments.assignBazaarTransaction#62617a01` in the TL-scheme + server-side handler
- [ ] Fetch and configure Bazaar's RSA public key
- [ ] Verify `SHA1withRSA` signature on `dataJson`
- [ ] (Recommended) Direct query to the Bazaar Developer API
- [ ] Validate `productId` / `packageName` / `purchaseState` / amount
- [ ] Anti-replay table (`purchaseToken` unique) + DB transaction
- [ ] Activation per `purpose` + return `Updates`:
  - [ ] Premium (subscription)
  - [ ] Stars top-up (consumable)
  - [ ] Premium gift (consumable)
  - [ ] Star gift (consumable)
- [ ] Idempotent behavior for repeating a successful transaction (clear the client queue without re-activating)
- [ ] Tests: online purchase, offline purchase (queue + auto-send after recovery), and duplicate purchase (both Premium and Stars)

---

## 10. Test scenarios the server must pass

1. **Online:** client sends → server verifies → `Updates` → Premium active + client queue empty.
2. **Offline:** server down → client stores the receipt and shows `onQueued` (money taken). After the server is up, the client auto-sends → activated.
3. **Duplicate:** same receipt arrives twice → first activates, second returns `Updates` without re-activating (idempotent). Premium is **not** renewed twice.
4. **Forged:** wrong `signature` → reject.
5. **Wrong package:** `packageName` other than `com.thecat.messenger` → reject.

---

## 11. Building a test APK (Bazaar flavor)

To test the server side, build the Bazaar APK:
```bash
scripts/release-apk-bazaar.sh          # build + sign + verify → dist/TheCat-<ver>-Bazaar-ZeroHub.apk
scripts/release-apk-bazaar.sh --skip-build   # re-sign only
```
Or directly with Gradle: `./gradlew :TMessagesProj_AppBazaar:assembleAfatBazaar`

- **Without a verifying server:** the purchase happens from the Bazaar wallet but is not activated; the receipt stays in the offline queue and is re-sent automatically. To see it: side menu → "Pending purchases".
- **After the server is implemented:** repeat the same purchase (or press "Resend all" on that screen) so the remaining receipt reaches the server and activates.
- Details of both flavors and the scripts: section 7.1 of [BAZAAR_IAB_REPORT.md](BAZAAR_IAB_REPORT.md).
