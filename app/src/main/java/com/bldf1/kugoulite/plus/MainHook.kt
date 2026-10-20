package com.bldf1.kugoulite.plus

import android.content.Context
import android.util.Log
import com.bldf1.kugoulite.plus.lyric.LyriconBridge
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Xposed 模块入口（libxposed API 102，Kotlin 实现）
 */
class MainHook : XposedModule() {

    @Volatile
    private var installed = false

    /** 当前进程名（onModuleLoaded 时确定，供「只该在某进程做的事」按名过滤） */
    @Volatile
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ModuleLog.setModule(this)
        processName = param.processName
        log(
            Log.INFO, TAG, "模块已加载, 进程: ${param.processName}" +
                ", framework: $frameworkName $frameworkVersion, api: $apiVersion"
        )
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) {
            // 作用域内的进程可能额外加载其它包（createPackageContext 等），
            // 若目标包尚未处理过则本进程无需继续监听，detach 停止回调
            if (!installed) {
                detach()
            }
            return
        }
        if (installed) return
        installed = true

        // 这是 libxposed 的 XposedInterface.Param#getDefaultClassLoader（框架自己传入的类加载器），
        // 与 java.lang 的高版本 API 无关；lint 把它误判为 NewApi，这里局部抑制。
        @Suppress("NewApi")
        val classLoader = param.defaultClassLoader

        ModuleLog.log("========== 模块已加载 ==========")
        ModuleLog.log("目标包名: ${param.packageName}")

        // 安装 OkHttp 凭证捕获
        NetCapture.install(this, classLoader)

        // GDT(优量汇)广告 SDK 自带网络层：广告请求直接给"成功空响应"（零闪现）
        GdtAdBlocker.install(this, classLoader)

        // 安装弹窗阻止器
        PopupBlocker.install(this, classLoader)

        // 安装自动点击器
        AutoClicker.install(this, classLoader)

        // UI净化引擎（去广告/隐藏会员类，关键词防御式 + 持续隐藏）
        UiCleaner.install(this, classLoader)
        installSplashReopenGuard(this, classLoader)

        // 🔬 临时诊断（R63）：观察宿主 feed 适配器是否还在插入新 item
        //（定位「发现页刷不到底」用；`FeedProbe.ENABLED` 置 false 即关闭）
        FeedProbe.install(this, classLoader)

        // 🔬 临时诊断（R64）：宿主卡片协议管线计数（判断"停在哪一环"）
        CardProbe.install(this, classLoader)

        // 防检测（签名一致性）
        IntegrityBypass.install(this)

        // 寄生模块设置入口（主界面长按底栏）
        SettingsEntry.install(this)

        // 拦截发现页顶栏"下拉进入频道"手势（配合去频道开关）
        ChannelGestureBlocker.install(this, classLoader)

        // 频道页「发帖引导条」源头拦截（「开启美好的一天」+ 去发布，5 秒后自行滑出）
        ChannelPostGuideBlocker.install(this, classLoader)

        // 词幕(Lyricon)歌词提供器 —— 只在 .support 进程真正安装（MediaSession 在那边）
        LyriconBridge.install(this, classLoader, processName)

        // Hook Application.onCreate
        hookApplicationOnCreate(classLoader)
    }

    private fun hookApplicationOnCreate(classLoader: ClassLoader) {
        try {
            val appClass = Class.forName("android.app.Application", false, classLoader)
            val onCreate = appClass.getDeclaredMethod("onCreate")
            hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val context = chain.thisObject as Context
                        ModuleLog.init(context)
                        ModuleLog.log("Application.onCreate 触发")
                        // R150：记录进程启动时刻，供「启动页重入拦截」判断冷启动/回前台
                        processStartAt = System.currentTimeMillis()
                        ModuleLog.log("Application类: ${context.javaClass.name}")
                        LyriconBridge.onAppCreate(context)
                        ClaimWorker.start(context, classLoader)
                    } catch (e: Exception) {
                        ModuleLog.log("启动失败: $e")
                    }
                    result
                }
            ModuleLog.log("Application.onCreate Hook 安装成功")
        } catch (e: Exception) {
            ModuleLog.log("Application.onCreate Hook 安装失败: $e")
        }
    }

    companion object {
        private const val TAG = "[KuGouLiteVip]"
        private const val TARGET_PACKAGE = "com.kugou.android.lite"
    }

    /**
     * R150：拦截「后台回前台时再次进入启动页」。
     *
     * 现象（用户需求）：从后台切回酷狗时会重新走一遍 SplashActivity（启动页广告位）。
     * 做法：hook SplashActivity.onCreate，按「距进程启动的时长」区分冷启动与回前台 ——
     *   进程刚起（<=3s）时的 SplashActivity 是真正的冷启动，放行；
     *   超过 3s 才出现的，说明是回前台重新拉起，直接 finish 掉。
     * 复用「去开屏广告」开关（KEY_UI_SPLASH）。
     */
    @Volatile
    private var processStartAt = 0L

    private fun installSplashReopenGuard(xposed: XposedModule, cl: ClassLoader) {
        // ⭐ 真正导致「后台回前台闪屏」的是 ForeSplashActivity（酷安帖实测：
        //    com.kugou.android.app.splash.foresplash.ForeSplashActivity，用 Blocker 禁用它就不闪了）。
        //    SplashActivity 是冷启动那个，两个都要处理。
        for (clsName in listOf(
            "com.kugou.android.app.splash.foresplash.ForeSplashActivity",
            "com.kugou.android.app.splash.SplashActivity"
        )) {
        try {
            val cls = Class.forName(clsName, false, cl)
            val m = cls.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
            xposed.hook(m).intercept { chain ->
                val ret = chain.proceed()
                try {
                    val act = chain.thisObject as? android.app.Activity
                    if (act != null) {
                        val elapsed = System.currentTimeMillis() - processStartAt
                        if (elapsed > 3000L && Settings.isFeature(act, Settings.KEY_UI_SPLASH)) {
                            ModuleLog.log("启动页重入拦截: finish (距启动 ${elapsed}ms)")
                            // 目标：无缝回到前台，不能黑屏也不能看到启动页。
                            // 做法：把内容换成纯色（挡住启动页那一帧），然后直接跳主界面
                            // MediaActivity（CLEAR_TOP|SINGLE_TOP 复用它，不重建）再 finish。
                            try {
                                // 启动图通常挂在 window background（theme 里的 splash drawable），
                                // 只换 setContentView 挡不住它 ⇒ 必须一并把窗口背景换掉。
                                act.window?.setBackgroundDrawable(
                                    android.graphics.drawable.ColorDrawable(0xFF121212.toInt())
                                )
                                val blank = android.view.View(act)
                                blank.setBackgroundColor(0xFF121212.toInt())
                                act.setContentView(blank)
                            } catch (_: Throwable) {
                            }
                            try {
                                val intent = android.content.Intent().apply {
                                    setClassName(act, "com.kugou.android.app.MediaActivity")
                                    addFlags(
                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    )
                                }
                                act.startActivity(intent)
                            } catch (t: Throwable) {
                                ModuleLog.log("跳主界面失败: $t")
                            }
                            act.finish()
                        }
                    }
                } catch (_: Throwable) {
                }
                ret
            }
            ModuleLog.log("启动页重入拦截 Hook 安装成功: $clsName")
        } catch (e: Throwable) {
            ModuleLog.log("启动页重入拦截 Hook 失败 $clsName: $e")
        }
        }
    }

}
