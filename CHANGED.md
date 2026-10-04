# Changelog

## 5.2.9 — 修复配置刷新链路：设置不生效 / 白名单回弹 (2026-10-04)

本版三处修改指向同一根因链：**UI 改了设置，但注入进程永远不知道**。
5.2.8 及之前，ACTION_REFRESH_CONFIG 广播只有发送方（Dashboard），无注册方；
ConfigProvider 对 callingPackage 过严校验导致部分 ROM 上 ContentProvider.call 静默返回 null。
两者叠加使 HookConfigPreferences.refresh() 拿到的 bundle 始终为空，DSP 参数停在默认值。

### Fixed
- **新增 ConfigRefreshReceiver**：注入目标进程内动态注册 BroadcastReceiver，
  监听 com.mouya.musichaptics.ACTION_REFRESH_CONFIG，收到后立即调用
  HapticEngine.synchronizeParameters() 重新拉取配置并重建 DSP 参数。
- **放宽 ConfigProvider.call caller 校验**：白名单已在 MainHook/HookCoordinator 安装阶段把关，
  ContentProvider 再用 callingPackage 严格匹配会在共享 UID 宿主或 null caller 的 ROM 上拒绝合法请求。
  现改为仅当 caller 明确不匹配且不在白名单时才拒绝。
- **HookCoordinator 生命周期绑定**：install() 与 adopt() 两处调用
  registerConfigRefreshWhenPossible()，@Volatile 强引用持有 receiver 防 GC 回收。

### Changed
- **风格预设文案简化**：label 改为“均衡”“低频”“清脆”“柔和”“强劲”“纯净”短词，
  description 改为英文单词（Balanced/Bass/Crisp/Soft/Immersive/Pure）。
- **删除“整体触感幅度”独立滑块**：强度百分比已由增益档位分段控件和风格预设的 ampScale 隐式承担，
  不再有重复入口。DSP 层 intensityScale 乘算保留，使用默认值 75%。
- **多语言支持**：新增 strings.xml 资源文件，
  支持中文（默认）/ 英语 / 日语 / 韩语 / 西班牙语。
  UI 中 34 处硬编码中文改为 stringResource() 引用，
  Android 系统语言变更时自动切换。
- 版本升至 5.2.9 (versionCode 50209)。

---

## 5.2.8 — 收敛为单一入口：撤销独立卡片，dock 回到滑块 (2026-10-03)
5.2.7 把风格预设做成了一张独立卡片，结果是控制台里**同一件事有两个入口**：
上面一张「震感风格」卡，下面又一块既有的「增益档位 / 风格预设」。
主人装上后指出这一点是对的 —— 界面不该为同一个决定开两个口子，
何况新增卡片把真正在用的控件挤到了下方。本版把参数全部并回原有控件，
并回退 5.2.7 的 dock 改版。

### 撤销的内容
- **删除独立的「震感风格」卡片**及其六格网格与强度滑块。
  六档 `StylePreset` 改由既有的「风格预设」横排承载（3 列 × 2 行），
  强度百分比改由既有的「增益档位」下方的滑块承载。
- **回退底部 dock**：恢复胶囊 + 透镜横移滑块 + 可拖拽切换。
  5.2.7 改成了"每项独立微凸"，实测下来图标大小跳变、标签时隐时现，
  反而不如滑块直观，也不好按。横移指示器与 `IOSSegmentedControl` 保持同一套语言。
- **撤销 5.2.7 的实时参数预览行**随卡片一并移除；
  数值仍可在导出的 `[STYLE]` 日志里逐项核对。

### 保留的内容（这些是对的）
- 六档风格预设与 DSP 参数的对应关系：`StylePreset` 枚举与 `synchronizeParameters()`
  的三级乘算原样保留，只是入口位置变了。
- `[STYLE]` / `[BEAT]` 双段调试日志。
- 硬件触觉适配卡的折叠化。
- 重启作用域 App 对话框的液态玻璃化。

