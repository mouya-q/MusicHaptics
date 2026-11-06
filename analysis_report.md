# SystemHelper APK 全面逆向分析报告

> **分析日期**：2026-11-06  
> **分析对象**：`SystemHelper_4.0.0-20260729.apk`（3MB）  
> **分析方法**：apktool 解包 + JADX 反编译 + 全量源码审计  
> **工作区**：`/storage/emulated/0/work/systemhelper/`

---

## 一、基本信息

| 属性 | 值 |
|---|---|
| 包名 | `com.mobiletools.systemhelper` |
| 应用名 | SystemHelper |
| 版本 | 4.0.0-20260729 (versionCode 34) |
| minSdk / targetSdk | 31 / 34 (Android 12–14) |
| 共享 UID | `com.mobiletools.systemhelper.uid` |
| 签名权限 | `com.mobiletools.systemhelper.permission` (protectionLevel=signature) |
| 上传服务器 | `dm.wo.com.cn` (HTTPS:18081 / HTTP:18080) |
| 百度定位 SDK API Key | `IGY6EFGRsixbYqk7GeZdAOD2Od3Rj7iF` |
| Native 库 | `libindoor.so` (百度室内定位, 1.5MB) + `liblocSDK8b.so` (百度定位SDK, 350KB) |
| DEX | classes.dex × 1 (1.3MB, 561 个类) |
| ABI | arm64-v8a only |

**域名归属判定**：`wo.com.cn` 是中国联通"沃"品牌域名，`dm` = Device Management。  
**定性**：中国联通预装在渠道设备上的设备管理与遥测注册客户端。

---

## 二、Manifest 关键声明

### 2.1 权限列表（20 项）

| 权限 | 用途说明 | 隐私等级 |
|---|---|---|
| `BLUETOOTH_CONNECT` | 读取已配对蓝牙设备名称和MAC | 🔴 极高 |
| `BLUETOOTH` | 旧版蓝牙访问 | 🟠 高 |
| `INTERNET` | 上传数据到 dm.wo.com.cn | — |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 | 🟠 高 |
| `ACCESS_FINE_LOCATION` | GPS 精确定位 | 🔴 极高 |
| `ACCESS_COARSE_LOCATION` | 基站定位 | 🟠 高 |
| `ACCESS_BACKGROUND_LOCATION` | **后台持续定位** | 🔴 极高 |
| `READ_EXTERNAL_STORAGE` | 读取外部存储 | 🟡 中 |
| `ACCESS_NETWORK_STATE` | 读取网络状态 | 低 |
| `READ_PHONE_STATE` | 读取 IMEI/IMSI/ICCID 等 | 🔴 极高 |
| `ACCESS_WIFI_STATE` | 读取 WiFi BSSID/SSID | 🔴 极高 |
| `CHANGE_WIFI_STATE` | 修改 WiFi 状态 | 🟡 中 |
| `FOREGROUND_SERVICE` | 前台服务保活 | 🟡 中 |
| `ACCESS_LOCATION_EXTRA_COMMANDS` | 定位额外命令 | 🟡 中 |
| `READ_PRECISE_PHONE_STATE` | **精确电话状态**（系统级） | 🔴 极高 |
| `READ_PRIVILEGED_PHONE_STATE` | **特权电话状态**（仅系统应用可获取） | 🔴 极高 |
| `PACKAGE_USAGE_STATS` | **应用使用统计**（需用户授权） | 🔴 极高 |
| `QUERY_ALL_PACKAGES` | **枚举全部已安装应用** | 🔴 极高 |
| `SCHEDULE_EXACT_ALARM` | 精确定时器（绕过 Doze） | 🟠 高 |

### 2.2 组件声明

| 类型 | 组件 | 说明 |
|---|---|---|
| Activity | `TestActivity` | 需签名权限，普通用户不可访问，仅用于测试展示设备信息 |
| Service | `com.baidu.location.f` | 百度定位 SDK 服务，独立进程 `:remote` |
| Service | `RegisterHelperService` | AIDL 绑定服务，允许外部调用配置参数 |
| Service | `RegisterIntentService` | Intent 服务处理注册任务 |
| Service | `RegisterJobService` | JobService 兼容 |
| Receiver | `OnBootReceiver` | 开机自启，优先级 2147483647（最高） |
| Receiver | `ScheduleRegisterReceiver` | 定时注册触发 |
| Receiver | `AppInfoRegisterReceiver` | 应用信息上报触发 |
| Receiver | `SignalInfoRegisterReceiver` | 信号信息上报触发 |
| Receiver | `GetSignalInfoReceiver` | 百度定位+信号采集触发 |
| Receiver | `SIMStateReceiver` | SIM 卡变化监听 |
| Receiver | `ConnectStateReceiver` | 网络连接变化监听 |

