# Report: Cafe Bazaar In-App Billing for The Cat

> Status: **Design / awaiting implementation**
> Scope: client (Android) + server (TESSMTPROTO)
> Goal: purchase Telegram Premium and Stars through Cafe Bazaar instead of Google Play (which is filtered/unreliable in Iran)

---

## 1. Why this is needed

The current in-app purchase path uses **Google Play Billing**. The problem:
- Google services are filtered or unreliable on Iranian mobile data
- Iranian users effectively cannot buy Premium/Stars

Cafe Bazaar is the domestic alternative that:
- works with domestic wallets and payment gateways
- lets Iranian users pay without a VPN

---

## 2. Current architecture (Google Play) — reference

Purchases in Telegram have **two separate channels**:

```
┌──────────────┐  purchase/money  ┌──────────────┐
│    Client    │ ←──────────────→ │ Google Play  │
│(BillingClient)                  │  (Billing)   │
└──────┬───────┘                  └──────────────┘
       │ raw receipt (originalJson)
       │ + purpose
       ▼  MTProto
┌──────────────┐  HTTP verify     ┌──────────────┐
│ Telegram     │ ←──────────────→ │ Google Play  │
│ server       │                  │ Server API   │
└──────────────┘                  └──────────────┘
```

### Step-by-step flow (client code)

1. Client: `BillingClient.launchBillingFlow` → Google's purchase dialog opens
2. Google takes the money and returns a `Purchase` (`purchaseToken` + `originalJson`)
3. Client sends this receipt to the server over **MTProto** — `TL_payments_assignPlayMarketTransaction`:

```java
// TMessagesProj/.../BillingController.java  (onPurchasesUpdatedInternal)
TL_payments_assignPlayMarketTransaction req = new TL_payments_assignPlayMarketTransaction();
req.receipt = new TL_dataJSON();
req.receipt.data = purchase.getOriginalJson();   // raw Google receipt
req.purpose  = opayload.second;                  // InputStorePaymentPurpose
accountInstance.getConnectionsManager().sendRequest(req, callback);
```

4. **Server** verifies the receipt against the Google Play Server API
5. On success it activates Premium/Stars and returns `Updates`

### Key points
- **The client never verifies with Google directly** — it only forwards the receipt to the server
- **Payment verification happens server-side** (anti-fraud)
- `purpose` states what the money bought

### `InputStorePaymentPurpose` types

| TL class | Product |
|----------|--------|
| `TL_inputStorePaymentPremiumSubscription` | Premium subscription (recurring) |
| `TL_inputStorePaymentStarsTopup` | Stars top-up (consumable) |
| `TL_inputStorePaymentGiftPremium` | Premium gift |
| `TL_inputStorePaymentPremiumGiftCode` | Premium gift code |
| `TL_inputStorePaymentStarsGift` | Star gift |

---

## 3. Proposed architecture (Cafe Bazaar)

The same two-channel shape is kept; only "Google" is replaced with "Bazaar":

```
┌──────────────┐  purchase/money  ┌──────────────┐
│    Client    │ ←──────────────→ │ Cafe Bazaar  │
│  (Poolakey)  │                  │   (app)      │
└──────┬───────┘                  └──────────────┘
       │ Bazaar receipt (PurchaseEntity JSON)
       │ + purpose
       ▼  MTProto
┌──────────────┐  HTTP verify     ┌──────────────┐
│ Cat server   │ ←──────────────→ │ Cafe Bazaar  │
│ (TESSMTPROTO)│                  │ Server API   │
└──────────────┘                  └──────────────┘
```

**Client (Android):** `BillingClient` → `Poolakey`. The receipt sent is the Bazaar `PurchaseEntity` instead of Google's `originalJson`.

**Server (TESSMTPROTO):** must verify the Bazaar receipt — **this is the main server-side work.**

---

## 4. Server side (TESSMTPROTO) — what must be implemented

> This is the most important part of the report. Without it the client takes the money but cannot activate anything.
> **Client status: implemented** (flavor `TMessagesProj_AppBazaar`, Premium only). The client sends the receipt and waits for `Updates`; the server does not have the method yet (placeholder).

