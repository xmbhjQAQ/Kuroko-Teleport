#pragma once

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
#include <atomic>
#endif

#define KUROKO_SHM_MAGIC        0x4F52554Bu /* "KURO" little-endian */
#define KUROKO_SHM_VERSION      1u
#define KUROKO_SHM_SIZE         16384u      /* Exact 16KB page alignment */
#define KUROKO_MAX_TARGETS      32u
#define KUROKO_MAX_SVS          32u
#define KUROKO_NMEA_BUF_SIZE    2040u

/* Binder transaction codes (within FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION 0x00ffffff for libbinder_ndk) */
#define KUROKO_BINDER_TX_SET_SHM    0x004B5243u /* 'KRC' */
#define KUROKO_BINDER_TX_PING       0x004B5250u /* 'KRP' */
#define KUROKO_ACK_OK               0x4F4Bu

/* Daemon control TCP loopback port (authenticated by per-boot random token in /data/adb/kuroko/token) */
#define KUROKO_CTRL_TCP_PORT        28765
/* Root-only Unix domain socket path for Zygisk Companion <-> Daemon memfd handoff */
#define KUROKO_COMPANION_SOCK_PATH  "/dev/kuroko_root.sock"
#define KUROKO_TOKEN_FILE_PATH      "/data/adb/kuroko/token"
#define KUROKO_BOOT_GUARD_PATH      "/data/adb/kuroko/boot_guard"

/* Global flags */
#define KUROKO_FLAG_GNSS_SYNTH          (1u << 0)
#define KUROKO_FLAG_SUPPRESS_RAW_MEAS   (1u << 1)
#define KUROKO_FLAG_STEP_SPOOF          (1u << 2)
#define KUROKO_FLAG_IMU_SPOOF           (1u << 3)

/* Per-target UID policy bitmask */
#define KUROKO_POLICY_LOCATION          (1u << 0)
#define KUROKO_POLICY_STEPS             (1u << 1)
#define KUROKO_POLICY_IMU               (1u << 2)
#define KUROKO_POLICY_HIDE_WIFI         (1u << 3)
#define KUROKO_POLICY_HIDE_CELL         (1u << 4)
#define KUROKO_POLICY_SUPPRESS_MEAS     (1u << 5)
#define KUROKO_POLICY_ALL_STRICT        (KUROKO_POLICY_LOCATION | KUROKO_POLICY_STEPS | \
                                         KUROKO_POLICY_IMU | KUROKO_POLICY_HIDE_WIFI | \
                                         KUROKO_POLICY_HIDE_CELL | KUROKO_POLICY_SUPPRESS_MEAS)
#define KUROKO_POLICY_LOCAL_OUTDOOR     (KUROKO_POLICY_LOCATION | KUROKO_POLICY_STEPS | \
                                         KUROKO_POLICY_IMU | KUROKO_POLICY_HIDE_WIFI | \
                                         KUROKO_POLICY_SUPPRESS_MEAS)

/* Motion modes */
enum KurokoMotionMode : uint32_t {
    KUROKO_MOTION_STILL  = 0,
    KUROKO_MOTION_WALK   = 1,
    KUROKO_MOTION_JOG    = 2,
    KUROKO_MOTION_RUN    = 3,
    KUROKO_MOTION_SPRINT = 4,
    KUROKO_MOTION_AUTO   = 5, /* Derive gait automatically from instantaneous speed */
};

/* Carry modes (affects accelerometer & gyroscope axes/attitude) */
enum KurokoCarryMode : uint32_t {
    KUROKO_CARRY_HAND_SWING = 0,
    KUROKO_CARRY_POCKET     = 1,
};

/* GnssStatus SV flags */
#define KUROKO_SV_HAS_EPHEMERIS      (1u << 0)
#define KUROKO_SV_HAS_ALMANAC        (1u << 1)
#define KUROKO_SV_USED_IN_FIX        (1u << 2)
#define KUROKO_SV_HAS_CARRIER_FREQ   (1u << 3)
#define KUROKO_SV_HAS_BASEBAND_CN0   (1u << 4)

#pragma pack(push, 1)

struct KurokoTarget {
    uint32_t uid;                    /* +0x00 */
    uint32_t policy_flags;           /* +0x04 */
    uint64_t step_baseline_override; /* +0x08 (0 = auto-seed from HW) */
};                                   /* 16 bytes */

struct KurokoSv {
    int32_t  constellation;          /* +0x00: 1=GPS, 3=GLONASS, 5=BEIDOU, 6=GALILEO */
    int32_t  svid;                   /* +0x04 */
    float    cn0_dbhz;               /* +0x08 */
    float    elevation_deg;          /* +0x0C */
    float    azimuth_deg;            /* +0x10 */
    float    carrier_freq_hz;        /* +0x14 */
    float    baseband_cn0_dbhz;      /* +0x18 */
    uint32_t flags;                  /* +0x1C */
    uint8_t  reserved[16];           /* +0x20 */
};                                   /* 48 bytes */

struct KurokoShm {
    /* ===== Header: 0x000 .. 0x03F (64 bytes) ===== */
    uint32_t magic;                  /* 0x000 */
    uint32_t version;                /* 0x004 */
    uint32_t seq;                    /* 0x008 Seqlock (odd=writing, even=stable) */
    uint32_t active;                 /* 0x00C 0=off, 1=active */
    uint32_t target_count;           /* 0x010 0..32 */
    uint32_t motion_mode;            /* 0x014 KurokoMotionMode */
    uint32_t carry_mode;             /* 0x018 KurokoCarryMode */
    uint32_t global_flags;           /* 0x01C KUROKO_FLAG_* */
    uint64_t daemon_heartbeat_ns;    /* 0x020 CLOCK_BOOTTIME ns */
    uint64_t snapshot_boot_ns;       /* 0x028 CLOCK_BOOTTIME ns */
    uint64_t snapshot_utc_ms;        /* 0x030 CLOCK_REALTIME ms */
    uint64_t route_epoch;            /* 0x038 */

