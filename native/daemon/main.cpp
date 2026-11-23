#include "kuroko_shm.h"
#include "sim_engine.h"

#include <android/binder_ibinder.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <netinet/in.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <type_traits>
#include <vector>

#define LOG_TAG "KurokoDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef KUROKO_MODULE_VERSION_NAME
#define KUROKO_MODULE_VERSION_NAME "0.0.0"
#endif
#ifndef KUROKO_MODULE_VERSION_CODE
#define KUROKO_MODULE_VERSION_CODE 0
#endif
#ifndef KUROKO_IPC_PROTOCOL_VERSION
#define KUROKO_IPC_PROTOCOL_VERSION 2
#endif
#ifndef KUROKO_SHM_SCHEMA_VERSION
#define KUROKO_SHM_SCHEMA_VERSION KUROKO_SHM_VERSION
#endif
#ifndef KUROKO_MIN_COMPAT_APP_CODE
#define KUROKO_MIN_COMPAT_APP_CODE 0
#endif

static_assert(KUROKO_SHM_SCHEMA_VERSION == KUROKO_SHM_VERSION,
              "version.properties SHM_SCHEMA_VERSION must match KUROKO_SHM_VERSION");

namespace {

std::atomic<bool> g_running{true};
int g_memfd = -1;
int g_memfd_ro = -1;
KurokoShm* g_shm = nullptr;
std::string g_auth_token;
kuroko::SimEngine g_engine;
std::atomic<bool> g_loc_shm_ok{false};
std::atomic<bool> g_phone_shm_ok{false};
std::atomic<bool> g_sensor_shm_ok{false};
std::atomic<int32_t> g_xposed_code{0};
std::atomic<int32_t> g_zygisk_code{0};
std::mutex g_diag_mu;
std::string g_sensor_diag;
std::mutex g_log_mu;
constexpr const char* kDaemonLogPath = "/data/adb/kuroko/daemon.log";
constexpr const char* kLangFilePath  = "/data/adb/kuroko/lang";
constexpr const char* kLangTmpPath   = "/data/adb/kuroko/lang.tmp";

void write_daemon_log(bool truncate, const char* fmt, ...) {
    std::lock_guard<std::mutex> lk(g_log_mu);
    mkdir("/data/adb/kuroko", 0700);
    int flags = O_WRONLY | O_CREAT | O_CLOEXEC | (truncate ? O_TRUNC : O_APPEND);
    int fd = open(kDaemonLogPath, flags, 0600);
    if (fd < 0) return;

    char msg[384];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);

    struct timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    char line[512];
    int n = std::snprintf(line, sizeof(line), "[%ld.%03ld] %s\n",
                          static_cast<long>(ts.tv_sec), ts.tv_nsec / 1000000L, msg);
    if (n > 0) {
        size_t len = static_cast<size_t>(
            n < static_cast<int>(sizeof(line)) ? n : static_cast<int>(sizeof(line)) - 1);
        ssize_t w = write(fd, line, len);
        (void)w;
    }
    fchmod(fd, 0600);
    close(fd);
}

uint64_t now_boot_ns() {
    struct timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
}

uint64_t now_utc_ms() {
    struct timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000ULL + static_cast<uint64_t>(ts.tv_nsec) / 1000000ULL;
}

bool create_shared_mem() {
    g_memfd = static_cast<int>(syscall(__NR_memfd_create, "kuroko_shm", MFD_CLOEXEC));
    if (g_memfd < 0) {
        LOGE("memfd_create failed: %s", strerror(errno));
        return false;
    }
    if (ftruncate(g_memfd, KUROKO_SHM_SIZE) != 0) {
        LOGE("ftruncate failed: %s", strerror(errno));
        close(g_memfd);
        g_memfd = -1;
        return false;
    }
    void* ptr = mmap(nullptr, KUROKO_SHM_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, g_memfd, 0);
    if (ptr == MAP_FAILED) {
        LOGE("mmap failed: %s", strerror(errno));
        close(g_memfd);
        g_memfd = -1;
        return false;
    }
    std::memset(ptr, 0, KUROKO_SHM_SIZE);
    g_shm = reinterpret_cast<KurokoShm*>(ptr);
    g_shm->magic = KUROKO_SHM_MAGIC;
    g_shm->version = KUROKO_SHM_VERSION;
    g_shm->daemon_heartbeat_ns = now_boot_ns();

    char proc_fd[64];
    std::snprintf(proc_fd, sizeof(proc_fd), "/proc/self/fd/%d", g_memfd);
    g_memfd_ro = open(proc_fd, O_RDONLY | O_CLOEXEC);
    if (g_memfd_ro < 0) {
        LOGE("open(%s, O_RDONLY) failed: %s; falling back to RW memfd=%d",
             proc_fd, strerror(errno), g_memfd);
        g_memfd_ro = g_memfd;
    } else {
        LOGI("Opened read-only memfd handle=%d via %s", g_memfd_ro, proc_fd);
    }

    LOGI("Created memfd=%d (ro=%d) mapped at %p (%u bytes)", g_memfd, g_memfd_ro, ptr, KUROKO_SHM_SIZE);
    return true;
}

