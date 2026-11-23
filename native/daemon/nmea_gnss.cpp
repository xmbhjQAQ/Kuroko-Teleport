#include "nmea_gnss.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>

namespace kuroko {

namespace {
constexpr float kL1FreqHz = 1575420000.0f;
constexpr float kB1FreqHz = 1561098000.0f;
constexpr float kG1FreqHz = 1602000000.0f;

size_t append_nmea(char* dst, size_t cap, size_t pos, const char* body) {
    uint8_t csum = 0;
    for (const char* p = body; *p; ++p) {
        csum ^= static_cast<uint8_t>(*p);
    }
    int n = std::snprintf(dst + pos, (pos < cap) ? (cap - pos) : 0, "$%s*%02X\r\n", body, csum);
    if (n <= 0) return pos;
    size_t next = pos + static_cast<size_t>(n);
    return (next < cap) ? next : (cap - 1);
}

void format_dm(double deg, bool is_lat, char* out, size_t out_sz, char* hemi) {
    *hemi = is_lat ? ((deg >= 0.0) ? 'N' : 'S') : ((deg >= 0.0) ? 'E' : 'W');
    double a = std::fabs(deg);
    int d = static_cast<int>(a);
    double m = (a - d) * 60.0;
    if (is_lat) {
        std::snprintf(out, out_sz, "%02d%07.4f", d, m);
    } else {
        std::snprintf(out, out_sz, "%03d%07.4f", d, m);
    }
}
} // namespace

GnssNmeaGenerator::GnssNmeaGenerator() {
    const SvState init[kNumSvs] = {
        {1,  3, 72.0f,  35.0f,  0.008f, 39.5f, 0.0f, kL1FreqHz, true},
        {1,  8, 54.0f, 128.0f,  0.006f, 36.0f, 0.0f, kL1FreqHz, true},
        {1, 14, 41.0f, 215.0f, -0.007f, 33.5f, 0.0f, kL1FreqHz, true},
        {1, 19, 63.0f, 305.0f,  0.005f, 38.0f, 0.0f, kL1FreqHz, true},
        {1, 24, 28.0f,  78.0f, -0.006f, 29.0f, 0.0f, kL1FreqHz, true},
        {1, 31, 18.0f, 255.0f,  0.007f, 24.5f, 0.0f, kL1FreqHz, false},
        {5,  2, 68.0f, 165.0f,  0.002f, 40.5f, 0.0f, kB1FreqHz, true},
        {5,  6, 58.0f, 195.0f,  0.004f, 37.5f, 0.0f, kB1FreqHz, true},
        {5, 11, 49.0f,  52.0f, -0.005f, 35.0f, 0.0f, kB1FreqHz, true},
        {5, 22, 37.0f, 332.0f,  0.006f, 32.0f, 0.0f, kB1FreqHz, true},
        {5, 29, 77.0f, 112.0f, -0.003f, 41.0f, 0.0f, kB1FreqHz, true},
        {5, 38, 23.0f, 288.0f,  0.005f, 26.5f, 0.0f, kB1FreqHz, false},
        {6,  4, 61.0f,  15.0f,  0.005f, 36.5f, 0.0f, kL1FreqHz, true},
        {6, 12, 44.0f, 148.0f, -0.006f, 33.0f, 0.0f, kL1FreqHz, true},
        {6, 27, 29.0f, 236.0f,  0.004f, 28.0f, 0.0f, kL1FreqHz, true},
        {3,  5, 52.0f,  95.0f, -0.007f, 34.5f, 0.0f, kG1FreqHz, true},
        {3, 15, 36.0f, 272.0f,  0.006f, 30.5f, 0.0f, kG1FreqHz, true},
        {3, 21, 21.0f, 350.0f, -0.005f, 25.0f, 0.0f, kG1FreqHz, false},
    };
    std::memcpy(svs_, init, sizeof(svs_));
}

float GnssNmeaGenerator::rand_normal() {
    auto next_u = [this]() -> float {
        rng_state_ ^= rng_state_ << 13;
        rng_state_ ^= rng_state_ >> 17;
        rng_state_ ^= rng_state_ << 5;
        return static_cast<float>((rng_state_ & 0xFFFFFFu) + 1u) / 16777217.0f;
    };
    float u1 = next_u();
    float u2 = next_u();
    return std::sqrt(-2.0f * std::log(u1)) * std::cos(6.2831853f * u2);
}

void GnssNmeaGenerator::update(double lat_deg, double lon_deg, double alt_m, double msl_alt_m,
                               float speed_mps, float bearing_deg, float h_acc_m,
                               uint64_t utc_ms, double dt_sec, KurokoShm* shm) {
    if (!shm) return;

    float dt = static_cast<float>(dt_sec > 0.01 ? dt_sec : 1.0);
    float decay = std::exp(-dt / 12.0f);
    float noise_scale = 1.6f * std::sqrt(1.0f - decay * decay);

    int used_count = 0;
    for (int i = 0; i < kNumSvs; ++i) {
        SvState& s = svs_[i];
        s.base_az = std::fmod(s.base_az + s.az_rate * dt + 360.0f, 360.0f);
        s.cn0_drift = s.cn0_drift * decay + noise_scale * rand_normal();
        float cn0 = s.base_cn0 + s.cn0_drift;
        if (cn0 < 16.0f) cn0 = 16.0f;
        if (cn0 > 45.0f) cn0 = 45.0f;

        KurokoSv& out = shm->svs[i];
        out.constellation = s.constellation;
        out.svid = s.svid;
        out.cn0_dbhz = cn0;
        out.elevation_deg = s.base_el;
        out.azimuth_deg = s.base_az;
        out.carrier_freq_hz = s.carrier_freq_hz;
        out.baseband_cn0_dbhz = cn0 - 2.2f;
        uint32_t fl = KUROKO_SV_HAS_EPHEMERIS | KUROKO_SV_HAS_ALMANAC |
                      KUROKO_SV_HAS_CARRIER_FREQ | KUROKO_SV_HAS_BASEBAND_CN0;
        if (s.used) {
            fl |= KUROKO_SV_USED_IN_FIX;
            used_count++;
        }
        out.flags = fl;
    }
    shm->sv_count = static_cast<uint32_t>(kNumSvs);

    time_t sec = static_cast<time_t>(utc_ms / 1000ULL);
    int msec = static_cast<int>(utc_ms % 1000ULL);
    struct tm gmt{};
    gmtime_r(&sec, &gmt);

    char lat_s[24], lon_s[24];
    char ns = 'N', ew = 'E';
    format_dm(lat_deg, true, lat_s, sizeof(lat_s), &ns);
    format_dm(lon_deg, false, lon_s, sizeof(lon_s), &ew);

    float hdop = h_acc_m / 4.2f;
    if (hdop < 0.7f) hdop = 0.7f;
    if (hdop > 3.5f) hdop = 3.5f;
    float geoid_sep = static_cast<float>(alt_m - msl_alt_m);
    float speed_knots = speed_mps * 1.9438445f;

    char* buf = shm->nmea_text;
    size_t cap = KUROKO_NMEA_BUF_SIZE;
    size_t pos = 0;
    char body[160];

    std::snprintf(body, sizeof(body),
                  "GNGGA,%02d%02d%02d.%02d,%s,%c,%s,%c,1,%02d,%.2f,%.1f,M,%.1f,M,,",
                  gmt.tm_hour, gmt.tm_min, gmt.tm_sec, msec / 10,
                  lat_s, ns, lon_s, ew,
                  used_count > 12 ? 12 : used_count,
                  hdop, msl_alt_m, geoid_sep);
    pos = append_nmea(buf, cap, pos, body);

    std::snprintf(body, sizeof(body),
                  "GNRMC,%02d%02d%02d.%02d,A,%s,%c,%s,%c,%.2f,%.1f,%02d%02d%02d,,,A",
                  gmt.tm_hour, gmt.tm_min, gmt.tm_sec, msec / 10,
                  lat_s, ns, lon_s, ew,
                  speed_knots, bearing_deg,
                  gmt.tm_mday, gmt.tm_mon + 1, (gmt.tm_year + 1900) % 100);
    pos = append_nmea(buf, cap, pos, body);

    std::snprintf(body, sizeof(body),
                  "GNGSA,A,3,03,08,14,19,24,,,,,,,,%.2f,%.2f,%.2f",
                  hdop * 1.45f, hdop, hdop * 1.1f);
    pos = append_nmea(buf, cap, pos, body);

    std::snprintf(body, sizeof(body),
                  "GPGSV,2,1,06,%02d,%02d,%03d,%02d,%02d,%02d,%03d,%02d,%02d,%02d,%03d,%02d,%02d,%02d,%03d,%02d",
                  shm->svs[0].svid, static_cast<int>(shm->svs[0].elevation_deg), static_cast<int>(shm->svs[0].azimuth_deg), static_cast<int>(shm->svs[0].cn0_dbhz),
                  shm->svs[1].svid, static_cast<int>(shm->svs[1].elevation_deg), static_cast<int>(shm->svs[1].azimuth_deg), static_cast<int>(shm->svs[1].cn0_dbhz),
                  shm->svs[2].svid, static_cast<int>(shm->svs[2].elevation_deg), static_cast<int>(shm->svs[2].azimuth_deg), static_cast<int>(shm->svs[2].cn0_dbhz),
                  shm->svs[3].svid, static_cast<int>(shm->svs[3].elevation_deg), static_cast<int>(shm->svs[3].azimuth_deg), static_cast<int>(shm->svs[3].cn0_dbhz));
    pos = append_nmea(buf, cap, pos, body);

    std::snprintf(body, sizeof(body),
                  "GPGSV,2,2,06,%02d,%02d,%03d,%02d,%02d,%02d,%03d,%02d",
                  shm->svs[4].svid, static_cast<int>(shm->svs[4].elevation_deg), static_cast<int>(shm->svs[4].azimuth_deg), static_cast<int>(shm->svs[4].cn0_dbhz),
                  shm->svs[5].svid, static_cast<int>(shm->svs[5].elevation_deg), static_cast<int>(shm->svs[5].azimuth_deg), static_cast<int>(shm->svs[5].cn0_dbhz));
    pos = append_nmea(buf, cap, pos, body);

    buf[pos] = '\0';
    shm->nmea_len = static_cast<uint32_t>(pos);
    shm->nmea_seq = ++nmea_seq_;
}

} // namespace kuroko