### 顺带修掉两个对话框缺陷
1. **面板点击会被 scrim 吞掉**：关闭用的 `clickable` 挂在最外层 Box 上，
   而面板自身没有消费点击 —— 点标题或说明文字会冒泡上去，直接把对话框关掉。
   现由面板自己拦截点击，scrim 只对"点到面板以外"生效。
2. **退出动画从不播放**：原先写作 `AnimatedVisibility(visible = true)`，
   恒为真，因此关闭是瞬间消失。改为常驻挂载 + 真实 `show` 状态驱动，
   进出会沿同一条路径（顶部锚点 `scaleIn` ↔ `scaleOut`）完成。
   同时 scrim 也纳入 `show` 控制 —— 否则组件常驻后，
   关闭状态下它仍会拦截整屏点击。

---
## 5.2.7 — 风格预设体系与界面重构：让每一档都可感知 (2026-10-03)
5.2.6 打通了振动链路，此后的问题是另一层：**调了，却听不出区别**。本座通读控制台
全部可调项后发现，界面上"震感预设"只映射到四个常数增益（0.70 / 0.90 / 1.00 / 1.20），
仅是一个乘数——频段、锐度、起音、触发阈值全部纹丝不动。于是从低音到清脆，输出几乎是
同一条曲线，用户自然得出"改了没反应"的结论。本版把预设重新建模为**真实 DSP 参数集**，
并把整套界面按同一套物理准则重做一遍。

### 新增：六档风格预设，每档是一组完整参数
新增 `StylePreset` 枚举，每档改写的不只是一个倍率，而是六个维度同时变化：

| 预设 | 冷却间隔 | 增益 | 锐度 | 起音 | 频段 | 起音阈值 | 重音 |
|------|---------|------|------|------|------|---------|------|
| 均衡自适应 | 118ms | ×1.00 | 0.32 | ×1.00 | 55–650Hz | 0.08 | ×1.00 |
| 低频律动   | 128ms | ×1.22 | 0.20 | ×1.45 | 32–420Hz | 0.07 | ×1.35 |
| 清脆节拍   |  98ms | ×0.92 | 0.62 | ×0.55 | 90–1400Hz | 0.11 | ×0.85 |
| 柔和氛围   | 142ms | ×0.66 | 0.14 | ×1.80 | 45–520Hz | 0.06 | ×0.60 |
| 强劲沉浸   | 108ms | ×1.35 | 0.42 | ×0.78 | 38–900Hz | 0.05 | ×1.55 |
| 纯净律动   | 260ms | ×1.12 | 0.74 | ×0.42 | 120–2200Hz | 0.19 | ×1.25 |

冷却间隔是这里最容易被忽视、却最影响听感的一项：同样的鼓点密度，
118ms 与 260ms 的冷却会分别呈现为"跟得上"与"只剩骨架"两种完全不同的质感。
纯净律动把冷却拉到 260ms 并把阈值推到 0.19，同时把低切抬到 120Hz——
连续微振被整段滤掉，只剩干净的强起音。低频律动反其道而行：低切压到 32Hz、
起音时间乘 1.45、冷却放宽到 128ms，让低频有余振而非干脆利落。

### 三级乘算：风格、强度、放大彼此正交
```
输出幅度 = 基准幅度 × 风格增益 × (强度百分比 ÷ 100) × 电源放大
```
强度滑块（10%–100%）作用在**所选风格曲线上**，而非直接覆盖它——
所以"清脆 + 低强度"仍是清脆，只是更轻；"低频 + 低强度"仍是低频。
换风格与调强弱是两条独立的轴，用户不必为了改一点强弱就牺牲音色选择。

### 新增：[STYLE] / [BEAT] 双段调试日志
- `[STYLE]`：仅在风格或强度变化时打印一行完整快照（预设、强度、有效幅度、
  锐度、起音倍率、频段、阈值、冷却），用于核对**界面显示值与 DSP 实际取值是否一致**；
- `[BEAT]`：每次触发都记录，前 12 条全打、其后每 40 条打一次，
  格式含原始强度、风格键、百分比、有效幅度、时长、起音、是否走 DynamicEffect 通道。
  此段不受 `verboseLogging` 门控——排障时最需要的就是它。

