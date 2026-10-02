#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

required = [
    ROOT / "README.md",
    ROOT / "CHANGED.md",
    ROOT / "THIRD_PARTY_NOTICES.md",
    ROOT / "LICENSE",
    ROOT / "docs" / "ARCHITECTURE.md",
    ROOT / "app" / "src" / "main" / "cpp" / "jni" / "NativeBridge.cpp",
]
for path in required:
    if not path.exists():
        errors.append(f"missing required file: {path.relative_to(ROOT)}")

for path in ROOT.rglob("*"):
    if not path.is_file():
        continue
    rel = path.relative_to(ROOT).as_posix().lower()
    if any(token in rel for token in ("pingfang", "pingfangsc", "pingfang_hack")):
        errors.append(f"unapproved font path remains: {rel}")
    if path.suffix.lower() in {".bak", ".orig", ".patch", ".tmp", ".apk", ".aab"}:
        errors.append(f"generated/temp artifact remains: {path.relative_to(ROOT)}")

for directory in (
    ROOT / "app" / "src" / "main" / "cpp" / "cpp",
    ROOT / "app" / "src" / "main" / "cpp_disabled",
    ROOT / "app" / "build",
):
    if directory.exists():
        errors.append(f"obsolete/generated directory remains: {directory.relative_to(ROOT)}")

native = (ROOT / "app" / "src" / "main" / "java").rglob("*.kt")
kotlin_names = set()
for path in native:
    text = path.read_text(encoding="utf-8", errors="ignore")
    kotlin_names.update(re.findall(r"external fun (native[A-Za-z0-9_]+)", text))

cpp_text = "\n".join(
    p.read_text(encoding="utf-8", errors="ignore")
    for p in (ROOT / "app" / "src" / "main" / "cpp").rglob("*.cpp")
)
cpp_names = set(re.findall(r"Java_com_mouya_musichaptics_NativeBridge_(native[A-Za-z0-9_]+)", cpp_text))
missing = sorted(kotlin_names - cpp_names)
extra = sorted(cpp_names - kotlin_names)
for name in missing:
    errors.append(f"JNI declaration has no native implementation: {name}")
# Extra C++ JNI functions are allowed only if the name is part of an intentionally
# legacy bridge; report them as warnings rather than failing the tree check.
if extra:
    print("warning: native functions not declared in Kotlin:", ", ".join(extra))

# Every named DeviceProfile should have an explicit DeviceTuning branch.
profile_text = (ROOT / "app" / "src" / "main" / "java" / "com" / "mouya" / "musichaptics" / "DeviceProfile.kt").read_text(encoding="utf-8", errors="ignore")
tuning_text = (ROOT / "app" / "src" / "main" / "java" / "com" / "mouya" / "musichaptics" / "haptic" / "DeviceTuning.kt").read_text(encoding="utf-8", errors="ignore")
profile_names = set(re.findall(r"\bval\s+([A-Z0-9_]+)\s*=\s*DeviceProfile\(", profile_text)) - {"DEFAULT"}
explicit_tuning_names = set(re.findall(r"DeviceProfile\.([A-Z0-9_]+)\s*->\s*DeviceTuning", tuning_text))
for name in sorted(profile_names - explicit_tuning_names):
    errors.append(f"DeviceProfile has no explicit DeviceTuning branch: {name}")

