# Kuroko Teleport 工程待办清单（TODOLIST）

本文档按子系统追踪 **Kuroko Teleport**（当前统一基线：**`v0.7.0` (`versionCode=13`, `IPC=3`, `SHM=2`) All-in-One + 全球地理感知射频仿真（基站 / Wi-Fi / 蓝牙 BLE）+ 应用内诊断中心 + Release 更新检查**）的细粒度研发、真机验证与后续演进任务。已完成项标注 `[x]`，待真机联调与后续迭代项标注 `[ ]`。

---

## 一、 Phase 0：真机探针与基线锁定

- [x] 采集 Android 17 (API 37) ARM64 测试设备（HyperOS 4）全只读环境画像
- [x] 拉取并反汇编 `/system/lib64/libsensorservice.so`，确认 16KB 页对齐 (`p_align=0x4000`)、BTI+PAC (`0x3`)、`sizeof(sensors_event_t)=0x68`
- [x] 验证 `libsensorservice.so` 未开 `-Bsymbolic`，锁定纯 PLT/GOT 零 Inline Hook 路线及全部 `R_AARCH64_JUMP_SLOT` 目标符号
- [x] 静态分析 `services.jar`、`miui-services.jar`、`service-wifi.jar`、`service-connectivity.jar`、`TeleService.apk` 与蓝牙 APEX（`/apex/com.android.bt`、`service-bluetooth.jar`、`Bluetooth.apk`）
- [x] 锁定 Android 17 APEX jarjar 重定位类（`com.android.wifi.x...ParceledListSlice`、`android.net.connectivity.com.android.server.ConnectivityService`）、蓝牙服务入口（`BluetoothManagerService`、`AdapterBinder`、`ScanController`/`ScanBinder`）与 HyperOS 4 `GpsProxyManager` / `MiuiBlurLocationManagerImpl`

---

## 二、 统一版本契约与跨语言共享内存协议（`version.properties`, `common/`）

- [x] 维护仓库唯一版本契约 `version.properties`（`RELEASE_VERSION_NAME=0.7.0`, `RELEASE_VERSION_CODE=13`, `SHM_SCHEMA_VERSION=2`, `IPC_PROTOCOL_VERSION=3`, `MIN_COMPAT_MODULE_CODE=13`, `MIN_COMPAT_APP_CODE=13`），统一驱动 CMake、Gradle、`module.prop` 与打包校验
- [x] 升级 16KB 页对齐 `KurokoShm v2` 无锁 Seqlock 内存结构体（`common/include/kuroko_shm.h`，`KUROKO_SHM_MAGIC=0x4F52554B` `"KURO"`，`KUROKO_SHM_VERSION=2u`）：在保留 `0x0000..0x113F` 不变的前提下，于 `0x1140..0x237F` 新增 `KurokoRfHeader` (64B)、`KurokoCell[8]` (512B)、`KurokoWifiAp[16]` (2048B) 与 `KurokoBleDevice[16]` (2048B) 及全量编译期 `static_assert` 偏移校验
- [x] 扩展 32 槽位 `KurokoTarget` 策略位掩码（新增 `SPOOF_WIFI`/`SPOOF_CELL`/`HIDE_BLE`/`SPOOF_BLE` 及预设 `KUROKO_POLICY_ALL_SPOOF=0x3E7`、`KUROKO_POLICY_ALL_STRICT=0x13F`、`KUROKO_POLICY_LOCAL_OUTDOOR=0x02F`）与 `KUROKO_RF_FLAG_*` 控制位
- [x] 扩展 `libbinder_ndk` 合法区间事务码：`KUROKO_BINDER_TX_SET_SHM = 0x004B5243` `'KRC'`、`KUROKO_BINDER_TX_PING = 0x004B5250` `'KRP'`、`KUROKO_BINDER_TX_GET_BT_BINDER = 0x004B5242` `'KRB'`（仅 Root UID 0 查询 `com.android.bluetooth` `AdapterBinder`）、`KUROKO_ACK_OK = 0x4F4B`

