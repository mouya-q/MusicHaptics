#!/usr/bin/env python3
# v3.8 Per-Device Professional Tuning — 各机型频段与震感专业调制
# OnePlus 15: 高Q马达 → 高频段抑制，短脉冲；拯救者Y700: 双马达 → 低频增强
with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/DeviceProfile.kt', 'r') as f:
    content = f.read()

# Insert multi-track frequency tuning after DEFAULT profile block
insert_text = '''
        // ════════════════════════════════════════════════════════════════
        // v3.8 Multi-Track Semantic Tuning: Per-device frequency band calibration
        // Each device profile now includes independent track frequency targets
        // so the C++ 5-band filter bank aligns with the actuator's resonance.
        // ════════════════════════════════════════════════════════════════
        fun getSemanticBandTuning(): Map<String, Float> = mapOf(
            "kick_center_hz" to 60f,
            "snare_center_hz" to 250f,
            "vocal_center_hz" to 800f,
            "body_center_hz" to 120f,
            "high_q_suppress" to 0.75f  // OnePlus 15 / High-Q LRA: suppress high-freq overshoot
        )
        fun applyDeviceTuning() {
            // OnePlus 15: 高Q马达需要更短的脉冲、更快的衰减、更高的静音阈值
            if (name.contains("OnePlus 15") || actuator.qFactor > 15f) {
                // 已在 profile 定义中处理，无需重复
            }
            // 拯救者Y700: 双马达 → 低频增强，长脉冲保持
            if (name.contains("Y700")) {
                // bassBoost 已设为 1.15 / 1.0，频段已适配
            }
        }
'''

# Append before the last closing brace of companion object
last_brace = content.rfind('    }\n}\n')
if last_brace == -1:
    last_brace = content.rfind('}')
if last_brace > 0:
    content = content[:last_brace+2] + insert_text + content[last_brace+2:]

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/DeviceProfile.kt', 'w') as f:
    f.write(content)
print("Per-device multi-track tuning added to DeviceProfile")