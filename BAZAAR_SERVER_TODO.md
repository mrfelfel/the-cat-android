# راهنمای پیاده‌سازی سرور — پرداخت بازار (Cafe Bazaar)

> **وضعیت کلاینت:** آماده و کامپایل‌شده (فلاو `TMessagesProj_AppBazaar`، فقط پرمیوم).
> **وضعیت سرور:** نداره. تا وقتی این متد پیاده نشه، خریدِ بازار پول رو می‌گیره ولی پرمیوم فعال نمی‌شه (کلاینت رسید رو در صف آفلاین نگه می‌داره و خودکار دوباره می‌فرسته).
>
> این فایل عمداً «چی باید بسازی» رو قدم‌به‌قدم می‌ده تا مستقیم اجرا بشه.

---

## ۰. خلاصه‌ی یک‌خطی

یک متد MTProto جدید `payments.assignBazaarTransaction` بساز که رسید امضاشده‌ی بازار رو رمزگشایی/تأیید کنه، در برابر **replay** محافظت کنه، پرمیوم رو بر اساس `purpose` فعال کنه و `Updates` برگردونه.

---

## ۱. امضای متد (دقیقاً همین)

```
payments.assignBazaarTransaction#62617a01 receipt:JSON purpose:InputStorePaymentPurpose = Updates;
```

| قسمت | مقدار |
|------|-------|
| نام | `payments.assignBazaarTransaction` |
| constructor id | `0x62617a01` |
| `receipt` | نوع `JSON` (یعنی همون `dataJSON` — یه رشته‌ی JSON خام) |
| `purpose` | نوع `InputStorePaymentPurpose` (موجود در لایه) |
| پاسخ | `Updates` |

نمونه‌ی TL-scheme:
```tl
payments.assignBazaarTransaction#62617a01 receipt:JSON purpose:InputStorePaymentPurpose = Updates;
```

> **نکته:** این constructor یه مقدار محلی/غیر-upstream هست. کلاینت همین `0x62617a01` رو می‌فرسته ([TLRPC.java `TL_payments_assignBazaarTransaction`](TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java)). اگه constructor رو عوض کردی، **باید کلاینت رو هم همگام کنی** وگرنه سرور متد رو نمی‌شناسه.

---

## ۲. فرمت `receipt` (JSON) که کلاینت می‌فرسته

کلاینت این JSON رو در `receipt` می‌ذاره ([BazaarBillingBridge.buildReceiptJson](TMessagesProj_AppBazaar/src/main/java/org/telegram/messenger/billing/BazaarBillingBridge.java)):

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

### توضیح فیلدها

| فیلد | چیه | برای چی |
|------|-----|---------|
| `dataJson` | payload خامِ امضاشده‌ی بازار (خودِ رسیدِ Bazaar) | **بدون این و `signature` تأیید ممکن نیست** |
| `signature` | امضای RSA بازار روی `dataJson` (base64) | تأیید صحت |
| `productId` | SKU خریداری‌شده | چک کردن محصول مجاز |
| `packageName` | پکیج‌نیم اپ | جلوگیری از replay بین اپ‌ها |
| `orderId` / `purchaseToken` | شناسه‌های یکتای تراکنش | **ضد-replay** |
| `purchaseState` | وضعیت (باید `Purchased` باشه) | رد کردن خرید لغو شده |
| `purchaseTime` | زمان (ms) | لاگ / محدوده‌ی زمانی |
| `payload` | همون purpose-HEX که کلاینت فرستاد | اختیاری؛ purpose از فیلد `purpose` متد هم میاد |

> اگه `dataJson` خالی بود، کلاینت در جایگزین خودش `originalJson` بازار رو به‌عنوان `receipt` فرستاده — یعنی `receipt` گاهی مستقیم همون payload امضاشده‌ست. منطق تأیید باید هر دو حالت رو تحمل کنه: اول سعی کن `dataJson`+`signature` داخل JSON باشه؛ نبود، کل JSON رو به‌عنوان payload خام در نظر بگیر.

---

## ۳. الگوریتم تأیید (Verification)

