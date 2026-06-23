# Fandogh-Shekan VPN

یک اپلیکیشن VPN اندروید محدود برای آزادی اینترنت

## ویژگی‌ها

- رابط کاربری ساده و کاربرپسند
- پشتیبانی برای کانفیگ‌های VLESS
- استفاده از JNI برای عملکرد بهتر
- ذخیره‌سازی ایمن تنظیمات

## سیستم مورد نیاز

- Android API 21+
- Android Studio 2022.1+
- NDK for native development

## نصب و ساخت

### 1. Clone کردن پروژه
```bash
git clone https://github.com/arcaneechoes08-hub/Fandoghshekn.git
cd Fandoghshekn
```

### 2. باز کردن در Android Studio
```bash
android-studio .
```

### 3. ساخت و اجرا
```bash
./gradlew build
./gradlew installDebug
```

## ساختار پروژه

```
app/
├── src/
│   └── main/
│       ├── java/com/fandogh/shekan/
│       │   ├── MainActivity.java          # فعالیت اصلی
│       │   ├── FandoghVpnService.java     # سرویس VPN
│       │   └── Encryptor.java             # کلاس رمزنگاری
│       ├── cpp/
│       │   ├── native-lib.c               # کد Native برای اجرا
│       │   └── CMakeLists.txt             # پیکربندی CMake
│       ├── res/
│       │   ├── layout/                    # فایل‌های XML رابط
│       │   └── values/                    # منابع رشته‌ای و رنگ‌ها
│       └── AndroidManifest.xml            # پیکربندی اپ
├── build.gradle                           # پیکربندی Gradle
└── src/

build.gradle                                # پیکربندی پروژه
settings.gradle                             # تنظیمات Gradle
```

## نحوه استفاده

1. اپلیکیشن را باز کنید
2. کانفیگ VLESS را در قسمت مناسب کپی کنید
3. بر روی دکمه "اتصال" کلیک کنید
4. اجازه دهید تا VPN متصل شود
5. برای قطع، بر روی دکمه "قطع" کلیک کنید

## توجهات امنیتی

⚠️ **مهم**: 
- هرگز کانفیگ‌های حساس را در کد سخت‌کد نکنید
- برای ذخیره‌سازی حساس، از `EncryptedSharedPreferences` استفاده کنید
- VPN بر روی دستگاه‌های Rooted استفاده نکنید

## مسائل و پیشنهادات

اگر مسئله‌ای پیدا کردید یا پیشنهادی دارید، لطفاً [یک Issue باز کنید](https://github.com/arcaneechoes08-hub/Fandoghshekn/issues)

## مجوز

این پروژه تحت مجوز MIT منتشر شده است.

---

**نویسنده**: arcaneechoes08-hub
**آخرین بروزرسانی**: 2026