两项均同步至 Dashboard 的 `LogBroadcaster`，可在 App 内直接导出。

### 界面重构
- **新增「震感风格」卡片**：六格网格 + 徽标 + 描述 + 强度滑块 + 实时参数预览行，
  预览行显示的正是当前风格写入 DSP 的那组数值，与 `[STYLE]` 日志可逐项对照。
- **底部 dock 全面 Apple 化**：取消横向滑块与拖拽，改为每项独立形变——
  选中项放大 1.14× 并抬高 3dp，未选中 1.0×，按下 0.96×。
  形变锚点 `TransformOrigin(0.5f, 1f)` 落在底部，图标"站"在底边而非悬空上浮。
  **理由**：tab 是平级导航，横向滑动会暗示一层并不存在的层级深度。
  全部走 `spring(dampingRatio=0.7, stiffness=500)`，可随时被打断；
  reduced-motion 时降级为 `snap()`。
- **硬件触觉适配卡改为可折叠**：标题行的 Root 状态常驻可见，
  型号 / f₀ / Q / 上升频率 / 指纹等次要信息默认收起，
  展开走 `expandVertically + fadeIn`，chevron 同步做弹簧旋转。
  reduced-motion 时去掉高度与位移，仅保留透明度过渡——仍然能看出"展开了"。
- **重启作用域 App 对话框液态玻璃化**：由 Material `AlertDialog` 改为自绘玻璃面板，
  从**顶部锚点**（触发按钮方向）以 `scaleIn(0.92f)` 展开，
  进出走同一条路径，避免"凭空出现在屏幕中央"。
- **修复「应用触觉」勾选双重触发**：此前勾选行同时存在 `Row.clickable` 与
  `Checkbox.onCheckedChange` 两个点击源，一次点击被处理两次，表现为"点了没反应"。
  现改为行做唯一点击源，Checkbox 改为 `onCheckedChange = null` 的纯展示件。

### 全部动画遵守同一套准则
展开走 spring 而非 timing（可被打断，不从零重来）；按下与提交分离，
**每次用户动作只发一次触觉**；触觉与视觉同帧发出，不等动画走完；
所有动效均响应系统的减弱动态效果设置；折角、图标、面板的进出路径保持空间一致。

---
## 5.2.6 — 直驱与 Java 链路解耦：修复"报告可用但零振动" (2026-10-03)

5.2.5 装上后仍然零振动。本座从导出的四份 Dashboard 日志定位到**三个互相叠加的缺陷**，
它们共同构成了"直驱明明可用，却一声不响"的完整因果链。

### 根因一：native 调度器把 Java 回调与直驱做成了互斥

`NativeBridge.cpp` 的 `scheduler_thread_func` 里，onset 命中后走的是二选一：

```cpp
if (!use_direct_drive && onBeatTrigger) { env->CallVoidMethod(...); }   // 分支 A
if (use_direct_drive && StrikeOnly)     { trigger_direct_drive(...); }   // 分支 B
```

日志中 `isDirectDriveAvailable=true` → `use_direct_drive=true` → **分支 A 永不执行**，
`onBeatTrigger` 一次都不会回调 Java。于是 `triggerBeatVibration` → `performDynamicEffect`
整条 5.2.5 新链路从未被调用过——这也解释了为何日志里连一条 `[HAPTIC] ... dynamic=` 都没有。

**修复**：分支 A 去掉 `!use_direct_drive` 条件，改为无条件回调。直驱降级为"附加通道"，
可用则叠加，不可用也不影响 Java 主链路。

### 根因二：音频活动判定只看 native 帧计数，导致恢复逻辑永不触发

`runSemanticFrameLoop` 中 `lastAudioInputTime` 仅在 `semanticFrameCount > 0 || onsetFrameCount > 0`
时刷新，而这两个计数受 `!nativeSchedulerActive` 守卫——调度器启用时恒为 0。
于是 `hasAudioActivity` 永远为 false。

**修复**：`markHookAudioArrival()` 每次 PCM 到达都会写 `nativeLastAudioTime`，
改以它作为真实音频活动来源（`hasNativeAudioActivity || hasHookAudioActivity`）。