### 2.3 其他 Manifest 关键属性

- `android:sharedUserId="com.mobiletools.systemhelper.uid"` — 共享 UID，暗示系统级预装
- `android:usesCleartextTraffic="true"` — 允许明文 HTTP 传输
- `android:allowBackup="false"` — 禁止备份
- 百度 LBS API Key 直接硬编码在 meta-data 中

---

## 三、应用用途与核心业务流程

这是一个**中国联通渠道设备自动注册与网络质量遥测系统**。它在设备开机后自动启动，定期收集设备硬件身份、SIM 卡信息、蜂窝网络信号质量、WiFi 状态、蓝牙配对设备、GPS 位置、以及全部已安装应用的使用统计，加密后上传到联通服务器 `dm.wo.com.cn`。

### 3.1 触发类型与流程

```
开机 BOOT_COMPLETED
  → OnBootReceiver 延迟 4 分钟启动
  → 注册 AlarmManager 精确定时器 (setExactAndAllowWhileIdle)
  → 触发以下周期任务：

[B] Boot / [F] 首次开机 → 完整设备注册
[S] Schedule (每24h+随机偏移) → 定期设备信息上报
[T] SIM变化 → SIM卡变更触发重新注册
[A] AppInfo (每24h+随机偏移) → 应用使用统计上报
[R] SignalInfo (每12h) → 信号质量采集上报
[GetSignal] (每1h+随机偏移) → 百度定位+信号采集
```

### 3.2 上传频率汇总

| 触发类型 | 频率 | RegType 代码 |
|---|---|---|
| 开机首次 | 开机后 4 分钟延迟 | B / F |
| 定时注册 (Schedule) | 24 小时 + 随机偏移 (0–86400000ms) | S |
| 信号采集 (SignalInfo) | 12 小时 (43200000ms) | R |
| 应用统计 (AppInfo) | 24 小时 + 随机偏移 | A |
| 定位+信号 (GetSignal) | 1 小时 + 随机偏移 (3600000ms) | — |
| SIM 变更 | 事件触发，延迟 3 分钟 | T |

### 3.3 核心类映射表

| 混淆类名 | 实际功能 |
|---|---|
| `h1.a` (DeviceInfoReaderBase) | 设备信息读取基类：IMEI、IMSI、ICCID、基站、WiFi、蓝牙 |
| `h1.p` (DeviceManager) | 设备信息编排：SIM 变更检测、脱敏处理、上传调度 |
| `h1.n` (XiaomiReader) | 小米设备专用读取器 |
| `g1.b` (DeviceInfo) | 设备信息数据模型，序列化为 JSON |
| `g1.c` (GetIPAddressThread) | IPv6 地址获取线程 |
| `g1.f` (QueryAppInfoThread) | 应用使用统计采集线程 |
| `g1.g` (RegisterThread) | 数据上传线程（加密+HTTP） |
| `g1.e` (HistoryPostThread) | 历史失败数据重试上传 |
| `g1.a` (DMTelephonyCallback) | 电话状态回调：通话断因、数据连接失败 |
| `g1.d` (GetSignalStrengthInfo) | 蜂窝信号强度采集 |
| `i1.a` (AlarmHelper) | AlarmManager 定时器管理 |
| `i1.c` (CryptoHelper) | AES-GCM + RSA 加密 |
| `i1.d` (GlobalSetting) | 全局配置：服务器地址、间隔常量 |
| `i1.e` (HistoryDatabaseHelper) | 失败数据 SQLite 缓存 (dmregister 表, 最多 10 条) |
| `i1.f` (HttpHelper) | HTTP/HTTPS 网络请求 |
| `i1.m` (SignalDatabaseHelper) | 信号数据 SQLite 缓存 (signal 表, 最多 100 条) |
| `i1.n` (SignalInfo) | 信号信息数据模型 |
| `i1.b` (AppInfo) | 应用信息数据模型 |
| `i1.l` (RegisterUtils) | SharedPreferences 存储 + 系统属性读取 |
| `i1.g` (MemoryUtils) | RAM/ROM 存储信息 |
| `com.mobiletools.systemhelper.control.OnBootReceiver` | 开机自启入口 |
| `com.mobiletools.systemhelper.control.GetSignalInfoReceiver` | 百度定位+信号采集 |
| `com.mobiletools.systemhelper.service.RegisterHelperService` | AIDL 服务，允许外部配置 |

