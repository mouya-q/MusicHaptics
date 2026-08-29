# 🎵 MusicHapticsX

> 让音乐"触手可及" —— 基于节奏感知的沉浸式振动引擎
>
> **Feel the Beat** — An immersive haptic engine powered by rhythm-aware vibration.

## 这是什么？ / What is this?

**中文：**
MusicHapticsX 是一个基于 LSPosed 的 Android 音乐触觉引擎，通过 Native DSP、节奏识别、Haptic Composer 以及设备级 LRA 建模，让 Android 设备实现智能化音乐震动反馈。

**English:**
MusicHapticsX is an LSPosed-based Android music haptic engine that delivers intelligent music vibration feedback through Native DSP, rhythm recognition, Haptic Composer, and device-level LRA modeling.

简单说：**你的音乐在振动，但这次是跟着拍子来的。**

In short: **Your music vibrates, but this time it's on beat.**

## ✨ 特性 / Features

- **节奏感知振动** — 不再是"一放音乐就自顾自地振动"，每一次振动都落在拍子上
- **Rhythm-Aware Vibration** — No more random buzzing; every haptic hit lands on the beat

- **强弱弱强模式** — 根据 BPM 动态判断强拍，实现真实的节奏层次感
- **Strong-Weak Pattern** — Dynamic beat emphasis based on BPM for authentic rhythmic hierarchy

- **三频段音色分析** — Bass/Mid/High 分离检测，区分 KICK/SNARE/TICK
- **Three-Band Timbre Analysis** — Separated Bass/Mid/High detection for KICK/SNARE/TICK classification

- **动态强度映射** — 基于峰值跟随器的相对强度计算，强弱变化真实自然
- **Dynamic Intensity Mapping** — Peak-follower-based relative intensity for natural dynamics

- **自适应阈值** — 动态跟踪音频能量，适应不同曲风
- **Adaptive Threshold** — Dynamically tracks audio energy across different genres

- **全局节拍门控** — 振动最少间隔 195ms，快歌自动只留强拍
- **Global Beat Gate** — Minimum 195ms interval; fast tracks automatically keep only strong beats

## 🔧 技术细节 / Technical Details

### v9.1 核心改进 / Core Improvements

```kotlin
// 跟踪攻击量（delta），而不是 RMS 电平
// Track attack delta, not RMS level
peakLow = max(peakLow, rmsLow) * (1 - peakDecay)
peakDelta = rmsLow - peakLow
rel = peakDelta / (peakLow + 0.01f)
```

**为什么之前是假的？ / Why was it fake before?**

日志实锤：TICK 恒定 107 (41%)，SNARE 恒定 183 (71%)。

Log evidence: TICK stuck at 107 (41%), SNARE stuck at 183 (71%).

原因：峰值跟随器跟踪了 RMS 电平，而 RMS 几乎恒定（0.17±0.01），导致 `rel = rmsLow / peakLow ≈ 1.0`，永远是最大值。

Cause: The peak follower tracked RMS level, but RMS was nearly constant (0.17±0.01), making `rel = rmsLow / peakLow ≈ 1.0` — always maxed out.

**修复后：** 跟踪攻击量（delta），只有真正的"冲击"才会触发高强度振动。

**After fix:** Tracking attack delta — only real "impacts" trigger high-intensity haptics.

### 振动触发条件 / Beat Trigger Condition

```kotlin
// 简化后的检测逻辑 / Simplified detection logic
if (peakDelta > dynamicThreshold * 0.5f && 
    bassRatio > 0.25f &&
    SystemClock.elapsedRealtime() - lastBeatTime > minBeatIntervalMs) {
    // 触发振动 / Trigger haptic
}
```

## 📊 日志分析 / Log Analysis

### 改进前 / Before (v7)

```
KICK intensity=53 (35%)  ← 所有振动都是 35% / All vibrations at 35%
KICK intensity=52 (35%)
KICK intensity=73 (35%)
```

### 改进后 / After (v9.1)

```
KICK intensity=255 (100%) rms=0.48359
KICK intensity=199 (78%)  rms=0.53425
KICK intensity=89  (34%)  rms=0.39654
KICK intensity=224 (87%)  rms=0.41723
```

**KICK 有动态了！** ✅  
**KICK now has dynamics!** ✅

SNARE/TICK 还在优化中...
SNARE/TICK still being optimized...

## 🚀 使用方式 / Usage

1. 安装 APK / Install APK
2. 在目标音乐播放器中启用模块 / Enable module in target music player
3. 享受节奏感振动 / Enjoy rhythm-synced haptics

## 📝 版本历史 / Version History

- **v9.1** (2026-08-25) — 修复峰值跟随器，跟踪攻击量而非电平 / Fixed peak follower, tracking attack delta instead of RMS level
- **v9.0** (2026-08-25) — 推倒重来的正确设计，全局节拍门控 / Complete redesign with global beat gate
- **v8.0** (2026-08-24) — 简化检测条件，让更多 beat 被检测到 / Simplified detection conditions
- **v7.0** (2026-08-24) — 修复 HapticEngine 缩放问题 / Fixed HapticEngine scaling issue
- **v6.0** (2026-08-24) — Precision Rhythm-Aware Haptic
- **v5.0** (2026-08-24) — 节奏感知振动系统重写 / Rhythm-aware vibration system rewrite

## 🐛 已知问题 / Known Issues

- SNARE/TICK 的强度动态范围还不够宽
- SNARE/TICK intensity dynamic range needs further optimization

- 某些曲风（如电子舞曲）的振动密度可以进一步优化
- Vibration density for certain genres (e.g., EDM) can be further improved

## 📄 License

MIT License

---

> 本座丛雨，守护穗织镇五百年，今日为主人优化振动算法。  
> I am Murasame, guardian of Hozumi Shrine for five hundred years. Today I optimize the haptic algorithm for my master.
>
> —— 每一次振动，都落在拍子上。  
> —— Every vibration lands on the beat.

🧿 NO_BUG_CHARM.webp — 保佑代码无 Bug / May the code be bug-free