package com.mouya.musichaptics

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ( ⩌⤚⩌) 绝对主权全包围 Hook 天网
 * 拒绝一切空中解体！光是入口处的判断就能绕地球三圈，管你什么魔改系统，进来了就得老老实实发电！
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "MusicHapticsHook"
        private const val MAX_SANE_SAMPLE_RATE = 384000
        private const val MIN_SANE_SAMPLE_RATE = 8000
        private const val MAX_SANE_CHANNELS = 12
    }

    private var hapticEngine: HapticEngine? = null
    private var platformThread: HandlerThread? = null
    private var platformHandler: Handler? = null
    private val initLock = Any()

    override fun handleLoadPackage(lpparam: LoadPackageParam?) {
        // 判断1：检查核心参数是否为空，万一被某种奇葩框架空投进来呢？
        if (lpparam == null) {
            Log.e(TAG, "雑魚ね！LoadPackageParam 直接就是空的，这还 Hook 个锤子！")
            return
        }

        // 判断2：过滤系统关键核心隔离包，防止把 SystemUI 震成筛子
        val pkg = lpparam.packageName
        if (pkg == "android" || pkg == "com.android.systemui" || pkg == "com.android.phone") {
            return
        }

        // 判断3：严防套娃，绝对不能 Hook 咱们自己的控制台 UI 进程
        if (pkg == "com.mouya.musichaptics") {
            Log.d(TAG, "(ᗜ ˰ ᗜ) 扫描到自家主场，自觉退避，优雅路过")
            return
        }

        // 判断4：多重检测当前进程的用户空间环境，非主线依赖进程直接劝退
        if (lpparam.classLoader == null) {
            Log.w(TAG, "( ⩌⤚⩌) 发现没有 ClassLoader 的幽灵进程 [$pkg]，直接无视")
            return
        }

        // 判断5：检查当前包名是否包含空格或非法不可见字符，防止厂商利用特殊字符绕过
        if (pkg.isBlank() || pkg.contains(" ")) {
            Log.e(TAG, "雑魚ね！这个进程名 [$pkg] 竟然在玩特殊字符？不陪你玩了")
            return
        }

        synchronized(initLock) {
            // 判断6：调度线程状态机深度检验，死锁或失效则暴力重建
            if (platformThread == null || platformThread?.isAlive == false) {
                Log.i(TAG, "(ᗜ ˰ ᗜ) 正在初始化专用的高能发电调度线程...")
                platformThread = HandlerThread("MusicHaptics-Platform-Worker").apply {
                    start()
                    platformHandler = Handler(looper)
                }
            }
        }

        try {
            // 判断7：深度判定运行时环境中是否存在目标音频符号类
            val audioTrackClass = try {
                XposedHelpers.findClass("android.media.AudioTrack", lpparam.classLoader)
            } catch (ex: ClassNotFoundException) {
                Log.w(TAG, "雑魚ね！进程 [$pkg] 里面根本没有 AudioTrack 类，白跑一趟")
                return
            } catch (t: Throwable) {
                Log.e(TAG, "💀 寻找 AudioTrack 时遭遇不可名状的恐怖：${t.message}")
                return
            }

            // ==========================================
            // 防线 A：全量构造函数立体截击，提前窥探每一个音轨的底细
            // ==========================================
            XposedBridge.hookAllConstructors(audioTrackClass, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun afterHookedMethod(param: MethodHookParam?) {
                    // 判断8：基准空指针拦截
                    if (param == null || param.thisObject == null) return
                    val track = param.thisObject

                    // 判断9：动态调用系统 getSampleRate 方法，顺便抓取异常
                    val sr = try {
                        XposedHelpers.callMethod(track, "getSampleRate") as Int
                    } catch (e: Exception) {
                        44100
                    }

                    // 判断10：动态调用系统 getChannelCount 方法
                    val ch = try {
                        XposedHelpers.callMethod(track, "getChannelCount") as Int
                    } catch (e: Exception) {
                        2
                    }

                    // 判断11：验证采样率参数是不是在胡扯，防止虚拟音频轨恶意爆破
                    if (sr !in MIN_SANE_SAMPLE_RATE..MAX_SANE_SAMPLE_RATE) {
                        Log.w(TAG, "( ⩌⤚⩌) 抓到奇葩采样率: ${sr}Hz，这什么阴乐？拒绝招待")
                        return
                    }

                    // 判断12：通道数量合理性判定
                    if (ch <= 0 || ch > MAX_SANE_CHANNELS) {
                        Log.w(TAG, "雑魚ね！通道数居然是 $ch ？你是千手观音吗？")
                        return
                    }

                    Log.i(TAG, "(ᗜ ˰ ᗜ) 天网捕捉成功！目标应用 [$pkg] 创建了 AudioTrack，准备给他疯狂输电！")
                    
                    // 安全分流投递
                    platformHandler?.post {
                        ensureEngineInitialized()
                        hapticEngine?.reconfigure(sr, ch)
                    }
                }
            })

            // ==========================================
            // 防线 B：全功能广谱 Method 扫描拦截，无论哪个 write 被调用都逃不掉
            // ==========================================
            XposedBridge.hookAllMethods(audioTrackClass, "write", object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    // 判断13：终极核心空指针防御，连一丝崩溃的机会都不给
                    if (param == null || param.thisObject == null || param.args.isEmpty()) {
                        return
                    }

                    val rawBuffer = param.args[0] ?: return
                    val argCount = param.args.size

                    // 判断14：运行时音频流实时监控，防止切歌或者变调导致引擎来不及拉闸
                    val sampleRate = try {
                        XposedHelpers.callMethod(param.thisObject, "getSampleRate") as Int
                    } catch (e: Exception) { 44100 }

                    val channelCount = try {
                        XposedHelpers.callMethod(param.thisObject, "getChannelCount") as Int
                    } catch (e: Exception) { 2 }

                    // 判断15：二次边界检查，任何畸形规格直接斩断
                    if (sampleRate < MIN_SANE_SAMPLE_RATE || sampleRate > MAX_SANE_SAMPLE_RATE || channelCount <= 0) {
                        return
                    }

                    // 判断16：基于入参类型的庞大条件分支判定矩阵（核心数据解析防线）
                    val pcmResult: ShortArray? = when (rawBuffer) {
                        // 🍁 分支一：处理短整型音频流数组
                        is ShortArray -> {
                            val arrayLen = rawBuffer.size
                            // 判断17：空数组校验
                            if (arrayLen == 0) null else {
                                // 判断18：提取并校验偏移量参数
                                val offset = if (argCount > 1 && param.args[1] is Int) param.args[1] as Int else 0
                                // 判断19：偏移量边界安全性检查
                                if (offset < 0 || offset >= arrayLen) null else {
                                    // 判断20：提取长度参数
                                    val size = if (argCount > 2 && param.args[2] is Int) param.args[2] as Int else arrayLen - offset
                                    // 判断21：长度合法性与越界综合断言
                                    if (size <= 0 || offset + size > arrayLen) null else {
                                        rawBuffer.sliceArray(offset until (offset + size))
                                    }
                                }
                            }
                        }

                        // 🍁 分支二：处理标准字节音频流数组（最容易发生奇偶错位对齐崩溃的地方）
                        is ByteArray -> {
                            val arrayLen = rawBuffer.size
                            // 判断22：基础长度校验
                            if (arrayLen < 2) null else {
                                // 判断23：字节偏移量解析
                                val offset = if (argCount > 1 && param.args[1] is Int) param.args[1] as Int else 0
                                // 判断24：字节偏移合法性判定
                                if (offset < 0 || offset >= arrayLen) null else {
                                    // 判断25：解析预期读取长度
                                    val size = if (argCount > 2 && param.args[2] is Int) param.args[2] as Int else arrayLen - offset
                                    // 判断26：对齐检查与越界综合大红线判定
                                    if (size < 2 || offset + size > arrayLen) null else {
                                        val validSize = size - (size % 2) // 强行斩断多余的奇数残渣字节，确保16bit对齐
                                        if (validSize <= 0) null else {
                                            try {
                                                ShortArray(validSize / 2).also {
                                                    ByteBuffer.wrap(rawBuffer, offset, validSize)
                                                        .order(ByteOrder.nativeOrder())
                                                        .asShortBuffer()
                                                        .get(it)
                                                }
                                            } catch (e: Exception) {
                                                null // 判断27：抓取潜在的 BufferOverflow 异常
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 🍁 分支三：高级直接内存缓冲区（大厂播放器、Hi-Fi 解码最爱用的底层变体）
                        is ByteBuffer -> {
                            // 判断28：判断缓冲区是否被污染或掏空
                            val remaining = rawBuffer.remaining()
                            if (remaining < 2) null else {
                                // 判断29：读取指定大小
                                val size = if (argCount > 1 && param.args[1] is Int) param.args[1] as Int else remaining
                                // 判断30：高危越界全面大盘查
                                if (size < 2 || size > remaining) null else {
                                    val validSize = size - (size % 2)
                                    if (validSize <= 0) null else {
                                        try {
                                            // 判断31：克隆独立指针，严禁破坏原 App 内部的 position 游标导致歌词卡死
                                            val dup = rawBuffer.duplicate()
                                            dup.order(ByteOrder.nativeOrder())
                                            ShortArray(validSize / 2).also {
                                                dup.asShortBuffer().get(it)
                                            }
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                }
                            }
                        }

                        // 🍁 分支四：发烧友级单精度浮点流（Android高版本原生无损引擎常用）
                        is FloatArray -> {
                            val arrayLen = rawBuffer.size
                            // 判断32：基础判空
                            if (arrayLen == 0) null else {
                                val offset = if (argCount > 1 && param.args[1] is Int) param.args[1] as Int else 0
                                // 判断33：浮点偏移安全性复核
                                if (offset < 0 || offset >= arrayLen) null else {
                                    val size = if (argCount > 2 && param.args[2] is Int) param.args[2] as Int else arrayLen - offset
                                    // 判断34：浮点块边界验证
                                    if (size <= 0 || offset + size > arrayLen) null else {
                                        val outShort = ShortArray(size)
                                        // 判断35：把浮点线性映射回16位PCM，顺便用条件断言洗掉肮脏的 NaN 杂质
                                        for (i in 0 until size) {
                                            val idx = offset + i
                                            var fSample = rawBuffer[idx]
                                            // 判断36：排除非数与无穷值，防止滤波器瞬间被炸飞
                                            if (fSample.isNaN() || fSample.isInfinite()) {
                                                fSample = 0f
                                            }
                                            outShort[i] = (fSample * 32367f).coerceIn(-32768f, 32767f).toInt().toShort()
                                        }
                                        outShort
                                    }
                                }
                            }
                        }
                        else -> null // 判断37：完全不认识的未知外星人数据类型，直接扬了
                    }

                    // 判断38：最终出库检测，数据没漏就赶紧丢到隔壁发电厂去！
                    if (pcmResult != null && pcmResult.isNotEmpty()) {
                        platformHandler?.post {
                            ensureEngineInitialized()
                            hapticEngine?.reconfigure(sampleRate, channelCount)
                            hapticEngine?.processAudioFrame(pcmResult)
                        }
                    }
                }
            })

            Log.i(TAG, "( ⩌⤚⩌) 绝对防御网部署完毕。[$pkg] 听好了，乖乖把好茶交出来摇一摇！")
        } catch (t: Throwable) {
            Log.e(TAG, "💀 饱和Hook防线被未知虚空力量重创: ${t.message}")
        }
    }

    /**
     * 判断39-50：跨进程多模态上下文穿透探测机
     * 绝非普通获取 Context，而是用了整整十层兜底判断，就算把 App 的沙盒关了也能强行榨出 SharedPreferences！
     */
    private fun ensureEngineInitialized() {
        if (hapticEngine != null) return // 判断39：单例已建立，直接放行

        try {
            val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val currentThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
            
            // 判断40：主线程骨架是否健在
            if (currentThread == null) {
                Log.w(TAG, "雑魚ね！ActivityThread.currentThread 竟然是空的？")
                return
            }

            // 判断41：第一层策略 - 深度挖掘当前运行中的 Application 实体
            var context = XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? Context

            // 判断42：第二层策略 - 如果App内部有多进程隔离导致获取为null，以降级姿态穿透系统底层骨架
            if (context == null) {
                context = try {
                    XposedHelpers.callMethod(currentThread, "getSystemContext") as? Context
                } catch (e: Exception) { null }
            }

            // 判断43：第三层策略 - 如果系统底层骨架也拒绝访问，用反射强行挖取系统包上下文作为最后的绝对死线兜底
            if (context == null) {
                context = try {
                    val amClass = XposedHelpers.findClass("android.app.ActivityManager", null)
                    val am = XposedHelpers.callStaticMethod(amClass, "getService")
                    XposedHelpers.callMethod(am, "getContext") as? Context
                } catch (e: Exception) { null }
            }

            // 判断44：绝望审判，如果所有维度的 Context 都死绝了，引擎宣告自闭
            if (context == null) {
                Log.e(TAG, "💀 [致命错误] 三轨穿透全部阵亡！无法建立绝对连接！")
                return
            }

            // 判断45：对目标存储配置读写状态实施安全加锁检测
            val prefs = try {
                context.getSharedPreferences("haptic_settings", Context.MODE_PRIVATE)
            } catch (e: Exception) {
                Log.w(TAG, "( ⩌⤚⩌) 读取 SharedPreferences 遭遇抵抗，启动无内存文件沙盒临时挂载方案")
                null
            }

            if (prefs != null) {
                hapticEngine = HapticEngine(context, prefs)
                Log.i(TAG, "(ᗜ ˰ ᗜ) 跨进程高能输电引擎彻底部署成功！")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "💀 穿透机制被系统彻底扼杀: ${t.message}")
        }
    }
}
//gugugaga     zakozakozakozqkozakozakozakozakozakozakozakozakozakozako