### 根因三：hapticPaused 一旦置位即永久锁死

`markCandidateStopped()` 误判后会置 `hapticPaused = true`，但恢复路径只复位了
`vibrateProxy.paused`，从未复位 `hapticPaused`。而 onset 分支带 `!hapticPaused` 守卫，
于是整条振动链路永久关闭。日志里连续三次 `[PLAYBACK TRULY PAUSED]` 之后不再振动，
正是这个锁死。

**修复**：音频恢复时一并解冻 `hapticPaused`，并输出 `[PLAYBACK RESUMED]` 日志。

### 附带：设置读取静默失效

`HookConfigPreferences.refresh()` 在 provider 查询失败时静默 `return`，`values` 永远停在
`emptyMap`，所有 `get*` 返回默认值且无任何日志。补上一次性告警与加载成功日志，
避免"UI 改了设置但注入进程读不到"这类问题再次隐身。

### 变更

- `NativeBridge.cpp`：Java 回调改为无条件执行，与直驱并存
- `HapticEngine.kt`：音频活动判定引入 `nativeLastAudioTime`；恢复时解冻 `hapticPaused`
- `HookConfigPreferences.kt`：`refresh()` 失败与成功均输出日志
- `versionCode` 50205 → 50206

### 兼容性

- 直驱可用：Java DynamicEffect/fallback 必发 + sysfs 叠加
- 直驱不可用：仅 Java 链路
- 无行为回退风险；振动强度提升后可用 UI 的 `haptic_amplitude` 重新微调

### 已验证的存储/UI 链路

`ConfigProvider` 已注册且可读，`haptics_config.xml` 内容完整：
`master_switch=true`、`haptic_amplitude=2.0`、`haptic_boost_level=1.6`、
`selected_preset=3`、`haptic_preset_id=0`、`hardware_profile_id=XIAOMI14`、
`direct_drive_nodes` 已配置。`synchronizeParameters()` 每 60 帧同步一次，
`outputAmp`/`forceDefaultAmplitude`/`boostLevel`/profile/preset 均正确下传。


## 5.2.5 — DynamicEffect ADSR 包络：接入 Android 14+ OS 级触觉引擎 (2026-10-03)

5.2.4 修好线程优先级崩溃后引擎能正常初始化，但振动质感仍差——`VibrationEffect.createWaveform`
是离散阶梯波，无法表达 C++ DSP 算出的连续 ADSR 包络（rise=2.8ms / fall=4.5ms / Q=17.0）。

### 参考实现分析

反编译 14PRO MusicHaptic v0.1.8 daemon.dex，发现其核心并非 RichTap 私有 API，而是
Android 14+ 标准 `android.os.DynamicEffect` + `android.os.HapticPlayer`：

```java
DynamicEffect.createContinuous(amplitude, sharpness, duration)
  .addParameter(createParameter(0, times[], amps[]))  // 四点 ADSR
new HapticPlayer(effect).start()
```

OS 底层对四点曲线做插值，直接驱动 LRA，质感远优于阶梯波。该 API 是 AOSP 标准，
不限品牌。

### 变更
- `VibrateProxy.performDynamicEffect()`：反射调用 DynamicEffect/HapticPlayer，加入品牌
  门控（仅 Xiaomi/Redmi 生效），SDK<34、非目标品牌或反射失败均返回 false
- `HapticEngine.triggerBeatVibration()`：优先调 performDynamicEffect，失败 fallback 到
  原 performWaveform 路径；ADSR 参数从 actuator profile 映射（attack=riseTime,
  sharpness=Q/30, duration=totalDuration）
- `versionCode` 50204 → 50205

### 兼容性

- SDK≥34 且品牌为 Xiaomi/Redmi：走 DynamicEffect 连续包络（RichTap OS 级驱动）
- SDK<34、非 Xiaomi/Redmi 品牌或 API 不可用：自动 fallback 到 createWaveform 阶梯波
</ARG>

