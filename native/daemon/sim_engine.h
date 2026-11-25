#pragma once

#include "kuroko_shm.h"
#include "nmea_gnss.h"
#include "rf_synth.h"
#include <string>
#include <vector>
#include <mutex>

namespace kuroko {

struct Waypoint {
    double lat;
    double lon;
    double alt;
};

enum class SetRouteResult {
    Ok,
    EmptyRoute,
    InvalidCoords,
    ZeroLengthRoute,
};

struct SimStatus {
    bool active;
    bool moving;
    bool loop;
    double lat;
    double lon;
    double alt;
    float speed_mps;
    float target_speed_mps;
    float bearing_deg;
    float h_accuracy_m;
    float total_distance_m;
    float route_length_m;
    float max_distance_m;
    uint32_t lap_count;
    uint32_t max_laps;
    uint64_t virtual_steps;
    float cadence_spm;
    float stride_m;
    uint32_t motion_mode;
    uint32_t carry_mode;
    float jitter_sigma_m;
    float pace_var_ratio;
    uint32_t target_count;
    std::string rf_country;
    uint16_t rf_mcc;
    uint16_t rf_mnc;
    uint32_t rf_cells;
    uint32_t rf_wifis;
    uint32_t rf_bles;
    std::string rf_serving;
};

class SimEngine {
public:
    static constexpr double kMinRouteLengthM = 1.0;

    SimEngine();

    void set_active(bool active);
    void set_moving(bool moving);
    bool set_point(double lat, double lon, double alt);
    SetRouteResult set_route(const std::vector<Waypoint>& pts, bool loop, float max_distance_m, uint32_t max_laps = 0);
    void set_speed(float speed_mps);
    void set_motion_mode(uint32_t mode);
    void set_carry_mode(uint32_t mode);
    void set_jitter(float sigma_m);
    void set_pace_var(float ratio);
    void set_targets(const std::vector<KurokoTarget>& targets);
    void set_rf_config(const RfConfig& cfg);
    void set_rf_cells(const std::vector<PinnedCell>& cells);
    void set_rf_wifis(const std::vector<PinnedWifiAp>& wifis);
    void set_rf_beacons(const std::vector<PinnedBleBeacon>& beacons);
    void reset_steps();
    void reset_route();

    void step(double dt_sec, uint64_t boot_ns, uint64_t utc_ms, KurokoShm* shm);

    SimStatus get_status() const;

private:
    struct SamplePt {
        double lat;
        double lon;
        double alt;
        double cum_dist_m;
        float corner_factor;
    };

    mutable std::mutex mu_;
    bool active_ = false;
    bool moving_ = false;
    bool loop_ = true;
    float target_speed_mps_ = 2.8f;
    float current_speed_mps_ = 0.0f;
    float jitter_sigma_m_ = 2.0f;
    float pace_var_ratio_ = 0.08f;
    float max_distance_m_ = 0.0f;
    uint32_t motion_mode_ = KUROKO_MOTION_AUTO;
    uint32_t carry_mode_ = KUROKO_CARRY_HAND_SWING;

    std::vector<SamplePt> samples_;
    double route_length_m_ = 0.0;
    double dist_on_route_m_ = 0.0;
    double total_distance_m_ = 0.0;
    uint32_t lap_count_ = 0;
    uint32_t max_laps_ = 0;
    uint64_t route_epoch_ = 1;

    double cur_lat_ = 39.9042;
    double cur_lon_ = 116.4074;
    double cur_alt_ = 45.0;
    float cur_bearing_deg_ = 0.0f;
    float cur_h_acc_m_ = 4.5f;

    double ou_east_m_ = 0.0;
    double ou_north_m_ = 0.0;
    double ou_up_m_ = 0.0;
    float ou_speed_mod_ = 0.0f;
    float ou_speed_fast_ = 0.0f;
    float ou_acc_mod_ = 0.0f;
    double disp_lat_ = 39.9042;
    double disp_lon_ = 116.4074;
    double disp_alt_ = 45.0;

    uint64_t virtual_steps_ = 0;
    uint64_t last_step_boot_ns_ = 0;
    double gait_phase_ = 0.0;
    double half_stride_phase_ = 0.0;
    float step_cadence_jitter_ = 1.0f;
    float cadence_spm_ = 0.0f;
    float stride_m_ = 0.0f;
    float prev_q_[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    float sway_drift_pitch_ = 0.0f;
    float sway_drift_roll_ = 0.0f;

    float accel_[3] = {0.0f, 0.0f, 9.80665f};
    float lin_accel_[3] = {0.0f, 0.0f, 0.0f};
    float gravity_[3] = {0.0f, 0.0f, 9.80665f};
    float gyro_[3] = {0.0f, 0.0f, 0.0f};
    float rot_vec_[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    float mag_[3] = {0.0f, 30.0f, -40.0f};
    float pressure_hpa_ = 1007.8f;

    std::vector<KurokoTarget> targets_;

    double gnss_accum_sec_ = 1.0;
    bool rf_dirty_ = true;
    GnssNmeaGenerator gnss_gen_;
    RfSynthesizer rf_synth_;

    uint64_t rng_state_ = 0x9E3779B97F4A7C15ULL;
    float rand_normal();
    void build_smooth_route_samples(const std::vector<Waypoint>& pts, bool loop,
                                    std::vector<SamplePt>* out_samples, double* out_length_m) const;
    void sample_route_at_locked(double s_m, double* lat, double* lon, double* alt,
                                float* bearing_deg, float* corner_factor) const;
    void update_biomechanics_locked(double dt_sec, uint64_t boot_ns, float eff_speed);
};

} // namespace kuroko
