#include "gms_hook.h"

#include <android/log.h>
#include <elf.h>
#include <errno.h>
#include <linux/android/binder.h>
#include <pthread.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#define LOG_TAG "KurokoGmsHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef DT_GNU_HASH
#define DT_GNU_HASH 0x6ffffef5
#endif
#ifndef PT_GNU_RELRO
#define PT_GNU_RELRO 0x6474e552
#endif
#ifndef R_AARCH64_GLOB_DAT
#define R_AARCH64_GLOB_DAT 1025
#endif
#ifndef R_AARCH64_JUMP_SLOT
#define R_AARCH64_JUMP_SLOT 1026
#endif
#ifndef KUROKO_MODULE_VERSION_CODE
#define KUROKO_MODULE_VERSION_CODE 0
#endif

namespace kuroko {

namespace {

constexpr uint64_t kStaleHeartbeatNs       = 5000000000ULL; // 5s fail-safe
constexpr uint64_t kPumpDedupGuardNs       = 850000000ULL;  // 850ms dedup guard
constexpr uint64_t kProbeDelayNs           = 150000000ULL;  // 150ms wait before INTERFACE_TRANSACTION probe
constexpr uint64_t kOneShotRescueDelayNs   = 1200000000ULL; // 1.2s indoor rescue for getCurrentLocation
constexpr size_t   kMaxTrackedBinders      = 256;
constexpr int32_t  kBinderDeadObject       = -32;           // -EPIPE (android::DEAD_OBJECT)
constexpr uint32_t kTxInterfaceTransaction = 0x5f4e5446u;   // IBinder.INTERFACE_TRANSACTION ('_NTF')
constexpr uint32_t kTxFlagOneway           = 0x01u;         // TF_ONE_WAY
constexpr uint32_t kSafeParcelObjMagic     = 0x4F45u;       // SafeParcelable.SAFE_PARCELABLE_MAGIC (20293)

constexpr const char kDescGoogleLocSvc[]   = "com.google.android.gms.location.internal.IGoogleLocationManagerService";
constexpr const char kDescLocCallback[]    = "com.google.android.gms.location.ILocationCallback";
constexpr const char kDescLocListener[]    = "com.google.android.gms.location.ILocationListener";
constexpr const char kDescLocStatusCb[]    = "com.google.android.gms.location.internal.ILocationStatusCallback";

// Non-trivial wrapper matching android::sp<T> AAPCS64 indirect return (x8 sret) ABI.
// Neither copy ctor nor dtor calls RefBase methods; ownership of the +1 strong ref from
// libbinder is transferred directly to TrackedBinder (or retained for singleton ProcessState).
struct AndroidSp {
    void* ptr;
    AndroidSp() : ptr(nullptr) {}
    AndroidSp(const AndroidSp& other) : ptr(other.ptr) {}
    ~AndroidSp() {}
};
static_assert(sizeof(AndroidSp) == sizeof(void*), "AndroidSp must be pointer-sized");
static_assert(!std::is_trivially_copyable_v<AndroidSp>, "AndroidSp must use AAPCS64 indirect x8 return");
static_assert(!std::is_trivially_destructible_v<AndroidSp>, "AndroidSp must be non-trivially destructible");

// Resolved libbinder.so / libutils.so function pointer types
using FnBinderTransact          = int32_t (*)(void* self, uint32_t code, const void* data, void* reply, uint32_t flags);
using FnIpcSelf                 = void* (*)();
using FnIpcGetCallingUid        = int32_t (*)(const void* self);
using FnParcelData              = const uint8_t* (*)(const void* self);
using FnParcelDataSize          = size_t (*)(const void* self);
using FnParcelObjects           = const size_t* (*)(const void* self);
using FnParcelObjectsCount      = size_t (*)(const void* self);
using FnParcelDataPosition      = size_t (*)(const void* self);
using FnParcelSetDataPosition   = void (*)(const void* self, size_t pos);
using FnParcelSetData           = int32_t (*)(void* self, const uint8_t* buffer, size_t len);
using FnProcessStateSelf        = AndroidSp (*)();
using FnGetStrongProxyForHandle = AndroidSp (*)(void* process_state, int32_t handle);
using FnRefBaseDecStrong        = void (*)(const void* refbase, const void* id);

// Global state
const KurokoShm* g_shm = nullptr;
JavaVM* g_jvm = nullptr;
std::atomic<uint32_t> g_my_uid{0};
std::atomic<bool> g_hooks_installed{false};
std::atomic<bool> g_pump_started{false};

std::atomic<FnBinderTransact> g_orig_bpbinder_transact{nullptr};
std::atomic<FnBinderTransact> g_orig_bbinder_transact{nullptr};

FnIpcSelf                 g_ipc_self              = nullptr;
FnIpcSelf                 g_ipc_self_or_null      = nullptr;
FnIpcGetCallingUid        g_ipc_get_calling_uid   = nullptr;
FnParcelData              g_parcel_data           = nullptr;
FnParcelDataSize          g_parcel_data_size      = nullptr;
FnParcelObjects           g_parcel_objects        = nullptr;
FnParcelObjectsCount      g_parcel_objects_count  = nullptr;
FnParcelDataPosition      g_parcel_data_pos       = nullptr;
FnParcelSetDataPosition   g_parcel_set_data_pos   = nullptr;
FnParcelSetData           g_parcel_set_data       = nullptr;
FnProcessStateSelf        g_process_state_self    = nullptr;
FnGetStrongProxyForHandle g_get_strong_proxy      = nullptr;
FnRefBaseDecStrong        g_refbase_dec_strong    = nullptr;

std::atomic<void*> g_process_state{nullptr};
std::atomic<bool>  g_logged_dec_strong_skip{false};

// Re-entrancy guards
thread_local bool g_in_gms_pump = false;
thread_local bool g_in_bb_hook  = false;

struct ScopedFlag {
    bool& ref;
    explicit ScopedFlag(bool& r) : ref(r) { ref = true; }
    ~ScopedFlag() { ref = false; }
};

// Telemetry counters (for logcat diagnostics)
std::atomic<uint64_t> g_cnt_incoming_gms{0};
std::atomic<uint64_t> g_cnt_intercepted_cb{0};
std::atomic<uint64_t> g_cnt_pumped{0};
std::atomic<uint64_t> g_cnt_sync_lastloc{0};
std::atomic<size_t>   g_tracked_count{0};

// Cached JNI classes, methods, fields, and constant strings (initialized once)
struct JniCache {
    bool ready = false;
    jclass cls_parcel = nullptr;
    jmethodID mid_parcel_obtain = nullptr;
    jmethodID mid_parcel_recycle = nullptr;
    jmethodID mid_parcel_write_token = nullptr;
    jmethodID mid_parcel_write_no_exc = nullptr;
    jmethodID mid_parcel_write_int = nullptr;
    jmethodID mid_parcel_write_long = nullptr;
    jmethodID mid_parcel_data_pos = nullptr;
    jmethodID mid_parcel_set_data_pos = nullptr;
    jmethodID mid_parcel_read_string = nullptr;
    jmethodID mid_parcel_marshall = nullptr;
    jfieldID fid_parcel_native_ptr = nullptr;

    jclass cls_location = nullptr;
    jmethodID mid_loc_ctor = nullptr;
    jmethodID mid_loc_set_lat = nullptr;
    jmethodID mid_loc_set_lon = nullptr;
    jmethodID mid_loc_set_alt = nullptr;
    jmethodID mid_loc_set_acc = nullptr;
    jmethodID mid_loc_set_vacc = nullptr;
    jmethodID mid_loc_set_speed = nullptr;
    jmethodID mid_loc_set_sacc = nullptr;
    jmethodID mid_loc_set_bearing = nullptr;
    jmethodID mid_loc_set_bacc = nullptr;
    jmethodID mid_loc_set_time = nullptr;
    jmethodID mid_loc_set_elapsed_ns = nullptr;
    jmethodID mid_loc_set_mock = nullptr;      // optional (API 31+)
    jmethodID mid_loc_set_msl_alt = nullptr;   // optional (API 34+)
    jmethodID mid_loc_set_msl_acc = nullptr;   // optional (API 34+)
    jmethodID mid_loc_set_extras = nullptr;
    jmethodID mid_loc_write_to_parcel = nullptr;

    jclass cls_bundle = nullptr;
    jmethodID mid_bundle_ctor = nullptr;
    jmethodID mid_bundle_put_int = nullptr;

    jstring str_fused = nullptr;
    jstring str_satellites = nullptr;
    jstring str_desc_cb = nullptr;
    jstring str_desc_listener = nullptr;
    jstring str_desc_status_cb = nullptr;
};

JniCache g_jni;

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

// Compact 832-byte prefix of KurokoShm (Header + Location + IMU + Targets = 0x000..0x340).
// Avoids 16KB stack frames and 9KB Seqlock copies on GMS Binder hot paths.
struct alignas(8) KurokoLocSnap {
    uint8_t bytes[offsetof(KurokoShm, svs)];
    const KurokoShm* as_shm() const { return reinterpret_cast<const KurokoShm*>(bytes); }
};
static_assert(sizeof(KurokoLocSnap) == 0x340, "KurokoLocSnap must match KurokoShm prefix up to svs");

bool read_fresh_snapshot(KurokoLocSnap* out) {
    if (!g_shm || !out) return false;
    const auto* seq_ptr = reinterpret_cast<const std::atomic<uint32_t>*>(&g_shm->seq);
    bool ok = false;
    for (int tries = 0; tries < 8; ++tries) {
        uint32_t s1 = seq_ptr->load(std::memory_order_acquire);
        if (s1 & 1u) continue;
        __builtin_memcpy(out->bytes, g_shm, sizeof(out->bytes));
        std::atomic_thread_fence(std::memory_order_acquire);
        uint32_t s2 = seq_ptr->load(std::memory_order_acquire);
        if (s1 == s2 &&
            out->as_shm()->magic == KUROKO_SHM_MAGIC &&
            out->as_shm()->version == KUROKO_SHM_VERSION) {
            ok = true;
            break;
        }
    }
    if (!ok) return false;
    const KurokoShm* s = out->as_shm();
    if (!s->active) return false;
    uint64_t now = now_boot_ns();
    if (now > s->daemon_heartbeat_ns && (now - s->daemon_heartbeat_ns) > kStaleHeartbeatNs) {
        return false;
    }
    return true;
}

// Safely decrement RefBase strong refcount on an android::IBinder* (which virtually inherits RefBase).
// Uses Itanium C++ ABI vbase offset verification before ever touching RefBase::decStrong.
void safe_ibinder_dec_strong(void* ibinder) {
    if (!ibinder || !g_refbase_dec_strong) return;
    const auto* vptr = *reinterpret_cast<const uintptr_t* const*>(ibinder);
    if (!vptr) return;

    // Primary base offset-to-top must be 0
    if (static_cast<ptrdiff_t>(vptr[-2]) != 0) return;

    // vptr[-3] is the vbase offset to the sole virtual base android::RefBase
    ptrdiff_t vbase_off = static_cast<ptrdiff_t>(vptr[-3]);
    if (vbase_off < 16 || vbase_off > 512 || (vbase_off & 7) != 0) {
        if (!g_logged_dec_strong_skip.exchange(true, std::memory_order_relaxed)) {
            LOGW("safe_ibinder_dec_strong: unexpected vbase_off=%td; skipping decStrong", vbase_off);
        }
        return;
    }

    auto* refbase = reinterpret_cast<uint8_t*>(ibinder) + vbase_off;
    const auto* sub_vptr = *reinterpret_cast<const uintptr_t* const*>(refbase);
    if (!sub_vptr) return;

    // Verify secondary vtable belongs to the same class's VTT/vtable group:
    // offset-to-top == -vbase_off, RTTI matches primary (both 0 under -fno-rtti), and closely follows vptr.
    uintptr_t v_addr = reinterpret_cast<uintptr_t>(vptr);
    uintptr_t s_addr = reinterpret_cast<uintptr_t>(sub_vptr);
    if (s_addr <= v_addr || (s_addr - v_addr) > 1024u ||
        static_cast<ptrdiff_t>(sub_vptr[-2]) != -vbase_off ||
        sub_vptr[-1] != vptr[-1]) {
        if (!g_logged_dec_strong_skip.exchange(true, std::memory_order_relaxed)) {
            LOGW("safe_ibinder_dec_strong: secondary vtable verification failed; skipping decStrong");
        }
        return;
    }

    g_refbase_dec_strong(refbase, ibinder);
}

enum class CallbackKind : uint8_t {
    UNKNOWN = 0,
    LOCATION_CALLBACK = 1,
    LOCATION_LISTENER = 2,
    LOCATION_STATUS_CALLBACK = 3,
    IGNORED = 4,
};

struct TrackedBinder {
    void* bp_binder = nullptr; // Owns 1 strong ref from ProcessState::getStrongProxyForHandle
    uint32_t uid = 0;
    int32_t handle = -1;
    std::atomic<uint8_t> kind{static_cast<uint8_t>(CallbackKind::UNKNOWN)};
    std::atomic<uint64_t> registered_ns{0};
    std::atomic<uint64_t> last_delivered_ns{0};
    std::atomic<bool> availability_sent{false};
    std::atomic<bool> one_shot_done{false};
    std::atomic<bool> dead{false};