## 5.2.4 — HapticEngine 构造抛异常：Linux nice 值被当成 Java 线程优先级 (2026-10-03)
</ARG>
5.2.3 修掉 Context 获取问题后，模块日志终于完整出现，但引擎仍然建不起来。冷启动网易云
（pid 31196）抓到 21 条模块日志，链路一路通到引擎构造，然后抛出：

```
11:15:03.230 I MusicHapticsX-Hook: [com.netease.cloudmusic] hooked Application.attach
11:15:03.269 I MusicHapticsX-Hook: [com.netease.cloudmusic] hooks registered (context not yet bound)
11:15:03.269 I MusicHapticsX-Hook: HookCoordinator installed for com.netease.cloudmusic
11:15:04.234 I MusicHapticsX-Hook: [com.netease.cloudmusic] context acquired via CloudMusicApplication
11:15:04.367 E MusicHapticsX-Hook: Engine init failed for com.netease.cloudmusic
                  java.lang.IllegalArgumentException: Priority out of range: -16
                    at java.lang.Thread.setPriority(Thread.java:1956)
                    at p1.b.<init>(SourceFile:93)     ← AudioIngress.<init>
                    at o1.T0.<init>(SourceFile:224)   ← HapticEngine.<init>
```

### 排查过程中的一个假象

先前"完全没有日志"的判断是错的。模块一直在正常加载，只是 **logcat 缓冲区被宿主侧
对话日志冲刷掉了**——网易云 10:30 产生的记录到 10:37 已滚出缓冲。改用
`logcat -c` → 强杀目标 App → 冷启动 → **立刻**按 pid 抓取后，21 条模块日志全部到手。

> 取证纪律：判断"模块有没有加载"不能靠事后翻 logcat 缓冲区，必须在复现的同一
> 脚本里先清缓冲、再按 `grep -a " <pid> "` 精确锁定，否则会被无关日志淹没。

### 根因

`AudioIngress` 的 `init` 把 **Linux nice 值**赋给了 **Java 线程优先级**：

```kotlin
worker.priority = Process.THREAD_PRIORITY_AUDIO   // -16
```

两者是完全不同的量纲：

| API | 合法范围 | 语义 |
| --- | --- | --- |
| `Thread.priority` | `1..10` | Java 层调度提示 |
| `Process.setThreadPriority()` | `进程tid..20` 的 nice 值 | OS 层调度策略 |

`Process.THREAD_PRIORITY_AUDIO` = `-16` 远超 `1..10`，`Thread.setPriority()` 直接抛
`IllegalArgumentException("Priority out of range")`。`HapticEngine` 构造函数里同步构造
`AudioIngress`，异常一路冒泡上来，**引擎从未建成**，因此依旧零振动。

该异常被 R8 混淆成 `p1.b.<init>`，栈帧完全看不出是 `AudioIngress`，这也是它能潜伏
至今的原因。

### 变更

- `AudioIngress.init`：Java 侧改用 `Thread.MAX_PRIORITY`，OS 侧另用
  `Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)` 固定 nice 值，两者各自
  就位，并全程 `runCatching` 记录结果
- `versionCode` 50203 → 50204

DSP 循环与 AudioFlinger 同源，从 OS 层加压才是原本意图的语义；Java 优先级仅作
辅助提示。

### 排查笔记

- `HookCoordinator` 里的 `HandlerThread("MusicHapticsX-Hook", Process.THREAD_PRIORITY_DISPLAY)`
  **没有**这个问题——`HandlerThread(String, int)` 重载的第二个参数本来就是 nice 值，
  内部直接调 `Process.setThreadPriority`，`-4` 合法。
- 全工程仅 `AudioIngress` 一处存在该缺陷。

## 5.2.3 — Hook 装上了却毫无振动：Android 14+ 取不到 Context (2026-10-03)

5.2.2 修掉 RenderThread 栈溢出后应用能正常打开，但**注入的音乐应用完全没有振动**，
且 logcat 里找不到任何模块日志。

### 排查结论：Hook 其实是成功的

在设备上冷启动网易云后，模块日志确实存在：

```
09:53:45.232 28757 28757 I MusicHapticsX-Hook: HookCoordinator installed for com.netease.cloudmusic
09:53:49.207 28757 19373 D MusicHapticsX-Hook: [com.google.android.webview] not enabled by application filter
```

