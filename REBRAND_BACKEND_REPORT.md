# گزارش ری‌برند برای بک‌اند — چه چیزهایی باید سرورساید ری‌پلیس بشن

تاریخ: 2026-10-16 · شاخه: `cat/rebrand` · بیس: Telegram 12.10.1 fork

کلاینت (این ریپو) این جایگزینی‌ها را انجام داد. بک‌اند باید **همان قواعد** را روی هر متنی که خودش تولید/ارسال می‌کند اعمال کند: langpack سرور، متن‌های سرویس‌مسیج، خطاها، اعلان‌های push، متن‌های وب‌ویو و هر جایی که سرور مستقیماً به کلاینت/کاربر می‌رسد.

## نقشه ری‌پلیسمنت (Rules)

| # | الگو | جایگزین | دامنه |
|---|------|---------|-------|
| 1 | `The Cat` | `کت` | **فقط فارسی** (`values-fa`) — «کت پرمیوم»، «در کت»، «پروفایل کت» |
| 2 | `Telegram` / `telegram` (نمایشی) | `The Cat` / `the cat` | انگلیسی (هااردکد + ریسورس) |
| 3 | `Telegram` / `telegram` (نمایشی) | `کت` | فارسی |
| 4 | `Fragment` (نمایشی، بدون URL) | `کت مارکت` | هر دو زبان |
| 5 | `TON` (نمایشی) | `پولک` | هر دو زبان |
| 6 | `blockchain` / «بلاکچین» در ترکیب با TON | فارسی: «بانک مجازی پولک» · انگلیسی: هم‌ارز انگلیسی (پیشنهاد: *Polka virtual bank* — تصمیم نهایی با شما) | هر دو زبان |

### استثناها (دست نزنید)

- **URLها**: `telegram.org`، `core.telegram.org`، `desktop.telegram.org`، `web.telegram.org`، `t.me/...`، `fragment.com/...` — دست‌نخورده بمانند (لینک‌های زنده‌اند؛ اگر دامنه عوض شده، اول DNS/سرور را آماده کنید بعد کلاینت را).
- **شناسه‌های فنی** (در کلاینت هم دست نخورده‌اند): `TelegramWebviewProxy` (JS bridge مینی‌اپ‌ها — اگر عوض شود همه وب‌اپ‌ها می‌شکنند)، `window.Telegram.WebView`، `Telegram-Android` user-agent، account type `org.telegram.messenger`، `TelegramConnectionService`، `TelegramMediaSession`، مسیرهای استوریج داخلی `Pictures/Telegram` (تغییرش دیتای موجود کاربر را گم می‌کند)، نام سرور رسمی تلگرام در `TheCatServers.telegramServer()` (هویت سرور واقعی است، نه برند UI).
- **`TelegramFeaturesUrl`**: هم‌اکنون در هر دو زبان `https://t.me/The CatTips[FA]` است (فضا داخل URL — **از قبل خراب بوده**). یا کانال جدید بسازید و URL را بدهید، یا موقتاً حذفش کنید.

## آنچه کلاینت انجام داد (مرجع برای تطبیق سرور)

- `values-fa/strings.xml`: **~448 وری** تغییر کرد (The Cat→کت، Fragment→کت مارکت، TON→پولک، «بلاکچین TON»→«بانک مجازی پولک»).
- `values/strings.xml`: **~283 وری** تغییر کرد (Telegram→The Cat، Fragment→کت مارکت، TON→پولک). URLها محافظت شدند.
- هاردکدهای Java قابل نمایش (فهرست کامل در پایین).
- XML هر دو فایل معتبر؛ هیچ `Telegram`/`TON`/`Fragment` نمایشی باقی نمانده (به‌جز نام‌کلید `<string name="Fragment">` که فقط کلید است).

## سرور چه چیزهایی را باید ری‌پلیس کند؟

### ۱. Langpack (مهم‌ترین)

اگر سرور langpack می‌دهد (فارسی و هر زبان دیگر)، هر وری که کلاینت از `values-fa` می‌خواند ممکن است از سرور override شود. **اگر langpack سرور اصلاح نشود، تغییرات فارسی کلاینت بی‌اثر است.** فهرست کامل کلیدهای تغییریافته (خروجی‌گرفته‌شده، آماده تحویل):

- `REBRAND_fa_changed_keys.txt` — ۴۴۸ کلید فارسی
- `REBRAND_en_changed_keys.txt` — ۲۸۳ کلید انگلیسی

نکته: کلیدهای حاوی TON در نام (`Gift2ExportTON*`، `*TONText` و…) **نام‌شان عوض نشده** — فقط مقدارشان. سرور هم فقط value را عوض کند، نه key را (وگرنه کلاینت‌های قدیمی می‌شکنند).

### ۲. متن‌هایی که سرور مستقیم می‌فرستد (حدس بر اساس TL — بازبینی کنید)