bool init_auth_token() {
    mkdir("/data/adb/kuroko", 0700);
    uint8_t raw[16]{};
    int rfd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (rfd >= 0) {
        ssize_t n = read(rfd, raw, sizeof(raw));
        (void)n;
        close(rfd);
    } else {
        uint64_t t = now_boot_ns();
        std::memcpy(raw, &t, sizeof(t));
    }
    char hex[33]{};
    for (int i = 0; i < 16; ++i) {
        std::snprintf(hex + i * 2, 3, "%02x", raw[i]);
    }
    g_auth_token = hex;

    int fd = open(KUROKO_TOKEN_FILE_PATH, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        LOGW("Failed to write token file %s: %s", KUROKO_TOKEN_FILE_PATH, strerror(errno));
        return false;
    }
    ssize_t w = write(fd, g_auth_token.data(), g_auth_token.size());
    (void)w;
    fchmod(fd, 0600);
    close(fd);
    LOGI("Wrote auth token to %s", KUROKO_TOKEN_FILE_PATH);
    return true;
}

bool send_fd_over_unix(int sock, int fd_to_send) {
    struct msghdr msg{};
    char payload = 'K';
    struct iovec iov{&payload, 1};
    char cmsg_buf[CMSG_SPACE(sizeof(int))]{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf;
    msg.msg_controllen = sizeof(cmsg_buf);

    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    std::memcpy(CMSG_DATA(cmsg), &fd_to_send, sizeof(int));

    return sendmsg(sock, &msg, MSG_NOSIGNAL) > 0;
}

void companion_unix_server_loop() {
    unlink(KUROKO_COMPANION_SOCK_PATH);
    int srv = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (srv < 0) {
        LOGE("AF_UNIX socket failed: %s", strerror(errno));
        return;
    }
    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, KUROKO_COMPANION_SOCK_PATH, sizeof(addr.sun_path) - 1);
    if (bind(srv, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0) {
        LOGE("bind %s failed: %s", KUROKO_COMPANION_SOCK_PATH, strerror(errno));
        close(srv);
        return;
    }
    chmod(KUROKO_COMPANION_SOCK_PATH, 0600);
    if (listen(srv, 8) != 0) {
        LOGE("listen %s failed: %s", KUROKO_COMPANION_SOCK_PATH, strerror(errno));
        close(srv);
        return;
    }
    LOGI("Companion Unix socket listening at %s", KUROKO_COMPANION_SOCK_PATH);

    while (g_running.load()) {
        int cli = accept4(srv, nullptr, nullptr, SOCK_CLOEXEC);
        if (cli < 0) {
            if (errno == EINTR) continue;
            usleep(100000);
            continue;
        }
        if (send_fd_over_unix(cli, g_memfd_ro)) {
            LOGI("Sent read-only memfd=%d to Zygisk Companion", g_memfd_ro);
            write_daemon_log(false, "companion_handoff ok memfd_ro=%d", g_memfd_ro);
        } else {
            LOGW("Failed to send memfd_ro=%d to Zygisk Companion: %s", g_memfd_ro, strerror(errno));
        }
        close(cli);
    }
    close(srv);
    unlink(KUROKO_COMPANION_SOCK_PATH);
}

/* ===== Dynamic libbinder_ndk.so memfd push to system_server ("location") & com.android.phone ("phone") ===== */
using FnClassOnCreate      = void* (*)(void* args);
using FnClassOnDestroy     = void (*)(void* userData);
using FnClassOnTransact    = binder_status_t (*)(AIBinder* binder, transaction_code_t code, const AParcel* in, AParcel* out);
using FnClassDefine        = AIBinder_Class* (*)(const char* interfaceDescriptor,
                                                 FnClassOnCreate onCreate,
                                                 FnClassOnDestroy onDestroy,
                                                 FnClassOnTransact onTransact);
using FnAssociateClass     = bool (*)(AIBinder* binder, const AIBinder_Class* clazz);
using FnCheckService       = AIBinder* (*)(const char* instance);
using FnDecStrong          = void (*)(AIBinder* binder);
using FnPrepareTx          = binder_status_t (*)(AIBinder* binder, AParcel** in);
using FnTransact           = binder_status_t (*)(AIBinder* binder, transaction_code_t code, AParcel** in, AParcel** out, binder_flags_t flags);
using FnWritePfd           = binder_status_t (*)(AParcel* parcel, int fd);
using FnWriteInt32         = binder_status_t (*)(AParcel* parcel, int32_t val);
using FnReadInt32          = binder_status_t (*)(const AParcel* parcel, int32_t* val);
using FnReadByteArray      = binder_status_t (*)(const AParcel* parcel, void* arrayData, AParcel_byteArrayAllocator allocator);
using FnDeleteParcel       = void (*)(AParcel* parcel);

static_assert(std::is_same_v<FnReadByteArray, decltype(&::AParcel_readByteArray)>,
              "FnReadByteArray must match NDK AParcel_readByteArray");

void* binder_stub_on_create(void*) { return nullptr; }
void binder_stub_on_destroy(void*) {}
binder_status_t binder_stub_on_transact(AIBinder*, transaction_code_t, const AParcel*, AParcel*) {
    return -EBADMSG; // STATUS_UNKNOWN_TRANSACTION (remote proxy classes only; never called locally)
}

bool diag_byte_array_allocator(void* array_data, int32_t length, int8_t** out_buffer) {
    auto* out = reinterpret_cast<std::string*>(array_data);
    if (!out || !out_buffer) return false;
    if (length <= 0) {
        out->clear();
        *out_buffer = nullptr;
        return length == 0 || length == -1;
    }
    if (length > 512) {
        out->clear();
        *out_buffer = nullptr;
        return false;
    }
    out->resize(static_cast<size_t>(length));
    *out_buffer = reinterpret_cast<int8_t*>(out->data());
    return true;
}

std::string json_escape(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 8);
    for (unsigned char c : in) {
        if (c == '"') {
            out += "\\\"";
        } else if (c == '\\') {
            out += "\\\\";
        } else if (c == '\n') {
            out += "\\n";
        } else if (c == '\r') {
            out += "\\r";
        } else if (c == '\t') {
            out += "\\t";
        } else if (c >= 0x20) {
            out.push_back(static_cast<char>(c));
        }
    }
    return out;
}

