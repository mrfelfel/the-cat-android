# گزارش: پرداخت درون‌برنامه‌ای کافه‌بازار برای The Cat

> وضعیت: **طراحی / در انتظار پیاده‌سازی**
> دامنه: کلاینت (Android) + سرور (gram srv)
> هدف: خرید Telegram Premium و ستاره (Stars) از طریق کافه‌بازار، به‌جای گوگل‌پلی (که در ایران فیلتر/ناسازگاره)

---

## ۱. چرا این کار لازمه

خرید درون‌برنامه‌ای فعلی از **Google Play Billing** استفاده می‌کنه. مشکل:
- سرویس‌های گوگل روی موبایل‌دیتای ایران فیلتر یا ناپایدارن
- کاربر ایرانی عملاً قادر به خرید پرمیوم/ستاره نیست

کافه‌بازار جایگزین داخلیه که:
- روی کیف پول / درگاه‌های داخلی کار می‌کنه
- کاربر ایرانی بدون VPN پرداخت می‌کنه

---

## ۲. معماری فعلی (گوگل‌پلی) — مرجع

خرید در تلگرام **دو کانال جداست**:

```
┌──────────────┐   خرید/پول    ┌──────────────┐
│   کلاینت     │ ←───────────→ │ Google Play  │
│ (BillingClient)              │   (Billing)  │
└──────┬───────┘               └──────────────┘
       │ رسید خام (originalJson)
       │ + purpose
       ▼  MTProto
┌──────────────┐   تأیید HTTP  ┌──────────────┐
│ سرور تلگرام  │ ←───────────→ │ Google Play  │
│              │               │  Server API  │
└──────────────┘               └──────────────┘
```

### جریان قدم‌به‌قدم (کد کلاینت)

1. کلاینت `BillingClient.launchBillingFlow` → دیالوگ خرید گوگل باز می‌شه
2. گوگل پول رو برمی‌داره و `Purchase` برمی‌گردونه (`purchaseToken` + `originalJson`)
3. کلاینت این رسید رو از مسیر **MTProto** به سرور می‌فرسته — `TL_payments_assignPlayMarketTransaction`:

```java
// TMessagesProj/.../BillingController.java  (onPurchasesUpdatedInternal)
TL_payments_assignPlayMarketTransaction req = new TL_payments_assignPlayMarketTransaction();
req.receipt = new TL_dataJSON();
req.receipt.data = purchase.getOriginalJson();   // رسید خامِ گوگل
req.purpose  = opayload.second;                  // InputStorePaymentPurpose
accountInstance.getConnectionsManager().sendRequest(req, callback);
```

4. **سرور** رسید رو با Google Play Server API تأیید (verify) می‌کنه
5. در صورت صحت، پرمیوم/ستاره رو فعال می‌کنه و `Updates` برمی‌گردونه

### نکات کلیدی
- **کلاینت هرگز مستقیم با گوگل تأیید نمی‌کنه** — فقط رسید رو به سرور می‌فرسته
- **تأیید صحت پرداخت سمت سرور انجام می‌شه** (anti-fraud)
- `purpose` مشخص می‌کنه پول بابت چی خرج شده

### نوع‌های `InputStorePaymentPurpose`

| TL class | محصول |
|----------|--------|
| `TL_inputStorePaymentPremiumSubscription` | اشتراک پرمیوم (اشتراکی) |
| `TL_inputStorePaymentStarsTopup` | شارژ ستاره (مصرفی) |
| `TL_inputStorePaymentGiftPremium` | هدیه پرمیوم |
| `TL_inputStorePaymentPremiumGiftCode` | کد هدیه پرمیوم |
| `TL_inputStorePaymentStarsGift` | هدیه ستاره |

---

## ۳. معماری پیشنهادی (کافه‌بازار)

همون دو-کانالی حفظ می‌شه، فقط «گوگل» با «بازار» عوض می‌شه:

