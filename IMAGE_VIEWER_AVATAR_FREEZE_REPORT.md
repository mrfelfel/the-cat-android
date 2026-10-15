# باگ فریز شدن Image Viewer روی آواتار — گزارش کامل و راهنمای حل

**تاریخ:** 2026-10-14 | **دستگاه:** Samsung R5CX90K2GQH (Android 15+, اپ `com.thecat.messenger`)
**علامت باگ:** روی عکس پروفایل یه یوزر (اینجا: 777000) کلیک می‌کنیم → PhotoViewer باز می‌شه → عکس فول‌سایز هیچ‌وقت رندر نمی‌شه؛ ورکر در حالت لودینگ (placeholder تار + اسپینر) فریز می‌مونه.

---

## ۱. خلاصه یک‌خطی

**مشکل از سرور نیست.** بایت‌های عکس سالم و کامل روی دستگاه کش شدن؛ مشکل اینه که بعد از تغییر server-scoping در فورک، سه لایه‌ی کلاینت اسم فایل رو **دو جور متفاوت** می‌سازن (scoped در FileLoader/ImageLoader، unscoped در PhotoViewer/getPathToAttach) و به همین دلیل PhotoViewer و کال‌بک‌ها هرگز متوجه «تمام شدن لود» نمی‌شن.

---

## ۲. شواهد

### ۲.۱. لاگ ADB (دو ریپرو مستقل: 22:32 و 22:49)

```
22:49:53.177 D/tmessages: send request TL_photos_getUserPhotos with token = 426
22:49:53.177 D/tmessages: create load operation fileName=-7764917031462819293_99_s5719c42f.jpg
                          size=۰ KB position in queue 0 account=0 cacheType=1
22:49:53.200 D/tmessages: finished downloading file to
                          /storage/emulated/0/Android/data/com.thecat.messenger/cache/-7764917031462819293_99_s5719c42f.jpg
                          time = 3 dc = 2 size = ۰ KB
22:49:53.507 D/tmessages: java received TLRPC$TL_photos_photos (پاسخ سرور سالم رسید)
```

- عملیات لود در **۳ میلی‌ثانیه** «تموم» شده → یعنی فایل از قبل روی دیسک بوده و دانلودی در کار نبوده.
- در هر دو ریپرو (۲۲:۳۲ و ۲۲:۴۹) همین رفتار؛ فایل کش از ساعت ۲۲:۲۸ روی دیسک بوده و در هر دو بازدید reuse شده.

### ۲.۲. نکته مهم: `size = ۰ KB` گمراه‌کننده است و طبیعی است

برای آواتار، سایز از قبل معلوم نیست: `ImageLocation.getForUserOrChat()` عمداً `size = 0` پاس می‌دهد
(`ImageLocation.java:242` → `getForPhoto(fileLocation, 0, ...)`)، پس `totalBytesCount = 0` و در لاگ «۰ KB»
یعنی «سایز نامشخص»، **نه** فایل صفر بایتی. این لاگ ردپای خرابی نیست.

### ۲.۳. تحلیل بایت‌های فایل کش (pull شده به لوکال)

فایل `-7764917031462819293_99_s5719c42f.jpg` از دستگاه pull شد:

```
total bytes: 782822
chunk count: 4
  IHDR  len=13       crc=OK
  caBX  len=21879    crc=OK
  IDAT  len=760874   crc=OK
  IEND  len=0        crc=OK
VERDICT: INTACT PNG  (PNG image data, 1024 x 1024, 8-bit/color RGB)
```

فایل **کاملاً سالم** است: هر ۴ چانک CRC درست، IEND کامل، هیچ truncation ای نیست.
**یعنی سرور بایت‌های عکس را کامل و درست داده است.** پاسخ `TL_photos_photos` هم سالم parse شد.

### ۲.۴. چرا حدس «سرور کلید اشتباه می‌دهد» رد می‌شود

- اگر file_reference یا کلیدِ location از سرور غلط/منقضی بود، دانلود **فِیل می‌شد** (FILE_REFERENCE_EXPIRED / FILE_MIGRATE) — نه اینکه در ۳ms از کش «تمام» شود.
- بایت‌های روی دیسک سالم‌اند (بند ۲.۳) — همان بایت‌هایی که همین سرور قبلاً داده.
- پاسخ `photos.getUserPhotos` در هر دو ریپرو درست رسید و پارس شد.

---

## ۳. ریشه باگ: mismatch اسم فایل بین لایه‌ها (ناشی از تغییرات فورک)