    /* ===== Location Snapshot: 0x040 .. 0x0BF (128 bytes) ===== */
    double   lat_wgs84;              /* 0x040 */
    double   lon_wgs84;              /* 0x048 */
    double   alt_wgs84;              /* 0x050 */
    double   msl_alt;                /* 0x058 */
    float    speed_mps;              /* 0x060 */
    float    bearing_deg;            /* 0x064 */
    float    h_accuracy_m;           /* 0x068 */
    float    v_accuracy_m;           /* 0x06C */
    float    speed_accuracy_mps;     /* 0x070 */
    float    bearing_accuracy_deg;   /* 0x074 */
    float    total_distance_m;       /* 0x078 */
    float    route_progress;         /* 0x07C */
    uint32_t lap_count;              /* 0x080 */
    uint32_t sv_count;               /* 0x084 */
    float    jitter_sigma_m;         /* 0x088 */
    float    target_speed_mps;       /* 0x08C */
    uint8_t  loc_reserved[48];       /* 0x090 */

    /* ===== Biomechanics / Pedometer & IMU: 0x0C0 .. 0x13F (128 bytes) ===== */
    uint64_t virtual_step_count;     /* 0x0C0 Monotonic virtual step delta */
    uint64_t last_step_boot_ns;      /* 0x0C8 CLOCK_BOOTTIME ns of last heel strike */
    float    cadence_spm;            /* 0x0D0 */
    float    stride_length_m;        /* 0x0D4 */
    float    gait_phase_rad;         /* 0x0D8 */
    float    pressure_hpa;           /* 0x0DC */
    float    accel[3];               /* 0x0E0 TYPE_ACCELEROMETER (m/s^2, includes gravity) */
    float    linear_accel[3];        /* 0x0EC TYPE_LINEAR_ACCELERATION (m/s^2) */
    float    gravity[3];             /* 0x0F8 TYPE_GRAVITY (m/s^2) */
    float    gyro[3];                /* 0x104 TYPE_GYROSCOPE (rad/s) */
    float    rot_vec[4];             /* 0x110 TYPE_ROTATION_VECTOR [x, y, z, w] */
    float    mag[3];                 /* 0x120 TYPE_MAGNETIC_FIELD (uT) */
    uint8_t  imu_reserved[20];       /* 0x12C */

    /* ===== Target UID Table: 0x140 .. 0x33F (512 bytes) ===== */
    struct KurokoTarget targets[KUROKO_MAX_TARGETS];

    /* ===== Synthetic GNSS SVs: 0x340 .. 0x93F (1536 bytes) ===== */
    struct KurokoSv svs[KUROKO_MAX_SVS];

    /* ===== Synthetic NMEA Epoch: 0x940 .. 0x113F (2048 bytes) ===== */
    uint32_t nmea_seq;               /* 0x940 */
    uint32_t nmea_len;               /* 0x944 */
    char     nmea_text[KUROKO_NMEA_BUF_SIZE]; /* 0x948 */

    /* Pad to exact 16KB */
    uint8_t  page_pad[KUROKO_SHM_SIZE - 0x1140];
};

#pragma pack(pop)

#ifdef __cplusplus
static_assert(sizeof(KurokoTarget) == 16, "KurokoTarget must be 16 bytes");
static_assert(sizeof(KurokoSv) == 48, "KurokoSv must be 48 bytes");
static_assert(offsetof(KurokoShm, lat_wgs84) == 0x040, "lat_wgs84 offset mismatch");
static_assert(offsetof(KurokoShm, virtual_step_count) == 0x0C0, "virtual_step_count offset mismatch");
static_assert(offsetof(KurokoShm, targets) == 0x140, "targets offset mismatch");
static_assert(offsetof(KurokoShm, svs) == 0x340, "svs offset mismatch");
static_assert(offsetof(KurokoShm, nmea_seq) == 0x940, "nmea_seq offset mismatch");
static_assert(sizeof(KurokoShm) == KUROKO_SHM_SIZE, "KurokoShm must be exactly 16KB");

inline bool kuroko_shm_read_snapshot(const KurokoShm* shm, KurokoShm* out) {
    if (!shm || !out) return false;
    const auto* seq_ptr = reinterpret_cast<const std::atomic<uint32_t>*>(&shm->seq);
    for (int tries = 0; tries < 8; ++tries) {
        uint32_t s1 = seq_ptr->load(std::memory_order_acquire);
        if (s1 & 1u) continue;
        __builtin_memcpy(out, shm, 0x1140);
        std::atomic_thread_fence(std::memory_order_acquire);
        uint32_t s2 = seq_ptr->load(std::memory_order_acquire);
        if (s1 == s2 && out->magic == KUROKO_SHM_MAGIC && out->version == KUROKO_SHM_VERSION) {
            return true;
        }
    }
    return false;
}

inline uint32_t kuroko_find_target_policy(const KurokoShm* snap, uint32_t uid) {
    if (!snap || !snap->active) return 0;
    uint32_t n = snap->target_count;
    if (n > KUROKO_MAX_TARGETS) n = KUROKO_MAX_TARGETS;
    for (uint32_t i = 0; i < n; ++i) {
        if (snap->targets[i].uid == uid) {
            return snap->targets[i].policy_flags;
        }
    }
    return 0;
}
#endif