```
┌──────────────┐   خرید/پول    ┌──────────────┐
│   کلاینت     │ ←───────────→ │ کافه‌بازار    │
│  (Poolakey)  │               │   (app)      │
└──────┬───────┘               └──────────────┘
       │ رسید بازار (PurchaseEntity JSON)
       │ + purpose
       ▼  MTProto
┌──────────────┐   تأیید HTTP  ┌──────────────┐
│ سرور Cat     │ ←───────────→ │ کافه‌بازار    │
│  (gram srv)  │               │ Server API   │
└──────────────┘               └──────────────┘
```

**کلاینت (Android):** `BillingClient` → `Poolakey`. رسید رو هم به‌جای `originalJson` گوگل، `PurchaseEntity` بازار می‌فرسته.

**سرور (gram srv):** باید رسید بازار رو تأیید کنه — **این بخش اصلی کار سروره.**

---

## ۴. سمت سرور (gram srv) — چه چیزی باید پیاده بشه

> این بخش مهم‌ترین قسمت گزارشه. کلاینت بدون این بخش فقط پول رو می‌گیره ولی نمی‌تونه چیزی فعال کنه.
> **وضعیت کلاینت: پیاده‌سازی شد** (فلاو `TMessagesProj_AppBazaar`، فقط پرمیوم). کلاینت رسید رو می‌فرسته و منتظر `Updates` می‌مونه؛ سرور فعلاً متد رو نداره (placeholder).

### ۴.۱. متد MTProto جدید — امضای دقیق

کلاینت از این کلاس استفاده می‌کنه ([TLRPC.java](TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java) `TL_payments_assignBazaarTransaction`):

```
payments.assignBazaarTransaction#62617a01 receipt:JSON purpose:InputStorePaymentPurpose = Updates;
```

- **constructor id** = `0x62617a01` (محلی و غیر-upstream — سرور باید همین رو ثبت کنه)
- `receipt`: رشته‌ی JSON رسید بازار (فرمت دقیق در بخش ۴.۳)
- `purpose`: همون `InputStorePaymentPurpose` موجود (`TL_inputStorePaymentPremiumSubscription` برای پرمیوم)

> نکته: کلاینت بعد از ارسال، منتظر `TLRPC.Updates` می‌مونه. اگه سرور خطا برگردونه، کاربر پیام «Payment could not be confirmed: ...» می‌بینه و **هیچ پرمیومی فعال نمی‌شه** (تأیید نهایی فقط سمت سرور).

### ۴.۲. جریان از دید سرور

```
1. کلاینت → payments.assignBazaarTransaction (receipt + purpose)
2. سرور رسید رو با کلید عمومی RSA بازار تأیید می‌کنه
3. سرور فیلدها رو چک می‌کنه (productId / packageName / amount / ضد-replay)
4. سرور پرمیوم رو بر اساس purpose فعال می‌کنه
5. سرور → Updates (همون آپدیت‌هایی که گوگل برمی‌گردونه)
6. کلاینت processUpdates می‌کنه و UI پرمیوم رو refresh می‌کنه
```

### ۴.۳. فرمت دقیق `receipt` (JSON)

کلاینت این JSON رو در فیلد `receipt` می‌فرسته ([BazaarBillingBridge.buildReceiptJson](TMessagesProj_AppBazaar/src/main/java/org/telegram/messenger/billing/BazaarBillingBridge.java)):

```json
{
  "productId":     "thecat_premium_monthly",
  "orderId":       "GPA.1234-5678-9012-34567",
  "purchaseToken": "abc123def456...",
  "packageName":   "com.thecat.messenger",
  "purchaseTime":  1730000000000,
  "purchaseState": "Purchased",       // PurchaseState.Purchased
  "payload":       "<hex purpose>",   // همون obfuscated purpose (اختیاری برای سرور)
  "dataJson":      "{...}",           // payload خامِ امضاشده‌ی بازار
  "signature":     "MEUCIQ..."        // امضای RSA بازار روی dataJson
}
```