---

## 三、 Native 仿真守护进程与全球射频合成器（`native/daemon/` · `v0.7.0`）

- [x] 实现 50Hz 主时钟（`clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME)`）与 Seqlock 原子快照发布
- [x] 实现 Catmull-Rom 样条密采样轨迹插值（闭合循环 Loop、弯道自动减速、目标里程与重复圈数 `max_laps` 达标自动原地停留）
- [x] 实现双时间尺度 OU 跑步配速波动（中期 $\tau=18\text{s}$ 节奏 + 短期 $\tau=4\text{s}$ 微扰）及 TCP 命令 `SET_PACE_VAR <ratio>`
- [x] 实现局部 ENU 坐标 OU 时间相关有色噪声 GPS 漂移（$\tau = 9\text{s}$）与相关水平精度呼吸波动
- [x] 实现单源连续相位生物力学 5 路 IMU 合成器（升级纬度感知偶极子地磁模型 $B_n=31\cos\phi,\;B_u=-62\sin\phi$，兼容南北半球）与 1Hz 18 颗 4 星座 GNSS/NMEA-0183 生成器（`nmea_gnss.cpp`）
- [x] 新增全球地理感知射频合成引擎 `RfSynthesizer`（`native/daemon/rf_synth.{h,cpp}`，1Hz 与 GNSS 同步刷新）：覆盖 CN/HK/TW/JP/KR/SG-SEA/US/CA/UK/DE/FR/EU/AU-NZ/Global 区域运营商与无线法规表；实现 450m 网格 4G/5G 基站（Cost-231 衰落 + OU 波动 + 3 dB 切换迟滞 + TimingAdvance + 用户固定基站）、80m 3×3 微网格 Wi-Fi AP 列表（合规信道/区域 SSID/单播 OUI/连贯 `center_freq0` + 用户固定热点）、以及 30B Apple `0x004C` iBeacon AD 报文与环境 BLE 合成
- [x] 通过 `/proc/self/fd/<g_memfd>`（memfd `"kuroko_shm"`）以 `O_RDONLY` 打开只读句柄 `g_memfd_ro` 进行 Unix `SCM_RIGHTS`（`/dev/kuroko_root.sock`）与三服务 Binder（`location` / `phone` / 可选 `com.android.bluetooth` `AdapterBinder`）跨进程传递
- [x] 升级 `127.0.0.1:28765` 鉴权 TCP IPC v3（18 命令：新增 `SET_RF_CONFIG`、`SET_RF_CELLS`、`SET_RF_WIFIS`、`SET_RF_BEACONS`；`STATUS` JSON 扩展 `bt_shm` 与 `rf_*` 实时遥测）与 `SET_LANG <auto|BCP-47>` 落盘
- [x] 为 `/data/adb/kuroko/daemon.log` 实现 `256 KB` 上限原子轮转（`rotate_daemon_log_if_needed_locked()` 截取换行对齐 `128 KB` 尾部写入 `0600` `daemon.log.tmp` 后原子 `rename()` 替换）

---

## 四、 Zygisk Native 传感器引擎与 All-in-One 双语模块打包（`native/zygisk/`, `module_template/`, `build_module.py` · `v0.7.0`）

