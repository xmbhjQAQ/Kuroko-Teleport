#include "rf_synth.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace kuroko {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kDegToRad = kPi / 180.0;
constexpr double kEarthRadiusM = 6378137.0;
constexpr double kMetersPerDegLat = 111320.0;

struct RegionProfile {
    const char* iso_lower;
    const char* iso_upper;
    uint16_t    mcc;
    uint16_t    mnc;
    uint8_t     mnc_digits;
    const char* alpha;
    uint16_t    lte_band[2];
    uint32_t    lte_arfcn[2];
    uint16_t    nr_band;
    uint32_t    nr_arfcn;
    const int32_t* wifi_5g_freqs;
    size_t      wifi_5g_count;
    const char* const* ssid_prefixes;
    size_t      ssid_count;
};

// Regulatory 5GHz channel plans
constexpr int32_t kWifi5gFcc[]  = {5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825};
constexpr int32_t kWifi5gCn[]   = {5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825};
constexpr int32_t kWifi5gEtsi[] = {5180, 5200, 5220, 5240, 5260, 5280, 5300, 5320};
constexpr int32_t kWifi5gJp[]   = {5180, 5200, 5220, 5240, 5260, 5280, 5300, 5320};
constexpr int32_t kWifi2g[]     = {2412, 2437, 2462};

constexpr const char* kSsidsCn[] = {"CMCC-", "ChinaNet-", "CU_", "TP-LINK_", "Xiaomi_", "HUAWEI-", "Tenda_", "MERCURY_"};
constexpr const char* kSsidsHk[] = {"HKBN-", "Netvigator-", "csl-WiFi-", "PCCW-", "ASUS_", "TP-Link_"};
constexpr const char* kSsidsTw[] = {"CHT-WiFi-", "HiNet-", "ASUS_", "D-Link_", "TP-Link_", "HomeNet-"};
constexpr const char* kSsidsJp[] = {"aterm-", "Buffalo-A-", "Buffalo-G-", "ELECOM-", "SoftBank-", "docomo-"};
constexpr const char* kSsidsKr[] = {"iptime_", "KT_GiGA_", "SK_WiFiGIGA", "U+Net", "iptime5G_", "Olleh_"};
constexpr const char* kSsidsSg[] = {"Singtel-", "StarHub-", "MyRepublic-", "ASUS_", "TP-Link_", "Linksys"};
constexpr const char* kSsidsUs[] = {"xfinitywifi", "ATT", "SpectrumSetup-", "Verizon_", "NETGEAR", "Linksys", "GoogleFiber-"};
constexpr const char* kSsidsCa[] = {"BELL", "ROGERS-", "TELUS", "Shaw-", "NETGEAR", "TP-Link_"};
constexpr const char* kSsidsGb[] = {"BT-", "SKY", "VM", "EE-Hub-", "Vodafone-", "TalkTalk-"};
constexpr const char* kSsidsDe[] = {"FRITZ!Box ", "WLAN-", "Vodafone-", "o2-WLAN", "Telekom_", "Speedport-"};
constexpr const char* kSsidsFr[] = {"Livebox-", "Freebox-", "SFR_", "Bbox-", "TP-Link_", "Numericable-"};
constexpr const char* kSsidsAu[] = {"Telstra", "Optus_", "TPG-", "AussieBB-", "NETGEAR", "TP-Link_"};
constexpr const char* kSsidsGl[] = {"TP-Link_", "NETGEAR", "ASUS_", "Linksys", "Guest-WiFi-", "Office-5G-"};

// Authentic unicast globally-administered networking vendor OUIs (bit0=0 unicast, bit1=0 OUI)
constexpr uint8_t kVendorOuis[][3] = {
    {0x50, 0xC7, 0xBF}, // TP-Link
    {0x2C, 0xFD, 0xA1}, // ASUSTek
    {0xA0, 0x40, 0xA0}, // Netgear
    {0x64, 0xCC, 0x2E}, // Xiaomi Communications
    {0x48, 0x46, 0xFB}, // Huawei
    {0xC0, 0x56, 0x27}, // Linksys / Belkin
    {0x10, 0x6F, 0x3F}, // Buffalo
    {0x38, 0x10, 0xD5}, // AVM (FRITZ!Box)
    {0x74, 0xAC, 0xB9}, // Ubiquiti
    {0x00, 0x1B, 0x63}, // Apple Airport / Consumer
    {0x34, 0x97, 0xF6}, // ASUSTek 5G
    {0xB0, 0xBE, 0x76}, // TP-Link Archer
};
constexpr size_t kNumVendorOuis = sizeof(kVendorOuis) / sizeof(kVendorOuis[0]);

constexpr const char* kAmbientBleNames[] = {
    "Watch", "Buds", "Band", "Tag", "Audio", "Speaker", "Tracker", "Scale", "Lock", "Sensor"
};
constexpr size_t kNumAmbientBleNames = sizeof(kAmbientBleNames) / sizeof(kAmbientBleNames[0]);