**فیلدهای کلیدی برای تأیید:**
- `dataJson` + `signature`: با کلید عمومی RSA بازار تأیید امضا کن (الگوریتم: `SHA1withRSA` روی بایت‌های UTF-8 رشته‌ی `dataJson`)
- `productId`: با SKU محصول مورد انتظار یکی باشه
- `packageName`: باید `com.thecat.messenger` باشه (جلوگیری از replay بین اپ‌ها)
- `purchaseState`: باید `Purchased` باشه
- `purchaseToken` / `orderId`: برای ضد-replay استفاده کن (هر کدوم فقط یک‌بار بپذیر)

### ۴.۴. SKU های پرمیوم (نگاشت کلاینت)

کلاینت بر اساس `months` انتخاب می‌کنه ([BazaarBillingBridge.skuForOption](TMessagesProj_AppBazaar/src/main/java/org/telegram/messenger/billing/BazaarBillingBridge.java)):

| دوره | SKU پیش‌فرض | purpose |
|------|-------------|---------|
| ماهانه (۱ ماه) | `thecat_premium_monthly` | `TL_inputStorePaymentPremiumSubscription` |
| سالانه (۱۲ ماه) | `thecat_premium_yearly` | `TL_inputStorePaymentPremiumSubscription` |

> اگه سرور در `TL_premiumSubscriptionOption.store_product` مقدار بذاره، کلاینت **اون SKU** رو به‌جای جدول بالا استفاده می‌کنه. پس می‌تونی از سمت سرور SKU دلخواه (مثلاً ۳ ماهه، ۶ ماهه) هم تحمیل کنی.

### ۴.۵. تأیید صحت (Server-Side Verification) — چک‌لیست

سرور بعد از دریافت receipt باید:

1. **کلید عمومی RSA برنامه** رو از پنل بازار بگیر (سربرگ پرداخت درون‌برنامه‌ای → کلید RSA). برای هر برنامه ثابت و یکتا ست.

2. **تأیید امضای `dataJson`**: با کلید عمومی RSA صحت `signature` رو روی `dataJson` چک کن.

3. **(توصیه‌شده) استعلام مستقیم**: در صورت امکان، صحت خرید رو از **Bazaar Developer API** هم بگیر (جلوگیری از جعل/replay). مستندات: <https://developers.cafebazaar.ir/fa/guidelines/in-app-billing>

4. **اعتبارسنجی فیلدها**:
   - `productId` ∈ SKU های مجاز پرمیوم
   - `packageName == com.thecat.messenger`
   - `purchaseState == Purchased`
   - مبلغ (`amount` داخل `dataJson`) با قیمت تعریف‌شده در پنل بازار بخونه

5. **ضد-replay**: هر `purchaseToken`/`orderId` رو **فقط یک‌بار** بپذیر (یه جدول/cache از تراکنش‌های مصرف‌شده نگه دار). اگه قبلاً دیدیش، ردش کن.

6. **فعال‌سازی**: بعد از تأیید، پرمیوم رو بر اساس `purpose` فعال کن (دقیقاً همون منطقی که برای `assignPlayMarketTransaction` داری) و **Updates** برگردون.

### ۴.۶. کلید RSA سمت کلاینت (SecurityCheck)

کلاینت یه بررسی امضای محلی هم می‌تونه داشته باشه (فلگ `BAZAAR_RSA_PUBLIC_KEY` در `BazaarBillingBridge`). الان خالیه (بررسی محلی غیرفعال)، چون **تأیید نهایی سمت سرور انجام می‌شه**. اگه خواستی بررسی محلی رو هم فعال کنی، همون کلید عمومی RSA پنل بازار رو اونجا بذار — ولی این جایگزین تأیید سرور نیست، فقط یه لایه‌ی اضافه‌ست.

### ۴.۷. مدیریت خریدهای مصرفی (ستاره — فاز بعدی)

