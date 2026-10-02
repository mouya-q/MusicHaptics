# Changelog

## 5.2.1 — 安装后闪退与 16 KB 页对齐修复 (2026-10-03)

5.2.0 云编译全绿（Debug + Release 均 BUILD SUCCESSFUL）后，真机安装暴露两类问题。
两者都已定位到确切代码行并用设备上的官方工具取证，修复如下。

### 修复一：除引导页外全部界面一打开就闪退

**现象**：首次激活（`RootActivationActivity`）正常，进入 Dashboard 后立即闪退；
`am start` 返回 `Status: ok`，但进程在首帧合成阶段被 `RuntimeInit$KillApplicationHandler` 杀掉。

**崩溃栈（取自 `dumpsys dropbox`，PID 26288 / 28130，v50200）**：

```
java.lang.IllegalStateException: Size is unspecified
    at androidx.compose.ui.geometry.Size.getMinDimension-impl(Size.kt:166)
    at com.kyant.backdrop.effects.LensKt.getCornerRadii(Lens.kt:80)
    at com.kyant.backdrop.effects.LensKt.lens(Lens.kt:28)
    at com.mouya.musichaptics.HapticDashboardActivityKt.liquidGlass_...$lambda$4$lambda$3(HapticDashboardActivity.kt:156)
    at com.kyant.backdrop.BackdropEffectScopeImpl.apply(BackdropEffectScope.kt:62)
    at com.kyant.backdrop.DrawBackdropNode.updateEffects(DrawBackdropModifier.kt:369)
    at com.kyant.backdrop.DrawBackdropNode.observeEffects(DrawBackdropModifier.kt:363)
    at com.kyant.backdrop.DrawBackdropNode.onAttach(DrawBackdropModifier.kt:378)
```

**根因**：`DrawBackdropNode.onAttach()` 无条件调用 `observeEffects()` → `updateEffects()`，
而 `onAttach()` 发生在**首次绘制之前**，此时 `BackdropEffectScopeImpl.size` 仍是初始值
`Size.Unspecified`。`lens()` 的 `cornerRadii` 直接读取 `size.minDimension`，
`Size.minDimension` 对未指定尺寸会主动抛 `IllegalStateException`，异常从
`observeReads` 里逃出，直接打断首次组合（`Recomposer.composeInitial`），进程被杀。

**修复（三层防御，任一层单独都能挡住这类崩溃）**：

- `DrawBackdropModifier.updateEffects()`：进入时先判 `effectScope.size.isSpecified`，
  尺寸未就绪直接返回。`ContentDrawScope.draw()` 里的 `effectScope.update(this)`
  会在真实尺寸可用后重新触发 `updateEffects()`，因此不会丢失效果。
- `effects/Lens.kt` 的 `lens()`：在读取 `cornerRadii` 之前加同一守卫，
  使该扩展函数自身对调用时机免疫。
- `highlight/HighlightStyle.kt` 的 `DrawScope.getCornerRadii()`：尺寸未指定时
  返回全零圆角数组而不是抛异常（该方法还会被高光 shader 在组合早期调用）。

这三处都用 `androidx.compose.ui.geometry.isSpecified`，不引入新依赖。

### 修复二：16 KB 页对齐检查失败

**现象**：系统安装/运行弹窗提示
「此应用不符合 16 KB 对齐要求。ELF 文件对齐检查失败」，
并列出 `lib/arm64-v8a/libandroidx.graphics.path.so: 未知错误` 与
`lib/arm64-v8a/libnative-bridge.so: LOAD 区段未对齐`。

**取证方法与结论**：用设备自带的官方 `readelf -lW`（`/system/bin/readelf`）逐个核对
APK 内两个 `.so` 的 `PT_LOAD` 段：

| 库 | `p_align` | 判定 |
|---|---|---|
| `lib/arm64-v8a/libnative-bridge.so` | **0x1000 (4 KB)** | **真违规** |
| `lib/arm64-v8a/libandroidx.graphics.path.so` | 0x4000 (16 KB) | 合规 |

`libnative-bridge.so` 是本项目自己的 C++ DSP 引擎（`app/src/main/cpp/CMakeLists.txt`），
由 NDK r27 默认参数产出，仍是 4 KB 对齐——这才是真正的违规项。
`libandroidx.graphics.path.so` 是 `androidx.compose.ui:ui-graphics` 传递依赖进来的
`androidx.graphics:graphics-path:1.0.1`，其 ELF 与 APK 内 zip 条目偏移都已 16 KB 对齐；
系统给出的「未知错误」是该条目在页大小检查器里无法归类时的兜底文案，
并非真实的对齐缺陷。这一点有旁证：设备上多个第三方应用（如 `com.kiminonawa.HyperLight`）
内置的 `libandroidx.graphics.path.so` 与本项目**字节完全一致（SHA-256 相同）**，
且不触发该提示。

**修复**：`app/src/main/cpp/CMakeLists.txt` 增加

```cmake
add_link_options(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
```

强制链接器按 16 KB 页输出 `PT_LOAD`（`p_align = 0x4000`）。
NDK r28+ 已默认如此，此设置在旧 NDK 上生效、在新 NDK 上无害。
`graphics-path` 未做排除：它本身合规，排除它反而会破坏
`AndroidPathIterator` 的可用性。

### 修复三：去掉「正在测试可调试应用」警告

**现象**：安装后系统提示「由于当前正在测试的是可调试应用，因此系统会显示此警告」。