- [x] 配置 `module_template/sepolicy.rule` 完整覆盖 `zygote` / `system_server` / `radio` / `bluetooth` 四域只读 `memfd` 与 Binder 策略
- [x] 彻底消除 Android 17 Zygisk Next 隔离 linker namespace 双副本陷阱：`arm64-v8a.so` **零 C `dlopen` 导入**，主路径经 JNI `java.lang.System.load` 加载至 `default` 命名空间并执行纯 PLT Hook；`StepInjectorThread` 内置纯 C++ ARM64 ELF64 `.rela.plt` GOT 自愈看门狗（`.rela.plt` 预过滤防注册不存在符号、`CONN_C1 | SEND_EVENTS` 预检核心门禁、静态生命期 `g_plt_orig_slots[SPEC_COUNT]` 无条件同步 + 全包装函数 `load_orig_for_slot` 回退、`PROT_EXEC` 重定位守卫、`.dynstr` 边界校验、`sysconf(_SC_PAGESIZE)` 页对齐 `mprotect`）
- [x] 升级三层多句柄发现覆盖 Non-wakeup/Wakeup 双句柄，在 `hook_save_sensor_usage_item` 过滤 `arg4 >= 0` 哨兵污染；覆写 8 类连续传感器（含 `TYPE_ORIENTATION (3)`）与 40Hz 单调计步注入；嵌入 `mv=13` 实时遥测（`kuroko.sensor.hook` / `kuroko.sensor.diag`）
- [x] 跨版本兼容基线加固：Native NDK 编译目标下探至 `android-31`（Android 12+，零 API>31 Bionic 符号依赖）；`sensor_hook.cpp` 升级为 ABI 安全的 20 变体 `HookSlot` 多版本 Android 12–17 候选符号表（5 核心 + 3 发现位掩码去重，各参数个数变体独立精确签名包装与 `std::atomic` 原指针防 AAPCS64 栈参截断，未命中时 `LOGW` 自动打印 `.rela.plt` 候选符号供未知 OEM 排查）
- [x] 配置可扩展多语言安装器与模块元数据：`module.prop`（`id=kuroko_teleport`、`name=Kuroko Teleport`、`author=Kuroko Teleport`，构建/Release 阶段动态注入 KernelSU `updateJson`）；`module_template/lang/{en,zh}.sh`（16 对称键，含 `MOD_DESC` 及可选 `com.android.bluetooth` 提示）；`customize.sh` 通用 BCP-47 级联加载动态改写 `$MODPATH/module.prop` `description` 后清理 `$MODPATH/lang/` 与 `$MODPATH/apk/`
- [x] 落实 All-in-One 策略 A1/A2/A4（SHA-256 验签、流式 `pm install -r -d -S`、PMS 离线暂存 `/data/adb/kuroko/pending_apk/` 由 `service.sh` 补装、签名冲突与 `uninstall.sh` 严禁 `pm uninstall` 保护用户路线）
- [x] 跨平台构建与 CI/Release 工程化：`build_module.py` / `native/build_native.py` 动态探测 SDK/NDK/CMake/Ninja/LLVM，配合 `scripts/audit_i18n.py`、`.github/workflows/{ci,release}.yml`（含 `update.json` 发布）与 `.github/ISSUE_TEMPLATE/bug_report.yml` 产出并审计 `dist/kuroko-teleport-ksu-module.zip`（16KB + BTI/PAC + 零 `libc++_shared.so` + 零 `dlopen`）

---

## 五、 LSPosed 系统侧模块（`app/src/main/java/com/kuroko/teleport/xposed/` · `v0.7.0`）

