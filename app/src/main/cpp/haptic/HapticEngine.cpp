// HapticEngine.cpp — C++ 核心触觉引擎（DSP 层）
// v1.7: ODR fix — all implementation now lives in HapticEngine.hpp
// This file is kept for CMakeLists compatibility; it simply includes the header.
// The header uses inline methods to ensure single definition across TUs.

#include "HapticEngine.hpp"

// All class methods are defined inline in HapticEngine.hpp.
// This file exists solely to satisfy the CMake build system's source list.
// No additional symbols are defined here to avoid ODR violations.