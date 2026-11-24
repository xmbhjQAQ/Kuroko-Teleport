#include "sensor_hook.h"

#include <android/log.h>
#include <elf.h>
#include <errno.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#define LOG_TAG "KurokoSensorHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef R_AARCH64_JUMP_SLOT
#define R_AARCH64_JUMP_SLOT 1026
#endif

#ifndef KUROKO_MODULE_VERSION_CODE
#define KUROKO_MODULE_VERSION_CODE 0
#endif

namespace kuroko {

namespace {

// Verified on Android 17 arm64 libsensorservice.so (0x3c220: mov w15, 0x68)
struct alignas(8) SensorsEvent {
    int32_t version;       // +0x00 (= 104)
    int32_t sensor;        // +0x04 (handle)
    int32_t type;          // +0x08 (SENSOR_TYPE_*)
    int32_t reserved0;     // +0x0C
    int64_t timestamp;     // +0x10 (CLOCK_BOOTTIME ns)
    union {
        float    data[16];     // +0x18
        uint64_t u64_data[8];  // +0x18 (u64_data[0] = step_counter)
    };
    uint32_t flags;        // +0x58
    int32_t  reserved1[3]; // +0x5C..0x67
};
static_assert(sizeof(SensorsEvent) == 0x68, "SensorsEvent must match AOSP 104-byte sensors_event_t");

constexpr int32_t kSensorTypeAccel        = 1;
constexpr int32_t kSensorTypeMag          = 2;
constexpr int32_t kSensorTypeOrientation  = 3;
constexpr int32_t kSensorTypeGyro         = 4;
constexpr int32_t kSensorTypePressure     = 6;
constexpr int32_t kSensorTypeGravity      = 9;
constexpr int32_t kSensorTypeLinearAccel  = 10;
constexpr int32_t kSensorTypeRotVec       = 11;
constexpr int32_t kSensorTypeGameRotVec   = 15;
constexpr int32_t kSensorTypeSigMotion    = 17;
constexpr int32_t kSensorTypeStepDetector = 18;
constexpr int32_t kSensorTypeStepCounter  = 19;

constexpr uint64_t kStaleHeartbeatNs = 5000000000ULL; // 5s fail-safe
constexpr float    kRadToDegF        = 57.2957795f;
constexpr const char* kLibSensorServicePath   = "/system/lib64/libsensorservice.so";
constexpr const char* kLibSensorServiceSuffix = "/libsensorservice.so";

struct ConnRecord {
    uint32_t uid = 0;
    std::unordered_set<int32_t> active_handles;
    bool step_base_initialized = false;
    std::atomic<bool> need_initial_emit{true};
    uint64_t step_counter_base = 0;
    uint64_t last_vsteps_seen = 0;
    uint64_t emitted_steps = 0;
};

struct MappedElf {
    uintptr_t base = 0;
    dev_t dev = 0;
    ino_t ino = 0;
};

struct VmaRange {
    uintptr_t start = 0;
    uintptr_t end = 0;
    int prot = 0;
};

const KurokoShm* g_shm = nullptr;
JavaVM* g_jvm = nullptr;

std::shared_mutex g_state_mu;
std::unordered_map<void*, ConnRecord> g_conns;
std::unordered_map<int32_t, int32_t> g_handle_to_type;
std::atomic<uint64_t> g_last_hw_steps{0};
std::atomic<bool> g_injector_started{false};

std::mutex g_patch_mu;
std::unordered_set<uintptr_t> g_patched_bases;
std::unordered_set<uintptr_t> g_diag_logged_bases;

// Thread-local handoff pairing SensorList::add(handle, ...) with RecentEventLogger::C1(sensor_type)
thread_local int32_t tls_pending_reg_handle = -1;

// Logical hook slots: 5 core slots + 3 discovery slots (multi-version candidates deduplicated by bitmask)
enum class HookSlot : uint8_t {
    CONN_C1          = 0,
    ADD_SENSOR       = 1,
    REMOVE_SENSOR    = 2,
    SEND_EVENTS      = 3,
    CLEANUP_CONN     = 4,
    SENSOR_LIST_ADD  = 5,
    RECENT_LOGGER_C1 = 6,
    MI_SAVE_USAGE    = 7,
};

constexpr bool is_core_slot(HookSlot slot) {
    return static_cast<uint8_t>(slot) < 5u;
}

constexpr uint32_t slot_bit(HookSlot slot) {
    uint8_t idx = static_cast<uint8_t>(slot);
    return 1u << (idx < 5u ? idx : (idx - 5u));
}

int popcount_u32(uint32_t mask) {
    return __builtin_popcount(mask);
}

constexpr uint32_t kRequiredCoreMask = slot_bit(HookSlot::CONN_C1) | slot_bit(HookSlot::SEND_EVENTS);

// Exact compile-time indices into kHookSpecs[] and static g_plt_orig_slots[]
enum SpecIdx : size_t {
    SPEC_CONN_C1_7VAL = 0,
    SPEC_CONN_C1_7REF,
    SPEC_CONN_C1_6VAL,
    SPEC_CONN_C1_8VAL_I,
    SPEC_CONN_C1_8REF_I,
    SPEC_CONN_C1_9VAL_IB,
    SPEC_CONN_C1_9VAL_II,
    SPEC_CONN_C1_5NOMODE,
    SPEC_CONN_C1_6NOMODE_ATTR,
    SPEC_CONN_C1_6NOMODE_I,
    SPEC_CONN_C1_7NOMODE_ATTR_I,
    SPEC_ADD_SENSOR,
    SPEC_REMOVE_SENSOR,
    SPEC_SEND_EVENTS,
    SPEC_CLEANUP_CONN,
    SPEC_SENSOR_LIST_ADD_6,
    SPEC_SENSOR_LIST_ADD_5,
    SPEC_SENSOR_LIST_ADD_4,
    SPEC_RECENT_LOGGER_C1,
    SPEC_MI_SAVE_USAGE,
    SPEC_COUNT
};

// Global static-lifetime PLT orig slots passed to api->pltHookRegister (NEVER stack-local!)
void* g_plt_orig_slots[SPEC_COUNT] = {};

// Original PLT/GOT function pointers (one dedicated std::atomic per exact AAPCS64 signature)
using FnConnC1_7Val        = void* (*)(void*, const void*, uint32_t, int32_t, void*, bool, const void*, const void*);
using FnConnC1_7Ref        = void* (*)(void*, const void*, uint32_t, int32_t, const void*, bool, const void*, const void*);
using FnConnC1_6Val        = void* (*)(void*, const void*, uint32_t, int32_t, void*, bool, const void*);
using FnConnC1_8ValI       = void* (*)(void*, const void*, uint32_t, int32_t, void*, bool, const void*, const void*, int32_t);
using FnConnC1_8RefI       = void* (*)(void*, const void*, uint32_t, int32_t, const void*, bool, const void*, const void*, int32_t);
using FnConnC1_9ValIB      = void* (*)(void*, const void*, uint32_t, int32_t, void*, bool, const void*, const void*, int32_t, bool);
using FnConnC1_9ValII      = void* (*)(void*, const void*, uint32_t, int32_t, void*, bool, const void*, const void*, int32_t, int32_t);
using FnConnC1_5NoMode     = void* (*)(void*, const void*, uint32_t, void*, bool, const void*);
using FnConnC1_6NoModeAttr = void* (*)(void*, const void*, uint32_t, void*, bool, const void*, const void*);
using FnConnC1_6NoModeI    = void* (*)(void*, const void*, uint32_t, void*, bool, const void*, int32_t);
using FnConnC1_7NoModeAttrI = void* (*)(void*, const void*, uint32_t, void*, bool, const void*, const void*, int32_t);

using FnAddRemoveSensor     = bool (*)(void*, int32_t);
using FnSendEvents          = int32_t (*)(void*, const SensorsEvent*, size_t, SensorsEvent*, const void*);
using FnCleanupConn         = void (*)(void*, void*);

using FnSensorListAdd6      = bool (*)(void* self, int32_t handle, void* sp_iface, bool is_debug, bool is_virtual, int32_t dev_id);
using FnSensorListAdd5      = bool (*)(void* self, int32_t handle, void* sp_iface, bool is_debug, bool is_virtual);
using FnSensorListAdd4      = bool (*)(void* self, int32_t handle, void* sp_iface, bool is_debug);

using FnRecentLoggerC1      = void* (*)(void* self, int32_t sensor_type);
using FnSaveSensorUsageItem = void (*)(int32_t type, const void* str8_name, const void* str8_pkg, bool enable, int32_t arg4, int32_t handle);

std::atomic<FnConnC1_7Val>         g_orig_conn_c1_7val{nullptr};
std::atomic<FnConnC1_7Ref>         g_orig_conn_c1_7ref{nullptr};
std::atomic<FnConnC1_6Val>         g_orig_conn_c1_6val{nullptr};
std::atomic<FnConnC1_8ValI>        g_orig_conn_c1_8val_i{nullptr};
std::atomic<FnConnC1_8RefI>        g_orig_conn_c1_8ref_i{nullptr};
std::atomic<FnConnC1_9ValIB>       g_orig_conn_c1_9val_ib{nullptr};
std::atomic<FnConnC1_9ValII>       g_orig_conn_c1_9val_ii{nullptr};
std::atomic<FnConnC1_5NoMode>      g_orig_conn_c1_5nomode{nullptr};
std::atomic<FnConnC1_6NoModeAttr>  g_orig_conn_c1_6nomode_attr{nullptr};
std::atomic<FnConnC1_6NoModeI>     g_orig_conn_c1_6nomode_i{nullptr};
std::atomic<FnConnC1_7NoModeAttrI> g_orig_conn_c1_7nomode_attr_i{nullptr};

std::atomic<FnAddRemoveSensor>     g_orig_add_sensor{nullptr};
std::atomic<FnAddRemoveSensor>     g_orig_remove_sensor{nullptr};
std::atomic<FnSendEvents>          g_orig_send_events{nullptr};
std::atomic<FnCleanupConn>         g_orig_cleanup_conn{nullptr};

std::atomic<FnSensorListAdd6>      g_orig_sensor_list_add_6{nullptr};
std::atomic<FnSensorListAdd5>      g_orig_sensor_list_add_5{nullptr};
std::atomic<FnSensorListAdd4>      g_orig_sensor_list_add_4{nullptr};

std::atomic<FnRecentLoggerC1>      g_orig_recent_logger_c1{nullptr};
std::atomic<FnSaveSensorUsageItem> g_orig_save_sensor_usage_item{nullptr};

// Lock-free orig loader with static g_plt_orig_slots[] fallback (eliminates mid-commit/partial-commit null orig)
template <typename Fn>
inline Fn load_orig_for_slot(std::atomic<Fn>& atomic_orig, size_t spec_idx, void* self_hook_fn) {
    Fn fn = atomic_orig.load(std::memory_order_acquire);
    if (fn) return fn;
    void* raw = __atomic_load_n(&g_plt_orig_slots[spec_idx], __ATOMIC_ACQUIRE);
    if (raw && raw != self_hook_fn) {
        fn = reinterpret_cast<Fn>(raw);
        atomic_orig.store(fn, std::memory_order_release);
        return fn;
    }
    return nullptr;
}

bool has_any_conn_c1_orig() {
    for (size_t idx = SPEC_CONN_C1_7VAL; idx <= SPEC_CONN_C1_7NOMODE_ATTR_I; ++idx) {
        if (__atomic_load_n(&g_plt_orig_slots[idx], __ATOMIC_ACQUIRE) != nullptr) {
            return true;
        }
    }
    return g_orig_conn_c1_7val.load(std::memory_order_acquire) != nullptr ||
           g_orig_conn_c1_7ref.load(std::memory_order_acquire) != nullptr ||
           g_orig_conn_c1_6val.load(std::memory_order_acquire) != nullptr ||
           g_orig_conn_c1_8val_i.load(std::memory_order_acquire) != nullptr ||
           g_orig_conn_c1_8ref_i.load(std::memory_order_acquire) != nullptr ||
           g_orig_conn_c1_9val_ib.load(std::memory_order_acquire) != nullptr ||
           g_orig_conn_c1_9val_ii.load(std::memory_order_acquire) != nullptr ||
           g_orig_conn_c1_5nomode.load(std::memory_order_acquire) != nullptr ||
           g_orig_conn_c1_6nomode_attr.load(std::memory_order_acquire) != nullptr ||
           g_orig_conn_c1_6nomode_i.load(std::memory_order_acquire) != nullptr ||
           g_orig_conn_c1_7nomode_attr_i.load(std::memory_order_acquire) != nullptr;
}

// Runtime telemetry counters (published <= 1Hz to kuroko.sensor.diag)
std::atomic<bool>     g_used_jni_plt{false};
std::atomic<bool>     g_used_elf_got{false};
std::atomic<uint32_t> g_matched_core_mask{0};
std::atomic<uint32_t> g_matched_disc_mask{0};
std::atomic<int>      g_core_hits{0};
std::atomic<int>      g_disc_hits{0};
std::atomic<int>      g_maps_count{0};
std::atomic<int>      g_step_handles_count{0};
std::atomic<uint64_t> g_cnt_c1{0};
std::atomic<uint64_t> g_cnt_add{0};
std::atomic<uint64_t> g_cnt_send{0};
std::atomic<uint64_t> g_cnt_inj_ok{0};
std::atomic<uint64_t> g_cnt_inj_err{0};
std::atomic<uint32_t> g_last_uid{0};
std::atomic<uint32_t> g_last_handle{0};
std::atomic<uint64_t> g_last_val{0};
std::atomic<int32_t>  g_last_ret{0};
std::atomic<bool>     g_logged_first_step_add{false};

void record_matched_slots(uint32_t core_mask, uint32_t disc_mask) {
    uint32_t new_core = g_matched_core_mask.fetch_or(core_mask, std::memory_order_relaxed) | core_mask;
    uint32_t new_disc = g_matched_disc_mask.fetch_or(disc_mask, std::memory_order_relaxed) | disc_mask;
    g_core_hits.store(popcount_u32(new_core), std::memory_order_relaxed);
    g_disc_hits.store(popcount_u32(new_disc), std::memory_order_relaxed);
}

uint64_t now_boot_ns() {
    struct timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
}

bool read_fresh_snapshot(KurokoShm* out) {
    if (!g_shm) return false;
    if (!kuroko_shm_read_snapshot(g_shm, out)) return false;
    if (!out->active) return false;
    uint64_t now = now_boot_ns();
    if (now > out->daemon_heartbeat_ns && (now - out->daemon_heartbeat_ns) > kStaleHeartbeatNs) {
        return false;
    }
    return true;
}

void set_java_system_property_jni(JNIEnv* env, const char* key, const char* val) {
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

// Load /system/lib64/libsensorservice.so via Java System.load so libnativeloader places it in
// system_server's default linker namespace (shared with System.loadLibrary("android_servers")).
bool jni_system_load_lib(JNIEnv* env, const char* path) {
    if (!env || !path) return false;
    if (env->ExceptionCheck()) env->ExceptionClear();

    jclass sys_cls = env->FindClass("java/lang/System");
    if (!sys_cls || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (sys_cls) env->DeleteLocalRef(sys_cls);
        return false;
    }
    jmethodID mid = env->GetStaticMethodID(sys_cls, "load", "(Ljava/lang/String;)V");
    if (!mid || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(sys_cls);
        return false;
    }
    jstring jpath = env->NewStringUTF(path);
    bool ok = false;
    if (jpath && !env->ExceptionCheck()) {
        env->CallStaticVoidMethod(sys_cls, mid, jpath);
        if (env->ExceptionCheck()) {
            jthrowable exc = env->ExceptionOccurred();
            env->ExceptionClear();
            if (exc) {
                jclass obj_cls = env->GetObjectClass(exc);
                if (obj_cls && !env->ExceptionCheck()) {
                    jmethodID to_str = env->GetMethodID(obj_cls, "toString", "()Ljava/lang/String;");
                    if (to_str && !env->ExceptionCheck()) {
                        auto jstr = static_cast<jstring>(env->CallObjectMethod(exc, to_str));
                        if (jstr && !env->ExceptionCheck()) {
                            const char* utf = env->GetStringUTFChars(jstr, nullptr);
                            if (utf) {
                                LOGW("System.load(%s) threw: %s", path, utf);
                                env->ReleaseStringUTFChars(jstr, utf);
                            }
                            env->DeleteLocalRef(jstr);
                        }
                    }
                    env->DeleteLocalRef(obj_cls);
                }
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(exc);
            }
        } else {
            ok = true;
        }
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (jpath) env->DeleteLocalRef(jpath);
    env->DeleteLocalRef(sys_cls);
    return ok;
}

void record_handle_type(int32_t handle, int32_t type) {
    if (handle < 0 || type <= 0) return;
    bool newly_logged = false;
    {
        std::unique_lock<std::shared_mutex> lk(g_state_mu);
        auto it = g_handle_to_type.find(handle);
        if (it == g_handle_to_type.end() || it->second != type) {
            g_handle_to_type[handle] = type;
            newly_logged = (type == kSensorTypeStepDetector || type == kSensorTypeStepCounter);
            int step_cnt = 0;
            for (const auto& kv : g_handle_to_type) {
                if (kv.second == kSensorTypeStepDetector || kv.second == kSensorTypeStepCounter) {
                    ++step_cnt;
                }
            }
            g_step_handles_count.store(step_cnt, std::memory_order_relaxed);
        }
    }
    if (newly_logged) {
        LOGI("Mapped step sensor handle=0x%x -> type=%d", handle, type);
    }
}

void ensure_handle_recorded(int32_t handle, int32_t type) {
    if (handle < 0 || type <= 0) return;
    {
        std::shared_lock<std::shared_mutex> lk(g_state_mu);
        auto it = g_handle_to_type.find(handle);
        if (it != g_handle_to_type.end() && it->second == type) return;
    }
    record_handle_type(handle, type);
}

// Tier 1 discovery: pure AOSP SensorList::add + RecentEventLogger::C1 capture all sensors at boot
bool hook_sensor_list_add_6(void* self, int32_t handle, void* sp_iface,
                            bool is_debug, bool is_virtual, int32_t dev_id) {
    auto orig = load_orig_for_slot(g_orig_sensor_list_add_6, SPEC_SENSOR_LIST_ADD_6,
                                   reinterpret_cast<void*>(hook_sensor_list_add_6));
    if (!orig) return false;
    tls_pending_reg_handle = (handle >= 0) ? handle : -1;
    bool ok = orig(self, handle, sp_iface, is_debug, is_virtual, dev_id);
    if (!ok) {
        tls_pending_reg_handle = -1;
    }
    return ok;
}

bool hook_sensor_list_add_5(void* self, int32_t handle, void* sp_iface,
                            bool is_debug, bool is_virtual) {
    auto orig = load_orig_for_slot(g_orig_sensor_list_add_5, SPEC_SENSOR_LIST_ADD_5,
                                   reinterpret_cast<void*>(hook_sensor_list_add_5));
    if (!orig) return false;
    tls_pending_reg_handle = (handle >= 0) ? handle : -1;
    bool ok = orig(self, handle, sp_iface, is_debug, is_virtual);
    if (!ok) {
        tls_pending_reg_handle = -1;
    }
    return ok;
}

bool hook_sensor_list_add_4(void* self, int32_t handle, void* sp_iface, bool is_debug) {
    auto orig = load_orig_for_slot(g_orig_sensor_list_add_4, SPEC_SENSOR_LIST_ADD_4,
                                   reinterpret_cast<void*>(hook_sensor_list_add_4));
    if (!orig) return false;
    tls_pending_reg_handle = (handle >= 0) ? handle : -1;
    bool ok = orig(self, handle, sp_iface, is_debug);
    if (!ok) {
        tls_pending_reg_handle = -1;
    }
    return ok;
}

void* hook_recent_logger_c1(void* self, int32_t sensor_type) {
    auto orig = load_orig_for_slot(g_orig_recent_logger_c1, SPEC_RECENT_LOGGER_C1,
                                   reinterpret_cast<void*>(hook_recent_logger_c1));
    int32_t pending = tls_pending_reg_handle;
    tls_pending_reg_handle = -1;
    if (pending >= 0 && sensor_type > 0) {
        record_handle_type(pending, sensor_type);
    }
    return orig ? orig(self, sensor_type) : self;
}

// Tier 2 discovery: HyperOS enable-time fallback
void hook_save_sensor_usage_item(int32_t type, const void* str8_name, const void* str8_pkg,
                                 bool enable, int32_t arg4, int32_t handle) {
    // Filter out SensorDevice::doActivateHardwareLocked sentinel (arg4 == -1, w5 == 1)
    if (arg4 >= 0 && handle >= 0 && type > 0) {
        record_handle_type(handle, type);
    }
    auto orig = load_orig_for_slot(g_orig_save_sensor_usage_item, SPEC_MI_SAVE_USAGE,
                                   reinterpret_cast<void*>(hook_save_sensor_usage_item));
    if (orig) orig(type, str8_name, str8_pkg, enable, arg4, handle);
}

void register_conn_uid(void* self, uint32_t uid, const char* variant) {
    uint64_t n = g_cnt_c1.fetch_add(1, std::memory_order_relaxed) + 1;
    if (n == 1) {
        LOGI("First hook_conn_c1 (%s) hit: conn=%p uid=%u", variant, self, uid);
    }
    if (self && uid >= 10000u) {
        std::unique_lock<std::shared_mutex> lk(g_state_mu);
        ConnRecord& rec = g_conns[self];
        rec.uid = uid;
        rec.active_handles.clear();
        rec.step_base_initialized = false;
        rec.need_initial_emit.store(true, std::memory_order_relaxed);
        rec.step_counter_base = 0;
        rec.last_vsteps_seen = 0;
        rec.emitted_steps = 0;
    }
}

void* hook_conn_c1_7val(void* self, const void* sp_service, uint32_t uid, int32_t mode,
                        void* str8_pkg, bool has_access, const void* str16_op, const void* str16_attr) {
    auto orig = load_orig_for_slot(g_orig_conn_c1_7val, SPEC_CONN_C1_7VAL,
                                   reinterpret_cast<void*>(hook_conn_c1_7val));
    void* ret = orig ? orig(self, sp_service, uid, mode, str8_pkg, has_access, str16_op, str16_attr) : self;
    register_conn_uid(self, uid, "7val");
    return ret;
}

void* hook_conn_c1_7ref(void* self, const void* sp_service, uint32_t uid, int32_t mode,
                        const void* str8_pkg, bool has_access, const void* str16_op, const void* str16_attr) {
    auto orig = load_orig_for_slot(g_orig_conn_c1_7ref, SPEC_CONN_C1_7REF,
                                   reinterpret_cast<void*>(hook_conn_c1_7ref));
    void* ret = orig ? orig(self, sp_service, uid, mode, str8_pkg, has_access, str16_op, str16_attr) : self;
    register_conn_uid(self, uid, "7ref");
    return ret;
}

void* hook_conn_c1_6val(void* self, const void* sp_service, uint32_t uid, int32_t mode,
                        void* str8_pkg, bool has_access, const void* str16_op) {
    auto orig = load_orig_for_slot(g_orig_conn_c1_6val, SPEC_CONN_C1_6VAL,
                                   reinterpret_cast<void*>(hook_conn_c1_6val));
    void* ret = orig ? orig(self, sp_service, uid, mode, str8_pkg, has_access, str16_op) : self;
    register_conn_uid(self, uid, "6val");
    return ret;
}

void* hook_conn_c1_8val_i(void* self, const void* sp_service, uint32_t uid, int32_t mode,
                          void* str8_pkg, bool has_access, const void* str16_op, const void* str16_attr,
                          int32_t dev_id) {
    auto orig = load_orig_for_slot(g_orig_conn_c1_8val_i, SPEC_CONN_C1_8VAL_I,
                                   reinterpret_cast<void*>(hook_conn_c1_8val_i));
    void* ret = orig ? orig(self, sp_service, uid, mode, str8_pkg, has_access, str16_op, str16_attr, dev_id) : self;
    register_conn_uid(self, uid, "8val_i");
    return ret;
}

void* hook_conn_c1_8ref_i(void* self, const void* sp_service, uint32_t uid, int32_t mode,
                          const void* str8_pkg, bool has_access, const void* str16_op, const void* str16_attr,
                          int32_t dev_id) {
    auto orig = load_orig_for_slot(g_orig_conn_c1_8ref_i, SPEC_CONN_C1_8REF_I,
                                   reinterpret_cast<void*>(hook_conn_c1_8ref_i));
    void* ret = orig ? orig(self, sp_service, uid, mode, str8_pkg, has_access, str16_op, str16_attr, dev_id) : self;
    register_conn_uid(self, uid, "8ref_i");
    return ret;
}

void* hook_conn_c1_9val_ib(void* self, const void* sp_service, uint32_t uid, int32_t mode,
                           void* str8_pkg, bool has_access, const void* str16_op, const void* str16_attr,
                           int32_t a8, bool a9) {
    auto orig = load_orig_for_slot(g_orig_conn_c1_9val_ib, SPEC_CONN_C1_9VAL_IB,
                                   reinterpret_cast<void*>(hook_conn_c1_9val_ib));
    void* ret = orig ? orig(self, sp_service, uid, mode, str8_pkg, has_access, str16_op, str16_attr, a8, a9) : self;
    register_conn_uid(self, uid, "9val_ib");
    return ret;
}

void* hook_conn_c1_9val_ii(void* self, const void* sp_service, uint32_t uid, int32_t mode,
                           void* str8_pkg, bool has_access, const void* str16_op, const void* str16_attr,
                           int32_t a8, int32_t a9) {
    auto orig = load_orig_for_slot(g_orig_conn_c1_9val_ii, SPEC_CONN_C1_9VAL_II,
                                   reinterpret_cast<void*>(hook_conn_c1_9val_ii));
    void* ret = orig ? orig(self, sp_service, uid, mode, str8_pkg, has_access, str16_op, str16_attr, a8, a9) : self;
    register_conn_uid(self, uid, "9val_ii");
    return ret;
}

void* hook_conn_c1_5nomode(void* self, const void* sp_service, uint32_t uid,
                           void* str8_pkg, bool has_access, const void* str16_op) {
    auto orig = load_orig_for_slot(g_orig_conn_c1_5nomode, SPEC_CONN_C1_5NOMODE,
                                   reinterpret_cast<void*>(hook_conn_c1_5nomode));
    void* ret = orig ? orig(self, sp_service, uid, str8_pkg, has_access, str16_op) : self;
    register_conn_uid(self, uid, "5nomode");
    return ret;
}

void* hook_conn_c1_6nomode_attr(void* self, const void* sp_service, uint32_t uid,
                                void* str8_pkg, bool has_access, const void* str16_op, const void* str16_attr) {
    auto orig = load_orig_for_slot(g_orig_conn_c1_6nomode_attr, SPEC_CONN_C1_6NOMODE_ATTR,
                                   reinterpret_cast<void*>(hook_conn_c1_6nomode_attr));
    void* ret = orig ? orig(self, sp_service, uid, str8_pkg, has_access, str16_op, str16_attr) : self;
    register_conn_uid(self, uid, "6nomode_attr");
    return ret;
}

void* hook_conn_c1_6nomode_i(void* self, const void* sp_service, uint32_t uid,
                             void* str8_pkg, bool has_access, const void* str16_op, int32_t dev_id) {
    auto orig = load_orig_for_slot(g_orig_conn_c1_6nomode_i, SPEC_CONN_C1_6NOMODE_I,
                                   reinterpret_cast<void*>(hook_conn_c1_6nomode_i));
    void* ret = orig ? orig(self, sp_service, uid, str8_pkg, has_access, str16_op, dev_id) : self;
    register_conn_uid(self, uid, "6nomode_i");
    return ret;
}

void* hook_conn_c1_7nomode_attr_i(void* self, const void* sp_service, uint32_t uid,
                                  void* str8_pkg, bool has_access, const void* str16_op, const void* str16_attr,
                                  int32_t dev_id) {
    auto orig = load_orig_for_slot(g_orig_conn_c1_7nomode_attr_i, SPEC_CONN_C1_7NOMODE_ATTR_I,
                                   reinterpret_cast<void*>(hook_conn_c1_7nomode_attr_i));
    void* ret = orig ? orig(self, sp_service, uid, str8_pkg, has_access, str16_op, str16_attr, dev_id) : self;
    register_conn_uid(self, uid, "7nomode_attr_i");
    return ret;
}

bool hook_add_sensor(void* self, int32_t handle) {
    auto orig = load_orig_for_slot(g_orig_add_sensor, SPEC_ADD_SENSOR,
                                   reinterpret_cast<void*>(hook_add_sensor));
    if (!orig) return false;
    bool ok = orig(self, handle);
    g_cnt_add.fetch_add(1, std::memory_order_relaxed);
    if (ok && self) {
        bool log_first_step = false;
        uint32_t logged_uid = 0;
        int32_t logged_type = 0;
        {
            std::unique_lock<std::shared_mutex> lk(g_state_mu);
            auto it = g_conns.find(self);
            if (it != g_conns.end()) {
                auto res = it->second.active_handles.insert(handle);
                auto tit = g_handle_to_type.find(handle);
                int32_t htype = (tit != g_handle_to_type.end()) ? tit->second : 0;
                bool is_step_counter = (htype == kSensorTypeStepCounter);
                bool is_step = (htype == kSensorTypeStepCounter || htype == kSensorTypeStepDetector);
                if (res.second || is_step_counter) {
                    // Force immediate initial ON_CHANGE step counter packet (covers re-runs on reused connection)
                    it->second.need_initial_emit.store(true, std::memory_order_relaxed);
                }
                if (is_step && !g_logged_first_step_add.exchange(true, std::memory_order_relaxed)) {
                    log_first_step = true;
                    logged_uid = it->second.uid;
                    logged_type = htype;
                }
            }
        }
        if (log_first_step) {
            LOGI("First step handle addSensor: conn=%p uid=%u handle=0x%x type=%d",
                 self, logged_uid, handle, logged_type);
        }
    }
    return ok;
}

bool hook_remove_sensor(void* self, int32_t handle) {
    auto orig = load_orig_for_slot(g_orig_remove_sensor, SPEC_REMOVE_SENSOR,
                                   reinterpret_cast<void*>(hook_remove_sensor));
    if (!orig) return false;
    bool ok = orig(self, handle);
    if (self) {
        std::unique_lock<std::shared_mutex> lk(g_state_mu);
        auto it = g_conns.find(self);
        if (it != g_conns.end()) {
            it->second.active_handles.erase(handle);
        }
    }
    return ok;
}

void hook_cleanup_conn(void* service, void* conn) {
    if (conn) {
        // Exclusive lock guarantees StepInjectorThread has finished any in-flight call on this conn
        std::unique_lock<std::shared_mutex> lk(g_state_mu);
        g_conns.erase(conn);
    }
    auto orig = load_orig_for_slot(g_orig_cleanup_conn, SPEC_CLEANUP_CONN,
                                   reinterpret_cast<void*>(hook_cleanup_conn));
    if (orig) orig(service, conn);
}

bool rewrite_imu_event(SensorsEvent& ev, const KurokoShm& snap) {
    switch (ev.type) {
        case kSensorTypeAccel:
            ev.data[0] = snap.accel[0];
            ev.data[1] = snap.accel[1];
            ev.data[2] = snap.accel[2];
            return true;
        case kSensorTypeLinearAccel:
            ev.data[0] = snap.linear_accel[0];
            ev.data[1] = snap.linear_accel[1];
            ev.data[2] = snap.linear_accel[2];
            return true;
        case kSensorTypeGravity:
            ev.data[0] = snap.gravity[0];
            ev.data[1] = snap.gravity[1];
            ev.data[2] = snap.gravity[2];
            return true;
        case kSensorTypeGyro:
            ev.data[0] = snap.gyro[0];
            ev.data[1] = snap.gyro[1];
            ev.data[2] = snap.gyro[2];
            return true;
        case kSensorTypeOrientation: {
            float az = std::fmod(snap.bearing_deg, 360.0f);
            if (az < 0.0f) az += 360.0f;
            float gx = snap.gravity[0];
            float gy = snap.gravity[1];
            float gz = snap.gravity[2];
            float pitch_deg = std::atan2(-gy, gz) * kRadToDegF;
            float roll_deg  = std::atan2(-gx, std::sqrt(gy * gy + gz * gz)) * kRadToDegF;
            ev.data[0] = az;
            ev.data[1] = pitch_deg;
            ev.data[2] = roll_deg;
            return true;
        }
        case kSensorTypeRotVec:
        case kSensorTypeGameRotVec:
            ev.data[0] = snap.rot_vec[0];
            ev.data[1] = snap.rot_vec[1];
            ev.data[2] = snap.rot_vec[2];
            ev.data[3] = snap.rot_vec[3];
            ev.data[4] = 0.05f; // estimated heading accuracy rad
            return true;
        case kSensorTypeMag:
            ev.data[0] = snap.mag[0];
            ev.data[1] = snap.mag[1];
            ev.data[2] = snap.mag[2];
            return true;
        case kSensorTypePressure:
            ev.data[0] = snap.pressure_hpa;
            return true;
        default:
            return false;
    }
}

int32_t hook_send_events(void* self, const SensorsEvent* buffer, size_t count,
                         SensorsEvent* scratch, const void* map_flush) {
    auto orig = load_orig_for_slot(g_orig_send_events, SPEC_SEND_EVENTS,
                                   reinterpret_cast<void*>(hook_send_events));
    if (!orig) return 0;
    g_cnt_send.fetch_add(1, std::memory_order_relaxed);
    if (!self || !buffer || count == 0) {
        return orig(self, buffer, count, scratch, map_flush);
    }

    // Tier 3 passive fallback discovery + real HW step counter baseline tracking
    for (size_t i = 0; i < count; ++i) {
        if (buffer[i].sensor >= 0 && buffer[i].type > 0) {
            ensure_handle_recorded(buffer[i].sensor, buffer[i].type);
        }
        if (buffer[i].type == kSensorTypeStepCounter) {
            uint64_t hw = buffer[i].u64_data[0];
            uint64_t prev = g_last_hw_steps.load(std::memory_order_relaxed);
            if (hw > prev) g_last_hw_steps.store(hw, std::memory_order_relaxed);
        }
    }

    // Fast path: check if spoofing is active and this connection belongs to a target UID
    KurokoShm snap{};
    if (!read_fresh_snapshot(&snap)) {
        return orig(self, buffer, count, scratch, map_flush);
    }

    uint32_t uid = 0;
    {
        std::shared_lock<std::shared_mutex> lk(g_state_mu);
        auto it = g_conns.find(self);
        if (it == g_conns.end()) {
            return orig(self, buffer, count, scratch, map_flush);
        }
        uid = it->second.uid;
    }

    uint32_t policy = kuroko_find_target_policy(&snap, uid);
    if ((policy & (KUROKO_POLICY_STEPS | KUROKO_POLICY_IMU)) == 0) {
        return orig(self, buffer, count, scratch, map_flush);
    }

    // NEVER mutate shared `buffer` in-place (SensorService reuses it across all clients!)
    SensorsEvent stack_copy[64];
    std::vector<SensorsEvent> heap_copy;
    SensorsEvent* out_buf = stack_copy;
    if (count > 64) {
        heap_copy.resize(count);
        out_buf = heap_copy.data();
    }

    size_t out_count = 0;
    const bool spoof_steps = (policy & KUROKO_POLICY_STEPS) != 0 &&
                             (snap.global_flags & KUROKO_FLAG_STEP_SPOOF) != 0;
    const bool spoof_imu   = (policy & KUROKO_POLICY_IMU) != 0 &&
                             (snap.global_flags & KUROKO_FLAG_IMU_SPOOF) != 0;

    for (size_t i = 0; i < count; ++i) {
        SensorsEvent ev = buffer[i];
        // Suppress real HW step events while virtual steps are active so counter never jumps/doubles
        if (spoof_steps && (ev.type == kSensorTypeStepCounter ||
                            ev.type == kSensorTypeStepDetector ||
                            ev.type == kSensorTypeSigMotion)) {
            continue;
        }
        if (spoof_imu) {
            rewrite_imu_event(ev, snap);
        }
        out_buf[out_count++] = ev;
    }

    if (out_count == 0) return 0;
    return orig(self, out_buf, out_count, scratch, map_flush);
}

struct HookSpec {
    const char* symbol;
    void* hook_fn;
    HookSlot slot;
    const char* label;
    void (*store_orig)(void*);
};

// Multi-version Android 12-17 candidate symbol table (verified with llvm-cxxfilt; exact-arity wrappers per variant)
const HookSpec kHookSpecs[] = {
    // --- Core slot 0: CONN_C1 (11 exact-arity Android 12-17 + OEM variants) ---
    {
        "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjiNS_7String8EbRKNS_8String16ES9_",
        reinterpret_cast<void*>(hook_conn_c1_7val),
        HookSlot::CONN_C1,
        "conn_c1:7val",
        [](void* p) { g_orig_conn_c1_7val.store(reinterpret_cast<FnConnC1_7Val>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjiRKNS_7String8EbRKNS_8String16ESB_",
        reinterpret_cast<void*>(hook_conn_c1_7ref),
        HookSlot::CONN_C1,
        "conn_c1:7ref",
        [](void* p) { g_orig_conn_c1_7ref.store(reinterpret_cast<FnConnC1_7Ref>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjiNS_7String8EbRKNS_8String16E",
        reinterpret_cast<void*>(hook_conn_c1_6val),
        HookSlot::CONN_C1,
        "conn_c1:6val",
        [](void* p) { g_orig_conn_c1_6val.store(reinterpret_cast<FnConnC1_6Val>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjiNS_7String8EbRKNS_8String16ES9_i",
        reinterpret_cast<void*>(hook_conn_c1_8val_i),
        HookSlot::CONN_C1,
        "conn_c1:8val_i",
        [](void* p) { g_orig_conn_c1_8val_i.store(reinterpret_cast<FnConnC1_8ValI>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjiRKNS_7String8EbRKNS_8String16ESB_i",
        reinterpret_cast<void*>(hook_conn_c1_8ref_i),
        HookSlot::CONN_C1,
        "conn_c1:8ref_i",
        [](void* p) { g_orig_conn_c1_8ref_i.store(reinterpret_cast<FnConnC1_8RefI>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjiNS_7String8EbRKNS_8String16ES9_ib",
        reinterpret_cast<void*>(hook_conn_c1_9val_ib),
        HookSlot::CONN_C1,
        "conn_c1:9val_ib",
        [](void* p) { g_orig_conn_c1_9val_ib.store(reinterpret_cast<FnConnC1_9ValIB>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjiNS_7String8EbRKNS_8String16ES9_ii",
        reinterpret_cast<void*>(hook_conn_c1_9val_ii),
        HookSlot::CONN_C1,
        "conn_c1:9val_ii",
        [](void* p) { g_orig_conn_c1_9val_ii.store(reinterpret_cast<FnConnC1_9ValII>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjNS_7String8EbRKNS_8String16E",
        reinterpret_cast<void*>(hook_conn_c1_5nomode),
        HookSlot::CONN_C1,
        "conn_c1:5nomode",
        [](void* p) { g_orig_conn_c1_5nomode.store(reinterpret_cast<FnConnC1_5NoMode>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjNS_7String8EbRKNS_8String16ES9_",
        reinterpret_cast<void*>(hook_conn_c1_6nomode_attr),
        HookSlot::CONN_C1,
        "conn_c1:6nomode_attr",
        [](void* p) { g_orig_conn_c1_6nomode_attr.store(reinterpret_cast<FnConnC1_6NoModeAttr>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjNS_7String8EbRKNS_8String16Ei",
        reinterpret_cast<void*>(hook_conn_c1_6nomode_i),
        HookSlot::CONN_C1,
        "conn_c1:6nomode_i",
        [](void* p) { g_orig_conn_c1_6nomode_i.store(reinterpret_cast<FnConnC1_6NoModeI>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjNS_7String8EbRKNS_8String16ES9_i",
        reinterpret_cast<void*>(hook_conn_c1_7nomode_attr_i),
        HookSlot::CONN_C1,
        "conn_c1:7nomode_attr_i",
        [](void* p) { g_orig_conn_c1_7nomode_attr_i.store(reinterpret_cast<FnConnC1_7NoModeAttrI>(p), std::memory_order_release); },
    },
    // --- Core slots 1..4 ---
    {
        "_ZN7android13SensorService21SensorEventConnection9addSensorEi",
        reinterpret_cast<void*>(hook_add_sensor),
        HookSlot::ADD_SENSOR,
        "addSensor",
        [](void* p) { g_orig_add_sensor.store(reinterpret_cast<FnAddRemoveSensor>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService21SensorEventConnection12removeSensorEi",
        reinterpret_cast<void*>(hook_remove_sensor),
        HookSlot::REMOVE_SENSOR,
        "removeSensor",
        [](void* p) { g_orig_remove_sensor.store(reinterpret_cast<FnAddRemoveSensor>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService21SensorEventConnection10sendEventsEPK15sensors_event_tmPS2_PKNS_2wpIKS1_EE",
        reinterpret_cast<void*>(hook_send_events),
        HookSlot::SEND_EVENTS,
        "sendEvents",
        [](void* p) { g_orig_send_events.store(reinterpret_cast<FnSendEvents>(p), std::memory_order_release); },
    },
    {
        "_ZN7android13SensorService17cleanupConnectionEPNS0_21SensorEventConnectionE",
        reinterpret_cast<void*>(hook_cleanup_conn),
        HookSlot::CLEANUP_CONN,
        "cleanupConnection",
        [](void* p) { g_orig_cleanup_conn.store(reinterpret_cast<FnCleanupConn>(p), std::memory_order_release); },
    },
    // --- Discovery slot 5: SENSOR_LIST_ADD (3 exact-arity Android 12-17 variants) ---
    {
        "_ZN7android17SensorServiceUtil10SensorList3addEiNSt3__110shared_ptrINS_15SensorInterfaceEEEbbi",
        reinterpret_cast<void*>(hook_sensor_list_add_6),
        HookSlot::SENSOR_LIST_ADD,
        "sensorListAdd:6",
        [](void* p) { g_orig_sensor_list_add_6.store(reinterpret_cast<FnSensorListAdd6>(p), std::memory_order_release); },
    },
    {
        "_ZN7android17SensorServiceUtil10SensorList3addEiNSt3__110shared_ptrINS_15SensorInterfaceEEEbb",
        reinterpret_cast<void*>(hook_sensor_list_add_5),
        HookSlot::SENSOR_LIST_ADD,
        "sensorListAdd:5",
        [](void* p) { g_orig_sensor_list_add_5.store(reinterpret_cast<FnSensorListAdd5>(p), std::memory_order_release); },
    },
    {
        "_ZN7android17SensorServiceUtil10SensorList3addEiNSt3__110shared_ptrINS_15SensorInterfaceEEEb",
        reinterpret_cast<void*>(hook_sensor_list_add_4),
        HookSlot::SENSOR_LIST_ADD,
        "sensorListAdd:4",
        [](void* p) { g_orig_sensor_list_add_4.store(reinterpret_cast<FnSensorListAdd4>(p), std::memory_order_release); },
    },
    // --- Discovery slots 6..7 ---
    {
        "_ZN7android17SensorServiceUtil17RecentEventLoggerC1Ei",
        reinterpret_cast<void*>(hook_recent_logger_c1),
        HookSlot::RECENT_LOGGER_C1,
        "recentLoggerC1",
        [](void* p) { g_orig_recent_logger_c1.store(reinterpret_cast<FnRecentLoggerC1>(p), std::memory_order_release); },
    },
    {
        "_ZN19MiSensorServiceStub19SaveSensorUsageItemEiRKN7android7String8ES3_bii",
        reinterpret_cast<void*>(hook_save_sensor_usage_item),
        HookSlot::MI_SAVE_USAGE,
        "miSaveUsage",
        [](void* p) { g_orig_save_sensor_usage_item.store(reinterpret_cast<FnSaveSensorUsageItem>(p), std::memory_order_release); },
    },
};

constexpr size_t kNumHookSpecs = sizeof(kHookSpecs) / sizeof(kHookSpecs[0]);
static_assert(kNumHookSpecs == SPEC_COUNT, "kHookSpecs must stay aligned 1:1 with SpecIdx / g_plt_orig_slots");

bool ends_with(const char* str, const char* suffix) {
    size_t slen = std::strlen(str);
    size_t suflen = std::strlen(suffix);
    if (slen < suflen) return false;
    return std::memcmp(str + (slen - suflen), suffix, suflen) == 0;
}

// Scan /proc/self/maps for private readable ELF base mappings (offset == 0, perms == r--p/r*p,
// strictly excluding lsplt r--s shared backups) matching /libsensorservice.so
std::vector<MappedElf> scan_sensorservice_maps() {
    std::vector<MappedElf> result;
    FILE* fp = std::fopen("/proc/self/maps", "r");
    if (!fp) return result;

    char line[768];
    while (std::fgets(line, sizeof(line), fp)) {
        if (std::strstr(line, kLibSensorServiceSuffix) == nullptr) continue;
        unsigned long start = 0, end = 0, offset = 0;
        unsigned int maj = 0, min = 0;
        unsigned long long ino = 0;
        char perms[8]{};
        char path[512]{};
        int n = std::sscanf(line, "%lx-%lx %7s %lx %x:%x %llu %511[^\n]",
                            &start, &end, perms, &offset, &maj, &min, &ino, path);
        if (n < 8 || offset != 0 || ino == 0) continue;
        if (perms[0] != 'r' || perms[3] != 'p') continue;

        size_t plen = std::strlen(path);
        while (plen > 0 && (path[plen - 1] == '\r' || path[plen - 1] == '\n' || path[plen - 1] == ' ')) {
            path[--plen] = '\0';
        }
        if (!ends_with(path, kLibSensorServiceSuffix)) continue;

        MappedElf me;
        me.base = static_cast<uintptr_t>(start);
        me.dev = makedev(maj, min);
        me.ino = static_cast<ino_t>(ino);
        result.push_back(me);
    }
    std::fclose(fp);
    return result;
}

// Snapshot all readable private VMAs (perms[0] == 'r' && perms[3] == 'p') for safe ELF/GOT validation
std::vector<VmaRange> snapshot_readable_private_vmas() {
    std::vector<VmaRange> vmas;
    FILE* fp = std::fopen("/proc/self/maps", "r");
    if (!fp) return vmas;

    char line[512];
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

struct PatchResult {
    uint32_t core_mask = 0;
    uint32_t disc_mask = 0;
    bool had_unresolved_slot = false;
    bool abi_unsupported = false;
    int core_hits() const { return popcount_u32(core_mask); }
    int disc_hits() const { return popcount_u32(disc_mask); }
};

struct MatchedPltEntry {
    size_t spec_idx = 0;
    uintptr_t slot_addr = 0;
    int orig_prot = 0;
};

struct ElfPltView {
    uintptr_t load_bias = 0;
    uintptr_t symtab_addr = 0;
    const Elf64_Sym* symtab = nullptr;
    const char* strtab = nullptr;
    size_t strsz = 0;
    const Elf64_Rela* relas = nullptr;
    size_t rela_count = 0;
    bool valid = false;
};

ElfPltView parse_elf_plt_view(uintptr_t base, const std::vector<VmaRange>& vmas) {
    ElfPltView view{};
    if (!find_vma(vmas, base, sizeof(Elf64_Ehdr))) {
        return view;
    }

    const auto* ehdr = reinterpret_cast<const Elf64_Ehdr*>(base);
    if (std::memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
        ehdr->e_ident[EI_CLASS] != ELFCLASS64 ||
        ehdr->e_machine != EM_AARCH64 ||
        ehdr->e_phoff == 0 || ehdr->e_phnum == 0 ||
        ehdr->e_phentsize != sizeof(Elf64_Phdr)) {
        return view;
    }

    uintptr_t phdr_addr = base + ehdr->e_phoff;
    size_t phdr_bytes = static_cast<size_t>(ehdr->e_phnum) * sizeof(Elf64_Phdr);
    if (!find_vma(vmas, phdr_addr, phdr_bytes)) {
        return view;
    }

    const auto* phdrs = reinterpret_cast<const Elf64_Phdr*>(phdr_addr);
    uintptr_t min_load_vaddr = UINTPTR_MAX;
    uintptr_t dyn_vaddr = 0;
    size_t dyn_memsz = 0;

    for (uint16_t i = 0; i < ehdr->e_phnum; ++i) {
        if (phdrs[i].p_type == PT_LOAD) {
            if (phdrs[i].p_vaddr < min_load_vaddr) {
                min_load_vaddr = phdrs[i].p_vaddr;
            }
        } else if (phdrs[i].p_type == PT_DYNAMIC) {
            dyn_vaddr = phdrs[i].p_vaddr;
            dyn_memsz = phdrs[i].p_memsz;
        }
    }
    if (min_load_vaddr == UINTPTR_MAX || dyn_vaddr == 0 || dyn_memsz < sizeof(Elf64_Dyn)) {
        return view;
    }

    uintptr_t load_bias = base - min_load_vaddr;
    uintptr_t dyn_addr = load_bias + dyn_vaddr;
    if (!find_vma(vmas, dyn_addr, dyn_memsz)) {
        return view;
    }

    uintptr_t raw_symtab = 0;
    uintptr_t raw_strtab = 0;
    size_t strsz = 0;
    uintptr_t raw_jmprel = 0;
    size_t pltrelsz = 0;

    const auto* dyn = reinterpret_cast<const Elf64_Dyn*>(dyn_addr);
    size_t dyn_count = dyn_memsz / sizeof(Elf64_Dyn);
    for (size_t i = 0; i < dyn_count && dyn[i].d_tag != DT_NULL; ++i) {
        switch (dyn[i].d_tag) {
            case DT_SYMTAB:   raw_symtab = static_cast<uintptr_t>(dyn[i].d_un.d_ptr); break;
            case DT_STRTAB:   raw_strtab = static_cast<uintptr_t>(dyn[i].d_un.d_ptr); break;
            case DT_STRSZ:    strsz      = static_cast<size_t>(dyn[i].d_un.d_val); break;
            case DT_JMPREL:   raw_jmprel = static_cast<uintptr_t>(dyn[i].d_un.d_ptr); break;
            case DT_PLTRELSZ: pltrelsz   = static_cast<size_t>(dyn[i].d_un.d_val); break;
            default: break;
        }
    }

    auto resolve_dyn_ptr = [&](uintptr_t p) -> uintptr_t {
        if (p == 0) return 0;
        return (p >= base) ? p : (load_bias + p);
    };

    uintptr_t symtab_addr = resolve_dyn_ptr(raw_symtab);
    uintptr_t strtab_addr = resolve_dyn_ptr(raw_strtab);
    uintptr_t jmprel_addr = resolve_dyn_ptr(raw_jmprel);
    if (symtab_addr == 0 || strtab_addr == 0 || strsz == 0 || jmprel_addr == 0 || pltrelsz < sizeof(Elf64_Rela)) {
        return view;
    }
    if (!find_vma(vmas, jmprel_addr, pltrelsz) ||
        !find_vma(vmas, symtab_addr, sizeof(Elf64_Sym)) ||
        !find_vma(vmas, strtab_addr, strsz)) {
        return view;
    }

    const char* strtab = reinterpret_cast<const char*>(strtab_addr);
    if (strtab[strsz - 1] != '\0') {
        return view;
    }

    view.load_bias = load_bias;
    view.symtab_addr = symtab_addr;
    view.symtab = reinterpret_cast<const Elf64_Sym*>(symtab_addr);
    view.strtab = strtab;
    view.strsz = strsz;
    view.relas = reinterpret_cast<const Elf64_Rela*>(jmprel_addr);
    view.rela_count = pltrelsz / sizeof(Elf64_Rela);
    view.valid = true;
    return view;
}

// Log candidate .rela.plt symbols once per ELF base when CONN_C1 or SENSOR_LIST_ADD is unmatched (unknown OEM ROM aid)
void log_unmatched_oem_symbols_once(uintptr_t base, const ElfPltView& view, const std::vector<VmaRange>& vmas,
                                    uint32_t core_mask, uint32_t disc_mask) {
    const bool miss_c1 = (core_mask & slot_bit(HookSlot::CONN_C1)) == 0;
    const bool miss_list = (disc_mask & slot_bit(HookSlot::SENSOR_LIST_ADD)) == 0;
    if (!miss_c1 && !miss_list) return;
    if (!view.valid) return;

    {
        std::lock_guard<std::mutex> plk(g_patch_mu);
        if (!g_diag_logged_bases.insert(base).second) {
            return;
        }
    }

    int logged = 0;
    constexpr int kMaxDiagLines = 6;
    for (size_t i = 0; i < view.rela_count && logged < kMaxDiagLines; ++i) {
        if (ELF64_R_TYPE(view.relas[i].r_info) != R_AARCH64_JUMP_SLOT) continue;
        uint32_t sym_idx = ELF64_R_SYM(view.relas[i].r_info);
        uintptr_t sym_entry_addr = view.symtab_addr + static_cast<uintptr_t>(sym_idx) * sizeof(Elf64_Sym);
        if (!find_vma(vmas, sym_entry_addr, sizeof(Elf64_Sym))) continue;

        uint32_t st_name = view.symtab[sym_idx].st_name;
        if (st_name >= view.strsz) continue;
        const char* sym_name = view.strtab + st_name;

        bool match_c1 = miss_c1 && (std::strstr(sym_name, "SensorEventConnectionC") != nullptr);
        bool match_list = miss_list && (std::strstr(sym_name, "SensorList3add") != nullptr);
        if (match_c1 || match_list) {
            LOGW("Unmatched OEM candidate in .rela.plt (base=0x%lx, miss_c1=%d, miss_list=%d): %s",
                 static_cast<unsigned long>(base), miss_c1 ? 1 : 0, miss_list ? 1 : 0, sym_name);
            ++logged;
        }
    }
    if (logged == 0) {
        LOGW("Unmatched slot(s) at base=0x%lx (miss_c1=%d, miss_list=%d): no matching .rela.plt candidates found",
             static_cast<unsigned long>(base), miss_c1 ? 1 : 0, miss_list ? 1 : 0);
    }
}

// Pre-filter .rela.plt for exact kHookSpecs symbols present in this ELF, max 1 entry per HookSlot (<= 8 total)
void scan_matched_plt_entries(const ElfPltView& view, const std::vector<VmaRange>& vmas,
                              std::vector<MatchedPltEntry>* out_entries,
                              uint32_t* out_core_mask, uint32_t* out_disc_mask) {
    out_entries->clear();
    *out_core_mask = 0;
    *out_disc_mask = 0;
    if (!view.valid) return;

    uint32_t seen_core = 0;
    uint32_t seen_disc = 0;
    for (size_t i = 0; i < view.rela_count; ++i) {
        if (ELF64_R_TYPE(view.relas[i].r_info) != R_AARCH64_JUMP_SLOT) continue;
        uint32_t sym_idx = ELF64_R_SYM(view.relas[i].r_info);
        uintptr_t sym_entry_addr = view.symtab_addr + static_cast<uintptr_t>(sym_idx) * sizeof(Elf64_Sym);
        if (!find_vma(vmas, sym_entry_addr, sizeof(Elf64_Sym))) continue;

        uint32_t st_name = view.symtab[sym_idx].st_name;
        if (st_name >= view.strsz) continue;
        const char* sym_name = view.strtab + st_name;

        for (size_t s = 0; s < kNumHookSpecs; ++s) {
            if (std::strcmp(sym_name, kHookSpecs[s].symbol) != 0) continue;

            const bool core = is_core_slot(kHookSpecs[s].slot);
            const uint32_t bit = slot_bit(kHookSpecs[s].slot);
            if ((core ? (seen_core & bit) : (seen_disc & bit)) != 0) {
                break; // deduplicated: at most 1 symbol per HookSlot
            }

            uintptr_t slot_addr = view.load_bias + static_cast<uintptr_t>(view.relas[i].r_offset);
            int orig_prot = 0;
            if (!find_vma(vmas, slot_addr, sizeof(void*), &orig_prot)) {
                LOGW("GOT slot %p for %s not in readable private VMA; skipping",
                     reinterpret_cast<void*>(slot_addr), kHookSpecs[s].symbol);
                break;
            }

            out_entries->push_back(MatchedPltEntry{s, slot_addr, orig_prot});
            if (core) {
                seen_core |= bit;
            } else {
                seen_disc |= bit;
            }
            break;
        }
    }
    *out_core_mask = seen_core;
    *out_disc_mask = seen_disc;
}

// Pure C++ ARM64 ELF64 .rela.plt GOT patcher for self-healing on any newly-loaded libsensorservice.so
PatchResult patch_elf_got_at_base(uintptr_t base) {
    PatchResult pr{};
    auto vmas = snapshot_readable_private_vmas();
    ElfPltView view = parse_elf_plt_view(base, vmas);
    if (!view.valid) {
        return pr;
    }

    std::vector<MatchedPltEntry> matched;
    uint32_t avail_core = 0;
    uint32_t avail_disc = 0;
    scan_matched_plt_entries(view, vmas, &matched, &avail_core, &avail_disc);

    // Pre-flight core gate (Stage A): require both CONN_C1 and SEND_EVENTS present (or already stored)
    const bool has_c1_sym = ((avail_core & slot_bit(HookSlot::CONN_C1)) != 0) || has_any_conn_c1_orig();
    const bool has_send_sym =
        ((avail_core & slot_bit(HookSlot::SEND_EVENTS)) != 0) ||
        (load_orig_for_slot(g_orig_send_events, SPEC_SEND_EVENTS,
                            reinterpret_cast<void*>(hook_send_events)) != nullptr);
    if (!has_c1_sym || !has_send_sym) {
        pr.abi_unsupported = true;
        log_unmatched_oem_symbols_once(base, view, vmas, avail_core, avail_disc);
        return pr;
    }

    // Pre-flight relocation readiness check (Stage B): never partially patch if core relocations aren't resolved yet
    struct ReadyEntry {
        MatchedPltEntry entry;
        void* old_val = nullptr;
        bool already_hooked = false;
    };
    std::vector<ReadyEntry> ready;
    ready.reserve(matched.size());

    uint32_t ready_core = 0;
    for (const auto& e : matched) {
        const auto& spec = kHookSpecs[e.spec_idx];
        auto** slot = reinterpret_cast<void**>(e.slot_addr);
        void* old_val = __atomic_load_n(slot, __ATOMIC_ACQUIRE);

        if (old_val == spec.hook_fn) {
            // Already patched: still recover orig from g_plt_orig_slots[] if needed
            void* saved_plt = __atomic_load_n(&g_plt_orig_slots[e.spec_idx], __ATOMIC_ACQUIRE);
            if (saved_plt && saved_plt != spec.hook_fn) {
                spec.store_orig(saved_plt);
            }
            ready.push_back(ReadyEntry{e, old_val, true});
            if (is_core_slot(spec.slot)) {
                ready_core |= slot_bit(spec.slot);
            }
            continue;
        }

        int tgt_prot = 0;
        if (!old_val ||
            !find_vma(vmas, reinterpret_cast<uintptr_t>(old_val), 4, &tgt_prot) ||
            (tgt_prot & PROT_EXEC) == 0) {
            // Linker relocation not finished yet (null or unrelocated .plt stub vaddr); retry next tick
            pr.had_unresolved_slot = true;
            continue;
        }

        ready.push_back(ReadyEntry{e, old_val, false});
        if (is_core_slot(spec.slot)) {
            ready_core |= slot_bit(spec.slot);
        }
    }

    const bool c1_ready = ((ready_core & slot_bit(HookSlot::CONN_C1)) != 0) || has_any_conn_c1_orig();
    const bool send_ready =
        ((ready_core & slot_bit(HookSlot::SEND_EVENTS)) != 0) ||
        (load_orig_for_slot(g_orig_send_events, SPEC_SEND_EVENTS,
                            reinterpret_cast<void*>(hook_send_events)) != nullptr);
    if (!c1_ready || !send_ready) {
        pr.had_unresolved_slot = true;
        return pr;
    }

    long page_size_l = sysconf(_SC_PAGESIZE);
    size_t page_size = (page_size_l > 0) ? static_cast<size_t>(page_size_l) : 4096u;

    for (const auto& r : ready) {
        const auto& e = r.entry;
        const auto& spec = kHookSpecs[e.spec_idx];

        if (r.already_hooked) {
            if (is_core_slot(spec.slot)) {
                pr.core_mask |= slot_bit(spec.slot);
            } else {
                pr.disc_mask |= slot_bit(spec.slot);
            }
            continue;
        }

        uintptr_t page_start = e.slot_addr & ~static_cast<uintptr_t>(page_size - 1);
        size_t prot_len = ((e.slot_addr + sizeof(void*)) > (page_start + page_size))
                          ? (page_size * 2) : page_size;

        bool changed_prot = false;
        if ((e.orig_prot & PROT_WRITE) == 0) {
            if (mprotect(reinterpret_cast<void*>(page_start), prot_len, e.orig_prot | PROT_WRITE) != 0) {
                LOGW("mprotect RW failed for %s at %p: %s",
                     spec.symbol, reinterpret_cast<void*>(page_start), strerror(errno));
                continue;
            }
            changed_prot = true;
        }

        // Store real original pointer into BOTH static slot and atomic orig BEFORE swapping GOT slot
        __atomic_store_n(&g_plt_orig_slots[e.spec_idx], r.old_val, __ATOMIC_RELEASE);
        spec.store_orig(r.old_val);
        auto** slot = reinterpret_cast<void**>(e.slot_addr);
        __atomic_store_n(slot, spec.hook_fn, __ATOMIC_RELEASE);

        if (changed_prot) {
            mprotect(reinterpret_cast<void*>(page_start), prot_len, e.orig_prot);
        }

        if (is_core_slot(spec.slot)) {
            pr.core_mask |= slot_bit(spec.slot);
        } else {
            pr.disc_mask |= slot_bit(spec.slot);
        }
        LOGI("elf_got matched [%s]: %s -> orig=%p", spec.label, spec.symbol, r.old_val);
    }

    if (!pr.had_unresolved_slot) {
        log_unmatched_oem_symbols_once(base, view, vmas, pr.core_mask, pr.disc_mask);
    }
    return pr;
}

std::string build_diag_string() {
    bool used_jni = g_used_jni_plt.load(std::memory_order_relaxed);
    bool used_got = g_used_elf_got.load(std::memory_order_relaxed);
    const char* mode = (used_jni && used_got) ? "both"
                     : (used_jni ? "jni_plt"
                     : (used_got ? "elf_got" : "wait"));
    char buf[256];
    std::snprintf(
        buf, sizeof(buf),
        "mv=%d,m=%s,mp=%d,c=%d,d=%d,sh=%d,c1=%llu,ad=%llu,se=%llu,ok=%llu,er=%llu,u=%u,h=%x,v=%llu,r=%d",
        KUROKO_MODULE_VERSION_CODE,
        mode,
        g_maps_count.load(std::memory_order_relaxed),
        g_core_hits.load(std::memory_order_relaxed),
        g_disc_hits.load(std::memory_order_relaxed),
        g_step_handles_count.load(std::memory_order_relaxed),
        static_cast<unsigned long long>(g_cnt_c1.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(g_cnt_add.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(g_cnt_send.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(g_cnt_inj_ok.load(std::memory_order_relaxed)),
        static_cast<unsigned long long>(g_cnt_inj_err.load(std::memory_order_relaxed)),
        g_last_uid.load(std::memory_order_relaxed),
        g_last_handle.load(std::memory_order_relaxed),
        static_cast<unsigned long long>(g_last_val.load(std::memory_order_relaxed)),
        g_last_ret.load(std::memory_order_relaxed));
    return std::string(buf);
}

// Active pump + self-healing ELF64 GOT watchdog + <=1Hz telemetry publisher
void step_injector_loop() {
    LOGI("StepInjectorThread started inside system_server");
    constexpr size_t kMaxPerType = 8;
    constexpr size_t kMaxBatch = kMaxPerType * 2;
    SensorsEvent scratch[kMaxBatch]{};

    JNIEnv* thr_env = nullptr;
    if (g_jvm) {
        if (g_jvm->AttachCurrentThreadAsDaemon(&thr_env, nullptr) != JNI_OK) {
            thr_env = nullptr;
            LOGW("StepInjectorThread: AttachCurrentThreadAsDaemon failed");
        }
    }

    const uint64_t start_ns = now_boot_ns();
    uint64_t last_scan_ns = 0;
    uint64_t last_diag_ns = 0;
    std::string last_diag_str;

    while (true) {
        uint64_t now_ns = now_boot_ns();

        // 1. Self-healing /proc/self/maps watchdog for any new libsensorservice.so base
        bool hooked = (load_orig_for_slot(g_orig_send_events, SPEC_SEND_EVENTS,
                                          reinterpret_cast<void*>(hook_send_events)) != nullptr);
        uint64_t elapsed_ms = (now_ns >= start_ns) ? ((now_ns - start_ns) / 1000000ULL) : 0ULL;
        uint64_t scan_interval_ns = (elapsed_ms < 30000ULL)
            ? (hooked ? 500000000ULL : 50000000ULL)
            : 5000000000ULL;

        if (last_scan_ns == 0 || (now_ns - last_scan_ns) >= scan_interval_ns) {
            last_scan_ns = now_ns;
            auto maps = scan_sensorservice_maps();
            int mp = static_cast<int>(maps.size());
            g_maps_count.store(mp, std::memory_order_relaxed);

            for (const auto& m : maps) {
                bool already_patched = false;
                {
                    std::lock_guard<std::mutex> plk(g_patch_mu);
                    already_patched = (g_patched_bases.count(m.base) > 0);
                }
                if (already_patched) continue;

                PatchResult pr = patch_elf_got_at_base(m.base);
                if (pr.abi_unsupported) {
                    {
                        std::lock_guard<std::mutex> plk(g_patch_mu);
                        g_patched_bases.insert(m.base);
                    }
                    if (!hooked && thr_env) {
                        set_java_system_property_jni(thr_env, "kuroko.sensor.hook", "err:abi_unsupported");
                    }
                    LOGW("ELF64 GOT pre-flight rejected unsupported libsensorservice.so base=0x%lx (skipped safely)",
                         static_cast<unsigned long>(m.base));
                    last_diag_ns = 0;
                    continue;
                }
                if (pr.core_mask != 0 &&
                    load_orig_for_slot(g_orig_send_events, SPEC_SEND_EVENTS,
                                       reinterpret_cast<void*>(hook_send_events)) != nullptr &&
                    !pr.had_unresolved_slot) {
                    {
                        std::lock_guard<std::mutex> plk(g_patch_mu);
                        g_patched_bases.insert(m.base);
                    }
                    g_used_elf_got.store(true, std::memory_order_relaxed);
                    record_matched_slots(pr.core_mask, pr.disc_mask);
                    int c_now = g_core_hits.load(std::memory_order_relaxed);
                    int d_now = g_disc_hits.load(std::memory_order_relaxed);

                    char hook_prop[96];
                    std::snprintf(hook_prop, sizeof(hook_prop),
                                  "ok:got:mv=%d,core=%d/5,disc=%d/3,maps=%d",
                                  KUROKO_MODULE_VERSION_CODE,
                                  c_now, d_now, mp);
                    if (thr_env) {
                        set_java_system_property_jni(thr_env, "kuroko.sensor.hook", hook_prop);
                    }
                    LOGI("Self-healing ELF64 GOT patched libsensorservice.so base=0x%lx -> %s",
                         static_cast<unsigned long>(m.base), hook_prop);
                    last_diag_ns = 0; // force immediate diag publish
                }
            }
        }

        // 2. Rate-limited (<= 1Hz) runtime telemetry property update when changed
        if (thr_env && (last_diag_ns == 0 || (now_ns - last_diag_ns) >= 1000000000ULL)) {
            std::string diag = build_diag_string();
            if (diag != last_diag_str) {
                set_java_system_property_jni(thr_env, "kuroko.sensor.diag", diag.c_str());
                last_diag_str = diag;
                last_diag_ns = now_ns;
            }
        }

        // 3. 40Hz active synthetic STEP_DETECTOR & STEP_COUNTER pump
        auto orig_send = load_orig_for_slot(g_orig_send_events, SPEC_SEND_EVENTS,
                                            reinterpret_cast<void*>(hook_send_events));
        KurokoShm snap{};
        if (orig_send && read_fresh_snapshot(&snap) &&
            (snap.global_flags & KUROKO_FLAG_STEP_SPOOF) != 0) {
            uint64_t vsteps = snap.virtual_step_count;
            int64_t step_ts = static_cast<int64_t>(snap.last_step_boot_ns ? snap.last_step_boot_ns : now_boot_ns());

            // Hold shared_lock while dispatching so cleanupConnection cannot free any conn mid-call
            std::shared_lock<std::shared_mutex> lk(g_state_mu);
            for (auto& kv : g_conns) {
                void* conn = kv.first;
                ConnRecord& rec = kv.second;
                uint32_t policy = kuroko_find_target_policy(&snap, rec.uid);
                if ((policy & KUROKO_POLICY_STEPS) == 0) continue;

                int32_t det_handles[kMaxPerType];
                int32_t cnt_handles[kMaxPerType];
                size_t det_n = 0;
                size_t cnt_n = 0;
                for (int32_t h : rec.active_handles) {
                    auto tit = g_handle_to_type.find(h);
                    if (tit == g_handle_to_type.end()) continue;
                    if (tit->second == kSensorTypeStepDetector && det_n < kMaxPerType) {
                        det_handles[det_n++] = h;
                    } else if (tit->second == kSensorTypeStepCounter && cnt_n < kMaxPerType) {
                        cnt_handles[cnt_n++] = h;
                    }
                }
                if (det_n == 0 && cnt_n == 0) continue;

                if (!rec.step_base_initialized) {
                    // Guarantee baseline >= 1000 so fitness apps never reject a 0 initial step counter
                    rec.step_counter_base = std::max<uint64_t>(
                        g_last_hw_steps.load(std::memory_order_relaxed), 1000ULL);
                    rec.last_vsteps_seen = vsteps;
                    rec.emitted_steps = 0;
                    rec.step_base_initialized = true;
                }

                if (cnt_n > 0 && rec.need_initial_emit.exchange(false, std::memory_order_relaxed)) {
                    rec.last_vsteps_seen = vsteps;
                    SensorsEvent init_batch[kMaxPerType]{};
                    int64_t now_ts = static_cast<int64_t>(now_boot_ns());
                    uint64_t cur_total = rec.step_counter_base + rec.emitted_steps;
                    for (size_t i = 0; i < cnt_n; ++i) {
                        SensorsEvent& ev = init_batch[i];
                        ev.version = static_cast<int32_t>(sizeof(SensorsEvent));
                        ev.sensor = cnt_handles[i];
                        ev.type = kSensorTypeStepCounter;
                        ev.timestamp = now_ts;
                        ev.u64_data[0] = cur_total;
                    }
                    int32_t ret = orig_send(conn, init_batch, cnt_n, scratch, nullptr);
                    g_last_uid.store(rec.uid, std::memory_order_relaxed);
                    g_last_handle.store(static_cast<uint32_t>(cnt_handles[0]), std::memory_order_relaxed);
                    g_last_val.store(cur_total, std::memory_order_relaxed);
                    g_last_ret.store(ret, std::memory_order_relaxed);
                    if (ret >= 0) {
                        uint64_t n_ok = g_cnt_inj_ok.fetch_add(1, std::memory_order_relaxed) + 1;
                        if (n_ok == 1) {
                            LOGI("First step injection ok (init): uid=%u handle=0x%x val=%llu ret=%d",
                                 rec.uid, cnt_handles[0], static_cast<unsigned long long>(cur_total), ret);
                        }
                    } else {
                        g_cnt_inj_err.fetch_add(1, std::memory_order_relaxed);
                        LOGW("Step injection failed (init): uid=%u handle=0x%x val=%llu ret=%d",
                             rec.uid, cnt_handles[0], static_cast<unsigned long long>(cur_total), ret);
                    }
                    continue;
                }

                if (vsteps == rec.last_vsteps_seen) continue;
                if (vsteps < rec.last_vsteps_seen) {
                    // User reset daemon virtual steps -> re-sync baseline cleanly; emitted_steps never regresses
                    rec.last_vsteps_seen = vsteps;
                    continue;
                }

                uint64_t delta = vsteps - rec.last_vsteps_seen;
                rec.last_vsteps_seen = vsteps;
                rec.emitted_steps += delta;

                SensorsEvent batch[kMaxBatch]{};
                size_t n = 0;
                for (size_t i = 0; i < det_n && n < kMaxBatch; ++i) {
                    SensorsEvent& ev = batch[n++];
                    ev.version = static_cast<int32_t>(sizeof(SensorsEvent));
                    ev.sensor = det_handles[i];
                    ev.type = kSensorTypeStepDetector;
                    ev.timestamp = step_ts;
                    ev.data[0] = 1.0f;
                }
                uint64_t cur_total = rec.step_counter_base + rec.emitted_steps;
                for (size_t i = 0; i < cnt_n && n < kMaxBatch; ++i) {
                    SensorsEvent& ev = batch[n++];
                    ev.version = static_cast<int32_t>(sizeof(SensorsEvent));
                    ev.sensor = cnt_handles[i];
                    ev.type = kSensorTypeStepCounter;
                    ev.timestamp = step_ts;
                    ev.u64_data[0] = cur_total;
                }
                if (n > 0) {
                    int32_t ret = orig_send(conn, batch, n, scratch, nullptr);
                    int32_t rep_h = (cnt_n > 0) ? cnt_handles[0] : det_handles[0];
                    uint64_t rep_v = (cnt_n > 0) ? cur_total : 1ULL;
                    g_last_uid.store(rec.uid, std::memory_order_relaxed);
                    g_last_handle.store(static_cast<uint32_t>(rep_h), std::memory_order_relaxed);
                    g_last_val.store(rep_v, std::memory_order_relaxed);
                    g_last_ret.store(ret, std::memory_order_relaxed);
                    if (ret >= 0) {
                        uint64_t n_ok = g_cnt_inj_ok.fetch_add(1, std::memory_order_relaxed) + 1;
                        if (n_ok == 1) {
                            LOGI("First step injection ok (delta): uid=%u handle=0x%x val=%llu ret=%d",
                                 rec.uid, rep_h, static_cast<unsigned long long>(rep_v), ret);
                        }
                    } else {
                        g_cnt_inj_err.fetch_add(1, std::memory_order_relaxed);
                        LOGW("Step injection failed (delta): uid=%u handle=0x%x val=%llu ret=%d",
                             rec.uid, rep_h, static_cast<unsigned long long>(rep_v), ret);
                    }
                }
            }
        }

        usleep(25000); // 40Hz wake check (~0% CPU when idle)
    }
}

} // namespace

bool install_sensor_hooks(zygisk::Api* api, JNIEnv* env, const KurokoShm* shm, std::string* out_status) {
    auto set_status = [&](const std::string& s) {
        if (out_status) *out_status = s;
    };

    if (!shm) {
        set_status("err:null_shm");
        return false;
    }
    g_shm = shm;

    if (env) {
        env->GetJavaVM(&g_jvm);
    }

    // Primary path (jni_plt): load libsensorservice.so into system_server's default linker namespace
    // via java.lang.System.load (NEVER C dlopen from Zygisk isolated namespace!).
    bool loaded_via_jni = jni_system_load_lib(env, kLibSensorServicePath);
    auto maps = scan_sensorservice_maps();
    g_maps_count.store(static_cast<int>(maps.size()), std::memory_order_relaxed);
    LOGI("install_sensor_hooks: System.load=%d maps_found=%zu", loaded_via_jni ? 1 : 0, maps.size());

    bool hooked_now = false;
    bool abi_unsupported = false;
    if (api && !maps.empty()) {
        // Pre-filter .rela.plt BEFORE any pltHookRegister call (ZERO nonexistent symbols registered!)
        auto vmas = snapshot_readable_private_vmas();
        ElfPltView view = parse_elf_plt_view(maps[0].base, vmas);
        if (view.valid) {
            std::vector<MatchedPltEntry> matched;
            uint32_t avail_core = 0;
            uint32_t avail_disc = 0;
            scan_matched_plt_entries(view, vmas, &matched, &avail_core, &avail_disc);

            if ((avail_core & kRequiredCoreMask) != kRequiredCoreMask) {
                // Pre-flight core gate failed: DO NOT register any PLT hooks so system_server stays 100% alive
                abi_unsupported = true;
                log_unmatched_oem_symbols_once(maps[0].base, view, vmas, avail_core, avail_disc);
                LOGW("install_sensor_hooks: pre-flight core gate failed (core_mask=0x%x disc_mask=0x%x); "
                     "skipping PLT hooks cleanly",
                     avail_core, avail_disc);
            } else {
                LOGI("install_sensor_hooks: pre-filtered %zu present .rela.plt symbol(s) "
                     "(avail core=%d/5 disc=%d/3)",
                     matched.size(), popcount_u32(avail_core), popcount_u32(avail_disc));
                for (const auto& e : matched) {
                    api->pltHookRegister(
                        maps[0].dev, maps[0].ino,
                        kHookSpecs[e.spec_idx].symbol,
                        kHookSpecs[e.spec_idx].hook_fn,
                        &g_plt_orig_slots[e.spec_idx]);
                }

                bool committed = api->pltHookCommit();

                // UNCONDITIONALLY sync every populated static g_plt_orig_slots[] entry into g_orig_*
                // EVEN IF committed == false (prevents null orig if lsplt patched a subset before returning false!)
                uint32_t core_mask = 0;
                uint32_t disc_mask = 0;
                for (const auto& e : matched) {
                    const auto& spec = kHookSpecs[e.spec_idx];
                    void* orig_p = __atomic_load_n(&g_plt_orig_slots[e.spec_idx], __ATOMIC_ACQUIRE);
                    if (orig_p && orig_p != spec.hook_fn) {
                        spec.store_orig(orig_p);
                        if (is_core_slot(spec.slot)) {
                            core_mask |= slot_bit(spec.slot);
                        } else {
                            disc_mask |= slot_bit(spec.slot);
                        }
                        LOGI("jni_plt matched [%s] (committed=%d): %s -> orig=%p",
                             spec.label, committed ? 1 : 0, spec.symbol, orig_p);
                    }
                }
                int core_hits = popcount_u32(core_mask);
                int disc_hits = popcount_u32(disc_mask);
                LOGI("jni_plt commit=%d on libsensorservice.so (maps=%zu): core=%d/5 (mask=0x%x) disc=%d/3 (mask=0x%x)",
                     committed ? 1 : 0, maps.size(), core_hits, core_mask, disc_hits, disc_mask);

                if (core_mask != 0 || disc_mask != 0) {
                    record_matched_slots(core_mask, disc_mask);
                }

                if (committed && (core_mask & kRequiredCoreMask) == kRequiredCoreMask) {
                    {
                        std::lock_guard<std::mutex> plk(g_patch_mu);
                        for (const auto& m : maps) {
                            if (m.dev == maps[0].dev && m.ino == maps[0].ino) {
                                g_patched_bases.insert(m.base);
                            }
                        }
                    }
                    g_used_jni_plt.store(true, std::memory_order_relaxed);
                    log_unmatched_oem_symbols_once(maps[0].base, view, vmas, core_mask, disc_mask);

                    char summary[64];
                    std::snprintf(summary, sizeof(summary),
                                  "ok:jni_plt:mv=%d,core=%d/5,disc=%d/3",
                                  KUROKO_MODULE_VERSION_CODE, core_hits, disc_hits);
                    set_status(summary);
                    hooked_now = true;
                } else {
                    LOGW("jni_plt incomplete (committed=%d core_mask=0x%x); deferring remaining slots to ELF64 GOT watchdog",
                         committed ? 1 : 0, core_mask);
                }
            }
        } else {
            LOGW("install_sensor_hooks: failed to parse ELF64 .rela.plt at base=0x%lx; deferring to GOT watchdog",
                 static_cast<unsigned long>(maps[0].base));
        }
    }

    if (abi_unsupported) {
        set_status("err:abi_unsupported");
        if (env) {
            std::string init_diag = build_diag_string();
            set_java_system_property_jni(env, "kuroko.sensor.diag", init_diag.c_str());
        }
        return false;
    }

    if (!hooked_now) {
        char wait_buf[48];
        std::snprintf(wait_buf, sizeof(wait_buf), "wait:defer_got:mv=%d", KUROKO_MODULE_VERSION_CODE);
        set_status(wait_buf);
    }

    if (env) {
        std::string init_diag = build_diag_string();
        set_java_system_property_jni(env, "kuroko.sensor.diag", init_diag.c_str());
    }

    // Launch StepInjectorThread (doubles as self-healing ELF64 GOT watchdog for any new maps)
    if (!g_injector_started.exchange(true)) {
        std::thread(step_injector_loop).detach();
    }
    return hooked_now;
}

} // namespace kuroko