- ستاره‌ها محصول **مصرفی (INAPP)** هستن
- بعد از فعال‌سازی موفق، سرور باید به کلاینت بگه `consume` کنه (یا کلاینت خودش consume کنه) تا کاربر بتونه دوباره بخره
- پرمیوم **اشتراکیه** و consume نمی‌شه (همون‌طور که در `BazaarBillingBridge` فقط از `subscribeProduct` استفاده شده)

### ۴.۴. جلوگیری از Replay / تقلب

- هر `purchaseToken`/شناسه‌ی تراکنش بازار رو **یک‌بار** بپذیر (یه جدول یا cache از تراکنش‌های مصرف‌شده نگه دار)
- اگه قبلاً دیدیش، ردش کن
- ترجیحاً تراکنش رو با شماره‌ی سفارش (`orderId`) هم لاگ کن

---

## ۵. سمت کلاینت (Android) — خلاصه

- **flavor جدید بازار**: یه ماژول/فلاور جدا (مثلاً `TMessagesProj_AppBazaar`) با `applicationId` ثبت‌شده در بازار
- **افزودن Poolakey**:
  ```gradle
  implementation "com.github.cafebazaar.Poolakey:poolakey:<latest>"
  // + maven { url 'https://jitpack.io' }
  ```
- **جایگزینی کنترلر**: به‌جای `BillingController` (گوگل)، یه `BazaarBillingController` با `Payment`/`Poolakey`
  - `payment.connect` → اتصال به بازار
  - `purchaseProduct`/`subscribeProduct` → شروع خرید
  - بعد از `purchaseSucceed`، `PurchaseEntity` رو به سرور بفرست (متد MTProto جدید)
- **کلید RSA** از پنل بازار در `SecurityCheck.Enable`
- **تأیید نهایی سمت سرور** — کلاینت فقط رسید رو می‌فرسته، تأیید با سروره

> نکته: اگه `applicationId` بازار همون `com.thecat.messenger` باشه، می‌شه همین standalone رو دوبلیکیت کرد و فقط لایه‌ی billing عوض کرد. ولی اگه پکیج‌نیم جدا می‌خوای، flavor مستقل بساز.

---

## ۶. نگاشت محصولات (SKU)

| محصول | نوع بازار | purpose MTProto |
|--------|-----------|-----------------|
| پرمیوم ماهانه | اشتراک ۳۰ روزه | `TL_inputStorePaymentPremiumSubscription` |
| پرمیوم سالانه | اشتراک ۳۶۵ روزه | `TL_inputStorePaymentPremiumSubscription` |
| شارژ ستاره (مثلاً ۱۰۰) | مصرفی (INAPP) | `TL_inputStorePaymentStarsTopup` |
| هدیه پرمیوم | مصرفی | `TL_inputStorePaymentGiftPremium` |

> SKUها رو در **پنل بازار** (سربرگ پرداخت درون‌برنامه‌ای) تعریف کن. شناسه باید با حروف کوچک/عدد شروع بشه و بعد از تعریف قابل تغییر نیست.

---

## ۷. چک‌لیست پیاده‌سازی

### سرور (gram srv) — [ ] در انتظار
- [ ] متد MTProto `payments.assignBazaarTransaction#62617a01` (بخش ۴.۱)
- [ ] دریافت و ذخیره‌ی کلید عمومی RSA بازار
- [ ] تأیید امضای `dataJson` با کلید RSA (بخش ۴.۳/۴.۵)
- [ ] (توصیه‌شده) استعلام مستقیم از Bazaar Developer API
- [ ] اعتبارسنجی productId / packageName / purchaseState / amount
- [ ] جدول/کش ضد-replay برای purchaseToken / orderId
- [ ] فعال‌سازی پرمیوم بر اساس purpose + برگرداندن Updates
- [ ] لاگ شماره سفارش (orderId)

