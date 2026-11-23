#include "kuroko_shm.h"
#include "sensor_hook.h"
#include "zygisk.hpp"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#define LOG_TAG "KurokoZygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

bool send_fd_with_status(int sock, uint8_t status, int fd_to_send) {
    struct msghdr msg{};
    struct iovec iov{&status, 1};
    char cmsg_buf[CMSG_SPACE(sizeof(int))]{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    if (fd_to_send >= 0) {
        msg.msg_control = cmsg_buf;
        msg.msg_controllen = sizeof(cmsg_buf);
        struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int));
        std::memcpy(CMSG_DATA(cmsg), &fd_to_send, sizeof(int));
    }
    return sendmsg(sock, &msg, MSG_NOSIGNAL) > 0;
}

bool recv_fd_with_status(int sock, uint8_t* out_status, int* out_fd) {
    *out_status = 0;
    *out_fd = -1;
    struct msghdr msg{};
    struct iovec iov{out_status, 1};
    char cmsg_buf[CMSG_SPACE(sizeof(int))]{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf;
    msg.msg_controllen = sizeof(cmsg_buf);

    ssize_t n = recvmsg(sock, &msg, MSG_CMSG_CLOEXEC);
    if (n <= 0) return false;

    for (struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS &&
            cmsg->cmsg_len >= CMSG_LEN(sizeof(int))) {
            int fd = -1;
            std::memcpy(&fd, CMSG_DATA(cmsg), sizeof(int));
            *out_fd = fd;
            break;
        }
    }
    return true;
}

// Runs inside root Companion process (triggered ONLY by preServerSpecialize)
int fetch_memfd_from_daemon() {
    for (int attempt = 0; attempt < 40; ++attempt) {
        int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (s < 0) return -1;
        struct sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        std::strncpy(addr.sun_path, KUROKO_COMPANION_SOCK_PATH, sizeof(addr.sun_path) - 1);
        if (connect(s, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == 0) {
            uint8_t dummy = 0;
            int memfd = -1;
            bool ok = recv_fd_with_status(s, &dummy, &memfd);
            close(s);
            if (ok && memfd >= 0) return memfd;
        } else {
            close(s);
        }
        usleep(100000); // wait up to 4.0s for daemon socket
    }
    return -1;
}

// Boot-guard anti-bootloop check (runs as root in Companion)
bool check_boot_guard_safe() {
    FILE* fp = std::fopen(KUROKO_BOOT_GUARD_PATH, "r");
    if (!fp) return true;
    int fails = 0;
    if (std::fscanf(fp, "%d", &fails) != 1) fails = 0;
    std::fclose(fp);
    if (fails >= 2) {
        LOGE("Boot-guard tripped (fails=%d >= 2): skipping all system_server hooks for safety!", fails);
        return false;
    }
    return true;
}

void companion_handler(int client_sock) {
    uint8_t cmd = 0;
    if (read(client_sock, &cmd, 1) != 1 || cmd != 1) {
        send_fd_with_status(client_sock, 0, -1);
        return;
    }
    if (!check_boot_guard_safe()) {
        send_fd_with_status(client_sock, 2, -1); // 2 = boot-guard tripped
        return;
    }
    int memfd = fetch_memfd_from_daemon();
    if (memfd < 0) {
        LOGW("Companion could not fetch memfd from kuroko_daemon");
        send_fd_with_status(client_sock, 0, -1);
        return;
    }
    send_fd_with_status(client_sock, 1, memfd);
    close(memfd);
}

// Safely publish status to java.lang.System.setProperty inside system_server; never throws.
void set_java_system_property(JNIEnv* env, const char* key, const char* val) {
    if (!env || !key || !val) return;
    if (env->ExceptionCheck()) env->ExceptionClear();

    jclass sys_cls = env->FindClass("java/lang/System");
    if (!sys_cls || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (sys_cls) env->DeleteLocalRef(sys_cls);
        return;
    }
    jmethodID mid = env->GetStaticMethodID(
        sys_cls, "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (!mid || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(sys_cls);
        return;
    }
    jstring jkey = env->NewStringUTF(key);
    jstring jval = (jkey && !env->ExceptionCheck()) ? env->NewStringUTF(val) : nullptr;
    if (jkey && jval && !env->ExceptionCheck()) {
        jobject prev = env->CallStaticObjectMethod(sys_cls, mid, jkey, jval);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (prev) env->DeleteLocalRef(prev);
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (jkey) env->DeleteLocalRef(jkey);
    if (jval) env->DeleteLocalRef(jval);
    env->DeleteLocalRef(sys_cls);
}

class KurokoModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    // Zero footprint inside EVERY application process!
    void preAppSpecialize(zygisk::AppSpecializeArgs*) override {
        api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs*) override {
        int sock = api_->connectCompanion();
        if (sock < 0) {
            status_ = "err:companion_connect";
            LOGW("preServerSpecialize: connectCompanion failed");
            return;
        }
        uint8_t cmd = 1;
        if (write(sock, &cmd, 1) != 1) {
            status_ = "err:companion_write";
            LOGW("preServerSpecialize: write to companion failed");
            close(sock);
            return;
        }
        uint8_t status = 0;
        int memfd = -1;
        if (!recv_fd_with_status(sock, &status, &memfd)) {
            status_ = "err:companion_recv";
            LOGW("preServerSpecialize: recv from companion failed");
            close(sock);
            return;
        }
        close(sock);

        if (status == 2) {
            status_ = "err:boot_guard";
            LOGE("preServerSpecialize: disabled by boot-guard");
            return;
        }
        if (status != 1 || memfd < 0) {
            status_ = "err:no_memfd";
            LOGW("preServerSpecialize: companion returned no memfd (status=%u fd=%d)", status, memfd);
            return;
        }

        void* ptr = mmap(nullptr, KUROKO_SHM_SIZE, PROT_READ, MAP_SHARED, memfd, 0);
        int saved_errno = errno;
        close(memfd); // mapping persists after fd close -> zero leaked fd!
        if (ptr != MAP_FAILED) {
            shm_ = reinterpret_cast<const KurokoShm*>(ptr);
            status_ = "ok:shm_mapped";
            LOGI("preServerSpecialize: mapped KurokoShm read-only at %p", ptr);
        } else {
            status_ = "err:mmap_errno_" + std::to_string(saved_errno);
            LOGE("preServerSpecialize: mmap failed: %s (errno=%d)", strerror(saved_errno), saved_errno);
        }
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs*) override {
        if (!shm_) {
            LOGW("postServerSpecialize: no SHM mapped (%s), unloading module cleanly", status_.c_str());
            set_java_system_property(env_, "kuroko.sensor.hook", status_.c_str());
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        std::string hook_status;
        bool ok = kuroko::install_sensor_hooks(api_, env_, shm_, &hook_status);
        status_ = hook_status.empty() ? (ok ? "ok:installed" : "wait:defer_got") : hook_status;
        set_java_system_property(env_, "kuroko.sensor.hook", status_.c_str());
        LOGI("postServerSpecialize: kuroko.sensor.hook=%s (ok=%d; module kept resident for self-healing GOT watchdog)",
             status_.c_str(), ok ? 1 : 0);
        // Never DLCLOSE_MODULE_LIBRARY once shm_ is mapped: StepInjectorThread + ELF64 GOT watchdog stay resident.
    }

private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    const KurokoShm* shm_ = nullptr;
    std::string status_ = "err:not_run";
};

} // namespace

REGISTER_ZYGISK_MODULE(KurokoModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
