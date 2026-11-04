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
    ROOT / "README.md", ROOT / "CHANGED.md",
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
    "master_switch", "selected_preset", "haptic_amplitude", "haptic_bass_boost", "sync_offset_ms", "style_preset",
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
for key in ("master_switch", "haptic_amplitude", "haptic_bass_boost", "sync_offset_ms"):
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

# --- Rust DSP core -----------------------------------------------------------
#
# The Rust core is linked into libnative-bridge.so as a static archive, so a
# missing or desynced build breaks the whole native library at link time rather
# than at runtime. These checks fail fast with a clear message.
RUST = APP / "rust"
rust_src = RUST / "src"
for rel in ("Cargo.toml", "src/lib.rs", "src/engine.rs", "src/onset.rs",
            "src/filter.rs", "src/ring.rs", "src/ffi.rs", "src/chain.rs",
            "build-android.sh"):
    if not (RUST / rel).exists():
        errors.append(f"missing rust source: {rel}")

abi_header = CPP / "jni" / "mhx_dsp_abi.h"
if not abi_header.exists():
    errors.append("missing Rust ABI header: app/src/main/cpp/jni/mhx_dsp_abi.h")

if rust_src.exists() and abi_header.exists():
    rust_text = "\n".join(
        p.read_text(encoding="utf-8", errors="ignore")
        for p in rust_src.glob("*.rs")
    )
    header_text = abi_header.read_text(encoding="utf-8", errors="ignore")

    # Every extern "C" symbol declared in the header must be defined with
    # #[no_mangle] in Rust, and vice versa. A mismatch links to a null reference
    # and presents as "analysis returns zeros" -- silent by nature.
    header_symbols = set(re.findall(r"\b(mhx_[a-z0-9_]+)\s*\(", header_text))
    rust_exports = set(re.findall(r"#\[no_mangle\]\s*pub\s+(?:unsafe\s+)?extern\s+\"C\"\s+fn\s+(mhx_[a-z0-9_]+)", rust_text))
    for name in sorted(header_symbols - rust_exports):
        errors.append(f"rust symbol not exported: {name}")
    for name in sorted(rust_exports - header_symbols):
        errors.append(f"rust symbol missing from abi header: {name}")

    # The telemetry array size is duplicated in three places; assert they agree.
    m = re.search(r"MHX_TELEMETRY_LEN\s*=\s*(\d+)", header_text)
    if m:
        header_len = int(m.group(1))
        m2 = re.search(r"TELEMETRY_LEN:\s*usize\s*=\s*(\d+)", rust_text)
        if m2 and int(m2.group(1)) != header_len:
            errors.append(
                f"telemetry length mismatch: header={header_len} rust={m2.group(1)}"
            )

    # The ABI version must match too, otherwise a stale archive is accepted.
    m = re.search(r"MHX_DSP_ABI_VERSION\s*=\s*(\d+)", header_text)
    m2 = re.search(r"fn\s+mhx_abi_version\s*\(\s*\)\s*->\s*u32\s*\{\s*(\d+)", rust_text)
    if m and m2 and m.group(1) != m2.group(1):
        errors.append(f"ABI version mismatch: header={m.group(1)} rust={m2.group(1)}")

# The Rust archive must be produced before CMake configures, or the build dies
# with the FATAL_ERROR in CMakeLists. Check the CI workflow still sequences it.
workflow = ROOT / ".github" / "workflows" / "build.yml"
if workflow.exists():
    wf = workflow.read_text(encoding="utf-8", errors="ignore")
    if "build-android.sh" not in wf:
        errors.append("CI workflow does not build the Rust core")
    if "cargo test" not in wf:
        errors.append("CI workflow does not run the Rust tests")

# R8 must keep every Kotlin method the native code looks up by name. A stale
# signature in the keep rule lets R8 delete the method and GetMethodID then fails
# at runtime, which silently disabled all haptic events in release builds.
_cpp = (ROOT / "app/src/main/cpp/jni/NativeBridge.cpp").read_text(encoding="utf-8", errors="ignore")
_pro = (ROOT / "app/proguard-rules.pro").read_text(encoding="utf-8", errors="ignore")
_jtypes = {"V": "void", "I": "int", "Z": "boolean", "J": "long", "F": "float", "D": "double"}
def _java_sig(desc):
    args, ret = re.match(r"\((.*)\)(.*)", desc).groups()
    out, i = [], 0
    while i < len(args):
        if args[i] == "L":
            j = args.index(";", i); out.append(args[i + 1:j].replace("/", ".")); i = j + 1
        else:
            out.append(_jtypes[args[i]]); i += 1
    return _jtypes.get(ret, ret), ", ".join(out)
for name, desc in re.findall(r'GetMethodID\([^,]+,\s*"(\w+)",\s*"([^"]+)"\)', _cpp):
    ret, params = _java_sig(desc)
    if f"{ret} {name}({params});" not in _pro:
        errors.append(f"proguard-rules.pro lacks a keep rule for JNI callback: {ret} {name}({params});")

if errors:
    print("repository check failed")
    for error in errors: print("-", error)
    sys.exit(1)

print("repository check passed")
print(f"JNI declarations checked: {len(kt_jni)}")
print(f"Device profiles checked: {len(profiles)}")
print(f"Runtime settings checked: {len(settings)}")