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
#include <cmath>
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
#define KUROKO_IPC_PROTOCOL_VERSION 3
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
uint64_t g_memfd_ino = 0;
KurokoShm* g_shm = nullptr;
std::string g_auth_token;
kuroko::SimEngine g_engine;
std::atomic<bool> g_loc_shm_ok{false};
std::atomic<bool> g_phone_shm_ok{false};
std::atomic<bool> g_sensor_shm_ok{false};
std::atomic<bool> g_bt_shm_ok{false};
std::atomic<int32_t> g_xposed_code{0};
std::atomic<int32_t> g_zygisk_code{0};
std::mutex g_diag_mu;
std::string g_sensor_diag;
std::mutex g_log_mu;
constexpr const char* kDaemonLogPath      = "/data/adb/kuroko/daemon.log";
constexpr const char* kDaemonLogTmpPath   = "/data/adb/kuroko/daemon.log.tmp";
constexpr off_t       kDaemonLogMaxBytes  = 262144; // 256 KB cap
constexpr size_t      kDaemonLogKeepBytes = 131072; // 128 KB retained tail
constexpr const char* kLangFilePath       = "/data/adb/kuroko/lang";
constexpr const char* kLangTmpPath        = "/data/adb/kuroko/lang.tmp";

void rotate_daemon_log_if_needed_locked() {
    struct stat st{};
    if (stat(kDaemonLogPath, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= kDaemonLogMaxBytes) {
        return;
    }

    int rfd = open(kDaemonLogPath, O_RDONLY | O_CLOEXEC);
    if (rfd < 0) return;

    off_t seek_off = (st.st_size > static_cast<off_t>(kDaemonLogKeepBytes))
                         ? (st.st_size - static_cast<off_t>(kDaemonLogKeepBytes))
                         : 0;
    if (lseek(rfd, seek_off, SEEK_SET) < 0) {
        close(rfd);
        return;
    }

    std::vector<char> buf(kDaemonLogKeepBytes);
    size_t total_read = 0;
    while (total_read < buf.size()) {
        ssize_t r = read(rfd, buf.data() + total_read, buf.size() - total_read);
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (r == 0) break;
        total_read += static_cast<size_t>(r);
    }
    close(rfd);

    size_t start_off = 0;
    if (seek_off > 0 && total_read > 0) {
        const void* nl = std::memchr(buf.data(), '\n', total_read);
        if (nl) {
            size_t next_off = static_cast<size_t>(static_cast<const char*>(nl) - buf.data()) + 1;
            if (next_off < total_read) {
                start_off = next_off;
            }
        }
    }

    int tfd = open(kDaemonLogTmpPath, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (tfd < 0) return;

    bool write_ok = true;
    size_t remaining = total_read - start_off;
    const char* ptr = buf.data() + start_off;
    while (remaining > 0) {
        ssize_t w = write(tfd, ptr, remaining);
        if (w < 0) {
            if (errno == EINTR) continue;
            write_ok = false;
            break;
        }
        ptr += w;
        remaining -= static_cast<size_t>(w);
    }
    fchmod(tfd, 0600);
    close(tfd);

    if (!write_ok || rename(kDaemonLogTmpPath, kDaemonLogPath) != 0) {
        unlink(kDaemonLogTmpPath);
    }
}

void write_daemon_log(bool truncate, const char* fmt, ...) {
    std::lock_guard<std::mutex> lk(g_log_mu);
    mkdir("/data/adb/kuroko", 0700);
    if (!truncate) {
        rotate_daemon_log_if_needed_locked();
    }
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

    struct stat mst{};
    if (fstat(g_memfd, &mst) == 0) {
        g_memfd_ino = static_cast<uint64_t>(mst.st_ino);
    }
    LOGI("Created memfd=%d (ro=%d, ino=%llu) mapped at %p (%u bytes)",
         g_memfd, g_memfd_ro, static_cast<unsigned long long>(g_memfd_ino), ptr, KUROKO_SHM_SIZE);
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

/* ===== Dynamic libbinder_ndk.so memfd push to system_server ("location"), com.android.phone ("phone") & optional com.android.bluetooth ===== */
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
using FnReadStrongBinder   = binder_status_t (*)(const AParcel* parcel, AIBinder** binder);
using FnDeleteParcel       = void (*)(AParcel* parcel);

static_assert(std::is_same_v<FnReadByteArray, decltype(&::AParcel_readByteArray)>,
              "FnReadByteArray must match NDK AParcel_readByteArray");
static_assert(std::is_same_v<FnReadStrongBinder, decltype(&::AParcel_readStrongBinder)>,
              "FnReadStrongBinder must match NDK AParcel_readStrongBinder");

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
    FnReadByteArray readByteArray = nullptr;       // optional: missing does not fail init
    FnReadStrongBinder readStrongBinder = nullptr; // optional: missing disables BT binder handoff
    FnDeleteParcel deleteParcel = nullptr;

    AIBinder_Class* clazzLocation = nullptr;
    AIBinder_Class* clazzPhone = nullptr;
    AIBinder_Class* clazzBluetooth = nullptr;
    AIBinder_Class* clazzBluetoothAdapter = nullptr;

    bool init() {
        handle = dlopen("libbinder_ndk.so", RTLD_NOW);
        if (!handle) {
            LOGW("dlopen libbinder_ndk.so failed: %s", dlerror());
            return false;
        }
        classDefine      = reinterpret_cast<FnClassDefine>(dlsym(handle, "AIBinder_Class_define"));
        associateClass   = reinterpret_cast<FnAssociateClass>(dlsym(handle, "AIBinder_associateClass"));
        checkService     = reinterpret_cast<FnCheckService>(dlsym(handle, "AServiceManager_checkService"));
        decStrong        = reinterpret_cast<FnDecStrong>(dlsym(handle, "AIBinder_decStrong"));
        prepareTx        = reinterpret_cast<FnPrepareTx>(dlsym(handle, "AIBinder_prepareTransaction"));
        transact         = reinterpret_cast<FnTransact>(dlsym(handle, "AIBinder_transact"));
        writePfd         = reinterpret_cast<FnWritePfd>(dlsym(handle, "AParcel_writeParcelFileDescriptor"));
        writeInt32       = reinterpret_cast<FnWriteInt32>(dlsym(handle, "AParcel_writeInt32"));
        readInt32        = reinterpret_cast<FnReadInt32>(dlsym(handle, "AParcel_readInt32"));
        readByteArray    = reinterpret_cast<FnReadByteArray>(dlsym(handle, "AParcel_readByteArray"));
        readStrongBinder = reinterpret_cast<FnReadStrongBinder>(dlsym(handle, "AParcel_readStrongBinder"));
        deleteParcel     = reinterpret_cast<FnDeleteParcel>(dlsym(handle, "AParcel_delete"));
        if (!classDefine || !associateClass || !checkService || !decStrong ||
            !prepareTx || !transact || !writePfd || !writeInt32 || !readInt32 || !deleteParcel) {
            LOGE("Missing required symbols in libbinder_ndk.so");
            return false;
        }
        clazzLocation  = classDefine("android.location.ILocationManager",
                                     binder_stub_on_create, binder_stub_on_destroy, binder_stub_on_transact);
        clazzPhone     = classDefine("com.android.internal.telephony.ITelephony",
                                     binder_stub_on_create, binder_stub_on_destroy, binder_stub_on_transact);
        clazzBluetooth        = classDefine("android.bluetooth.IBluetooth",
                                            binder_stub_on_create, binder_stub_on_destroy, binder_stub_on_transact);
        clazzBluetoothAdapter = classDefine("android.bluetooth.IAdapter",
                                            binder_stub_on_create, binder_stub_on_destroy, binder_stub_on_transact);
        if (!clazzLocation || !clazzPhone) {
            LOGE("AIBinder_Class_define failed (location=%p phone=%p)", clazzLocation, clazzPhone);
            return false;
        }
        if ((!clazzBluetooth && !clazzBluetoothAdapter) || !readStrongBinder) {
            LOGW("Optional Bluetooth binder bridge unavailable (bt=%p adapter=%p readStrongBinder=%p)",
                 clazzBluetooth, clazzBluetoothAdapter, reinterpret_cast<void*>(readStrongBinder));
        }
        return true;
    }

    const AIBinder_Class* resolve_bluetooth_class(AIBinder* b) {
        if (!b) return nullptr;
        if (clazzBluetooth && associateClass(b, clazzBluetooth)) {
            return clazzBluetooth;
        }
        if (clazzBluetoothAdapter && associateClass(b, clazzBluetoothAdapter)) {
            return clazzBluetoothAdapter;
        }
        return nullptr;
    }

    int32_t ping_binder(AIBinder* b, const AIBinder_Class* clazz, const char* label,
                        int32_t* out_sensor_ok = nullptr,
                        std::string* out_sensor_diag = nullptr,
                        bool quiet = false) {
        if (out_sensor_ok) *out_sensor_ok = -1;
        if (out_sensor_diag) out_sensor_diag->clear();
        if (!b) return -1;
        if (!clazz || !associateClass(b, clazz)) {
            if (!quiet) {
                LOGW("AIBinder_associateClass failed for service '%s'", label);
            }
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
        return mapped;
    }

    int32_t ping_service(const char* svc_name, const AIBinder_Class* clazz,
                         int32_t* out_sensor_ok = nullptr,
                         std::string* out_sensor_diag = nullptr) {
        if (out_sensor_ok) *out_sensor_ok = -1;
        if (out_sensor_diag) out_sensor_diag->clear();
        AIBinder* b = checkService(svc_name);
        if (!b) return -1;
        int32_t mapped = ping_binder(b, clazz, svc_name, out_sensor_ok, out_sensor_diag, false);
        decStrong(b);
        return mapped;
    }

    bool push_shm_to_binder(AIBinder* b, const AIBinder_Class* clazz, const char* label,
                            int memfd, bool quiet = false) {
        if (!b) return false;
        if (!clazz || !associateClass(b, clazz)) {
            if (!quiet) {
                LOGW("AIBinder_associateClass failed for service '%s'", label);
            }
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
        return ok;
    }

    bool push_shm_to_service(const char* svc_name, const AIBinder_Class* clazz, int memfd) {
        AIBinder* b = checkService(svc_name);
        if (!b) return false;
        bool ok = push_shm_to_binder(b, clazz, svc_name, memfd, false);
        decStrong(b);
        return ok;
    }

    AIBinder* query_bluetooth_binder() {
        if (!readStrongBinder || (!clazzBluetooth && !clazzBluetoothAdapter) || !clazzLocation) return nullptr;
        AIBinder* loc = checkService("location");
        if (!loc) return nullptr;
        if (!associateClass(loc, clazzLocation)) {
            decStrong(loc);
            return nullptr;
        }
        AParcel* in = nullptr;
        AParcel* out = nullptr;
        AIBinder* bt = nullptr;
        if (prepareTx(loc, &in) == 0 && in) {
            if (writeInt32(in, static_cast<int32_t>(KUROKO_SHM_MAGIC)) == 0 &&
                transact(loc, KUROKO_BINDER_TX_GET_BT_BINDER, &in, &out, 0) == 0 && out) {
                int32_t exc = 0;
                AIBinder* raw_bt = nullptr;
                if (readInt32(out, &exc) == 0 && exc == 0 &&
                    readStrongBinder(out, &raw_bt) == 0 && raw_bt != nullptr) {
                    bt = raw_bt;
                }
            }
        }
        if (in) deleteParcel(in);
        if (out) deleteParcel(out);
        decStrong(loc);
        return bt;
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
    bool bt_pushed = false;
    int32_t last_bt_fail_state = 1;
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

        // Optional Bluetooth SHM handoff via system_server cached IBluetooth/IAdapter binder
        if (pushed[0]) {
            AIBinder* bt = api.query_bluetooth_binder();
            if (bt) {
                const AIBinder_Class* bt_clazz = api.resolve_bluetooth_class(bt);
                int32_t bt_state = bt_clazz
                    ? api.ping_binder(bt, bt_clazz, "bluetooth", nullptr, nullptr, /*quiet=*/true)
                    : -2;
                if (bt_state == 1) {
                    last_bt_fail_state = 1;
                    g_bt_shm_ok.store(true, std::memory_order_relaxed);
                    if (!bt_pushed) {
                        LOGI("Service 'bluetooth' reports SHM already mapped");
                        write_daemon_log(false, "binder_handoff ok service=bluetooth mode=already_mapped memfd_ro=%d",
                                         g_memfd_ro);
                        bt_pushed = true;
                    }
                } else if (bt_state == 0 && bt_clazz) {
                    bt_pushed = false;
                    g_bt_shm_ok.store(false, std::memory_order_relaxed);
                    if (api.push_shm_to_binder(bt, bt_clazz, "bluetooth", g_memfd_ro, /*quiet=*/true)) {
                        last_bt_fail_state = 1;
                        LOGI("Successfully pushed read-only memfd=%d to Binder service 'bluetooth'",
                             g_memfd_ro);
                        write_daemon_log(false, "binder_handoff ok service=bluetooth mode=pushed memfd_ro=%d",
                                         g_memfd_ro);
                        bt_pushed = true;
                        g_bt_shm_ok.store(true, std::memory_order_relaxed);
                    } else if (last_bt_fail_state != -3) {
                        last_bt_fail_state = -3;
                        write_daemon_log(false, "binder_handoff warn service=bluetooth push_failed memfd_ro=%d",
                                         g_memfd_ro);
                    }
                } else {
                    if (bt_pushed) {
                        LOGI("Service 'bluetooth' hook unreachable (state=%d); marking bt_shm=0", bt_state);
                        write_daemon_log(false, "binder_handoff lost service=bluetooth state=%d", bt_state);
                        bt_pushed = false;
                    } else if (bt_state != last_bt_fail_state) {
                        last_bt_fail_state = bt_state;
                        write_daemon_log(false, "binder_handoff warn service=bluetooth state=%d", bt_state);
                    }
                    g_bt_shm_ok.store(false, std::memory_order_relaxed);
                }
                api.decStrong(bt);
            } else {
                if (bt_pushed) {
                    LOGI("Service 'bluetooth' binder cleared (BT off or restarting); marking bt_shm=0");
                    write_daemon_log(false, "binder_handoff cleared service=bluetooth");
                    bt_pushed = false;
                }
                last_bt_fail_state = 1;
                g_bt_shm_ok.store(false, std::memory_order_relaxed);
            }
        } else {
            bt_pushed = false;
            last_bt_fail_state = 1;
            g_bt_shm_ok.store(false, std::memory_order_relaxed);
        }

        bool active_now = g_engine.get_status().active;
        sleep((pushed[0] && pushed[1] && !active_now) ? 5 : 2);
    }
}

/* ===== GMS FLP Live Telemetry Reader (UID + Current Daemon Instance Verified) ===== */
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

void read_gms_status(bool* out_gms_shm_ok, bool* out_gms_enabled, std::string* out_gms_diag) {
    bool enabled = (access(KUROKO_GMS_DISABLED_PATH, F_OK) != 0);
    if (out_gms_enabled) *out_gms_enabled = enabled;
    if (out_gms_shm_ok) *out_gms_shm_ok = false;
    if (out_gms_diag) out_gms_diag->clear();

    FILE* fp = std::fopen(KUROKO_GMS_STATUS_PATH, "r");
    if (!fp) {
        if (!enabled && out_gms_diag) *out_gms_diag = "disabled";
        return;
    }

    int32_t pid = 0;
    uint32_t reported_uid = 0;
    int32_t daemon_pid = 0;
    uint64_t shm_ino = 0;
    std::string status;
    char line[320];
    while (std::fgets(line, sizeof(line), fp)) {
        size_t len = std::strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
            line[--len] = '\0';
        }
        if (std::strncmp(line, "pid=", 4) == 0) {
            pid = static_cast<int32_t>(std::strtol(line + 4, nullptr, 10));
        } else if (std::strncmp(line, "uid=", 4) == 0) {
            reported_uid = static_cast<uint32_t>(std::strtoul(line + 4, nullptr, 10));
        } else if (std::strncmp(line, "daemon_pid=", 11) == 0) {
            daemon_pid = static_cast<int32_t>(std::strtol(line + 11, nullptr, 10));
        } else if (std::strncmp(line, "shm_ino=", 8) == 0) {
            shm_ino = static_cast<uint64_t>(std::strtoull(line + 8, nullptr, 10));
        } else if (std::strncmp(line, "status=", 7) == 0) {
            status = std::string(line + 7);
        }
    }
    std::fclose(fp);

    bool proc_verified = false;
    if (pid > 0 && kill(static_cast<pid_t>(pid), 0) == 0) {
        int gms_app_id = resolve_installed_gms_app_id();
        char proc_dir[64];
        std::snprintf(proc_dir, sizeof(proc_dir), "/proc/%d", pid);
        struct stat pst{};
        if (gms_app_id >= 10000 &&
            stat(proc_dir, &pst) == 0 &&
            (pst.st_uid % 100000u) == static_cast<uint32_t>(gms_app_id) &&
            (reported_uid == 0 || reported_uid == static_cast<uint32_t>(pst.st_uid))) {
            char cmdline_path[64];
            std::snprintf(cmdline_path, sizeof(cmdline_path), "/proc/%d/cmdline", pid);
            int cfd = open(cmdline_path, O_RDONLY | O_CLOEXEC);
            if (cfd >= 0) {
                char cmdline[128]{};
                ssize_t n = read(cfd, cmdline, sizeof(cmdline) - 1);
                close(cfd);
                if (n > 0) {
                    cmdline[n] = '\0';
                    // Exact match against GMS location process(es); never accept recycled PIDs (e.g. .unstable)
                    if (std::strcmp(cmdline, "com.google.android.gms") == 0 ||
                        std::strcmp(cmdline, "com.google.android.gms.persistent") == 0) {
                        proc_verified = true;
                    }
                }
            }
        }
    }

    const bool status_ok = (status.rfind("ok:", 0) == 0);

    if (!enabled) {
        if (out_gms_diag) {
            *out_gms_diag = (proc_verified && status_ok) ? "disabled_pending_restart" : "disabled";
        }
        return;
    }

    if (!proc_verified) {
        if (out_gms_diag) *out_gms_diag = "stale_pid";
        return;
    }

    if (!status_ok) {
        if (out_gms_diag) *out_gms_diag = status;
        return;
    }

    // Verify GMS is mapped to THIS daemon instance's memfd (not a dead/previous kuroko_daemon)
    if (daemon_pid != static_cast<int32_t>(getpid()) ||
        shm_ino == 0 || g_memfd_ino == 0 || shm_ino != g_memfd_ino) {
        if (out_gms_diag) *out_gms_diag = "stale_daemon_restart";
        return;
    }

    if (out_gms_diag) *out_gms_diag = status;
    if (out_gms_shm_ok) *out_gms_shm_ok = true;
}

/* ===== Localhost TCP Control Protocol (127.0.0.1:28765) ===== */
std::string format_status_json() {
    kuroko::SimStatus s = g_engine.get_status();
    std::string diag_esc;
    {
        std::lock_guard<std::mutex> dlk(g_diag_mu);
        diag_esc = json_escape(g_sensor_diag);
    }
    bool gms_shm_ok = false;
    bool gms_enabled = true;
    std::string gms_diag;
    read_gms_status(&gms_shm_ok, &gms_enabled, &gms_diag);
    std::string gms_diag_esc = json_escape(gms_diag);

    std::string rf_country_esc = json_escape(s.rf_country);
    std::string rf_serving_esc = json_escape(s.rf_serving);
    char buf[4096];
    std::snprintf(
        buf, sizeof(buf),
        "{\"active\":%d,\"moving\":%d,\"loop\":%d,"
        "\"lat\":%.7f,\"lon\":%.7f,\"alt\":%.2f,"
        "\"speed\":%.3f,\"target_speed\":%.3f,\"bearing\":%.2f,\"acc\":%.2f,"
        "\"dist\":%.2f,\"route_len\":%.2f,\"max_dist\":%.2f,\"laps\":%u,\"max_laps\":%u,"
        "\"steps\":%llu,\"cadence\":%.1f,\"stride\":%.3f,"
        "\"mode\":%u,\"carry\":%u,\"jitter\":%.2f,\"pace_var\":%.3f,\"targets\":%u,"
        "\"loc_shm\":%d,\"phone_shm\":%d,\"sensor_shm\":%d,\"bt_shm\":%d,\"sensor_diag\":\"%s\","
        "\"gms_shm\":%s,\"gms_enabled\":%s,\"gms_diag\":\"%s\","
        "\"daemon_pid\":%d,"
        "\"mod_ver\":\"%s\",\"mod_code\":%d,\"proto_ver\":%d,\"shm_ver\":%d,"
        "\"min_app_code\":%d,\"xposed_code\":%d,\"zygisk_code\":%d,"
        "\"rf_country\":\"%s\",\"rf_mcc\":%u,\"rf_mnc\":%u,"
        "\"rf_cells\":%u,\"rf_wifis\":%u,\"rf_bles\":%u,\"rf_serving\":\"%s\"}\n",
        s.active ? 1 : 0, s.moving ? 1 : 0, s.loop ? 1 : 0,
        s.lat, s.lon, s.alt,
        s.speed_mps, s.target_speed_mps, s.bearing_deg, s.h_accuracy_m,
        s.total_distance_m, s.route_length_m, s.max_distance_m, s.lap_count, s.max_laps,
        static_cast<unsigned long long>(s.virtual_steps), s.cadence_spm, s.stride_m,
        s.motion_mode, s.carry_mode, s.jitter_sigma_m, s.pace_var_ratio, s.target_count,
        g_loc_shm_ok.load(std::memory_order_relaxed) ? 1 : 0,
        g_phone_shm_ok.load(std::memory_order_relaxed) ? 1 : 0,
        g_sensor_shm_ok.load(std::memory_order_relaxed) ? 1 : 0,
        g_bt_shm_ok.load(std::memory_order_relaxed) ? 1 : 0,
        diag_esc.c_str(),
        gms_shm_ok ? "true" : "false",
        gms_enabled ? "true" : "false",
        gms_diag_esc.c_str(),
        static_cast<int>(getpid()),
        KUROKO_MODULE_VERSION_NAME,
        KUROKO_MODULE_VERSION_CODE,
        KUROKO_IPC_PROTOCOL_VERSION,
        KUROKO_SHM_SCHEMA_VERSION,
        KUROKO_MIN_COMPAT_APP_CODE,
        g_xposed_code.load(std::memory_order_relaxed),
        g_zygisk_code.load(std::memory_order_relaxed),
        rf_country_esc.c_str(),
        static_cast<unsigned int>(s.rf_mcc),
        static_cast<unsigned int>(s.rf_mnc),
        static_cast<unsigned int>(s.rf_cells),
        static_cast<unsigned int>(s.rf_wifis),
        static_cast<unsigned int>(s.rf_bles),
        rf_serving_esc.c_str());
    return std::string(buf);
}

std::vector<std::string> split_fields(const std::string& s, char delim) {
    std::vector<std::string> out;
    size_t start = 0;
    while (true) {
        size_t pos = s.find(delim, start);
        if (pos == std::string::npos) {
            out.push_back(s.substr(start));
            break;
        }
        out.push_back(s.substr(start, pos - start));
        start = pos + 1;
    }
    return out;
}

int hex_nibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

bool decode_hex_bytes(const std::string& hex, uint8_t* out, size_t expected_len) {
    if (hex.size() != expected_len * 2) return false;
    for (size_t i = 0; i < expected_len; ++i) {
        int hi = hex_nibble(hex[i * 2]);
        int lo = hex_nibble(hex[i * 2 + 1]);
        if (hi < 0 || lo < 0) return false;
        out[i] = static_cast<uint8_t>((hi << 4) | lo);
    }
    return true;
}

size_t utf8_safe_prefix_len(const char* s, size_t len, size_t max_bytes) {
    if (!s || len == 0 || max_bytes == 0) return 0;
    size_t n = len < max_bytes ? len : max_bytes;
    size_t back = 0;
    while (back < n && back < 4u &&
           (static_cast<unsigned char>(s[n - 1 - back]) & 0xC0u) == 0x80u) {
        ++back;
    }
    if (back == n) return 0;
    size_t lead_idx = n - 1 - back;
    unsigned char lead = static_cast<unsigned char>(s[lead_idx]);
    size_t expected = 1;
    if ((lead & 0x80u) == 0x00u) {
        expected = 1;
    } else if ((lead & 0xE0u) == 0xC0u) {
        expected = 2;
    } else if ((lead & 0xF0u) == 0xE0u) {
        expected = 3;
    } else if ((lead & 0xF8u) == 0xF0u) {
        expected = 4;
    } else {
        return lead_idx;
    }
    size_t have = n - lead_idx;
    if (have < expected) return lead_idx;
    if (have > expected) return lead_idx + expected;
    return n;
}

bool decode_hex_str(const std::string& hex, std::string* out, size_t max_bytes) {
    out->clear();
    if (hex.empty()) return true;
    if ((hex.size() & 1u) != 0) return false;
    const size_t n = hex.size() / 2;
    out->reserve(n < max_bytes ? n : max_bytes);
    for (size_t i = 0; i < n; ++i) {
        int hi = hex_nibble(hex[i * 2]);
        int lo = hex_nibble(hex[i * 2 + 1]);
        if (hi < 0 || lo < 0) {
            out->clear();
            return false;
        }
        if (out->size() < max_bytes) {
            out->push_back(static_cast<char>((hi << 4) | lo));
        }
    }
    out->resize(utf8_safe_prefix_len(out->data(), out->size(), max_bytes));
    return true;
}

bool decode_base64_str(const std::string& b64, std::string* out, size_t max_bytes) {
    out->clear();
    if (b64.empty()) return true;
    uint32_t acc = 0;
    int bits = 0;
    for (char c : b64) {
        if (c == '=') break;
        int v = -1;
        if (c >= 'A' && c <= 'Z') v = c - 'A';
        else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
        else if (c >= '0' && c <= '9') v = c - '0' + 52;
        else if (c == '+' || c == '-') v = 62;
        else if (c == '/' || c == '_') v = 63;
        else {
            out->clear();
            return false;
        }
        acc = (acc << 6) | static_cast<uint32_t>(v);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            char byte_val = static_cast<char>((acc >> bits) & 0xFFu);
            if (out->size() < max_bytes) {
                out->push_back(byte_val);
            }
        }
    }
    out->resize(utf8_safe_prefix_len(out->data(), out->size(), max_bytes));
    return true;
}

bool parse_u64_strict(const std::string& s, uint64_t* out, int base = 10) {
    if (s.empty() || s[0] == '-') return false;
    errno = 0;
    char* endp = nullptr;
    unsigned long long v = std::strtoull(s.c_str(), &endp, base);
    if (errno != 0 || !endp || endp == s.c_str() || *endp != '\0') return false;
    *out = static_cast<uint64_t>(v);
    return true;
}

bool parse_i64_strict(const std::string& s, int64_t* out, int base = 10) {
    if (s.empty()) return false;
    errno = 0;
    char* endp = nullptr;
    long long v = std::strtoll(s.c_str(), &endp, base);
    if (errno != 0 || !endp || endp == s.c_str() || *endp != '\0') return false;
    *out = static_cast<int64_t>(v);
    return true;
}

bool parse_double_strict(const std::string& s, double* out) {
    if (s.empty()) return false;
    errno = 0;
    char* endp = nullptr;
    double v = std::strtod(s.c_str(), &endp);
    if (errno != 0 || !endp || endp == s.c_str() || *endp != '\0' || !std::isfinite(v)) return false;
    *out = v;
    return true;
}

bool is_auto_token(const std::string& s) {
    return s.empty() || s == "-" || s == "AUTO" || s == "auto" || s == "Auto";
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
    } else if (cmd == "RESET_ROUTE") {
        g_engine.reset_route();
        return "OK RESET_ROUTE\n";
    } else if (cmd == "SET_POINT") {
        std::string lat_s, lon_s, alt_s, extra;
        if (!(iss >> lat_s >> lon_s)) return "ERR BAD_ARGS\n";
        double lat = 0.0, lon = 0.0, alt = 45.0;
        if (!parse_double_strict(lat_s, &lat) || !parse_double_strict(lon_s, &lon)) {
            return "ERR INVALID_COORDS\n";
        }
        if (iss >> alt_s) {
            if (!parse_double_strict(alt_s, &alt)) return "ERR INVALID_COORDS\n";
            if (iss >> extra) return "ERR INVALID_COORDS\n";
        }
        if (!g_engine.set_point(lat, lon, alt)) return "ERR INVALID_COORDS\n";
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
        std::string tok3, tok4, extra;
        if (!(iss >> loop_i >> max_dist >> tok3)) return "ERR BAD_ARGS\n";
        uint32_t max_laps = (loop_i != 0) ? 0u : 1u;
        std::string pts_str;
        if (iss >> tok4) {
            if (iss >> extra) return "ERR BAD_ARGS\n";
            char* endp = nullptr;
            unsigned long parsed_laps = std::strtoul(tok3.c_str(), &endp, 10);
            if (!endp || *endp != '\0' || tok3.empty() || tok3[0] == '-') return "ERR BAD_ARGS\n";
            max_laps = static_cast<uint32_t>(parsed_laps);
            pts_str = tok4;
        } else {
            pts_str = tok3;
        }
        auto raw_pts = split_fields(pts_str, ';');
        std::vector<kuroko::Waypoint> pts;
        pts.reserve(raw_pts.size());
        for (const auto& tok : raw_pts) {
            if (tok.empty()) continue;
            auto f = split_fields(tok, ',');
            if (f.size() != 2 && f.size() != 3) return "ERR INVALID_COORDS\n";
            double lat = 0.0, lon = 0.0, alt = 45.0;
            if (!parse_double_strict(f[0], &lat) || !parse_double_strict(f[1], &lon)) {
                return "ERR INVALID_COORDS\n";
            }
            if (f.size() == 3 && !parse_double_strict(f[2], &alt)) {
                return "ERR INVALID_COORDS\n";
            }
            pts.push_back(kuroko::Waypoint{lat, lon, alt});
        }
        if (pts.empty()) return "ERR EMPTY_ROUTE\n";
        switch (g_engine.set_route(pts, loop_i != 0, max_dist, max_laps)) {
            case kuroko::SetRouteResult::Ok:
                return "OK SET_ROUTE " + std::to_string(pts.size()) + "\n";
            case kuroko::SetRouteResult::EmptyRoute:
                return "ERR EMPTY_ROUTE\n";
            case kuroko::SetRouteResult::InvalidCoords:
                return "ERR INVALID_COORDS\n";
            case kuroko::SetRouteResult::ZeroLengthRoute:
                return "ERR ZERO_LENGTH_ROUTE\n";
        }
        return "ERR INVALID_COORDS\n";
    } else if (cmd == "SET_RF_CONFIG") {
        std::string flags_str, mcc_str, mnc_str, digits_str, iso_str, alpha_b64;
        if (!(iss >> flags_str >> mcc_str >> mnc_str >> digits_str >> iso_str)) {
            return "ERR BAD_ARGS\n";
        }
        if (!(iss >> alpha_b64)) {
            alpha_b64 = "AUTO";
        }
        uint64_t flags_v = 0, mcc_v = 0, mnc_v = 0, digits_v = 2;
        if (!parse_u64_strict(flags_str, &flags_v, 0) ||
            !parse_u64_strict(mcc_str, &mcc_v, 10) ||
            !parse_u64_strict(mnc_str, &mnc_v, 10) ||
            !parse_u64_strict(digits_str, &digits_v, 10)) {
            return "ERR BAD_ARGS\n";
        }
        if (mcc_v > 999 || mnc_v > 999 || (digits_v != 2 && digits_v != 3)) {
            return "ERR BAD_ARGS\n";
        }
        kuroko::RfConfig cfg;
        cfg.rf_flags = static_cast<uint32_t>(flags_v);
        cfg.carrier_mcc = static_cast<uint16_t>(mcc_v);
        cfg.carrier_mnc = static_cast<uint16_t>(mnc_v);
        cfg.mnc_digits = static_cast<uint8_t>(digits_v);
        if (!is_auto_token(iso_str)) {
            if (iso_str.size() != 2) return "ERR BAD_ARGS\n";
            char c0 = iso_str[0];
            char c1 = iso_str[1];
            auto to_lower_alpha = [](char c, char* out) {
                if (c >= 'a' && c <= 'z') { *out = c; return true; }
                if (c >= 'A' && c <= 'Z') { *out = static_cast<char>(c - 'A' + 'a'); return true; }
                return false;
            };
            char l0 = 0, l1 = 0;
            if (!to_lower_alpha(c0, &l0) || !to_lower_alpha(c1, &l1)) return "ERR BAD_ARGS\n";
            cfg.country_iso.assign({l0, l1});
        }
        if (!is_auto_token(alpha_b64)) {
            if (!decode_base64_str(alpha_b64, &cfg.carrier_alpha, 15)) {
                return "ERR BAD_ARGS\n";
            }
        }
        g_engine.set_rf_config(cfg);
        return "OK SET_RF_CONFIG\n";
    } else if (cmd == "SET_RF_CELLS") {
        std::string spec;
        if (!(iss >> spec)) return "ERR BAD_ARGS\n";
        std::vector<kuroko::PinnedCell> list;
        if (spec != "NONE") {
            auto records = split_fields(spec, ';');
            for (const auto& rec : records) {
                if (rec.empty()) continue;
                if (list.size() >= 4) break;
                auto f = split_fields(rec, ':');
                if (f.size() != 12) return "ERR BAD_ARGS\n";
                uint64_t rat_v = 0, mcc_v = 0, mnc_v = 0, dig_v = 2;
                uint64_t tac_v = 0, ci_v = 0, pci_v = 0, arfcn_v = 0, band_v = 0;
                int64_t rsrp_v = -78;
                double pin_lat = 999.0, pin_lon = 999.0;
                if (!parse_u64_strict(f[0], &rat_v) ||
                    !parse_u64_strict(f[1], &mcc_v) ||
                    !parse_u64_strict(f[2], &mnc_v) ||
                    !parse_u64_strict(f[3], &dig_v) ||
                    !parse_u64_strict(f[4], &tac_v) ||
                    !parse_u64_strict(f[5], &ci_v) ||
                    !parse_u64_strict(f[6], &pci_v) ||
                    !parse_u64_strict(f[7], &arfcn_v) ||
                    !parse_u64_strict(f[8], &band_v) ||
                    !parse_i64_strict(f[9], &rsrp_v) ||
                    !parse_double_strict(f[10], &pin_lat) ||
                    !parse_double_strict(f[11], &pin_lon)) {
                    return "ERR BAD_ARGS\n";
                }
                if (mcc_v > 999 || mnc_v > 999 || pci_v > 1007) return "ERR BAD_ARGS\n";
                kuroko::PinnedCell c;
                c.rat = (rat_v == KUROKO_RAT_NR) ? KUROKO_RAT_NR : KUROKO_RAT_LTE;
                c.mcc = static_cast<uint16_t>(mcc_v);
                c.mnc = static_cast<uint16_t>(mnc_v);
                c.mnc_digits = (dig_v == 3) ? 3 : 2;
                c.tac = static_cast<uint32_t>(tac_v & 0xFFFFFFu);
                c.ci = ci_v;
                c.pci = static_cast<uint16_t>(pci_v);
                c.arfcn = static_cast<uint32_t>(arfcn_v);
                c.band = static_cast<uint16_t>(band_v);
                if (rsrp_v < -140) rsrp_v = -140;
                if (rsrp_v > -44) rsrp_v = -44;
                c.rsrp_dbm = static_cast<int32_t>(rsrp_v);
                c.pin_lat = pin_lat;
                c.pin_lon = pin_lon;
                list.push_back(c);
            }
        }
        g_engine.set_rf_cells(list);
        return "OK SET_RF_CELLS " + std::to_string(list.size()) + "\n";
    } else if (cmd == "SET_RF_WIFIS") {
        std::string spec;
        if (!(iss >> spec)) return "ERR BAD_ARGS\n";
        std::vector<kuroko::PinnedWifiAp> list;
        if (spec != "NONE") {
            auto records = split_fields(spec, ';');
            for (const auto& rec : records) {
                if (rec.empty()) continue;
                if (list.size() >= 8) break;
                auto f = split_fields(rec, ':');
                if (f.size() != 7) return "ERR BAD_ARGS\n";
                kuroko::PinnedWifiAp ap;
                if (!decode_hex_bytes(f[0], ap.bssid, 6)) return "ERR BAD_ARGS\n";
                int64_t freq_v = 2437, rssi_v = -52;
                uint64_t flags_v = 0;
                double pin_lat = 999.0, pin_lon = 999.0;
                if (!parse_i64_strict(f[1], &freq_v) ||
                    !parse_i64_strict(f[2], &rssi_v) ||
                    !parse_u64_strict(f[3], &flags_v, 0) ||
                    !parse_double_strict(f[4], &pin_lat) ||
                    !parse_double_strict(f[5], &pin_lon) ||
                    !decode_hex_str(f[6], &ap.ssid, 32)) {
                    return "ERR BAD_ARGS\n";
                }
                if (freq_v < 2400 || freq_v > 7125) freq_v = 2437;
                if (rssi_v < -100) rssi_v = -100;
                if (rssi_v > -20) rssi_v = -20;
                ap.frequency_mhz = static_cast<int32_t>(freq_v);
                ap.rssi_dbm = static_cast<int32_t>(rssi_v);
                ap.flags = static_cast<uint8_t>(flags_v & 0xFFu);
                ap.pin_lat = pin_lat;
                ap.pin_lon = pin_lon;
                list.push_back(std::move(ap));
            }
        }
        g_engine.set_rf_wifis(list);
        return "OK SET_RF_WIFIS " + std::to_string(list.size()) + "\n";
    } else if (cmd == "SET_RF_BEACONS") {
        std::string spec;
        if (!(iss >> spec)) return "ERR BAD_ARGS\n";
        std::vector<kuroko::PinnedBleBeacon> list;
        if (spec != "NONE") {
            auto records = split_fields(spec, ';');
            for (const auto& rec : records) {
                if (rec.empty()) continue;
                if (list.size() >= 8) break;
                auto f = split_fields(rec, ':');
                if (f.size() != 9) return "ERR BAD_ARGS\n";
                kuroko::PinnedBleBeacon b;
                if (!decode_hex_bytes(f[0], b.mac, 6)) return "ERR BAD_ARGS\n";
                uint64_t type_v = 0, major_v = 0, minor_v = 0;
                int64_t tx_pwr_v = -59;
                double pin_lat = 999.0, pin_lon = 999.0;
                if (!parse_u64_strict(f[1], &type_v)) return "ERR BAD_ARGS\n";
                if (!f[2].empty() && !decode_hex_bytes(f[2], b.uuid, 16)) {
                    return "ERR BAD_ARGS\n";
                }
                if (!parse_u64_strict(f[3], &major_v) ||
                    !parse_u64_strict(f[4], &minor_v) ||
                    !parse_i64_strict(f[5], &tx_pwr_v) ||
                    !parse_double_strict(f[6], &pin_lat) ||
                    !parse_double_strict(f[7], &pin_lon) ||
                    !decode_hex_str(f[8], &b.name, 24)) {
                    return "ERR BAD_ARGS\n";
                }
                if (major_v > 65535 || minor_v > 65535) return "ERR BAD_ARGS\n";
                if (tx_pwr_v < -100) tx_pwr_v = -100;
                if (tx_pwr_v > 20) tx_pwr_v = 20;
                b.device_type = (type_v == KUROKO_BLE_TYPE_IBEACON)
                                    ? KUROKO_BLE_TYPE_IBEACON
                                    : KUROKO_BLE_TYPE_GENERIC;
                b.major = static_cast<uint16_t>(major_v);
                b.minor = static_cast<uint16_t>(minor_v);
                b.tx_power_dbm = static_cast<int8_t>(tx_pwr_v);
                b.pin_lat = pin_lat;
                b.pin_lon = pin_lon;
                list.push_back(std::move(b));
            }
        }
        g_engine.set_rf_beacons(list);
        return "OK SET_RF_BEACONS " + std::to_string(list.size()) + "\n";
    } else if (cmd == "SET_GMS") {
        std::string arg, extra;
        if (!(iss >> arg) || (iss >> extra)) return "ERR BAD_ARGS\n";
        if (arg == "0") {
            mkdir("/data/adb/kuroko", 0700);
            chmod("/data/adb/kuroko", 0700);
            const char* tmp_path = KUROKO_GMS_DISABLED_PATH ".tmp";
            int fd = open(tmp_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
            if (fd < 0) {
                LOGW("Failed to open %s: %s", tmp_path, strerror(errno));
                return "ERR IO_FAILED\n";
            }
            const char payload[] = "1\n";
            ssize_t w = write(fd, payload, sizeof(payload) - 1);
            fchmod(fd, 0600);
            close(fd);
            if (w != static_cast<ssize_t>(sizeof(payload) - 1) ||
                rename(tmp_path, KUROKO_GMS_DISABLED_PATH) != 0) {
                LOGW("Failed to atomically write %s: %s", KUROKO_GMS_DISABLED_PATH, strerror(errno));
                unlink(tmp_path);
                return "ERR IO_FAILED\n";
            }
            // Keep KUROKO_GMS_STATUS_PATH so read_gms_status() can accurately report
            // "disabled_pending_restart" while the previously hooked GMS PID is still alive.
            LOGI("GMS FLP disabled via SET_GMS 0");
            write_daemon_log(false, "gms_flp set=disabled");
            return "OK GMS_DISABLED\n";
        } else if (arg == "1") {
            if (unlink(KUROKO_GMS_DISABLED_PATH) != 0 && errno != ENOENT) {
                LOGW("Failed to unlink %s: %s", KUROKO_GMS_DISABLED_PATH, strerror(errno));
                return "ERR IO_FAILED\n";
            }
            LOGI("GMS FLP enabled via SET_GMS 1");
            write_daemon_log(false, "gms_flp set=enabled");
            return "OK GMS_ENABLED\n";
        }
        return "ERR BAD_ARGS\n";
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