std::string diag_state_key(const std::string& diag) {
    // Key on version/mode/maps/core/disc/step-handle prefix (before per-tick c1/ad/se/ok counters)
    auto pos = diag.find(",c1=");
    return (pos != std::string::npos) ? diag.substr(0, pos) : diag;
}

int32_t parse_kv_int32(const std::string& s, const char* key) {
    size_t klen = std::strlen(key);
    size_t pos = 0;
    while (pos < s.size()) {
        size_t found = s.find(key, pos);
        if (found == std::string::npos) return 0;
        bool boundary_ok = (found == 0 || s[found - 1] == ',' || s[found - 1] == ':' || s[found - 1] == ' ');
        size_t eq_pos = found + klen;
        if (boundary_ok && eq_pos < s.size() && s[eq_pos] == '=') {
            const char* num_start = s.c_str() + eq_pos + 1;
            char* endp = nullptr;
            long v = std::strtol(num_start, &endp, 10);
            if (endp != num_start && v > 0 && v <= INT32_MAX) {
                return static_cast<int32_t>(v);
            }
        }
        pos = found + 1;
    }
    return 0;
}

struct BinderNdkApi {
    void* handle = nullptr;
    FnClassDefine classDefine = nullptr;
    FnAssociateClass associateClass = nullptr;
    FnCheckService checkService = nullptr;
    FnDecStrong decStrong = nullptr;
    FnPrepareTx prepareTx = nullptr;
    FnTransact transact = nullptr;
    FnWritePfd writePfd = nullptr;
    FnWriteInt32 writeInt32 = nullptr;
    FnReadInt32 readInt32 = nullptr;
    FnReadByteArray readByteArray = nullptr; // optional: missing does not fail init
    FnDeleteParcel deleteParcel = nullptr;

    AIBinder_Class* clazzLocation = nullptr;
    AIBinder_Class* clazzPhone = nullptr;