constexpr RegionProfile kRegionHk  = {"hk", "HK", 454,   0, 2, "CSL",          {3, 7},   {1650, 3100},  78, 633312, kWifi5gCn,   sizeof(kWifi5gCn)/sizeof(int32_t),   kSsidsHk, sizeof(kSsidsHk)/sizeof(char*)};
constexpr RegionProfile kRegionTw  = {"tw", "TW", 466,  92, 2, "Chunghwa",     {3, 7},   {1700, 3350},  78, 634656, kWifi5gFcc,  sizeof(kWifi5gFcc)/sizeof(int32_t),  kSsidsTw, sizeof(kSsidsTw)/sizeof(char*)};
constexpr RegionProfile kRegionCn  = {"cn", "CN", 460,   0, 2, "China Mobile", {3, 41},  {1300, 38950}, 78, 633984, kWifi5gCn,   sizeof(kWifi5gCn)/sizeof(int32_t),   kSsidsCn, sizeof(kSsidsCn)/sizeof(char*)};
constexpr RegionProfile kRegionKr  = {"kr", "KR", 450,   5, 2, "SKTelecom",    {3, 1},   {1350, 100},   78, 640000, kWifi5gFcc,  sizeof(kWifi5gFcc)/sizeof(int32_t),  kSsidsKr, sizeof(kSsidsKr)/sizeof(char*)};
constexpr RegionProfile kRegionJp  = {"jp", "JP", 440,  10, 2, "NTT DOCOMO",   {1, 3},   {100, 1850},   78, 643328, kWifi5gJp,   sizeof(kWifi5gJp)/sizeof(int32_t),   kSsidsJp, sizeof(kSsidsJp)/sizeof(char*)};
constexpr RegionProfile kRegionSg  = {"sg", "SG", 525,   1, 2, "Singtel",      {3, 7},   {1650, 3100},  78, 634656, kWifi5gFcc,  sizeof(kWifi5gFcc)/sizeof(int32_t),  kSsidsSg, sizeof(kSsidsSg)/sizeof(char*)};
constexpr RegionProfile kRegionUs  = {"us", "US", 310, 410, 3, "AT&T",         {2, 66},  {900, 66786},  77, 650000, kWifi5gFcc,  sizeof(kWifi5gFcc)/sizeof(int32_t),  kSsidsUs, sizeof(kSsidsUs)/sizeof(char*)};
constexpr RegionProfile kRegionCa  = {"ca", "CA", 302, 720, 3, "Rogers",       {4, 7},   {2100, 3100},  78, 636672, kWifi5gFcc,  sizeof(kWifi5gFcc)/sizeof(int32_t),  kSsidsCa, sizeof(kSsidsCa)/sizeof(char*)};
constexpr RegionProfile kRegionGb  = {"gb", "GB", 234,  15, 2, "Vodafone UK",  {3, 20},  {1300, 6300},  78, 628000, kWifi5gEtsi, sizeof(kWifi5gEtsi)/sizeof(int32_t), kSsidsGb, sizeof(kSsidsGb)/sizeof(char*)};
constexpr RegionProfile kRegionDe  = {"de", "DE", 262,   1, 2, "Telekom.de",   {3, 7},   {1300, 2850},  78, 640704, kWifi5gEtsi, sizeof(kWifi5gEtsi)/sizeof(int32_t), kSsidsDe, sizeof(kSsidsDe)/sizeof(char*)};
constexpr RegionProfile kRegionFr  = {"fr", "FR", 208,   1, 2, "Orange F",     {3, 7},   {1650, 3000},  78, 646656, kWifi5gEtsi, sizeof(kWifi5gEtsi)/sizeof(int32_t), kSsidsFr, sizeof(kSsidsFr)/sizeof(char*)};
constexpr RegionProfile kRegionAu  = {"au", "AU", 505,   1, 2, "Telstra",      {3, 28},  {1650, 9410},  78, 640000, kWifi5gFcc,  sizeof(kWifi5gFcc)/sizeof(int32_t),  kSsidsAu, sizeof(kSsidsAu)/sizeof(char*)};
constexpr RegionProfile kRegionGl  = {"us", "US", 310, 260, 3, "T-Mobile",     {2, 4},   {900, 2100},   77, 650000, kWifi5gEtsi, sizeof(kWifi5gEtsi)/sizeof(int32_t), kSsidsGl, sizeof(kSsidsGl)/sizeof(char*)};

const RegionProfile* lookup_region_by_coords(double lat, double lon) {
    // Specific small regions first
    if (lat >= 22.14 && lat <= 22.58 && lon >= 113.82 && lon <= 114.45) return &kRegionHk;
    if (lat >= 21.85 && lat <= 25.35 && lon >= 119.30 && lon <= 122.10) return &kRegionTw;
    if (lat >= 33.00 && lat <= 38.70 && lon >= 124.50 && lon <= 131.00) return &kRegionKr;
    if (lat >= 24.00 && lat <= 46.00 && lon >= 122.90 && lon <= 146.50) {
        // Distinguish East China coast vs Japan/Okinawa
        if (lon >= 129.0 || (lat < 31.5 && lon >= 123.5)) return &kRegionJp;
    }
    if (lat >= 18.00 && lat <= 53.60 && lon >= 73.50 && lon <= 135.10) return &kRegionCn;
    if (lat >= -11.0 && lat <= 21.00 && lon >= 95.00 && lon <= 126.50) return &kRegionSg;

    // North America
    if ((lat >= 24.50 && lat <= 49.40 && lon >= -125.0 && lon <= -66.9) ||
        (lat >= 51.00 && lat <= 72.00 && lon >= -170.0 && lon <= -130.0) ||
        (lat >= 18.50 && lat <= 22.50 && lon >= -161.0 && lon <= -154.0)) {
        return &kRegionUs;
    }
    if (lat >= 41.70 && lat <= 83.10 && lon >= -141.0 && lon <= -52.6) return &kRegionCa;

    // Europe
    if (lat >= 49.80 && lat <= 61.00 && lon >= -8.70 && lon <= 2.00) return &kRegionGb;
    if (lat >= 47.20 && lat <= 55.10 && lon >= 5.80 && lon <= 15.10) return &kRegionDe;
    if (lat >= 41.30 && lat <= 51.10 && lon >= -5.20 && lon <= 9.60) return &kRegionFr;
    if (lat >= 35.00 && lat <= 71.50 && lon >= -10.50 && lon <= 40.00) return &kRegionDe;

    // Oceania
    if (lat >= -47.50 && lat <= -10.00 && lon >= 112.0 && lon <= 179.5) return &kRegionAu;

    return &kRegionGl;
}

const RegionProfile* lookup_region_by_iso(const std::string& iso_lower) {
    if (iso_lower == "hk") return &kRegionHk;
    if (iso_lower == "tw") return &kRegionTw;
    if (iso_lower == "cn") return &kRegionCn;
    if (iso_lower == "kr") return &kRegionKr;
    if (iso_lower == "jp") return &kRegionJp;
    if (iso_lower == "sg") return &kRegionSg;
    if (iso_lower == "us") return &kRegionUs;
    if (iso_lower == "ca") return &kRegionCa;
    if (iso_lower == "gb" || iso_lower == "uk") return &kRegionGb;
    if (iso_lower == "de") return &kRegionDe;
    if (iso_lower == "fr") return &kRegionFr;
    if (iso_lower == "au" || iso_lower == "nz") return &kRegionAu;
    return nullptr;
}

