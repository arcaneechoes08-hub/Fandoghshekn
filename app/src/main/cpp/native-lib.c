#include <jni.h>
#include <android/log.h>
#include <stdlib.h>

#define TAG "FandoghNativeXray"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// تعریف امضای تابعی که درون libxray.so قرار دارد
extern const char* RunXray(const char* config);
extern void StopXray();

JNIEXPORT void JNICALL
Java_com_fandogh_shekan_FandoghVpnService_startXray(JNIEnv *env, jclass clazz, jstring config_json, jint tun_fd) {
    const char *config = (*env)->GetStringUTFChars(env, config_json, NULL);
    LOGD("startXray: Connecting to Xray Core Engine...");

    // 🚀 صدا زدن موتور واقعی Xray با کانفیگ کاربر!
    // این تابع پورت‌های محلی (مثل Socks 10808) را روی گوشی باز می‌کند.
    const char* result = RunXray(config);
    LOGD("Xray Core Result: %s", result);

    (*env)->ReleaseStringUTFChars(env, config_json, config);
}

JNIEXPORT void JNICALL
Java_com_fandogh_shekan_FandoghVpnService_stopXray(JNIEnv *env, jclass clazz) {
    LOGD("stopXray: Requesting Core to Shutdown.");
    StopXray();
}