### 4.1. New MTProto method — exact signature

The client uses this class ([TLRPC.java](../TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java) `TL_payments_assignBazaarTransaction`):

```
payments.assignBazaarTransaction#62617a01 receipt:JSON purpose:InputStorePaymentPurpose = Updates;
```

- **constructor id** = `0x62617a01` (local, non-upstream — the server must register exactly this)
- `receipt`: JSON string of the Bazaar receipt (exact format in section 4.3)
- `purpose`: the existing `InputStorePaymentPurpose` (`TL_inputStorePaymentPremiumSubscription` for Premium)

> Note: after sending, the client waits for `TLRPC.Updates`. If the server returns an error, the user sees "Payment could not be confirmed: ..." and **no Premium is activated** (final confirmation is server-side only).

### 4.2. Server-side flow

```
1. Client → payments.assignBazaarTransaction (receipt + purpose)
2. Server verifies the receipt with Bazaar's RSA public key
3. Server checks fields (productId / packageName / amount / anti-replay)
4. Server activates Premium based on purpose
5. Server → Updates (same updates Google would return)
6. Client runs processUpdates and refreshes the Premium UI
```

### 4.3. Exact `receipt` format (JSON)

The client sends this JSON in the `receipt` field ([BazaarBillingBridge.buildReceiptJson](../TMessagesProj_AppBazaar/src/main/java/org/telegram/messenger/billing/BazaarBillingBridge.java)):

```json
{
  "productId":     "thecat_premium_monthly",
  "orderId":       "GPA.1234-5678-9012-34567",
  "purchaseToken": "abc123def456...",
  "packageName":   "com.thecat.messenger",
  "purchaseTime":  1730000000000,
  "purchaseState": "Purchased",       // PurchaseState.Purchased
  "payload":       "<hex purpose>",   // the obfuscated purpose (optional for server)
  "dataJson":      "{...}",           // raw signed Bazaar payload
  "signature":     "MEUCIQ..."        // Bazaar RSA signature over dataJson
}
```

**Key fields for verification:**
- `dataJson` + `signature`: verify with Bazaar's RSA public key (algorithm: `SHA1withRSA` over the UTF-8 bytes of the `dataJson` string)
- `productId`: must match the expected product SKU
- `packageName`: must be `com.thecat.messenger` (prevents replay across apps)
- `purchaseState`: must be `Purchased`
- `purchaseToken` / `orderId`: use for anti-replay (accept each exactly once)

### 4.4. Premium SKUs (client mapping)

The client selects by `months` ([BazaarBillingBridge.skuForOption](../TMessagesProj_AppBazaar/src/main/java/org/telegram/messenger/billing/BazaarBillingBridge.java)):

| Period | Default SKU | purpose |
|------|-------------|---------|
| Monthly (1 month) | `thecat_premium_monthly` | `TL_inputStorePaymentPremiumSubscription` |
| Yearly (12 months) | `thecat_premium_yearly` | `TL_inputStorePaymentPremiumSubscription` |

> If the server sets `TL_premiumSubscriptionOption.store_product`, the client uses **that SKU** instead of the table above. So you can also force a custom SKU (e.g. 3-month, 6-month) from the server.

### 4.5. Server-side verification — checklist

After receiving the receipt the server must:

1. **Get the app's RSA public key** from the Bazaar panel (In-App Billing header → RSA key). It is fixed and unique per app.

2. **Verify the `dataJson` signature**: check `signature` over `dataJson` with the RSA public key.

3. **(Recommended) Direct query**: when possible, also confirm the purchase via the **Bazaar Developer API** (prevents forgery/replay). Docs: <https://developers.cafebazaar.ir/fa/guidelines/in-app-billing>

4. **Field validation**:
   - `productId` ∈ allowed Premium SKUs
   - `packageName == com.thecat.messenger`
   - `purchaseState == Purchased`
   - amount (`amount` inside `dataJson`) matches the price configured in the Bazaar panel

