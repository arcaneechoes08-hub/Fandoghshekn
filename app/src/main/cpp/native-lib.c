#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#define TAG "FandoghNativeXray"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static volatile int core_running = 0;

// پیاده‌سازی مستقیم توابع کُر به عنوان استاب داخلی جهت بیلد موفق
const char* RunXray(const char* config) {
    LOGD("Xray Core Stub: Engine simulation initialized successfully.");
    return "Stub Success";
}

void StopXray() {
    LOGD("Xray Core Stub: Engine simulation stopped.");
}

JNIEXPORT void JNICALL
Java_com_fandogh_shekan_FandoghVpnService_startXray(JNIEnv *env, jclass clazz, jstring config_json, jint tun_fd) {
    const char *config = (*env)->GetStringUTFChars(env, config_json, NULL);
    LOGD("startXray call received. tunFd: %d", tun_fd);
    
    core_running = 1;
    RunXray(config);
    
    while(core_running) {
        sleep(1); 
    }

    (*env)->ReleaseStringUTFChars(env, config_json, config);
    LOGD("Xray loop exited safely.");
}

JNIEXPORT void JNICALL
Java_com_fandogh_shekan_FandoghVpnService_stopXray(JNIEnv *env, jclass clazz) {
    LOGD("stopXray call received. Signaling core to stop.");
    core_running = 0;
    StopXray();
}
