# Changelog

## 5.2.0 — 全链路性能打磨 (2026-10-02)

### Native DSP（最大收益）
- **音高估计 16× 提速**：`estimatePitch` 自相关先做 4× 盒式抽取（12kHz），滞后扫描 1212×256 ≈ 31 万次乘加 → 300×64 ≈ 1.9 万次；附带静音门控，安静段落直接跳过扫描。
- **Hann 窗预计算**：`updateSpectrum` 每个音频块不再执行 512 次 `cosf`，窗函数只算一次。
- **JNI 遥测零拷贝**：`nativeProcessAudioDirect` 改用 `GetPrimitiveArrayCritical`，消除每个音频块两次 32-float 数组拷贝。
- **调度线程 JNI 缓存**：四个事件名字符串改为全局引用缓存，每次节拍不再 `NewStringUTF`/`DeleteLocalRef`；退出时先删引用再 detach，去掉多余的二次 attach。
- **修复 JNI 弱引用误用**：Java Pipe 回調路径对 weak-global-ref 直接 `GetObjectClass`，GC 后可能悬空——改为 `NewLocalRef` 提升后再调用。
- **直驱日志降频**：200Hz 触发路径上的周期日志从每 100ms 降为每 10s，空闲日志 200ms → 12s。

### Kotlin 热路径
- **AudioIngress 批量写**：四路 PCM 入口（short[]/float[]/byte[]/ByteBuffer）由逐样本 `FloatBuffer.put`（直接缓冲区上的虚调用）改为堆数组暂存 + 单次批量 `put`（内联 intrinsic 拷贝）。
- **LinkHealthMonitor.setPlayingState 去抖**：该函数在每个 `AudioTrack.write` 上被调用，原实现每次都写 volatile + 打日志，现仅在状态真正翻转时生效。
- **Root pipe 零分配**：`triggerRootPipeVibration`（200Hz）的 `"G0x%02x\n".format()` 字符串格式化替换为 201/256 项预编码字节表。
- **禁用期不再每帧 cancel**：`processAudioFrame` 在总开关关闭时只发送一次 `Vibrator.cancel()`。
- **日志门控**：节拍事件、`[C++-ONSET]`、VibrateProxy 每次振动等热路径日志统一收进 `verboseLogging`（默认 = BuildConfig.DEBUG）。

### 正确性修复
- **能量浪涌检测复活**：`generateAndPlay` 中 `accumulatedEnergy` 先清零再判断 `isEnergySurge(...)`，导致该分支永假；mood 包络也同时被拍平。现调整求值顺序，Drop 波形与情绪包络恢复生效。
- **HapticEventGenerator 去重**：四份复制的 vibrator 回退解析合并为 `resolveVibrator()`。

### 日志/广播链路
- **LogBroadcaster 限流**：跨进程 `sendBroadcast` 增加 16 条/秒滑动窗口限流，防止异常状态下刷爆 system_server。
- **ConsoleLogArchive 批量落盘**：每条日志一次 open/write/close → 内存缓冲 750ms/4KB 批量 flush；文件长度检查摊销到每 16 次 flush；UI 退到后台时兜底 flush。
- **ConsoleLogState 批量裁剪**：300 条上限处由逐条 `removeAt(0)`（O(n) 搬移）改为每次裁 32 条。
### 构建
- **Release 开启 R8 + 资源压缩**：配套完整 keep 规则（Xposed 入口、JNI 方法与回调、Manifest 组件、LSPosed 服务回调）。
- **CMake**：去掉 `-fno-omit-frame-pointer`，新增 `-ffunction-sections -fdata-sections -fvisibility-inlines-hidden`，链接 `-Wl,--gc-sections --icf=all`，缩小注入到每个宿主进程的 .so。
- **Gradle**：开启并行构建、构建缓存、Kotlin 增量编译。

### 构建链修复（本版性能改动本身不动，只修编译）
5.2.0 的性能代码方向正确，但 `:liquidglass` 模块是从 Compose Multiplatform 库整体搬来的，
在 AndroidX Compose 上有三处硬编译错误，共 72 条。逐条定位如下：

