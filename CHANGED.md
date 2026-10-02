# CHANGED

## 5.1.0 — 2026-10-02

这次是在 5.0 架构基础上，把“机型适配”从少数 Build 判断提升成真正贯穿 DSP → 事件层 → 输出层的多机型适配链。

### 多机型深度适配

- `DeviceTuningRegistry` 不再只识别 `umi / cmi / thyme`，现在覆盖 `DeviceProfile.kt` 中全部命名机型档案：Xiaomi / Redmi / OnePlus / OPPO / Lenovo / Samsung / vivo/iQOO；`FLAGSHIP_XAXIS` 只作为现代旗舰 fallback。
- `HapticImpactPolicy` 改为按已经解析完成的 `DeviceProfile` 取 tuning，避免再次用一套 `Build.DEVICE` 判断覆盖掉 Root fingerprint 已确定的机型。
- `VibrateProxy.init(profile)` 接收最终 profile；`DEFAULT_AMPLITUDE` 兼容策略不再硬编码为 Xiaomi 10 条件。
- `DeviceProfile` 的 DSP 参数现在通过新增 JNI 配置入口真正进入 Native：low-band profile gain、KICK/SNARE onset 权重、DSP floor、high-frequency texture 权重和 per-band refractory；同时修正 VOCAL / BODY onset 的归一化门控，避免原有阈值组合导致永远触发不到。
- `RootHardwareProbe` 的 fingerprint 解析与 `DeviceProfile` 保持同一机型矩阵；补齐 Xiaomi 13/14/15 Ultra、14 Pro、15 Pro、17 Pro 的 codename/model 别名，并保留移植 ROM 旧别名；同时补充 AW86224 / Qualcomm haptic 家族的已知入口扫描。
- 对 Xiaomi Ultra / Redmi K70 Ultra / K80 的判断增加优先级，避免产品名中的 `Ultra` 把 Redmi Ultra 机型误判成 Xiaomi Ultra；13/14/15 Ultra 均能命中已有 `XIAOMI_ULTRA` 档。
- 增加针对慢响应 Z-axis / 小体积执行器 / 高 Q 高速执行器 / 宽频执行器的不同事件冷却、时长、增益和振幅上限。
- `scripts/repo_check.py` 增加 profile 覆盖检查，后续新增 `DeviceProfile` 档案但没有在 `DeviceTuningRegistry` 中显式处理时会被 CI 提醒。

### 震动引擎

- 新增 `HapticImpactPolicy`，把事件时长、攻击、保持、衰减、Q 因子和冷却统一到一层策略。
- 新增独立 `DeviceTuningRegistry`，5.0 时代先为 Xiaomi 10 `umi` 以及同族 `cmi/thyme` 提供专用触觉曲线；5.1 已扩展到全部命名 profile。
- Native 直驱节点增加 `strike-only` 模式。`activate`、AW8697、AW86224 等明显的一次性触觉入口不再被当作连续 200 Hz 幅值接口刷写。
- Native scheduler 与 Android Vibrator 回退分离，避免同一个事件同时走两条输出链。
- 事件输出使用短攻击、受控衰减和最小事件间隔，优先提高瞬态清晰度，而不是简单增加总振幅。
- 修复直驱节点 JNI 入口缺失问题，并把 direct-drive 能力判断扩展到 Root pipe / UDP / Java pipe 路径。

### 音频分析

- 新增固定容量 `AudioIngress`，Hook 线程只负责 PCM 归一化和入队。
- Native DSP 保留固定尺寸内存布局，降低音频写线程和 GC 对触觉时序的影响。
- 现有分析链扩展为五频段 + 512 点 FFT + 谱流量 + 频谱质心 + 自相关基频估计 + per-band onset。
- 事件检测进一步向“瞬态”倾斜，削弱持续音量对触觉输出的支配。
- PCM16、float PCM、ByteArray、ByteBuffer 路径统一进同一个 Native worker。
- 修正 ByteBuffer PCM16 读取为显式 little-endian 解析，避免复用默认 ByteOrder 造成音频解释错误。

### Hook

- `HookCoordinator` 统一管理 AudioTrack / MediaPlayer / SoundPool Hook。
- 采用 `Application.attach()` 获取目标进程 Context，避免依赖进程早期 `currentApplication()`。
- 增加 classloader 去重保护，避免同一进程重复安装 Hook。
- 只注册支持的 `AudioTrack.write(...)` 签名，减少不必要的 Hook 覆盖范围。
- 增加播放生命周期跟踪；多轨同时存在时不会因为某一路停止就立即误判整首音乐结束。
- Visualizer 回退增加延迟窗口，并通过实际 Hook 音频写入时间判断是否需要启用。

### 应用白名单与配置

- 新增 `/data/adb/musichaptics/whitelist.conf` 白名单配置。
- 白名单默认模式为 `whitelist`，配置缺失时不会退化成全局启用。
- Provider 改为只读，并增加调用方、目标包和白名单校验。
- 增加硬件 profile / direct-drive node 等配置的只读跨进程同步。
- Hook 进程不再主动执行完整 Root 硬件探测，避免每个目标应用启动时额外调用 `su` 和递归扫描 `/sys`。
- Root 探测保留在模块 UI / 显式硬件刷新路径。

### UI

- 将 AndroidLiquidGlass / Backdrop 源码整合为独立 `:liquidglass` 模块。
- 控制台、卡片、白名单、分段控制、底部导航统一使用 LiquidGlass。
- 添加 backdrop 不可用时的低成本降级绘制。
- 深色 / 浅色模式统一玻璃材质、阴影和边缘高光。
- 清理历史版本号式 UI 文案和无意义的迁移注释。

### 仓库

- 删除 PingFang 字体资源。
- 删除重复 C++ 源码、`cpp_disabled`、临时 patch、debug keystore、本地 Maven 缓存和其他构建垃圾。
- 删除未参与主链路的旧版音频 / 触觉缓存实现与无调用 RichTap 包装层。
- 补充 `LICENSE`、`CONTRIBUTING.md`、`SECURITY.md`、`docs/ARCHITECTURE.md` 和 `scripts/repo_check.py`。
- CI 增加仓库结构检查。

### 兼容性说明

5.0 的 Native 输出重点针对 arm64 Android。未知机型仍保留 Android Vibrator、Root pipe、UDP 和 Visualizer 兼容路径。设备级触觉节点不是 Android 标准接口，因此升级 ROM / Kernel 后建议重新执行硬件检测。

## 5.0 → 5.1

升级后最重要的变化是：**最终检测到的 DeviceProfile 现在会一路进入 Native DSP、事件策略和输出端。** 因此以前只在日志里出现的执行器参数，现在会实际改变 onset 门槛、事件时长与冷却。

## 4.x → 5.0

5.0 将音频输入、DSP、事件判定、触觉成形和硬件输出从历史代码路径中拆开。旧的“一个类里同时做 Hook、FFT、发振动、Root 探测”的方式不再继续扩展。

如果升级后出现参数变化，先检查：

1. LSPosed 作用域是否正确；
2. MusicHapticsX 白名单是否包含目标包；
3. 硬件检测结果是否仍然有效；
4. Xiaomi 10 是否正在走 `strike-only` 直驱；
5. 无直驱权限时是否正确回退到 Android Vibrator。
