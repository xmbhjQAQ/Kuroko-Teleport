<p align="center">
  <img src="docs/assets/kuroko-teleport-icon.png" width="112" alt="Kuroko Teleport" />
</p>
<p align="center"><em>Teleport!!!</em></p>
<h1 align="center">Kuroko Teleport</h1>
<p align="center">
  Per-app system-side GPS, pedometer &amp; IMU simulation engine for Android — zero target app injection.
  <br/>
  <a href="README.md">简体中文</a> | English
</p>

---

## Key Features

- **Zero Target-App Injection & Per-App Isolation**: All location, network sanitization, and sensor simulation happens strictly on the Android system side per calling UID — target app processes and memory are never hooked or modified. Isolate up to 32 target apps simultaneously while unselected apps and the controller itself continue receiving genuine hardware GPS and sensors.
- **Static Pin & Realistic Route Motion**: Built-in open-source map (no Google Play Services required) with address search, saved pins, and reusable route presets (JSON import/export). Supports smooth curved paths, loop / lap-count / target-distance completion, natural pace variation, and hand-swing or pocket carry postures.
- **Stationary Indoor Pedometer & IMU Sync**: Delivers synthetic GPS, multi-constellation GNSS/NMEA, monotonically increasing steps, and phase-synchronized motion sensors even while your phone rests completely still indoors — complete with a built-in **Sensor Self-Test** in Settings.
- **Per-App Wi-Fi & Cell Anti-Leak Isolation**: Choose between **Strict Cross-City** (hides nearby Wi-Fi scans/info, cell towers, and raw GNSS measurements) and **Local Outdoor** (hides Wi-Fi while preserving local cell towers) independently for each target app.
- **Background Floating HUD & Screen-Capture Protection**: Edge-snapping floating capsule and expandable mini control panel that auto-appears when backgrounded, notification quick controls, and a dynamic `FLAG_SECURE` screen-capture hiding toggle.
- **All-in-One Install, Boot-Guard, Diagnostics & Live Language Switch**: A single KernelSU module ZIP auto-installs the companion App and immediately reclaims storage, verifies strict cross-component version alignment, protects against bootloops via `boot-guard`, includes a built-in Diagnostics & Logs center (auto health checks + redacted log export), and supports instant Simplified Chinese / English switching.

---

## Screenshots & Demo

<table>
  <tr>
    <td align="center" width="33%">
      <img src="docs/assets/preview-motion.jpg" width="250" alt="Route Motion UI" /><br/>
      <sub><strong>Route Motion &amp; Waypoints</strong></sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/assets/preview-routes.jpg" width="250" alt="Saved Route Presets" /><br/>
      <sub><strong>Saved Route Presets &amp; Export</strong></sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/assets/demo-hud.gif" width="250" alt="Floating HUD Live Demo" /><br/>
      <sub><strong>Floating HUD Live Demo (GIF)</strong></sub>
    </td>
  </tr>
</table>

---

## Requirements

| Component | Requirement | Notes |
| :--- | :--- | :--- |
| **Architecture** | `arm64-v8a` | 64-bit ARM devices only |
| **Android Version** | **Android 12+** (`minSdk 31`) | Location & network isolation supports Android 12+; **Note: native sensor/pedometer hooks are currently verified on Android 17 — multi-version Android 12–16 support is on the [Roadmap](docs/ROADMAP.md)** |
| **Root Framework** | **KernelSU / KSU2** | Installs the All-in-One module, starts the background daemon at boot, and applies SELinux rules |
| **Zygisk** | **Zygisk Next** (API v4) | Powers system-side pedometer and IMU simulation |
| **Xposed** | **LSPosed** | Check **ONLY** `System Framework (android)` and `Phone Services (com.android.phone)` — **never check target apps** |

---

## Quick Installation & Usage

> [!IMPORTANT]
> **NEVER check any target third-party app in LSPosed.** Kuroko Teleport isolates apps by UID inside system services — only System Framework and Phone Services should be enabled.

1. **Flash the All-in-One Module ZIP**
   - Push `dist/kuroko-teleport-ksu-module.zip` to your device and install it in **KernelSU Manager → Modules → Install from local**.
   - The installer verifies and installs the companion App (`com.kuroko.teleport`), then immediately deletes the bundled installer payload to reclaim storage. If flashed offline (e.g., in Recovery), installation completes automatically after boot.
   - Make sure **Zygisk Next** is enabled.
2. **Configure LSPosed Scope (First Install Only)**
   - Open **LSPosed Manager → Modules → Kuroko Teleport** and toggle the module on.
   - Check **ONLY** these two system entries:
     - `[x]` **System Framework (`android`)**
     - `[x]` **Phone Services (`com.android.phone`)**
3. **Reboot**
   - Reboot your device so the background daemon, Zygisk sensor module, and LSPosed system hooks initialize together.
