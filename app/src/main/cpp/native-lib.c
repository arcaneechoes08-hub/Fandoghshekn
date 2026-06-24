#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <errno.h>

#define TAG "FandoghNativeCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static volatile pid_t core_pid = -1;

JNIEXPORT jint JNICALL
Java_com_fandogh_shekan_FandoghVpnService_startCoreNative(JNIEnv *env, jclass clazz, jstring java_core_path, jstring java_config_path, jint tun_fd) {
    const char *core_path = (*env)->GetStringUTFChars(env, java_core_path, NULL);
    const char *config_path = (*env)->GetStringUTFChars(env, java_config_path, NULL);

    LOGD("Starting core: %s, config: %s, tunFd: %d", core_path, config_path, tun_fd);

    int flags = fcntl(tun_fd, F_GETFD);
    if (flags != -1) {
        fcntl(tun_fd, F_SETFD, flags & ~FD_CLOEXEC);
    }

    core_pid = fork();
    if (core_pid == 0) {
        char fd_env[64];
        snprintf(fd_env, sizeof(fd_env), "TUN_FD=%d", tun_fd);
        putenv(fd_env);
        execl(core_path, "sing-box", "run", "-c", config_path, NULL);
        LOGE("execl failed: %s", strerror(errno));
        _exit(1);
    }

    (*env)->ReleaseStringUTFChars(env, java_core_path, core_path);
    (*env)->ReleaseStringUTFChars(env, java_config_path, config_path);

    if (core_pid > 0) {
        LOGD("Core started with PID: %d, waiting...", core_pid);
        int status;
        waitpid(core_pid, &status, 0);
        core_pid = -1;
        if (WIFEXITED(status)) {
            LOGD("Core exited with status: %d", WEXITSTATUS(status));
            return WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            LOGD("Core killed by signal: %d", WTERMSIG(status));
            return -1;
        }
        return -1;
    } else {
        LOGE("Fork failed: %s", strerror(errno));
        return -1;
    }
}

JNIEXPORT void JNICALL
Java_com_fandogh_shekan_FandoghVpnService_stopCoreNative(JNIEnv *env, jclass clazz) {
    pid_t pid = core_pid;
    if (pid > 0) {
        LOGD("Sending SIGTERM to core PID: %d", pid);
        kill(pid, SIGTERM);
    }
}