---

## 四、收集的信息清单

### 4.1 设备身份信息

| JSON 字段 | 数据来源 | 隐私等级 |
|---|---|---|
| `Manuf` | `Build.MANUFACTURER` | 低 |
| `Model` | `Build.MODEL` | 低 |
| `HWVersion` | `ro.hardware.version` 系统属性 | 中 |
| `SWVersion` | `Build.DISPLAY` 或 MIUI 版本号 | 中 |
| `OS` | 固定值 `"Android"` | 低 |
| `OSVersion` | `Build.VERSION.RELEASE` | 低 |
| `IMEI1` | `TelephonyManager.getImei(0)` / miui 反射 `getImeiForSlot` | 🔴 极高 |
| `IMEI2` | `TelephonyManager.getImei(1)` / miui 反射 `getImeiForSlot` | 🔴 极高 |
| `DeviceType` | 设备类型码 `"000005"` | 中 |
| `RAM` | `ro.boot.dram_info` 或 ActivityManager | 低 |
| `ROM` | StatFs 计算 | 低 |
| `FREE` | StatFs 计算 | 低 |
| `ClientVersion` | PackageInfo.versionName `"4.0.0"` | 低 |
| `SpecVersion` | 固定值 `"6.0"` | 低 |
| `Time` | `SimpleDateFormat("yyyy-MM-dd HH:mm:ss")` | 低 |

> **源码位置**：`h1/a.java` line 205–296（`a()` 方法）、`g1/b.java` line 131–367（`b()` 序列化方法）

### 4.2 SIM 卡与用户身份信息

| JSON 字段 | 数据来源 | 隐私等级 |
|---|---|---|
| `ICCID1` | `TelephonyManager.getSimSerialNumber()` (slot 0) | 🔴 极高（SIM 卡唯一序列号） |
| `ICCID2` | `TelephonyManager.getSimSerialNumber()` (slot 1) | 🔴 极高 |
| `IMSI1` | `TelephonyManager.getSubscriberId()` (slot 0) | 🔴 极高（用户唯一标识） |
| `IMSI2` | `TelephonyManager.getSubscriberId()` (slot 1) | 🔴 极高 |
| `MNC1` | `getSimOperator()` (slot 0) | 高 |
| `MNC2` | `getSimOperator()` (slot 1) | 高 |
| `NetworkType1` | GSM/WCDMA/TD-SCDMA/CDMA/LTE/NSA/SA | 中 |
| `NetworkType2` | 同上 (slot 1) | 中 |
| `TAC1` | `CellIdentity.getTac()` (slot 0) | 高（追踪区域码） |
| `TAC2` | `CellIdentity.getTac()` (slot 1) | 高 |
| `CID1` | `CellIdentity.getCi()` / `getNci()` | 高（基站小区 ID） |
| `CID2` | 同上 (slot 1) | 高 |
| `PCI1` | `CellIdentity.getPci()` | 中 |
| `PCI2` | `CellIdentity.getPci()` (slot 1) | 中 |
| `VolteEnabled1/2` | 系统状态 | 低 |
| `5GEnabled1/2` | 系统状态 | 低 |
| `VoNREnable1/2` | `ServiceState` isEnDcAvailable | 低 |
| `DataSlot` | `SubscriptionManager.getActiveDataSubscriptionId()` | 中 |
| `Esim` | `EuiccManager.isEnabled()` / `isEmbedded()` | 中 |
| `InactiveEsimMNC` | 未激活 eSIM 的 MCC+MNC | 中 |

> **脱敏处理（不充分）**：  
> - IMSI 截取前 10 位 + `*****`  
> - ICCID 截取前 10 位 + `**********`  
> - 仅当 SIM 不是联通（MNC 不以 46001/46006/46009/46010 开头）时才脱敏  
> - 前 10 位已包含 MCC+MNC+大部分 IMSI 标识符，仍可用于用户追踪