uint64_t splitmix64(uint64_t x) {
    x += 0x9E3779B97F4A7C15ULL;
    x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ULL;
    x = (x ^ (x >> 27)) * 0x94D049BB133111EBULL;
    return x ^ (x >> 31);
}

uint64_t grid_hash(int64_t gx, int64_t gy, uint64_t salt) {
    uint64_t h = splitmix64(static_cast<uint64_t>(gx) ^ salt);
    h = splitmix64(h ^ (static_cast<uint64_t>(gy) * 0x9E3779B97F4A7C15ULL));
    return splitmix64(h ^ salt);
}

double hash_unit(uint64_t h) {
    return static_cast<double>(h & 0xFFFFFFULL) / 16777216.0;
}

double haversine_m(double lat1, double lon1, double lat2, double lon2) {
    double dlat = (lat2 - lat1) * kDegToRad;
    double dlon = (lon2 - lon1) * kDegToRad;
    double a = std::sin(dlat * 0.5) * std::sin(dlat * 0.5) +
               std::cos(lat1 * kDegToRad) * std::cos(lat2 * kDegToRad) *
               std::sin(dlon * 0.5) * std::sin(dlon * 0.5);
    return 2.0 * kEarthRadiusM * std::atan2(std::sqrt(a), std::sqrt(std::max(0.0, 1.0 - a)));
}

void wifi_channel_params(int32_t freq_mhz, int32_t* out_width, int32_t* out_center0) {
    if (freq_mhz < 5000) {
        *out_width = 0; // 20 MHz
        *out_center0 = freq_mhz;
        return;
    }
    if (freq_mhz >= 5180 && freq_mhz <= 5240) {
        *out_width = 2; // 80 MHz
        *out_center0 = 5210;
    } else if (freq_mhz >= 5260 && freq_mhz <= 5320) {
        *out_width = 2; // 80 MHz
        *out_center0 = 5290;
    } else if (freq_mhz >= 5500 && freq_mhz <= 5560) {
        *out_width = 2; // 80 MHz
        *out_center0 = 5530;
    } else if (freq_mhz >= 5745 && freq_mhz <= 5805) {
        *out_width = 2; // 80 MHz
        *out_center0 = 5775;
    } else {
        *out_width = 0; // 20 MHz (e.g. ch165 / 5825)
        *out_center0 = freq_mhz;
    }
}

void build_ibeacon_ad(const uint8_t uuid[16], uint16_t major, uint16_t minor, int8_t tx_power,
                      uint8_t out_adv[62], uint8_t* out_len) {
    std::memset(out_adv, 0, 62);
    // Spec-compliant 30-byte Apple 0x004C iBeacon AD packet:
    // 02 01 06 | 1A FF 4C 00 02 15 | <16B UUID> | <2B Major BE> | <2B Minor BE> | <1B TxPower>
    out_adv[0] = 0x02;
    out_adv[1] = 0x01;
    out_adv[2] = 0x06;
    out_adv[3] = 0x1A;
    out_adv[4] = 0xFF;
    out_adv[5] = 0x4C;
    out_adv[6] = 0x00;
    out_adv[7] = 0x02;
    out_adv[8] = 0x15;
    std::memcpy(out_adv + 9, uuid, 16);
    out_adv[25] = static_cast<uint8_t>((major >> 8) & 0xFFu);
    out_adv[26] = static_cast<uint8_t>(major & 0xFFu);
    out_adv[27] = static_cast<uint8_t>((minor >> 8) & 0xFFu);
    out_adv[28] = static_cast<uint8_t>(minor & 0xFFu);
    out_adv[29] = static_cast<uint8_t>(tx_power);
    *out_len = 30;
}

// Return the longest byte length <= min(len, max_bytes) that does not split a UTF-8 code point.
size_t utf8_safe_prefix_len(const char* s, size_t len, size_t max_bytes) {
    if (!s || len == 0 || max_bytes == 0) return 0;
    size_t n = std::min(len, max_bytes);
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

void build_generic_ble_ad(const char* name, uint8_t name_len, uint8_t out_adv[62], uint8_t* out_len) {
    std::memset(out_adv, 0, 62);
    out_adv[0] = 0x02;
    out_adv[1] = 0x01;
    out_adv[2] = 0x06;
    uint8_t pos = 3;
    if (name && name_len > 0) {
        uint8_t n = static_cast<uint8_t>(utf8_safe_prefix_len(name, name_len, 24));
        if (n > 0 && static_cast<size_t>(pos) + 2u + n <= 62u) {
            out_adv[pos++] = static_cast<uint8_t>(n + 1u);
            out_adv[pos++] = 0x09; // Complete Local Name
            std::memcpy(out_adv + pos, name, n);
            pos = static_cast<uint8_t>(pos + n);
        }
    }
    *out_len = pos;
}

void copy_cstr_padded(char* dst, size_t cap, const char* src) {
    std::memset(dst, 0, cap);
    if (!src || cap <= 1) return;
    size_t n = utf8_safe_prefix_len(src, std::strlen(src), cap - 1);
    if (n > 0) std::memcpy(dst, src, n);
}

} // namespace

RfSynthesizer::RfSynthesizer() = default;