    bool init() {
        handle = dlopen("libbinder_ndk.so", RTLD_NOW);
        if (!handle) {
            LOGW("dlopen libbinder_ndk.so failed: %s", dlerror());
            return false;
        }
        classDefine    = reinterpret_cast<FnClassDefine>(dlsym(handle, "AIBinder_Class_define"));
        associateClass = reinterpret_cast<FnAssociateClass>(dlsym(handle, "AIBinder_associateClass"));
        checkService   = reinterpret_cast<FnCheckService>(dlsym(handle, "AServiceManager_checkService"));
        decStrong      = reinterpret_cast<FnDecStrong>(dlsym(handle, "AIBinder_decStrong"));
        prepareTx      = reinterpret_cast<FnPrepareTx>(dlsym(handle, "AIBinder_prepareTransaction"));
        transact       = reinterpret_cast<FnTransact>(dlsym(handle, "AIBinder_transact"));
        writePfd       = reinterpret_cast<FnWritePfd>(dlsym(handle, "AParcel_writeParcelFileDescriptor"));
        writeInt32     = reinterpret_cast<FnWriteInt32>(dlsym(handle, "AParcel_writeInt32"));
        readInt32      = reinterpret_cast<FnReadInt32>(dlsym(handle, "AParcel_readInt32"));
        readByteArray  = reinterpret_cast<FnReadByteArray>(dlsym(handle, "AParcel_readByteArray"));
        deleteParcel   = reinterpret_cast<FnDeleteParcel>(dlsym(handle, "AParcel_delete"));
        if (!classDefine || !associateClass || !checkService || !decStrong ||
            !prepareTx || !transact || !writePfd || !writeInt32 || !readInt32 || !deleteParcel) {
            LOGE("Missing required symbols in libbinder_ndk.so");
            return false;
        }
        clazzLocation = classDefine("android.location.ILocationManager",
                                    binder_stub_on_create, binder_stub_on_destroy, binder_stub_on_transact);
        clazzPhone    = classDefine("com.android.internal.telephony.ITelephony",
                                    binder_stub_on_create, binder_stub_on_destroy, binder_stub_on_transact);
        if (!clazzLocation || !clazzPhone) {
            LOGE("AIBinder_Class_define failed (location=%p phone=%p)", clazzLocation, clazzPhone);
            return false;
        }
        return true;
    }

    int32_t ping_service(const char* svc_name, const AIBinder_Class* clazz,
                         int32_t* out_sensor_ok = nullptr,
                         std::string* out_sensor_diag = nullptr) {
        if (out_sensor_ok) *out_sensor_ok = -1;
        if (out_sensor_diag) out_sensor_diag->clear();
        AIBinder* b = checkService(svc_name);
        if (!b) return -1;
        if (!clazz || !associateClass(b, clazz)) {
            LOGW("AIBinder_associateClass failed for service '%s'", svc_name);
            decStrong(b);
            return -1;
        }
        AParcel* in = nullptr;
        AParcel* out = nullptr;
        int32_t mapped = -1;
        if (prepareTx(b, &in) == 0 && in) {
            if (writeInt32(in, static_cast<int32_t>(KUROKO_SHM_MAGIC)) == 0 &&
                transact(b, KUROKO_BINDER_TX_PING, &in, &out, 0) == 0 && out) {
                int32_t exc = 0, val = 0;
                if (readInt32(out, &exc) == 0 && exc == 0 && readInt32(out, &val) == 0) {
                    mapped = val;
                    if (out_sensor_ok) {
                        int32_t sensor_val = 0;
                        // Optional 2nd int32 from v0.4.0+ ShmReader; older APKs return 1 int -> fallback 0
                        if (readInt32(out, &sensor_val) == 0) {
                            *out_sensor_ok = sensor_val;
                            // Optional 3rd UTF-8 byte[] from v0.4.1+ ShmReader; older APKs stop at 2 ints
                            if (out_sensor_diag && readByteArray) {
                                std::string diag_tmp;
                                if (readByteArray(out, &diag_tmp, diag_byte_array_allocator) == 0) {
                                    *out_sensor_diag = std::move(diag_tmp);
                                }
                            }
                        } else {
                            *out_sensor_ok = 0;
                        }
                    }
                }
            }
        }
        if (in) deleteParcel(in);
        if (out) deleteParcel(out);
        decStrong(b);
        return mapped;
    }

    bool push_shm_to_service(const char* svc_name, const AIBinder_Class* clazz, int memfd) {
        AIBinder* b = checkService(svc_name);
        if (!b) return false;
        if (!clazz || !associateClass(b, clazz)) {
            LOGW("AIBinder_associateClass failed for service '%s'", svc_name);
            decStrong(b);
            return false;
        }
        AParcel* in = nullptr;
        AParcel* out = nullptr;
        bool ok = false;
        if (prepareTx(b, &in) == 0 && in) {
            if (writeInt32(in, static_cast<int32_t>(KUROKO_SHM_MAGIC)) == 0 &&
                writeInt32(in, static_cast<int32_t>(KUROKO_SHM_SIZE)) == 0 &&
                writePfd(in, memfd) == 0 &&
                transact(b, KUROKO_BINDER_TX_SET_SHM, &in, &out, 0) == 0 && out) {
                int32_t exc = 0, ack = 0;
                if (readInt32(out, &exc) == 0 && exc == 0 && readInt32(out, &ack) == 0 &&
                    ack == static_cast<int32_t>(KUROKO_ACK_OK)) {
                    ok = true;
                }
            }
        }
        if (in) deleteParcel(in);
        if (out) deleteParcel(out);
        decStrong(b);
        return ok;
    }
};

