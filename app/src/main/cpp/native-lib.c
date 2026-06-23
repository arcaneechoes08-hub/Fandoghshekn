#include <jni.h>
#include <android/log.h>

#define TAG "FandoghNativeXray"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// پیاده‌سازی متد لود و اجرای ایکس‌ری به فرمت استاندارد JNI اندروید
JNIEXPORT void JNICALL
Java_com_fandogh_shekan_FandoghVpnService_startXray(JNIEnv *env, jclass clazz, jstring config_json, jint tun_fd) {
    const char *config = (*env)->GetStringUTFChars(env, config_json, NULL);
    
    LOGD("startXray call received. tunFd: %d", tun_fd);
    // اینجا در آینده پوینت‌های متصل‌کننده به هسته اصلی Go (مستخرج از xray.a) صدا زده می‌شوند.
    // فعلاً برای شبیه‌سازی و تست عدم کرش، ترد را در وضعیت انتظار فیک نگه می‌داریم.
    while(1) {
        // برای جلوگیری از مصرف ۱۰۰ درصدی سی‌پی‌یو در حلقه دباگ فیک
        jclass thread_class = (*env)->FindClass(env, "java/lang/Thread");
        jmethodID sleep_method = (*env)->GetStaticMethodID(env, thread_class, "sleep", "(J)V");
        (*env)->CallStaticVoidMethod(env, thread_class, sleep_method, (jlong)1000);
    }

    (*env)->ReleaseStringUTFChars(env, config_json, config);
}

JNIEXPORT void JNICALL
Java_com_fandogh_shekan_FandoghVpnService_stopXray(JNIEnv *env, jclass clazz) {
    LOGD("stopXray call received. Stopping core execution.");
}
