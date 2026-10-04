# MusicHapticsX

LSPosed 音乐触觉模块。把目标应用的 PCM 音频送入轻量 Native DSP，识别瞬态、频段和节奏事件，再根据执行器特性合成为短促、可控的触觉反馈。

MusicHapticsX 让真正有意义的瞬间落在对的位置——不是把音乐“变成震动”，而是把瞬态、身体感与微细纹理重新编排成触觉。

## 核心能力

- **AudioTrack Hook**：覆盖常见 `write(ByteArray/ShortArray/FloatArray/ByteBuffer, …)` 写入路径，并跟踪 `play/pause/stop/flush/release` 生命周期。
- **Application.attach 初始化**：不依赖早期 `currentApplication()`，先取得目标应用自己的 `Context`，再启动 Native 层。
- **应用白名单**：LSPosed 作用域负责“注入谁”，MusicHapticsX 白名单负责“处理谁”。默认采用白名单模式，配置损坏或缺失不会退化成全局处理。
- **双层音频来源**：优先 AudioTrack PCM；没有 PCM 写入时，可延迟启用 Visualizer 作为兼容回退。
- **Native DSP**：固定尺寸缓冲、NEON RMS、5 个分析频段、512 点窗函数 FFT、谱流量、频谱质心、自相关基频估计、瞬态检测与分层事件概率。
- **语义触觉**：KICK / SNARE / VOCAL / BODY 四层语义输出，避免把整首音乐压成单一音量曲线。
- **LRA 触觉成形**：事件先经过 compact impact policy，再由 `HapticSynthesizer.sculptImpact()` 按执行器 rise / fall / Q 生成短时多段包络；连续触觉只作为很薄的背景层，避免“马达一直搓”。
- **分层输出策略**：Native 负责精确找拍，Java 触觉层负责最终渲染裁决；Strike-only 节点也会先套用当前风格、强度和 DeviceTuning 的 sculpted envelope，再经 Native 直驱发送。具备持续幅值控制的驱动器使用约 100 Hz 的平滑控制；Android Vibrator 则在校准 Primitive 与定制 waveform 之间按设备能力选择。
- **多机型深度适配**：`DeviceProfile` 负责执行器模型，`DeviceTuningRegistry` 负责输出时序/增益/冷却，二者共同进入 Kotlin 事件层与 Native DSP。
- **LiquidGlass UI**：控制台、应用列表、参数面板和底部导航统一接入 AndroidLiquidGlass / Backdrop 源码；没有玻璃渲染能力时仍有低成本降级层。

## 架构

```text
                 ┌─────────────────────────────┐
                 │       LSPosed / Zygote       │
                 └──────────────┬──────────────┘
                                │ inject
                 ┌──────────────▼──────────────┐
                 │       HookCoordinator       │
                 │ attach + AudioTrack + life  │
                 └──────────────┬──────────────┘
                                │ PCM only
                 ┌──────────────▼──────────────┐
                 │         AudioIngress         │
                 │ bounded queue / direct buf  │
                 └──────────────┬──────────────┘
                                │ one worker
                 ┌──────────────▼──────────────┐
                 │          Native DSP          │
                 │ bands / FFT / flux / pitch  │
                 │ onset / semantic envelopes  │
                 └──────────────┬──────────────┘
                                │ semantic events
                 ┌──────────────▼──────────────┐
                 │      HapticImpactPolicy     │
                 │ compact timing + gating       │
                 └──────────────┬────────────────┘
                                │
                 ┌──────────────▼──────────────┐
                 │ HapticSynthesizer           │
                 │ actuator-aware impact shape │
                 │ attack / body / tail        │
                 └──────────────┬──────────────┘
                                │
              ┌─────────────────┴──────────────────┐
              │                                    │
      ┌───────▼────────┐                    ┌───────▼────────┐
      │ direct actuator │                   │ Android Vibrator │
      │ strike / 100Hz  │                   │ primitive / wave │
      └─────────────────┘                  └─────────────────┘
```

### 为什么这样拆

Hook 线程只做拦截、轻量参数读取和 PCM 归一化。FFT、滤波、概率更新、温升模型和触觉合成都不在宿主应用的音频写线程上执行，避免把 DSP 延迟直接带回播放器。

音频处理使用有界的 16 槽队列，每槽最多 256 个单声道帧。队列满时丢弃当前批次并记录丢帧，而不是无限增长；Native DSP 使用固定数组，没有每块音频都发生的堆分配。

