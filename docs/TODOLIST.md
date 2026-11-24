# Kuroko Teleport 工程待办清单（TODOLIST）

本文档按子系统追踪 **Kuroko Teleport**（当前统一基线：**`v0.6.2` (`versionCode=11`, `IPC=2`, `SHM=1`) All-in-One + 数据驱动多语言 + 应用内诊断中心**）的细粒度研发、真机验证与后续演进任务。已完成项标注 `[x]`，待真机联调与后续迭代项标注 `[ ]`。

---

## 一、 Phase 0：真机探针与基线锁定

- [x] 采集 Android 17 (API 37) ARM64 测试设备（HyperOS 4）全只读环境画像
- [x] 拉取并反汇编 `/system/lib64/libsensorservice.so`，确认 16KB 页对齐 (`p_align=0x4000`)、BTI+PAC (`0x3`)、`sizeof(sensors_event_t)=0x68`
- [x] 验证 `libsensorservice.so` 未开 `-Bsymbolic`，锁定纯 PLT/GOT 零 Inline Hook 路线及全部 `R_AARCH64_JUMP_SLOT` 目标符号
- [x] 静态分析 `services.jar`、`miui-services.jar`、`service-wifi.jar`、`service-connectivity.jar`、`TeleService.apk`
- [x] 锁定 Android 17 APEX jarjar 重定位类（`com.android.wifi.x...ParceledListSlice`、`android.net.connectivity.com.android.server.ConnectivityService`）与 HyperOS 4 `GpsProxyManager` / `MiuiBlurLocationManagerImpl`

---

## 二、 统一版本契约与跨语言共享内存协议（`version.properties`, `common/`）

- [x] 维护仓库唯一版本契约 `version.properties`（`RELEASE_VERSION_NAME=0.6.2`, `RELEASE_VERSION_CODE=11`, `SHM_SCHEMA_VERSION=1`, `IPC_PROTOCOL_VERSION=2`, `MIN_COMPAT_MODULE_CODE=11`, `MIN_COMPAT_APP_CODE=11`），统一驱动 CMake、Gradle、`module.prop` 与打包校验
- [x] 设计 16KB 页对齐 `KurokoShm` 无锁 Seqlock 内存结构体（`common/include/kuroko_shm.h`，`KUROKO_SHM_MAGIC=0x4F52554B` `"KURO"`）及编译期 `static_assert` 偏移与 Schema 版本校验
- [x] 定义 32 槽位 `KurokoTarget`（`KUROKO_POLICY_ALL_STRICT` / `KUROKO_POLICY_LOCAL_OUTDOOR`）、32 槽位 `KurokoSv` 与 2040B NMEA 缓冲区
- [x] 定义 `libbinder_ndk` 合法区间事务码（`KUROKO_BINDER_TX_SET_SHM = 0x004B5243` `'KRC'`、`KUROKO_BINDER_TX_PING = 0x004B5250` `'KRP'`、`KUROKO_ACK_OK = 0x4F4B`）

---

## 三、 Native 仿真守护进程（`native/daemon/` · `v0.6.2`）

- [x] 实现 50Hz 主时钟（`clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME)`）与 Seqlock 原子快照发布
- [x] 实现 Catmull-Rom 样条密采样轨迹插值（闭合循环 Loop、弯道自动减速、目标里程与重复圈数 `max_laps` 达标自动原地停留）
- [x] 实现双时间尺度 OU 跑步配速波动（中期 $\tau=18\text{s}$ 节奏 + 短期 $\tau=4\text{s}$ 微扰）及 TCP 命令 `SET_PACE_VAR <ratio>`
- [x] 实现局部 ENU 坐标 OU 时间相关有色噪声 GPS 漂移（$\tau = 9\text{s}$）与相关水平精度呼吸波动
- [x] 实现单源连续相位生物力学 5 路 IMU 合成器与 1Hz 18 颗 4 星座 GNSS/NMEA-0183 生成器（`nmea_gnss.cpp`）
- [x] 通过 `/proc/self/fd/<g_memfd>`（memfd `"kuroko_shm"`）以 `O_RDONLY` 打开只读句柄 `g_memfd_ro` 进行 Unix `SCM_RIGHTS`（`/dev/kuroko_root.sock`）与 Binder 跨进程传递，消除 Android 17 `tmpfs` `{ write }` SELinux 拦截
- [x] 实现 `127.0.0.1:28765` 鉴权 TCP 控制服务器（14 命令 / 34 字段 `STATUS`）与动态 `libbinder_ndk.so` 推送线程：解析 `xp`/`mv` 写入四端版本字段，支持 `SET_LANG <auto|BCP-47>` 严格校验并原子写入 `0600` `/data/adb/kuroko/lang`（`auto` 时删除）
- [x] 为 `/data/adb/kuroko/daemon.log` 实现 `256 KB` 上限原子轮转（`rotate_daemon_log_if_needed_locked()` 截取换行对齐 `128 KB` 尾部写入 `0600` `daemon.log.tmp` 后原子 `rename()` 替换）

