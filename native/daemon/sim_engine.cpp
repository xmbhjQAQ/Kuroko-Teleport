#include "sim_engine.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>

namespace kuroko {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr double kTwoPi = 2.0 * kPi;
constexpr double kDegToRad = kPi / 180.0;
constexpr double kRadToDeg = 180.0 / kPi;
constexpr double kEarthRadiusM = 6378137.0;
constexpr float  kGravity = 9.80665f;

double haversine_m(double lat1, double lon1, double lat2, double lon2) {
    double dlat = (lat2 - lat1) * kDegToRad;
    double dlon = (lon2 - lon1) * kDegToRad;
    double a = std::sin(dlat * 0.5) * std::sin(dlat * 0.5) +
               std::cos(lat1 * kDegToRad) * std::cos(lat2 * kDegToRad) *
               std::sin(dlon * 0.5) * std::sin(dlon * 0.5);
    return 2.0 * kEarthRadiusM * std::atan2(std::sqrt(a), std::sqrt(std::max(0.0, 1.0 - a)));
}

float initial_bearing_deg(double lat1, double lon1, double lat2, double lon2) {
    double phi1 = lat1 * kDegToRad;
    double phi2 = lat2 * kDegToRad;
    double dlam = (lon2 - lon1) * kDegToRad;
    double y = std::sin(dlam) * std::cos(phi2);
    double x = std::cos(phi1) * std::sin(phi2) - std::sin(phi1) * std::cos(phi2) * std::cos(dlam);
    double b = std::atan2(y, x) * kRadToDeg;
    if (b < 0.0) b += 360.0;
    return static_cast<float>(b);
}

// Centripetal-style Catmull-Rom interpolation
double catmull_rom(double p0, double p1, double p2, double p3, double t) {
    double t2 = t * t;
    double t3 = t2 * t;
    return 0.5 * ((2.0 * p1) +
                  (-p0 + p2) * t +
                  (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
                  (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
}

// Quaternion helpers: q = [x, y, z, w] rotating body -> world (Android ENU convention)
void euler_to_quat(float yaw_rad, float pitch_rad, float roll_rad, float q[4]) {
    float cy = std::cos(yaw_rad * 0.5f),   sy = std::sin(yaw_rad * 0.5f);
    float cp = std::cos(pitch_rad * 0.5f), sp = std::sin(pitch_rad * 0.5f);
    float cr = std::cos(roll_rad * 0.5f),  sr = std::sin(roll_rad * 0.5f);
    // Z (yaw) * X (pitch) * Y (roll)
    q[3] = cy * cp * cr - sy * sp * sr; // w
    q[0] = cy * sp * cr - sy * cp * sr; // x
    q[1] = cy * cp * sr + sy * sp * cr; // y
    q[2] = sy * cp * cr + cy * sp * sr; // z
    float n = std::sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]);
    if (n > 1e-6f) {
        q[0] /= n; q[1] /= n; q[2] /= n; q[3] /= n;
    }
}

// Rotate world vector v_w into body frame: v_b = q^{-1} * v_w * q
void rotate_world_to_body(const float q[4], const float vw[3], float vb[3]) {
    // q_conj = [-x, -y, -z, w]
    float qx = -q[0], qy = -q[1], qz = -q[2], qw = q[3];
    float tx = 2.0f * (qy * vw[2] - qz * vw[1]);
    float ty = 2.0f * (qz * vw[0] - qx * vw[2]);
    float tz = 2.0f * (qx * vw[1] - qy * vw[0]);
    vb[0] = vw[0] + qw * tx + (qy * tz - qz * ty);
    vb[1] = vw[1] + qw * ty + (qz * tx - qx * tz);
    vb[2] = vw[2] + qw * tz + (qx * ty - qy * tx);
}

// Body-frame angular velocity from q_prev -> q_curr over dt: dq = q_prev^{-1} * q_curr
void quat_to_body_omega(const float qp[4], const float qc[4], float dt, float omega[3]) {
    if (dt < 1e-4f) {
        omega[0] = omega[1] = omega[2] = 0.0f;
        return;
    }
    // q_prev_conj * q_curr
    float px = -qp[0], py = -qp[1], pz = -qp[2], pw = qp[3];
    float cx =  qc[0], cy =  qc[1], cz =  qc[2], cw = qc[3];
    float dx = pw*cx + px*cw + py*cz - pz*cy;
    float dy = pw*cy - px*cz + py*cw + pz*cx;
    float dz = pw*cz + px*cy - py*cx + pz*cw;
    float dw = pw*cw - px*cx - py*cy - pz*cz;
    if (dw < 0.0f) { dx = -dx; dy = -dy; dz = -dz; dw = -dw; }
    float vnorm = std::sqrt(dx*dx + dy*dy + dz*dz);
    if (vnorm < 1e-7f) {
        omega[0] = omega[1] = omega[2] = 0.0f;
        return;
    }
    float angle = 2.0f * std::atan2(vnorm, dw);
    float s = (angle / dt) / vnorm;
    omega[0] = dx * s;
    omega[1] = dy * s;
    omega[2] = dz * s;
}
} // namespace

SimEngine::SimEngine() {
    euler_to_quat(0.0f, 0.35f, 0.0f, prev_q_);
    std::memcpy(rot_vec_, prev_q_, sizeof(rot_vec_));
}

float SimEngine::rand_normal() {
    auto next_u = [this]() -> float {
        // xorshift64*
        uint64_t x = rng_state_;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        rng_state_ = x;
        uint64_t r = x * 2685821657736338717ULL;
        return static_cast<float>(((r >> 40) & 0xFFFFFFu) + 1u) / 16777217.0f;
    };
    float u1 = next_u();
    float u2 = next_u();
    return std::sqrt(-2.0f * std::log(u1)) * std::cos(static_cast<float>(kTwoPi) * u2);
}

void SimEngine::set_active(bool active) {
    std::lock_guard<std::mutex> lk(mu_);
    active_ = active;
    if (!active_) {
        moving_ = false;
        current_speed_mps_ = 0.0f;
    }
}

void SimEngine::set_moving(bool moving) {
    std::lock_guard<std::mutex> lk(mu_);
    moving_ = moving;
    if (moving_) {
        active_ = true;
    }
}

void SimEngine::set_point(double lat, double lon, double alt) {
    std::lock_guard<std::mutex> lk(mu_);
    samples_.clear();
    route_length_m_ = 0.0;
    dist_on_route_m_ = 0.0;
    total_distance_m_ = 0.0;
    lap_count_ = 0;
    max_laps_ = 0;
    cur_lat_ = disp_lat_ = lat;
    cur_lon_ = disp_lon_ = lon;
    cur_alt_ = disp_alt_ = alt;
    moving_ = false;
    current_speed_mps_ = 0.0f;
    route_epoch_++;
    rf_dirty_ = true;
}

void SimEngine::set_route(const std::vector<Waypoint>& pts, bool loop, float max_distance_m, uint32_t max_laps) {
    std::lock_guard<std::mutex> lk(mu_);
    loop_ = loop;
    max_distance_m_ = std::max(0.0f, max_distance_m);
    max_laps_ = max_laps;
    dist_on_route_m_ = 0.0;
    total_distance_m_ = 0.0;
    lap_count_ = 0;
    route_epoch_++;
    rf_dirty_ = true;

    if (pts.empty()) {
        samples_.clear();
        route_length_m_ = 0.0;
        moving_ = false;
        return;
    }
    if (pts.size() == 1) {
        samples_.clear();
        route_length_m_ = 0.0;
        cur_lat_ = disp_lat_ = pts[0].lat;
        cur_lon_ = disp_lon_ = pts[0].lon;
        cur_alt_ = disp_alt_ = pts[0].alt;
        return;
    }

    rebuild_smooth_route_locked(pts, loop);
    float dummy_cf = 1.0f;
    sample_route_at_locked(0.0, &cur_lat_, &cur_lon_, &cur_alt_, &cur_bearing_deg_, &dummy_cf);
    disp_lat_ = cur_lat_;
    disp_lon_ = cur_lon_;
    disp_alt_ = cur_alt_;
}

void SimEngine::rebuild_smooth_route_locked(const std::vector<Waypoint>& pts, bool loop) {
    samples_.clear();
    const size_t n = pts.size();
    const size_t segs = loop ? n : (n - 1);

    // Build smooth Catmull-Rom samples (~every 3 meters along each segment)
    std::vector<Waypoint> dense;
    dense.reserve(segs * 12);

    auto get_pt = [&](int idx) -> Waypoint {
        if (loop) {
            int m = ((idx % static_cast<int>(n)) + static_cast<int>(n)) % static_cast<int>(n);
            return pts[static_cast<size_t>(m)];
        }
        int c = std::clamp(idx, 0, static_cast<int>(n - 1));
        return pts[static_cast<size_t>(c)];
    };

    for (size_t i = 0; i < segs; ++i) {
        Waypoint p0 = get_pt(static_cast<int>(i) - 1);
        Waypoint p1 = get_pt(static_cast<int>(i));
        Waypoint p2 = get_pt(static_cast<int>(i) + 1);
        Waypoint p3 = get_pt(static_cast<int>(i) + 2);
        double seg_len = haversine_m(p1.lat, p1.lon, p2.lat, p2.lon);
        int sub = std::clamp(static_cast<int>(std::ceil(seg_len / 3.0)), 4, 40);
        for (int k = 0; k < sub; ++k) {
            double t = static_cast<double>(k) / static_cast<double>(sub);
            Waypoint w;
            w.lat = catmull_rom(p0.lat, p1.lat, p2.lat, p3.lat, t);
            w.lon = catmull_rom(p0.lon, p1.lon, p2.lon, p3.lon, t);
            w.alt = catmull_rom(p0.alt, p1.alt, p2.alt, p3.alt, t);
            dense.push_back(w);
        }
    }
    if (loop) {
        dense.push_back(dense.front());
    } else {
        dense.push_back(pts.back());
    }

    samples_.reserve(dense.size());
    double cum = 0.0;
    for (size_t i = 0; i < dense.size(); ++i) {
        if (i > 0) {
            cum += haversine_m(dense[i - 1].lat, dense[i - 1].lon, dense[i].lat, dense[i].lon);
        }
        SamplePt sp{dense[i].lat, dense[i].lon, dense[i].alt, cum, 1.0f};
        samples_.push_back(sp);
    }
    route_length_m_ = cum;

    // Estimate corner slowdown factor from local heading change
    for (size_t i = 1; i + 1 < samples_.size(); ++i) {
        float b1 = initial_bearing_deg(samples_[i - 1].lat, samples_[i - 1].lon, samples_[i].lat, samples_[i].lon);
        float b2 = initial_bearing_deg(samples_[i].lat, samples_[i].lon, samples_[i + 1].lat, samples_[i + 1].lon);
        float diff = std::fabs(b2 - b1);
        if (diff > 180.0f) diff = 360.0f - diff;
        // 15 deg per 3m sample -> mild turn; 45 deg -> tight corner (slow down to ~0.72)
        float cf = 1.0f - std::clamp(diff / 120.0f, 0.0f, 0.30f);
        samples_[i].corner_factor = cf;
    }
}

void SimEngine::sample_route_at_locked(double s_m, double* lat, double* lon, double* alt,
                                       float* bearing_deg, float* corner_factor) const {
    if (samples_.size() < 2 || route_length_m_ <= 1e-3) {
        *lat = cur_lat_;
        *lon = cur_lon_;
        *alt = cur_alt_;
        *bearing_deg = cur_bearing_deg_;
        *corner_factor = 1.0f;
        return;
    }
    double s = std::clamp(s_m, 0.0, route_length_m_);
    auto it = std::lower_bound(samples_.begin(), samples_.end(), s,
                               [](const SamplePt& a, double v) { return a.cum_dist_m < v; });
    size_t idx = (it == samples_.begin()) ? 1 : static_cast<size_t>(it - samples_.begin());
    if (idx >= samples_.size()) idx = samples_.size() - 1;
    const SamplePt& a = samples_[idx - 1];
    const SamplePt& b = samples_[idx];
    double seg = std::max(1e-6, b.cum_dist_m - a.cum_dist_m);
    double frac = std::clamp((s - a.cum_dist_m) / seg, 0.0, 1.0);
    *lat = a.lat + (b.lat - a.lat) * frac;
    *lon = a.lon + (b.lon - a.lon) * frac;
    *alt = a.alt + (b.alt - a.alt) * frac;
    *bearing_deg = initial_bearing_deg(a.lat, a.lon, b.lat, b.lon);
    *corner_factor = a.corner_factor + (b.corner_factor - a.corner_factor) * static_cast<float>(frac);
}

void SimEngine::set_speed(float speed_mps) {
    std::lock_guard<std::mutex> lk(mu_);
    target_speed_mps_ = std::clamp(speed_mps, 0.0f, 30.0f);
}

void SimEngine::set_motion_mode(uint32_t mode) {
    std::lock_guard<std::mutex> lk(mu_);
    motion_mode_ = mode;
}

void SimEngine::set_carry_mode(uint32_t mode) {
    std::lock_guard<std::mutex> lk(mu_);
    carry_mode_ = mode;
}

void SimEngine::set_jitter(float sigma_m) {
    std::lock_guard<std::mutex> lk(mu_);
    jitter_sigma_m_ = std::clamp(sigma_m, 0.0f, 25.0f);
}

void SimEngine::set_pace_var(float ratio) {
    std::lock_guard<std::mutex> lk(mu_);
    pace_var_ratio_ = std::clamp(ratio, 0.0f, 0.25f);
    if (pace_var_ratio_ <= 1e-4f) {
        ou_speed_mod_ = 0.0f;
        ou_speed_fast_ = 0.0f;
    }
}

void SimEngine::set_targets(const std::vector<KurokoTarget>& targets) {
    std::lock_guard<std::mutex> lk(mu_);
    targets_.clear();
    for (size_t i = 0; i < targets.size() && i < KUROKO_MAX_TARGETS; ++i) {
        targets_.push_back(targets[i]);
    }
}

void SimEngine::set_rf_config(const RfConfig& cfg) {
    std::lock_guard<std::mutex> lk(mu_);
    rf_synth_.set_config(cfg);
    rf_dirty_ = true;
}

void SimEngine::set_rf_cells(const std::vector<PinnedCell>& cells) {
    std::lock_guard<std::mutex> lk(mu_);
    rf_synth_.set_pinned_cells(cells);
    rf_dirty_ = true;
}

void SimEngine::set_rf_wifis(const std::vector<PinnedWifiAp>& wifis) {
    std::lock_guard<std::mutex> lk(mu_);
    rf_synth_.set_pinned_wifis(wifis);
    rf_dirty_ = true;
}

void SimEngine::set_rf_beacons(const std::vector<PinnedBleBeacon>& beacons) {
    std::lock_guard<std::mutex> lk(mu_);
    rf_synth_.set_pinned_beacons(beacons);
    rf_dirty_ = true;
}

void SimEngine::reset_steps() {
    std::lock_guard<std::mutex> lk(mu_);
    virtual_steps_ = 0;
    total_distance_m_ = 0.0;
    lap_count_ = 0;
    dist_on_route_m_ = 0.0;
}

void SimEngine::update_biomechanics_locked(double dt_sec, uint64_t boot_ns, float eff_speed) {
    // Determine effective biomechanical mode & gait speed
    uint32_t mode = motion_mode_;
    float gait_speed = eff_speed;
    if (mode == KUROKO_MOTION_AUTO) {
        if (eff_speed < 0.25f)      mode = KUROKO_MOTION_STILL;
        else if (eff_speed < 2.0f)  mode = KUROKO_MOTION_WALK;
        else if (eff_speed < 3.6f)  mode = KUROKO_MOTION_JOG;
        else if (eff_speed < 5.0f)  mode = KUROKO_MOTION_RUN;
        else                        mode = KUROKO_MOTION_SPRINT;
    } else {
        // Explicit user mode overrides still/walk/run feel even if stationary or testing pedometer alone
        if (mode == KUROKO_MOTION_STILL) {
            gait_speed = 0.0f;
        } else if (gait_speed < 0.4f) {
            if (mode == KUROKO_MOTION_WALK)   gait_speed = 1.35f;
            if (mode == KUROKO_MOTION_JOG)    gait_speed = 2.80f;
            if (mode == KUROKO_MOTION_RUN)    gait_speed = 4.10f;
            if (mode == KUROKO_MOTION_SPRINT) gait_speed = 5.80f;
        }
    }

    // Empirical human stride length L(v) & step frequency f = v / L(v)
    float stride = 0.72f;
    float impact_amp = 0.0f;    // vertical peak acceleration amplitude (m/s^2)
    float fwd_amp = 0.0f;       // forward braking/push-off accel (m/s^2)
    float lat_amp = 0.0f;       // lateral sway accel at f/2 (m/s^2)
    float swing_pitch_amp = 0.0f;
    float swing_roll_amp = 0.0f;

    if (mode == KUROKO_MOTION_STILL || gait_speed < 0.2f) {
        stride_m_ = 0.0f;
        cadence_spm_ = 0.0f;
    } else {
        // Continuous empirical curve: L ~ 0.48 + 0.20 * v, clamped by mode
        stride = std::clamp(0.48f + 0.19f * gait_speed, 0.62f, 1.95f);
        float freq_hz = std::clamp((gait_speed / stride) * step_cadence_jitter_, 1.25f, 3.85f);
        stride_m_ = gait_speed / freq_hz;
        cadence_spm_ = freq_hz * 60.0f;

        if (mode == KUROKO_MOTION_WALK) {
            impact_amp = 2.6f;  fwd_amp = 1.1f; lat_amp = 0.7f;
            swing_pitch_amp = (carry_mode_ == KUROKO_CARRY_HAND_SWING) ? 0.22f : 0.14f;
            swing_roll_amp  = 0.07f;
        } else if (mode == KUROKO_MOTION_JOG) {
            impact_amp = 11.5f; fwd_amp = 3.2f; lat_amp = 1.9f;
            swing_pitch_amp = (carry_mode_ == KUROKO_CARRY_HAND_SWING) ? 0.42f : 0.26f;
            swing_roll_amp  = 0.14f;
        } else if (mode == KUROKO_MOTION_RUN) {
            impact_amp = 18.5f; fwd_amp = 4.8f; lat_amp = 2.6f;
            swing_pitch_amp = (carry_mode_ == KUROKO_CARRY_HAND_SWING) ? 0.56f : 0.34f;
            swing_roll_amp  = 0.18f;
        } else { // SPRINT
            impact_amp = 26.0f; fwd_amp = 6.8f; lat_amp = 3.4f;
            swing_pitch_amp = (carry_mode_ == KUROKO_CARRY_HAND_SWING) ? 0.72f : 0.42f;
            swing_roll_amp  = 0.24f;
        }

        double dphi = kTwoPi * static_cast<double>(freq_hz) * dt_sec;
        gait_phase_ += dphi;
        half_stride_phase_ += dphi * 0.5;

        // Trigger heel-strike step event every 2*pi crossing
        while (gait_phase_ >= kTwoPi) {
            gait_phase_ -= kTwoPi;
            virtual_steps_++;
            last_step_boot_ns_ = boot_ns;
            // Subtle step-to-step cadence variation (+/- 4.5%) so FFT has natural human spread
            step_cadence_jitter_ = std::clamp(1.0f + 0.045f * rand_normal(), 0.90f, 1.10f);
        }
        if (half_stride_phase_ >= kTwoPi) {
            half_stride_phase_ -= kTwoPi;
        }
    }

    // Slow hand/pocket posture drift (OU process, tau ~ 4s)
    float dt_f = static_cast<float>(dt_sec);
    float drift_decay = std::exp(-dt_f / 4.0f);
    float drift_noise = 0.06f * std::sqrt(std::max(0.0f, 1.0f - drift_decay * drift_decay));
    sway_drift_pitch_ = sway_drift_pitch_ * drift_decay + drift_noise * rand_normal();
    sway_drift_roll_  = sway_drift_roll_  * drift_decay + drift_noise * rand_normal();

    float base_pitch = (carry_mode_ == KUROKO_CARRY_HAND_SWING) ? 0.55f : 1.38f; // hand tilted vs vertical in pocket
    float pitch = base_pitch + sway_drift_pitch_ + swing_pitch_amp * std::sin(static_cast<float>(half_stride_phase_));
    float roll  = sway_drift_roll_  + swing_roll_amp  * std::cos(static_cast<float>(half_stride_phase_) + 0.4f);
    // Android yaw: 0 = North, positive CCW from North -> -bearing
    float yaw = static_cast<float>(-cur_bearing_deg_ * kDegToRad);

    float q_curr[4];
    euler_to_quat(yaw, pitch, roll, q_curr);

    // Exact analytic gyroscope from quaternion change
    quat_to_body_omega(prev_q_, q_curr, dt_f, gyro_);
    gyro_[0] += 0.012f * rand_normal();
    gyro_[1] += 0.012f * rand_normal();
    gyro_[2] += 0.012f * rand_normal();
    std::memcpy(prev_q_, q_curr, sizeof(prev_q_));
    std::memcpy(rot_vec_, q_curr, sizeof(rot_vec_));

    // Synthesize world-frame linear acceleration aligned to heel-strike phase phi = 0
    float phi = static_cast<float>(gait_phase_);
    float hphi = static_cast<float>(half_stride_phase_);
    // Sharp asymmetric heel-strike impulse near phi ~ 0 (wrapped to [-pi, pi])
    float d_strike = (phi > static_cast<float>(kPi)) ? (phi - static_cast<float>(kTwoPi)) : phi;
    float impulse = std::exp(-(d_strike * d_strike) / (2.0f * 0.38f * 0.38f)) - 0.16f;

    float a_up_lin = impact_amp * (0.48f * std::cos(phi) +
                                   0.26f * std::cos(2.0f * phi - 0.35f) +
                                   0.10f * std::cos(3.0f * phi - 0.7f) +
                                   0.45f * impulse);
    float a_fwd_lin = fwd_amp * (-0.65f * std::sin(phi + 0.25f) + 0.25f * std::cos(2.0f * phi));
    float a_lat_lin = lat_amp * std::sin(hphi + 0.2f); // Half-frequency left/right foot sway!

    // Map (fwd, lat, up) -> world ENU (East, North, Up) using bearing
    float b_rad = static_cast<float>(cur_bearing_deg_ * kDegToRad);
    float sin_b = std::sin(b_rad), cos_b = std::cos(b_rad);
    float aw_lin[3] = {
        a_fwd_lin * sin_b + a_lat_lin * cos_b,
        a_fwd_lin * cos_b - a_lat_lin * sin_b,
        a_up_lin
    };
    float gw[3] = {0.0f, 0.0f, kGravity};
    float lat_rad = static_cast<float>(disp_lat_ * kDegToRad);
    float mag_w[3] = {
        0.0f,
        31.0f * std::cos(lat_rad),
        -62.0f * std::sin(lat_rad)
    }; // Latitude-aware Earth dipole geomagnetic vector (uT, ENU)

    rotate_world_to_body(q_curr, gw, gravity_);
    rotate_world_to_body(q_curr, aw_lin, lin_accel_);
    rotate_world_to_body(q_curr, mag_w, mag_);

    // MEMS sensor thermal noise floor
    for (int i = 0; i < 3; ++i) {
        lin_accel_[i] += 0.045f * rand_normal();
        accel_[i] = gravity_[i] + lin_accel_[i];
        mag_[i] += 0.35f * rand_normal();
    }

    // Standard barometric formula matched to current altitude
    pressure_hpa_ = static_cast<float>(1013.25 * std::pow(1.0 - 2.25577e-5 * disp_alt_, 5.25588))
                    + 0.02f * rand_normal();
}

void SimEngine::step(double dt_sec, uint64_t boot_ns, uint64_t utc_ms, KurokoShm* shm) {
    if (!shm) return;
    std::lock_guard<std::mutex> lk(mu_);

    double dt = std::clamp(dt_sec, 0.001, 0.25);

    // 1. Advance route kinematics if active & moving
    float eff_speed = 0.0f;
    if (active_ && moving_ && samples_.size() >= 2 && route_length_m_ > 1.0) {
        // Two-timescale human running pace fluctuation driven by pace_var_ratio_:
        // - Medium-term pace rhythm (OU tau = 18s, sigma = 0.75 * pace_var_ratio_)
        // - Short-term stride/breathing micro-fluctuation (OU tau = 4s, sigma = 0.35 * pace_var_ratio_)
        float slow_decay = std::exp(static_cast<float>(-dt / 18.0));
        float fast_decay = std::exp(static_cast<float>(-dt / 4.0));
        float slow_sigma = 0.75f * pace_var_ratio_;
        float fast_sigma = 0.35f * pace_var_ratio_;
        ou_speed_mod_  = ou_speed_mod_  * slow_decay +
                         slow_sigma * std::sqrt(std::max(0.0f, 1.0f - slow_decay * slow_decay)) * rand_normal();
        ou_speed_fast_ = ou_speed_fast_ * fast_decay +
                         fast_sigma * std::sqrt(std::max(0.0f, 1.0f - fast_decay * fast_decay)) * rand_normal();

        float lo_factor = std::max(0.65f, 1.0f - 1.6f * pace_var_ratio_);
        float hi_factor = 1.0f + 1.6f * pace_var_ratio_;
        float speed_mod_factor = std::clamp(1.0f + ou_speed_mod_ + ou_speed_fast_, lo_factor, hi_factor);

        double tmp_lat, tmp_lon, tmp_alt;
        float tmp_b, corner_f;
        sample_route_at_locked(dist_on_route_m_, &tmp_lat, &tmp_lon, &tmp_alt, &tmp_b, &corner_f);

        float desired_v = target_speed_mps_ * corner_f * speed_mod_factor;
        // Smooth human acceleration/deceleration (tau ~ 1.2s)
        float alpha = 1.0f - std::exp(static_cast<float>(-dt / 1.2));
        current_speed_mps_ += (desired_v - current_speed_mps_) * alpha;
        eff_speed = std::max(0.0f, current_speed_mps_);

        double step_m = eff_speed * dt;
        dist_on_route_m_ += step_m;
        total_distance_m_ += step_m;

        if (max_distance_m_ > 0.0f && total_distance_m_ >= max_distance_m_) {
            moving_ = false;
            current_speed_mps_ = 0.0f;
            eff_speed = 0.0f;
        } else if (dist_on_route_m_ >= route_length_m_) {
            lap_count_++;
            if (max_laps_ > 0 && lap_count_ >= max_laps_) {
                dist_on_route_m_ = loop_ ? 0.0 : route_length_m_;
                moving_ = false;
                current_speed_mps_ = 0.0f;
                eff_speed = 0.0f;
            } else {
                dist_on_route_m_ = std::fmod(dist_on_route_m_, route_length_m_);
            }
        }

        sample_route_at_locked(dist_on_route_m_, &cur_lat_, &cur_lon_, &cur_alt_, &cur_bearing_deg_, &corner_f);
    } else {
        // Decelerate smoothly to 0 when paused
        current_speed_mps_ *= std::exp(static_cast<float>(-dt / 0.4));
        if (current_speed_mps_ < 0.05f) current_speed_mps_ = 0.0f;
        eff_speed = current_speed_mps_;
    }

    // 2. Apply Ornstein-Uhlenbeck temporally-correlated GPS jitter in local ENU meters
    double tau_gps = 9.0; // 9s correlation time (realistic multipath + iono slow drift)
    double g_decay = std::exp(-dt / tau_gps);
    double g_scale = static_cast<double>(jitter_sigma_m_) * std::sqrt(std::max(0.0, 1.0 - g_decay * g_decay));
    ou_east_m_  = ou_east_m_  * g_decay + g_scale * rand_normal();
    ou_north_m_ = ou_north_m_ * g_decay + g_scale * rand_normal();
    ou_up_m_    = ou_up_m_    * g_decay + (g_scale * 0.65) * rand_normal();

    double hf_e = static_cast<double>(jitter_sigma_m_) * 0.14 * rand_normal();
    double hf_n = static_cast<double>(jitter_sigma_m_) * 0.14 * rand_normal();

    double d_north_m = ou_north_m_ + hf_n;
    double d_east_m  = ou_east_m_  + hf_e;
    double cos_lat = std::max(0.05, std::cos(cur_lat_ * kDegToRad));

    disp_lat_ = cur_lat_ + (d_north_m / kEarthRadiusM) * kRadToDeg;
    disp_lon_ = cur_lon_ + (d_east_m  / (kEarthRadiusM * cos_lat)) * kRadToDeg;
    disp_alt_ = cur_alt_ + ou_up_m_;

    // Correlated horizontal accuracy breathing (3.5..10.5m)
    float acc_decay = std::exp(static_cast<float>(-dt / 10.0));
    ou_acc_mod_ = ou_acc_mod_ * acc_decay + 0.9f * std::sqrt(std::max(0.0f, 1.0f - acc_decay * acc_decay)) * rand_normal();
    float err_mag = static_cast<float>(std::hypot(d_east_m, d_north_m));
    cur_h_acc_m_ = std::clamp(3.6f + 0.65f * jitter_sigma_m_ + 0.35f * err_mag + ou_acc_mod_, 3.0f, 18.0f);

    // 3. Advance biomechanical pedometer & 5-axis IMU state
    if (active_) {
        update_biomechanics_locked(dt, boot_ns, eff_speed);
    }

    // 4. Publish atomic Seqlock snapshot into KurokoShm
    auto* seq_ptr = reinterpret_cast<std::atomic<uint32_t>*>(&shm->seq);
    uint32_t seq = seq_ptr->load(std::memory_order_relaxed);
    seq_ptr->store(seq + 1u, std::memory_order_release);
    std::atomic_thread_fence(std::memory_order_release);

    shm->magic = KUROKO_SHM_MAGIC;
    shm->version = KUROKO_SHM_VERSION;
    shm->active = active_ ? 1u : 0u;
    shm->motion_mode = motion_mode_;
    shm->carry_mode = carry_mode_;
    shm->global_flags = KUROKO_FLAG_GNSS_SYNTH | KUROKO_FLAG_SUPPRESS_RAW_MEAS |
                        KUROKO_FLAG_STEP_SPOOF | KUROKO_FLAG_IMU_SPOOF;
    shm->daemon_heartbeat_ns = boot_ns;
    shm->snapshot_boot_ns = boot_ns;
    shm->snapshot_utc_ms = utc_ms;
    shm->route_epoch = route_epoch_;

    shm->lat_wgs84 = disp_lat_;
    shm->lon_wgs84 = disp_lon_;
    shm->alt_wgs84 = disp_alt_;
    shm->msl_alt = disp_alt_ - 8.5; // Approximate geoid separation in eastern China
    shm->speed_mps = (eff_speed > 0.1f) ? std::max(0.0f, eff_speed + 0.08f * rand_normal()) : 0.0f;
    shm->bearing_deg = cur_bearing_deg_;
    shm->h_accuracy_m = cur_h_acc_m_;
    shm->v_accuracy_m = cur_h_acc_m_ * 1.25f;
    shm->speed_accuracy_mps = 0.25f;
    shm->bearing_accuracy_deg = (eff_speed > 0.4f) ? 6.5f : 25.0f;
    shm->total_distance_m = static_cast<float>(total_distance_m_);
    shm->route_progress = (route_length_m_ > 1.0)
                          ? static_cast<float>(dist_on_route_m_ / route_length_m_) : 0.0f;
    shm->lap_count = lap_count_;
    shm->jitter_sigma_m = jitter_sigma_m_;
    shm->target_speed_mps = target_speed_mps_;

    shm->virtual_step_count = virtual_steps_;
    shm->last_step_boot_ns = last_step_boot_ns_;
    shm->cadence_spm = cadence_spm_;
    shm->stride_length_m = stride_m_;
    shm->gait_phase_rad = static_cast<float>(gait_phase_);
    shm->pressure_hpa = pressure_hpa_;
    std::memcpy(shm->accel, accel_, sizeof(accel_));
    std::memcpy(shm->linear_accel, lin_accel_, sizeof(lin_accel_));
    std::memcpy(shm->gravity, gravity_, sizeof(gravity_));
    std::memcpy(shm->gyro, gyro_, sizeof(gyro_));
    std::memcpy(shm->rot_vec, rot_vec_, sizeof(rot_vec_));
    std::memcpy(shm->mag, mag_, sizeof(mag_));

    shm->target_count = static_cast<uint32_t>(targets_.size());
    for (size_t i = 0; i < targets_.size() && i < KUROKO_MAX_TARGETS; ++i) {
        shm->targets[i] = targets_[i];
    }

    // 1Hz GNSS SV constellation, NMEA & global RF refresh (immediate RF refresh on config/pin/teleport change)
    gnss_accum_sec_ += dt;
    if (gnss_accum_sec_ >= 1.0) {
        gnss_gen_.update(disp_lat_, disp_lon_, disp_alt_, shm->msl_alt,
                         shm->speed_mps, cur_bearing_deg_, cur_h_acc_m_,
                         utc_ms, gnss_accum_sec_, shm);
        rf_synth_.update(disp_lat_, disp_lon_, boot_ns, gnss_accum_sec_, shm);
        gnss_accum_sec_ = 0.0;
        rf_dirty_ = false;
    } else if (rf_dirty_) {
        rf_synth_.update(disp_lat_, disp_lon_, boot_ns, dt, shm);
        rf_dirty_ = false;
    }

    std::atomic_thread_fence(std::memory_order_release);
    seq_ptr->store(seq + 2u, std::memory_order_release);
}

SimStatus SimEngine::get_status() const {
    std::lock_guard<std::mutex> lk(mu_);
    RfTelemetry rf = rf_synth_.get_telemetry();
    return SimStatus{
        active_, moving_, loop_,
        disp_lat_, disp_lon_, disp_alt_,
        current_speed_mps_, target_speed_mps_,
        cur_bearing_deg_, cur_h_acc_m_,
        static_cast<float>(total_distance_m_),
        static_cast<float>(route_length_m_),
        max_distance_m_,
        lap_count_, max_laps_, virtual_steps_,
        cadence_spm_, stride_m_,
        motion_mode_, carry_mode_,
        jitter_sigma_m_,
        pace_var_ratio_,
        static_cast<uint32_t>(targets_.size()),
        std::move(rf.country_iso),
        rf.mcc,
        rf.mnc,
        rf.cell_count,
        rf.wifi_count,
        rf.ble_count,
        std::move(rf.serving_summary),
    };
}

} // namespace kuroko