void binder_pusher_loop() {
    BinderNdkApi api;
    if (!api.init()) {
        LOGE("BinderNdkApi init failed; LSPosed Binder SHM push disabled");
        write_daemon_log(false, "binder_init failed");
        return;
    }
    struct BinderTarget {
        const char* name;
        const AIBinder_Class* clazz;
        std::atomic<bool>* ok_flag;
    };
    const BinderTarget targets[] = {
        {"location", api.clazzLocation, &g_loc_shm_ok},
        {"phone",    api.clazzPhone,    &g_phone_shm_ok},
    };
    bool pushed[2] = {false, false};
    int32_t prev_sensor_state = -1;
    std::string prev_diag_key;
    bool logged_first_diag = false;

    while (g_running.load()) {
        for (int i = 0; i < 2; ++i) {
            int32_t sensor_ok = -1;
            std::string sensor_diag;
            int32_t state = api.ping_service(
                targets[i].name, targets[i].clazz,
                (i == 0) ? &sensor_ok : nullptr,
                (i == 0) ? &sensor_diag : nullptr);
            if (i == 0) {
                if (sensor_ok >= 0) {
                    bool s_ok = (sensor_ok != 0);
                    g_sensor_shm_ok.store(s_ok, std::memory_order_relaxed);
                    g_xposed_code.store(parse_kv_int32(sensor_diag, "xp"), std::memory_order_relaxed);
                    g_zygisk_code.store(parse_kv_int32(sensor_diag, "mv"), std::memory_order_relaxed);
                    if (!sensor_diag.empty()) {
                        std::lock_guard<std::mutex> dlk(g_diag_mu);
                        g_sensor_diag = sensor_diag;
                    }
                    bool state_changed = (sensor_ok != prev_sensor_state);
                    std::string cur_key = diag_state_key(sensor_diag);
                    bool diag_changed = !sensor_diag.empty() && (!logged_first_diag || cur_key != prev_diag_key);
                    if (state_changed || diag_changed) {
                        prev_sensor_state = sensor_ok;
                        if (!sensor_diag.empty()) {
                            prev_diag_key = cur_key;
                            logged_first_diag = true;
                        }
                        LOGI("Service 'location' reports sensor_shm=%d xp=%d mv=%d diag=%s",
                             sensor_ok,
                             g_xposed_code.load(std::memory_order_relaxed),
                             g_zygisk_code.load(std::memory_order_relaxed),
                             sensor_diag.c_str());
                        write_daemon_log(false, "sensor_shm state=%d xp=%d mv=%d diag=%s",
                                         sensor_ok,
                                         g_xposed_code.load(std::memory_order_relaxed),
                                         g_zygisk_code.load(std::memory_order_relaxed),
                                         sensor_diag.c_str());
                    }
                } else if (state < 0) {
                    g_sensor_shm_ok.store(false, std::memory_order_relaxed);
                    g_xposed_code.store(0, std::memory_order_relaxed);
                    g_zygisk_code.store(0, std::memory_order_relaxed);
                }
            }
            if (state == 1) {
                targets[i].ok_flag->store(true, std::memory_order_relaxed);
                if (!pushed[i]) {
                    LOGI("Service '%s' reports SHM already mapped", targets[i].name);
                    write_daemon_log(false, "binder_handoff ok service=%s mode=already_mapped memfd_ro=%d",
                                     targets[i].name, g_memfd_ro);
                    pushed[i] = true;
                }
            } else {
                pushed[i] = false;
                targets[i].ok_flag->store(false, std::memory_order_relaxed);
                if (api.push_shm_to_service(targets[i].name, targets[i].clazz, g_memfd_ro)) {
                    LOGI("Successfully pushed read-only memfd=%d to Binder service '%s'",
                         g_memfd_ro, targets[i].name);
                    write_daemon_log(false, "binder_handoff ok service=%s mode=pushed memfd_ro=%d",
                                     targets[i].name, g_memfd_ro);
                    pushed[i] = true;
                    targets[i].ok_flag->store(true, std::memory_order_relaxed);
                }
            }
        }
        bool active_now = g_engine.get_status().active;
        sleep((pushed[0] && pushed[1] && !active_now) ? 5 : 2);
    }
}