### ۳.۱ کلید عمومی RSA
از **پنل بازار** بگیر: سربرگ «پرداخت درون‌برنامه‌ای» → «کلید RSA».
`https://pishkhan.cafebazaar.ir/apps/<packageName>/in-app-billing`

این کلید برای هر برنامه ثابت و یکتا ست. برای `com.thecat.messenger`.

### ۳.۲ تأیید امضا
الگوریتم: **`SHA1withRSA`** (استاندارد بازار) روی بایت‌های UTF-8 رشته‌ی `dataJson`، با کلید عمومی RSA، و مقایسه با `signature` (base64-decode شده).

شبه‌کد:
```
key = loadRsaPublicKey(PANEL_PUBLIC_KEY)          // формат X.509 / SubjectPublicKeyInfo
sig = Base64.decode(receipt.signature)
ok  = Signature("SHA1withRSA").verify(dataJson.getBytes(UTF_8), sig, key)
if (!ok) → reject("BAD_SIGNATURE")
```

> بعضی پیاده‌سازی‌ها `SHA1withRSA` رو برای payload بلند با SHA256 اشتباه می‌کنن. **دقیقاً SHA1withRSA** رو استفاده کن، چون همون چیزیه که IAB بازار استفاده می‌کنه.

### ۳.۳ استعلام مستقیم (توصیه‌شده، تقویت در برابر جعل)
امضا به‌تنهایی کافیه ولی برای جلوگیری از replay/جعل پیشرفته، در صورت امکان تأیید رو از **Bazaar Developer API** هم بگیر:
`<https://developers.cafebazaar.ir/fa/guidelines/in-app-billing>`

این کار رو **بعد از تأیید امضا** انجام بده (فقط برای رسیدهایی که امضاشون درست بود) تا بار زیادی به API بازار نزنی.

### ۳.۴ اعتبارسنجی فیلدها (چک‌لیست)
همه‌ی این‌ها باید رد بشن، وگرنه `reject`:
- [ ] `signature` روی `dataJson` درست باشه (بند ۳.۲)
- [ ] `packageName == "com.thecat.messenger"`
- [ ] `purchaseState == "Purchased"` (یا معادلش در payload خام)
- [ ] `productId` یکی از SKU های مجاز پرمیوم باشه:
      `thecat_premium_monthly` | `thecat_premium_yearly`
      (یا هر `store_product` که خودت در promo فرستادی)
- [ ] مبلغ (`amount` داخل `dataJson`) با قیمت تعریف‌شده در پنل بازار بخونه

---

## ۴. ضد-replay — **مهم‌ترین بخش (چون کلاینت صف آفلاین داره)**

### ۴.۱ چرا الان حیاتیه
کلاینت یه **صف outbox پایدار** داره ([BazaarReceiptQueue](TMessagesProj_AppBazaar/src/main/java/org/telegram/messenger/billing/BazaarReceiptQueue.java)):
- اگه سرور آفلاینه/متد رو نداره، رسید ذخیره و خودکار با backoff دوباره فرستاده می‌شه.
- یعنی **رسیدها ممکنه با تأخیر، چندبار، و خارج از ترتیب برسن.**
- پس سرور باید هر تراکنش رو **دقیقاً یک‌بار** بپذیره، وگرنه یه خرید ممکنه چند بار پرمیوم فعال کنه.

### ۴.۲ قواعد
- هر `purchaseToken` (ترجیحاً) یا `orderId` رو **فقط یک‌بار** بپذیر.
- یه جدول/cache دائم نگه دار، مثلاً:

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

- **ترتیب عملیات:**
  1. تأیید امضا + فیلدها
  2. **چک کن آیا `purchaseToken` قبلاً پردازش شده؟** اگه آره → رد کن (`DUPLICATE`)؛ **ولی** اگه برای همون purpose قبلاً فعال شده، می‌تونی `Updates` خالی/موفق برگردونی تا کلاینت صف رو پاک کنه (idempotent).
  3. در غیر این صورت: پرمیوم رو فعال کن، سپس `purchaseToken` رو INSERT کن.
- **commit تراکنشی:** فعال‌سازی پرمیوم و ثبت توکن باید در **یک تراکنش DB** باشن، تا اگه وسطش کراشید، نه پرمیوم بدون ثبت فعال بشه نه ثبت بدون پرمیوم.

