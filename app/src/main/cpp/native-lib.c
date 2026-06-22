#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <sys/fcntl.h>
#include <stdio.h>

JNIEXPORT jint JNICALL
Java_com_fandogh_shekan_FandoghVpnService_execWithFd(JNIEnv *env, jobject thiz, jobjectArray cmd_array, jint tun_fd, jstring log_path_str) {
    const char *log_path = (*env)->GetStringUTFChars(env, log_path_str, 0);
    
    int len = (*env)->GetArrayLength(env, cmd_array);
    char **args = malloc((len + 1) * sizeof(char *));
    for (int i = 0; i < len; i++) {
        jstring str = (*env)->GetObjectArrayElement(env, cmd_array, i);
        args[i] = (char *)(*env)->GetStringUTFChars(env, str, 0);
    }
    args[len] = NULL;

    // باز کردن قفل ارث‌بری فایل دسکریپتور
    fcntl(tun_fd, F_SETFD, 0);

    pid_t pid = fork();
    if (pid == 0) {
        // هدایت تمام ارورها و خروجی‌های لایه شبکه به فایل لوگ
        freopen(log_path, "w", stdout);
        freopen(log_path, "w", stderr);
        
        execv(args[0], args);
        perror("execv failed");
        exit(1);
    }

    // آزادسازی حافظه در فرآیند اصلی جاوا
    for (int i = 0; i < len; i++) {
        jstring str = (*env)->GetObjectArrayElement(env, cmd_array, i);
        (*env)->ReleaseStringUTFChars(env, str, args[i]);
    }
    free(args);
    (*env)->ReleaseStringUTFChars(env, log_path_str, log_path);

    return pid;
}