/* ===== Localhost TCP Control Protocol (127.0.0.1:28765) ===== */
std::string format_status_json() {
    kuroko::SimStatus s = g_engine.get_status();
    std::string diag_esc;
    {
        std::lock_guard<std::mutex> dlk(g_diag_mu);
        diag_esc = json_escape(g_sensor_diag);
    }
    char buf[2048];
    std::snprintf(
        buf, sizeof(buf),
        "{\"active\":%d,\"moving\":%d,\"loop\":%d,"
        "\"lat\":%.7f,\"lon\":%.7f,\"alt\":%.2f,"
        "\"speed\":%.3f,\"target_speed\":%.3f,\"bearing\":%.2f,\"acc\":%.2f,"
        "\"dist\":%.2f,\"route_len\":%.2f,\"max_dist\":%.2f,\"laps\":%u,\"max_laps\":%u,"
        "\"steps\":%llu,\"cadence\":%.1f,\"stride\":%.3f,"
        "\"mode\":%u,\"carry\":%u,\"jitter\":%.2f,\"pace_var\":%.3f,\"targets\":%u,"
        "\"loc_shm\":%d,\"phone_shm\":%d,\"sensor_shm\":%d,\"sensor_diag\":\"%s\","
        "\"mod_ver\":\"%s\",\"mod_code\":%d,\"proto_ver\":%d,\"shm_ver\":%d,"
        "\"min_app_code\":%d,\"xposed_code\":%d,\"zygisk_code\":%d}\n",
        s.active ? 1 : 0, s.moving ? 1 : 0, s.loop ? 1 : 0,
        s.lat, s.lon, s.alt,
        s.speed_mps, s.target_speed_mps, s.bearing_deg, s.h_accuracy_m,
        s.total_distance_m, s.route_length_m, s.max_distance_m, s.lap_count, s.max_laps,
        static_cast<unsigned long long>(s.virtual_steps), s.cadence_spm, s.stride_m,
        s.motion_mode, s.carry_mode, s.jitter_sigma_m, s.pace_var_ratio, s.target_count,
        g_loc_shm_ok.load(std::memory_order_relaxed) ? 1 : 0,
        g_phone_shm_ok.load(std::memory_order_relaxed) ? 1 : 0,
        g_sensor_shm_ok.load(std::memory_order_relaxed) ? 1 : 0,
        diag_esc.c_str(),
        KUROKO_MODULE_VERSION_NAME,
        KUROKO_MODULE_VERSION_CODE,
        KUROKO_IPC_PROTOCOL_VERSION,
        KUROKO_SHM_SCHEMA_VERSION,
        KUROKO_MIN_COMPAT_APP_CODE,
        g_xposed_code.load(std::memory_order_relaxed),
        g_zygisk_code.load(std::memory_order_relaxed));
    return std::string(buf);
}

bool is_valid_bcp47_tag(const std::string& tag) {
    if (tag.empty() || tag.size() > 16) return false;
    auto is_alpha = [](char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    };
    auto is_alnum = [&](char c) {
        return is_alpha(c) || (c >= '0' && c <= '9');
    };
    size_t i = 0;
    const size_t n = tag.size();
    while (i < n && is_alpha(tag[i])) ++i;
    if (i < 2 || i > 3) return false; // primary language subtag: 2..3 ASCII letters
    int subtags = 0;
    while (i < n) {
        if (tag[i] != '-') return false;
        ++i;
        ++subtags;
        if (subtags > 2) return false;
        size_t s_start = i;
        while (i < n && is_alnum(tag[i])) ++i;
        size_t s_len = i - s_start;
        if (s_len < 2 || s_len > 8) return false;
    }
    return true;
}

