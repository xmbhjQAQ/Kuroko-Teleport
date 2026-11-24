#pragma once

#include "kuroko_shm.h"

#include <cstdint>
#include <string>
#include <vector>

namespace kuroko {

struct RfConfig {
    uint32_t rf_flags = KUROKO_RF_FLAG_CELL_AUTO | KUROKO_RF_FLAG_WIFI_AUTO |
                        KUROKO_RF_FLAG_BLE_AMBIENT | KUROKO_RF_FLAG_CELL_PREFER_5G;
    uint16_t carrier_mcc = 0;       // 0 = auto-select from virtual WGS-84 coordinates
    uint16_t carrier_mnc = 0;
    uint8_t  mnc_digits = 2;        // 2 or 3
    std::string country_iso;        // empty = AUTO (lowercase 2-char, e.g. "cn", "jp", "us")
    std::string carrier_alpha;      // empty = AUTO
};

struct PinnedCell {
    uint8_t  rat = KUROKO_RAT_LTE;  // 1=LTE, 2=NR
    uint16_t mcc = 0;
    uint16_t mnc = 0;
    uint8_t  mnc_digits = 2;
    uint32_t tac = 0;
    uint64_t ci = 0;
    uint16_t pci = 0;
    uint32_t arfcn = 0;
    uint16_t band = 0;
    int32_t  rsrp_dbm = -78;
    double   pin_lat = 999.0;       // > 90.0 means always-follow
    double   pin_lon = 999.0;
};

struct PinnedWifiAp {
    uint8_t     bssid[6] = {};
    int32_t     frequency_mhz = 2437;
    int32_t     rssi_dbm = -52;
    uint8_t     flags = 0;          // bit0=connected_ap
    double      pin_lat = 999.0;    // > 90.0 means always-follow
    double      pin_lon = 999.0;
    std::string ssid;
};

struct PinnedBleBeacon {
    uint8_t     mac[6] = {};
    uint8_t     device_type = KUROKO_BLE_TYPE_IBEACON; // 0=GENERIC_BLE, 1=IBEACON
    uint8_t     uuid[16] = {};
    uint16_t    major = 0;
    uint16_t    minor = 0;
    int8_t      tx_power_dbm = -59;
    double      pin_lat = 999.0;    // > 90.0 means always-follow
    double      pin_lon = 999.0;
    std::string name;
};

struct RfTelemetry {
    std::string country_iso = "cn";
    uint16_t    mcc = 460;
    uint16_t    mnc = 0;
    uint32_t    cell_count = 0;
    uint32_t    wifi_count = 0;
    uint32_t    ble_count = 0;
    std::string serving_summary = "NONE";
};

class RfSynthesizer {
public:
    RfSynthesizer();

    void set_config(const RfConfig& cfg);
    void set_pinned_cells(const std::vector<PinnedCell>& cells);
    void set_pinned_wifis(const std::vector<PinnedWifiAp>& wifis);
    void set_pinned_beacons(const std::vector<PinnedBleBeacon>& beacons);

    void update(double lat_deg, double lon_deg, uint64_t boot_ns, double dt_sec, KurokoShm* shm);
    RfTelemetry get_telemetry() const { return telemetry_; }

private:
    RfConfig config_{};
    std::vector<PinnedCell> pinned_cells_;
    std::vector<PinnedWifiAp> pinned_wifis_;
    std::vector<PinnedBleBeacon> pinned_beacons_;

    uint32_t rf_epoch_ = 0;
    uint64_t serving_ci_ = 0;
    uint8_t  serving_rat_ = 0;
    float    ou_cell_fade_ = 0.0f;
    float    ou_wifi_fade_ = 0.0f;
    float    ou_ble_fade_ = 0.0f;
    uint64_t rng_state_ = 0xD1B54A32D192ED03ULL;
    RfTelemetry telemetry_{};

    float rand_normal();
};

} // namespace kuroko
