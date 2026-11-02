# MusicHapticsX

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://www.android.com/)
[![LSPosed](https://img.shields.io/badge/LSPosed-Module-6F42C1)](https://github.com/LSPosed/LSPosed)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![C++](https://img.shields.io/badge/Native-C%2B%2B-00599C?logo=cplusplus&logoColor=white)](https://isocpp.org/)
[![DSP](https://img.shields.io/badge/Audio-DSP-111827)](#音频分析)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/mouya-q/MusicHaptics/build.yml?label=Build)](../../actions)

**简体中文** | [English](README_EN.md)

> 一个面向 Android 的 LSPosed 音乐触觉模块。  
> 从播放器获取 PCM 音频，提取瞬态、频段与节奏信息，再结合设备执行器特性生成短促、动态的触觉反馈。

MusicHapticsX 的目标不是让手机“跟着音乐一直震”，而是让真正有意义的音乐事件落在触觉上：鼓点更有冲击，人声保持轻柔，低频形成身体感，同时避免连续轰鸣。

> MusicHapticsX 是独立开源项目，与 Xiaomi、HyperOS、Apple、LSPosed 或任何音乐服务提供商不存在官方隶属或背书关系。

---

## 目录

- [特性](#特性)
- [工作方式](#工作方式)
- [音频分析](#音频分析)
- [触觉合成](#触觉合成)
- [设备适配](#设备适配)
- [安装](#安装)
- [配置](#配置)
- [项目结构](#项目结构)
- [构建](#构建)
- [验证](#验证)
- [已知限制](#已知限制)
- [第三方与许可](#第三方与许可)

---

## 特性

- **AudioTrack Hook** — 覆盖常见 `write(ByteArray / ShortArray / FloatArray / ByteBuffer, …)` 路径，并跟踪播放生命周期。
- **PCM 优先** — 直接处理播放器 PCM；没有可用 PCM 时可延迟启用 Visualizer 作为兼容回退。
- **Native DSP** — C++ 固定内存分析核心，包含 RMS、五段频带、512 点 FFT、谱流量、谱质心、自相关基频估计和瞬态检测。
- **语义事件** — 将音乐拆分为 `KICK / SNARE / VOCAL / BODY`，而不是简单跟随总音量。
- **动态触觉** — 事件强度会影响振幅、持续时间与 envelope，不再让所有鼓点变成同一个力度。
- **执行器感知** — 根据设备档案调整增益、时长、冷却、频段权重和输出策略。
- **Strike-only 支持** — 对 AW8697 / AW86224 等 one-shot 节点避免错误地当作连续幅值接口使用。
- **应用白名单** — LSPosed 负责注入范围，MusicHapticsX 再通过独立白名单决定实际处理哪些应用。
- **LiquidGlass UI** — 设置、应用列表和控制台采用独立 `:liquidglass` 模块，并提供普通半透明降级路径。
- **ARM64 Native** — 发布目标集中于现代 ARM64 Android 设备。

---

## 工作方式

MusicHapticsX 将音频处理、事件判断和触觉输出拆成独立阶段：

```text
LSPosed
   │
   ▼
HookCoordinator
   │  AudioTrack PCM
   ▼
AudioIngress
   │  bounded queue
   ▼
Native DSP
   │  onset / bands / rhythm
   ▼
Semantic Events
   │  KICK / SNARE / VOCAL / BODY
   ▼
HapticImpactPolicy
   │  timing / gating
   ▼
HapticSynthesizer
   │  actuator-aware envelope
   ├───────────────┐
   ▼               ▼
Direct Drive    Android Vibrator
```

Hook 线程只负责拦截、轻量参数读取和 PCM 归一化。FFT、滤波、概率更新和触觉合成不会直接运行在宿主播放器的音频写线程上。

音频入口使用有界 16 槽队列，每槽最多 256 个单声道帧。负载过高时优先丢弃过时数据，而不是让延迟无限增长。

---

## 音频分析

当前 Native DSP 主要包含：

1. **输入整形** — PCM16、Float PCM、ByteBuffer 统一为单声道分析流。
2. **频段分析** — 约 80–180 Hz、180–500 Hz、500–3000 Hz、3–8 kHz、8 kHz 以上。
3. **能量特征** — RMS、绝对幅度、零交叉率和动态变化。
4. **频谱特征** — 512 点窗口 FFT、谱流量、低/高频谱流量和谱质心。
5. **周期性分析** — 节流后的自相关基频估计。
6. **瞬态检测** — 关注 attack / onset 变化，而不是简单读取当前音量。
7. **语义分层** — 将结果转换成 KICK、SNARE、VOCAL、BODY。

### 为什么不用“整首歌跟随音量”

单纯 RMS → vibration 会产生一个明显问题：音乐越响，手机越一直震；鼓点、声乐和背景低频没有区别。

MusicHapticsX 使用瞬态和频段信息先判断“发生了什么”，再决定“应该怎么震”。

---

## 触觉合成

事件进入 `HapticImpactPolicy` 后，会根据设备参数生成短时 envelope：

```text
Semantic Event
      │
      ├─ KICK   → fast attack + controlled low-frequency body
      ├─ SNARE  → short peak + texture tail
      ├─ VOCAL  → soft accent
      └─ BODY   → sparse low-amplitude support
      │
      ▼
Compact Impact Policy
      │
      ▼
Actuator-aware Sculpting
      │
      ├─ calibrated primitive
      └─ multi-segment waveform
```

核心原则：

- attack 要短；
- body 要比 tail 更重要；
- tail 不能覆盖下一拍；
- 连续层只能增加存在感，不能抢走瞬态；
- 快歌优先保持事件新鲜度，而不是积压旧事件。

5.4.x 进一步修正了动态强度和持续时间：强弱拍不再全部撞到同一个振幅上，事件持续时间也会随强度变化。

---

## 设备适配

设备适配由三层组成：

```text
DeviceProfile
      │
      ├── actuator model
      ├── DSP floor / band multipliers
      └── event weights
              │
              ▼
DeviceTuning
      │
      ├── duration
      ├── gain
      ├── cooldown
      └── amplitude ceiling
              │
              ▼
HapticImpactPolicy
              │
              ▼
VibrateProxy / direct drive
```

当前源码包含 Xiaomi、Redmi、OnePlus、OPPO、Lenovo、Samsung、vivo / iQOO 等设备家族的命名档案，并为未知设备保留通用 fallback。

> “深度适配”表示档案参数实际参与渲染决策，不代表所有执行器参数都是厂商公开规格。部分曲线属于项目的实机经验模型。

---

## 安装

### 前置条件

- Android 8.0+ / API 28+（当前 APK `minSdk = 28`）
- ARM64 Android 设备
- Root
- LSPosed 或兼容的 Xposed API 82+ 框架

### 步骤

1. 安装 MusicHapticsX APK。
2. 在 LSPosed 中启用模块。
3. 将需要处理的音乐应用加入 LSPosed scope。
4. 在 MusicHapticsX 中启用对应应用的白名单。
5. 强制停止目标应用并重新打开。
6. 播放音乐并观察控制台日志。

正式使用建议保持 `whitelist` 模式，不要使用 `mode=all`。

---

## 配置

白名单文件：

```text
/data/adb/musichaptics/whitelist.conf
```

示例：

```text
mode=whitelist
com.netease.cloudmusic
com.tencent.qqmusic
com.spotify.music
```

`mode=all` 仅用于调试。

运行时配置通过只读 `ContentProvider` 同步到目标进程。Provider 会检查调用方包名、目标包名格式和白名单状态。

---

## 项目结构

```text
MusicHapticsX/
├─ app/
│  └─ src/main/
│     ├─ java/com/mouya/musichaptics/
│     │  ├─ hook/          HookCoordinator / TrackRegistry / IPC
│     │  ├─ audio/         PCM ingress
│     │  ├─ haptic/        device tuning / impact policy
│     │  ├─ phira/         Phira timing
│     │  └─ ui/             dashboard / console
│     └─ cpp/
│        ├─ haptic/         Native DSP / haptic engine
│        └─ jni/            JNI bridge / scheduler
├─ liquidglass/              LiquidGlass / Backdrop integration
├─ docs/                     architecture notes
├─ scripts/                  repository checks
└─ .github/workflows/        CI
```

---

## 构建

### 环境

- JDK 17
- Android SDK 35
- Android NDK `27.0.12077973`
- CMake `3.22.1`
- Gradle 8.9
- Android Gradle Plugin 8.7.0
- Kotlin 2.0.21

### Debug

```bash
./gradlew assembleDebug
```

### Release

```bash
./gradlew assembleRelease
```

### Repository check

```bash
python3 scripts/repo_check.py
```

Release 构建在 CI 中使用固定签名密钥；本地或没有 CI secrets 的构建会回退到 debug signing。

当前 Native 发布目标为：

```text
arm64-v8a
```

---

## 验证

建议至少验证以下场景：

1. 模块关闭时目标播放器正常播放。
2. 只启用一个播放器，确认只有白名单应用被处理。
3. 测试静音、人声、鼓点和密集电子音乐。
4. 测试连续 8 分音符 / 16 分音符，确认不会变成持续电机声。
5. 对不同设备 profile 检查时长和 cooldown 是否确实发生变化。
6. 分别验证 direct-drive 和 Android Vibrator fallback。
7. 暂停、切歌、退出后台后确认触觉及时释放。
8. 没有 AudioTrack PCM 的应用验证 Visualizer fallback。
9. 长时间播放后检查温升模型与实际触感是否稳定。

---

## 已知限制

- 不同厂商 `/sys` 触觉节点没有统一语义；未知设备优先使用 Android Vibrator fallback。
- Visualizer 是兼容路径，不等价于直接 PCM Hook，延迟和动态范围会不同。
- Root direct-drive 依赖厂商节点与 SELinux 策略，系统升级后可能变化。
- 设备调音来自项目实机测试，不意味着同一参数适用于同厂商所有设备。
- 当前发布只提供 `arm64-v8a` Native 库。

---

## 第三方与许可

MusicHapticsX 主体采用 **MIT License**。

第三方声明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

项目中的 LiquidGlass / Backdrop 源码保留其 Apache-2.0 许可文件。

感谢：

- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) — LiquidGlass / Backdrop 实现。
- [KISS FFT](https://github.com/mborgerding/kissfft) — FFT 方案参考。
- Android / Jetpack Compose / LSPosed 社区。

---

## 文档

- [`README_EN.md`](README_EN.md) — English documentation
- [`CHANGED.md`](CHANGED.md) — version changes
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — runtime architecture
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — contribution guide
- [`SECURITY.md`](SECURITY.md) — security notes
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) — third-party licenses