float RfSynthesizer::rand_normal() {
    auto next_u = [this]() -> float {
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
    return std::sqrt(-2.0f * std::log(u1)) * std::cos(static_cast<float>(2.0 * kPi) * u2);
}

void RfSynthesizer::set_config(const RfConfig& cfg) {
    config_ = cfg;
    // Reset serving cell hysteresis when carrier/region config changes
    serving_ci_ = 0;
    serving_rat_ = 0;
}

void RfSynthesizer::set_pinned_cells(const std::vector<PinnedCell>& cells) {
    pinned_cells_.clear();
    for (size_t i = 0; i < cells.size() && i < 4; ++i) {
        pinned_cells_.push_back(cells[i]);
    }
    serving_ci_ = 0;
}

void RfSynthesizer::set_pinned_wifis(const std::vector<PinnedWifiAp>& wifis) {
    pinned_wifis_.clear();
    for (size_t i = 0; i < wifis.size() && i < 8; ++i) {
        pinned_wifis_.push_back(wifis[i]);
    }
}

void RfSynthesizer::set_pinned_beacons(const std::vector<PinnedBleBeacon>& beacons) {
    pinned_beacons_.clear();
    for (size_t i = 0; i < beacons.size() && i < 8; ++i) {
        pinned_beacons_.push_back(beacons[i]);
    }
}

void RfSynthesizer::update(double lat_deg, double lon_deg, uint64_t boot_ns, double dt_sec, KurokoShm* shm) {
    if (!shm) return;

    const float dt = static_cast<float>(std::clamp(dt_sec, 0.05, 5.0));
    auto step_ou = [&](float& state, float tau, float sigma) {
        float decay = std::exp(-dt / tau);
        state = state * decay + sigma * std::sqrt(std::max(0.0f, 1.0f - decay * decay)) * rand_normal();
    };
    step_ou(ou_cell_fade_, 8.0f, 2.2f);
    step_ou(ou_wifi_fade_, 6.0f, 2.5f);
    step_ou(ou_ble_fade_,  5.0f, 2.0f);

    // 1. Resolve active region & carrier identity
    const RegionProfile* coord_reg = lookup_region_by_coords(lat_deg, lon_deg);
    std::string iso_lower = coord_reg->iso_lower;
    if (!config_.country_iso.empty()) {
        iso_lower.clear();
        for (size_t i = 0; i < config_.country_iso.size() && i < 2; ++i) {
            iso_lower.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(config_.country_iso[i]))));
        }
    }
    const RegionProfile* active_reg = lookup_region_by_iso(iso_lower);
    if (!active_reg) active_reg = coord_reg;

    std::string iso_upper;
    for (char c : iso_lower) {
        iso_upper.push_back(static_cast<char>(std::toupper(static_cast<unsigned char>(c))));
    }
    if (iso_upper.size() < 2) {
        iso_lower = active_reg->iso_lower;
        iso_upper = active_reg->iso_upper;
    }

    const uint16_t carrier_mcc = (config_.carrier_mcc > 0) ? config_.carrier_mcc : active_reg->mcc;
    const uint16_t carrier_mnc = (config_.carrier_mcc > 0) ? config_.carrier_mnc : active_reg->mnc;
    const uint8_t  mnc_digits  = (config_.carrier_mcc > 0)
                                     ? static_cast<uint8_t>(config_.mnc_digits == 3 ? 3 : 2)
                                     : active_reg->mnc_digits;
    const std::string carrier_alpha =
        !config_.carrier_alpha.empty() ? config_.carrier_alpha : std::string(active_reg->alpha);

    const uint32_t rf_flags = config_.rf_flags;
    const bool cell_auto = (rf_flags & KUROKO_RF_FLAG_CELL_AUTO) != 0;
    const bool prefer_5g = (rf_flags & KUROKO_RF_FLAG_CELL_PREFER_5G) != 0;
    const bool wifi_auto = (rf_flags & KUROKO_RF_FLAG_WIFI_AUTO) != 0;
    const bool wifi_spoof_conn = (rf_flags & KUROKO_RF_FLAG_WIFI_SPOOF_CONN) != 0;
    const bool ble_ambient = (rf_flags & KUROKO_RF_FLAG_BLE_AMBIENT) != 0;

    // 2. Synthesize Cell Towers (user-pinned + 450m deterministic grid + 3 dB hysteresis)
    std::vector<KurokoCell> cell_cands;
    cell_cands.reserve(16);

    for (size_t i = 0; i < pinned_cells_.size(); ++i) {
        const auto& pc = pinned_cells_[i];
        const bool always_follow = (pc.pin_lat > 90.0 || pc.pin_lon > 180.0);
        double dist_m = always_follow ? 120.0 : haversine_m(lat_deg, lon_deg, pc.pin_lat, pc.pin_lon);
        if (!always_follow && dist_m > 3000.0) continue;

        float fade = ou_cell_fade_ * 0.6f + 0.8f * rand_normal();
        int32_t rsrp = pc.rsrp_dbm;
        if (!always_follow) {
            double atten = 28.0 * std::log10(std::max(dist_m, 30.0) / 30.0);
            rsrp = static_cast<int32_t>(std::lround(static_cast<double>(pc.rsrp_dbm) - atten + fade));
        } else {
            rsrp = static_cast<int32_t>(std::lround(static_cast<double>(pc.rsrp_dbm) + fade));
        }
        rsrp = std::clamp(rsrp, -125, -52);

        KurokoCell c{};
        c.rat = (pc.rat == KUROKO_RAT_NR) ? KUROKO_RAT_NR : KUROKO_RAT_LTE;
        c.registered = 0;
        c.mnc_digits = (pc.mnc_digits == 3) ? 3 : 2;
        c.flags = KUROKO_CELL_FLAG_USER_PINNED;
        c.mcc = (pc.mcc > 0) ? pc.mcc : carrier_mcc;
        c.mnc = (pc.mcc > 0) ? pc.mnc : carrier_mnc;
        c.pci = static_cast<uint16_t>(pc.pci % (c.rat == KUROKO_RAT_NR ? 1008u : 504u));
        c.band = (pc.band > 0) ? pc.band : (c.rat == KUROKO_RAT_NR ? active_reg->nr_band : active_reg->lte_band[0]);
        c.tac = (pc.tac > 0) ? pc.tac : 10001u;
        c.arfcn = (pc.arfcn > 0) ? pc.arfcn : (c.rat == KUROKO_RAT_NR ? active_reg->nr_arfcn : active_reg->lte_arfcn[0]);
        c.bandwidth_khz = (c.rat == KUROKO_RAT_NR) ? 100000 : 20000;
        c.ci = (pc.ci > 0) ? pc.ci : (100000ULL + i);
        c.rsrp_dbm = rsrp;
        c.rsrq_db = std::clamp(-6 - static_cast<int32_t>((-65 - rsrp) / 5), -19, -5);
        c.sinr_db = std::clamp(24 - static_cast<int32_t>((-65 - rsrp) / 2), -5, 29);
        c.ta = std::clamp(static_cast<int32_t>(std::lround(dist_m / (c.rat == KUROKO_RAT_NR ? 39.06 : 78.12))), 0, 1282);
        c.timestamp_ns = boot_ns;
        cell_cands.push_back(c);
    }

    if (cell_auto) {
        constexpr double kCellGridM = 450.0;
        const double dlat = kCellGridM / kMetersPerDegLat;
        const int64_t gy0 = static_cast<int64_t>(std::floor(lat_deg / dlat));

        for (int64_t dy = -1; dy <= 1; ++dy) {
            const int64_t gy = gy0 + dy;
            const double row_lat = (static_cast<double>(gy) + 0.5) * dlat;
            const double cos_row = std::max(0.15, std::cos(row_lat * kDegToRad));
            const double dlon = kCellGridM / (kMetersPerDegLat * cos_row);
            const int64_t gx0 = static_cast<int64_t>(std::floor(lon_deg / dlon));

            for (int64_t dx = -1; dx <= 1; ++dx) {
                const int64_t gx = gx0 + dx;
                const uint64_t h0 = grid_hash(gx, gy, 0x43454C4C5F544F57ULL);
                const uint64_t h1 = splitmix64(h0 ^ 0xA5A5A5A55A5A5A5AULL);

                const double t_lat = (static_cast<double>(gy) + 0.15 + 0.70 * hash_unit(h0)) * dlat;
                const double t_lon = (static_cast<double>(gx) + 0.15 + 0.70 * hash_unit(h1)) * dlon;
                const double dist_m = haversine_m(lat_deg, lon_deg, t_lat, t_lon);

                // Cost-231 / urban macro log-distance path loss + per-tower shadow offset + OU fade
                const float shadow_db = static_cast<float>((static_cast<int64_t>((h1 >> 24) & 0xFu) - 8) * 0.75);
                const float tower_jitter = ou_cell_fade_ + 0.7f * rand_normal();
                const double rsrp_d = -58.0 - 31.0 * std::log10(std::max(dist_m, 20.0) / 20.0) + shadow_db + tower_jitter;
                const int32_t rsrp = std::clamp(static_cast<int32_t>(std::lround(rsrp_d)), -124, -55);

                // Determine RAT (LTE vs 5G NR mix when prefer_5g)
                const bool is_nr = prefer_5g && (((dx == 0 && dy == 0) || ((h0 >> 40) & 1u) != 0));
                const uint8_t rat = is_nr ? KUROKO_RAT_NR : KUROKO_RAT_LTE;

                // Regional TAC quantized at ~8x8 grid (~3.6 km tracking area)
                const uint64_t tac_h = grid_hash(gx >> 3, gy >> 3, 0x5441435F41524541ULL);
                const uint32_t tac = 1000u + static_cast<uint32_t>(tac_h % 60000u);

                KurokoCell c{};
                c.rat = rat;
                c.registered = 0;
                c.mnc_digits = mnc_digits;
                c.flags = 0;
                c.mcc = carrier_mcc;
                c.mnc = carrier_mnc;
                c.tac = tac;
                if (is_nr) {
                    c.pci = static_cast<uint16_t>((h0 >> 16) % 1008u);
                    c.band = active_reg->nr_band;
                    c.arfcn = active_reg->nr_arfcn;
                    c.bandwidth_khz = 100000;
                    // 36-bit NR Cell Identity (gNB ID + sector)
                    c.ci = (1ULL << 24) + (h1 & 0x0000000FFFFFFFFFULL);
                    if (c.ci == 0) c.ci = 1ULL;
                } else {
                    const size_t bidx = static_cast<size_t>((h0 >> 32) & 1u);
                    c.pci = static_cast<uint16_t>((h0 >> 16) % 504u);
                    c.band = active_reg->lte_band[bidx];
                    c.arfcn = active_reg->lte_arfcn[bidx];
                    c.bandwidth_khz = 20000;
                    // 28-bit LTE ECI (20-bit eNB + 8-bit sector)
                    c.ci = 0x00100000ULL + (h1 & 0x0FEFFFFFULL);
                }
                c.rsrp_dbm = rsrp;
                c.rsrq_db = std::clamp(-6 - static_cast<int32_t>((-65 - rsrp) / 5), -19, -5);
                c.sinr_db = std::clamp(23 - static_cast<int32_t>((-65 - rsrp) / 2), -5, 28);
                c.ta = std::clamp(static_cast<int32_t>(std::lround(dist_m / (is_nr ? 39.06 : 78.12))), 0, 1282);
                c.timestamp_ns = boot_ns;
                cell_cands.push_back(c);
            }
        }
    }

    // Sort candidates: user-pinned first only as tie-breaker; pick serving with 3 dB handover hysteresis
    if (!cell_cands.empty()) {
        std::sort(cell_cands.begin(), cell_cands.end(), [](const KurokoCell& a, const KurokoCell& b) {
            bool a_pin = (a.flags & KUROKO_CELL_FLAG_USER_PINNED) != 0;
            bool b_pin = (b.flags & KUROKO_CELL_FLAG_USER_PINNED) != 0;
            if (a_pin != b_pin) return a_pin > b_pin;
            return a.rsrp_dbm > b.rsrp_dbm;
        });

        size_t best_idx = 0;
        int32_t best_rsrp = cell_cands[0].rsrp_dbm;
        // If no user-pinned cell is forcing serving, apply 3 dB hysteresis on previous serving_ci_
        bool top_is_pinned = (cell_cands[0].flags & KUROKO_CELL_FLAG_USER_PINNED) != 0;
        if (!top_is_pinned && serving_ci_ != 0) {
            for (size_t i = 0; i < cell_cands.size(); ++i) {
                if (cell_cands[i].ci == serving_ci_ && cell_cands[i].rat == serving_rat_) {
                    if (cell_cands[i].rsrp_dbm >= best_rsrp - 3) {
                        best_idx = i;
                    }
                    break;
                }
            }
        }
        if (best_idx > 0) {
            std::swap(cell_cands[0], cell_cands[best_idx]);
        }
        cell_cands[0].registered = 1;
        serving_ci_ = cell_cands[0].ci;
        serving_rat_ = cell_cands[0].rat;
    }

    const uint32_t cell_out_n = static_cast<uint32_t>(std::min<size_t>(cell_cands.size(), KUROKO_MAX_CELLS));
    std::memset(shm->cells, 0, sizeof(shm->cells));
    for (uint32_t i = 0; i < cell_out_n; ++i) {
        shm->cells[i] = cell_cands[i];
    }

    // 3. Synthesize Wi-Fi AP List (user-pinned + 80m micro-grid 3x3 neighborhood within 95m)
    std::vector<KurokoWifiAp> wifi_cands;
    wifi_cands.reserve(24);
    const uint64_t boot_us = boot_ns / 1000ULL;
    bool has_connected_ap = false;

    for (size_t i = 0; i < pinned_wifis_.size(); ++i) {
        const auto& pw = pinned_wifis_[i];
        const bool always_follow = (pw.pin_lat > 90.0 || pw.pin_lon > 180.0);
        double dist_m = always_follow ? 5.0 : haversine_m(lat_deg, lon_deg, pw.pin_lat, pw.pin_lon);
        if (!always_follow && dist_m > 150.0) continue;

        float fade = ou_wifi_fade_ * 0.6f + 0.9f * rand_normal();
        int32_t rssi = pw.rssi_dbm;
        if (!always_follow) {
            double atten = 26.0 * std::log10(std::max(dist_m, 2.0) / 2.0);
            rssi = static_cast<int32_t>(std::lround(static_cast<double>(pw.rssi_dbm) - atten + fade));
        } else {
            rssi = static_cast<int32_t>(std::lround(static_cast<double>(pw.rssi_dbm) + fade));
        }
        rssi = std::clamp(rssi, -95, -28);

        KurokoWifiAp ap{};
        std::memcpy(ap.bssid, pw.bssid, 6);
        uint8_t fl = KUROKO_WIFI_FLAG_USER_PINNED;
        if ((pw.flags & KUROKO_WIFI_FLAG_CONNECTED_AP) != 0) {
            fl |= KUROKO_WIFI_FLAG_CONNECTED_AP;
            has_connected_ap = true;
        }
        if (pw.frequency_mhz >= 5000) fl |= KUROKO_WIFI_FLAG_IS_5GHZ;
        ap.flags = fl;

        size_t slen = utf8_safe_prefix_len(pw.ssid.data(), pw.ssid.size(), 32);
        ap.ssid_len = static_cast<uint8_t>(slen);
        if (slen > 0) std::memcpy(ap.ssid, pw.ssid.data(), slen);
        copy_cstr_padded(ap.capabilities, sizeof(ap.capabilities), "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]");
        ap.frequency_mhz = (pw.frequency_mhz >= 2412) ? pw.frequency_mhz : 2437;
        ap.rssi_dbm = rssi;
        wifi_channel_params(ap.frequency_mhz, &ap.channel_width, &ap.center_freq0_mhz);
        ap.timestamp_us = boot_us > (i * 1500ULL) ? (boot_us - i * 1500ULL) : boot_us;
        copy_cstr_padded(ap.country_iso, sizeof(ap.country_iso), iso_upper.c_str());
        wifi_cands.push_back(ap);
    }

    if (wifi_auto) {
        constexpr double kWifiGridM = 80.0;
        const double dlat = kWifiGridM / kMetersPerDegLat;
        const int64_t gy0 = static_cast<int64_t>(std::floor(lat_deg / dlat));

        for (int64_t dy = -1; dy <= 1; ++dy) {
            const int64_t gy = gy0 + dy;
            const double row_lat = (static_cast<double>(gy) + 0.5) * dlat;
            const double cos_row = std::max(0.15, std::cos(row_lat * kDegToRad));
            const double dlon = kWifiGridM / (kMetersPerDegLat * cos_row);
            const int64_t gx0 = static_cast<int64_t>(std::floor(lon_deg / dlon));

            for (int64_t dx = -1; dx <= 1; ++dx) {
                const int64_t gx = gx0 + dx;
                for (uint64_t sub = 0; sub < 2; ++sub) {
                    const uint64_t h0 = grid_hash(gx, gy, 0x574946495F415053ULL + sub * 0x1337ULL);
                    const uint64_t h1 = splitmix64(h0 ^ 0x517CC1B727220A95ULL);

                    const double ap_lat = (static_cast<double>(gy) + 0.08 + 0.84 * hash_unit(h0)) * dlat;
                    const double ap_lon = (static_cast<double>(gx) + 0.08 + 0.84 * hash_unit(h1)) * dlon;
                    const double dist_m = haversine_m(lat_deg, lon_deg, ap_lat, ap_lon);
                    if (dist_m > 95.0) continue;

                    const bool is_5g = (sub == 1);
                    const int32_t freq = is_5g
                                             ? active_reg->wifi_5g_freqs[(h0 >> 24) % active_reg->wifi_5g_count]
                                             : kWifi2g[(h0 >> 24) % 3];

                    const double path_loss = (is_5g ? 30.0 : 27.0) * std::log10(std::max(dist_m, 2.0) / 2.0);
                    const float fade = ou_wifi_fade_ + 1.1f * rand_normal();
                    const int32_t rssi = std::clamp(
                        static_cast<int32_t>(std::lround(-39.0 - path_loss + fade)), -94, -34);

                    KurokoWifiAp ap{};
                    const size_t oui_idx = static_cast<size_t>((h0 >> 32) % kNumVendorOuis);
                    ap.bssid[0] = kVendorOuis[oui_idx][0] & 0xFCu; // guarantee unicast + globally administered
                    ap.bssid[1] = kVendorOuis[oui_idx][1];
                    ap.bssid[2] = kVendorOuis[oui_idx][2];
                    ap.bssid[3] = static_cast<uint8_t>((h1 >> 8) & 0xFFu);
                    ap.bssid[4] = static_cast<uint8_t>((h1 >> 16) & 0xFFu);
                    ap.bssid[5] = static_cast<uint8_t>((h1 >> 24) & 0xFFu);
                    ap.flags = is_5g ? KUROKO_WIFI_FLAG_IS_5GHZ : 0u;

                    const char* prefix = active_reg->ssid_prefixes[(h0 >> 40) % active_reg->ssid_count];
                    char ssid_buf[36]{};
                    uint16_t suffix = static_cast<uint16_t>(h1 & 0xFFFFu);
                    if (is_5g) {
                        std::snprintf(ssid_buf, sizeof(ssid_buf), "%s%04X_5G", prefix, suffix);
                    } else {
                        std::snprintf(ssid_buf, sizeof(ssid_buf), "%s%04X", prefix, suffix);
                    }
                    size_t slen = utf8_safe_prefix_len(ssid_buf, std::strlen(ssid_buf), 32);
                    ap.ssid_len = static_cast<uint8_t>(slen);
                    if (slen > 0) std::memcpy(ap.ssid, ssid_buf, slen);

                    const char* caps = ((h0 >> 48) & 1u)
                                           ? "[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS]"
                                           : "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]";
                    copy_cstr_padded(ap.capabilities, sizeof(ap.capabilities), caps);
                    ap.frequency_mhz = freq;
                    ap.rssi_dbm = rssi;
                    wifi_channel_params(freq, &ap.channel_width, &ap.center_freq0_mhz);
                    uint64_t age_us = (h1 >> 32) % 35000ULL;
                    ap.timestamp_us = (boot_us > age_us) ? (boot_us - age_us) : boot_us;
                    copy_cstr_padded(ap.country_iso, sizeof(ap.country_iso), iso_upper.c_str());
                    wifi_cands.push_back(ap);
                }
            }
        }
    }

    std::sort(wifi_cands.begin(), wifi_cands.end(), [](const KurokoWifiAp& a, const KurokoWifiAp& b) {
        bool a_conn = (a.flags & KUROKO_WIFI_FLAG_CONNECTED_AP) != 0;
        bool b_conn = (b.flags & KUROKO_WIFI_FLAG_CONNECTED_AP) != 0;
        if (a_conn != b_conn) return a_conn > b_conn;
        bool a_pin = (a.flags & KUROKO_WIFI_FLAG_USER_PINNED) != 0;
        bool b_pin = (b.flags & KUROKO_WIFI_FLAG_USER_PINNED) != 0;
        if (a_pin != b_pin) return a_pin > b_pin;
        return a.rssi_dbm > b.rssi_dbm;
    });

    if (wifi_spoof_conn && !has_connected_ap && !wifi_cands.empty()) {
        wifi_cands[0].flags |= KUROKO_WIFI_FLAG_CONNECTED_AP;
    }

    const uint32_t wifi_out_n = static_cast<uint32_t>(std::min<size_t>(wifi_cands.size(), KUROKO_MAX_WIFI_APS));
    std::memset(shm->wifi_aps, 0, sizeof(shm->wifi_aps));
    for (uint32_t i = 0; i < wifi_out_n; ++i) {
        shm->wifi_aps[i] = wifi_cands[i];
    }

    // 4. Synthesize BLE / iBeacon Devices (user-pinned + optional ambient BLE pool)
    std::vector<KurokoBleDevice> ble_cands;
    ble_cands.reserve(16);

    for (size_t i = 0; i < pinned_beacons_.size(); ++i) {
        const auto& pb = pinned_beacons_[i];
        const bool always_follow = (pb.pin_lat > 90.0 || pb.pin_lon > 180.0);
        double dist_m = always_follow ? 1.5 : haversine_m(lat_deg, lon_deg, pb.pin_lat, pb.pin_lon);
        if (!always_follow && dist_m > 60.0) continue;

        float fade = ou_ble_fade_ * 0.6f + 0.9f * rand_normal();
        int32_t rssi = always_follow
                           ? static_cast<int32_t>(std::lround(static_cast<double>(pb.tx_power_dbm) - 4.0 + fade))
                           : static_cast<int32_t>(std::lround(
                                 static_cast<double>(pb.tx_power_dbm) - 22.0 * std::log10(std::max(dist_m, 1.0)) + fade));
        rssi = std::clamp(rssi, -98, -38);

        KurokoBleDevice d{};
        std::memcpy(d.mac, pb.mac, 6);
        d.addr_type = ((d.mac[0] & 0xC0u) == 0xC0u) ? KUROKO_BLE_ADDR_RANDOM : KUROKO_BLE_ADDR_PUBLIC;
        d.device_type = (pb.device_type == KUROKO_BLE_TYPE_IBEACON) ? KUROKO_BLE_TYPE_IBEACON : KUROKO_BLE_TYPE_GENERIC;
        d.flags = KUROKO_BLE_FLAG_USER_PINNED;
        if (d.device_type == KUROKO_BLE_TYPE_GENERIC) {
            d.flags |= KUROKO_BLE_FLAG_CONNECTABLE;
        }
        size_t nlen = utf8_safe_prefix_len(pb.name.data(), pb.name.size(), 24);
        d.name_len = static_cast<uint8_t>(nlen);
        if (nlen > 0) std::memcpy(d.name, pb.name.data(), nlen);
        d.tx_power_dbm = pb.tx_power_dbm;
        d.rssi_dbm = rssi;
        d.major = pb.major;
        d.minor = pb.minor;
        std::memcpy(d.uuid, pb.uuid, 16);

        if (d.device_type == KUROKO_BLE_TYPE_IBEACON) {
            build_ibeacon_ad(d.uuid, d.major, d.minor, d.tx_power_dbm, d.adv_bytes, &d.adv_len);
        } else {
            build_generic_ble_ad(d.name, d.name_len, d.adv_bytes, &d.adv_len);
        }
        ble_cands.push_back(d);
    }

    if (ble_ambient) {
        constexpr double kBleGridM = 40.0;
        const double dlat = kBleGridM / kMetersPerDegLat;
        const int64_t gy0 = static_cast<int64_t>(std::floor(lat_deg / dlat));

        for (int64_t dy = -1; dy <= 1; ++dy) {
            const int64_t gy = gy0 + dy;
            const double row_lat = (static_cast<double>(gy) + 0.5) * dlat;
            const double cos_row = std::max(0.15, std::cos(row_lat * kDegToRad));
            const double dlon = kBleGridM / (kMetersPerDegLat * cos_row);
            const int64_t gx0 = static_cast<int64_t>(std::floor(lon_deg / dlon));

            for (int64_t dx = -1; dx <= 1; ++dx) {
                const int64_t gx = gx0 + dx;
                const uint64_t h0 = grid_hash(gx, gy, 0x424C455F414D4249ULL);
                if ((h0 & 3u) == 0u) continue; // ~75% occupancy -> 2..5 ambient BLE devices in 35m

                const uint64_t h1 = splitmix64(h0 ^ 0x94D049BB133111EBULL);
                const double b_lat = (static_cast<double>(gy) + 0.1 + 0.8 * hash_unit(h0)) * dlat;
                const double b_lon = (static_cast<double>(gx) + 0.1 + 0.8 * hash_unit(h1)) * dlon;
                const double dist_m = haversine_m(lat_deg, lon_deg, b_lat, b_lon);
                if (dist_m > 35.0) continue;

                const int8_t tx_pwr = -59;
                const float fade = ou_ble_fade_ + 1.0f * rand_normal();
                const int32_t rssi = std::clamp(
                    static_cast<int32_t>(std::lround(static_cast<double>(tx_pwr) - 22.0 * std::log10(std::max(dist_m, 1.5)) + fade)),
                    -96, -48);

                KurokoBleDevice d{};
                // Valid BLE Random Static address (top 2 MSBs == 11 per BT Core Spec)
                d.mac[0] = static_cast<uint8_t>((h0 & 0x3Fu) | 0xC0u);
                d.mac[1] = static_cast<uint8_t>((h0 >> 8) & 0xFFu);
                d.mac[2] = static_cast<uint8_t>((h0 >> 16) & 0xFFu);
                d.mac[3] = static_cast<uint8_t>((h1 >> 8) & 0xFFu);
                d.mac[4] = static_cast<uint8_t>((h1 >> 16) & 0xFFu);
                d.mac[5] = static_cast<uint8_t>((h1 >> 24) & 0xFFu);
                d.addr_type = KUROKO_BLE_ADDR_RANDOM;
                d.device_type = KUROKO_BLE_TYPE_GENERIC;
                d.flags = KUROKO_BLE_FLAG_CONNECTABLE;
                d.tx_power_dbm = tx_pwr;
                d.rssi_dbm = rssi;

                const char* base_name = kAmbientBleNames[(h0 >> 32) % kNumAmbientBleNames];
                char nbuf[28]{};
                std::snprintf(nbuf, sizeof(nbuf), "%s-%02X%02X", base_name, d.mac[4], d.mac[5]);
                size_t nlen = utf8_safe_prefix_len(nbuf, std::strlen(nbuf), 24);
                d.name_len = static_cast<uint8_t>(nlen);
                if (nlen > 0) std::memcpy(d.name, nbuf, nlen);
                build_generic_ble_ad(d.name, d.name_len, d.adv_bytes, &d.adv_len);
                ble_cands.push_back(d);
            }
        }
    }

    std::sort(ble_cands.begin(), ble_cands.end(), [](const KurokoBleDevice& a, const KurokoBleDevice& b) {
        bool a_pin = (a.flags & KUROKO_BLE_FLAG_USER_PINNED) != 0;
        bool b_pin = (b.flags & KUROKO_BLE_FLAG_USER_PINNED) != 0;
        if (a_pin != b_pin) return a_pin > b_pin;
        return a.rssi_dbm > b.rssi_dbm;
    });

    const uint32_t ble_out_n = static_cast<uint32_t>(std::min<size_t>(ble_cands.size(), KUROKO_MAX_BLE_DEVS));
    std::memset(shm->ble_devs, 0, sizeof(shm->ble_devs));
    for (uint32_t i = 0; i < ble_out_n; ++i) {
        shm->ble_devs[i] = ble_cands[i];
    }

    // 5. Publish KurokoRfHeader into SHM
    KurokoRfHeader& hdr = shm->rf_hdr;
    std::memset(&hdr, 0, sizeof(hdr));
    hdr.rf_epoch = ++rf_epoch_;
    hdr.cell_count = cell_out_n;
    hdr.wifi_count = wifi_out_n;
    hdr.ble_count = ble_out_n;
    hdr.rf_flags = rf_flags;
    hdr.carrier_mcc = carrier_mcc;
    hdr.carrier_mnc = carrier_mnc;
    hdr.mnc_digits = mnc_digits;
    copy_cstr_padded(hdr.country_iso, sizeof(hdr.country_iso), iso_lower.c_str());
    copy_cstr_padded(hdr.wifi_country_iso, sizeof(hdr.wifi_country_iso), iso_upper.c_str());
    copy_cstr_padded(hdr.carrier_alpha, sizeof(hdr.carrier_alpha), carrier_alpha.c_str());

    // 6. Update telemetry snapshot for STATUS JSON
    telemetry_.country_iso = iso_lower;
    telemetry_.mcc = carrier_mcc;
    telemetry_.mnc = carrier_mnc;
    telemetry_.cell_count = cell_out_n;
    telemetry_.wifi_count = wifi_out_n;
    telemetry_.ble_count = ble_out_n;
    if (cell_out_n > 0) {
        const KurokoCell& srv = shm->cells[0];
        const char* rat_str = (srv.rat == KUROKO_RAT_NR) ? "NR" : "LTE";
        char sbuf[96]{};
        if (srv.mnc_digits == 3) {
            std::snprintf(sbuf, sizeof(sbuf), "%s %03u-%03u TAC:%u CI:%llu %ddBm",
                          rat_str, static_cast<unsigned>(srv.mcc), static_cast<unsigned>(srv.mnc),
                          static_cast<unsigned>(srv.tac), static_cast<unsigned long long>(srv.ci),
                          static_cast<int>(srv.rsrp_dbm));
        } else {
            std::snprintf(sbuf, sizeof(sbuf), "%s %03u-%02u TAC:%u CI:%llu %ddBm",
                          rat_str, static_cast<unsigned>(srv.mcc), static_cast<unsigned>(srv.mnc),
                          static_cast<unsigned>(srv.tac), static_cast<unsigned long long>(srv.ci),
                          static_cast<int>(srv.rsrp_dbm));
        }
        telemetry_.serving_summary = sbuf;
    } else {
        telemetry_.serving_summary = "NONE";
    }
}

} // namespace kuroko