---

## 四、 Zygisk Native 传感器引擎与 All-in-One 双语模块打包（`native/zygisk/`, `module_template/`, `build_module.py` · `v0.6.2`）

- [x] 配置 `module_template/sepolicy.rule` 完整覆盖 `zygote` / `system_server` / `radio` 三域只读 `memfd` 策略
- [x] 彻底消除 Android 17 Zygisk Next 隔离 linker namespace 双副本陷阱：`arm64-v8a.so` **零 C `dlopen` 导入**，主路径经 JNI `java.lang.System.load` 加载至 `default` 命名空间并执行 8 点纯 PLT Hook；`StepInjectorThread` 内置纯 C++ ARM64 ELF64 `.rela.plt` GOT 自愈看门狗（`r--p offset=0`、`PROT_EXEC` 重定位守卫、`.dynstr` 边界校验、`sysconf(_SC_PAGESIZE)` 页对齐 `mprotect` + 8 个 `std::atomic` 原指针）
- [x] 升级三层多句柄发现覆盖 Non-wakeup/Wakeup 双句柄，在 `hook_save_sensor_usage_item` 过滤 `arg4 >= 0` 哨兵污染；覆写 8 类连续传感器（含 `TYPE_ORIENTATION (3)`）与 40Hz 单调计步注入；嵌入 `mv=11` 实时遥测（`kuroko.sensor.hook` / `kuroko.sensor.diag`）
- [x] 配置可扩展多语言安装器与模块元数据：`module.prop`（`id=kuroko_teleport`、`name=Kuroko Teleport`、`author=Kuroko Teleport`）；`module_template/lang/{en,zh}.sh`（16 对称键，含 `MOD_DESC`）；`customize.sh` 通用 BCP-47 级联加载动态改写 `$MODPATH/module.prop` `description` 后清理 `$MODPATH/lang/` 与 `$MODPATH/apk/`
- [x] 落实 All-in-One 策略 A1/A2/A4（SHA-256 验签、流式 `pm install -r -d -S`、PMS 离线暂存 `/data/adb/kuroko/pending_apk/` 由 `service.sh` 补装、签名冲突与 `uninstall.sh` 严禁 `pm uninstall` 保护用户路线）
- [x] 跨平台构建与 CI/Release 工程化：`build_module.py` / `native/build_native.py` 动态探测 SDK/NDK/CMake/Ninja/LLVM，配合 `scripts/audit_i18n.py`、`.github/workflows/{ci,release}.yml` 与 `.github/ISSUE_TEMPLATE/bug_report.yml` 产出并审计 `dist/kuroko-teleport-ksu-module.zip`（16KB + BTI/PAC + 零 `libc++_shared.so` + 零 `dlopen`）

---

## 五、 LSPosed 系统侧模块（`app/src/main/java/com/kuroko/teleport/xposed/`）

- [x] 配置单 APK 集成的 Xposed 元数据（`xposedmodule=true`、`xposedminversion=93`、`xposedscope=@array/xposed_scope` 仅包含 `android` 与 `com.android.phone`，`assets/xposed_init` 声明 `com.kuroko.teleport.xposed.KurokoXposedEntry`，双语 `xposed_description`，目标 App 零注入）
- [x] 实现 `KurokoXposedEntry.kt`、`ShmReader.kt`（回传 `sensorOk` + `xp=11` 前缀 `sensor_diag`）、`LocationHook.kt`（1Hz 主动推包泵）、`WifiConnectivityHook.kt`（APEX 双通道）、`TelephonyHook.kt`（双进程基站策略）与 `HyperOsHook.kt`（HyperOS 4 豁免）