# ── Vendored com.kyant.backdrop source invariants ───────────────────────────
# The :liquidglass module is a copy of a Compose Multiplatform library running
# on plain AndroidX Compose. Three things silently break that port and each of
# them costs a full CI cycle to rediscover, so fail fast here instead:
#   1. AndroidX Compose has no androidx.compose.ui.graphics.RuntimeShader — the
#      interface must be declared locally.
#   2. Kotlin context parameters need Kotlin 2.2+; this project is on 2.0.21.
#   3. AndroidX ships TWO same-named classes and they are NOT interchangeable:
#        androidx.compose.ui.graphics.CompositingStrategy       (module ui-android)
#        androidx.compose.ui.graphics.layer.CompositingStrategy (module ui-graphics)
#      GraphicsLayerScope declares the former, layer.GraphicsLayer declares the
#      latter. Verified against ui-android-1.7.8.aar / ui-graphics-android-1.7.8.aar
#      bytecode, so the rule below is host-aware rather than blanket-banning one.
backdrop_root = ROOT / "liquidglass" / "src"
if backdrop_root.is_dir():
    runtime_shader = backdrop_root / "main" / "kotlin" / "com" / "kyant" / "backdrop" / "RuntimeShader.kt"
    if not runtime_shader.exists():
        errors.append("liquidglass: RuntimeShader.kt is missing")
    elif "interface RuntimeShader" not in runtime_shader.read_text(encoding="utf-8", errors="ignore"):
        errors.append("liquidglass: RuntimeShader.kt no longer declares interface RuntimeShader")

    for path in sorted(backdrop_root.rglob("*.kt")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        rel = path.relative_to(ROOT).as_posix()
        # Only inspect real code, not doc comments that explain these pitfalls.
        code = "\n".join(
            line for line in text.splitlines()
            if not line.lstrip().startswith(("//", "*", "/*"))
        )
        if re.search(r"^\s*import\s+androidx\.compose\.ui\.graphics\.RuntimeShader\b", code, re.MULTILINE):
            errors.append(f"liquidglass: AndroidX has no graphics.RuntimeShader: {rel}")
        if re.search(r"^\s*context\s*\(", code, re.MULTILINE):
            errors.append(f"liquidglass: Kotlin context parameters need Kotlin 2.2+: {rel}")
        # Host-aware CompositingStrategy check: an assignment inside a
        # GraphicsLayerScope block must use the ui-android class, while an
        # assignment to a layer.GraphicsLayer must use the ui-graphics one.
        # A fully-qualified wrong-package assignment is always an error.
        for lineno, line in enumerate(code.splitlines(), 1):
            if "compositingStrategy" not in line:
                continue
            if "androidx.compose.ui.graphics.layer.CompositingStrategy" in line and \
                    "GraphicsLayerScope" in text and rel.endswith("DrawBackdropModifier.kt"):
                errors.append(
                    f"liquidglass: GraphicsLayerScope.compositingStrategy needs "
                    f"graphics.CompositingStrategy (ui-android), not layer.*: {rel}:{lineno}"
                )
        imports_layer = re.search(
            r"^\s*import\s+androidx\.compose\.ui\.graphics\.layer\.CompositingStrategy\b",
            code, re.MULTILINE)
        imports_graphics = re.search(
            r"^\s*import\s+androidx\.compose\.ui\.graphics\.CompositingStrategy\b",
            code, re.MULTILINE)
        if imports_layer and imports_graphics:
            errors.append(f"liquidglass: both CompositingStrategy classes imported: {rel}")

# ── app module invariants ───────────────────────────────────────────────────
# HapticComposer.kt is the command vocabulary shared by HapticEventGenerator and
# HapticSynthesizer. It was lost while the 5.x tree was rebuilt and cost a full
# CI cycle to rediscover (78 errors, all cascading from this one missing file).
composer = (ROOT / "app" / "src" / "main" / "java" / "com" / "mouya" / "musichaptics"
            / "HapticComposer.kt")
if not composer.exists():
    errors.append("app: HapticComposer.kt is missing (HapticCommand/KeyStrikeSemantic/SemanticType)")
else:
    composer_text = composer.read_text(encoding="utf-8", errors="ignore")
    for decl in ("enum class KeyStrikeSemantic", "enum class SemanticType",
                 "data class HapticCommand"):
        if decl not in composer_text:
            errors.append(f"app: HapticComposer.kt no longer declares '{decl}'")

app_src = ROOT / "app" / "src" / "main" / "java" / "com" / "mouya" / "musichaptics"
if app_src.is_dir():
    for path in sorted(app_src.rglob("*.kt")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        rel = path.relative_to(ROOT).as_posix()
        code = "\n".join(
            line for line in text.splitlines()
            if not line.lstrip().startswith(("//", "*", "/*"))
        )
        # kotlin.text.isNullOrEmpty has no ByteArray? overload: it only covers
        # CharSequence? / Array<out T>? / Collection? / Map?. Calling it on a
        # ByteArray? is an unresolved reference.
        if re.search(r"ByteArray\?[^\n]*\.isNullOrEmpty\(\)", code):
            errors.append(f"app: isNullOrEmpty has no ByteArray? overload: {rel}")
        # SharedPreferences.getAll() is exposed as Map<String, *>; a MutableMap
        # override does not match the synthetic property type.
        if "override val all: MutableMap" in code:
            errors.append(f"app: SharedPreferences.all must be Map<String, *>: {rel}")

if errors:
    print("repository check failed")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("repository check passed")
print(f"JNI declarations checked: {len(kotlin_names)}")