std::string handle_command(const std::string& line) {
    std::istringstream iss(line);
    std::string cmd;
    iss >> cmd;
    if (cmd == "PING") {
        return "OK PONG\n";
    } else if (cmd == "STATUS") {
        return format_status_json();
    } else if (cmd == "START") {
        g_engine.set_active(true);
        g_engine.set_moving(true);
        return "OK START\n";
    } else if (cmd == "HOLD") {
        g_engine.set_active(true);
        g_engine.set_moving(false);
        return "OK HOLD\n";
    } else if (cmd == "STOP") {
        g_engine.set_active(false);
        return "OK STOP\n";
    } else if (cmd == "RESET_STEPS") {
        g_engine.reset_steps();
        return "OK RESET_STEPS\n";
    } else if (cmd == "SET_POINT") {
        double lat = 0, lon = 0, alt = 45.0;
        if (!(iss >> lat >> lon)) return "ERR BAD_ARGS\n";
        iss >> alt;
        g_engine.set_point(lat, lon, alt);
        return "OK SET_POINT\n";
    } else if (cmd == "SET_SPEED") {
        float sp = 0.0f;
        if (!(iss >> sp)) return "ERR BAD_ARGS\n";
        g_engine.set_speed(sp);
        return "OK SET_SPEED\n";
    } else if (cmd == "SET_MODE") {
        uint32_t mode = KUROKO_MOTION_AUTO;
        uint32_t carry = KUROKO_CARRY_HAND_SWING;
        if (!(iss >> mode)) return "ERR BAD_ARGS\n";
        iss >> carry;
        g_engine.set_motion_mode(mode);
        g_engine.set_carry_mode(carry);
        return "OK SET_MODE\n";
    } else if (cmd == "SET_JITTER") {
        float j = 2.0f;
        if (!(iss >> j)) return "ERR BAD_ARGS\n";
        g_engine.set_jitter(j);
        return "OK SET_JITTER\n";
    } else if (cmd == "SET_PACE_VAR") {
        float ratio = 0.08f;
        if (!(iss >> ratio)) return "ERR BAD_ARGS\n";
        g_engine.set_pace_var(ratio);
        return "OK SET_PACE_VAR\n";
    } else if (cmd == "SET_LANG") {
        std::string lang;
        std::string extra;
        if (!(iss >> lang) || (iss >> extra)) return "ERR BAD_ARGS\n";
        if (lang == "auto") {
            unlink(kLangFilePath);
            LOGI("Cleared language override (%s -> auto)", kLangFilePath);
            return "OK SET_LANG\n";
        }
        if (is_valid_bcp47_tag(lang)) {
            mkdir("/data/adb/kuroko", 0700);
            chmod("/data/adb/kuroko", 0700);
            int fd = open(kLangTmpPath, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
            if (fd < 0) {
                LOGW("Failed to open %s: %s", kLangTmpPath, strerror(errno));
                return "ERR IO_FAILED\n";
            }
            std::string payload = lang + "\n";
            ssize_t w = write(fd, payload.data(), payload.size());
            fchmod(fd, 0600);
            close(fd);
            if (w != static_cast<ssize_t>(payload.size()) || rename(kLangTmpPath, kLangFilePath) != 0) {
                LOGW("Failed to atomically write %s: %s", kLangFilePath, strerror(errno));
                unlink(kLangTmpPath);
                return "ERR IO_FAILED\n";
            }
            LOGI("Set language override %s = %s", kLangFilePath, lang.c_str());
            return "OK SET_LANG\n";
        }
        return "ERR BAD_ARGS\n";
    } else if (cmd == "SET_TARGETS") {
        std::string spec;
        iss >> spec;
        std::vector<KurokoTarget> list;
        if (!spec.empty() && spec != "NONE") {
            std::stringstream ss(spec);
            std::string item;
            while (std::getline(ss, item, ',')) {
                auto colon = item.find(':');
                uint32_t uid = 0;
                uint32_t fl = KUROKO_POLICY_ALL_STRICT;
                if (colon == std::string::npos) {
                    uid = static_cast<uint32_t>(std::strtoul(item.c_str(), nullptr, 10));
                } else {
                    uid = static_cast<uint32_t>(std::strtoul(item.substr(0, colon).c_str(), nullptr, 10));
                    fl  = static_cast<uint32_t>(std::strtoul(item.substr(colon + 1).c_str(), nullptr, 0));
                }
                if (uid > 0) {
                    KurokoTarget t{uid, fl, 0};
                    list.push_back(t);
                }
            }
        }
        g_engine.set_targets(list);
        return "OK SET_TARGETS " + std::to_string(list.size()) + "\n";
    } else if (cmd == "SET_ROUTE") {
        int loop_i = 1;
        float max_dist = 0.0f;
        std::string tok3, tok4;
        if (!(iss >> loop_i >> max_dist >> tok3)) return "ERR BAD_ARGS\n";
        uint32_t max_laps = (loop_i != 0) ? 0u : 1u;
        std::string pts_str;
        if (iss >> tok4) {
            char* endp = nullptr;
            unsigned long parsed_laps = std::strtoul(tok3.c_str(), &endp, 10);
            if (!endp || *endp != '\0' || tok3.empty() || tok3[0] == '-') return "ERR BAD_ARGS\n";
            max_laps = static_cast<uint32_t>(parsed_laps);
            pts_str = tok4;
        } else {
            pts_str = tok3;
        }
        std::vector<kuroko::Waypoint> pts;
        std::stringstream ss(pts_str);
        std::string tok;
        while (std::getline(ss, tok, ';')) {
            if (tok.empty()) continue;
            double lat = 0.0, lon = 0.0, alt = 45.0;
            int n = std::sscanf(tok.c_str(), "%lf,%lf,%lf", &lat, &lon, &alt);
            if (n >= 2) {
                pts.push_back(kuroko::Waypoint{lat, lon, alt});
            }
        }
        if (pts.empty()) return "ERR EMPTY_ROUTE\n";
        g_engine.set_route(pts, loop_i != 0, max_dist, max_laps);
        return "OK SET_ROUTE " + std::to_string(pts.size()) + "\n";
    }
    return "ERR UNKNOWN_CMD\n";
}

void handle_tcp_client(int fd) {
    FILE* fp = fdopen(fd, "r");
    if (!fp) {
        close(fd);
        return;
    }
    bool authed = false;
    char* line_buf = nullptr;
    size_t line_cap = 0;

    while (g_running.load()) {
        ssize_t len = getline(&line_buf, &line_cap, fp);
        if (len <= 0) break;
        while (len > 0 && (line_buf[len - 1] == '\n' || line_buf[len - 1] == '\r')) {
            line_buf[--len] = '\0';
        }
        if (len == 0) continue;
        std::string line(line_buf, static_cast<size_t>(len));

        std::string resp;
        if (!authed) {
            if (line.rfind("AUTH ", 0) == 0 && line.substr(5) == g_auth_token) {
                authed = true;
                resp = "OK AUTH\n";
            } else {
                resp = "ERR UNAUTHORIZED\n";
                send(fd, resp.data(), resp.size(), MSG_NOSIGNAL);
                break;
            }
        } else {
            resp = handle_command(line);
        }
        if (send(fd, resp.data(), resp.size(), MSG_NOSIGNAL) <= 0) break;
    }

    free(line_buf);
    fclose(fp); // closes fd
}

void tcp_control_server_loop() {
    int srv = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (srv < 0) {
        LOGE("TCP socket failed: %s", strerror(errno));
        return;
    }
    int opt = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons(KUROKO_CTRL_TCP_PORT);

    if (bind(srv, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0) {
        LOGE("TCP bind 127.0.0.1:%d failed: %s", KUROKO_CTRL_TCP_PORT, strerror(errno));
        close(srv);
        return;
    }
    if (listen(srv, 16) != 0) {
        LOGE("TCP listen failed: %s", strerror(errno));
        close(srv);
        return;
    }
    LOGI("Control TCP server listening on 127.0.0.1:%d", KUROKO_CTRL_TCP_PORT);

    while (g_running.load()) {
        int cli = accept4(srv, nullptr, nullptr, SOCK_CLOEXEC);
        if (cli < 0) {
            if (errno == EINTR) continue;
            usleep(100000);
            continue;
        }
        std::thread(handle_tcp_client, cli).detach();
    }
    close(srv);
}

void handle_signal(int) {
    g_running.store(false);
}

} // namespace

int main(int argc, char** argv) {
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, handle_signal);
    signal(SIGINT, handle_signal);

    bool daemonize = false;
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--daemonize") == 0) {
            daemonize = true;
        }
    }

    // Create memfd & token BEFORE daemonizing so post-fs-data.sh can immediately proceed
    if (!create_shared_mem()) return 1;
    init_auth_token();

    if (daemonize) {
        pid_t pid = fork();
        if (pid < 0) return 1;
        if (pid > 0) return 0; // parent exits cleanly
        setsid();
        int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
            if (devnull > 2) close(devnull);
        }
    }

    LOGI("kuroko_daemon started (pid=%d, ver=%s(%d), proto=%d, shm=%d, memfd_rw=%d, memfd_ro=%d)",
         getpid(), KUROKO_MODULE_VERSION_NAME, KUROKO_MODULE_VERSION_CODE,
         KUROKO_IPC_PROTOCOL_VERSION, KUROKO_SHM_SCHEMA_VERSION, g_memfd, g_memfd_ro);
    write_daemon_log(true, "started pid=%d ver=%s(%d) proto=%d shm=%d memfd_rw=%d memfd_ro=%d ro_distinct=%d",
                     getpid(), KUROKO_MODULE_VERSION_NAME, KUROKO_MODULE_VERSION_CODE,
                     KUROKO_IPC_PROTOCOL_VERSION, KUROKO_SHM_SCHEMA_VERSION,
                     g_memfd, g_memfd_ro, (g_memfd_ro != g_memfd) ? 1 : 0);

    std::thread unix_thr(companion_unix_server_loop);
    std::thread binder_thr(binder_pusher_loop);
    std::thread tcp_thr(tcp_control_server_loop);
    unix_thr.detach();
    binder_thr.detach();
    tcp_thr.detach();

    // 50Hz real-time physics & Seqlock publisher loop (20ms tick)
    uint64_t prev_boot_ns = now_boot_ns();
    struct timespec next_tick{};
    clock_gettime(CLOCK_MONOTONIC, &next_tick);

    while (g_running.load()) {
        next_tick.tv_nsec += 20000000L; // 20ms = 50Hz
        if (next_tick.tv_nsec >= 1000000000L) {
            next_tick.tv_sec += 1;
            next_tick.tv_nsec -= 1000000000L;
        }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next_tick, nullptr);

        uint64_t cur_boot_ns = now_boot_ns();
        double dt_sec = static_cast<double>(cur_boot_ns - prev_boot_ns) * 1e-9;
        prev_boot_ns = cur_boot_ns;
        g_engine.step(dt_sec, cur_boot_ns, now_utc_ms(), g_shm);
    }

    LOGI("kuroko_daemon exiting");
    return 0;
}