### کلاینت (Android) — [x] پرمیوم + ستاره + هدیه پیاده‌شده
- [x] ماژول فلاو بازار `TMessagesProj_AppBazaar` (applicationId مشترک `APP_PACKAGE`)
- [x] افزودن Poolakey ۲.۲.۰ + jitpack
- [x] لایه‌ی انتزاعی `StoreBillingBridge` / `StoreBilling` در کتابخانه‌ی مشترک
- [x] `BazaarBillingBridge` — پرمیوم (subscribe) + محصولات مصرفی (purchaseProduct + consume بعد از تأیید سرور)
- [x] صف آفلاین `BazaarReceiptQueue` (ارسال خودکار با backoff + consume بعد از تأیید نهایی)
- [x] صفحه‌ی «پرداخت‌های معلق» (دیباگ/عیب‌یابی)
- [x] هوک انتخاب استور در پرمیوم + ستاره + هدیه
- [x] TL `TL_payments_assignBazaarTransaction` + ارسال رسید بازار به سرور
- [ ] تست با اشتراک/محصولات آزمایشی بازار

### پنل بازار — [ ] در انتظار operator
- [ ] ثبت برنامه با applicationId `com.thecat.messenger`
- [ ] تعریف SKU اشتراک پرمیوم: `thecat_premium_monthly` و `thecat_premium_yearly`
- [ ] دریافت کلید عمومی RSA (و قراردادن در `BazaarBillingBridge.BAZAAR_RSA_PUBLIC_KEY` در صورت فعال‌سازی بررسی محلی)
- [ ] (فاز بعدی) تعریف SKU های ستاره (مصرفی)

---

## ۷.۱. بیلد و انتشار (دو فلاو)

این پروژه **دو APK جدا** تولید می‌کنه که `applicationId` و keystore مشترک دارن، ولی لایه‌ی پرداختشون فرق داره:

| فلاو | ماژول | buildType | لایه‌ی پرداخت | اسکریپت |
|------|-------|-----------|----------------|---------|
| Standalone | `:TMessagesProj_AppStandalone` | `release` | گوگل‌پلی / invoice-ربات | `scripts/release-apk.sh` |
| **بازار** | `:TMessagesProj_AppBazaar` | `bazaar` | **Poolakey / کافه‌بازار** | `scripts/release-apk-bazaar.sh` |

### اسکریپت‌ها (macOS)
```bash
# APK مستقل (گوگل/invoice) → dist/TheCat-<ver>-ZeroHub.apk
scripts/release-apk.sh

# APK بازار (پرداخت کافه‌بازار) → dist/TheCat-<ver>-Bazaar-ZeroHub.apk
scripts/release-apk-bazaar.sh

# هر کدوم با --skip-build → فقط دوباره امضا + verify (بدون بیلد مجدد)
scripts/release-apk-bazaar.sh --skip-build
```

هر اسکریپت کارهای زیر رو انجام میده: بیلد release → **امضای مجدد تضمینی** با `apksigner` (امضای خود Gradle قابل‌اعتماد نیست) → verify v1/v2/v3 → کپی به `dist/`. خروجی نهایی با گواهی **ZeroHub** امضا شده.

### نکته‌ی R8 / proguard (فلاوی بازار)
اولین بیلد release بازار با خطای R8 شکست می‌خوره:
```
Missing class org.telegram.tgnet.TLRPC$TL_payments_assignBazaarTransaction
```
این **false-positive** هست — کلاس واقعاً کامپایل و در APK هست. چون فقط فلاوی بازار بهش ارجاع میده (`BazaarReceiptQueue`) و فلاوی standalone از مسیر رسید بازار استفاده نمی‌کنه، R8 موقع minify اون رو resolve نمی‌کنه. راه‌حل: قانون `-dontwarn` در [TMessagesProj_AppBazaar/proguard-rules.pro](TMessagesProj_AppBazaar/proguard-rules.pro) (خودِ R8 همین رو در `missing_rules.txt` تولید کرده).