- [x] 配置单 APK 集成的 Xposed 元数据（`xposedmodule=true`、`xposedminversion=93`、`xposedscope=@array/xposed_scope` 严格限定为系统组件 `android`、`com.android.phone` 及可选的 `com.android.bluetooth`，`assets/xposed_init` 声明 `com.kuroko.teleport.xposed.KurokoXposedEntry`，双语 `xposed_description`，目标 App 零注入）
- [x] 升级 `ShmReader.kt` 至 `KurokoShm v2`（`KUROKO_SHM_VERSION=2`，新增 `readRfHeader`/`readCells`/`readWifiAps`/`readBleDevices`、`cachedBluetoothBinder` 与仅 Root UID 0 的 `KUROKO_BINDER_TX_GET_BT_BINDER` 桥接，回传 `sensorOk` + `xp=13` 前缀 `sensor_diag`）
- [x] 实现 `LocationHook.kt`（1Hz 主动推包泵）、`HyperOsHook.kt`（HyperOS 4 豁免）
- [x] 新增 `VirtualCellBuilder.kt` 并升级 `TelephonyHook.kt`：在 `com.android.phone`（`PhoneInterfaceManager` + `ISub` `SubscriptionManagerService`/`SubscriptionController`）与 `system_server`（`TelephonyRegistry` + `Kuroko-1Hz-Tel` 主动热切换泵）按 UID 支持三态策略（透传 / 隐藏 / `SPOOF_CELL` 虚拟 4G/5G `CellInfoLte`/`CellInfoNr`、`GsmCellLocation`、克隆改写 `ServiceState` 与 `NetworkRegistrationInfo.mRplmn` 及可选 `SubscriptionInfo` 改写），并明确文档化进程内直读 `/dev/__properties__` `gsm.*` 快捷方法的零注入边界
- [x] 新增 `VirtualWifiBuilder.kt` 并升级 `WifiConnectivityHook.kt`：在 APEX `service-wifi.jar` 与 `service-connectivity.jar` 按 UID 支持三态策略（透传 / 隐藏 / `SPOOF_WIFI` 虚拟 `ScanResult` + 802.11d Country IE + `getCountryCode` + 可选虚拟已连 `WifiInfo`）
- [x] 新增 `BluetoothHook.kt` 并接入 `KurokoXposedEntry.kt`：`system_server` 捕获 `service-bluetooth.jar` 暴露的远程 Binder 缓存至 `ShmReader`；`com.android.bluetooth`（`/apex/com.android.bt`）同时 Hook `AdapterBinder`（`IAdapter`）与 `AdapterServiceBinder`（`IBluetooth`）接收 `g_memfd_ro`，在 `ScanController`/`ScanBinder`/`ScannerMap`（及 Android 12–15 `GattService` 回退）与 `RemoteDevices` 实现按 UID 抑制真实 BLE/经典蓝牙发现及 1Hz 虚拟 BLE / iBeacon 注入（零影响已配对设备）

---

## 六、 开源地图主控 App、悬浮窗、射频配置、更新检查与诊断中心（`app/src/main/java/com/kuroko/teleport/` · `v0.7.0`）