- **Service messages / updateهای متنی** اگر سرور متن از پیش‌ساخته می‌فرستد به‌جای اینکه کلاینت از TL بسازد.
- **تنها چیزی که کلاینت از سرور به‌صورت متن خام نشان می‌دهد**: نام کاربری/چت، متن ربات‌ها، نتیجه‌ی `payments` و giftها (نام هدیه، توضیحات auction). اگر اینها «Telegram»/«Fragment»/«TON» دارند، فقط سرور می‌تواند عوضشان کند.
- **`TL_fragment.collectibleInfo`** (شیت خرید یوزرنیم/شماره): متن‌ها از ریسورس کلاینت‌اند (ری‌پلیس شد)؛ فقط مطمئن شوید `url` برگشتی از سرور دامنه درست (کت‌مارکت/فرگمنت واقعی) باشد.
- **پیام‌های ارور استرینگ** مثل `TONES_SAVED_TOO_MANY` و ارورهای گیت‌وی پرداخت — اینها کد نیستند، اگر سرور متن human-readable می‌فرستد باید ری‌پلیس شوند.
- **Push notification body** اگر سرور خودش می‌سازتش (نه فچ‌از‌دی‌ال‌اف).

### ۳. ترجمه‌های زبان دیگر

کلاینت فقط `values` (EN) و `values-fa` را تعویض کرد. زبان‌های دیگر موجود در ریپو (`values-ar/de/es/it/ko/nl/pt-rBR/ru/uk`) هنوز «Telegram»/«Fragment»/«TON» انگلیسی دارند. یا:
- سرور langpack برای این زبان‌ها هم اصلاح شود (پیشنهاد)، یا
- کلاینت fallback به انگلیسی بخورد (طبیعی است — Android مقادیر missing را از `values/` می‌گیرد؛ ولی مقادیر *موجود* و قدیمی در آن فایل‌ها می‌مانند).

### ۴. وب‌سایت/دیپ‌لینک‌ها

- لینک‌های `fragment.com/ads/topup` و `fragment.com/stars` در ریسورس کلاینت ماندند (URL). اگر کت‌مارکت خودتان جایگزین است، **اول** سرویس را بالا بیاورید **بعد** این URLها را در یک کامیت جدا عوض کنید.
- لینک‌های `telegram.org/...` (FAQ، TOS، privacy): اگر سند جایگزین دارید، همین الگو را اعمال کنید؛ وگرنه دست نزنید.

## هاردکدهای Java که کلاینت عوض کرد (مرجع)

| فایل | قبل | بعد |
|------|-----|-----|
| `NewContactBottomSheet.java` | `This phone number is not on Telegram.` / `...is on Telegram.` | `... on The Cat.` |
| `PasscodeView.java` | `Unlock to use Telegram` | `Unlock to use The Cat` |
| `LaunchActivity.java` | `This is a Telegram link...` / fallback name `"Telegram"` | `The Cat` |
| `LaunchActivity.java` | `This link can only be opened with an official Telegram account...` | `... official The Cat account...` |
| `ContactsController.java` | `Telegram Profile/Voice Call/Video Call` | `The Cat ...` |
| `ThemePreviewActivity.java` | `Telegram Beta Chat` | `The Cat Beta Chat` |
| `MessagePreviewView.java` | `Subscribe to **Telegram Premium**...` | `**The Cat Premium**...` |
| `LoginActivity.java` | support email body `But Telegram says it's...` | `But The Cat says it's...` |

### هاردکدهای باقی‌مانده‌ی عمدی (دست نزنید / تصمیم بعدی)

- `StarsIntroActivity` / `ChannelMonetizationLayout` / `FragmentUsernameBottomSheet`: رشته `"TON"` به‌عنوان **متنِ جایگزین‌شده با آیکون** (`ColoredImageSpan` روی «TON» می‌نشیند و آیکون گِرم نشان می‌دهد) — عملاً کاربر حرف TON نمی‌بیند؛ اگر هم ببیند، آیکون جایگزینش می‌کند. تغییر اینها ریسک شکستن span دارد؛ فقط در صورت نیاز و با دقت.
- `TheCatServers.java`: `s.name = "Telegram"` برای سرور رسمی تلگرام — هویت واقعی سرور است.
- مسیرهای استوریج `Telegram` / account typeها — سازگاری دیتای upgrade.

## چک‌لیست تأیید سرور

- [ ] Langpack فارسی: صفر `Telegram` نمایشی، صفر `The Cat` (باید `کت` باشد)، صفر `Fragment` (باید `کت مارکت`)، صفر `TON` (باید `پولک`)
- [ ] Langpack انگلیسی: صفر `Telegram` نمایشی، `Fragment`→`کت مارکت`، `TON`→`پولک`
- [ ] URLهای telegram.org/fragment.com طبق تصمیم دامنه — یا زنده‌اند یا عوض شده‌اند
- [ ] ارورهای گیت‌وی/pushهای سروری ری‌پلیس شده‌اند
- [ ] نام کانال tips (`TelegramFeaturesUrl`) مشخص شده
- [ ] `t.me/The CatTips` — URL خراب اصلاح/حذف شده
