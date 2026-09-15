# تماس من — نسخه‌ی بومی اندروید (بدون WebView)

این پوشه یک پروژه‌ی کامل Android Studio (Kotlin) است که همان قابلیت‌های
`MyContact.html` را با کتابخانه‌ی WebRTC بومی اندروید پیاده‌سازی می‌کند —
بدون WebView، بدون HTML/CSS/JS. رابط کاربری با View/XML بومی ساخته شده و
دقیقاً همان توکن‌های رنگی، ترتیب صفحات، و متن‌های فارسی فایل اصلی را دارد.

## چطور باز کنم و بسازم

1. Android Studio (نسخه‌ی Ladybug یا جدیدتر) را باز کنید → **Open** → این پوشه
   (`MyContactAndroid`) را انتخاب کنید.
2. صبر کنید Gradle sync کامل شود (به اینترنت نیاز دارد تا کتابخانه‌ها —
   WebRTC، CameraX، ML Kit، ZXing، Gson، OkHttp — دانلود شوند).
3. یک دستگاه واقعی وصل کنید (تماس صوتی/تصویری روی امولاتور معمولاً کار
   نمی‌کند چون به میکروفون/دوربین واقعی نیاز دارد) و Run بزنید.

## فونت Vazirmatn

چون این محیط به اینترنت دسترسی نداشت، فایل فونت را نتوانستم دانلود کنم.
برای فعال کردن آن:

1. فایل‌های `Vazirmatn-Regular.ttf` و `Vazirmatn-Bold.ttf` را از
   https://fonts.google.com/specimen/Vazirmatn دانلود کنید.
2. در `app/src/main/res/font/` کپی کنید (نام‌ها را به حروف کوچک و بدون خط
   تیره تغییر دهید: `vazirmatn_regular.ttf`, `vazirmatn_bold.ttf`).
3. یک فایل `res/font/vazirmatn.xml` بسازید که این دو وزن را به هم وصل کند
   و در `themes.xml` با `<item name="android:fontFamily">@font/vazirmatn</item>`
   ست کنید.

فعلاً برنامه با فونت پیش‌فرض سیستم اجرا می‌شود (بدون کرش).

## تنظیم TURN شخصی شما

دامنه‌ی Metered شما (`mycontact.metered.live`) به‌صورت پیش‌فرض در
`Prefs.kt` قرار داده شده؛ کلید مخفی (Secret Key) را از صفحه‌ی تنظیمات
برنامه وارد کنید (چون این کلید هرگز در این گفتگو به من داده نشده بود، در
کد هاردکد نشده — فقط دامنه).

## چیزهایی که «بازنویسی» شدند نه «کپی خط‌به‌خط»

یک اپ WebView جاوااسکریپتی و یک اپ بومی اندروید دو مدل برنامه‌نویسی
کاملاً متفاوت‌اند؛ چیزهایی که لزوماً معادل‌سازی شدند نه عیناً کپی:

- **پروتکل سیگنالینگ کد اتصال (Offer/Answer):** دقیقاً یکسان است —
  `base64(JSON{sdp, type})`، بعد از تکمیل کامل ICE gathering (بدون
  trickle ICE)، چون این بخش مستقل از پلتفرم است.
- **پروتکل پیام روی DataChannel** (متن، فایل، تماس، ICE-restart):
  چون آبجکت‌های PeerConnection بومی با آبجکت‌های مرورگر یکی نیستند،
  یک پروتکل JSON هم‌ارز اما از نو تعریف شد (`ControlMessage.kt`) — رفتار
  یکسان (چت متنی، انتقال فایل تکه‌تکه، تماس صوتی/تصویری، اتصال مجدد
  خودکار با ICE-restart) اما بایت‌به‌بایت با نسخه‌ی HTML یکی نیست.
- **QR:** به‌جای موتور QR دست‌ساز جاوااسکریپتی، از ZXing (تولید) و
  CameraX + ML Kit (اسکن) استفاده شده — هر دو استاندارد صنعتی اندروید و
  به همان اندازه (یا بیشتر) قابل‌اعتماد.
- **اتصال مجدد خودکار (ICE restart):** منطق اصلی پیاده شده (فقط طرف
  starter پیشنهاد می‌دهد، receiver فقط پاسخ می‌دهد) ولی به‌صورت ساده‌تر
  از حلقه‌ی ۶ بار تلاش نسخه‌ی اصلی — قابل تقویت در صورت نیاز.
- **کیبورد/اشتراک‌گذاری:** به‌جای Web Share API از Android Share Sheet
  (`Intent.ACTION_SEND`) و Clipboard بومی استفاده شده.

## ساختار پروژه

```
app/src/main/java/com/mycontact/app/
  MyContactApp.kt        — Application، init کردن WebRTC
  Session.kt              — state سراسری (معادل State در نسخه‌ی JS)
  SessionRtcBridge.kt      — اتصال رویدادهای WebRTC/فایل به UI
  WebRtcManager.kt         — هسته‌ی PeerConnection بومی
  SignalingCodec.kt        — کدگذاری/کدگشایی کد اتصال
  ControlMessage.kt        — پروتکل پیام روی DataChannel
  FileTransferManager.kt   — انتقال فایل تکه‌تکه
  QrCodeUtil.kt / QrScanActivity.kt / QrDisplayActivity.kt
  NotificationHelper.kt
  Prefs.kt / Message.kt / MessageAdapter.kt
  WelcomeActivity / ConnectActivity / ChatActivity / CallActivity / SettingsActivity
```

## محدودیت شناخته‌شده

این کد این‌جا کامپایل/تست نشده (این محیط نه SDK اندروید دارد نه دسترسی
به اینترنت برای دانلود Gradle dependencies) — پس ممکن است در اولین Build
با یکی دو خطای کوچک نحوی/امضای API روبه‌رو شوید که با پیام خطای
Android Studio به‌سادگی قابل رفع است. اگر به خطایی برخوردید، پیامش را
برایم بفرستید تا اصلاح کنم.
