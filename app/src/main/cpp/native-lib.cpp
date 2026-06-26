#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <stdlib.h>

#define TAG "FandoghNativeCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static pid_t core_pid = -1;

extern "C"
JNIEXPORT jint JNICALL
Java_com_fandogh_shekan_FandoghVpnService_startCoreNative(JNIEnv *env, jclass clazz, jstring java_core_path, jstring java_config_path, jint tun_fd) {
    
    const char *core_path = env->GetStringUTFChars(java_core_path, nullptr);
    const char *config_path = env->GetStringUTFChars(java_config_path, nullptr);

    LOGD("Starting core native: %s, config: %s, tunFd: %d", core_path, config_path, tun_fd);
    
    // اجازه ارث‌بری FD
    int flags = fcntl(tun_fd, F_GETFD);
    if (flags != -1) {
        fcntl(tun_fd, F_SETFD, flags & ~FD_CLOEXEC);
    }

    core_pid = fork();
    
    if (core_pid == 0) {
        int null_fd = open("/dev/null", O_RDWR);
        if (null_fd != -1) {
            dup2(null_fd, STDIN_FILENO);
            dup2(null_fd, STDOUT_FILENO);
            dup2(null_fd, STDERR_FILENO);
            close(null_fd);
        }

        execl(core_path, "core", "run", "-c", config_path, nullptr);
        _exit(1);
    }

    env->ReleaseStringUTFChars(java_core_path, core_path);
    env->ReleaseStringUTFChars(java_config_path, config_path);

    if (core_pid > 0) {
        LOGD("Core started successfully with PID: %d", core_pid);
        return core_pid;
    } else {
        LOGD("Critical Error: Fork failed!");
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
            LOGD("Process did not terminate gracefully, forcing SIGKILL...");
            kill(core_pid, SIGKILL); 
            waitpid(core_pid, &status, 0);
        }
        core_pid = -1;
        LOGD("Core process cleanly reaped and terminated.");
    }
}