- [x] 配置 `app/build.gradle.kts`（`namespace` / `applicationId = com.kuroko.teleport`，支持环境变量 Option-A Release 签名与本地 Debug 回退）读取 `../version.properties`（`v0.7.0`/`13`），`res/xml/locales_config.xml` + `AndroidManifest.xml` `android:localeConfig` 接入 Android 13+ 原生 Per-App Language
- [x] 维护 100% 对称中英字符串资源（`strings.xml` + `strings_modules.xml`，完整覆盖地图/运动/悬浮窗/诊断/Release 更新/全球射频信号仿真与三态策略，由 `scripts/audit_i18n.py` 强制校验键与占位符对称、零残留硬编码中文）与 `GeocoderClient` `KurokoTeleport/<version>` User-Agent
- [x] 配置全平台应用与状态栏图标资源（Android 13–17 Adaptive + Monochrome、`mipmap-anydpi-v26`、`mdpi`–`xxxhdpi` 兼容 PNG 与前台保活通知栏 `24dp` 矢量图标 `ic_stat_kuroko.xml`）
- [x] 实现注册表驱动 `AppLocaleManager.kt`（`kuroko_locale_prefs` + `SupportedLanguage` 母语自称 + API 33+ `LocaleManager` / API 31–32 回退 + `com.kuroko.teleport.overlay.ACTION_REFRESH_LOCALE` + `daemon.setLang` 同步）、`SettingsDialog.kt` 可扩展 `FlowRow` 语言选择器、`FloatingOverlayService`（`kuroko_hud_channel`）`disposeComposition()` 实时换语重建、`RouteStore`/`PointStore`（`kuroko_store_v1`、`kuroko_route`/`kuroko_points`、`builtInKey` 预设本地化）与 `GeocoderClient` 动态 BCP-47 `accept-language`
- [x] 实现 Release 专用更新检查（`v0.6.3`：`update/UpdateChecker.kt`、`ui/UpdateDialog.kt`，接入 `MainActivity` 与 `SettingsDialog`）及 KernelSU `updateJson` 静态更新通道
- [x] 实现全球射频配置中心、实测信号导入与三态策略 UI（`v0.7.0`）：`rf/RfConfigStore.kt`（`kuroko_rf_v1` JSON 持久化与导入导出）、`rf/RfLiveImporter.kt`（一键导入当前周边实测基站/Wi-Fi/BLE iBeacon 及按需可选 OpenCelliD 查询）、`ui/RfConfigDialog.kt`（实时射频遥测、全局开关、区域运营商预设与 3 标签固定点位编辑）、`ui/TargetAppSheet.kt`（全量拟真/严格隐藏/同城户外三预设 + 蜂窝/Wi-Fi/蓝牙三态微调 + 蓝牙作用域提示），`KurokoRuntime`/`DaemonClient` 连接自动同步 IPC v3 且保持 `btShm` 可选不锁模拟
- [x] 实现零常驻后台的 **应用内诊断与日志中心（`v0.6.2` / `v0.7.0` 升级）**：400 条内存事件环形缓冲 `diag/AppLogBuffer.kt`、5 源有界按需采集器（两阶段 `logcat`、零误报 `isEngineRelevantAvc` 含 `bluetooth` 域、严格防跨模块 LSPosed 白名单、精确 ELF 实例数解析）+ 强制隐私脱敏（含 OpenCelliD Token 脱敏）+ 本地自动体检（含可选蓝牙钩子 `B` 检查）`diag/DiagnosticCollector.kt`、Braun 新拟态 `ui/DiagnosticsDialog.kt`、严格限定 `cache/diagnostics/` 的 `FileProvider` + SAF 6 文件脱敏 ZIP 导出
- [x] 完整实现 Braun 亮色新拟态双栏地图（`KurokoControllerScreen`、`KurokoMapController`、`KurokoLightColors`）、持久化拖拽弹簧底栏、三层场景方案记忆、后台悬浮控制窗（`specialUse` + `FLAG_SECURE`）、设置页内置「计步 / IMU 传感器自检」及全链路四端严格版本锁定状态机（`KurokoRuntime` + `KurokoProtocol`）

---

## 七、 工程文档与社区模板（`README.md`, `README_EN.md`, `docs/`, `.github/`）

- [x] 维护 `README.md`（简体中文用户向主页）与 `README_EN.md`（英文用户向主页），同步全球射频仿真（基站/Wi-Fi/BLE）、可选 `com.android.bluetooth` 作用域说明、精简核心 TODO 与社区支持
- [x] 维护 `docs/ARCHITECTURE.md`（同步 `KurokoShm v2` 16KB 布局、`RfSynthesizer`、偶极子地磁模型、蓝牙 APEX Hook 与 `KUROKO_BINDER_TX_GET_BT_BINDER` 桥接、TCP IPC v3 及应用内诊断中心）
- [x] 维护 `docs/ROADMAP.md`（同步 `v0.6.3` / `v0.7.0` 里程碑与各阶段演进规划）与 `docs/TODOLIST.md`（本可勾选追踪清单）
- [x] 维护双语 GitHub Issue 反馈表单 `.github/ISSUE_TEMPLATE/bug_report.yml` 与 CI/Release 工作流 `.github/workflows/{ci,release}.yml`

---

## 八、 Phase 1 与 `v0.7.0` 射频真机联合验收清单