    ~TrackedBinder() {
        if (bp_binder) {
            safe_ibinder_dec_strong(bp_binder);
            bp_binder = nullptr;
        }
    }
};

std::shared_mutex g_binders_mu;
std::unordered_map<void*, std::shared_ptr<TrackedBinder>> g_tracked_binders;

void evict_tracked_binder(void* bp) {
    if (!bp) return;
    std::shared_ptr<TrackedBinder> dropped;
    {
        std::unique_lock<std::shared_mutex> lk(g_binders_mu);
        auto it = g_tracked_binders.find(bp);
        if (it != g_tracked_binders.end()) {
            it->second->dead.store(true, std::memory_order_relaxed);
            dropped = std::move(it->second);
            g_tracked_binders.erase(it);
            g_tracked_count.store(g_tracked_binders.size(), std::memory_order_relaxed);
        }
    }
    // dropped releases outside g_binders_mu (so safe_ibinder_dec_strong never runs under lock)
}

void* get_process_state_singleton() {
    void* ps = g_process_state.load(std::memory_order_acquire);
    if (ps) return ps;
    if (!g_process_state_self) return nullptr;
    AndroidSp sp = g_process_state_self();
    if (!sp.ptr) return nullptr;
    void* expected = nullptr;
    if (g_process_state.compare_exchange_strong(expected, sp.ptr, std::memory_order_acq_rel)) {
        return sp.ptr;
    }
    // Another thread won the race; release our extra ref on ProcessState singleton
    safe_ibinder_dec_strong(sp.ptr);
    return expected;
}

// Resolve handle via ProcessState::getStrongProxyForHandle and track BpBinder* -> calling_uid.
// Returns the BpBinder* if tracked (and sets *out_was_new if freshly inserted).
void* track_handle_for_uid(int32_t handle, uint32_t uid, bool* out_was_new) {
    if (out_was_new) *out_was_new = false;
    if (handle <= 0 || uid < 10000u || !g_get_strong_proxy) return nullptr;

    void* ps = get_process_state_singleton();
    if (!ps) return nullptr;

    AndroidSp sp = g_get_strong_proxy(ps, handle);
    void* bp = sp.ptr;
    if (!bp) return nullptr;

    uint64_t now_ns = now_boot_ns();
    bool need_release_extra = false;
    std::shared_ptr<TrackedBinder> evicted_oldest;

    {
        std::unique_lock<std::shared_mutex> lk(g_binders_mu);
        auto it = g_tracked_binders.find(bp);
        if (it != g_tracked_binders.end()) {
            // Already tracked: refresh uid/handle/timestamp, release redundant strong ref outside lock
            it->second->uid = uid;
            it->second->handle = handle;
            it->second->dead.store(false, std::memory_order_relaxed);
            it->second->registered_ns.store(now_ns, std::memory_order_relaxed);
            it->second->one_shot_done.store(false, std::memory_order_relaxed);
            need_release_extra = true;
        } else {
            if (g_tracked_binders.size() >= kMaxTrackedBinders) {
                // Evict oldest tracked entry to keep memory strictly bounded
                auto oldest_it = g_tracked_binders.end();
                uint64_t oldest_ts = UINT64_MAX;
                for (auto cur = g_tracked_binders.begin(); cur != g_tracked_binders.end(); ++cur) {
                    uint64_t ts = cur->second->registered_ns.load(std::memory_order_relaxed);
                    if (ts < oldest_ts) {
                        oldest_ts = ts;
                        oldest_it = cur;
                    }
                }
                if (oldest_it != g_tracked_binders.end()) {
                    evicted_oldest = std::move(oldest_it->second);
                    g_tracked_binders.erase(oldest_it);
                }
            }
            auto entry = std::make_shared<TrackedBinder>();
            entry->bp_binder = bp; // takes ownership of sp's +1 strong ref
            entry->uid = uid;
            entry->handle = handle;
            entry->registered_ns.store(now_ns, std::memory_order_relaxed);
            g_tracked_binders.emplace(bp, std::move(entry));
            g_tracked_count.store(g_tracked_binders.size(), std::memory_order_relaxed);
            if (out_was_new) *out_was_new = true;
        }
    }

    if (need_release_extra) {
        safe_ibinder_dec_strong(bp);
    }
    return bp;
}

void untrack_handle(int32_t handle) {
    if (handle <= 0 || g_tracked_count.load(std::memory_order_relaxed) == 0) return;

    // Fast path: match by cached handle first (avoids calling getStrongProxyForHandle on unregister)
    std::vector<std::shared_ptr<TrackedBinder>> dropped;
    {
        std::unique_lock<std::shared_mutex> lk(g_binders_mu);
        for (auto it = g_tracked_binders.begin(); it != g_tracked_binders.end(); ) {
            if (it->second->handle == handle) {
                it->second->dead.store(true, std::memory_order_relaxed);
                dropped.push_back(std::move(it->second));
                it = g_tracked_binders.erase(it);
            } else {
                ++it;
            }
        }
        g_tracked_count.store(g_tracked_binders.size(), std::memory_order_relaxed);
    }

    if (!dropped.empty()) return;

    // Fallback: resolve exact BpBinder* if handle was reassigned
    if (!g_get_strong_proxy) return;
    void* ps = get_process_state_singleton();
    if (!ps) return;
    AndroidSp sp = g_get_strong_proxy(ps, handle);
    if (!sp.ptr) return;
    evict_tracked_binder(sp.ptr);
    safe_ibinder_dec_strong(sp.ptr);
}

// ============================================================================
// Pure C++ ELF64 /proc/self/maps + .dynsym/.dynstr + PT_GNU_RELRO Scanner
// (ZERO dlopen / dlsym / dlerror / dlclose imports!)
// ============================================================================

struct VmaRange {
    uintptr_t start = 0;
    uintptr_t end = 0;
    int prot = 0;
};

struct MappedLibrary {
    uintptr_t base = 0;
    uintptr_t load_bias = 0;
    uintptr_t relro_start = 0;
    uintptr_t relro_end = 0;
    uintptr_t symtab_addr = 0;
    uintptr_t strtab_addr = 0;
    size_t strsz = 0;
    uintptr_t gnu_hash_addr = 0;
    uintptr_t sysv_hash_addr = 0;
    std::string path;
    std::vector<VmaRange> exec_ranges;
    std::unordered_set<uintptr_t> excluded_got_slots;
    bool valid = false;
};

bool ends_with(const char* str, const char* suffix) {
    size_t slen = std::strlen(str);
    size_t suflen = std::strlen(suffix);
    if (slen < suflen) return false;
    return std::memcmp(str + (slen - suflen), suffix, suflen) == 0;
}

bool is_vendor_path(const char* path) {
    return std::strncmp(path, "/vendor/", 8) == 0 ||
           std::strncmp(path, "/odm/", 5) == 0 ||
           std::strncmp(path, "/vendor_dlkm/", 13) == 0;
}

bool find_vma(const std::vector<VmaRange>& vmas, uintptr_t addr, size_t len, int* out_prot = nullptr) {
    if (addr == 0 || len == 0 || addr + len < addr) return false;
    for (const auto& v : vmas) {
        if (addr >= v.start && (addr + len) <= v.end) {
            if (out_prot) *out_prot = v.prot;
            return true;
        }
    }
    return false;
}

bool in_exec_ranges(const std::vector<VmaRange>& ranges, uintptr_t addr) {
    for (const auto& r : ranges) {
        if (addr >= r.start && addr < r.end) return true;
    }
    return false;
}

std::vector<VmaRange> snapshot_readable_private_vmas() {
    std::vector<VmaRange> vmas;
    FILE* fp = std::fopen("/proc/self/maps", "r");
    if (!fp) return vmas;

    char line[768];
    while (std::fgets(line, sizeof(line), fp)) {
        unsigned long start = 0, end = 0;
        char perms[8]{};
        if (std::sscanf(line, "%lx-%lx %7s", &start, &end, perms) != 3) continue;
        if (perms[0] != 'r' || perms[3] != 'p') continue;
        int prot = PROT_READ;
        if (perms[1] == 'w') prot |= PROT_WRITE;
        if (perms[2] == 'x') prot |= PROT_EXEC;
        vmas.push_back(VmaRange{static_cast<uintptr_t>(start), static_cast<uintptr_t>(end), prot});
    }
    std::fclose(fp);
    return vmas;
}

bool locate_library_in_maps(const char* suffix, const char* preferred_path,
                            const std::vector<VmaRange>& vmas, MappedLibrary* out_lib) {
    *out_lib = MappedLibrary{};
    FILE* fp = std::fopen("/proc/self/maps", "r");
    if (!fp) return false;

    struct Candidate {
        uintptr_t base = 0;
        std::string path;
        std::vector<VmaRange> exec_ranges;
    };
    std::unordered_map<std::string, Candidate> cands;

    char line[768];
    while (std::fgets(line, sizeof(line), fp)) {
        if (std::strstr(line, suffix) == nullptr) continue;
        unsigned long start = 0, end = 0, offset = 0;
        unsigned int maj = 0, min = 0;
        unsigned long long ino = 0;
        char perms[8]{};
        char path[512]{};
        int n = std::sscanf(line, "%lx-%lx %7s %lx %x:%x %llu %511[^\n]",
                            &start, &end, perms, &offset, &maj, &min, &ino, path);
        if (n < 8 || ino == 0) continue;
        if (perms[0] != 'r' || perms[3] != 'p') continue;

        size_t plen = std::strlen(path);
        while (plen > 0 && (path[plen - 1] == '\r' || path[plen - 1] == '\n' || path[plen - 1] == ' ')) {
            path[--plen] = '\0';
        }
        if (!ends_with(path, suffix) || is_vendor_path(path)) continue;

        auto& c = cands[path];
        c.path = path;
        if (offset == 0 && c.base == 0) {
            c.base = static_cast<uintptr_t>(start);
        }
        if (perms[2] == 'x') {
            c.exec_ranges.push_back(VmaRange{static_cast<uintptr_t>(start), static_cast<uintptr_t>(end),
                                             PROT_READ | PROT_EXEC});
        }
    }
    std::fclose(fp);

    const Candidate* chosen = nullptr;
    if (preferred_path) {
        auto it = cands.find(preferred_path);
        if (it != cands.end() && it->second.base != 0) {
            chosen = &it->second;
        }
    }
    if (!chosen) {
        for (const auto& kv : cands) {
            if (kv.second.base != 0) {
                chosen = &kv.second;
                break;
            }
        }
    }
    if (!chosen) return false;

    uintptr_t base = chosen->base;
    if (!find_vma(vmas, base, sizeof(Elf64_Ehdr))) return false;
    const auto* ehdr = reinterpret_cast<const Elf64_Ehdr*>(base);
    if (std::memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
        ehdr->e_ident[EI_CLASS] != ELFCLASS64 ||
        ehdr->e_machine != EM_AARCH64 ||
        ehdr->e_phoff == 0 || ehdr->e_phnum == 0 ||
        ehdr->e_phentsize != sizeof(Elf64_Phdr)) {
        return false;
    }

    uintptr_t phdr_addr = base + ehdr->e_phoff;
    size_t phdr_bytes = static_cast<size_t>(ehdr->e_phnum) * sizeof(Elf64_Phdr);
    if (!find_vma(vmas, phdr_addr, phdr_bytes)) return false;

    const auto* phdrs = reinterpret_cast<const Elf64_Phdr*>(phdr_addr);
    uintptr_t min_load_vaddr = UINTPTR_MAX;
    uintptr_t dyn_vaddr = 0;
    size_t dyn_memsz = 0;
    uintptr_t relro_vaddr = 0;
    size_t relro_memsz = 0;

    for (uint16_t i = 0; i < ehdr->e_phnum; ++i) {
        if (phdrs[i].p_type == PT_LOAD) {
            if (phdrs[i].p_vaddr < min_load_vaddr) {
                min_load_vaddr = phdrs[i].p_vaddr;
            }
        } else if (phdrs[i].p_type == PT_DYNAMIC) {
            dyn_vaddr = phdrs[i].p_vaddr;
            dyn_memsz = phdrs[i].p_memsz;
        } else if (phdrs[i].p_type == PT_GNU_RELRO) {
            relro_vaddr = phdrs[i].p_vaddr;
            relro_memsz = phdrs[i].p_memsz;
        }
    }
    if (min_load_vaddr == UINTPTR_MAX || dyn_vaddr == 0 || dyn_memsz < sizeof(Elf64_Dyn)) {
        return false;
    }

    uintptr_t load_bias = base - min_load_vaddr;
    uintptr_t dyn_addr = load_bias + dyn_vaddr;
    if (!find_vma(vmas, dyn_addr, dyn_memsz)) return false;

    uintptr_t raw_symtab = 0;
    uintptr_t raw_strtab = 0;
    size_t strsz = 0;
    uintptr_t raw_gnu_hash = 0;
    uintptr_t raw_sysv_hash = 0;
    uintptr_t raw_jmprel = 0;
    size_t pltrelsz = 0;
    uintptr_t raw_pltgot = 0;
    uintptr_t raw_rela = 0;
    size_t relasz = 0;

    const auto* dyn = reinterpret_cast<const Elf64_Dyn*>(dyn_addr);
    size_t dyn_count = dyn_memsz / sizeof(Elf64_Dyn);
    for (size_t i = 0; i < dyn_count && dyn[i].d_tag != DT_NULL; ++i) {
        switch (dyn[i].d_tag) {
            case DT_SYMTAB:   raw_symtab    = static_cast<uintptr_t>(dyn[i].d_un.d_ptr); break;
            case DT_STRTAB:   raw_strtab    = static_cast<uintptr_t>(dyn[i].d_un.d_ptr); break;
            case DT_STRSZ:    strsz         = static_cast<size_t>(dyn[i].d_un.d_val); break;
            case DT_GNU_HASH: raw_gnu_hash  = static_cast<uintptr_t>(dyn[i].d_un.d_ptr); break;
            case DT_HASH:     raw_sysv_hash = static_cast<uintptr_t>(dyn[i].d_un.d_ptr); break;
            case DT_JMPREL:   raw_jmprel    = static_cast<uintptr_t>(dyn[i].d_un.d_ptr); break;
            case DT_PLTRELSZ: pltrelsz      = static_cast<size_t>(dyn[i].d_un.d_val); break;
            case DT_PLTGOT:   raw_pltgot    = static_cast<uintptr_t>(dyn[i].d_un.d_ptr); break;
            case DT_RELA:     raw_rela      = static_cast<uintptr_t>(dyn[i].d_un.d_ptr); break;
            case DT_RELASZ:   relasz        = static_cast<size_t>(dyn[i].d_un.d_val); break;
            default: break;
        }
    }

    auto resolve_dyn_ptr = [&](uintptr_t p) -> uintptr_t {
        if (p == 0) return 0;
        if (p >= base && find_vma(vmas, p, 1)) return p;
        return load_bias + p;
    };

    uintptr_t symtab_addr = resolve_dyn_ptr(raw_symtab);
    uintptr_t strtab_addr = resolve_dyn_ptr(raw_strtab);
    if (symtab_addr == 0 || strtab_addr == 0 || strsz == 0 ||
        !find_vma(vmas, symtab_addr, sizeof(Elf64_Sym)) ||
        !find_vma(vmas, strtab_addr, strsz)) {
        return false;
    }

    const char* strtab = reinterpret_cast<const char*>(strtab_addr);
    if (strtab[strsz - 1] != '\0') return false;

    out_lib->base = base;
    out_lib->load_bias = load_bias;
    out_lib->path = chosen->path;
    out_lib->exec_ranges = chosen->exec_ranges;
    if (relro_vaddr != 0 && relro_memsz >= sizeof(uintptr_t)) {
        out_lib->relro_start = load_bias + relro_vaddr;
        out_lib->relro_end = out_lib->relro_start + relro_memsz;
    }
    out_lib->symtab_addr = symtab_addr;
    out_lib->strtab_addr = strtab_addr;
    out_lib->strsz = strsz;
    out_lib->gnu_hash_addr = resolve_dyn_ptr(raw_gnu_hash);
    out_lib->sysv_hash_addr = resolve_dyn_ptr(raw_sysv_hash);

    // Exclude .got.plt and GLOB_DAT slots from PT_GNU_RELRO vtable scans
    uintptr_t pltgot_addr = resolve_dyn_ptr(raw_pltgot);
    if (pltgot_addr != 0) {
        out_lib->excluded_got_slots.insert(pltgot_addr);
        out_lib->excluded_got_slots.insert(pltgot_addr + sizeof(void*));
        out_lib->excluded_got_slots.insert(pltgot_addr + 2 * sizeof(void*));
    }
    uintptr_t jmprel_addr = resolve_dyn_ptr(raw_jmprel);
    if (jmprel_addr != 0 && pltrelsz >= sizeof(Elf64_Rela) && find_vma(vmas, jmprel_addr, pltrelsz)) {
        const auto* relas = reinterpret_cast<const Elf64_Rela*>(jmprel_addr);
        size_t count = pltrelsz / sizeof(Elf64_Rela);
        for (size_t i = 0; i < count; ++i) {
            out_lib->excluded_got_slots.insert(load_bias + static_cast<uintptr_t>(relas[i].r_offset));
        }
    }
    uintptr_t rela_addr = resolve_dyn_ptr(raw_rela);
    if (rela_addr != 0 && relasz >= sizeof(Elf64_Rela) && find_vma(vmas, rela_addr, relasz)) {
        const auto* relas = reinterpret_cast<const Elf64_Rela*>(rela_addr);
        size_t count = relasz / sizeof(Elf64_Rela);
        for (size_t i = 0; i < count; ++i) {
            uint32_t rtype = ELF64_R_TYPE(relas[i].r_info);
            if (rtype == R_AARCH64_GLOB_DAT || rtype == R_AARCH64_JUMP_SLOT) {
                out_lib->excluded_got_slots.insert(load_bias + static_cast<uintptr_t>(relas[i].r_offset));
            }
        }
    }

    out_lib->valid = true;
    return true;
}

uint32_t gnu_hash_str(const char* name) {
    uint32_t h = 5381u;
    for (const auto* p = reinterpret_cast<const unsigned char*>(name); *p; ++p) {
        h = (h << 5) + h + *p;
    }
    return h;
}

uint32_t sysv_hash_str(const char* name) {
    uint32_t h = 0;
    for (const auto* p = reinterpret_cast<const unsigned char*>(name); *p; ++p) {
        h = (h << 4) + *p;
        uint32_t g = h & 0xF0000000u;
        if (g) h ^= (g >> 24);
        h &= ~g;
    }
    return h;
}

uintptr_t lookup_elf_symbol(const MappedLibrary& lib, const std::vector<VmaRange>& vmas, const char* name) {
    if (!lib.valid || !name || !name[0]) return 0;
    const auto* symtab = reinterpret_cast<const Elf64_Sym*>(lib.symtab_addr);
    const char* strtab = reinterpret_cast<const char*>(lib.strtab_addr);

    auto match_sym = [&](uint32_t idx) -> uintptr_t {
        uintptr_t entry_addr = lib.symtab_addr + static_cast<uintptr_t>(idx) * sizeof(Elf64_Sym);
        if (!find_vma(vmas, entry_addr, sizeof(Elf64_Sym))) return 0;
        const Elf64_Sym& s = symtab[idx];
        if (s.st_name >= lib.strsz) return 0;
        if (std::strcmp(strtab + s.st_name, name) != 0) return 0;
        if (s.st_shndx == SHN_UNDEF || s.st_value == 0) return 0;
        return lib.load_bias + static_cast<uintptr_t>(s.st_value);
    };

    // Primary: O(1) DT_GNU_HASH lookup
    if (lib.gnu_hash_addr != 0 && find_vma(vmas, lib.gnu_hash_addr, 16)) {
        const auto* hdr = reinterpret_cast<const uint32_t*>(lib.gnu_hash_addr);
        uint32_t nbuckets    = hdr[0];
        uint32_t symoffset   = hdr[1];
        uint32_t bloom_size  = hdr[2];
        uint32_t bloom_shift = hdr[3];
        if (nbuckets > 0 && bloom_size > 0) {
            uintptr_t bloom_addr = lib.gnu_hash_addr + 16;
            size_t bloom_bytes = static_cast<size_t>(bloom_size) * sizeof(uint64_t);
            uintptr_t buckets_addr = bloom_addr + bloom_bytes;
            size_t buckets_bytes = static_cast<size_t>(nbuckets) * sizeof(uint32_t);
            uintptr_t chains_addr = buckets_addr + buckets_bytes;
            if (find_vma(vmas, bloom_addr, bloom_bytes) &&
                find_vma(vmas, buckets_addr, buckets_bytes)) {
                uint32_t h = gnu_hash_str(name);
                const auto* bloom = reinterpret_cast<const uint64_t*>(bloom_addr);
                uint64_t word = bloom[(h / 64u) % bloom_size];
                uint64_t mask = (1ULL << (h % 64u)) | (1ULL << ((h >> bloom_shift) % 64u));
                if ((word & mask) == mask) {
                    const auto* buckets = reinterpret_cast<const uint32_t*>(buckets_addr);
                    uint32_t idx = buckets[h % nbuckets];
                    if (idx >= symoffset) {
                        for (uint32_t guard = 0; guard < 4096; ++guard, ++idx) {
                            uintptr_t ch_addr = chains_addr + static_cast<uintptr_t>(idx - symoffset) * sizeof(uint32_t);
                            if (!find_vma(vmas, ch_addr, sizeof(uint32_t))) break;
                            uint32_t ch = *reinterpret_cast<const uint32_t*>(ch_addr);
                            if (((ch ^ h) & ~1u) == 0) {
                                uintptr_t found = match_sym(idx);
                                if (found != 0) return found;
                            }
                            if ((ch & 1u) != 0) break;
                        }
                    }
                }
                return 0; // Authoritative negative from GNU hash
            }
        }
    }

    // Fallback: SysV DT_HASH lookup
    if (lib.sysv_hash_addr != 0 && find_vma(vmas, lib.sysv_hash_addr, 8)) {
        const auto* hdr = reinterpret_cast<const uint32_t*>(lib.sysv_hash_addr);
        uint32_t nbucket = hdr[0];
        uint32_t nchain  = hdr[1];
        if (nbucket > 0 && nchain > 0) {
            uintptr_t buckets_addr = lib.sysv_hash_addr + 8;
            size_t buckets_bytes = static_cast<size_t>(nbucket) * sizeof(uint32_t);
            uintptr_t chains_addr = buckets_addr + buckets_bytes;
            size_t chains_bytes = static_cast<size_t>(nchain) * sizeof(uint32_t);
            if (find_vma(vmas, buckets_addr, buckets_bytes) &&
                find_vma(vmas, chains_addr, chains_bytes)) {
                const auto* buckets = reinterpret_cast<const uint32_t*>(buckets_addr);
                const auto* chains = reinterpret_cast<const uint32_t*>(chains_addr);
                uint32_t h = sysv_hash_str(name);
                uint32_t idx = buckets[h % nbucket];
                for (uint32_t guard = 0; guard < nchain && idx != 0 && idx < nchain; ++guard) {
                    uintptr_t found = match_sym(idx);
                    if (found != 0) return found;
                    idx = chains[idx];
                }
            }
        }
    }

    return 0;
}

struct VtableSlot {
    uintptr_t slot_addr = 0;
    int orig_prot = 0;
};

// Scan PT_GNU_RELRO of `target_lib` for genuine C++ vtable slots equal to `target_fn_addr`,
// excluding .got/.got.plt and verifying adjacent vtable peers point into `allowed_exec`.
void find_vtable_slots_in_relro(const MappedLibrary& target_lib,
                                const std::vector<VmaRange>& vmas,
                                const std::vector<VmaRange>& allowed_exec,
                                uintptr_t target_fn_addr,
                                std::vector<VtableSlot>* out_slots) {
    if (!target_lib.valid || target_fn_addr == 0) return;
    if (target_lib.relro_start == 0 || target_lib.relro_end <= target_lib.relro_start + 3 * sizeof(uintptr_t)) {
        return;
    }

    uintptr_t start = (target_lib.relro_start + sizeof(uintptr_t) - 1) & ~(sizeof(uintptr_t) - 1);
    uintptr_t end = target_lib.relro_end & ~(sizeof(uintptr_t) - 1);

    for (uintptr_t addr = start + sizeof(uintptr_t); addr + 2 * sizeof(uintptr_t) <= end; addr += sizeof(uintptr_t)) {
        if (target_lib.excluded_got_slots.count(addr) > 0) continue;

        int slot_prot = 0;
        if (!find_vma(vmas, addr - sizeof(uintptr_t), 3 * sizeof(uintptr_t), &slot_prot)) continue;

        const auto* ptr = reinterpret_cast<const uintptr_t*>(addr);
        if (ptr[0] != target_fn_addr) continue;

        // Require BOTH immediate vtable neighbors (slot[-1] and slot[+1]) to be valid PROT_EXEC code
        // inside allowed_exec (libbinder / libandroid_runtime).
        if (!in_exec_ranges(allowed_exec, ptr[-1]) || !in_exec_ranges(allowed_exec, ptr[1])) {
            continue;
        }

        // Also check at least one of slot[-2] or slot[+2] is valid PROT_EXEC in allowed_exec
        // (IBinder::transact sits deep in IBinder's virtual table, surrounded by >= 2 vfuncs on each side).
        bool peer2_ok = false;
        if (addr >= start + 2 * sizeof(uintptr_t) && find_vma(vmas, addr - 2 * sizeof(uintptr_t), sizeof(uintptr_t))) {
            if (in_exec_ranges(allowed_exec, ptr[-2])) peer2_ok = true;
        }
        if (!peer2_ok && addr + 3 * sizeof(uintptr_t) <= end && find_vma(vmas, addr + 2 * sizeof(uintptr_t), sizeof(uintptr_t))) {
            if (in_exec_ranges(allowed_exec, ptr[2])) peer2_ok = true;
        }
        if (!peer2_ok) continue;

        out_slots->push_back(VtableSlot{addr, slot_prot});
    }
}

size_t patch_vtable_slots(const std::vector<VtableSlot>& slots, void* expected_orig, void* hook_fn) {
    long page_size_l = sysconf(_SC_PAGESIZE);
    size_t page_size = (page_size_l > 0) ? static_cast<size_t>(page_size_l) : 4096u;
    size_t patched = 0;

    for (const auto& s : slots) {
        auto** slot = reinterpret_cast<void**>(s.slot_addr);
        void* cur = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
        if (cur == hook_fn) {
            ++patched;
            continue;
        }
        if (cur != expected_orig) continue;

        uintptr_t page_start = s.slot_addr & ~static_cast<uintptr_t>(page_size - 1);
        size_t prot_len = ((s.slot_addr + sizeof(void*)) > (page_start + page_size))
                              ? (page_size * 2) : page_size;

        bool changed_prot = false;
        if ((s.orig_prot & PROT_WRITE) == 0) {
            if (mprotect(reinterpret_cast<void*>(page_start), prot_len, s.orig_prot | PROT_WRITE) != 0) {
                LOGW("mprotect RW failed at %p (errno=%d)", reinterpret_cast<void*>(page_start), errno);
                continue;
            }
            changed_prot = true;
        }

        __atomic_store_n(slot, hook_fn, __ATOMIC_RELEASE);

        if (changed_prot) {
            mprotect(reinterpret_cast<void*>(page_start), prot_len, s.orig_prot);
        }
        ++patched;
    }
    return patched;
}

// ============================================================================
// Pure Read-Only Parcel Wire Inspection Helpers (ZERO Parcel struct offsets!)
// ============================================================================

// Exact ASCII-to-UTF-16LE match with trailing NUL code unit check inside [buf, min(buf_len, max_scan)).
bool parcel_has_interface_token(const uint8_t* buf, size_t buf_len,
                                const char* desc, size_t desc_len,
                                size_t* out_after_token_off = nullptr) {
    if (!buf || !desc || desc_len == 0) return false;
    const size_t needle_bytes = desc_len * 2;
    const size_t max_scan = std::min<size_t>(buf_len, 320u);
    if (max_scan < needle_bytes + 2) return false;

    const uint8_t first = static_cast<uint8_t>(desc[0]);
    const size_t max_start = max_scan - (needle_bytes + 2);
    for (size_t off = 0; off <= max_start; off += 2) {
        if (buf[off] != first || buf[off + 1] != 0) continue;
        bool ok = true;
        for (size_t j = 1; j < desc_len; ++j) {
            if (buf[off + j * 2] != static_cast<uint8_t>(desc[j]) ||
                buf[off + j * 2 + 1] != 0) {
                ok = false;
                break;
            }
        }
        if (!ok) continue;
        // Require UTF-16LE NUL terminator so prefixes never false-match
        if (buf[off + needle_bytes] == 0 && buf[off + needle_bytes + 1] == 0) {
            if (out_after_token_off) {
                size_t after = off + needle_bytes + 2;
                after = (after + 3u) & ~static_cast<size_t>(3u); // Parcel 4-byte alignment
                *out_after_token_off = after;
            }
            return true;
        }
    }
    return false;
}

template <size_t N>
inline bool parcel_has_interface_token_lit(const uint8_t* buf, size_t buf_len,
                                           const char (&lit)[N],
                                           size_t* out_after_token_off = nullptr) {
    return parcel_has_interface_token(buf, buf_len, lit, N - 1, out_after_token_off);
}

// Detect legacy tx 59 (updateLocationUpdates) opCode == 2 (REMOVE) safely without hardcoding Parcel offsets.
bool is_tx59_remove_request(const uint8_t* buf, size_t buf_len, size_t after_token_off) {
    if (!buf || after_token_off + 20 > buf_len) return false;
    size_t limit = std::min(buf_len, after_token_off + 48u);
    for (size_t off = after_token_off; off + 16 <= limit; off += 4) {
        uint32_t hdr = 0;
        std::memcpy(&hdr, buf + off, 4);
        if ((hdr & 0xFFFFu) != kSafeParcelObjMagic) continue;

        size_t fields_off = ((hdr & 0xFFFF0000u) == 0xFFFF0000u) ? (off + 8) : (off + 4);
        if (fields_off + 8 > buf_len) return false;

        uint32_t f1_hdr = 0;
        int32_t op_code = 0;
        std::memcpy(&f1_hdr, buf + fields_off, 4);
        std::memcpy(&op_code, buf + fields_off + 4, 4);
        // LocationRequestUpdateData field 1 (int opCode): tag (4 << 16) | 1 == 0x00040001
        if (f1_hdr == 0x00040001u) {
            return op_code == 2;
        }
        return false;
    }
    return false;
}

// Inspect outgoing ILocationStatusCallback.onResult(Status, Location) to check whether
// Status.statusCode == 0 (SUCCESS). Returns false if Status carries a non-zero error code
// (preserving Android location permission checks / cancellations).
bool status_callback_is_success(const uint8_t* buf, size_t buf_len, size_t after_token_off) {
    if (!buf || after_token_off + 12 > buf_len) return false;
    int32_t has_status = 0;
    std::memcpy(&has_status, buf + after_token_off, 4);
    if (has_status == 0) return false; // null Status

    size_t obj_off = after_token_off + 4;
    if (obj_off + 4 > buf_len) return false;
    uint32_t obj_hdr = 0;
    std::memcpy(&obj_hdr, buf + obj_off, 4);
    if ((obj_hdr & 0xFFFFu) != kSafeParcelObjMagic) return true; // unknown framing -> fail-open to spoof

    uint32_t obj_size = 0;
    size_t pos = 0;
    if ((obj_hdr & 0xFFFF0000u) == 0xFFFF0000u) {
        if (obj_off + 8 > buf_len) return false;
        std::memcpy(&obj_size, buf + obj_off + 4, 4);
        pos = obj_off + 8;
    } else {
        obj_size = (obj_hdr >> 16) & 0xFFFFu;
        pos = obj_off + 4;
    }
    if (pos + obj_size > buf_len) return false;
    size_t end = pos + obj_size;

    // Walk SafeParcelable fields of Status to find field 1 (statusCode)
    while (pos + 4 <= end) {
        uint32_t fhdr = 0;
        std::memcpy(&fhdr, buf + pos, 4);
        pos += 4;
        uint16_t fid = static_cast<uint16_t>(fhdr & 0xFFFFu);
        uint32_t fsize = 0;
        if ((fhdr & 0xFFFF0000u) == 0xFFFF0000u) {
            if (pos + 4 > end) break;
            std::memcpy(&fsize, buf + pos, 4);
            pos += 4;
        } else {
            fsize = (fhdr >> 16) & 0xFFFFu;
        }
        if (pos + fsize > end) break;
        if (fid == 1u && fsize == 4u) {
            int32_t status_code = 0;
            std::memcpy(&status_code, buf + pos, 4);
            return status_code == 0;
        }
        pos += fsize;
    }
    return true; // Default Status.statusCode when field 1 omitted is 0 (SUCCESS)
}

// Verify synchronous getLastLocation reply looks like either null Location ([exc=0, 0]) or a Location parcel.
bool reply_looks_like_location(const uint8_t* raw, size_t size) {
    if (!raw || size < 8) return false;
    int32_t exc = 0;
    int32_t present = 0;
    std::memcpy(&exc, raw, 4);
    std::memcpy(&present, raw + 4, 4);
    if (exc != 0) return false; // Never override SecurityException / RemoteException
    if (present == 0 && size == 8) return true; // Valid null Location (e.g. indoors)
    if (present != 1 || size < 32) return false;

    // Location.writeToParcel writes provider string first (writeString8 on API 30+, writeString16 older)
    static const char* const kProviders[] = {"fused", "gps", "network", "passive"};
    size_t scan_limit = std::min<size_t>(size, 64u);
    for (const char* prov : kProviders) {
        size_t plen = std::strlen(prov);
        for (size_t off = 8; off + plen <= scan_limit; ++off) {
            if (std::memcmp(raw + off, prov, plen) == 0) return true;
        }
        if (parcel_has_interface_token(raw + 8, scan_limit - 8, prov, plen)) return true;
    }
    return false;
}

// ============================================================================
// JNI Synthetic Location & SafeParcelable Builder (ZERO hardcoded Location/Parcel offsets!)
// ============================================================================

struct ScopedJniEnv {
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    bool attached = false;