> **源码位置**：`h1/a.java` line 390–516（`g()` 方法读取 SIM 信息）、`h1/p.java` line 250–375（`l()` 方法脱敏处理）

### 4.3 蜂窝网络信号信息（SignalInfo 数据模型）

| JSON 字段 | 数据来源 | 隐私等级 |
|---|---|---|
| `RSRP` | `CellSignalStrengthNr.getSsRsrp()` / `CellSignalStrengthLte.getRsrp()` | 低 |
| `RSRQ` | `CellSignalStrengthNr.getSsRsrq()` / `CellSignalStrengthLte.getRsrq()` | 低 |
| `SINR` | `CellSignalStrengthNr.getSsSinr()` / `CellSignalStrengthLte.getRssnr()` | 低 |
| `CQITableIndex` | `getCsiCqiTableIndex()` / `getCqiTableIndex()` | 低 |
| `CQIIndex` | `getCsiCqiReport()` / `getCqi()` | 低 |
| `ARFCN` | `CellIdentityNr.getNrarfcn()` | 低 |
| `Bands` | `CellIdentity.getBands()` | 低 |
| `Bandwidth` | `CellIdentityLte.getBandwidth()` | 低 |
| `CallDisconnectCause` | `TelephonyCallback.onCallDisconnectCauseChanged()` | 中 |
| `DataFailCause` | `PreciseDataConnectionState.getLastCauseCode()` (映射表 400+ 条) | 中 |
| `EPSFallback` | VoLTE/EPS fallback 状态 | 中 |

> **源码位置**：`g1/d.java` line 134–391（`d()` 方法）、`g1/a.java`（TelephonyCallback）、`i1/n.java`（数据模型）

### 4.4 WiFi 信息

| JSON 字段 | 数据来源 | 隐私等级 |
|---|---|---|
| `BSSID` | `WifiInfo.getBSSID()` | 🔴 极高（路由器 MAC，可用于定位追踪） |
| `LinkSpeed` | `WifiInfo.getLinkSpeed()` | 低 |
| `RSSI` | `WifiInfo.getRssi()` | 低 |
| `Frequency` | `WifiInfo.getFrequency()` | 低 |
| `SSID` (信号采集中) | `WifiInfo.getSSID()` | 高 |
| `IPv6` | 遍历 `NetworkInterface.getNetworkInterfaces()` 获取 Inet6Address | 高 |
| `AccType` | WiFi/Mobile/Disconnected | 低 |

> **源码位置**：`h1/a.java` line 248–269（WiFi 信息）、`g1/c.java` line 39–57（IPv6）、`g1/d.java` line 278–297（信号采集中 WiFi）

### 4.5 蓝牙配对设备信息

| JSON 字段 | 数据来源 | 隐私等级 |
|---|---|---|
| `PairedDevice` | `BluetoothAdapter.getBondedDevices()` → `getName()` 拼接 | 🔴 极高（配对设备名称） |
| `PairedMacAddress` | `BluetoothAdapter.getBondedDevices()` → `getAddress()` 拼接 | 🔴 极高（配对设备 MAC 地址） |

> **源码位置**：`h1/a.java` line 42–79（`h()` 方法）

### 4.6 GPS 位置信息（百度定位 SDK）

| JSON 字段 | 数据来源 | 隐私等级 |
|---|---|---|
| `LONGITUDE` | 百度 `BDLocation.getLongitude()` (bd09ll 坐标系) | 🔴 极高 |
| `LATITUDE` | 百度 `BDLocation.getLatitude()` | 🔴 极高 |
| `ALTITUDE` | 百度 `BDLocation.getAltitude()` | 🔴 极高 |
| `District` | 百度 `BDLocation` 省+市+区+街道 拼接 | 🔴 极高 |
| `SPEED` | 百度 `BDLocation.getSpeed()` | 高 |
| `BEARING` | 百度 `BDLocation.getBearing()` | 高 |

> **定位配置**：高精度模式 (`Hight_Accuracy`)、bd09ll 坐标系、GPS+网络混合定位  
> **源码位置**：`GetSignalInfoReceiver.java` line 47–109（定位回调）、line 151–171（定位参数配置）

