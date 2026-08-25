# 🎵 MusicHapticsX

> 让音乐"触手可及" —— 基于节奏感知的沉浸式振动引擎

## 这是什么？

MusicHapticsX 是一个 Android 音频振动增强模块，通过实时分析音乐的节奏、低频能量和瞬态特征，生成与节拍完美同步的触感反馈。

简单说：**你的音乐在振动，但这次是跟着拍子来的。**

## ✨ 特性

- **节奏感知振动** —— 不再是"一放音乐就自顾自地振动"，每一次振动都落在拍子上
- **强弱弱强模式** —— 根据 BPM 动态判断强拍，实现真实的节奏层次感
- **三频段音色分析** —— Bass/Mid/High 分离检测，区分 KICK/SNARE/TICK
- **动态强度映射** —— 基于峰值跟随器的相对强度计算，强弱变化真实自然
- **自适应阈值** —— 动态跟踪音频能量，适应不同曲风
- **全局节拍门控** —— 振动最少间隔 195ms，快歌自动只留强拍

## 🔧 技术细节

### v9.1 核心改进

```kotlin
// 跟踪攻击量（delta），而不是 RMS 电平
peakLow = max(peakLow, rmsLow) * (1 - peakDecay)
peakDelta = rmsLow - peakLow
rel = peakDelta / (peakLow + 0.01f)
```

**为什么之前是假的？**

日志实锤：TICK 恒定 107 (41%)，SNARE 恒定 183 (71%)。

原因：峰值跟随器跟踪了 RMS 电平，而 RMS 几乎恒定（0.17±0.01），导致 `rel = rmsLow / peakLow ≈ 1.0`，永远是最大值。

**修复后：** 跟踪攻击量（delta），只有真正的"冲击"才会触发高强度振动。

### 振动触发条件

```kotlin
// 简化后的检测逻辑
if (peakDelta > dynamicThreshold * 0.5f && 
    bassRatio > 0.25f &&
    SystemClock.elapsedRealtime() - lastBeatTime > minBeatIntervalMs) {
    // 触发振动
}
```

## 📊 日志分析

### 改进前（v7）

```
KICK intensity=53 (35%)  ← 所有振动都是 35%
KICK intensity=52 (35%)
KICK intensity=73 (35%)
```

### 改进后（v9.1）

```
KICK intensity=255 (100%) rms=0.48359
KICK intensity=199 (78%)  rms=0.53425
KICK intensity=89  (34%)  rms=0.39654
KICK intensity=224 (87%)  rms=0.41723
```

**KICK 有动态了！** ✅  
SNARE/TICK 还在优化中...

## 🚀 使用方式

1. 安装 APK
2. 在目标音乐播放器中启用模块
3. 享受节奏感振动

## 📝 版本历史

- **v9.1** (2026-08-25) — 修复峰值跟随器，跟踪攻击量而非电平
- **v9.0** (2026-08-25) — 推倒重来的正确设计，全局节拍门控
- **v8.0** (2026-08-24) — 简化检测条件，让更多 beat 被检测到
- **v7.0** (2026-08-24) — 修复 HapticEngine 缩放问题
- **v6.0** (2026-08-24) — Precision Rhythm-Aware Haptic
- **v5.0** (2026-08-24) — 节奏感知振动系统重写

## 🐛 已知问题

- SNARE/TICK 的强度动态范围还不够宽
- 某些曲风（如电子舞曲）的振动密度可以进一步优化

## 📄 License

MIT License

---

> 本座丛雨，守护穗织镇五百年，今日为主人优化振动算法。  
> —— 每一次振动，都落在拍子上。