- [x] **Phase 1 基础部署、LBS、计步、多语言与诊断中心全链路验收（✅ 已完成）**
  - [x] KSU2 模块与主控 APK 零 Bootloop 顺利进桌面，LSPosed 仅勾选系统组件（零目标 App 注入）
  - [x] `g_memfd_ro` 修复 `tmpfs` `{ write }` SELinux 告警，实现 `loc_shm` / `phone_shm` 握手，主流地图应用跨城定位首通通过
  - [x] All-in-One 单包刷入与双语本地化、四端版本对齐、`L✓ P✓ S✓` 全亮、设置页多语言实时切换、应用内诊断中心自动体检与脱敏 ZIP 导出、内置传感器自检、主流运动计步类应用室内桌面静止长时跑圈与 `boot_guard=2` 熔断全部验收通过
- [x] **`v0.7.0` 全球射频仿真（`SHM=2` / `IPC=3`）核心真机验收（✅ 核心已通过）**
  - [x] 刷入 `v0.7.0(13)` All-in-One 模块包，验证四端版本对齐（`v0.7.0(13) · P3 · SHM2`）且未启用或未勾选 `com.android.bluetooth` 时正常放行模拟不误锁
  - [x] 在 LSPosed 可选启用 `com.android.bluetooth` 并重启，验证双描述符（`IAdapter` / `IBluetooth`）`KUROKO_BINDER_TX_GET_BT_BINDER` 桥接成功（`B✓`、`bt_shm=1`）且诊断中心零 `bluetooth` SELinux AVC 拦截
  - [x] 实测跨国/跨区坐标下虚拟 4G/5G 基站（LTE/NR 服务与邻区小区）、`ServiceState` 1Hz 热切换（无需重启目标 App 即时刷新运营商与 MCC/MNC）及开启本地 SIM 同步后的 `ISub` `SubscriptionInfo` 改写，已配对蓝牙设备连接不受干扰（Wi-Fi 列表与自定义 iBeacon 现场导入待按需抽测）

---

## 九、 Phase 2 ~ Phase 4 后续迭代待办

### 1. 多版本 Android 12–17 传感器 ABI 与多 ROM 泛化（Phase 2）

- [x] 将 Native NDK 编译目标下探至 `android-31`（Android 12+，验证零 API>31 Bionic 依赖），并在 `native/zygisk/sensor_hook.cpp` 建立覆盖 Android 12–17（API 31–37）的 ABI 安全 20 变体 `HookSlot` 多版本候选符号表（`.rela.plt` 预过滤 + `CONN_C1 | SEND_EVENTS` 核心门禁 + 静态 `g_plt_orig_slots` 无条件同步 + 未知 OEM `LOGW` 转储）
- [ ] 在 Android 12–16 多机型与第三方 OEM ROM 上开展社区真机验证（收集诊断包补齐潜在非标 OEM 符号变体），完善 `sensors_event_t` 运行时校验与非特定 OEM 下的 AOSP 一级/三级计步句柄发现回退验证
- [ ] 保持现有 HyperOS 豁免逻辑解耦运行，调研并补充主流 ROM（ColorOS、OriginOS、One UI、Pixel/AOSP）的定位代理、省电冻结与模糊定位可选弱绑定适配钩子

### 2. 海外 GMS FusedLocationProvider (FLP) / `WorkSource` 适配（Phase 2）

- [ ] 在 `LocationHook.kt` 中解析 `LocationRequest.getWorkSource()` 与 `AttributionSource` 调用链，实现经由 `com.google.android.gms` 代理转发请求的真实调用方 UID 归因与多客户端分流隔离
- [ ] 评估并实现 GMS FLP 按包缓存（`lastLocation` / `LocationAvailability`）与 `Geofencing` 回调隔离（全程维持目标 App 零注入）
- [ ] （可选进阶）研究同源开普勒星历伪距与多普勒频移 `GnssMeasurements` 合成，替代当前降级抑制策略

### 3. 虚拟蜂窝基站伪造（Virtual Cell Tower Spoofing · ✅ 已完成，`v0.7.0`）