فورک برای جلوگیری از تصادم id بین سرورهای مختلف، پسوند `_s<hash(host)>` به اسم فایل آواتارها اضافه کرد.
این اسکوپ در **دو** جا اعمال می‌شود ولی در **دو** جای دیگر نه:

| لایه | تابع | اسم تولیدی | اسکوپ؟ |
|---|---|---|---|
| FileLoader (عملیات دانلود) | `scopedFileLocationName()` — [FileLoader.java:1652](TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java:1652) | `-7764917031462819293_99_s5719c42f.jpg` | ✅ |
| ImageLoader (کلید و URL کش) | `ImageLocation.getKey(..., currentAccount)` — [ImageLocation.java:476](TMessagesProj/src/main/java/org/telegram/messenger/ImageLocation.java:476) | `…_99_s5719c42f` | ✅ |
| **PhotoViewer (اسم فایل برای مقایسه/پیشرفت)** | `getFileName()` — [PhotoViewer.java:13562](TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java:13562) | `-7764917031462819293_99.jpg` | ❌ |
| **FileLoader (مسیر فایل برای checkProgress)** | `getPathToAttach()` → `getAttachFileName()` — [FileLoader.java:1432](TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java:1432) | `-7764917031462819293_99.jpg` | ❌ |

کامنت فورک در [FileLoader.java:1642-1651](TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java:1642) دقیقاً همین خطر را پیش‌بینی کرده:

> MUST produce exactly the same string as ImageLocation.getKey's location branch + extension …
> A mismatch here … makes the download's fileDidLoaded callback match nothing, so the avatar just never renders.

### زنجیره‌ی خرابی در runtime

1. PhotoViewer باز می‌شود → `checkProgress()` ([PhotoViewer.java:16369](TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java:16369)) برای وجود فایل، `getPathToAttach(location.location, ext, false/true)` را صدا می‌زند → اسم **unscoped** → فایلی که با اسم scoped ذخیره شده پیدا نمی‌شود → `existsFinal = false` → اسپینر می‌ماند (`PROGRESS_LOAD`).
2. دانلود/کش با اسم scoped تمام می‌شود → `NotificationCenter.fileLoaded` با اسم **scoped** پست می‌شود.
3. هندلر `fileLoaded` در [PhotoViewer.java:4163](TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java:4163) آن را با `currentFileNames[a]` که از `getFileName()` آمده (اسم **unscoped**) مقایسه می‌کند → **هرگز match نمی‌شود** → `setProgress(1.0f)` و re-check هرگز اجرا نمی‌شود.
4. نتیجه: اسپینر برای همیشه می‌چرخد؛ در حالت‌هایی که bitmap هم decode شده باشد، state machine نمایشگر باخبر نمی‌شود و UI روی لودینگ قفل می‌ماند. (سناریوی آواتارِ خارج از گالری — همان ریپروی ما.)

---

## ۴. راه حل

**اصل fix:** هر جا که PhotoViewer یا getPathToAttach اسم فایلِ یک `FileLocation`/`PhotoSize` عکس را می‌سازد، باید دقیقاً همان رشته‌ی scoped ساخته شود که `ImageLocation.getKey(…, currentAccount)` و `scopedFileLocationName()` می‌سازند.

### گام ۱ — یک helper واحد در FileLoader بساز

در `FileLoader.java` کنار `scopedFileLocationName` (خط ~1652):

```java
/**
 * Scoped name for a plain FileLocation/PhotoSize (avatars, chat photos).
 * Must match ImageLocation.getKey's location branch + extension exactly.
 */
public static String getScopedAttachFileName(TLRPC.FileLocation location, String ext, int currentAccount) {
    return scopedFileLocationName(location, ext, currentAccount);
}
```

### گام ۲ — اسکوپ کردن `getPathToAttach` برای FileLocation/PhotoSize

در [FileLoader.java:1432](TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java:1432) (انتهای `getPathToAttach`)، برای شاخه‌ی FileLocation/PhotoSize (همان‌هایی که `documentId = volume_id` گرفتند — خطوط 1370-1398) به جای `getAttachFileName(attach, ext)` از helper بالا استفاده کن. `getPathToAttach` یک instance-method است و `currentAccount` را دارد:

```java
// در انتهای getPathToAttach، وقتی attach instanceof TLRPC.FileLocation (یا PhotoSize با location):
return new File(dir, getScopedAttachFileName((TLRPC.FileLocation) attach, ext, currentAccount));
```