### ⚠️ رفتار بدون سرور
APK بازار **بدون پیاده‌سازی سرور** هم بیلد و نصب می‌شه، ولی **پرمیوم/ستاره فعال نمی‌کنه**: کلاینت پول رو از کیف پول بازار می‌گیره، رسید رو می‌فرسته، چون سرور متد `assignBazaarTransaction` رو نداره رسید در **صف آفلاین** ذخیره و خودکار دوباره فرستاده می‌شه. کاربر پیام «Payment received…» می‌بینه و خریدش در **صفحه‌ی «پرداخت‌های معلق»** (منوی side-menu) قابل‌ردیابیه. فعال‌شدن واقعی فقط بعد از پیاده‌سازی سرور (بخش ۴/۵ همین گزارش + [BAZAAR_SERVER_TODO.md](BAZAAR_SERVER_TODO.md)) و تعریف SKU در پنل بازار اتفاق می‌افته.


---

## ۸. ملاحظات مهم

1. **کار اصلی سمت سروره.** کلاینت نسبتاً ساده‌ست (تعویض SDK). ولی بدون تأیید رسید بازار روی سرور، پرداخت امن نیست و قابل جعله.

2. **قوانین بازار**: برنامه‌های اشتراک‌فروش باید از سرویس پرداخت خود بازار استفاده کنن و لینک پرداخت خارج از بازار ندن. (اگه پرمیوم جایی خارج از بازار هم فروخته بشه، قیمتش نباید بیشتر از داخل بازار باشه.)

3. **اشتراک همزمان**: بازار فعلاً فقط **یه اشتراک فعال همزمان** به هر کاربر از هر اپ میده. اگه چند سطح پرمیوم داری، منطق upgrade رو خودت مدیریت کن.

4. **تأیید سمت سرور ضروریه**: حتی اگه کلاینت امضا رو چک کنه، حتماً سرور هم تأیید کنه (کلاینت قابل دستکارئه).

5. **محصولات مصرفی**: ستاره‌ها رو حتماً بعد از فعال‌سازی `consume` کن، وگرنه کاربر نمی‌تونه دوباره بخره.

---

## ۹. مراجع

- مستندات رسمی بازار (IAB): <https://developers.cafebazaar.ir/fa/guidelines/in-app-billing>
- معرفی اشتراک: <https://developers.cafebazaar.ir/fa/guidelines/in-app-billing/subscription>
- کتابخانه پولکی: <https://developers.cafebazaar.ir/fa/guidelines/in-app-billing/poolakey>
- نمونه کد پولکی: <https://github.com/PHELAT/Poolakey>
- نمونه اشتراک: <https://github.com/cafebazaar/TrivialDrive>

---

# پیوست: نقشه‌ی کامل سیستم‌های پرداخت تلگرام

> برای اینکه روشن بشه دقیقاً **کدام بخش** باید با کافه‌بازار جایگزین بشه و کدام‌ها دست‌نخورده می‌مونن.

تلگرام **۴ سیستم پرداخت جدا** دارد. درک تفاوت اونا حیاتیه:

## سیستم ۱ — پرداخت استور (Google Play / App Store)

- **کاربرد:** خرید اشتراک پرمیوم، خرید (شارژ) ستاره
- **مسیر:**
  ```
  کلاینت → استور (پول رو برمی‌داره) → سرور تلگرام (MTProto) → تأیید با استور
  ```
- متد MTProto: `payments.assignPlayMarketTransaction`
- **این دقیقاً همون بخشیه که باید با کافه‌بازار جایگزین بشه** (بخش‌های ۳ تا ۵ این گزارش)

## سیستم ۲ — ارز داخلی تلگرام (Stars / XTR) ⭐

- **ستاره یه ارز داخلی خود تلگرامه** (مثل سکه‌ی بازی)، نه پول واقعی
- **یک‌بار** با استور (سیستم ۱) **خریداری/شارژ** می‌شه
- بعد **کاملاً داخل تلگرام خرج** می‌شه — **دیگه استور دخیل نیست، فقط MTProto**
- کاربردها: هدیه، بوست کانال، ارتقای هدیه، پرداخت داخلی
- **نتیجه برای بازار:** فقط «شارژ اولیه‌ی ستاره» به بازار مربوطه. خرج بعدی ستاره ربطی به بازار نداره