---

## 六、 开源地图主控 App、悬浮窗、四端锁定、多语言与诊断中心（`app/src/main/java/com/kuroko/teleport/` · `v0.6.2`）

- [x] 配置 `app/build.gradle.kts`（`namespace` / `applicationId = com.kuroko.teleport`，支持环境变量 Option-A Release 签名与本地 Debug 回退）读取 `../version.properties`（`v0.6.2`/`11`），`res/xml/locales_config.xml` + `AndroidManifest.xml` `android:localeConfig` 接入 Android 13+ 原生 Per-App Language
- [x] 维护 491 组 100% 对称中英字符串（`strings.xml` 246 键 + `strings_modules.xml` 245 键，含诊断与日志 54 键及 `application-label='Kuroko Teleport'`，位置占位符对齐、零残留硬编码中文）与 `GeocoderClient` `KurokoTeleport/<version>` User-Agent
- [x] 配置全平台应用与状态栏图标资源（Android 13–17 Adaptive + Monochrome、`mipmap-anydpi-v26`、`mdpi`–`xxxhdpi` 兼容 PNG 与前台保活通知栏 `24dp` 矢量图标 `ic_stat_kuroko.xml`）
- [x] 实现注册表驱动 `AppLocaleManager.kt`（`kuroko_locale_prefs` + `SupportedLanguage` 母语自称 + API 33+ `LocaleManager` / API 31–32 回退 + `com.kuroko.teleport.overlay.ACTION_REFRESH_LOCALE` + `daemon.setLang` 同步）、`SettingsDialog.kt` 可扩展 `FlowRow` 语言选择器、`FloatingOverlayService`（`kuroko_hud_channel`）`disposeComposition()` 实时换语重建、`RouteStore`/`PointStore`（`kuroko_store_v1`、`kuroko_route`/`kuroko_points`、`builtInKey` 预设本地化）与 `GeocoderClient` 动态 BCP-47 `accept-language`
- [x] 实现零常驻后台的 **应用内诊断与日志中心（`v0.6.2`）**：400 条内存事件环形缓冲 `diag/AppLogBuffer.kt`（接入 `DaemonClient`/`KurokoRuntime`/`FloatingOverlayService`）、5 源有界按需采集器 + 强制隐私脱敏引擎 + 5 项本地自动体检 `diag/DiagnosticCollector.kt`、Braun 新拟态 `ui/DiagnosticsDialog.kt`（接入设置页、版本锁弹窗与离线状态胶囊）、严格限定 `cache/diagnostics/` 的 `FileProvider`（`res/xml/file_paths.xml`，零新增敏感权限，自动清理 >24h 缓存）+ SAF `CreateDocument` 6 文件脱敏 ZIP 导出
- [x] 完整实现 Braun 亮色新拟态双栏地图（`KurokoControllerScreen`、`KurokoMapController`、`KurokoLightColors`）、持久化拖拽弹簧底栏、三层场景方案记忆、后台悬浮控制窗（`specialUse` + `FLAG_SECURE`）、设置页内置「计步 / IMU 传感器自检」及全链路四端严格版本锁定状态机（`KurokoRuntime` + `KurokoProtocol`）

---

## 七、 工程文档与社区模板（`README.md`, `README_EN.md`, `docs/`, `.github/`）

- [x] 维护 `README.md`（简体中文用户向主页）与 `README_EN.md`（英文用户向主页），补充一键诊断与脱敏日志反馈 FAQ
- [x] 维护 `docs/ARCHITECTURE.md`（全栈技术架构、应用内诊断与强制隐私脱敏、守护进程日志原子轮转与数学模型文档）
- [x] 维护 `docs/ROADMAP.md`（Phase 0 ~ Phase 4 分阶段演进路线图）与 `docs/TODOLIST.md`（本可勾选追踪清单）
- [x] 新增双语 GitHub Issue 反馈表单 `.github/ISSUE_TEMPLATE/bug_report.yml` 与 CI/Release 工作流 `.github/workflows/{ci,release}.yml`