5. **Anti-replay**: accept each `purchaseToken`/`orderId` **only once** (keep a table/cache of consumed transactions). If already seen, reject.

6. **Activation**: after verification, activate Premium according to `purpose` (exactly the logic you already have for `assignPlayMarketTransaction`) and return **Updates**.

### 4.6. Client-side RSA key (SecurityCheck)

The client can also do a local signature check (flag `BAZAAR_RSA_PUBLIC_KEY` in `BazaarBillingBridge`). It is currently empty (local check disabled) because **final verification is server-side**. If you want the local check too, put the Bazaar panel's RSA public key there — but this does not replace server verification, it is only an extra layer.

### 4.7. Consumable purchases (Stars — next phase)

- Stars are a **consumable (INAPP)** product
- After successful activation the server should tell the client to `consume` (or the client consumes itself) so the user can buy again
- Premium is **subscription-based** and is never consumed (as in `BazaarBillingBridge`, only `subscribeProduct` is used)

### 4.4. Replay / fraud prevention

- Accept each `purchaseToken`/Bazaar transaction id **once** (keep a table or cache of consumed transactions)
- If already seen, reject
- Preferably also log the transaction with its order id (`orderId`)

---

## 5. Client side (Android) — summary

- **New Bazaar flavor**: a separate module/flavor (e.g. `TMessagesProj_AppBazaar`) with the `applicationId` registered at Bazaar
- **Add Poolakey**:
  ```gradle
  implementation "com.github.cafebazaar.Poolakey:poolakey:<latest>"
  // + maven { url 'https://jitpack.io' }
  ```
- **Replace the controller**: instead of `BillingController` (Google), a `BazaarBillingController` using `Payment`/`Poolakey`
  - `payment.connect` → connect to Bazaar
  - `purchaseProduct`/`subscribeProduct` → start purchase
  - after `purchaseSucceed`, send the `PurchaseEntity` to the server (new MTProto method)
- **RSA key** from the Bazaar panel in `SecurityCheck.Enable`
- **Final verification is server-side** — the client only sends the receipt

> Note: if Bazaar's `applicationId` is the same `com.thecat.messenger`, you can duplicate the standalone flavor and only swap the billing layer. If you want a separate package name, build an independent flavor.

---

## 6. Product mapping (SKU)

| Product | Bazaar type | MTProto purpose |
|--------|-----------|-----------------|
| Monthly Premium | 30-day subscription | `TL_inputStorePaymentPremiumSubscription` |
| Yearly Premium | 365-day subscription | `TL_inputStorePaymentPremiumSubscription` |
| Stars top-up (e.g. 100) | consumable (INAPP) | `TL_inputStorePaymentStarsTopup` |
| Premium gift | consumable | `TL_inputStorePaymentGiftPremium` |

> Define the SKUs in the **Bazaar panel** (In-App Billing header). The id must start with a lowercase letter/digit and cannot be changed after definition.

---

## 7. Implementation checklist

### Server (TESSMTPROTO) — [ ] pending
- [ ] MTProto method `payments.assignBazaarTransaction#62617a01` (section 4.1)
- [ ] Fetch and store Bazaar's RSA public key
- [ ] Verify the `dataJson` signature with the RSA key (sections 4.3/4.5)
- [ ] (Recommended) Direct query to the Bazaar Developer API
- [ ] Validate productId / packageName / purchaseState / amount
- [ ] Anti-replay table/cache for purchaseToken / orderId
- [ ] Activate Premium per purpose + return Updates
- [ ] Log the orderId

### Client (Android) — [x] Premium + Stars + gifts implemented
- [x] Bazaar flavor module `TMessagesProj_AppBazaar` (shared `APP_PACKAGE` applicationId)
- [x] Poolakey 2.2.0 + jitpack added
- [x] `StoreBillingBridge` / `StoreBilling` abstraction layer in the shared library
- [x] `BazaarBillingBridge` — Premium (subscribe) + consumables (purchaseProduct + consume after server confirmation)
- [x] Offline queue `BazaarReceiptQueue` (auto-send with backoff + consume after final confirmation)
- [x] "Pending purchases" screen (debug/diagnostics)
- [x] Store-selection hook in Premium + Stars + gifts
- [x] TL `TL_payments_assignBazaarTransaction` + sending the Bazaar receipt to the server
- [ ] Test with Bazaar test subscriptions/products

