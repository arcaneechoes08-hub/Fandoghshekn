#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <errno.h>

#define TAG "FandoghNativeCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static volatile pid_t core_pid = -1;
static volatile pid_t tun2socks_pid = -1;

JNIEXPORT jint JNICALL
Java_com_fandogh_shekan_FandoghVpnService_startCoreNative(
        JNIEnv *env, jclass clazz,
        jstring java_core_path,
        jstring java_tun2socks_path,
        jstring java_config_path,
        jint tun_fd) {

    const char *core_path = (*env)->GetStringUTFChars(env, java_core_path, NULL);
    const char *tun2socks_path = (*env)->GetStringUTFChars(env, java_tun2socks_path, NULL);
    const char *config_path = (*env)->GetStringUTFChars(env, java_config_path, NULL);

    LOGD("Starting core: %s, tun2socks: %s, config: %s, tunFd: %d",
         core_path, tun2socks_path, config_path, tun_fd);

    if (access(core_path, X_OK) != 0) {
        LOGE("sing-box not executable: %s (errno=%d: %s)", core_path, errno, strerror(errno));
    }
    if (access(tun2socks_path, X_OK) != 0) {
        LOGE("tun2socks not executable: %s (errno=%d: %s)", tun2socks_path, errno, strerror(errno));
    }

    int flags = fcntl(tun_fd, F_GETFD);
    if (flags != -1) {
        fcntl(tun_fd, F_SETFD, flags & ~FD_CLOEXEC);
    }

    /* --- Start sing-box (SOCKS5 proxy on 127.0.0.1:10808) --- */
    pid_t pid1 = fork();
    if (pid1 == 0) {
        execl(core_path, "sing-box", "run", "-c", config_path, NULL);
        LOGE("execl sing-box failed: %s", strerror(errno));
        _exit(1);
    }
    if (pid1 < 0) {
        LOGE("Fork sing-box failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, java_core_path, core_path);
        (*env)->ReleaseStringUTFChars(env, java_tun2socks_path, tun2socks_path);
        (*env)->ReleaseStringUTFChars(env, java_config_path, config_path);
        return -1;
    }
    core_pid = pid1;
    LOGD("sing-box started with PID: %d", pid1);

    /* Pause to let sing-box bind the SOCKS port */
    usleep(1500000);

    /* --- Start tun2socks (bridges TUN fd to sing-box SOCKS proxy) --- */
    char device_arg[32];
    snprintf(device_arg, sizeof(device_arg), "fd://%d", tun_fd);

    pid_t pid2 = fork();
    if (pid2 == 0) {
        execl(tun2socks_path, "tun2socks",
              "-device", device_arg,
              "-proxy", "socks5://127.0.0.1:10808",
              "-loglevel", "warn",
              NULL);
        LOGE("execl tun2socks failed: %s", strerror(errno));
        _exit(1);
    }
    if (pid2 < 0) {
        LOGE("Fork tun2socks failed: %s", strerror(errno));
        kill(pid1, SIGTERM);
        (*env)->ReleaseStringUTFChars(env, java_core_path, core_path);
        (*env)->ReleaseStringUTFChars(env, java_tun2socks_path, tun2socks_path);
        (*env)->ReleaseStringUTFChars(env, java_config_path, config_path);
        return -1;
    }
    tun2socks_pid = pid2;
    LOGD("tun2socks started with PID: %d", pid2);

    (*env)->ReleaseStringUTFChars(env, java_core_path, core_path);
    (*env)->ReleaseStringUTFChars(env, java_tun2socks_path, tun2socks_path);
    (*env)->ReleaseStringUTFChars(env, java_config_path, config_path);

    /* Wait for either process to exit */
    int status;
    pid_t died = waitpid(-1, &status, 0);

    /* If one dies, kill the other */
    if (died == pid1) {
        LOGD("sing-box exited first");
        kill(pid2, SIGTERM);
        waitpid(pid2, NULL, 0);
    } else if (died == pid2) {
        LOGD("tun2socks exited first");
        kill(pid1, SIGTERM);
        waitpid(pid1, NULL, 0);
    }

    core_pid = -1;
    tun2socks_pid = -1;

    if (WIFEXITED(status)) {
        LOGD("Process exited with status: %d", WEXITSTATUS(status));
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        LOGD("Process killed by signal: %d", WTERMSIG(status));
        return -1;
    }
    return -1;
}

JNIEXPORT void JNICALL
Java_com_fandogh_shekan_FandoghVpnService_stopCoreNative(JNIEnv *env, jclass clazz) {
    pid_t pid;

    pid = tun2socks_pid;
    if (pid > 0) {
        LOGD("Sending SIGTERM to tun2socks PID: %d", pid);
        kill(pid, SIGTERM);
    }

    pid = core_pid;
    if (pid > 0) {
        LOGD("Sending SIGTERM to sing-box PID: %d", pid);
        kill(pid, SIGTERM);
    }
}
