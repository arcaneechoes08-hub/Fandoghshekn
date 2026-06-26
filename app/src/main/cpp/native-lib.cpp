#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>

#define TAG "FandoghNativeCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static pid_t core_pid = -1;

extern "C"
JNIEXPORT jint JNICALL
Java_com_fandogh_shekan_FandoghVpnService_startCoreNative(JNIEnv *env, jclass clazz, jstring java_core_path, jstring java_config_path, jint tun_fd) {

    const char *core_path = env->GetStringUTFChars(java_core_path, nullptr);
    const char *config_path = env->GetStringUTFChars(java_config_path, nullptr);

    LOGD("Starting core: %s", core_path);
    LOGD("Config: %s", config_path);
    LOGD("TUN fd: %d", tun_fd);

    // بررسی که فایل هسته وجود داره
    if (access(core_path, X_OK) != 0) {
        LOGE("Core binary not accessible or not executable: %s", core_path);
        env->ReleaseStringUTFChars(java_core_path, core_path);
        env->ReleaseStringUTFChars(java_config_path, config_path);
        return -1;
    }

    // بررسی که config وجود داره
    if (access(config_path, R_OK) != 0) {
        LOGE("Config file not readable: %s", config_path);
        env->ReleaseStringUTFChars(java_core_path, core_path);
        env->ReleaseStringUTFChars(java_config_path, config_path);
        return -1;
    }

    // بررسی که tun_fd معتبره
    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", tun_fd);
    if (access(fd_path, F_OK) != 0) {
        LOGE("tun_fd %d is NOT valid!", tun_fd);
        env->ReleaseStringUTFChars(java_core_path, core_path);
        env->ReleaseStringUTFChars(java_config_path, config_path);
        return -1;
    }
    LOGD("tun_fd %d is valid", tun_fd);

    // اجازه ارث‌بری FD به child process
    int flags = fcntl(tun_fd, F_GETFD);
    if (flags != -1) {
        fcntl(tun_fd, F_SETFD, flags & ~FD_CLOEXEC);
    }

    // مسیر فایل لاگ (کنار config.json)
    char log_path[512];
    strncpy(log_path, config_path, sizeof(log_path) - 20);
    char *slash = strrchr(log_path, '/');
    if (slash) *(slash + 1) = '\0';
    strncat(log_path, "core_output.log", 15);
    LOGD("Core output will be logged to: %s", log_path);

    core_pid = fork();

    if (core_pid == 0) {
        // child process: redirect stdout/stderr به فایل لاگ
        int log_fd = open(log_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (log_fd != -1) {
            dup2(log_fd, STDOUT_FILENO);
            dup2(log_fd, STDERR_FILENO);
            close(log_fd);
        }
        // stdin از /dev/null
        int null_fd = open("/dev/null", O_RDONLY);
        if (null_fd != -1) {
            dup2(null_fd, STDIN_FILENO);
            close(null_fd);
        }

        execl(core_path, "core", "run", "-c", config_path, nullptr);
        // اگه execl برگشت یعنی خطا داده
        _exit(1);
    }

    env->ReleaseStringUTFChars(java_core_path, core_path);
    env->ReleaseStringUTFChars(java_config_path, config_path);

    if (core_pid > 0) {
        LOGD("Core started with PID: %d", core_pid);
        return core_pid;
    } else {
        LOGE("Fork failed!");
        return -1;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_fandogh_shekan_FandoghVpnService_stopCoreNative(JNIEnv *env, jclass clazz) {
    if (core_pid > 0) {
        LOGD("Sending SIGTERM to PID: %d", core_pid);
        kill(core_pid, SIGTERM);
        usleep(500000);
        int status;
        pid_t result = waitpid(core_pid, &status, WNOHANG);
        if (result == 0) {
            LOGD("Force killing with SIGKILL...");
            kill(core_pid, SIGKILL);
            waitpid(core_pid, &status, 0);
        }
        core_pid = -1;
        LOGD("Core terminated.");
    }
}

// توضیح: فایل core_output.log در همان پوشه config.json ذخیره می‌شه
// مسیر: /data/data/com.fandogh.shekan/files/core_output.log
// برای خواندن لاگ در Java:
// File logFile = new File(getFilesDir(), "core_output.log");
