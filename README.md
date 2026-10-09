# Mrorgar-Q

پروژهٔ Android WebView browser با نوار کنترل بالایی کم‌ارتفاع و بازکردن لینک‌های HTTP/HTTPS داخل خود برنامه.

## دکمه‌ها
- Back / Forward: حرکت در تاریخچهٔ WebView
- Send: اشتراک‌گذاری پیوند صفحه از طریق برنامهٔ ارسال انتخابی کاربر
- File: انتخاب فایل برای آپلود در فرم‌های وب؛ انتخاب فایل در `input type=file` سایت نیز پشتیبانی می‌شود
- Copy MW: کپی HTML پنجرهٔ وب
- IP Set: پنجره‌های جداگانهٔ Proxy Port، DNS، Tunnel و Vray
- Save: ذخیرهٔ HTML صفحه در پوشهٔ خصوصی Downloads برنامه
- F Screen: تمام‌صفحه با دکمهٔ شناور شفاف برای بازگشت

## محدودیت‌های صادقانه
1. لینک‌های HTTP/HTTPS در WebView باز می‌شوند؛ برنامه برای این لینک‌ها مرورگر خارجی اجرا نمی‌کند. بعضی سایت‌ها ممکن است WebView را مسدود کنند یا قابلیت‌های خاصی بخواهند، پس سازگاری با تک‌تک سایت‌ها قابل تضمین نیست.
2. Proxy Port از Android 9/API 28 به بعد از API پراکسی WebView استفاده می‌کند. این تنظیم لزوماً بر برنامه‌های دیگر دستگاه اثر نمی‌گذارد.
3. DNS در این نسخه ذخیره می‌شود، اما تغییر واقعی DNS نیازمند پیاده‌سازی DoH/DoT یا VPN محلی است.
4. Tunnel و V2Ray کانفیگ/تنظیمات را جداگانه ذخیره می‌کنند؛ موتور اتصال VPN/Tunnel/V2Ray هنوز به پروژه افزوده نشده است، پس دکمه‌های ذخیره نباید به‌عنوان اتصال واقعی جا زده شوند.

## ساخت
در Android Studio باز کنید و Gradle Sync و `assembleDebug` را اجرا کنید. GitHub Actions نیز با push به main/master یا اجرای دستی ساخته می‌شود.


## Multi downloader / uploader update
- Downloads started by WebView download links are queued through Android DownloadManager, allowing several downloads to run/manage in parallel according to Android's download service.
- Long-press the `Save` button to open **Multi Downloader**. Paste one direct HTTP/HTTPS download URL per line, then add them to the queue. `View downloads` displays available status and progress.
- The `File` button tries to activate the current page's file input. The WebView file chooser allows multiple selection; the website's own HTML form must also support multiple files to upload more than one file in a single submission. If no file input exists on the current page, the file picker can select files but cannot upload them without a website upload form.
- Download links that require special browser authentication, JavaScript-only retrieval, or non-HTTP protocols may not work through Android DownloadManager.