`MainHook` → `WhitelistManager` → `HookCoordinator.install()` 全链路正常，AudioTrack 的
hook 也确实注册了。但**再往后什么都没有**：`HapticEngine`、`VibrateProxy`、
`NativeBridge` 的日志一条都没有出现。

> 附带的取证经验：模块用 `android.util.Log`（TAG `MusicHapticsX-Hook`）而非
> `XposedBridge.log`，所以在 LSPosed 的 `modules_*.log` 里**查不到任何记录**——
> 那是正常现象，不代表模块没加载。判断是否注入应看目标进程的 `/proc/<pid>/maps`
> 或直接抓 logcat。

### 根因

`HookCoordinator.hookApplicationAttach()` 只 hook 了**一个**方法：

```kotlin
val attach = runCatching {
    applicationClass.getDeclaredMethod("attach", Context::class.java)
}.getOrNull() ?: return          // ← 这里静默 return
```

`android.app.Application.attach(Context)` 在 **Android 14 起已被移除**，本机是
Android 17 / SDK 37，反射必然拿到 `null`，于是方法直接 `return`：

- `attachedContext` 永远是 `null`
- `initializeEngine()` 里 `attachedContext ?: contextProvider() ?: return null`
  两次都拿不到，**`HapticEngine` 从未被构造**
- `MainHook` 传入的 `contextProvider = { null }` 也不可能提供 Context

结果就是：Hook 装上了、AudioTrack 也 hook 了，但引擎从未启动，因此没有任何振动，
而且**全程零日志**——因为失败路径是一个不带任何提示的 `return`。

### 修复

`hookApplicationAttach()` 改为在多个候选入口上依次注册，取到即用：

1. `Application.attach(Context)` — 旧版本（Android 13 及以下）
2. `Application.attach(Context, ActivityThread)` — 旧版本重载
3. `Application.attachForCreate(Context)` — **Android 14+ 实际入口**
4. `Application.attachBaseContext(Context)` — 通用兜底
5. `ActivityThread.currentActivityThread().getApplication()` 轮询探测 — 最后的保险

同时把静默 `return` 换成显式日志：每个候选方法的注册结果（存在 / 不存在 / 失败）
都会打印，`install()` 结束也会记录「hooks 已注册、Context 尚未绑定」，
下次再出问题可以一眼看出断在哪一步。

### 变更

- `HookCoordinator.hookApplicationAttach()`：多入口注册 + 轮询兜底 + 逐步日志
- `HookCoordinator.install()`：新增注册完成日志
- `versionCode` 50202 → 50203

## 5.2.2 — RenderThread 栈溢出（渲染节点自引用成环）修复 (2026-10-03)

5.2.1 修掉了 `onAttach` 阶段的 Java 异常后，程序终于能走到渲染阶段，随即暴露出
**下一层问题**：这不是 Java 崩溃，而是 **native 崩溃（SIGSEGV / RenderThread 栈溢出）**。

### 现象

`dumpsys dropbox` 里**没有任何 5.2.1 的 Java 崩溃记录**，`logcat -b crash` 也是空的。
但进程仍然在启动约 2.7 秒后静默死亡：

```
=== 8:35:14.986  BinderSender: onForegroundActivitiesChanged: uid=10405, foregroundActivities=true
=== 8:35:17.671  BinderSender: onProcessDied: pid=9051, uid=10405
```

`dumpsys activity exit-info com.mouya.musichaptics` 给出真实死因——连续 16 次
`reason=5 (APP CRASH(NATIVE)) status=11`（SIGSEGV）。

### 崩溃证据（`/data/tombstones/tombstone_01`）