⚠️ دقت: شاخه‌ی `Document` دست نزن — نام‌گذاری داکیومنت‌ها (`dcId + "_" + docId`) اصلاً scope ندارد و نباید داشته باشد.

### گام ۳ — اسکوپ کردن `PhotoViewer.getFileName()`

در [PhotoViewer.java:13560-13563](TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java:13560):

```java
if (videoLocation != null && videoLocation != location) {
    return FileLoader.getScopedAttachFileName(videoLocation.location, ".mp4", currentAccount);
} else {
    return FileLoader.getScopedAttachFileName(location.location, ".jpg", currentAccount);
}
```

این کار به‌یک‌باره سه چیز را درست می‌کند:
- match شدن `NotificationCenter.fileLoaded` (هندلر 4163)
- درست شدن `isLoadingFile(currentFileNames[a])` و منوی cancel-loading
- match شدن اسم با عملیات لود واقعی در FileLoader

### گام ۴ — بررسی سایر call-siteهای unscoped

این‌ها را grep کن و هر کدام که با آواتار/PhotoLocation سروکار دارد را scope کن:

```bash
grep -rn 'volume_id + "_" +' TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java
grep -rn 'getAttachFileName(location' TMessagesProj/src/main/java/org/telegram/ | grep -v scoped
```

مقادیر شناخته‌شده‌ی باقی‌مانده که باید بررسی شوند (ممکن است عمداً unscoped درست باشند چون داکیومنت‌اند): `PhotoViewer` lines 13560, 13562 — این دو تا هدف اصلی‌اند.

---

## ۵. سناریوی مهاجرت کش (مهم)

بعد از این fix، اسم فایل‌های جدید scoped است و فایل‌های قدیمی هم همین الان scoped ذخیره شده‌اند (چون FileLoader از اول scoped بود) — پس **فایل‌های موجود روی دستگاه باقی می‌مانند و پیدا می‌شوند**. هیچ migration لازم نیست.

تنها ریسک: اگر جایی دیگری هنوز unscoped name بسازد و به `FileLoader.cancelLoadFile` یا `isLoadingFile` بدهد، همان علائم قبلی (لودینگ گیر کرده) را خواهد داشت — با grep گام ۴ پیدایش کن.

---

## ۶. پلن تست

1. **ریپروی اصلی:** روی آواتار یه یوزر تازه (که کش نداری) کلیک کن → باید عکس فول‌سایز بیاید و اسپینر محو شود.
2. **کش گرم:** دوباره روی همان آواتار کلیک کن → باید فوری (بدون اسپینر) باز شود.
3. **بعد از restart اپ:** همان تست → باید فوری باشد (فایل scoped روی دیسک reuse می‌شود).
4. **مقایسه با اکانت تلگرام رسمی:** آواتارها در هر دو حالت باید کار کنند (scope برای تلگرام رسمی `""` است و نام‌ها unscoped می‌مانند — backward compatible).
5. **رگرسیون:** عکس/ویدیوی داخل چت را در PhotoViewer باز کن (مسیر `imagesArr` / `MessageObject`) — چون فقط شاخه‌ی FileLocation را تغییر داده‌ای، نباید چیزی بشکند.
6. **لاگ انتظاری بعد از fix:** باید ببینی `create load operation fileName=X_sXXXX.jpg` و سپس در هندلر `fileLoaded` مقایسه‌ی `currentFileNames[0].equals(location)` برقرار شود (می‌توانی موقتاً یک `FileLog.d` برای مقایسه اضافه کنی).

---

## ۷. جمع‌بندی یافته‌ها (برای سشن بعدی)

- ✅ سرور سالم است: پاسخ TL API درست، بایت‌ها کامل (PNG 1024×1024، 782KB، CRC-OK).
- ✅ `size = ۰ KB` در لاگ طبیعی است (آواتارها size ندارند) — red herring.
- ❌ ریشه: mismatch اسم فایل scoped/unscoped بین `PhotoViewer.getFileName()` + `getPathToAttach()` (unscoped) و `FileLoader`/`ImageLoader` (scoped) — دقیقاً همان ریسکی که کامنت `scopedFileLocationName` هشدار داده.
- 📌 فیکس: helper مشترک + scope در دو نقطه (گام ۲ و ۳) + grep گام ۴ برای call-siteهای جامانده.
- 🔌 دستگاه وسط کار به‌خاطر شارژ جدا شد؛ ریپروی بعدی با همین پلن تست قابل انجام است.