## سیستم ۳ — پرداخت ربات / درگاه شخص ثالث (`PaymentFormActivity`)

- **کاربرد:** ربات‌هایی که محصول یا سرویس واقعی می‌فروشن (فروشگاه، رزرو، و...)
- ربات یه `payments.getPaymentForm` می‌ده. تلگرام دو شاخه داره:
  - اگه `native_provider` برابر **stripe** یا **smartglocal** باشه → تلگرام **فرم کارت** رو خودش (بومی) رندر می‌کنه
  - **هر درگاه دیگه‌ای** → **WebView** باز می‌شه و `paymentForm.url` (لینک درگاه تاجر) لود می‌شه
- **پول مستقیم به حسابِ تاجر می‌رسه** — تلگرام فقط واسطه‌ست، نه گیرنده
- تأیید نهایی با `payments.sendPaymentForm`
- **ربطی به بازار نداره** — مال پرداخت‌های تجاری ثالثه

## سیستم ۴ — هدیه (Gifts) 🎁

هدیه دو شکل داره و **منبع پولشون فرق داره**:

### الف) هدیه‌ی ستاره (Star Gift) — از موجودی ستاره
```
[یک‌بار]  خرید ستاره با استور  →  شارژ حساب ستاره      ← سیستم ۱ (بازار)
[چندبار]  ارسال هدیه با موجودی →  خرج ستاره‌ی داخلی     ← سیستم ۲ (MTProto فقط)
```
- کاربر قبلاً ستاره خریده و موجودی داره
- موقع هدیه: `TL_inputInvoiceStarGift` → `payments.getPaymentForm` (نوع `paymentFormStars`) → `payments.sendPaymentForm`
- **پول از موجودی ستاره‌ی داخلی کم می‌شه، نه از کیف پول بازار/گوگل**
- گیرنده می‌تونه قبول کنه، ارتقا بده، یا به کس دیگه‌ای انتقال بده (`TL_inputInvoiceStarGiftTransfer`)

### ب) هدیه‌ی پرمیوم (Premium Gift) — از استور
- معمولاً از مسیر **استور** (سیستم ۱) خریداری می‌شه → یعنی **پول واقعی**
- `TL_inputStorePaymentGiftPremium` → استور → سرور
- **این یکی به بازار مربوطه** (چون پول واقعیه)

## جدول جمع‌بندی

| سیستم | پول از کجا | تأیید | مرتبط با بازار؟ |
|-------|-----------|-------|-----------------|
| پرمیوم / شارژ ستاره | استور (گوگل) | MTProto + استور | ✅ **آره — اینو عوض کن** |
| خرج ستاره (هدیه/بوست) | موجودی ستاره‌ی داخلی | فقط MTProto | ❌ نه — ارز داخلیه |
| پرداخت ربات/تاجر | درگاه تاجر (وب) | MTProto (`sendPaymentForm`) | ❌ نه — درگاه ثالث |
| هدیه‌ی پرمیوم | استور | MTProto + استور | ⚠️ بله (پول واقعی) |
| هدیه‌ی ستاره | موجودی ستاره | فقط MTProto | ❌ بعد از شارژ، نه |

**نتیجه‌ی کلیدی:** با کافه‌بازار فقط **«خرید اولیه‌ی ستاره» و «پرمیوم/هدیه‌ی پرمیوم»** پوشش داده می‌شه. هر خرج بعدیِ ستاره (هدیه، بوست) از موجودی داخلی انجام می‌شه و **دیگه به بازار/گوگل وصل نمی‌شه** — پس تجربه‌ی کاربر بعد از یک‌بار شارژ، کاملاً روان و آفلاین-از-استوره.

