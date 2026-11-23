#pragma once

#include "kuroko_shm.h"
#include <stdint.h>

namespace kuroko {

class GnssNmeaGenerator {
public:
    GnssNmeaGenerator();

    void update(double lat_deg, double lon_deg, double alt_m, double msl_alt_m,
                float speed_mps, float bearing_deg, float h_acc_m,
                uint64_t utc_ms, double dt_sec, KurokoShm* shm);

private:
    struct SvState {
        int32_t constellation;
        int32_t svid;
        float base_el;
        float base_az;
        float az_rate;
        float base_cn0;
        float cn0_drift;
        float carrier_freq_hz;
        bool used;
    };

    static constexpr int kNumSvs = 18;
    SvState svs_[kNumSvs];
    uint32_t rng_state_ = 0x5A17E11Au;
    uint32_t nmea_seq_ = 0;

    float rand_normal();
};

} // namespace kuroko