**根因**：`dumpsys package com.mouya.musichaptics` 显示 `flags=[ DEBUGGABLE ... ]`——
之前安装的是 **debug 变体**（`versionName=5.2.0`，`flags=0x20e83e46` 含 DEBUGGABLE）。
可调试应用必然触发该提示，这是系统设计行为，不是缺陷。

**修复**：`app/build.gradle.kts` 的 `release` 构建类型显式使用调试签名配置
（`signingConfig = signingConfigs.getByName("debug")`），
使 CI 无需注入密钥即可产出**已签名且不可调试**的 Release APK。
安装 `app-release-unsigned.apk`（CI 产物，实际已签名）即不再出现该警告。

### 排查工具（留在 /storage/emulated/0/push/）

- `elfcheck.py` — 按 `(p_vaddr - p_offset) % p_align == 0` 判定 ELF 16 KB 对齐
- `apkalign.py` — 同时检查 ELF `PT_LOAD` 与 APK 内未压缩条目的 16 KB 偏移
- `apkcmp.py` — 对比多个 APK 中 `.so` 的对齐写法与 zip extra field
- `elfdeep.py` — 转储 ELF 头、程序头、节区名与 GNU 属性
- `classinfo.py` — 极简 Java class 常量池解析（定位 `loadLibrary` 等引用）
- `getlog.py` / `runinfo.py` — GitHub Actions 完整日志与提交核验


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
    仍然存在、禁止同一文件同时导入两个 `CompositingStrategy`、阻止把
    `graphics.layer.CompositingStrategy` 赋给 `GraphicsLayerScope`、确认 `HapticComposer.kt`
    及其三个类型声明仍在、阻止对 `ByteArray?` 调用 `isNullOrEmpty()`、阻止
    `SharedPreferences.all` 被声明成 `MutableMap`。这些检查只扫真实代码行
    （跳过注释），且已通过双向自检：正常仓库 exit=0，注入错误写法后 exit=1 并给出精确文件行号。
    这几类问题以后在 CI 第一步就会被拦下。

### 构建链修复（第二轮：`app` 模块）
第一轮把 `:liquidglass` 修通后（72 条错误 → 0），CI 随即暴露出 `:app` 模块自身的
78 条错误。根因与第一轮性质相同——都是重建 5.x 目录树时的遗漏：

- **`HapticComposer.kt` 整个文件丢失（本版最大问题）。** `HapticEventGenerator.kt` 与
  `HapticSynthesizer.kt` 大量引用 `HapticCommand`、`KeyStrikeSemantic`、`SemanticType`，
  但这三个类型在整个 5.1.0 / 5.2.0 仓库中都不存在——它们只存在于 4.23 的
  `HapticComposer.kt`，迁移时该文件被整份漏掉。78 条错误里有 70 条（89%）是它的级联：
  `Unresolved reference 'KeyStrikeSemantic'` ×16、`'semanticType'` ×7、`'SemanticType'` ×6、
  `'isBeat'` ×6、`'isKeyStrike'` ×6、`'keyStrikeSemantic'` ×5、`'bassComponent'` ×5、
  `'pitch'` ×4、`'isTransient'` ×4、`'textureComponent'` ×3、`'adsrEnvelope'` ×2、
  `'thermalGain'` ×2、`'HapticCommand'` ×1，以及由它们派生的
  `None of the following candidates is applicable` ×1。已按 4.23 原文补回该文件
  （7 个 `KeyStrikeSemantic` 值、7 个 `SemanticType` 值、12 个 `HapticCommand` 字段），
  并加 KDoc 说明它是命令词表的唯一来源。
- **`@Composable` 调用出现在非 composable 的 `onDrawSurface` 里（3 处）。**
  `HapticDashboardActivity.kt` 的 `Modifier.liquidGlass()` 与 `LiquidGlassTabBar` 中，
  `onDrawSurface = { drawRoundRect(glassColor(), ...) }` 的 lambda 是 `DrawScope` 接收者，
  不是 composable 作用域，而 `glassColor()` / `isDark()` 都标了 `@Composable`。
  改为在 composable 作用域先求值到局部变量（`glass`、`barGlass`、`lensGlass`）再在
  lambda 内使用，顺带避免了每帧重算颜色。
- **对 `ByteArray?` 调用 `isNullOrEmpty()`。** `kotlin.text.isNullOrEmpty` 只有
  `CharSequence?` / `Array<out T>?` / `Collection?` / `Map?` 四个重载，**没有 `ByteArray?`**，
  因此 `HookCoordinator.kt:296` 报 Unresolved，并连带 302 行的
  `Argument type mismatch: 'ByteArray?' but 'ByteArray' was expected` 与
  `Only safe (?.) ... on a nullable receiver`。改为显式
  `if (waveform == null || waveform.isEmpty()) return`，一次消掉 3 条错误。
- **`SharedPreferences.all` 不能作为属性覆盖。** Java 的 `getAll()` 返回 `Map<String, ?>`，
  但 Kotlin **不会**为 Kotlin 类实现的 Java 接口生成可覆盖的 `all` 合成属性。
  原写法 `override val all: MutableMap<String, *>` 与中间试过的
  `override val all: Map<String, *>` 都同时报出
  `'all' overrides nothing` + `does not implement abstract member 'getAll'`。
  正解是**按函数覆盖**：`override fun getAll(): Map<String, *> = values.toMutableMap()`。
  这一点由多个成熟项目交叉验证（muzei/muzei、bitwarden/android、duckduckgo/Android、
  LawnchairLauncher/lawnchair、Dev4Mod/WaEnhancer 均为 `override fun getAll(): Map<String, *>`）。
  调用方沿用 `prefs.all` 属性语法不受影响，无需改动。
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