## 音频分析链路

当前实现没有把大型 Java DSP 框架直接塞进 Hook 进程，而是保留自有 Native 分析核心：

1. **输入整形**：PCM16 / float PCM / ByteBuffer 统一混成单声道分析流，并限制到合理范围。
2. **频段分离**：约 80–180 Hz、180–500 Hz、500–3000 Hz、3–8 kHz、8 kHz 以上，用于鼓组、身体感、声乐和高频瞬态判断。
3. **能量特征**：RMS、绝对值、零交叉率、动态变化量。
4. **频谱特征**：512 点窗函数 FFT、谱流量、低频谱流量、高频谱流量、频谱质心。
5. **音高 / 周期性**：节流后的自相关估计，用于提升有明确基频的内容稳定性。
6. **瞬态检测**：以“攻击变化”而不是“当前音量”作为事件触发依据，并为不同频段设置独立 refractory。
7. **语义分层**：把结果整理成 KICK、SNARE、VOCAL、BODY，再交给触觉策略层。

### 高级触感渲染策略

5.3.x 起，实际输出不再默认走“每个 onset 调一次 DynamicEffect”的路径。原因不是 DynamicEffect 不高级，而是逐事件重启效果会把连续事件切成互相覆盖的独立片段，快节奏音乐尤其容易出现“啪、啪、啪”的廉价感。

当前路径按优先级处理：

```text
PCM
 │
 ├─ true RMS + crest + band flux + FFT novelty
 │
 ▼
Semantic Onset
 │
 ├─ KICK   → 快起音 + 有限低频尾
 ├─ SNARE  → 短促高峰 + 极短纹理尾
 ├─ VOCAL  → 低幅柔性 accent
 └─ BODY   → 稀疏、低幅、长一点的支撑
 │
 ▼
Compact Impact Policy
 │
 ▼
Actuator-aware Sculpting
 │
 ├─ Primitive composition（设备有校准 primitive 时）
 └─ Multi-segment waveform（需要精细幅度曲线时）
```

几个原则是刻意固定下来的：**攻击一定比主体短，主体一定比尾巴更重要，尾巴不能覆盖下一次拍点；连续层只能增加“存在感”，不能抢走瞬态。** 这比简单增加振幅更接近高质量 Taptic / LRA 设计的听感目标。

实时队列也以“新鲜度优先”：DSP worker 在负载上升时主动跳过过时 block，只保留最近的小窗口，避免 UI / 播放器抖动把触觉拖到几十毫秒以后。PCM16 ByteBuffer 路径明确按 little-endian 解码，避免不同调用方的 ByteOrder 造成整条分析链失真。

### 外部 DSP 项目的取舍

