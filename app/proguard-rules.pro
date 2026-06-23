# 🛡️ حل مشکل شماره ۲: ممانعت از تغییر نام متدهای حیاتی JNI و کلاس اصلی سرویس
-keep class com.fandogh.shekan.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