- **`RuntimeShader` 不是 AndroidX 的类型。** 上游把 `interface RuntimeShader` 放在 `commonMain`，
  只有工厂函数是 `expect/actual`；搬运时只带了 `androidMain` 实现，接口声明丢失，
  而 `import androidx.compose.ui.graphics.RuntimeShader` 在 AndroidX Compose 里根本不存在
  （已用 ui-graphics 1.7.8 / 1.8.2 的 classes.jar 逐类核对，两版都没有该类型）。
  现在接口在 `com.kyant.backdrop` 内本地声明，不再依赖任何 AndroidX 同名类型。
  这一条同时消掉 `setFloatUniform/setIntUniform/setColorUniform overrides nothing`、
  `Cannot infer type`、`Argument type mismatch` 等连锁报错。
- **Kotlin context parameters 需要 Kotlin 2.2+。** `LayerRecorder.kt` 用了
  `context(node: DelegatableNode)`，在本项目锁定的 Kotlin 2.0.21 上是解析错误
   （`Syntax error: Expecting comma or ')'`）。改为把 `node` 作为显式首参传递，
   两个调用点（`DrawBackdropModifier`、`LayerBackdropModifier`）同步更新。
- **`CompositingStrategy` 有两个同名类，必须按宿主类型选用。** AndroidX 同时提供
   `androidx.compose.ui.graphics.CompositingStrategy`（在 `ui-android` 模块）与
   `androidx.compose.ui.graphics.layer.CompositingStrategy`（在 `ui-graphics` 模块），
   两者成员相同（`Auto` / `Offscreen` / `ModulateAlpha`）但类型不兼容：
   `GraphicsLayerScope.compositingStrategy` 声明的是前者，
   `layer.GraphicsLayer.compositingStrategy` 声明的是后者。
   结论由 `ui-android-1.7.8.aar` 与 `ui-graphics-android-1.7.8.aar` 的字节码直接核验：
   `ui-android` 里 `GraphicsLayerScope` 的 `getCompositingStrategy--NrFUSI` 返回
   `()I` 且常量池引用 `Landroidx/compose/ui/graphics/CompositingStrategy;`；
   `ui-android` 中根本不存在 `layer/CompositingStrategy.class`。
   因此 `InverseLayerScope`（实现 `GraphicsLayerScope`）与 `DrawBackdropModifier.layoutLayerBlock`
   改用 `graphics.CompositingStrategy`；而 `ShadowModifier` / `InnerShadowModifier`
   赋值给 `createGraphicsLayer()` 返回的 `layer.GraphicsLayer`，继续使用
   `graphics.layer.CompositingStrategy`。
- **`GraphicsLayerScope` 成员差异。** `blendMode` / `colorFilter` 在 AndroidX Compose 1.7.x
   属于 `GraphicsLayer` 而非 `GraphicsLayerScope`，`InverseLayerScope` 不再 override 这两个成员。
- **仓库自检加护栏**：`scripts/repo_check.py` 新增检查——禁止导入 AndroidX 的
   `graphics.RuntimeShader`、禁止 Kotlin context parameters、确认本地 `RuntimeShader` 接口
   仍然存在、禁止同一文件同时导入两个 `CompositingStrategy`、并阻止把
   `graphics.layer.CompositingStrategy` 赋给 `GraphicsLayerScope`。这些检查只扫真实代码行
   （跳过注释），且已通过双向自检：正常仓库 exit=0，注入错误写法后 exit=1 并给出精确文件行号。
   这几类问题以后在 CI 第一步就会被拦下。
- **Compose BOM 统一到 2025.03.00**（Compose UI 1.7.8），`compileSdk` / `targetSdk` 升到 35，
  CI 同步安装 `platforms;android-35`。
- **`gradle.properties` 关闭 configuration cache**：本项目同时驱动 externalNativeBuild(CMake)
  与 R8 release 变体，AGP 8.7 的配置缓存在这两条路径上仍有边界问题，缓存未命中会直接变成构建失败，
  不值得为此冒险。并行构建、构建缓存、Kotlin 增量编译保留。
- **恢复 `local-maven` 本地仓库**：`de.robv.android.xposed:api:82` 以 vendored 形式入库，
  并在 `settings.gradle.kts` 重新声明，避免 CI 去访问常年不可达的 `api.xposed.info`。
- **CI 不再使用 `android-actions/setup-android@v3`**：该 action 会执行 `sdkmanager tools`，
  而 legacy `tools` 包已从 Google SDK 仓库下架，必然以 exit 1 中断构建；改为直接定位
  runner 预装的 SDK 并用 `sdkmanager` 安装缺失组件。
- **R8 规则补充**：`-dontwarn` 覆盖 `org.jetbrains.annotations`、`org.intellij.lang.annotations`
  与 `androidx.compose.**`，避免 shrink 阶段因缺失注解类报错。



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