### 4.7 应用使用统计（全量第三方应用）

| JSON 字段（AppInfo[] 数组） | 数据来源 | 隐私等级 |
|---|---|---|
| `pkgName` | `PackageManager.getInstalledApplications()` + `UsageStats.getPackageName()` | 🔴 极高 |
| `appLabel` | `ApplicationInfo.loadLabel()` | 🔴 极高 |
| `runTime` | `UsageStatsManager.queryAndAggregateUsageStats()` 前台使用时长 (分钟) | 🔴 极高 |
| `launchCount` | `UsageEvents` 事件统计 (ACTIVITY_RESUMED + ACTIVITY_STOPPED) | 🔴 极高 |
| `tx1` | `NetworkStatsManager.querySummary()` SIM1 上行流量 (KB) | 🔴 极高 |
| `rx1` | `NetworkStatsManager.querySummary()` SIM1 下行流量 (KB) | 🔴 极高 |
| `tx2` | `NetworkStatsManager.querySummary()` SIM2 上行流量 (KB) | 🔴 极高 |
| `rx2` | `NetworkStatsManager.querySummary()` SIM2 下行流量 (KB) | 🔴 极高 |
| `totalData1` | SIM1 总流量 (KB) | 🔴 极高 |
| `totalData2` | SIM2 总流量 (KB) | 🔴 极高 |
| `totalDataWiFi` | WiFi 总流量 (MB) | 🔴 极高 |

> **采集范围**：排除 `com.android.*` 开头的系统应用，**收集所有第三方已安装应用**的包名、名称、使用时长、启动次数和分卡流量消耗。  
> **采集周期**：每次查询过去 24 小时 (86400000ms) 的数据。  
> **源码位置**：`g1/f.java` line 59–303（完整采集逻辑）、`i1/b.java`（数据模型）

---

## 五、上传机制

### 5.1 加密方案

```
明文 JSON (DeviceInfo.b() 序列化)
  │
  ├─→ SHA-256 摘要 [i1.c.c()]  ← 用作 AES 密钥
  │
  ├─→ RSA 加密 SHA-256 摘要 [i1.c.b()]
  │     └─ 证书: assets/cudms_main_pub.cer
  │     └─ 输出: RSA 密文 (用于 URL sign 参数)
  │
  └─→ AES/GCM/NoPadding 加密明文 [i1.c.a()]
        └─ Key: SHA-256 结果 (32 字节)
        └─ IV: SHA-256 结果的前 8 字节 + 第 24–32 字节拼接 (16 字节)
        └─ Tag: 128 bit
        └─ 输出: AES-GCM 密文 (HTTP Body)
```

### 5.2 HTTP 请求格式

```
POST https://dm.wo.com.cn:18081/register?ver=6.0&model=<URLEncode>&manuf=<URLEncode>&sign=<Base64(RSA密文)>
Content-Type: application/encrypted-json
Content-Length: <密文长度>

<AES-GCM 密文 Body>
```

### 5.3 上传地址

| 协议 | 地址 | 说明 |
|---|---|---|
| HTTPS (主) | `https://dm.wo.com.cn:18081/register` | 首选 |
| HTTP (降级) | `http://dm.wo.com.cn:18080/register` | HTTPS 证书校验失败时自动降级 |

> **⚠️ 严重安全问题**：  
> - HTTPS 证书校验失败时自动降级为 HTTP 明文传输  
> - Manifest 中 `usesCleartextTraffic="true"`  
> - 虽然 body 已 AES 加密，但 URL 参数暴露了 model/manuf/sign 信息  
> - 自定义 `X509TrustManager` 实际做了证书有效性校验 (`checkValidity()`)，但仍存在降级风险  
> **源码位置**：`i1/f.java` line 100–220、`g1/g.java` line 24–71

### 5.4 上传条件控制

| 条件 | 检查逻辑 | 源码位置 |
|---|---|---|
| 运营商限制 | SIM MNC 必须以 `460` 开头（中国大陆运营商） | `i1/l.java:177-218` (`s()` 方法) |
| 联通网络优先 | MNC 为 46001/46006/46009/46010 时优先上传 | `i1/l.java:226-254` (`u()` 方法) |
| 设备初始化 | `Settings.Global.device_provisioned == 1`（开机向导完成） | `h1/a.java:303-308` |
| vivo 权限门 | `Settings.Secure.operator_service_improve_plan == 1` | `i1/l.java:60-68` (`a()` 方法) |
| 网络连接 | `ConnectivityManager.getActiveNetworkInfo().isConnected()` | `i1/l.java:220-224` |
| WiFi 联通组合 | WiFi 连接 + 中国网络 或 联通网络 | `g1/g.java:90-110` |

