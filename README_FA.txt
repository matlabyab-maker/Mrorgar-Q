Mrorgar-Q — سورس پروژه با DNS محلی ادغام‌شده

این بسته کد DNS را در ساختار واقعی پروژه قرار می‌دهد؛ لازم نیست فایل‌ها را دستی در محل‌های مختلف جست‌وجو کنی.

ساختار مهم:
- app/src/main/java/com/mrorgar/q/MainActivity.java
- app/src/main/java/com/mrorgar/q/DnsVpnService.java
- app/src/main/AndroidManifest.xml
- app/build.gradle

نحوه باز کردن:
1) پوشه پروژه را در Android Studio باز کن.
2) اجازه بده Gradle Sync انجام شود.
3) Build > Make Project را اجرا کن و خطاهای احتمالی را بررسی کن.
4) برنامه را روی گوشی نصب کن.
5) IP Set > DNS را باز کن، آدرس‌های IPv4 مثل 1.1.1.1 و 8.8.8.8 را وارد کن و «اعمال و اتصال» را بزن.
6) درخواست مجوز VPN اندروید را تأیید کن.

محدودیت‌ها:
- این سرویس فقط IPv4/UDP DNS را هدف می‌گیرد؛ DNS over TCP، IPv6 DNS، DoH/DoT و حالت‌های خاص پوشش داده نشده‌اند.
- چون سرویس از Android VpnService استفاده می‌کند، ممکن است هم‌زمان با VPN دیگری قابل‌استفاده نباشد.
- این پروژه در این محیط با Android Gradle Plugin ساخته یا روی گوشی آزمایش نشده است. موفقیت Build و عملکرد DNS را تا آزمایش عملی تأییدشده فرض نکن.
- این بسته فایل‌های سورس و تنظیمات Gradle را دارد؛ Gradle Wrapper همراه آن نیست و Android Studio می‌تواند از Gradle نصب‌شده/تنظیم‌شده استفاده کند.