---

## 八、 Phase 1 真机联合验收清单（✅ 已完成）

- [x] **基础部署与 LBS 首通验证（Android 17 真机实测通过）**
  - [x] KSU2 模块与主控 APK 零 Bootloop 顺利进桌面，LSPosed 仅勾选 `android` 与 `com.android.phone`
  - [x] `g_memfd_ro` 修复 `tmpfs` `{ write }` SELinux 告警，实现 `loc_shm` / `phone_shm` 握手，主流地图应用跨城定位首通通过
- [x] **`v0.6.2` All-in-One（多语言 + 四端锁定 + 应用内诊断中心）真机全链路验收**
  - [x] 在 KernelSU2 刷入单个 `dist/kuroko-teleport-ksu-module.zip`（`v0.6.2`），验证 `customize.sh` 自动探测中文/英文输出本地化刷机日志、动态改写 `module.prop` 描述、流式安装 `com.kuroko.teleport v0.6.2(11)` 并立即回收 `$MODPATH/apk/` 与 `$MODPATH/lang/`；重启前验证 `REBOOT_REQUIRED` 锁定与弹窗中「查看诊断日志」入口
  - [x] 重启进入桌面后核验桌面自适应/单色主题图标与前台通知栏图标，确认三灯 **`L✓ P✓ S✓`** 全亮、四端显示 `App v0.6.2(11) · Hook(11) · Daemon(11) · Sensor(11) · P2`、`mp=1` 单映射且无 `arg4=-1` 哨兵污染
  - [x] 打开 **设置 → 诊断与日志**，验证 5 项自动体检全绿、5 个日志分类标签与「仅看警告/错误」过滤正常、复制 Markdown 摘要及「分享诊断包 / 保存文件」导出的 6 文件 ZIP 均已严格脱敏（无令牌/Key/坐标/无关日志，开启匿名化后目标包名为 `target_1`…）
  - [x] 在设置页切换 **「跟随系统 / 简体中文 / English」**，验证主界面、LSPosed 管理器描述、运行中悬浮窗胶囊/展开控制台与前景通知栏均实时无截断刷新，且 `/data/adb/kuroko/lang` 同步落盘
  - [x] 使用设置页内置 **「计步 / IMU 传感器自检」** + **「以慢跑原地发步测试」**，手机平放桌面确认 `计步器(19)`、`步检(18)`、`加速度(1)` 与 `Native 遥测` (`ok` 递增, `er=0`, `r>=0`) 实时跳动，停测后自动还原
  - [x] 实测主流运动计步类应用室内桌面静止跑圈、后台悬浮 HUD、第三方地图跨城定位、目标 App `/proc/<pid>/maps` 零注入验证、`boot_guard=2` 防砖熔断与 30 分钟以上息屏长时压测

---

## 九、 Phase 2 ~ Phase 4 后续迭代待办

### 1. 多版本 Android 12–17 传感器 ABI 与多 ROM 泛化（Phase 2）

- [ ] 在 `native/zygisk/sensor_hook.cpp` 中建立覆盖 Android 12–17（API 31–37）的多版本 `libsensorservice.so` C++ Mangled 符号候选表与 `SensorEventConnection` 构造/派发 ABI 匹配表（结合 `ro.build.version.sdk` 与 `.rela.plt` 动态探针按需匹配）
- [ ] 为多版本 `libsensorservice.so` 增加运行时 `sensors_event_t` 尺寸与偏移校验，并完善非特定 OEM ROM 下的 AOSP 一级/三级计步句柄发现回退链路
- [ ] 保持现有 HyperOS 豁免逻辑解耦运行，调研并补充主流 ROM（ColorOS、OriginOS、One UI、Pixel/AOSP）的定位代理、省电冻结与模糊定位可选弱绑定适配钩子

### 2. 海外 GMS FusedLocationProvider (FLP) / `WorkSource` 适配（Phase 2）

