#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app" / "src" / "main"
JAVA = APP / "java"
CPP = APP / "cpp"
errors = []

required = [
    ROOT / "README.md", ROOT / "CHANGED.md", ROOT / "CONTRIBUTING.md",
    ROOT / "SECURITY.md", ROOT / "THIRD_PARTY_NOTICES.md", ROOT / "docs" / "ARCHITECTURE.md",
    ROOT / "LICENSE", CPP / "jni" / "NativeBridge.cpp",
]
for path in required:
    if not path.exists(): errors.append(f"missing: {path.relative_to(ROOT)}")

for path in ROOT.rglob("*"):
    if not path.is_file(): continue
    rel = path.relative_to(ROOT).as_posix()
    low = rel.lower()
    if any(token in low for token in ("pingfang", "pingfangsc", "pingfang_hack")):
        errors.append(f"font residue: {rel}")
    if path.suffix.lower() in {".bak", ".orig", ".patch", ".tmp", ".apk", ".aab"}:
        errors.append(f"temporary artifact: {rel}")

for rel in ("app/src/main/cpp/cpp", "app/src/main/cpp_disabled", "app/build"):
    if (ROOT / rel).exists(): errors.append(f"obsolete directory: {rel}")

legacy = [
    "RootActivationActivity.kt", "HapticEventGenerator.kt", "HapticComposer.kt",
    "HapticPrimitive.kt", "MusicStructureAnalyzer.kt", "InstrumentFeatures.kt", "TelemetryHub.kt",
]
for rel in legacy:
    if (JAVA / "com" / "mouya" / "musichaptics" / rel).exists():
        errors.append(f"legacy file remains: {rel}")

kt_files = list(JAVA.rglob("*.kt"))
kt_text = "\n".join(p.read_text(encoding="utf-8", errors="ignore") for p in kt_files)
cpp_text = "\n".join(p.read_text(encoding="utf-8", errors="ignore") for p in CPP.rglob("*.cpp"))

kt_jni = set(re.findall(r"external fun (native[A-Za-z0-9_]+)", kt_text))
cpp_jni = set(re.findall(r"Java_com_mouya_musichaptics_NativeBridge_(native[A-Za-z0-9_]+)", cpp_text))
for name in sorted(kt_jni - cpp_jni): errors.append(f"missing JNI implementation: {name}")

profile_text = (JAVA / "com/mouya/musichaptics/DeviceProfile.kt").read_text(encoding="utf-8")
tuning_text = (JAVA / "com/mouya/musichaptics/haptic/DeviceTuning.kt").read_text(encoding="utf-8")
profiles = set(re.findall(r"\bval\s+([A-Z0-9_]+)\s*=\s*DeviceProfile\(", profile_text)) - {"DEFAULT"}
tuning = set(re.findall(r"DeviceProfile\.([A-Z0-9_]+)\s*->\s*DeviceTuning", tuning_text))
for name in sorted(profiles - tuning): errors.append(f"device tuning missing: {name}")

settings = [
    "master_switch", "selected_preset", "haptic_amplitude", "haptic_bass_boost", "style_preset",
    "force_default_amplitude", "synth_lra_f0", "synth_lra_q", "synth_rate_hz",
    "synth_attack_impact", "synth_decay_impact", "synth_attack_continuous",
    "synth_decay_continuous", "synth_release", "synth_sustain", "synth_thermal_warn",
    "synth_thermal_crit", "synth_thermal_rth", "synth_thermal_cth", "synth_impact_gain",
    "synth_continuous_gain", "synth_texture_gain", "synth_master_gain",
]
dash = (JAVA / "com/mouya/musichaptics/HapticDashboardActivity.kt").read_text(encoding="utf-8")
engine = (JAVA / "com/mouya/musichaptics/HapticEngine.kt").read_text(encoding="utf-8")
proxy = (JAVA / "com/mouya/musichaptics/VibrateProxy.kt").read_text(encoding="utf-8")
provider = (JAVA / "com/mouya/musichaptics/ConfigProvider.kt").read_text(encoding="utf-8")
for key in settings:
    if key not in dash: errors.append(f"setting missing from dashboard: {key}")
    if key not in provider: errors.append(f"setting missing from provider: {key}")
    consumer = proxy if key == "force_default_amplitude" else engine
    if key not in consumer: errors.append(f"setting missing runtime consumer: {key}")

# Per-app settings must share keys with the engine.
scoped = dash[dash.find("private fun ScopedAppSettings"):]
for key in ("master_switch", "haptic_amplitude", "haptic_bass_boost"):
    if key not in scoped: errors.append(f"scoped setting missing: {key}")

if "点击复制" in kt_text: errors.append("QQ copy hint remains")
if "RootActivationActivity" in kt_text: errors.append("root onboarding symbol remains")
if any(x in kt_text for x in ("HapticEventGenerator", "MusicStructureAnalyzer", "InstrumentFeatures")):
    errors.append("legacy runtime symbol remains")
if "runtime-livedata" in (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"):
    errors.append("unused runtime-livedata dependency remains")

def comment_cjk(text):
    block = False
    for n, line in enumerate(text.splitlines(), 1):
        if block:
            if "*/" in line:
                block=False
            if re.search(r"[\u3400-\u9fff]", line): return n
            continue
        if "/*" in line:
            block=True
            if re.search(r"[\u3400-\u9fff]", line.split("/*",1)[1]): return n
        if "//" in line:
            if re.search(r"[\u3400-\u9fff]", line.split("//",1)[1]): return n
    return None
for path in list(JAVA.rglob("*.kt")) + list(CPP.rglob("*.cpp")) + list(CPP.rglob("*.hpp")) + list(CPP.rglob("*.h")):
    hit = comment_cjk(path.read_text(encoding="utf-8", errors="ignore"))
    if hit: errors.append(f"non-English comment: {path.relative_to(ROOT)}:{hit}")

cmake = (CPP / "CMakeLists.txt").read_text(encoding="utf-8", errors="ignore")
for flag in ("max-page-size=16384", "common-page-size=16384"):
    if flag not in cmake: errors.append(f"missing 16KB flag: {flag}")

if errors:
    print("repository check failed")
    for error in errors: print("-", error)
    sys.exit(1)

print("repository check passed")
print(f"JNI declarations checked: {len(kt_jni)}")
print(f"Device profiles checked: {len(profiles)}")
print(f"Runtime settings checked: {len(settings)}")