4. **Launch & Start Simulating**
   - Open **Kuroko Teleport** and grant Root access on first launch (used solely to read the per-boot authentication token).
   - Verify all three status indicators show green **`L✓ P✓ S✓`** (you can also switch languages or run **Sensor Self-Test** inside **Settings**).
   - Open **Target Apps** at the bottom to pick apps and isolation policies, then switch to **Static Pin** or **Route Motion** to start. After reaching your target laps or distance, the engine holds in place automatically — save your session in the target app before tapping **Stop**.

---

## FAQ

<details>
<summary><strong>Why shouldn't I check target apps in LSPosed?</strong></summary>

Kuroko Teleport is designed around zero target-app injection: all spoofing and isolation happens inside `system_server` and `com.android.phone` per calling UID. Checking target apps is unnecessary and breaks zero-injection stealth, increasing exposure to in-app environment detection.

</details>

<details>
<summary><strong>Will updating or uninstalling the module delete my saved routes, pins, or settings?</strong></summary>

No. Flashing an update in place preserves all saved routes, pins, preferences, and LSPosed scope. Uninstalling the module in KernelSU only stops the daemon and removes root runtime files — it **never uninstalls the companion App**, keeping all user data intact.

</details>

<details>
<summary><strong>What if my device fails to boot? (<code>boot-guard</code> recovery & emergency disable)</strong></summary>

`boot-guard` tracks each boot attempt and resets to `0` once boot completes. If two consecutive boots fail, it automatically trips and skips all hooks so the device boots cleanly. You can also disable the module manually via ADB:

```bash
adb shell su -c "touch /data/adb/modules/kuroko_teleport/disable && reboot"
```

</details>

<details>
<summary><strong>Why does the Floating HUD close when running in the background?</strong></summary>

Open your device's **App Info → Battery** settings for **Kuroko Teleport** and set battery optimization to **Unrestricted**, and allow notification + overlay permissions so the system does not kill the floating HUD during long background sessions.

</details>

<details>
<summary><strong>How do I troubleshoot issues or report a bug?</strong></summary>

Open **Settings → Diagnostics &amp; Logs** (or tap **View Logs** from the offline / version-lock dialog) to run the built-in Auto Health Checks and inspect categorized logs. When opening a GitHub Issue, tap **Copy Summary** to paste the redacted Markdown report, and optionally attach the exported ZIP via **Share ZIP / Save ZIP** (auth tokens, API keys, routes/coordinates, and unrelated app logs are always stripped automatically, with target app package anonymization enabled by default).

</details>

---

## Roadmap / TODO

- [x] System-side GPS / GNSS / NMEA, stationary pedometer & IMU sync, Wi-Fi & cell anti-leak, floating HUD, CHS/EN live switching, and redacted diagnostics export
- [ ] Multi-version Android 12–16 native sensor & pedometer compatibility
- [ ] GMS FusedLocationProvider (FLP) support
- [ ] Virtual 4G/5G cell tower spoofing
- [ ] Virtual nearby Wi-Fi AP list spoofing
- [ ] Bluetooth / BLE scan isolation & virtual beacon spoofing
- [ ] GPX / KML route import & 360° floating joystick

See [docs/TODOLIST.md](docs/TODOLIST.md) for the full engineering checklist.

---

## Build from Source, Translations & Developer Docs

### Building

Requires **OpenJDK 21**, **Android SDK / NDK**, and **Python 3.10+**. Run from the repository root:

```bash
# 1. Build the unified Controller & LSPosed APK (use .\gradlew.bat on Windows)
./gradlew :app:assembleDebug

# 2. Compile native binaries and package the All-in-One module (outputs dist/kuroko-teleport-ksu-module.zip)
python build_module.py
```

### Contributing Translations

Localization is fully data-driven with zero hardcoded language branches: add `app/src/main/res/values-<bcp47>/strings*.xml`, register the locale in `locales_config.xml` and `AppLocaleManager.kt`, and optionally add a matching installer dictionary under `module_template/lang/` (see the architecture doc for details).

### Technical Documentation

- **[Architecture (`docs/ARCHITECTURE.md`)](docs/ARCHITECTURE.md)** — System architecture, shared-memory & IPC protocol, pure PLT/GOT native sensor injection, and kinematics / IMU mathematical models *(Chinese)*.
- **[Roadmap (`docs/ROADMAP.md`)](docs/ROADMAP.md)** — Planned milestones including virtual cell towers, virtual Wi-Fi AP lists, Bluetooth/BLE isolation & spoofing, GMS FLP, and multi-version Android 12–17 support *(Chinese)*.
- **[Engineering Checklist (`docs/TODOLIST.md`)](docs/TODOLIST.md)** — Subsystem-level completed milestones and upcoming engineering tasks *(Chinese)*.

---

## Disclaimer

This project is intended solely for software development, testing, location-security research, and personal privacy study. Users are responsible for complying with all applicable laws and third-party terms of service.