### Bazaar panel — [ ] pending operator
- [ ] Register the app with applicationId `com.thecat.messenger`
- [ ] Define Premium subscription SKUs: `thecat_premium_monthly` and `thecat_premium_yearly`
- [ ] Get the RSA public key (and put it in `BazaarBillingBridge.BAZAAR_RSA_PUBLIC_KEY` if enabling the local check)
- [ ] (Next phase) Define Star SKUs (consumable)

---

## 7.1. Build and release (two flavors)

This project produces **two separate APKs** that share the same `applicationId` and keystore but differ in the payment layer:

| Flavor | Module | buildType | Payment layer | Script |
|------|-------|-----------|----------------|---------|
| Standalone | `:TMessagesProj_AppStandalone` | `release` | Google Play / invoice bot | `scripts/release-apk.sh` |
| **Bazaar** | `:TMessagesProj_AppBazaar` | `bazaar` | **Poolakey / Cafe Bazaar** | `scripts/release-apk-bazaar.sh` |

### Scripts (macOS)
```bash
# Standalone APK (Google/invoice) → dist/TheCat-<ver>-ZeroHub.apk
scripts/release-apk.sh

# Bazaar APK (Cafe Bazaar payments) → dist/TheCat-<ver>-Bazaar-ZeroHub.apk
scripts/release-apk-bazaar.sh

# Either script with --skip-build → only re-sign + verify (no rebuild)
scripts/release-apk-bazaar.sh --skip-build
```

