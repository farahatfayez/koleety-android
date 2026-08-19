# KOLEETY AI Android — Production Release Handover

## النطاق المثبت

هذه النسخة تُجهّز تطبيق TWA جديداً بالحزمة **`com.koleety.ai.app`**. لا تستبدل ولا تعدّل التطبيق القديم العامل `com.mycollegeai.app`، ولا ترفع أي ملف تلقائياً إلى Google Play.

| البند | القيمة المثبتة |
|---|---|
| Application ID / namespace | `com.koleety.ai.app` |
| الاسم الظاهر | KOLEETY AI |
| الإصدار | `1.0.20` — `versionCode 21` |
| compile / target SDK | 36 |
| عنوان TWA الافتراضي | `https://koleety.com/` |
| نطاقات App Links | `koleety.com` و`www.koleety.com` |
| Android Browser Helper | `2.5.0` — مثبت لتجنب تغيير سلوك الغلاف دون مرجع للتطبيق القديم |
| أذونات اختيارية | الكاميرا وصور الجهاز والإشعارات، وتُعلن الكاميرا كميزة غير إلزامية |

## ما تم التحقق منه محلياً

تم بناء ملف AAB موقّع محلياً باستخدام JDK 17 وAndroid SDK 36، ونجح Android Lint بعد تفعيل تحقق App Links لاختصار المساعد. ملف البناء محمي بـ ProGuard ويحتوي على توقيع upload key مستقل عن أي تطبيق سابق.

## سياسة المفاتيح والتوقيع

الـ upload key للحزمة الجديدة محفوظ خارج المستودع ولا يُضاف إلى Git أو إلى مخرجات GitHub Actions. التوقيع يستخدم متغيرات البيئة التالية في وقت البناء فقط:

```text
KEYSTORE_PATH
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD
```

Google Play App Signing هو من يوقّع APK الذي يصل للمستخدم. بصمة شهادة upload key ليست بصمة App Signing المطلوبة في `assetlinks.json`.

## ترتيب النشر المباشر الصحيح

1. في Play Console، افتح إصدار **Production** للحزمة `com.koleety.ai.app` واختر Play App Signing.
2. ارفع ملف AAB الموقّع كمسودة إصدار إنتاجي. بعد قبول الحزمة، انسخ **SHA-256 الخاص بـ App signing key** من صفحة App signing، وليس بصمة upload key.
3. حدّث `https://koleety.com/.well-known/assetlinks.json` و`https://www.koleety.com/.well-known/assetlinks.json` بإدخال الحزمة الجديدة والبصمة التي يعرضها Play Console، ثم انشر تحديث الموقع.
4. راجع قائمة تغييرات الإنتاج وبيانات Data safety وتصنيف المحتوى وسياسة الخصوصية ومواد المتجر، ثم أرسل الإصدار للمراجعة فقط بعد اكتمال الخطوة السابقة.
5. يبقى `com.mycollegeai.app` متاحاً ولا يُحذف أو يُوقف ضمن هذا المسار.

> لا تُخمن بصمة App Signing ولا تستبدل Asset Links ببصمة upload key. افتح التطبيق الجديد حتى قبل التحقق الكامل من App Links سيبقى قادراً على فتح `https://koleety.com/` عبر المتصفح الموثوق، لكن تحقق App Links هو ما يضمن تجربة TWA الكاملة بلا شريط متصفح.

## التحقق بعد وصول أول AAB إلى Play Console

بعد ظهور بصمة Play App Signing ونشر `assetlinks.json`، افحص عبر رابط Google الرسمي للتحقق من Digital Asset Links، ثم نفّذ على هاتف Android حقيقي: بدء بارد مرتين، فتح رابط `koleety.com`، تسجيل الدخول، الوصول إلى أداة من الحساب، ورفع صورة غير حساسة. هذا تحقق جودة مُوصى به ولا ينشر أو يغير التطبيق القديم.

## ممنوعات أمنية

- لا ترفع ملف `.jks` أو كلمات المرور أو شهادة خاصة إلى GitHub أو Google Drive عام.
- لا تستخدم workflow يصدّر keystore أو كلمة مرور كـ artifact قابل للتنزيل.
- لا تغير اسم الحزمة أو شهادة upload key بعد ربط الإصدار الأول بـ Play App Signing.
