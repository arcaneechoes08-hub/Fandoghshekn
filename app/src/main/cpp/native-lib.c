#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>

#define TAG "FandoghNativeCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static pid_t core_pid = -1;

JNIEXPORT jint JNICALL
Java_com_fandogh_shekan_FandoghVpnService_startCoreNative(JNIEnv *env, jclass clazz, jstring java_core_path, jstring java_config_path, jint tun_fd) {
    const char *core_path = (*env)->GetStringUTFChars(env, java_core_path, NULL);
    const char *config_path = (*env)->GetStringUTFChars(env, java_config_path, NULL);

    LOGD("Starting core native: %s, config: %s, tunFd: %d", core_path, config_path, tun_fd);

    // 💥 شاه‌کلید حل مشکل آیکون: اجازه به هسته برای دسترسی به تونل VPN اندروید
    int flags = fcntl(tun_fd, F_GETFD);
    if (flags != -1) {
        fcntl(tun_fd, F_SETFD, flags & ~FD_CLOEXEC);
    }

    core_pid = fork();
    if (core_pid == 0) {
        // اجرا با فلگ -c که هم برای سینگ‌باکس و هم Xray کار می‌کنه
        execl(core_path, "core", "run", "-c", config_path, NULL);
        _exit(1);
    }

    (*env)->ReleaseStringUTFChars(env, java_core_path, core_path);
    (*env)->ReleaseStringUTFChars(env, java_config_path, config_path);

    if (core_pid > 0) {
        LOGD("Core started with PID: %d", core_pid);
        return core_pid;
    } else {
        LOGD("Fork failed");
        return -1;
    }
}

JNIEXPORT void JNICALL
Java_com_fandogh_shekan_FandoghVpnService_stopCoreNative(JNIEnv *env, jclass clazz) {
    if (core_pid > 0) {
        kill(core_pid, SIGTERM); // بستن ایمن هسته برای جلوگیری از زامبی شدن
        int status;
        waitpid(core_pid, &status, 0);
        core_pid = -1;
        LOGD("Core process reaped.");
    }
}