- [ ] 在 `LocationHook.kt` 中解析 `LocationRequest.getWorkSource()` 与 `AttributionSource` 调用链，实现经由 `com.google.android.gms` 代理转发请求的真实调用方 UID 归因与多客户端分流隔离
- [ ] 评估并实现 GMS FLP 按包缓存（`lastLocation` / `LocationAvailability`）与 `Geofencing` 回调隔离（全程维持目标 App 零注入）
- [ ] （可选进阶）研究同源开普勒星历伪距与多普勒频移 `GnssMeasurements` 合成，替代当前降级抑制策略

### 3. 虚拟蜂窝基站伪造（Virtual Cell Tower Spoofing · Phase 4）

- [ ] 扩展 `KurokoShm` 单应用策略位与主控 App UI，将蜂窝基站隔离策略升级为三态切换：**`透传 (Pass-through)` / `隐藏 (Hide)` / `虚拟基站伪造 (Virtual Spoof)`**
- [ ] 在守护进程侧实现基于虚拟 WGS-84 坐标与轨迹的 4G/5G 邻近基站合成器（支持离线/在线基站库或确定性区域生成），动态输出合理的 `CellInfoLte` / `CellInfoNr` 参数（`MCC`、`MNC`、`TAC`、`CI`、`PCI`、`EARFCN` / `NR-ARFCN`、`RSRP` / `RSRQ`）并沿路线平滑切换服务小区
- [ ] 在 `TelephonyHook.kt`（`com.android.phone` `PhoneInterfaceManager` + `system_server` `TelephonyRegistry`）按 UID 构造并注入虚拟 `CellInfo` 列表与 `CellLocation`，规避“有 SIM 卡但零基站”启发式风控

### 4. 虚拟 Wi-Fi AP 列表伪造（Virtual Wi-Fi AP List Spoofing · Phase 4）

- [ ] 扩展 `KurokoShm` 与单应用策略 UI，为 Wi-Fi 隔离增加 **`隐藏 (Hide)` / `虚拟 Wi-Fi 列表 (Virtual AP List)`** 双态按 UID 切换
- [ ] 设计基于虚拟坐标与移动轨迹的周边 AP 合成器：生成合法 OUI 段 `BSSID`、自然 `SSID`、2.4GHz / 5GHz 信道频点、随相对距离衰减的 `RSSI` 及单调一致的扫描时间戳
- [ ] 在 `WifiConnectivityHook.kt` 的 `WifiServiceImpl.getScanResults`（APEX `service-wifi.jar`）中按 UID 反射构造填充虚拟 `ScanResult` 的 `ParceledListSlice`，并与 `ConnectivityService` 当前连接 `WifiInfo` 脱敏协同一致

### 5. 蓝牙 / BLE 隔离与伪造（Bluetooth / BLE Isolation & Spoofing · Phase 4）

- [ ] **Phase A（按 UID 蓝牙/BLE 扫描隔离）**：在 `BluetoothManagerService` / `com.android.bluetooth`（`AdapterService` / `ScanManager` / `GattService`）按调用方 UID 抑制真实周边 BLE 广播与经典蓝牙扫描结果，封堵蓝牙位置指纹与信标交叉检测
- [ ] **Phase B（虚拟 BLE / iBeacon 注入）**：在主控端与共享内存协议中新增可配置虚拟 BLE 广播帧 / iBeacon 参数（`UUID`、`Major`、`Minor`、`TxPower`、随距离耦合的 `RSSI`），支持按目标 UID 定时注入虚拟扫描回调

### 6. 计步生态、交互体验与架构演进（Phase 3 & Phase 4）

- [ ] **Phase 3（计步生态）**：适配系统计步聚合服务（如 `StepCounterManagerService` / `com.miui.providers.steps`）与 Android Health Connect 步数同步（微信运动 / 支付宝 / 系统健康）
- [ ] **Phase 4（交互与生态扩展）**：基于三步零硬编码框架扩展 `zh-TW` / `ja` / `ko` 社区语言包；支持 GPX / KML / TCX 轨迹导入导出、360° 虚拟方向摇杆（Floating Joystick）与开源路网（OSRM / Valhalla）自动贴路规划
- [ ] **Phase 4（架构演进）**：评估并开发可选内嵌 **LSPlant** 分支，支持零 LSPosed 依赖的纯 KSU2 + Zygisk 单模块部署
