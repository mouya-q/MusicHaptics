# Contributing

感谢贡献代码、测试结果和设备适配数据。

## 提交前

运行：

```bash
python3 scripts/repo_check.py
./gradlew assembleDebug
```

Android 构建需要 JDK 17、SDK 34、NDK 27.0.12077973 和 CMake 3.22.1。

## 改动原则

- Hook 代码优先保证宿主音频线程延迟，不在 `AudioTrack.write()` 回调里做 FFT、Root 命令或阻塞 IPC。
- Native DSP 使用固定内存，避免实时路径上的堆分配。
- 触觉参数要解释“为什么这样调”，不要只堆常量。
- 新增机型优先建立独立 `DeviceTuning`，不要直接修改默认参数。
- 新增硬件节点时必须同时考虑节点语义、权限和写入频率。
- 第三方源码必须保留许可证和来源，并同步更新 `THIRD_PARTY_NOTICES.md`。
- 不提交 APK、AAB、debug keystore、构建目录、临时 patch、编辑器缓存或未授权字体。

## Pull Request

PR 描述至少包含：改动目的、受影响链路、实机设备 / ROM、是否改变默认触觉行为，以及已经执行的验证命令。