> اگه `DUPLICATE` برگردونی و کلاینت خطا ببینه، اون رسید برای همیشه در صف می‌مونه و هر بار دوباره می‌فرسته. پس بهترین کار: برای تکرارِ همون تراکنشِ موفق، **`Updates` (موفق) برگردون** تا کلاینت پاکش کنه، ولی **پرمیوم رو دوباره فعال نکن**.

---

## ۵. فعال‌سازی + پاسخ

بعد از تأیید کامل:
1. بر اساس `purpose` پرمیوم رو فعال کن (دقیقاً همون منطقی که برای `assignPlayMarketTransaction` داری).
   - `TL_inputStorePaymentPremiumSubscription` → فعال‌سازی اشتراک پرمیوم (بر اساس `months` محصول: ماهانه/سالانه)
2. آپدیت‌های لازم (مثلاً `updateUserPremium` / ستاره‌های مربوطه) رو در یه `Updates` جمع کن.
3. **`Updates`** رو برگردون.

کلاینت:
- `response instanceof Updates` → `processUpdates` + refresh UI پرمیوم + **پاک کردن اون رسید از صف**.
- هر پاسخ دیگه (خطا/timeout) → رسید در صف **می‌مونه** و بعداً دوباره فرستاده می‌شه.

---

## ۶. انواع purpose (پرمیوم + ستاره + هدیه — همه از سمت کلاینت پیاده‌شده)

| purpose | محصول | نوع | وضعیت کلاینت |
|---------|--------|-----|--------------|
| `TL_inputStorePaymentPremiumSubscription` | اشتراک پرمیوم | اشتراکی (SUBS) | ✅ پیاده‌شده |
| `TL_inputStorePaymentStarsTopup` | شارژ ستاره | **مصرفی (INAPP)** | ✅ پیاده‌شده |
| `TL_inputStorePaymentGiftPremium` | هدیه پرمیوم | **مصرفی (INAPP)** | ✅ پیاده‌شده |
| `TL_inputStorePaymentStarsGift` | هدیه ستاره | **مصرفی (INAPP)** | ✅ پیاده‌شده |

فرمت `receipt` برای همه یکیه؛ فقط `purpose` فرق داره. پس سرور رو از اول **مشترک** برای همه بنویس.

### ۶.۱ تفاوت مصرفی vs اشتراکی برای سرور
- **پرمیوم (اشتراکی):** بعد از تأیید، اشتراک رو فعال/تمدید کن. **consume اصلاً ربطی به سرور نداره** (اشتراک مصرف نمی‌شه).
- **ستاره/هدیه (مصرفی):** بعد از تأیید، موجودی/هدیه رو صادر کن. **کلاینت خودش بعد از دریافت `Updates` محصول رو consume می‌کنه** تا کاربر بتونه دوباره بخره. سرور لازم نیست به کلاینت دستور consume بده — کلاینت بعد از تأییدِ موفق consume می‌کنه (و اگه در صف آفلاین باشه، بعد از تأییدِ نهاییِ flush هم consume می‌کنه).

> نکته‌ی ضد-replay برای مصرفی‌ها حیاتی‌تره: چون consume فقط بعد از تأیید سرور انجام می‌شه، اگه سرور همون رسید رو دوبار بپذیره، هم موجودی دوبار صادر می‌شه هم consume دوبار انجام. پس **هر `purchaseToken` دقیقاً یک‌بار** بپذیر (بخش ۴).

---

## ۷. SKU های پرمیوم

کلاینت بر اساس `months` انتخاب می‌کنه ([BazaarBillingBridge.skuForOption](TMessagesProj_AppBazaar/src/main/java/org/telegram/messenger/billing/BazaarBillingBridge.java)):

| دوره | SKU پیش‌فرض |
|------|-------------|
| ماهانه (۱ ماه) | `thecat_premium_monthly` |
| سالانه (۱۲ ماه) | `thecat_premium_yearly` |

> اگه در `TL_premiumSubscriptionOption.store_product` مقدار بذاری، کلاینت **اون SKU** رو استفاده می‌کنه. پس می‌تونی SKU دلخواه (۳ ماهه، ۶ ماهه، ...) هم از سمت سرور تحمیل کنی.