> **关键发现**：非 vivo 设备仅检查 `device_provisioned`（开机向导是否完成），**不检查 `operator_service_improve_plan`**。这意味着大多数品牌设备只要完成了开机设置就会自动上传。

### 5.5 失败缓存与重试

| 数据库 | 表名 | 容量 | 用途 |
|---|---|---|---|
| `systemhelper.db` | `dmregister` | 最多 10 条 | 设备注册失败数据缓存 |
| `signalhelper.db` | `signal` | 最多 100 条 | 信号采集失败数据缓存 |

- 上传失败时数据写入 SQLite
- 后续注册成功时触发 `HistoryPostThread` (`g1/e.java`) 重试上传历史数据
- 历史数据上传成功后从数据库删除
- **确保数据最终一定会上传**

> **源码位置**：`i1/e.java`（历史数据库）、`i1/m.java`（信号数据库）、`g1/e.java`（重试上传）

---

## 六、触发与保活机制

### 6.1 开机自启

- `OnBootReceiver` 注册 `BOOT_COMPLETED` + `QUICKBOOT_POWERON`
- Intent 优先级 `2147483647`（Android 最高优先级）
- 开机后延迟 4 分钟 (240000ms) 启动
- 通过 `AlarmManager.setExactAndAllowWhileIdle()` 注册所有定时任务

> **源码位置**：`OnBootReceiver.java` line 29–36

### 6.2 AlarmManager 精确定时器

所有定时任务使用 `setExactAndAllowWhileIdle(2, ...)` 即 `RTC_WAKEUP` 模式，**可在 Doze 模式下唤醒设备**。

首次触发加入随机偏移 (`Random().nextInt(86400000)`)，后续按固定间隔重复。

> **源码位置**：`i1/a.java` line 17–74

### 6.3 系统事件监听

| 事件 | Receiver | 行为 |
|---|---|---|
| `SIM_STATE_CHANGED` | `SIMStateReceiver` | SIM 卡变化时延迟 3 分钟重新注册 |
| `CONNECTIVITY_CHANGE` | `ConnectStateReceiver` | 网络变化时检查连接状态 |
| `TelephonyCallback` | `g1.a` (DMTelephonyCallback) | 实时监听通话断开原因和数据连接失败原因 |

### 6.4 百度定位 SDK

- 完整嵌入百度定位 SDK（`com.baidu.location.f` 服务 + `libindoor.so` + `liblocSDK8b.so`）
- 定位精度：`Hight_Accuracy`（高精度模式，GPS + 网络混合）
- 坐标系：`bd09ll`（百度坐标系）
- API Key 硬编码：`IGY6EFGRsixbYqk7GeZdAOD2Od3Rj7iF`
- 每 1 小时触发一次定位采集

### 6.5 AIDL 服务接口

`RegisterHelperService` 通过 AIDL (`IRegisterHelperService`) 暴露以下接口：

| Transaction Code | 方法 | 功能 |
|---|---|---|
| 1 | `c(String)` | 设置服务器地址 |
| 2 | `a(int)` | 设置各定时器间隔（按比例计算） |
| 3 | `e()` | 清除 SharedPreferences |
| 4 | `d(boolean)` | 启用/禁用日志 |
| 5 | `b()` | 查询日志启用状态 |

> 此服务需签名权限 `com.mobiletools.systemhelper.permission`，仅同签名应用可调用。  
> **源码位置**：`service/a.java`、`service/RegisterHelperService.java`

---

## 七、隐私侵犯分析

### 7.1 侵犯的隐私类型总表