    explicit ScopedJniEnv(JavaVM* jvm) : vm(jvm) {
        if (!vm) return;
        jint rc = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (rc == JNI_OK && env) return;
        if (rc == JNI_EDETACHED) {
            if (vm->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK && env) {
                attached = true;
            } else {
                env = nullptr;
            }
        }
    }

    ~ScopedJniEnv() {
        if (attached && vm) {
            vm->DetachCurrentThread();
        }
    }
};

void clear_exc(JNIEnv* env) {
    if (env && env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

jclass find_global_class(JNIEnv* env, const char* name) {
    jclass local = env->FindClass(name);
    if (!local || env->ExceptionCheck()) {
        clear_exc(env);
        if (local) env->DeleteLocalRef(local);
        return nullptr;
    }
    auto global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global || env->ExceptionCheck()) {
        clear_exc(env);
        return nullptr;
    }
    return global;
}

jstring make_global_jstring(JNIEnv* env, const char* utf) {
    jstring local = env->NewStringUTF(utf);
    if (!local || env->ExceptionCheck()) {
        clear_exc(env);
        if (local) env->DeleteLocalRef(local);
        return nullptr;
    }
    auto global = static_cast<jstring>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global || env->ExceptionCheck()) {
        clear_exc(env);
        return nullptr;
    }
    return global;
}

bool init_jni_cache(JNIEnv* env) {
    if (!env) return false;
    if (g_jni.ready) return true;
    clear_exc(env);

    g_jni.cls_parcel = find_global_class(env, "android/os/Parcel");
    g_jni.cls_location = find_global_class(env, "android/location/Location");
    g_jni.cls_bundle = find_global_class(env, "android/os/Bundle");
    if (!g_jni.cls_parcel || !g_jni.cls_location || !g_jni.cls_bundle) {
        return false;
    }

    auto get_smid = [&](jclass c, const char* n, const char* sig) -> jmethodID {
        jmethodID m = env->GetStaticMethodID(c, n, sig);
        if (!m || env->ExceptionCheck()) {
            clear_exc(env);
            return nullptr;
        }
        return m;
    };
    auto get_mid = [&](jclass c, const char* n, const char* sig) -> jmethodID {
        jmethodID m = env->GetMethodID(c, n, sig);
        if (!m || env->ExceptionCheck()) {
            clear_exc(env);
            return nullptr;
        }
        return m;
    };

    g_jni.mid_parcel_obtain       = get_smid(g_jni.cls_parcel, "obtain", "()Landroid/os/Parcel;");
    g_jni.mid_parcel_recycle      = get_mid(g_jni.cls_parcel, "recycle", "()V");
    g_jni.mid_parcel_write_token  = get_mid(g_jni.cls_parcel, "writeInterfaceToken", "(Ljava/lang/String;)V");
    g_jni.mid_parcel_write_no_exc = get_mid(g_jni.cls_parcel, "writeNoException", "()V");
    g_jni.mid_parcel_write_int    = get_mid(g_jni.cls_parcel, "writeInt", "(I)V");
    g_jni.mid_parcel_write_long   = get_mid(g_jni.cls_parcel, "writeLong", "(J)V");
    g_jni.mid_parcel_data_pos     = get_mid(g_jni.cls_parcel, "dataPosition", "()I");
    g_jni.mid_parcel_set_data_pos = get_mid(g_jni.cls_parcel, "setDataPosition", "(I)V");
    g_jni.mid_parcel_read_string  = get_mid(g_jni.cls_parcel, "readString", "()Ljava/lang/String;");
    g_jni.mid_parcel_marshall     = get_mid(g_jni.cls_parcel, "marshall", "()[B");
    g_jni.fid_parcel_native_ptr   = env->GetFieldID(g_jni.cls_parcel, "mNativePtr", "J");
    if (env->ExceptionCheck()) {
        clear_exc(env);
        g_jni.fid_parcel_native_ptr = nullptr;
    }

    if (!g_jni.mid_parcel_obtain || !g_jni.mid_parcel_recycle || !g_jni.mid_parcel_write_token ||
        !g_jni.mid_parcel_write_no_exc || !g_jni.mid_parcel_write_int || !g_jni.mid_parcel_write_long ||
        !g_jni.mid_parcel_data_pos || !g_jni.mid_parcel_set_data_pos || !g_jni.mid_parcel_read_string ||
        !g_jni.mid_parcel_marshall || !g_jni.fid_parcel_native_ptr) {
        return false;
    }

    g_jni.mid_loc_ctor            = get_mid(g_jni.cls_location, "<init>", "(Ljava/lang/String;)V");
    g_jni.mid_loc_set_lat         = get_mid(g_jni.cls_location, "setLatitude", "(D)V");
    g_jni.mid_loc_set_lon         = get_mid(g_jni.cls_location, "setLongitude", "(D)V");
    g_jni.mid_loc_set_alt         = get_mid(g_jni.cls_location, "setAltitude", "(D)V");
    g_jni.mid_loc_set_acc         = get_mid(g_jni.cls_location, "setAccuracy", "(F)V");
    g_jni.mid_loc_set_vacc        = get_mid(g_jni.cls_location, "setVerticalAccuracyMeters", "(F)V");
    g_jni.mid_loc_set_speed       = get_mid(g_jni.cls_location, "setSpeed", "(F)V");
    g_jni.mid_loc_set_sacc        = get_mid(g_jni.cls_location, "setSpeedAccuracyMetersPerSecond", "(F)V");
    g_jni.mid_loc_set_bearing     = get_mid(g_jni.cls_location, "setBearing", "(F)V");
    g_jni.mid_loc_set_bacc        = get_mid(g_jni.cls_location, "setBearingAccuracyDegrees", "(F)V");
    g_jni.mid_loc_set_time        = get_mid(g_jni.cls_location, "setTime", "(J)V");
    g_jni.mid_loc_set_elapsed_ns  = get_mid(g_jni.cls_location, "setElapsedRealtimeNanos", "(J)V");
    g_jni.mid_loc_set_extras      = get_mid(g_jni.cls_location, "setExtras", "(Landroid/os/Bundle;)V");
    g_jni.mid_loc_write_to_parcel = get_mid(g_jni.cls_location, "writeToParcel", "(Landroid/os/Parcel;I)V");

    // Optional API 31+ / API 34+ setters
    g_jni.mid_loc_set_mock        = get_mid(g_jni.cls_location, "setMock", "(Z)V");
    g_jni.mid_loc_set_msl_alt     = get_mid(g_jni.cls_location, "setMslAltitudeMeters", "(D)V");
    g_jni.mid_loc_set_msl_acc     = get_mid(g_jni.cls_location, "setMslAltitudeAccuracyMeters", "(F)V");

    if (!g_jni.mid_loc_ctor || !g_jni.mid_loc_set_lat || !g_jni.mid_loc_set_lon ||
        !g_jni.mid_loc_set_alt || !g_jni.mid_loc_set_acc || !g_jni.mid_loc_set_time ||
        !g_jni.mid_loc_set_elapsed_ns || !g_jni.mid_loc_write_to_parcel) {
        return false;
    }

    g_jni.mid_bundle_ctor    = get_mid(g_jni.cls_bundle, "<init>", "()V");
    g_jni.mid_bundle_put_int = get_mid(g_jni.cls_bundle, "putInt", "(Ljava/lang/String;I)V");

    g_jni.str_fused          = make_global_jstring(env, "fused");
    g_jni.str_satellites     = make_global_jstring(env, "satellites");
    g_jni.str_desc_cb        = make_global_jstring(env, kDescLocCallback);
    g_jni.str_desc_listener  = make_global_jstring(env, kDescLocListener);
    g_jni.str_desc_status_cb = make_global_jstring(env, kDescLocStatusCb);

    if (!g_jni.str_fused || !g_jni.str_desc_cb || !g_jni.str_desc_listener || !g_jni.str_desc_status_cb) {
        return false;
    }

    g_jni.ready = true;
    return true;
}

jobject build_java_location(JNIEnv* env, const KurokoShm& snap) {
    jobject loc = env->NewObject(g_jni.cls_location, g_jni.mid_loc_ctor, g_jni.str_fused);
    if (!loc || env->ExceptionCheck()) {
        clear_exc(env);
        return nullptr;
    }

    uint64_t utc_now = now_utc_ms();
    uint64_t boot_now = now_boot_ns();
    int64_t loc_time = (snap.snapshot_utc_ms > 0 &&
                        ((utc_now >= snap.snapshot_utc_ms && utc_now - snap.snapshot_utc_ms < 3000ULL) ||
                         (snap.snapshot_utc_ms > utc_now && snap.snapshot_utc_ms - utc_now < 3000ULL)))
                           ? static_cast<int64_t>(snap.snapshot_utc_ms)
                           : static_cast<int64_t>(utc_now);
    int64_t loc_boot = (snap.snapshot_boot_ns > 0 &&
                        ((boot_now >= snap.snapshot_boot_ns && boot_now - snap.snapshot_boot_ns < 3000000000ULL) ||
                         (snap.snapshot_boot_ns > boot_now && snap.snapshot_boot_ns - boot_now < 3000000000ULL)))
                           ? static_cast<int64_t>(snap.snapshot_boot_ns)
                           : static_cast<int64_t>(boot_now);

    float h_acc = std::clamp(snap.h_accuracy_m, 1.0f, 50.0f);
    float v_acc = std::clamp(snap.v_accuracy_m, 1.0f, 80.0f);
    float speed = std::max(snap.speed_mps, 0.0f);
    float s_acc = std::clamp(snap.speed_accuracy_mps, 0.05f, 10.0f);

    env->CallVoidMethod(loc, g_jni.mid_loc_set_time, static_cast<jlong>(loc_time));
    env->CallVoidMethod(loc, g_jni.mid_loc_set_elapsed_ns, static_cast<jlong>(loc_boot));
    env->CallVoidMethod(loc, g_jni.mid_loc_set_lat, static_cast<jdouble>(snap.lat_wgs84));
    env->CallVoidMethod(loc, g_jni.mid_loc_set_lon, static_cast<jdouble>(snap.lon_wgs84));
    env->CallVoidMethod(loc, g_jni.mid_loc_set_alt, static_cast<jdouble>(snap.alt_wgs84));
    env->CallVoidMethod(loc, g_jni.mid_loc_set_acc, static_cast<jfloat>(h_acc));
    if (g_jni.mid_loc_set_vacc) {
        env->CallVoidMethod(loc, g_jni.mid_loc_set_vacc, static_cast<jfloat>(v_acc));
    }
    if (g_jni.mid_loc_set_speed) {
        env->CallVoidMethod(loc, g_jni.mid_loc_set_speed, static_cast<jfloat>(speed));
    }
    if (g_jni.mid_loc_set_sacc) {
        env->CallVoidMethod(loc, g_jni.mid_loc_set_sacc, static_cast<jfloat>(s_acc));
    }
    if (speed > 0.15f && g_jni.mid_loc_set_bearing) {
        float brg = std::fmod(snap.bearing_deg, 360.0f);
        if (brg < 0.0f) brg += 360.0f;
        env->CallVoidMethod(loc, g_jni.mid_loc_set_bearing, static_cast<jfloat>(brg));
        if (g_jni.mid_loc_set_bacc) {
            float b_acc = std::clamp(snap.bearing_accuracy_deg, 1.0f, 180.0f);
            env->CallVoidMethod(loc, g_jni.mid_loc_set_bacc, static_cast<jfloat>(b_acc));
        }
    }
    if (g_jni.mid_loc_set_msl_alt && g_jni.mid_loc_set_msl_acc && std::isfinite(snap.msl_alt)) {
        env->CallVoidMethod(loc, g_jni.mid_loc_set_msl_alt, static_cast<jdouble>(snap.msl_alt));
        env->CallVoidMethod(loc, g_jni.mid_loc_set_msl_acc, static_cast<jfloat>(v_acc));
        if (env->ExceptionCheck()) clear_exc(env);
    }
    if (g_jni.mid_loc_set_mock) {
        env->CallVoidMethod(loc, g_jni.mid_loc_set_mock, JNI_FALSE);
        if (env->ExceptionCheck()) clear_exc(env);
    }
    if (g_jni.mid_loc_set_extras && g_jni.mid_bundle_ctor && g_jni.mid_bundle_put_int && g_jni.str_satellites) {
        jobject extras = env->NewObject(g_jni.cls_bundle, g_jni.mid_bundle_ctor);
        if (extras && !env->ExceptionCheck()) {
            int32_t svs = static_cast<int32_t>(std::clamp<uint32_t>(snap.sv_count, 8u, 24u));
            env->CallVoidMethod(extras, g_jni.mid_bundle_put_int, g_jni.str_satellites, static_cast<jint>(svs));
            env->CallVoidMethod(loc, g_jni.mid_loc_set_extras, extras);
        }
        clear_exc(env);
    }

    if (env->ExceptionCheck()) {
        clear_exc(env);
        return nullptr;
    }
    return loc;
}

// GMS SafeParcelWriter wire-compatible primitives operating on a Java android.os.Parcel
jint sp_begin_var_field(JNIEnv* env, jobject p, uint16_t field_id) {
    env->CallVoidMethod(p, g_jni.mid_parcel_write_int, static_cast<jint>(0xFFFF0000u | field_id));
    env->CallVoidMethod(p, g_jni.mid_parcel_write_int, static_cast<jint>(0));
    return env->CallIntMethod(p, g_jni.mid_parcel_data_pos);
}

void sp_end_var_field(JNIEnv* env, jobject p, jint start_pos) {
    jint end_pos = env->CallIntMethod(p, g_jni.mid_parcel_data_pos);
    env->CallVoidMethod(p, g_jni.mid_parcel_set_data_pos, start_pos - 4);
    env->CallVoidMethod(p, g_jni.mid_parcel_write_int, end_pos - start_pos);
    env->CallVoidMethod(p, g_jni.mid_parcel_set_data_pos, end_pos);
}

jint sp_begin_object_header(JNIEnv* env, jobject p) {
    return sp_begin_var_field(env, p, static_cast<uint16_t>(kSafeParcelObjMagic));
}

void sp_finish_object_header(JNIEnv* env, jobject p, jint start_pos) {
    sp_end_var_field(env, p, start_pos);
}

void sp_write_int_field(JNIEnv* env, jobject p, uint16_t field_id, int32_t val) {
    env->CallVoidMethod(p, g_jni.mid_parcel_write_int, static_cast<jint>((4u << 16) | field_id));
    env->CallVoidMethod(p, g_jni.mid_parcel_write_int, static_cast<jint>(val));
}

void sp_write_long_field(JNIEnv* env, jobject p, uint16_t field_id, int64_t val) {
    env->CallVoidMethod(p, g_jni.mid_parcel_write_int, static_cast<jint>((8u << 16) | field_id));
    env->CallVoidMethod(p, g_jni.mid_parcel_write_long, static_cast<jlong>(val));
}

// Write GMS SafeParcelable LocationResult (field 1: typed list of 1 android.location.Location)
void write_gms_location_result(JNIEnv* env, jobject p, jobject loc) {
    env->CallVoidMethod(p, g_jni.mid_parcel_write_int, static_cast<jint>(1)); // non-null AIDL Parcelable
    jint obj_start = sp_begin_object_header(env, p);

    // Field 1: List<Location> via SafeParcelWriter.writeTypedList format
    jint list_start = sp_begin_var_field(env, p, 1u);
    env->CallVoidMethod(p, g_jni.mid_parcel_write_int, static_cast<jint>(1)); // list count = 1
    jint elem_size_pos = env->CallIntMethod(p, g_jni.mid_parcel_data_pos);
    env->CallVoidMethod(p, g_jni.mid_parcel_write_int, static_cast<jint>(1)); // placeholder (> 0)
    jint elem_start = env->CallIntMethod(p, g_jni.mid_parcel_data_pos);
    env->CallVoidMethod(loc, g_jni.mid_loc_write_to_parcel, p, static_cast<jint>(0));
    jint elem_end = env->CallIntMethod(p, g_jni.mid_parcel_data_pos);
    env->CallVoidMethod(p, g_jni.mid_parcel_set_data_pos, elem_size_pos);
    env->CallVoidMethod(p, g_jni.mid_parcel_write_int, elem_end - elem_start);
    env->CallVoidMethod(p, g_jni.mid_parcel_set_data_pos, elem_end);
    sp_end_var_field(env, p, list_start);

    sp_finish_object_header(env, p, obj_start);
}

// Write GMS SafeParcelable LocationAvailability (fields 1/2/3/4 with locationStatus = 0)
void write_gms_location_availability(JNIEnv* env, jobject p, int64_t boot_ns) {
    env->CallVoidMethod(p, g_jni.mid_parcel_write_int, static_cast<jint>(1)); // non-null AIDL Parcelable
    jint obj_start = sp_begin_object_header(env, p);
    sp_write_int_field(env, p, 1u, 0);        // cellStatus = 0 (STATUS_SUCCESSFUL)
    sp_write_int_field(env, p, 2u, 0);        // wifiStatus = 0 (STATUS_SUCCESSFUL)
    sp_write_long_field(env, p, 3u, boot_ns); // elapsedRealtimeNs
    sp_write_int_field(env, p, 4u, 0);        // locationStatus = 0 (< 1000 => available)
    sp_finish_object_header(env, p, obj_start);
}

// Write GMS SafeParcelable Status (field 1 statusCode = 0 + field 1000 versionCode = 1)
void write_gms_status_ok(JNIEnv* env, jobject p) {
    env->CallVoidMethod(p, g_jni.mid_parcel_write_int, static_cast<jint>(1)); // non-null AIDL Parcelable
    jint obj_start = sp_begin_object_header(env, p);
    sp_write_int_field(env, p, 1u, 0);    // statusCode = CommonStatusCodes.SUCCESS (0)
    sp_write_int_field(env, p, 1000u, 1); // versionCode = 1
    sp_finish_object_header(env, p, obj_start);
}

enum class SynthKind : uint8_t {
    CALLBACK_RESULT = 1,
    CALLBACK_AVAILABILITY = 2,
    LISTENER_CHANGED = 3,
    STATUS_CALLBACK_RESULT = 4,
};

// Build a synthetic outgoing Parcel via JNI and invoke orig_bpbinder_transact directly.
// Returns true if the synthetic transaction was executed (writing Binder status to *out_ret).
bool send_synthetic_outgoing(JNIEnv* env, FnBinderTransact orig, void* bp_binder,
                             SynthKind synth_kind, const KurokoShm& snap,
                             void* reply, uint32_t flags, int32_t* out_ret) {
    if (!env || !orig || !bp_binder || !g_jni.ready) return false;
    if (env->ExceptionCheck()) return false; // Never clobber caller's pending Java exception

    if (env->PushLocalFrame(32) != JNI_OK) {
        clear_exc(env);
        return false;
    }

    jobject jparcel = env->CallStaticObjectMethod(g_jni.cls_parcel, g_jni.mid_parcel_obtain);
    if (!jparcel || env->ExceptionCheck()) {
        clear_exc(env);
        env->PopLocalFrame(nullptr);
        return false;
    }

    bool built = false;
    uint32_t tx_code = 1u;

    if (synth_kind == SynthKind::CALLBACK_AVAILABILITY) {
        tx_code = 2u;
        env->CallVoidMethod(jparcel, g_jni.mid_parcel_write_token, g_jni.str_desc_cb);
        int64_t boot_ns = static_cast<int64_t>(snap.snapshot_boot_ns ? snap.snapshot_boot_ns : now_boot_ns());
        write_gms_location_availability(env, jparcel, boot_ns);
        built = !env->ExceptionCheck();
    } else {
        jobject loc = build_java_location(env, snap);
        if (loc && !env->ExceptionCheck()) {
            switch (synth_kind) {
                case SynthKind::CALLBACK_RESULT:
                    tx_code = 1u;
                    env->CallVoidMethod(jparcel, g_jni.mid_parcel_write_token, g_jni.str_desc_cb);
                    write_gms_location_result(env, jparcel, loc);
                    built = !env->ExceptionCheck();
                    break;
                case SynthKind::LISTENER_CHANGED:
                    tx_code = 1u;
                    env->CallVoidMethod(jparcel, g_jni.mid_parcel_write_token, g_jni.str_desc_listener);
                    env->CallVoidMethod(jparcel, g_jni.mid_parcel_write_int, static_cast<jint>(1));
                    env->CallVoidMethod(loc, g_jni.mid_loc_write_to_parcel, jparcel, static_cast<jint>(0));
                    built = !env->ExceptionCheck();
                    break;
                case SynthKind::STATUS_CALLBACK_RESULT:
                    tx_code = 1u;
                    env->CallVoidMethod(jparcel, g_jni.mid_parcel_write_token, g_jni.str_desc_status_cb);
                    write_gms_status_ok(env, jparcel);
                    env->CallVoidMethod(jparcel, g_jni.mid_parcel_write_int, static_cast<jint>(1));
                    env->CallVoidMethod(loc, g_jni.mid_loc_write_to_parcel, jparcel, static_cast<jint>(0));
                    built = !env->ExceptionCheck();
                    break;
                default:
                    break;
            }
        }
    }

    bool executed = false;
    if (built) {
        jlong native_ptr = env->GetLongField(jparcel, g_jni.fid_parcel_native_ptr);
        if (!env->ExceptionCheck() && native_ptr != 0) {
            const void* native_parcel = reinterpret_cast<const void*>(static_cast<uintptr_t>(native_ptr));
            int32_t ret = orig(bp_binder, tx_code, native_parcel, reply, flags);
            if (out_ret) *out_ret = ret;
            executed = true;
        }
    }

    clear_exc(env);
    env->CallVoidMethod(jparcel, g_jni.mid_parcel_recycle);
    clear_exc(env);
    env->PopLocalFrame(nullptr);
    return executed;
}

// Rewrite synchronous getLastLocation reply Parcel via JNI Parcel.marshall() + Parcel::setData().
bool rewrite_sync_last_location_reply(JNIEnv* env, void* reply, const KurokoShm& snap) {
    if (!env || !reply || !g_jni.ready || !g_parcel_set_data) return false;
    if (env->ExceptionCheck()) return false;

    if (env->PushLocalFrame(32) != JNI_OK) {
        clear_exc(env);
        return false;
    }

    jobject jparcel = env->CallStaticObjectMethod(g_jni.cls_parcel, g_jni.mid_parcel_obtain);
    if (!jparcel || env->ExceptionCheck()) {
        clear_exc(env);
        env->PopLocalFrame(nullptr);
        return false;
    }

    bool ok = false;
    jobject loc = build_java_location(env, snap);
    if (loc && !env->ExceptionCheck()) {
        env->CallVoidMethod(jparcel, g_jni.mid_parcel_write_no_exc);
        env->CallVoidMethod(jparcel, g_jni.mid_parcel_write_int, static_cast<jint>(1));
        env->CallVoidMethod(loc, g_jni.mid_loc_write_to_parcel, jparcel, static_cast<jint>(1));
        auto arr = static_cast<jbyteArray>(env->CallObjectMethod(jparcel, g_jni.mid_parcel_marshall));
        if (arr && !env->ExceptionCheck()) {
            jsize len = env->GetArrayLength(arr);
            if (len > 0) {
                jbyte* bytes = env->GetByteArrayElements(arr, nullptr);
                if (bytes && !env->ExceptionCheck()) {
                    ok = (g_parcel_set_data(reply, reinterpret_cast<const uint8_t*>(bytes),
                                            static_cast<size_t>(len)) == 0);
                    env->ReleaseByteArrayElements(arr, bytes, JNI_ABORT);
                }
            }
        }
    }

    clear_exc(env);
    env->CallVoidMethod(jparcel, g_jni.mid_parcel_recycle);
    clear_exc(env);
    env->PopLocalFrame(nullptr);
    return ok;
}

// Probe an unclassified callback BpBinder via INTERFACE_TRANSACTION (called ONLY from background pump thread).
CallbackKind probe_binder_interface(JNIEnv* env, FnBinderTransact orig, void* bp_binder, bool* out_dead) {
    if (out_dead) *out_dead = false;
    if (!env || !orig || !bp_binder || !g_jni.ready) return CallbackKind::UNKNOWN;
    if (env->ExceptionCheck()) return CallbackKind::UNKNOWN;

    if (env->PushLocalFrame(16) != JNI_OK) {
        clear_exc(env);
        return CallbackKind::UNKNOWN;
    }

    jobject jdata = env->CallStaticObjectMethod(g_jni.cls_parcel, g_jni.mid_parcel_obtain);
    jobject jreply = env->CallStaticObjectMethod(g_jni.cls_parcel, g_jni.mid_parcel_obtain);
    if (!jdata || !jreply || env->ExceptionCheck()) {
        clear_exc(env);
        if (jdata) env->CallVoidMethod(jdata, g_jni.mid_parcel_recycle);
        if (jreply) env->CallVoidMethod(jreply, g_jni.mid_parcel_recycle);
        clear_exc(env);
        env->PopLocalFrame(nullptr);
        return CallbackKind::UNKNOWN;
    }

    CallbackKind result = CallbackKind::IGNORED;
    jlong data_ptr = env->GetLongField(jdata, g_jni.fid_parcel_native_ptr);
    jlong reply_ptr = env->GetLongField(jreply, g_jni.fid_parcel_native_ptr);
    if (!env->ExceptionCheck() && data_ptr != 0 && reply_ptr != 0) {
        int32_t ret = orig(bp_binder, kTxInterfaceTransaction,
                           reinterpret_cast<const void*>(static_cast<uintptr_t>(data_ptr)),
                           reinterpret_cast<void*>(static_cast<uintptr_t>(reply_ptr)),
                           0u);
        if (ret < 0) {
            if (out_dead) *out_dead = true;
            result = CallbackKind::IGNORED;
        } else {
            env->CallVoidMethod(jreply, g_jni.mid_parcel_set_data_pos, static_cast<jint>(0));
            auto jdesc = static_cast<jstring>(env->CallObjectMethod(jreply, g_jni.mid_parcel_read_string));
            if (jdesc && !env->ExceptionCheck()) {
                const char* utf = env->GetStringUTFChars(jdesc, nullptr);
                if (utf) {
                    if (std::strcmp(utf, kDescLocCallback) == 0) {
                        result = CallbackKind::LOCATION_CALLBACK;
                    } else if (std::strcmp(utf, kDescLocListener) == 0) {
                        result = CallbackKind::LOCATION_LISTENER;
                    } else if (std::strcmp(utf, kDescLocStatusCb) == 0) {
                        result = CallbackKind::LOCATION_STATUS_CALLBACK;
                    } else {
                        result = CallbackKind::IGNORED;
                    }
                    env->ReleaseStringUTFChars(jdesc, utf);
                }
            }
        }
    }

    clear_exc(env);
    env->CallVoidMethod(jdata, g_jni.mid_parcel_recycle);
    clear_exc(env);
    env->CallVoidMethod(jreply, g_jni.mid_parcel_recycle);
    clear_exc(env);
    env->PopLocalFrame(nullptr);
    return result;
}

// ============================================================================
// Vtable Hook Functions (ARM64 BTI+PAC clean via -mbranch-protection=standard)
// ============================================================================

[[gnu::noinline]]
int32_t handle_bpbinder_slow(FnBinderTransact orig, void* self, uint32_t code,
                             const void* data, void* reply, uint32_t flags) {
    std::shared_ptr<TrackedBinder> entry;
    {
        std::shared_lock<std::shared_mutex> lk(g_binders_mu);
        auto it = g_tracked_binders.find(self);
        if (it == g_tracked_binders.end()) {
            return orig(self, code, data, reply, flags);
        }
        entry = it->second;
    }

    // Read-only inspection of outgoing Parcel interface token (never mutates dataPosition)
    const uint8_t* raw = g_parcel_data(data);
    size_t raw_size = g_parcel_data_size(data);
    if (!raw || raw_size < 48) {
        return orig(self, code, data, reply, flags);
    }

    CallbackKind detected = CallbackKind::IGNORED;
    size_t after_token = 0;
    if (parcel_has_interface_token_lit(raw, raw_size, kDescLocCallback, &after_token)) {
        detected = CallbackKind::LOCATION_CALLBACK;
    } else if (parcel_has_interface_token_lit(raw, raw_size, kDescLocListener, &after_token)) {
        detected = CallbackKind::LOCATION_LISTENER;
    } else if (parcel_has_interface_token_lit(raw, raw_size, kDescLocStatusCb, &after_token)) {
        detected = CallbackKind::LOCATION_STATUS_CALLBACK;
    }

    if (detected == CallbackKind::IGNORED) {
        // Non-location callback (e.g. IStatusCallback / IFusedLocationProviderCallback): evict cleanly
        entry->kind.store(static_cast<uint8_t>(CallbackKind::IGNORED), std::memory_order_relaxed);
        evict_tracked_binder(self);
        return orig(self, code, data, reply, flags);
    }

    entry->kind.store(static_cast<uint8_t>(detected), std::memory_order_release);

    // Non-target UIDs or inactive/stale SHM pass straight through untouched
    KurokoLocSnap snap{};
    if (!read_fresh_snapshot(&snap)) {
        return orig(self, code, data, reply, flags);
    }
    const KurokoShm& shm_snap = *snap.as_shm();
    uint32_t policy = kuroko_find_target_policy(&shm_snap, entry->uid);
    if ((policy & KUROKO_POLICY_LOCATION) == 0) {
        return orig(self, code, data, reply, flags);
    }

    SynthKind synth{};
    bool should_spoof = false;
    if (detected == CallbackKind::LOCATION_CALLBACK) {
        if (code == 1u) {
            synth = SynthKind::CALLBACK_RESULT;
            should_spoof = true;
        } else if (code == 2u) {
            synth = SynthKind::CALLBACK_AVAILABILITY;
            should_spoof = true;
        }
    } else if (detected == CallbackKind::LOCATION_LISTENER) {
        if (code == 1u) {
            synth = SynthKind::LISTENER_CHANGED;
            should_spoof = true;
        }
    } else if (detected == CallbackKind::LOCATION_STATUS_CALLBACK) {
        if (code == 1u && status_callback_is_success(raw, raw_size, after_token)) {
            synth = SynthKind::STATUS_CALLBACK_RESULT;
            should_spoof = true;
        }
    }

    if (!should_spoof) {
        return orig(self, code, data, reply, flags);
    }

    ScopedFlag guard(g_in_gms_pump);
    ScopedJniEnv jni(g_jvm);
    int32_t synth_ret = 0;
    if (jni.env && send_synthetic_outgoing(jni.env, orig, self, synth, shm_snap, reply, flags, &synth_ret)) {
        uint64_t n = g_cnt_intercepted_cb.fetch_add(1, std::memory_order_relaxed) + 1;
        if (n == 1) {
            LOGI("First GMS outgoing callback spoofed: uid=%u kind=%u tx=%u ret=%d",
                 entry->uid, static_cast<unsigned>(detected), code, synth_ret);
        }
        if (synth == SynthKind::CALLBACK_RESULT || synth == SynthKind::LISTENER_CHANGED) {
            entry->last_delivered_ns.store(now_boot_ns(), std::memory_order_relaxed);
        } else if (synth == SynthKind::CALLBACK_AVAILABILITY) {
            entry->availability_sent.store(true, std::memory_order_relaxed);
        } else if (synth == SynthKind::STATUS_CALLBACK_RESULT) {
            entry->one_shot_done.store(true, std::memory_order_relaxed);
            evict_tracked_binder(self);
        }
        if (synth_ret == kBinderDeadObject) {
            evict_tracked_binder(self);
        }
        return synth_ret;
    }

    return orig(self, code, data, reply, flags);
}

int32_t hook_bpbinder_transact(void* self, uint32_t code, const void* data, void* reply, uint32_t flags) {
    auto orig = g_orig_bpbinder_transact.load(std::memory_order_acquire);
    if (!orig) return -19; // NO_INIT
    // Ultra-fast path (< 30ns, tiny stack frame): pass through immediately if no callbacks tracked
    if (g_in_gms_pump || !g_shm || !self || !data ||
        g_tracked_count.load(std::memory_order_relaxed) == 0) {
        return orig(self, code, data, reply, flags);
    }
    return handle_bpbinder_slow(orig, self, code, data, reply, flags);
}

[[gnu::noinline]]
int32_t handle_bbinder_gms_loc(FnBinderTransact orig, void* self, uint32_t code,
                               const void* data, void* reply, uint32_t flags,
                               uint32_t calling_uid, const uint8_t* raw, size_t raw_size,
                               size_t after_token) {
    ScopedFlag guard(g_in_bb_hook);
    uint64_t n_in = g_cnt_incoming_gms.fetch_add(1, std::memory_order_relaxed) + 1;
    if (n_in == 1) {
        LOGI("First incoming IGoogleLocationManagerService tx=%u from uid=%u", code, calling_uid);
    }

    // Extract embedded BINDER_TYPE_HANDLE callback objects from Parcel without mutating dataPosition
    int32_t handles[16];
    size_t handle_count = 0;
    const size_t* objs = g_parcel_objects(data);
    size_t obj_count = g_parcel_objects_count(data);
    if (objs && obj_count > 0 && obj_count <= 32u) {
        for (size_t i = 0; i < obj_count && handle_count < 16u; ++i) {
            size_t off = objs[i];
            if (off + sizeof(flat_binder_object) > raw_size) continue;
            flat_binder_object fbo{};
            std::memcpy(&fbo, raw + off, sizeof(fbo));
            if (fbo.hdr.type != BINDER_TYPE_HANDLE) continue;
            int32_t h = static_cast<int32_t>(fbo.handle);
            if (h > 0) {
                handles[handle_count++] = h;
            }
        }
    }

    bool is_unregister = (code == 89u) || (code == 59u && is_tx59_remove_request(raw, raw_size, after_token));

    void* newly_tracked[16];
    size_t newly_tracked_count = 0;
    if (handle_count > 0) {
        if (is_unregister) {
            for (size_t i = 0; i < handle_count; ++i) {
                untrack_handle(handles[i]);
            }
        } else {
            // Track BEFORE orig() so immediate synchronous callbacks (e.g. async getLastLocation tx 82)
            // invoked inside GMS onTransact already know their calling_uid!
            for (size_t i = 0; i < handle_count; ++i) {
                bool was_new = false;
                void* bp = track_handle_for_uid(handles[i], calling_uid, &was_new);
                if (bp && was_new && newly_tracked_count < 16u) {
                    newly_tracked[newly_tracked_count++] = bp;
                }
            }
        }
    }

    int32_t ret = orig(self, code, data, reply, flags);

    // If GMS rejected the call (e.g. SecurityException), roll back any newly tracked callback handles
    bool call_failed = (ret != 0);
    if (!call_failed && reply) {
        size_t rep_sz = g_parcel_data_size(reply);
        const uint8_t* rep_raw = (rep_sz >= 4) ? g_parcel_data(reply) : nullptr;
        if (rep_raw) {
            int32_t exc = 0;
            std::memcpy(&exc, rep_raw, 4);
            if (exc != 0) call_failed = true;
        }
    }
    if (call_failed && newly_tracked_count > 0) {
        for (size_t i = 0; i < newly_tracked_count; ++i) {
            evict_tracked_binder(newly_tracked[i]);
        }
        return ret;
    }

    // Synchronous getLastLocation (tx 7 / 21 / 80): rewrite reply for target UIDs when valid
    if (!call_failed && reply && (code == 7u || code == 21u || code == 80u)) {
        KurokoLocSnap snap{};
        if (read_fresh_snapshot(&snap) &&
            (kuroko_find_target_policy(snap.as_shm(), calling_uid) & KUROKO_POLICY_LOCATION) != 0) {
            size_t rep_sz = g_parcel_data_size(reply);
            size_t rep_objs = g_parcel_objects_count(reply);
            const uint8_t* rep_raw = g_parcel_data(reply);
            if (rep_objs == 0 && reply_looks_like_location(rep_raw, rep_sz)) {
                ScopedFlag pump_guard(g_in_gms_pump);
                ScopedJniEnv jni(g_jvm);
                if (jni.env && rewrite_sync_last_location_reply(jni.env, reply, *snap.as_shm())) {
                    uint64_t n_sync = g_cnt_sync_lastloc.fetch_add(1, std::memory_order_relaxed) + 1;
                    if (n_sync == 1) {
                        LOGI("First synchronous getLastLocation (tx=%u) spoofed for uid=%u", code, calling_uid);
                    }
                }
            }
        }
    }

    return ret;
}

int32_t hook_bbinder_transact(void* self, uint32_t code, const void* data, void* reply, uint32_t flags) {
    auto orig = g_orig_bbinder_transact.load(std::memory_order_acquire);
    if (!orig) return -19;
    if (g_in_bb_hook || !data) {
        return orig(self, code, data, reply, flags);
    }

    // Check kernel-verified calling UID first (reject system/GMS self calls in ~10ns)
    void* ipc = g_ipc_self_or_null ? g_ipc_self_or_null() : (g_ipc_self ? g_ipc_self() : nullptr);
    int32_t calling_uid_i = (ipc && g_ipc_get_calling_uid) ? g_ipc_get_calling_uid(ipc) : -1;
    uint32_t my_uid = g_my_uid.load(std::memory_order_relaxed);
    if (calling_uid_i < 10000 || (my_uid != 0 && static_cast<uint32_t>(calling_uid_i) == my_uid)) {
        return orig(self, code, data, reply, flags);
    }

    // Fast size rejection before UTF-16LE descriptor check (IGoogleLocationManagerService = 70 chars = 140B)
    size_t raw_size = g_parcel_data_size(data);
    if (raw_size < 148u) {
        return orig(self, code, data, reply, flags);
    }

    const uint8_t* raw = g_parcel_data(data);
    size_t after_token = 0;
    if (!raw || !parcel_has_interface_token_lit(raw, raw_size, kDescGoogleLocSvc, &after_token)) {
        return orig(self, code, data, reply, flags);
    }

    return handle_bbinder_gms_loc(orig, self, code, data, reply, flags,
                                  static_cast<uint32_t>(calling_uid_i), raw, raw_size, after_token);
}

// ============================================================================
// Kuroko-1Hz-GMS Active Indoor Pump Thread
// ============================================================================

void gms_pump_loop() {
    pthread_setname_np(pthread_self(), "Kuroko-1Hz-GMS");
    LOGI("Kuroko-1Hz-GMS pump thread started (uid=%u)", g_my_uid.load(std::memory_order_relaxed));

    JNIEnv* thr_env = nullptr;
    if (g_jvm) {
        JavaVMAttachArgs attach_args{JNI_VERSION_1_6, "Kuroko-1Hz-GMS", nullptr};
        if (g_jvm->AttachCurrentThreadAsDaemon(&thr_env, &attach_args) != JNI_OK) {
            thr_env = nullptr;
            LOGW("Kuroko-1Hz-GMS: AttachCurrentThreadAsDaemon failed");
        }
    }
    if (!thr_env) {
        LOGE("Kuroko-1Hz-GMS: no JNIEnv available; pump exiting");
        return;
    }

    g_in_gms_pump = true;

    while (true) {
        auto orig = g_orig_bpbinder_transact.load(std::memory_order_acquire);
        KurokoLocSnap snap_buf{};
        bool active_now = (orig != nullptr) &&
                          (g_tracked_count.load(std::memory_order_relaxed) > 0) &&
                          read_fresh_snapshot(&snap_buf);

        if (!active_now) {
            usleep(1000000); // 1s idle sleep (~0% CPU)
            continue;
        }
        const KurokoShm& snap = *snap_buf.as_shm();

        std::vector<std::shared_ptr<TrackedBinder>> snapshot_entries;
        {
            std::shared_lock<std::shared_mutex> lk(g_binders_mu);
            snapshot_entries.reserve(g_tracked_binders.size());
            for (const auto& kv : g_tracked_binders) {
                snapshot_entries.push_back(kv.second);
            }
        }

        bool had_target = false;
        uint64_t now_ns = now_boot_ns();

        for (const auto& entry : snapshot_entries) {
            if (!entry || entry->dead.load(std::memory_order_relaxed)) continue;
            if ((kuroko_find_target_policy(&snap, entry->uid) & KUROKO_POLICY_LOCATION) == 0) {
                continue;
            }
            had_target = true;

            auto kind = static_cast<CallbackKind>(entry->kind.load(std::memory_order_acquire));
            if (kind == CallbackKind::IGNORED) {
                evict_tracked_binder(entry->bp_binder);
                continue;
            }

            // Lazily probe unclassified binders after 150ms via INTERFACE_TRANSACTION (indoor cold-start)
            if (kind == CallbackKind::UNKNOWN) {
                uint64_t reg_ns = entry->registered_ns.load(std::memory_order_relaxed);
                if (now_ns < reg_ns || (now_ns - reg_ns) < kProbeDelayNs) {
                    continue;
                }
                bool is_dead = false;
                CallbackKind probed = probe_binder_interface(thr_env, orig, entry->bp_binder, &is_dead);
                if (is_dead || probed == CallbackKind::IGNORED) {
                    evict_tracked_binder(entry->bp_binder);
                    continue;
                }
                entry->kind.store(static_cast<uint8_t>(probed), std::memory_order_release);
                kind = probed;
            }

            if (kind == CallbackKind::LOCATION_CALLBACK) {
                uint64_t last = entry->last_delivered_ns.load(std::memory_order_relaxed);
                if (last != 0 && now_ns >= last && (now_ns - last) < kPumpDedupGuardNs) {
                    continue;
                }

                // Ensure LocationAvailability(available=true) is delivered at least once
                if (!entry->availability_sent.load(std::memory_order_relaxed)) {
                    int32_t av_ret = 0;
                    if (send_synthetic_outgoing(thr_env, orig, entry->bp_binder,
                                                SynthKind::CALLBACK_AVAILABILITY, snap,
                                                nullptr, kTxFlagOneway, &av_ret)) {
                        if (av_ret < 0) {
                            evict_tracked_binder(entry->bp_binder);
                            continue;
                        }
                        entry->availability_sent.store(true, std::memory_order_relaxed);
                    }
                }

                int32_t ret = 0;
                if (send_synthetic_outgoing(thr_env, orig, entry->bp_binder,
                                            SynthKind::CALLBACK_RESULT, snap,
                                            nullptr, kTxFlagOneway, &ret)) {
                    if (ret == 0) {
                        entry->last_delivered_ns.store(now_boot_ns(), std::memory_order_relaxed);
                        uint64_t p = g_cnt_pumped.fetch_add(1, std::memory_order_relaxed) + 1;
                        if (p == 1) {
                            LOGI("First 1Hz GMS pump delivered (ILocationCallback) to uid=%u", entry->uid);
                        }
                    } else {
                        evict_tracked_binder(entry->bp_binder);
                    }
                }
            } else if (kind == CallbackKind::LOCATION_LISTENER) {
                uint64_t last = entry->last_delivered_ns.load(std::memory_order_relaxed);
                if (last != 0 && now_ns >= last && (now_ns - last) < kPumpDedupGuardNs) {
                    continue;
                }
                int32_t ret = 0;
                if (send_synthetic_outgoing(thr_env, orig, entry->bp_binder,
                                            SynthKind::LISTENER_CHANGED, snap,
                                            nullptr, kTxFlagOneway, &ret)) {
                    if (ret == 0) {
                        entry->last_delivered_ns.store(now_boot_ns(), std::memory_order_relaxed);
                        uint64_t p = g_cnt_pumped.fetch_add(1, std::memory_order_relaxed) + 1;
                        if (p == 1) {
                            LOGI("First 1Hz GMS pump delivered (ILocationListener) to uid=%u", entry->uid);
                        }
                    } else {
                        evict_tracked_binder(entry->bp_binder);
                    }
                }
            } else if (kind == CallbackKind::LOCATION_STATUS_CALLBACK) {
                // One-shot rescue for indoor getCurrentLocation when GMS stalls waiting for HW GPS
                uint64_t reg_ns = entry->registered_ns.load(std::memory_order_relaxed);
                if (!entry->one_shot_done.load(std::memory_order_relaxed) &&
                    now_ns >= reg_ns && (now_ns - reg_ns) >= kOneShotRescueDelayNs) {
                    if (!entry->one_shot_done.exchange(true, std::memory_order_acq_rel)) {
                        int32_t ret = 0;
                        send_synthetic_outgoing(thr_env, orig, entry->bp_binder,
                                                SynthKind::STATUS_CALLBACK_RESULT, snap,
                                                nullptr, kTxFlagOneway, &ret);
                        evict_tracked_binder(entry->bp_binder);
                    }
                }
            }
        }

        // 250ms tick when active with target callbacks (850ms per-binder dedup => 1Hz steady-state,
        // <=250ms cold-start indoors); 1000ms when no target callbacks exist.
        usleep(had_target ? 250000 : 1000000);
    }
}

} // namespace

bool install_gms_hooks(JNIEnv* env, const KurokoShm* shm, const std::string& proc_name,
                       std::string* out_status, uint32_t self_uid) {
    auto set_status = [&](const std::string& s) {
        if (out_status) *out_status = s;
    };

    if (g_hooks_installed.load(std::memory_order_acquire)) {
        set_status("ok:already_installed");
        return true;
    }
    if (!shm || !env) {
        set_status("err:null_arg");
        return false;
    }

    g_shm = shm;
    if (self_uid != 0) {
        g_my_uid.store(self_uid, std::memory_order_relaxed);
    } else {
        g_my_uid.store(static_cast<uint32_t>(getuid()), std::memory_order_relaxed);
    }
    if (env->GetJavaVM(&g_jvm) != JNI_OK || !g_jvm) {
        set_status("err:no_jvm");
        return false;
    }

    // 1. Pre-flight JNI cache initialization (Parcel, Location, Bundle — bootclasspath)
    if (!init_jni_cache(env)) {
        set_status("err:jni_init");
        LOGE("install_gms_hooks(%s): JNI cache init failed", proc_name.c_str());
        return false;
    }

    // 2. Pure C++ /proc/self/maps + ELF64 discovery of libbinder.so, libandroid_runtime.so, libutils.so
    auto vmas = snapshot_readable_private_vmas();
    MappedLibrary lib_binder, lib_runtime, lib_utils;
    if (!locate_library_in_maps("/libbinder.so", "/system/lib64/libbinder.so", vmas, &lib_binder)) {
        set_status("err:no_libbinder");
        LOGE("install_gms_hooks(%s): libbinder.so not found in maps", proc_name.c_str());
        return false;
    }
    if (!locate_library_in_maps("/libandroid_runtime.so", "/system/lib64/libandroid_runtime.so", vmas, &lib_runtime)) {
        set_status("err:no_libandroid_runtime");
        LOGE("install_gms_hooks(%s): libandroid_runtime.so not found in maps", proc_name.c_str());
        return false;
    }
    bool have_utils = locate_library_in_maps("/libutils.so", "/system/lib64/libutils.so", vmas, &lib_utils);

    // 3. Resolve all required symbols purely via ELF64 .dynsym/.dynstr (ZERO dlopen/dlsym!)
    uintptr_t addr_bp_transact = lookup_elf_symbol(lib_binder, vmas, "_ZN7android8BpBinder8transactEjRKNS_6ParcelEPS1_j");
    uintptr_t addr_bb_transact = lookup_elf_symbol(lib_binder, vmas, "_ZN7android7BBinder8transactEjRKNS_6ParcelEPS1_j");
    uintptr_t addr_ipc_self    = lookup_elf_symbol(lib_binder, vmas, "_ZN7android14IPCThreadState4selfEv");
    uintptr_t addr_ipc_self_nn = lookup_elf_symbol(lib_binder, vmas, "_ZN7android14IPCThreadState10selfOrNullEv");
    uintptr_t addr_calling_uid = lookup_elf_symbol(lib_binder, vmas, "_ZNK7android14IPCThreadState13getCallingUidEv");
    if (addr_calling_uid == 0) {
        addr_calling_uid = lookup_elf_symbol(lib_binder, vmas, "_ZN7android14IPCThreadState13getCallingUidEv");
    }
    uintptr_t addr_p_data      = lookup_elf_symbol(lib_binder, vmas, "_ZNK7android6Parcel4dataEv");
    uintptr_t addr_p_size      = lookup_elf_symbol(lib_binder, vmas, "_ZNK7android6Parcel8dataSizeEv");
    uintptr_t addr_p_objs      = lookup_elf_symbol(lib_binder, vmas, "_ZNK7android6Parcel7objectsEv");
    uintptr_t addr_p_objs_cnt  = lookup_elf_symbol(lib_binder, vmas, "_ZNK7android6Parcel12objectsCountEv");
    uintptr_t addr_p_pos       = lookup_elf_symbol(lib_binder, vmas, "_ZNK7android6Parcel12dataPositionEv");
    uintptr_t addr_p_set_pos   = lookup_elf_symbol(lib_binder, vmas, "_ZNK7android6Parcel15setDataPositionEm");
    if (addr_p_set_pos == 0) {
        addr_p_set_pos = lookup_elf_symbol(lib_binder, vmas, "_ZN7android6Parcel15setDataPositionEm");
    }
    uintptr_t addr_p_set_data  = lookup_elf_symbol(lib_binder, vmas, "_ZN7android6Parcel7setDataEPKhm");
    uintptr_t addr_ps_self     = lookup_elf_symbol(lib_binder, vmas, "_ZN7android12ProcessState4selfEv");
    uintptr_t addr_ps_proxy    = lookup_elf_symbol(lib_binder, vmas, "_ZN7android12ProcessState23getStrongProxyForHandleEi");

    uintptr_t addr_dec_strong = 0;
    if (have_utils) {
        addr_dec_strong = lookup_elf_symbol(lib_utils, vmas, "_ZNK7android7RefBase9decStrongEPKv");
    }
    if (addr_dec_strong == 0) {
        addr_dec_strong = lookup_elf_symbol(lib_binder, vmas, "_ZNK7android7RefBase9decStrongEPKv");
    }

    if (!addr_bp_transact || !addr_bb_transact || (!addr_ipc_self && !addr_ipc_self_nn) ||
        !addr_calling_uid || !addr_p_data || !addr_p_size || !addr_p_objs || !addr_p_objs_cnt ||
        !addr_ps_self || !addr_ps_proxy) {
        char err_buf[128];
        std::snprintf(err_buf, sizeof(err_buf),
                      "err:missing_sym:bp=%d,bb=%d,ipc=%d,uid=%d,p=%d,ps=%d",
                      addr_bp_transact ? 1 : 0,
                      addr_bb_transact ? 1 : 0,
                      (addr_ipc_self || addr_ipc_self_nn) ? 1 : 0,
                      addr_calling_uid ? 1 : 0,
                      (addr_p_data && addr_p_size && addr_p_objs && addr_p_objs_cnt) ? 1 : 0,
                      (addr_ps_self && addr_ps_proxy) ? 1 : 0);
        set_status(err_buf);
        LOGE("install_gms_hooks(%s): %s", proc_name.c_str(), err_buf);
        return false;
    }

    // 4. Pre-flight PT_GNU_RELRO vtable scan BEFORE patching any slot
    std::vector<VtableSlot> bp_slots;
    std::vector<VtableSlot> bb_slots;

    std::vector<VmaRange> combined_exec = lib_binder.exec_ranges;
    combined_exec.insert(combined_exec.end(), lib_runtime.exec_ranges.begin(), lib_runtime.exec_ranges.end());

    find_vtable_slots_in_relro(lib_binder, vmas, lib_binder.exec_ranges, addr_bp_transact, &bp_slots);
    find_vtable_slots_in_relro(lib_runtime, vmas, combined_exec, addr_bb_transact, &bb_slots);
    find_vtable_slots_in_relro(lib_binder, vmas, lib_binder.exec_ranges, addr_bb_transact, &bb_slots);

    if (bp_slots.empty() || bb_slots.empty()) {
        char err_buf[96];
        std::snprintf(err_buf, sizeof(err_buf), "err:vtable_scan:bp=%zu,bb=%zu",
                      bp_slots.size(), bb_slots.size());
        set_status(err_buf);
        LOGE("install_gms_hooks(%s): %s", proc_name.c_str(), err_buf);
        return false;
    }

    // 5. Populate all resolved function pointers & originals BEFORE atomic vtable swap
    g_ipc_self              = reinterpret_cast<FnIpcSelf>(addr_ipc_self);
    g_ipc_self_or_null      = reinterpret_cast<FnIpcSelf>(addr_ipc_self_nn);
    g_ipc_get_calling_uid   = reinterpret_cast<FnIpcGetCallingUid>(addr_calling_uid);
    g_parcel_data           = reinterpret_cast<FnParcelData>(addr_p_data);
    g_parcel_data_size      = reinterpret_cast<FnParcelDataSize>(addr_p_size);
    g_parcel_objects        = reinterpret_cast<FnParcelObjects>(addr_p_objs);
    g_parcel_objects_count  = reinterpret_cast<FnParcelObjectsCount>(addr_p_objs_cnt);
    g_parcel_data_pos       = reinterpret_cast<FnParcelDataPosition>(addr_p_pos);
    g_parcel_set_data_pos   = reinterpret_cast<FnParcelSetDataPosition>(addr_p_set_pos);
    g_parcel_set_data       = reinterpret_cast<FnParcelSetData>(addr_p_set_data);
    g_process_state_self    = reinterpret_cast<FnProcessStateSelf>(addr_ps_self);
    g_get_strong_proxy      = reinterpret_cast<FnGetStrongProxyForHandle>(addr_ps_proxy);
    g_refbase_dec_strong    = reinterpret_cast<FnRefBaseDecStrong>(addr_dec_strong);

    g_orig_bpbinder_transact.store(reinterpret_cast<FnBinderTransact>(addr_bp_transact), std::memory_order_release);
    g_orig_bbinder_transact.store(reinterpret_cast<FnBinderTransact>(addr_bb_transact), std::memory_order_release);

    // 6. Atomic PT_GNU_RELRO vtable pointer swap
    size_t bp_patched = patch_vtable_slots(bp_slots, reinterpret_cast<void*>(addr_bp_transact),
                                           reinterpret_cast<void*>(hook_bpbinder_transact));
    size_t bb_patched = patch_vtable_slots(bb_slots, reinterpret_cast<void*>(addr_bb_transact),
                                           reinterpret_cast<void*>(hook_bbinder_transact));

    if (bp_patched == 0 || bb_patched == 0) {
        // Roll back any partial patch so we never leave half-hooked vtables before DLCLOSE
        if (bp_patched > 0) {
            patch_vtable_slots(bp_slots, reinterpret_cast<void*>(hook_bpbinder_transact),
                               reinterpret_cast<void*>(addr_bp_transact));
        }
        if (bb_patched > 0) {
            patch_vtable_slots(bb_slots, reinterpret_cast<void*>(hook_bbinder_transact),
                               reinterpret_cast<void*>(addr_bb_transact));
        }
        char err_buf[96];
        std::snprintf(err_buf, sizeof(err_buf), "err:vtable_patch:bp=%zu,bb=%zu", bp_patched, bb_patched);
        set_status(err_buf);
        LOGE("install_gms_hooks(%s): %s", proc_name.c_str(), err_buf);
        return false;
    }

    g_hooks_installed.store(true, std::memory_order_release);

    const char* short_proc = (proc_name == "com.google.android.gms.persistent") ? "gms.persistent" : "gms";
    char ok_buf[128];
    std::snprintf(ok_buf, sizeof(ok_buf), "ok:vtable:mv=%d,p=%s,bp=%zu,bb=%zu",
                  KUROKO_MODULE_VERSION_CODE, short_proc, bp_patched, bb_patched);
    set_status(ok_buf);
    LOGI("install_gms_hooks(%s): installed (%s, decStrong=%d, setData=%d)",
         proc_name.c_str(), ok_buf, addr_dec_strong ? 1 : 0, addr_p_set_data ? 1 : 0);
    return true;
}

void start_gms_pump_thread() {
    if (!g_hooks_installed.load(std::memory_order_acquire)) return;
    g_my_uid.store(static_cast<uint32_t>(getuid()), std::memory_order_relaxed);
    if (!g_pump_started.exchange(true, std::memory_order_acq_rel)) {
        std::thread(gms_pump_loop).detach();
    }
}

} // namespace kuroko