TarsosDSP 提供了很丰富的 pitch、onset、beat、FFT 等算法，但当前仓库不会直接引入它：上游仓库标明为 GPL-3.0，而 MusicHapticsX 本体采用 MIT，并且 Hook 进程需要尽量小的依赖闭包。[TarsosDSP](https://github.com/JorenSix/TarsosDSP)

KISS FFT 是适合进一步替换固定 FFT 核的 BSD-3-Clause 方案，且提供 real FFT；本版本暂时继续使用仓库内的 512 点定长实现，因为它已经满足当前设备目标的实时性和尺寸要求，没有为了“看起来更高级”而增加一个没有实际收益的二进制依赖。[KISS FFT](https://github.com/mborgerding/kissfft)

## 触觉链路

MusicHapticsX 的触觉输出分为两类：

**事件型触觉**负责 KICK / SNARE / VOCAL / BODY 的离散冲击。每个事件经过 `HapticImpactPolicy`：先根据设备执行器参数决定总时长，再生成 attack / sustain / decay 三段，最后套上最小间隔与强度门槛。

**连续型触觉**只用于真正支持持续幅值控制的节点。对于  `activate` / AW8697 / AW86224 一类“一次写入就触发波形”的节点，Native scheduler 会自动切成 **strike-only** 模式：瞬态直接触发短冲击，不再把一个 one-shot 节点当成 200 Hz 连续幅值口反复写入。

这样可以避开最常见的一类问题：事件看起来“很强”，但因为驱动节点语义不对，最终变成持续重触发、发糊、尾振长、快歌全部粘成一片。

## 多机型深度适配

适配链已经改成三层：`DeviceProfile.kt` 负责解析并承载执行器模型，`DeviceTuning.kt` 负责每个已有命名档案的输出修正，Native `configureProfile()` 再把 DSP 域参数接进去。也就是说，之前已经写进 `DeviceProfile.kt` 的机型现在不会只停留在“识别名称”这一步。Xiaomi 14 Pro 的 `shennong`、Xiaomi 15 Pro 的 `haotian`、Xiaomi 17 Pro 的 `pandora` 也纳入了指纹解析，并保留旧移植 ROM 的兼容别名。13/14/15 Ultra 也统一落入已有的 `XIAOMI_ULTRA` 深度档，而不是直接走通用旗舰 fallback。

```text
Build / Root fingerprint
        │
        ▼
DeviceProfile        ← 执行器模型 + DSP floor / band multipliers
        │
        ├──────────────► Native DSP profile controls
        │                 灵敏度 / 低频增益 / onset 权重 / refractory
        │
        └──────────────► DeviceTuningRegistry
                          时长 / 增益 / 最小强度 / 冷却 / 振幅上限
                                      │
                                      ▼
                              HapticImpactPolicy
                                      │
                                      ▼
                               VibrateProxy output
```

当前命名档案包括：

| 厂商 / 家族 | 已深度适配档案 | 调音重点 |
|---|---|---|
| Xiaomi | Mi 10 (`umi/cmi/thyme`)、11、12、13、13 Pro、13/14/15 Ultra、14 Pro、15、15 Pro、17 Pro、MIX Fold | 瞬态长度、低频纹理、默认振幅兼容、RichFeel/宽频路线 |
| Redmi | K40、K50 Gaming、K60、K70、K70 Ultra、K80 Ultra | 0809 / 宽频 / 慢 Z 轴差异、事件冷却与低频占比 |
| OnePlus | 9、10 Pro、11、12、13、13T、Ace 3 Pro、Ace3/Ace5、15 | 旗舰高速瞬态、0916/0815/0809 家族差异、primitive fallback |
| OPPO | Reno8 Pro | 小体积执行器的保守增益与更长恢复 |
| Lenovo | Legion Y700 Gen1 / Gen2+ | 平板双执行器与单执行器的事件节奏差异 |
| Samsung | S25 | primitive 友好路线、短冲击 |
| vivo / iQOO | Flagship / V23-V24 兼容档 | 高速 X-axis 与 IPC fallback |
| 其他现代旗舰 | `FLAGSHIP_XAXIS` | 仅在没有更精确命名档案时作为保守 fallback |

这里的“深度适配”指**渲染策略已经实际使用这些档案**，不表示每个执行器参数都是厂商公开规格。执行器数值与机型曲线属于项目内的经验模型；未知设备仍然走 fallback

### 机型档案现在真正影响什么

`DeviceProfile.kt` 中的 `dspEnergyFloor / dspSubMult / dspKickMult / dspSnareMult / dspTickMult / dspBodyMult / dspRefractoryScale` 已经通过 `NativeBridge.configureProfile()` 进入 C++。Native DSP 会据此调整低频分析增益、KICK/SNARE onset 门槛与权重、高频 texture 概率以及各类 onset 的 refractory；同时修正 VOCAL / BODY 的归一化门控，避免错误的“先压到 0.05、再要求大于 0.20”导致这两类事件永远不会出现。

`DeviceTuning.kt` 则负责输出端的第二层修正：KICK / SNARE / TICK / BODY 时长、事件增益、最小触发强度、cooldown、振幅上限与 `DEFAULT_AMPLITUDE` 偏好。这样同一个“鼓点强度”在高速旗舰和慢响应执行器上不会再使用完全相同的 envelope。

## Hook 设计

### 第一层：LSPosed 作用域

只允许 LSPosed 注入到用户明确勾选的应用。

### 第二层：MusicHapticsX 白名单

配置文件：

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

`mode=all` 可以用于调试。正式使用建议保持 `whitelist`。

### 第三层：目标进程内 Hook

`HookCoordinator` 只关注这些入口：

- `Application.attach()`
- `AudioTrack` 构造与 `write(...)`
- `AudioTrack.play/pause/stop/flush/release`
- `MediaPlayer` / `SoundPool` 生命周期，用于 Visualizer 回退

不会去全量扫描与音乐无关的方法，也不会在音频写线程里创建长期存活的后台任务。

### 配置 IPC

目标应用通过只读 `ContentProvider` 获取配置。Provider 同时检查调用方包名、目标包名格式和白名单状态；硬件探测结果、触觉参数等只提供给已启用目标进程读取。

## LiquidGlass

UI 采用独立 `:liquidglass` Android library module，当前直接集成 AndroidLiquidGlass / Backdrop 的 Android/Kotlin 源码，并保留 Apache-2.0 文本。

上游项目当前公开仓库为 `Kyant0/AndroidLiquidGlass`，项目采用 Apache-2.0；其 Backdrop 模块当前发布坐标为 `io.github.kyant0:backdrop:2.0.1`。

本项目没有把 LiquidGlass 当成一个“装饰底栏”，而是统一到：

- 页面背景与 backdrop layer；
- 主状态卡片；
- 设置面板；
- 应用白名单列表；
- 分段选择器；
- 底部 Tab Bar。

同时保留无 backdrop 能力时的普通半透明背景降级，避免 UI 依赖单一渲染路径。

## 项目结构

```text
MusicHapticsX/
├─ app/src/main/java/com/mouya/musichaptics/
│  ├─ hook/             HookCoordinator / TrackRegistry / config IPC view
│  ├─ audio/            PCM ingress queue
│  ├─ haptic/           device tuning / impact policy
│  ├─ phira/            Phira 专用时序逻辑
│  └─ ui/               控制台日志组件
├─ app/src/main/cpp/
│  ├─ haptic/           固定内存 Native DSP
│  └─ jni/              scheduler / actuator transport / JNI bridge
├─ liquidglass/         AndroidLiquidGlass / Backdrop 集成模块
├─ docs/                架构与开发说明
├─ scripts/             仓库级检查脚本
└─ .github/workflows/   CI
```

## 构建

### GitHub Actions

CI 会安装 JDK 17、Android SDK、NDK 27.0.12077973 与 CMake 3.22.1，然后分别构建 Debug / Release APK，并执行仓库洁净度检查。

### 本地

需要：

- JDK 17
- Android SDK 34
- NDK 27.0.12077973
- CMake 3.22.1

```bash
./gradlew assembleDebug
./gradlew assembleRelease
python3 scripts/repo_check.py
```

当前发布目标只编译 `arm64-v8a`，原因是本项目目标设备与 Native DSP 优化集中在 ARM64 Android。

## 验证重点

实机验证时建议按这个顺序看：

1. 关闭模块时，目标 App 的音频写入与播放正常；
2. 只开启一个播放器，确认白名单包才会收到 Hook；
3. 播放静音 / 人声 / 鼓点 / 密集电子乐，观察是否出现持续底震；
4. 快歌连续 8 分音符或 16 分音符时，确认事件不会叠成“电机嗡鸣”；
5. 至少覆盖一个高速旗舰（如 Xiaomi 14/15/OnePlus 13）、一个中速档（如 Xiaomi 11/Redmi K70）和一个慢响应档（如 Redmi K80U/OPPO Reno8 Pro），确认时长与冷却确实随 profile 变化；
6. 分别测试直驱节点可用和不可用两种路径；
7. 暂停 / 切歌 / App 切后台后，确认震动在生命周期结束后释放；
8. 在没有 AudioTrack PCM 的应用中，确认 Visualizer 回退不会与 PCM 路径双驱动；
9. 长时间循环播放，观察温升模型与实际触感有没有明显失真。

## 已知限制

- 无法保证所有厂商的 `/sys` 触觉节点都具有相同语义；未知机型优先使用 Android Vibrator 回退。
- `Visualizer` 是兼容路径，不等价于直接 PCM Hook；延迟、频响和动态范围都会不同。
- Root 直驱涉及厂商驱动节点与 SELinux 策略，设备升级后节点名称或权限可能发生变化。
- 小米部分设备的调音是针对实机路线，不代表其他 Xiaomi / Redmi 设备可以直接复用同一参数。

## 第三方与许可

MusicHapticsX 主体：MIT License。

第三方声明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

本仓库不再包含字体文件，也不会把 GPL-3.0 DSP 框架作为运行时依赖直接打进模块。

## 致谢

- [AndroidLiquidGlass / Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) — LiquidGlass / Backdrop 渲染实现，Apache-2.0。
- [KISS FFT](https://github.com/mborgerding/kissfft) — 作为后续 FFT 替换方案的参考，BSD-3-Clause。
- Android / Jetpack Compose / LSPosed 生态。

## 变更记录

- [`CHANGED.md`](CHANGED.md) — 面向用户和维护者的版本变更。
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — 运行链路、线程模型与硬件输出说明。

