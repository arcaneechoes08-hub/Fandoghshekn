#include <jni.h>
#include <android/log.h>

#define LOG_TAG "FandoghNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_com_fandogh_shekan_FandoghVpnService_startNativeCore(JNIEnv *env, jobject thiz, jstring config) {
    // تبدیل رشته جاوایی به رشته قابل فهم در C
    const char *config_str = (*env)->GetStringUTFChars(env, config, NULL);
    
    if (config_str == NULL) {
        LOGE("❌ خطای لود کانفیگ در لایه نیتیو!");
        return -1;
    }

    // 🎯 اینجاست که هسته تونل‌سازی فندق‌شکن بیدار میشه
    LOGI("🌰 [Core] هسته نیتیو فندق‌شکن با کانفیگ لود شده استارت خورد!");
    LOGI("🌰 [Core] Config: %s", config_str);

    // آزادسازی حافظه رشته تخصیص داده شده
    (*env)->ReleaseStringUTFChars(env, config, config_str);
    
    return 0; // کد وضعیت موفقیت‌آمیز
}