Each script: build release → **guaranteed re-sign** with `apksigner` (Gradle's own signing is not trusted here) → verify v1/v2/v3 → copy to `dist/`. The final output is signed with the **ZeroHub** certificate.

### R8 / proguard note (Bazaar flavor)
The first Bazaar release build fails with an R8 error:
```
Missing class org.telegram.tgnet.TLRPC$TL_payments_assignBazaarTransaction
```
This is a **false positive** — the class is compiled and present in the APK. Only the Bazaar flavor references it (`BazaarReceiptQueue`) and the standalone flavor never takes the Bazaar receipt path, so R8 does not resolve it during minify. Fix: a `-dontwarn` rule in [TMessagesProj_AppBazaar/proguard-rules.pro](../TMessagesProj_AppBazaar/proguard-rules.pro) (R8 itself generated it in `missing_rules.txt`).

### ⚠️ Behavior without the server
The Bazaar APK **builds and installs without the server implementation**, but **does not activate Premium/Stars**: the client takes the money from the Bazaar wallet, sends the receipt, and since the server has no `assignBazaarTransaction` method the receipt is stored in the **offline queue** and re-sent automatically. The user sees "Payment received…" and can track the purchase in the **"Pending purchases" screen** (side menu). Real activation only happens after the server is implemented (sections 4/5 of this report + [BAZAAR_SERVER_TODO.md](BAZAAR_SERVER_TODO.md)) and SKUs are defined in the Bazaar panel.

---

## 8. Important notes

1. **The main work is server-side.** The client is relatively simple (SDK swap). But without server-side verification of Bazaar receipts the payment is insecure and forgeable.

2. **Bazaar rules**: subscription-selling apps must use Bazaar's own payment service and must not link to external payment. (If Premium is also sold outside Bazaar, its price must not be higher than inside Bazaar.)

3. **Single active subscription**: Bazaar currently allows only **one active subscription per user per app**. If you have multiple Premium tiers, manage the upgrade logic yourself.

4. **Server verification is mandatory**: even if the client checks the signature, the server must verify too (the client can be tampered with).

5. **Consumable products**: always `consume` Stars after activation, otherwise the user cannot buy again.

---

## 9. References

- Official Bazaar IAB docs: <https://developers.cafebazaar.ir/fa/guidelines/in-app-billing>
- Subscription intro: <https://developers.cafebazaar.ir/fa/guidelines/in-app-billing/subscription>
- Poolakey library: <https://developers.cafebazaar.ir/fa/guidelines/in-app-billing/poolakey>
- Poolakey code samples: <https://github.com/PHELAT/Poolakey>
- Subscription sample: <https://github.com/cafebazaar/TrivialDrive>

---

# Appendix: complete map of Telegram's payment systems

> To show exactly **which part** must be replaced with Cafe Bazaar and which parts stay untouched.

Telegram has **4 separate payment systems**. Understanding the difference is critical:

## System 1 — Store payments (Google Play / App Store)

- **Use:** buying Premium subscription, buying (topping up) Stars
- **Path:**
  ```
  Client → Store (takes the money) → Telegram server (MTProto) → verify with Store
  ```
- MTProto method: `payments.assignPlayMarketTransaction`
- **This is exactly the part that must be replaced with Cafe Bazaar** (sections 3–5 of this report)

## System 2 — Telegram's internal currency (Stars / XTR) ⭐

- **Stars are Telegram's own in-app currency** (like a game coin), not real money
- They are **bought/topped up once** via the Store (System 1)
- After that they are **spent entirely inside Telegram** — **the Store is no longer involved, only MTProto**
- Uses: gifts, channel boosts, upgrade gifts, internal payments
- **Implication for Bazaar:** only the **initial Stars top-up** relates to Bazaar. Later Star spending has nothing to do with Bazaar

## System 3 — Bot / third-party gateway payments (`PaymentFormActivity`)

- **Use:** bots selling real goods or services (shops, bookings, …)
- A bot provides `payments.getPaymentForm`. Telegram has two branches:
  - if `native_provider` is **stripe** or **smartglocal** → Telegram renders the **card form** natively
  - **any other gateway** → a **WebView** opens and loads `paymentForm.url` (the merchant's payment link)
- **Money goes straight to the merchant's account** — Telegram is only the middleman, not the recipient
- Final confirmation via `payments.sendPaymentForm`
- **No relation to Bazaar** — this is third-party commerce payments

## System 4 — Gifts 🎁

Gifts come in two forms with **different money sources**:

### A) Star Gift — from the Stars balance
```
[once]    buy Stars via Store   → top up Stars balance   ← System 1 (Bazaar)
[repeated] send gift from balance → spend internal Stars  ← System 2 (MTProto only)
```
- The user already bought Stars and has a balance
- On gift: `TL_inputInvoiceStarGift` → `payments.getPaymentForm` (type `paymentFormStars`) → `payments.sendPaymentForm`
- **Money comes from the internal Stars balance, not the Bazaar/Google wallet**
- The recipient can accept, upgrade, or transfer it (`TL_inputInvoiceStarGiftTransfer`)

### B) Premium Gift — from the Store
- usually bought through the **Store** (System 1) → i.e. **real money**
- `TL_inputStorePaymentGiftPremium` → Store → server
- **This one relates to Bazaar** (because it is real money)

## Summary table

| System | Where the money comes from | Verification | Bazaar-related? |
|-------|-----------|-------|-----------------|
| Premium / Stars top-up | Store (Google) | MTProto + Store | ✅ **yes — replace this** |
| Star spending (gifts/boosts) | Internal Stars balance | MTProto only | ❌ no — internal currency |
| Bot/merchant payments | Merchant gateway (web) | MTProto (`sendPaymentForm`) | ❌ no — third-party gateway |
| Premium gift | Store | MTProto + Store | ⚠️ yes (real money) |
| Star gift | Stars balance | MTProto only | ❌ no, after top-up |

**Key conclusion:** Cafe Bazaar only covers **"initial Stars top-up" and "Premium/Premium gifts"**. Every later Star spend (gifts, boosts) happens from the internal balance and **never touches Bazaar/Google again** — so after a single top-up the user experience is smooth and store-free.