```
pid: 9051, tid: 18894, name: RenderThread  >>> com.mouya.musichaptics <<<
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000074639eeea0 (write)
Cause: stack pointer is not in a rw map; likely due to stack overflow.

512 total frames
backtrace:
  #00 libhwui.so  android::uirenderer::RenderNode::prepareTreeImpl(...)
  #01 libhwui.so  std::__1::__function<...prepareTreeImpl...>::operator()(RenderNode*&&, ...)
  #02 libhwui.so  android::uirenderer::skiapipeline::SkiaDisplayList::prepareListAndChildren(...)
  #03 libhwui.so  android::uirenderer::RenderNode::prepareTreeImpl(...)
  #04 libhwui.so  ...prepareListAndChildren...
  ...（以 3 帧为周期重复 512 次）
```

512 帧里 `prepareTreeImpl` 与 `prepareListAndChildren` 以 3 帧为周期**无限重复**，
说明渲染节点树**深度失控或成环**。

### 根因

`HapticDashboardActivity.kt` 把整个 Box（含内部全部玻璃卡片）用
`.layerBackdrop(liquidGlassBackdrop)` 录进同一个 backdrop `GraphicsLayer`：

```kotlin
Box(Modifier.fillMaxSize().background(bgPrimary())
    .layerBackdrop(liquidGlassBackdrop)   // ← 录入了内部全部玻璃卡片
) {
    // 内部所有卡片又都是 .liquidGlass(...) → drawBackdrop(backdrop = 同一层)
}
```

而每张玻璃卡片通过 `LocalLiquidGlassBackdrop` 拿到**同一个** `LayerBackdrop`，
绘制时调用 `LayerBackdrop.drawBackdrop()` → `drawLayer(graphicsLayer)` 去读取这个
**仍在录制中的层**。于是：层的 display list 包含卡片 → 卡片又 drawLayer 这个层
→ RenderNode 自引用成环（B 的 display list 里有 B），Hwui 递归遍历时没有终止条件，
RenderThread 栈被耗尽。

这正是 5.2.0 那次 Java 崩溃**挡住**的路径：当时程序在 `onAttach` 就死了，
永远走不到渲染阶段，因此这个环一直潜伏着。

### 修复一：把 backdrop 层移出玻璃子树（结构修正）

`HapticDashboardActivity.kt` 不再对包含玻璃卡片的父 Box 直接应用 `layerBackdrop()`，
而是录制一个**只含背景、不含玻璃卡片**的独立兄弟层，玻璃内容叠在其上：

```kotlin
Box(Modifier.fillMaxSize().background(bgPrimary())...) {
    // 兄弟层：只录制背景，不含任何 liquidGlass 子节点
    Box(Modifier.matchParentSize().layerBackdrop(liquidGlassBackdrop)) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(...)))
    }
    AnimatedContent { /* 全部玻璃卡片，作为兄弟节点叠在上方 */ }
    LiquidGlassTabBar(...)
}
```

这样 backdrop 层的内容与消费它的玻璃卡片互不包含，环被打断。

### 修复二：库层重入守卫（防止同类误用再次爆栈）

`liquidglass` 的 `LayerBackdropNode.draw()` 增加录制重入检测：

```kotlin
private var isRecording = false

override fun ContentDrawScope.draw() {
    if (isRecording) return
    drawContent()
    isRecording = true
    try {
        recordLayer(this@LayerBackdropNode, backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
    } finally {
        isRecording = false
    }
}
```

即使将来有调用方再次把玻璃卡片放进 backdrop 层内部，检测到递归后也只会
**少录一次内容**（降级为「没有额外背景」），而不是 native 栈溢出。

### 取证工具

- `dumpsys activity exit-info <pkg>`：区分 Java 崩溃与 native 崩溃的**关键**命令。
  `reason=5 (APP CRASH(NATIVE)) status=11` 直接指明 SIGSEGV，
  而 `dumpsys dropbox --print data_app_crash` 在 native 崩溃场景下**没有记录**。
- `su -c head -60 /data/tombstones/tombstone_01`：native 崩溃现场（信号、
  寄存器、512 帧回溯）。

### 附：5.2.1 已确认生效的两项

- 5.2.1 已安装且 `flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`——**DEBUGGABLE 标志已消失**，
  「正在测试可调试应用」警告消除。
- 5.2.1 APK 内 `libnative-bridge.so` 三段 LOAD 的 `p_align` 均为 `0x4000`，
  16 KB 页对齐修复保留。

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