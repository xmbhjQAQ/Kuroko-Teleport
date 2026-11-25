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

struct __attribute__((packed)) GmsMemfdReply {
    uint8_t status;
    int32_t daemon_pid;
    uint64_t shm_ino;
};
static_assert(sizeof(GmsMemfdReply) == 13, "GmsMemfdReply must be 13 bytes");

bool send_buf_with_fd(int sock, const void* buf, size_t len, int fd_to_send) {
    struct msghdr msg{};
    struct iovec iov{const_cast<void*>(buf), len};
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
    return sendmsg(sock, &msg, MSG_NOSIGNAL) == static_cast<ssize_t>(len);
}

bool recv_buf_with_fd(int sock, void* buf, size_t len, int* out_fd) {
    *out_fd = -1;
    struct msghdr msg{};
    struct iovec iov{buf, len};
    char cmsg_buf[CMSG_SPACE(sizeof(int))]{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf;
    msg.msg_controllen = sizeof(cmsg_buf);

    ssize_t n = recvmsg(sock, &msg, MSG_CMSG_CLOEXEC);
    if (n != static_cast<ssize_t>(len)) {
        for (struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
            if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS &&
                cmsg->cmsg_len >= CMSG_LEN(sizeof(int))) {
                int leaked = -1;
                std::memcpy(&leaked, CMSG_DATA(cmsg), sizeof(int));
                if (leaked >= 0) close(leaked);
            }
        }
        return false;
    }

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

bool send_fd_with_status(int sock, uint8_t status, int fd_to_send) {
    return send_buf_with_fd(sock, &status, 1, fd_to_send);
}

bool recv_fd_with_status(int sock, uint8_t* out_status, int* out_fd) {
    *out_status = 0;
    return recv_buf_with_fd(sock, out_status, 1, out_fd);
}

// Resolve installed com.google.android.gms appId (10000..99999) inside root Companion.
// Parses /data/system/packages.list and cross-checks dataDir stat(), with stat() fallback.
int resolve_installed_gms_app_id() {
    FILE* fp = std::fopen("/data/system/packages.list", "r");
    if (fp) {
        char line[1024];
        while (std::fgets(line, sizeof(line), fp)) {
            char pkg[128]{};
            unsigned long app_id_ul = 0;
            int debuggable = 0;
            char data_dir[256]{};
            int n = std::sscanf(line, "%127s %lu %d %255s", pkg, &app_id_ul, &debuggable, data_dir);
            if (n >= 2 && std::strcmp(pkg, "com.google.android.gms") == 0) {
                std::fclose(fp);
                if (app_id_ul < 10000UL || app_id_ul >= 100000UL) return -1;
                int app_id = static_cast<int>(app_id_ul);
                struct stat st{};
                const char* check_dir = (n >= 4 && data_dir[0] == '/')
                                            ? data_dir
                                            : "/data/user/0/com.google.android.gms";
                if (stat(check_dir, &st) == 0) {
                    return (static_cast<int>(st.st_uid % 100000u) == app_id) ? app_id : -1;
                }
                if (std::strcmp(check_dir, "/data/user/0/com.google.android.gms") != 0 &&
                    stat("/data/user/0/com.google.android.gms", &st) == 0) {
                    return (static_cast<int>(st.st_uid % 100000u) == app_id) ? app_id : -1;
                }
                return app_id;
            }
        }
        std::fclose(fp);
        return -1; // packages.list readable and com.google.android.gms is not installed
    }

    static const char* const kFallbackDirs[] = {
        "/data/user/0/com.google.android.gms",
        "/data/data/com.google.android.gms",
    };
    for (const char* dir : kFallbackDirs) {
        struct stat st{};
        if (stat(dir, &st) == 0) {
            uint32_t app_id = st.st_uid % 100000u;
            if (app_id >= 10000u && app_id < 100000u) {
                return static_cast<int>(app_id);
            }
        }
    }
    return -1;
}

// Runs inside root Companion process (cmd 1 = system_server, cmd 2 = GMS FLP)
int fetch_memfd_from_daemon(int max_attempts = 40,
                            int32_t* out_daemon_pid = nullptr,
                            uint64_t* out_shm_ino = nullptr) {
    if (out_daemon_pid) *out_daemon_pid = 0;
    if (out_shm_ino) *out_shm_ino = 0;
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
            int32_t peer_pid = 0;
            if (ok && memfd >= 0 && out_daemon_pid) {
                struct ucred cred{};
                socklen_t clen = sizeof(cred);
                if (getsockopt(s, SOL_SOCKET, SO_PEERCRED, &cred, &clen) == 0 && cred.pid > 0) {
                    peer_pid = static_cast<int32_t>(cred.pid);
                }
            }
            close(s);
            if (ok && memfd >= 0) {
                if (out_daemon_pid) *out_daemon_pid = peer_pid;
                if (out_shm_ino) {
                    struct stat st{};
                    if (fstat(memfd, &st) == 0) {
                        *out_shm_ino = static_cast<uint64_t>(st.st_ino);
                    }
                }
                return memfd;
            }
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

// Atomically write GMS hook telemetry + daemon-instance binding to KUROKO_GMS_STATUS_PATH (0600)
bool write_gms_status_atomic(int32_t pid, uint32_t uid, int32_t daemon_pid,
                             uint64_t shm_ino, const std::string& status) {
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

    char payload[448];
    int n = std::snprintf(payload, sizeof(payload),
                          "pid=%d\nuid=%u\ndaemon_pid=%d\nshm_ino=%llu\nstatus=%s\n",
                          pid, uid, daemon_pid,
                          static_cast<unsigned long long>(shm_ino), clean.c_str());
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
        // system_server memfd handoff (unchanged 1-byte protocol)
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
        // GMS FLP memfd handoff: strictly gated on caller_uid == installed GMS appId, boot-guard, gms_disabled
        uint32_t caller_uid = 0;
        GmsMemfdReply rep{0, 0, 0};
        if (!read_exact(client_sock, &caller_uid, sizeof(caller_uid))) {
            send_buf_with_fd(client_sock, &rep, sizeof(rep), -1);
            return;
        }
        if (!check_boot_guard_safe()) {
            rep.status = 2; // boot-guard tripped
            send_buf_with_fd(client_sock, &rep, sizeof(rep), -1);
            return;
        }
        if (access(KUROKO_GMS_DISABLED_PATH, F_OK) == 0) {
            rep.status = 3; // disabled by user
            send_buf_with_fd(client_sock, &rep, sizeof(rep), -1);
            return;
        }
        int gms_app_id = resolve_installed_gms_app_id();
        if (gms_app_id < 10000 || (caller_uid % 100000u) != static_cast<uint32_t>(gms_app_id)) {
            LOGW("Companion cmd=2 rejected spoofed GMS UID=%u (installed gms_app_id=%d)",
                 caller_uid, gms_app_id);
            rep.status = 4; // UID mismatch
            send_buf_with_fd(client_sock, &rep, sizeof(rep), -1);
            return;
        }
        int32_t daemon_pid = 0;
        uint64_t shm_ino = 0;
        int memfd = fetch_memfd_from_daemon(20, &daemon_pid, &shm_ino);
        if (memfd < 0) {
            LOGW("Companion could not fetch memfd from kuroko_daemon (cmd=2)");
            rep.status = 0;
            send_buf_with_fd(client_sock, &rep, sizeof(rep), -1);
            return;
        }
        rep.status = 1;
        rep.daemon_pid = daemon_pid;
        rep.shm_ino = shm_ino;
        send_buf_with_fd(client_sock, &rep, sizeof(rep), memfd);
        close(memfd);
        return;
    }

    if (cmd == 3) {
        // GMS hook status report: pid + gms_uid + daemon_pid + shm_ino + len + status bytes (<= 255B)
        int32_t pid = 0;
        uint32_t gms_uid = 0;
        int32_t daemon_pid = 0;
        uint64_t shm_ino = 0;
        uint16_t len = 0;
        if (!read_exact(client_sock, &pid, sizeof(pid)) ||
            !read_exact(client_sock, &gms_uid, sizeof(gms_uid)) ||
            !read_exact(client_sock, &daemon_pid, sizeof(daemon_pid)) ||
            !read_exact(client_sock, &shm_ino, sizeof(shm_ino)) ||
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

        int gms_app_id = resolve_installed_gms_app_id();
        if (gms_app_id < 10000 || (gms_uid % 100000u) != static_cast<uint32_t>(gms_app_id)) {
            LOGW("Companion cmd=3 rejected spoofed GMS UID=%u (installed gms_app_id=%d)",
                 gms_uid, gms_app_id);
            uint8_t ack = 0;
            write_exact(client_sock, &ack, 1);
            return;
        }
        uint8_t ack = write_gms_status_atomic(pid, gms_uid, daemon_pid, shm_ino, std::string(buf, len))
                          ? 1u : 0u;
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

bool is_gms_app_data_dir(const std::string& dir) {
    size_t end = dir.size();
    while (end > 0 && dir[end - 1] == '/') --end;
    constexpr const char kSuffix[] = "/com.google.android.gms";
    constexpr size_t kSuffixLen = sizeof(kSuffix) - 1;
    if (end < kSuffixLen) return false;
    return std::memcmp(dir.data() + (end - kSuffixLen), kSuffix, kSuffixLen) == 0;
}

class KurokoModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    // Zero footprint inside ALL apps except UID-verified GMS FLP process (com.google.android.gms / .persistent).
    // Unconditional immediate DLCLOSE_MODULE_LIBRARY on com.google.android.gms.unstable, .ui, spoofed processes, and every other app!
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

        // Fast client-side UID & app_data_dir verification before contacting Root Companion
        if (args->uid < 10000) {
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (args->app_data_dir) {
            const char* raw_dir = env_->GetStringUTFChars(args->app_data_dir, nullptr);
            std::string data_dir = raw_dir ? std::string(raw_dir) : std::string();
            if (raw_dir) {
                env_->ReleaseStringUTFChars(args->app_data_dir, raw_dir);
            }
            if (env_->ExceptionCheck()) env_->ExceptionClear();
            if (!is_gms_app_data_dir(data_dir)) {
                LOGW("preAppSpecialize(%s): rejected non-GMS app_data_dir (uid=%d); unloading",
                     proc_name.c_str(), args->uid);
                api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
                return;
            }
        }

        const uint32_t caller_uid = static_cast<uint32_t>(args->uid);
        is_gms_ = true;
        gms_proc_name_ = proc_name;

        int sock = api_->connectCompanion();
        if (sock < 0) {
            status_ = "err:companion_connect";
            LOGW("preAppSpecialize(%s): connectCompanion failed", gms_proc_name_.c_str());
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        uint8_t req[1 + sizeof(uint32_t)];
        req[0] = 2u;
        std::memcpy(req + 1, &caller_uid, sizeof(caller_uid));
        if (!write_exact(sock, req, sizeof(req))) {
            status_ = "err:companion_write";
            LOGW("preAppSpecialize(%s): write to companion failed", gms_proc_name_.c_str());
            close(sock);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        GmsMemfdReply rep{0, 0, 0};
        int memfd = -1;
        if (!recv_buf_with_fd(sock, &rep, sizeof(rep), &memfd)) {
            status_ = "err:companion_recv";
            LOGW("preAppSpecialize(%s): recv from companion failed", gms_proc_name_.c_str());
            close(sock);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        close(sock);

        if (rep.status == 4) {
            status_ = "err:uid_mismatch";
            LOGW("preAppSpecialize(%s): companion rejected UID=%u (not installed GMS); unloading",
                 gms_proc_name_.c_str(), caller_uid);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (rep.status == 3) {
            status_ = "disabled";
            LOGI("preAppSpecialize(%s): GMS FLP disabled via %s; unloading cleanly",
                 gms_proc_name_.c_str(), KUROKO_GMS_DISABLED_PATH);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (rep.status == 2) {
            status_ = "err:boot_guard";
            LOGE("preAppSpecialize(%s): disabled by boot-guard", gms_proc_name_.c_str());
            report_gms_status(static_cast<int32_t>(getpid()), caller_uid, 0, 0, status_);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (rep.status != 1 || memfd < 0) {
            status_ = "err:no_memfd";
            LOGW("preAppSpecialize(%s): companion returned no memfd (status=%u fd=%d)",
                 gms_proc_name_.c_str(), rep.status, memfd);
            report_gms_status(static_cast<int32_t>(getpid()), caller_uid, 0, 0, status_);
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
            report_gms_status(static_cast<int32_t>(getpid()), caller_uid,
                              rep.daemon_pid, rep.shm_ino, status_);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        shm_ = reinterpret_cast<const KurokoShm*>(ptr);

        // Install pure PT_GNU_RELRO vtable hooks synchronously (single-threaded, before unshare)
        // and report status + daemon binding via root Companion cmd 3 while connectCompanion() is permitted.
        std::string hook_status;
        bool ok = kuroko::install_gms_hooks(env_, shm_, gms_proc_name_, &hook_status, caller_uid);
        status_ = hook_status.empty() ? (ok ? "ok:vtable" : "err:hook_failed") : hook_status;
        report_gms_status(static_cast<int32_t>(getpid()), caller_uid,
                          rep.daemon_pid, rep.shm_ino, status_);

        if (!ok) {
            LOGW("preAppSpecialize(%s): GMS hook install failed (%s); unmapping & unloading",
                 gms_proc_name_.c_str(), status_.c_str());
            munmap(const_cast<KurokoShm*>(shm_), KUROKO_SHM_SIZE);
            shm_ = nullptr;
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        gms_hooked_ = true;
        LOGI("preAppSpecialize(%s): mapped KurokoShm at %p (daemon_pid=%d, ino=%llu), hooks active (%s)",
             gms_proc_name_.c_str(), ptr, rep.daemon_pid,
             static_cast<unsigned long long>(rep.shm_ino), status_.c_str());
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
    void report_gms_status(int32_t pid, uint32_t gms_uid, int32_t daemon_pid,
                           uint64_t shm_ino, const std::string& st) {
        int sock = api_->connectCompanion();
        if (sock < 0) {
            LOGW("report_gms_status: connectCompanion failed");
            return;
        }
        uint16_t len = static_cast<uint16_t>(st.size() > 255u ? 255u : st.size());
        uint8_t hdr[1 + sizeof(int32_t) + sizeof(uint32_t) + sizeof(int32_t) + sizeof(uint64_t) + sizeof(uint16_t)];
        size_t off = 0;
        hdr[off++] = 3u;
        std::memcpy(hdr + off, &pid, sizeof(pid));               off += sizeof(pid);
        std::memcpy(hdr + off, &gms_uid, sizeof(gms_uid));       off += sizeof(gms_uid);
        std::memcpy(hdr + off, &daemon_pid, sizeof(daemon_pid)); off += sizeof(daemon_pid);
        std::memcpy(hdr + off, &shm_ino, sizeof(shm_ino));       off += sizeof(shm_ino);
        std::memcpy(hdr + off, &len, sizeof(len));               off += sizeof(len);
        bool ok = write_exact(sock, hdr, off);
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