این SKU ها رو باید در **پنل بازار** به‌عنوان **اشتراک** تعریف کنی.

### ۷.۱ SKU های ستاره و هدیه (مصرفی)
برای ستاره/هدیه، کلاینت SKU رو **مستقیم از `store_product`** روی option می‌خونه (نه جدول ماهانه). یعنی سرور در `TL_starsTopupOption.store_product` / `TL_starsGiftOption.store_product` / `TL_premiumGiftOption.store_product` شناسه‌ی محصول بازار رو می‌ذاره و کلاینت همون رو می‌خره.

پس SKU های مصرفی رو توی **پنل بازار** به‌عنوان **محصول (INAPP)** تعریف کن و همون شناسه‌ها رو سرور در option ها بفرست. مثال:

| محصول | نوع پنل بازار | SKU (مثال، سرور تعیین می‌کنه) |
|--------|---------------|------------------------------|
| شارژ ستاره | مصرفی (INAPP) | `thecat_stars_100`, `thecat_stars_200`, ... |
| هدیه ستاره | مصرفی (INAPP) | `thecat_stars_gift_50`, ... |
| هدیه پرمیوم | مصرفی (INAPP) | `thecat_premium_gift_1m`, `thecat_premium_gift_12m`, ... |

> اگه `store_product` روی یه option خالی باشه، کلاینت اون گزینه رو «خریدنی از استور» نمی‌دونه و به مسیر invoice/ربات میره. پس برای اینکه ستاره/هدیه از کیف پول بازار فروخته بشه، **باید `store_product` پر باشه**.

---

## ۸. لاگ‌گیری (برای دیباگ عملیاتی)
- هر تراکنش (موفق/ردشده) رو با `orderId` + `purchaseToken` + `userId` + دلیل رد لاگ کن.
- کلاینت هم یه **صفحه‌ی «پرداخت‌های معلق»** داره (از منوی side-menu در فلاوی بازار) که رسیدهای منتظر و خطای آخرِ سرور رو نشون میده — برای عیب‌یابیِ اینکه چرا یه خرید فعال نشده.

---

## ۹. چک‌لیست تحویل

- [ ] متد `payments.assignBazaarTransaction#62617a01` در TL-scheme + handler سمت سرور
- [ ] دریافت و پیکربندی کلید عمومی RSA بازار
- [ ] تأیید امضا `SHA1withRSA` روی `dataJson`
- [ ] (توصیه‌شده) استعلام مستقیم از Bazaar Developer API
- [ ] اعتبارسنجی `productId` / `packageName` / `purchaseState` / مبلغ
- [ ] جدول ضد-replay (`purchaseToken` یکتا) + تراکنش DB
- [ ] فعال‌سازی بر اساس `purpose` + برگرداندن `Updates`:
  - [ ] پرمیوم (اشتراکی)
  - [ ] شارژ ستاره (مصرفی)
  - [ ] هدیه پرمیوم (مصرفی)
  - [ ] هدیه ستاره (مصرفی)
- [ ] رفتار idempotent برای تکرارِ تراکنشِ موفق (پاک کردن صف کلاینت بدون فعال‌سازی مجدد)
- [ ] تست: خرید آنلاین، خرید آفلاین (صف + ارسال خودکار بعد از برگشت)، و خرید تکراری (هم پرمیوم هم ستاره)

---

## ۱۰. سناریوهای تستی که سرور باید پاس کنه

1. **آنلاین:** کلاینت می‌فرسته → سرور تأیید → `Updates` → پرمیوم فعال + صف کلاینت خالی.
2. **آفلاین:** سرور down → کلاینت رسید رو ذخیره می‌کنه و `onQueued` نشون میده (پول گرفته شده). بعد از up شدن سرور، کلاینت خودکار می‌فرسته → فعال می‌شه.
3. **تکراری:** همون رسید دوبار برسه → بار اول فعال، بار دوم `Updates` بدون فعال‌سازی مجدد (idempotent). پرمیوم **دوبار** تمدید نمی‌شه.
4. **جعلی:** `signature` غلط → رد.
5. **پکیج اشتباه:** `packageName` غیر از `com.thecat.messenger` → رد.
