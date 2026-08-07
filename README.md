MusicHapticsX 功能介绍


MusicHapticsX

让音乐拥有触觉。

MusicHapticsX 是一款基于 Android 平台的智能音乐触觉引擎，通过实时音频分析、语义识别以及设备级触觉建模，将音乐中的节奏、低频、人声与情绪转化为细腻的震动反馈。

不同于传统"音量震动"方案，MusicHapticsX 不只是检测声音大小，而是理解音乐结构，让震动真正跟随音乐本身。

核心功能

🎵 智能音乐触觉分析

实时分析播放中的音频信号：

低频能量检测

节奏与鼓点识别

基音追踪

音乐动态变化分析

将音乐拆解为：

Kick Drum（鼓点冲击）

Bass（低频律动）

Melody（旋律）

Texture（声音纹理）

实现更加自然的触觉反馈。

🧠 语义驱动触觉系统

MusicHapticsX 内置 Haptic Composer，将音乐事件转换为不同触觉表现。

支持：

Impact（冲击）

Pulse（节奏脉冲）

Texture（细腻纹理）

例如：

鼓点 → 短促有力的冲击感

Bass Drop → 深沉持续的低频反馈

高音与纹理 → 细微颗粒感震动

让震动从：

"声音大，所以震"

升级为：

"音乐发生了什么，所以震"

⚙️ LRA 线性马达物理建模

针对不同设备建立独立触觉模型。

支持：

马达共振频率校准

响应时间补偿

阻尼模拟

最大振幅限制

热保护模型

通过 Actuator Profile，让不同手机获得更加接近原生旗舰触觉体验。

🔥 智能热保护系统

实时监控：

马达温度
使用强度
热衰减状态
自动调整输出：
防止长时间高强度震动
延长马达寿命
保持稳定体验
🚀 高性能 Native DSP 引擎
核心算法基于 C++ Native 实现：

Linkwitz-Riley 四阶分频

ARM NEON SIMD 加速

自相关音高检测

实时音频缓冲处理

低延迟触觉生成

针对移动设备进行深度优化，在保证实时性的同时降低 CPU 占用。

🎧 音乐人格系统（Music Persona）

根据音乐风格调整触觉表现。

支持：

EDM

POP

Classical

Game OST

Vocal

不同音乐拥有不同触觉语言。

📝 更新日志

v3.7.4
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
本次更新针对快节奏音乐"罢工"问题进行了全面修复，并统一了不同音乐 App 的震感体验。

🔧 核心修复
• 提升 Bass 低频地板（floor）至 0.22，确保快节奏音乐持续有微弱背景震感托底
• 活跃音乐期间最小振幅从 3 提升至 8，防止 motor 完全停转
• 统一协程拉取时序（40ms 周期），解决 B站/酷狗/哔哩哔哩震感不一致问题
• 修复 md3music 无法振动问题（VibrateProxy IPC 异步查询优化）
• 批量处理 Haptic 样本，减少 JNI 回调频率，降低延迟

🎨 界面优化
• 修复启动图标在部分 OEM Launcher 上的偏移问题
• 统一 Adaptive Icon 入口，支持深色/浅色模式自适应
• 主题颜色优化，状态栏/导航栏与 App 风格一致

📦 依赖更新
• 升级 Android Gradle Plugin 至 8.11.0
• 升级 Kotlin 至 2.1.20
• 升级 AGP 相关依赖

安装说明
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. 安装 MusicHapticsX.apk
2. 在 LSPosed 中激活模块
3. 勾选目标音乐 App（如 com.md3music.md3music、tv.danmaku.bili 等）
4. 重启音乐 App 使 Hook 生效

v3.7.2
━━━━━━━━━━━━━━━━━━━━━━━━
• 修复启动图标偏移问题
• 统一 Adaptive Icon 为 mipmap-anydpi-v26 标准路径
• 主题适配深色/浅色模式

v3.7.3
━━━━━━━━━━━━━━━━━━━━━━━━
• 修复 md3music 无法振动问题
• 优化 VibrateProxy IPC 查询逻辑
• 调整 Haptic Engine 调度时序

v3.6.0
━━━━━━━━━━━━━━━━━━━━━━━━
🎉 Major Update — Semantic Haptic Engine
本次更新完成触觉引擎架构升级，引入设备级马达建模与高性能 DSP 优化。