| 隐私类别 | 具体内容 | 严重程度 | 与网络监测的相关性 |
|---|---|---|---|
| **设备唯一标识** | IMEI1/IMEI2 | 🔴 极高 | 低（可用设备型号替代） |
| **用户身份标识** | IMSI1/IMSI2（部分脱敏） | 🔴 极高 | 低（可用 MNC 替代） |
| **SIM 卡标识** | ICCID1/ICCID2（部分脱敏） | 🔴 极高 | 低 |
| **精确位置** | 经纬度 + 海拔 + 行政区划 | 🔴 极高 | 中（基站定位足够） |
| **WiFi 追踪** | BSSID（路由器 MAC） | 🔴 极高 | 中 |
| **蓝牙设备隐私** | 全部配对设备名称 + MAC | 🔴 极高 | **无（完全无关）** |
| **应用使用画像** | 全部第三方应用包名 + 使用时长 + 启动次数 + 流量 | 🔴 极高 | **无（完全无关）** |
| **基站位置** | CID/TAC/PCI | 🟠 高 | 高（合理） |
| **IPv6 地址** | 设备网络地址 | 🟠 高 | 低 |
| **通话/数据故障** | 断话原因 + 数据失败码 | 🟡 中 | 高（合理） |
| **信号质量** | RSRP/RSRQ/SINR/CQI | 低 | 高（合理） |

### 7.2 核心隐私问题

#### 问题 1：无用户知情同意机制

- 唯一 Activity `TestActivity` 需要签名权限 (`com.mobiletools.systemhelper.permission`)，普通用户无法访问
- **无用户界面、无隐私政策、无同意对话框**
- 仅检查系统设置 `operator_service_improve_plan`（仅 vivo）或 `device_provisioned`（其他品牌）作为"权限门"
- `device_provisioned == 1` 仅表示开机向导完成，**不等同于用户同意数据收集**

#### 问题 2：开机自启 + 持续后台运行

- 最高优先级 `BOOT_COMPLETED` 监听 (priority = 2147483647)
- `AlarmManager.setExactAndAllowWhileIdle()` 绕过 Doze 模式
- **每 1 小时一次的 GPS 定位采集**
- TelephonyCallback 持续监听通话状态
- 无停止机制

#### 问题 3：过度收集应用使用数据

- `QUERY_ALL_PACKAGES` + `PACKAGE_USAGE_STATS` 权限组合
- 枚举**所有第三方已安装应用**，记录每个应用的：
  - 包名和应用名称
  - 前台使用时长（分钟）
  - 启动次数
  - 分 SIM 卡的上行/下行流量
  - WiFi 流量消耗
- 这构成了**完整的用户行为画像**，与网络质量监测目的完全无关
- 排除 `com.android.*` 但不排除社交媒体、银行、医疗等敏感应用

#### 问题 4：蓝牙配对设备泄露

- 收集**所有**已配对蓝牙设备的名称和 MAC 地址
- 蓝牙配对设备列表可能包含：耳机、手表、车载设备、医疗设备等
- **与网络质量监测毫无关系**，属于明显的过度收集

#### 问题 5：HTTP 降级传输

- HTTPS 证书验证失败时自动降级为 HTTP
- `usesCleartextTraffic="true"` 允许明文流量
- 虽然加密了 body，但 URL 参数暴露了 model、manuf、sign 信息
- 降级逻辑：`i1/f.java` 检测 `SSLException` → 设置 `f2565d = true` → `g1/g.java` 触发 HTTP fallback

#### 问题 6：数据持久化与强制重试

- 失败数据存入 SQLite，确保最终一定会上传
- 信号数据最多缓存 100 条
- 注册数据最多缓存 10 条
- `HistoryPostThread` 在每次成功注册后自动重试上传历史数据
- 用户无法手动清除这些缓存（除非清除应用数据）

#### 问题 7：特权权限

- `READ_PRIVILEGED_PHONE_STATE`：系统级权限，普通第三方应用无法获取
- `READ_PRECISE_PHONE_STATE`：精确电话状态
- 共享 UID `com.mobiletools.systemhelper.uid`：暗示作为系统应用预装
- 这些权限表明该应用可能预装在 `/system` 或 `/product` 分区

### 7.3 轻微缓解措施（不改变整体定性）

| 措施 | 说明 | 局限 |
|---|---|---|
| IMSI/ICCID 截断脱敏 | 前 10 位 + `*****` / `**********` | 前 10 位仍有识别力 |
| 仅中国网络上传 | MNC 以 460 开头才上传 | 中国境内用户全覆盖 |
| AES-GCM + RSA 加密 | 数据传输加密 | HTTP 降级时 URL 参数泄露 |
| 排除系统应用 | `com.android.*` 不统计 | 第三方应用全覆盖 |
| 证书校验 | `checkServerTrusted` 做了 `checkValidity()` | 降级为 HTTP 时无意义 |

