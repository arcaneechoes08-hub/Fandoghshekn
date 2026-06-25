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
    
    // سینتکس اصلاح شده برای ++C
    const char *core_path = env->GetStringUTFChars(java_core_path, nullptr);
    const char *config_path = env->GetStringUTFChars(java_config_path, nullptr);

    LOGD("Starting core native: %s, config: %s, tunFd: %d", core_path, config_path, tun_fd);

    // 💥 اجازه به هسته برای دسترسی به تونل VPN اندروید (ارث‌بری File Descriptor)
    int flags = fcntl(tun_fd, F_GETFD);
    if (flags != -1) {
        fcntl(tun_fd, F_SETFD, flags & ~FD_CLOEXEC);
    }

    core_pid = fork();
    
    if (core_pid == 0) {
        // --- ما الان در فرآیند فرزند (Child Process) هستیم ---
        
        // 🛡️ هدایت خروجی‌های استاندارد به /dev/null برای جلوگیری از سرریز بافر و کرش هسته
        int null_fd = open("/dev/null", O_RDWR);
        if (null_fd != -1) {
            dup2(null_fd, STDIN_FILENO);
            dup2(null_fd, STDOUT_FILENO);
            dup2(null_fd, STDERR_FILENO);
            close(null_fd);
        }

        // اجرا با فلگ -c که هم برای سینگ‌باکس و هم Xray کار می‌کنه
        execl(core_path, "core", "run", "-c", config_path, nullptr);
        
        // اگر execl به هر دلیلی شکست خورد، فرزند باید بلافاصله کشته شود
        _exit(1);
    }

    // آزادسازی حافظه متغیرهای JNI
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
        kill(core_pid, SIGTERM); // درخواست محترمانه برای بسته شدن (Soft Kill)
        
        // ⏱️ نیم ثانیه مهلت برای بسته شدن ایمن و آزادسازی پورت‌ها
        usleep(500000); 

        int status;
        // چک کردن اینکه آیا پروسه هنوز زنده است یا خیر (بدون گیر کردن Thread)
        pid_t result = waitpid(core_pid, &status, WNOHANG);
        
        if (result == 0) { 
            // هسته لجبازی کرده و بسته نشده است!
            LOGD("Process did not terminate gracefully, forcing SIGKILL...");
            kill(core_pid, SIGKILL); // شلیک تیر خلاص (Hard Kill)
            waitpid(core_pid, &status, 0); // حالا منتظر می‌مانیم تا جسد پروسه (Zombie) جمع‌آوری شود
        }
        
        core_pid = -1;
        LOGD("Core process cleanly reaped and terminated.");
    }
}
