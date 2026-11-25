#include "gms_hook.h"
#include "kuroko_shm.h"
#include "sensor_hook.h"
#include "zygisk.hpp"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

#define LOG_TAG "KurokoZygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_gms_status_file_mu;

bool read_exact(int fd, void* buf, size_t len) {
    auto* p = reinterpret_cast<uint8_t*>(buf);
    size_t got = 0;
    while (got < len) {
        ssize_t r = read(fd, p + got, len - got);
        if (r < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (r == 0) return false;
        got += static_cast<size_t>(r);
    }
    return true;
}

bool write_exact(int fd, const void* buf, size_t len) {
    const auto* p = reinterpret_cast<const uint8_t*>(buf);
    size_t sent = 0;
    while (sent < len) {
        ssize_t w = write(fd, p + sent, len - sent);
        if (w < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (w == 0) return false;
        sent += static_cast<size_t>(w);
    }
    return true;
}

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

// Runs inside root Companion process (cmd 1 = system_server, cmd 2 = GMS FLP)
int fetch_memfd_from_daemon(int max_attempts = 40) {
    for (int attempt = 0; attempt < max_attempts; ++attempt) {
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
        usleep(100000); // 100ms backoff while daemon socket initializes
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
        LOGE("Boot-guard tripped (fails=%d >= 2): skipping hooks for safety!", fails);
        return false;
    }
    return true;
}

// Atomically write pid=<pid>\nstatus=<str>\n to KUROKO_GMS_STATUS_PATH (0600) inside root Companion
bool write_gms_status_atomic(int32_t pid, const std::string& status) {
    std::lock_guard<std::mutex> lk(g_gms_status_file_mu);
    mkdir("/data/adb/kuroko", 0700);
    chmod("/data/adb/kuroko", 0700);

    std::string clean;
    clean.reserve(status.size());
    for (unsigned char c : status) {
        if (c >= 0x20 && c < 0x7F) {
            clean.push_back(static_cast<char>(c));
        }
    }

    char payload[320];
    int n = std::snprintf(payload, sizeof(payload), "pid=%d\nstatus=%s\n", pid, clean.c_str());
    if (n <= 0) return false;
    size_t len = static_cast<size_t>(
        n < static_cast<int>(sizeof(payload)) ? n : static_cast<int>(sizeof(payload)) - 1);

    const char* tmp_path = KUROKO_GMS_STATUS_PATH ".tmp";
    int fd = open(tmp_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        LOGW("Companion failed to open %s: %s", tmp_path, strerror(errno));
        return false;
    }
    bool ok = write_exact(fd, payload, len);
    fchmod(fd, 0600);
    close(fd);
    if (!ok || rename(tmp_path, KUROKO_GMS_STATUS_PATH) != 0) {
        LOGW("Companion failed to commit %s: %s", KUROKO_GMS_STATUS_PATH, strerror(errno));
        unlink(tmp_path);
        return false;
    }
    return true;
}

void companion_handler(int client_sock) {
    uint8_t cmd = 0;
    if (!read_exact(client_sock, &cmd, 1)) {
        send_fd_with_status(client_sock, 0, -1);
        return;
    }

    if (cmd == 1) {
        // system_server memfd handoff
        if (!check_boot_guard_safe()) {
            send_fd_with_status(client_sock, 2, -1); // 2 = boot-guard tripped
            return;
        }
        int memfd = fetch_memfd_from_daemon(40);
        if (memfd < 0) {
            LOGW("Companion could not fetch memfd from kuroko_daemon (cmd=1)");
            send_fd_with_status(client_sock, 0, -1);
            return;
        }
        send_fd_with_status(client_sock, 1, memfd);
        close(memfd);
        return;
    }

    if (cmd == 2) {
        // GMS FLP memfd handoff (strictly gated on boot-guard & gms_disabled)
        if (!check_boot_guard_safe()) {
            send_fd_with_status(client_sock, 2, -1);
            return;
        }
        if (access(KUROKO_GMS_DISABLED_PATH, F_OK) == 0) {
            send_fd_with_status(client_sock, 3, -1); // 3 = disabled by user
            return;
        }
        int memfd = fetch_memfd_from_daemon(20);
        if (memfd < 0) {
            LOGW("Companion could not fetch memfd from kuroko_daemon (cmd=2)");
            send_fd_with_status(client_sock, 0, -1);
            return;
        }
        send_fd_with_status(client_sock, 1, memfd);
        close(memfd);
        return;
    }

    if (cmd == 3) {
        // GMS hook status report: int32 pid + uint16 len + status bytes (<= 255B)
        int32_t pid = 0;
        uint16_t len = 0;
        if (!read_exact(client_sock, &pid, sizeof(pid)) ||
            !read_exact(client_sock, &len, sizeof(len)) ||
            len > 255u) {
            uint8_t ack = 0;
            write_exact(client_sock, &ack, 1);
            return;
        }
        char buf[256]{};
        if (len > 0 && !read_exact(client_sock, buf, len)) {
            uint8_t ack = 0;
            write_exact(client_sock, &ack, 1);
            return;
        }
        buf[len] = '\0';
        uint8_t ack = write_gms_status_atomic(pid, std::string(buf, len)) ? 1u : 0u;
        write_exact(client_sock, &ack, 1);
        return;
    }

    send_fd_with_status(client_sock, 0, -1);
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

bool is_gms_flp_process(const std::string& nice_name) {
    // STRICT process allowlist: ONLY GMS location service process(es).
    // NEVER match com.google.android.gms.unstable (DroidGuard / Play Integrity), .ui, or any other app!
    return nice_name == "com.google.android.gms" ||
           nice_name == "com.google.android.gms.persistent";
}

class KurokoModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    // Zero footprint inside ALL apps except GMS FLP process (com.google.android.gms / .persistent).
    // Unconditional immediate DLCLOSE_MODULE_LIBRARY on com.google.android.gms.unstable, .ui, and every other app!
    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        if (!args || !args->nice_name || !env_) {
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        const char* raw_name = env_->GetStringUTFChars(args->nice_name, nullptr);
        std::string proc_name = raw_name ? std::string(raw_name) : std::string();
        if (raw_name) {
            env_->ReleaseStringUTFChars(args->nice_name, raw_name);
        }
        if (env_->ExceptionCheck()) env_->ExceptionClear();

        if (!is_gms_flp_process(proc_name)) {
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        is_gms_ = true;
        gms_proc_name_ = proc_name;

        int sock = api_->connectCompanion();
        if (sock < 0) {
            status_ = "err:companion_connect";
            LOGW("preAppSpecialize(%s): connectCompanion failed", gms_proc_name_.c_str());
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        uint8_t cmd = 2;
        if (!write_exact(sock, &cmd, 1)) {
            status_ = "err:companion_write";
            LOGW("preAppSpecialize(%s): write to companion failed", gms_proc_name_.c_str());
            close(sock);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        uint8_t status = 0;
        int memfd = -1;
        if (!recv_fd_with_status(sock, &status, &memfd)) {
            status_ = "err:companion_recv";
            LOGW("preAppSpecialize(%s): recv from companion failed", gms_proc_name_.c_str());
            close(sock);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        close(sock);

        if (status == 3) {
            status_ = "disabled";
            LOGI("preAppSpecialize(%s): GMS FLP disabled via %s; unloading cleanly",
                 gms_proc_name_.c_str(), KUROKO_GMS_DISABLED_PATH);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (status == 2) {
            status_ = "err:boot_guard";
            LOGE("preAppSpecialize(%s): disabled by boot-guard", gms_proc_name_.c_str());
            report_gms_status(static_cast<int32_t>(getpid()), status_);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (status != 1 || memfd < 0) {
            status_ = "err:no_memfd";
            LOGW("preAppSpecialize(%s): companion returned no memfd (status=%u fd=%d)",
                 gms_proc_name_.c_str(), status, memfd);
            report_gms_status(static_cast<int32_t>(getpid()), status_);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        void* ptr = mmap(nullptr, KUROKO_SHM_SIZE, PROT_READ, MAP_SHARED, memfd, 0);
        int saved_errno = errno;
        close(memfd); // mapping persists after fd close -> zero leaked fd!
        if (ptr == MAP_FAILED) {
            status_ = "err:mmap_errno_" + std::to_string(saved_errno);
            LOGE("preAppSpecialize(%s): mmap failed: %s (errno=%d)",
                 gms_proc_name_.c_str(), strerror(saved_errno), saved_errno);
            report_gms_status(static_cast<int32_t>(getpid()), status_);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        shm_ = reinterpret_cast<const KurokoShm*>(ptr);

        // Install pure PT_GNU_RELRO vtable hooks synchronously (single-threaded, before unshare)
        // and report status via root Companion cmd 3 while Zygote SELinux context allows connectCompanion().
        std::string hook_status;
        uint32_t self_uid = (args->uid > 0) ? static_cast<uint32_t>(args->uid) : 0u;
        bool ok = kuroko::install_gms_hooks(env_, shm_, gms_proc_name_, &hook_status, self_uid);
        status_ = hook_status.empty() ? (ok ? "ok:vtable" : "err:hook_failed") : hook_status;
        report_gms_status(static_cast<int32_t>(getpid()), status_);

        if (!ok) {
            LOGW("preAppSpecialize(%s): GMS hook install failed (%s); unmapping & unloading",
                 gms_proc_name_.c_str(), status_.c_str());
            munmap(const_cast<KurokoShm*>(shm_), KUROKO_SHM_SIZE);
            shm_ = nullptr;
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        gms_hooked_ = true;
        LOGI("preAppSpecialize(%s): mapped KurokoShm at %p, hooks active (%s)",
             gms_proc_name_.c_str(), ptr, status_.c_str());
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs*) override {
        if (!is_gms_) return;
        if (!gms_hooked_ || !shm_) {
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        // Start Kuroko-1Hz-GMS daemon thread strictly AFTER SpecializeCommon finishes
        kuroko::start_gms_pump_thread();
        LOGI("postAppSpecialize(%s): Kuroko-1Hz-GMS pump active (%s)",
             gms_proc_name_.c_str(), status_.c_str());
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs*) override {
        int sock = api_->connectCompanion();
        if (sock < 0) {
            status_ = "err:companion_connect";
            LOGW("preServerSpecialize: connectCompanion failed");
            return;
        }
        uint8_t cmd = 1;
        if (!write_exact(sock, &cmd, 1)) {
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
    void report_gms_status(int32_t pid, const std::string& st) {
        int sock = api_->connectCompanion();
        if (sock < 0) {
            LOGW("report_gms_status: connectCompanion failed");
            return;
        }
        uint16_t len = static_cast<uint16_t>(st.size() > 255u ? 255u : st.size());
        uint8_t hdr[1 + sizeof(int32_t) + sizeof(uint16_t)];
        hdr[0] = 3u;
        std::memcpy(hdr + 1, &pid, sizeof(int32_t));
        std::memcpy(hdr + 1 + sizeof(int32_t), &len, sizeof(uint16_t));
        bool ok = write_exact(sock, hdr, sizeof(hdr));
        if (ok && len > 0) {
            ok = write_exact(sock, st.data(), len);
        }
        if (ok) {
            uint8_t ack = 0;
            (void)read_exact(sock, &ack, 1);
        }
        close(sock);
    }

    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    const KurokoShm* shm_ = nullptr;
    std::string status_ = "err:not_run";
    bool is_gms_ = false;
    bool gms_hooked_ = false;
    std::string gms_proc_name_;
};

} // namespace

REGISTER_ZYGISK_MODULE(KurokoModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