- [x] 扩展 `KurokoShm v2` 单应用策略位与主控 App UI，将蜂窝基站隔离策略升级为三态切换：**`透传 (Pass)` / `隐藏 (Hide)` / `虚拟基站伪造 (Spoof)`**
- [x] 在守护进程侧实现全球地理感知 4G/5G 邻近基站合成器（`rf_synth.{h,cpp}`：450m 网格 + Cost-231 衰落 + OU 波动 + 3 dB 切换迟滞 + TimingAdvance + 用户固定基站与按需 OpenCelliD/周边实测导入），动态输出 `CellInfoLte` / `CellInfoNr` 参数并沿路线平滑切换服务小区
- [x] 在 `VirtualCellBuilder.kt` & `TelephonyHook.kt`（`com.android.phone` `PhoneInterfaceManager` + `system_server` `TelephonyRegistry`）按 UID 注入虚拟 `CellInfo`、`GsmCellLocation` 及网络/可选 SIM 国家码与运营商名称

### 4. 虚拟 Wi-Fi AP 列表伪造（Virtual Wi-Fi AP List Spoofing · ✅ 已完成，`v0.7.0`）

- [x] 扩展 `KurokoShm v2` 与单应用策略 UI，为 Wi-Fi 隔离增加 **`透传 (Pass)` / `隐藏 (Hide)` / `虚拟 Wi-Fi 列表 (Spoof)`** 三态按 UID 切换
- [x] 实现基于虚拟坐标与移动轨迹的 80m 3×3 微网格周边 AP 合成器：生成合法单播 OUI `BSSID`、区域化 `SSID`、法规合规 2.4GHz / 5GHz 信道与 `centerFreq0`、距离衰减 `RSSI`、单调时间戳及用户固定热点
- [x] 在 `VirtualWifiBuilder.kt` & `WifiConnectivityHook.kt`（APEX `service-wifi.jar` / `service-connectivity.jar`）按 UID 构造带 **802.11d Country IE (`id=7`)** 的虚拟 `ScanResult` 切片、覆写 `getCountryCode` 与可选虚拟已连 `WifiInfo`

### 5. 蓝牙 / BLE 隔离与伪造（Bluetooth / BLE Isolation & Spoofing · ✅ 已完成，`v0.7.0`）

- [x] **按 UID 蓝牙/BLE 扫描隔离**：在 `BluetoothHook.kt`（`system_server` `service-bluetooth.jar` + `com.android.bluetooth` `/apex/com.android.bt` `ScanController`/`ScanBinder`/`ScannerMap` 及 Android 12–15 `GattService` 回退 + `RemoteDevices`）按调用方 UID 抑制真实周边 BLE 广播与经典蓝牙扫描结果，零影响已配对设备
- [x] **虚拟 BLE / iBeacon 注入**：在 `KurokoShm v2`、`rf_synth.cpp` 与 `RfConfigDialog.kt` 中支持可配置虚拟 BLE / 30B Apple `0x004C` iBeacon 参数（`UUID`、`Major`、`Minor`、`TxPower`、距离耦合 `RSSI` 及一键实测扫描导入），经 1Hz 虚拟注入器校验调用方 `ScanFilter` 后按目标 UID 派发合法 `ScanResult`

### 6. 计步生态、交互体验与架构演进（Phase 3 & Phase 4）

- [ ] **Phase 3（计步生态）**：适配系统计步聚合服务（如 `StepCounterManagerService` / `com.miui.providers.steps`）与 Android Health Connect 步数同步（微信运动 / 支付宝 / 系统健康）
- [ ] **Phase 4（交互与生态扩展）**：基于三步零硬编码框架扩展 `zh-TW` / `ja` / `ko` 社区语言包；支持 GPX / KML / TCX 轨迹导入导出、360° 虚拟方向摇杆（Floating Joystick）与开源路网（OSRM / Valhalla）自动贴路规划
- [ ] **Phase 4（架构演进）**：评估并开发可选内嵌 **LSPlant** 分支，支持零 LSPosed 依赖的纯 KSU2 + Zygisk 单模块部署