---

## 八、合理范围 vs 实际收集对比

| 数据类别 | 网络监测合理范围 | 实际收集 | 越界 |
|---|---|---|---|
| 蜂窝信号质量 | ✅ RSRP/SINR/CQI | ✅ 收集了 | — |
| 网络类型 | ✅ LTE/NSA/SA | ✅ 收集了 | — |
| 基站信息 | ✅ CID/TAC/PCI | ✅ 收集了 | — |
| 通话/数据故障 | ✅ 断话原因/失败码 | ✅ 收集了 | — |
| 设备型号 | ✅ Manuf/Model | ✅ 收集了 | — |
| IMEI | ❌ 不需要 | 🔴 收集了 | **越界** |
| IMSI | ❌ 不需要 | 🔴 收集了 | **越界** |
| ICCID | ❌ 不需要 | 🔴 收集了 | **越界** |
| 蓝牙配对设备 | ❌ 完全无关 | 🔴 收集了 | **严重越界** |
| WiFi BSSID | ⚠️ 有争议 | 🔴 收集了 | **越界** |
| GPS 精确定位 | ⚠️ 基站定位足够 | 🔴 收集了 | **越界** |
| 应用使用统计 | ❌ 完全无关 | 🔴 收集了 | **严重越界** |
| IPv6 地址 | ❌ 不需要 | 🔴 收集了 | **越界** |
| eSIM 状态 | ⚠️ 有争议 | 收集了 | 轻微越界 |

---

## 九、文件结构索引

```
APK 内部结构
├── AndroidManifest.xml          # 清单文件
├── classes.dex                  # 单 DEX (1.3MB, 561 类)
├── assets/
│   └── cudms_main_pub.cer       # RSA 公钥证书
├── lib/arm64-v8a/
│   ├── libindoor.so             # 百度室内定位 (1.5MB)
│   └── liblocSDK8b.so           # 百度定位 SDK (350KB)
├── res/
│   └── values/strings.xml       # app_name = "SystemHelper"
└── META-INF/                    # 签名文件 (27 条目)

反编译输出
├── decoded/                     # apktool 解包 (smali + resources)
└── jadx_out/                    # JADX 反编译 Java 源码
    └── sources/
        ├── com/mobiletools/systemhelper/   # 应用包
        │   ├── Activity/TestActivity.java
        │   ├── control/                    # 7 个 Receiver + DMApplication
        │   └── service/                    # 3 个 Service + AIDL 接口
        ├── com/baidu/location/             # 百度定位 SDK
        ├── g1/                             # 核心逻辑 (7 类)
        ├── h1/                             # 设备读取器 (16 类)
        ├── i1/                             # 工具层 (14 类)
        └── n0/                             # 百度定位 SDK 内部 (10 类)
```

---

## 十、最终结论

**SystemHelper 是中国联通预装在渠道设备上的设备管理与网络遥测客户端**。其名义用途是网络质量监测和设备注册，但实际收集范围远超网络监测所需。

该应用存在**严重且系统性的隐私侵犯行为**：

1. **在用户无感知的情况下**（无 UI、无同意对话框、无隐私政策），持续收集设备唯一标识（IMEI/IMSI/ICCID）、精确 GPS 位置、WiFi 路由器 MAC、全部蓝牙配对设备、以及完整的第三方应用使用画像，加密上传至中国联通服务器 `dm.wo.com.cn`。

2. **应用使用统计和蓝牙配对设备信息**与网络质量监测目的完全无关，属于明确的过度收集，构成完整的用户行为画像。

3. 开机自启 + 每小时定位 + 精确定时器绕过 Doze + 失败缓存强制重试，确保数据**持续、最终一定上传**，用户无法感知也无法阻止（除非卸载或禁用应用）。

4. 使用 `READ_PRIVILEGED_PHONE_STATE` 等系统级权限，共享 UID，表明作为系统应用预装，普通用户难以卸载。

---

*报告完毕。反编译产物保留于工作区 `decoded/` 与 `jadx_out/` 目录供进一步取证。*
