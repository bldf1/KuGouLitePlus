package com.bldf1.kugoulite.plus

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.WeakHashMap

/**
 * 弹窗阻止器（libxposed 拦截器链模型）
 *
 * 核心思路：在弹窗显示前拦截，阻止其出现。
 * 同时 ClaimWorker 通过 API 独立完成领取，不依赖弹窗。
 *
 * 多层拦截防线：
 * 1. Dialog.setContentView — 检查内容，标记领取弹窗
 * 2. Dialog.show() — 阻止已标记弹窗；漏网弹窗强制关闭
 * 3. WindowManagerImpl.addView — 底层拦截，阻止任何领取浮层添加到窗口
 * 4. Activity/Instrumentation 启动链路 — 阻止活动页 Activity 启动
 * 5. PopupWindow — 阻止 PopupWindow 弹窗
 */
object PopupBlocker {

    // 强阻止关键词：命中说明是纯广告/推广弹窗，AutoClicker 点了也没用
    private val BLOCK_KEYWORDS = arrayOf(
        "签到", "立即领取", "去领取", "马上领取",
        "一键签到", "领取会员", "领取VIP", "领VIP",
        "听歌会员", "概念版VIP", "免费领取", "点击领取",
        "每日签到", "每日领取",
        "每日福利", "会员福利", "免费听歌", "立即开通",
        "去使用", "免费VIP", "赠送", "福利", "活动",
        "receive_vip", "listen_song", "充值", "续费",
        // R62：服务端召回的「每周限定好运！免费抱走VIP / 开启本周红包」弹窗。
        // ⚠️ 这套文案**不在 APK 里**（114MB/15 dex 全文搜零命中），是服务端下发的
        // 召回运营弹窗，文案可随时变 ⇒ 同时用 campaign 核心词（抱走）与短词兜底，
        // 不写死整句。待窗口类名诊断拿到后，收紧为基于类名的精确拦截。
        "抱走VIP", "免费抱走", "开启本周红包", "本周红包", "限定好运"
    )

    /**
     * 让行关键词：与 AutoClicker.CLICK_KEYWORDS 对齐。含这些词的弹窗
     * 不在此拦死（原逻辑"领取"一词会把 AutoClicker 要点击的领取弹窗
     * 直接拦死，两个组件目标冲突），放行给 AutoClicker 自动点击后关闭；
     * AutoClicker 未能处理时，Dialog.show 防线2 仍会强制关闭漏网弹窗。
     */
    private val YIELD_KEYWORDS = arrayOf(
        "领取", "签到", "立即领取", "去领取", "马上领取",
        "一键签到", "立即签到", "领取会员", "领取VIP", "领VIP",
        "点击领取", "免费领取"
    )

    // 活动 Activity 类名关键词
    // 注意：不要加入启动链路上的容器类（如 AdContainerActivity），
    // 酷狗开屏流程必须经过它，拦截会导致无法打开（已实测踩坑）
    private val ACTIVITY_CLASS_KEYWORDS = arrayOf(
        "Sign", "Vip", "Welfare", "Receive", "Recharge",
        "WebActivity", "H5Activity", "BrowserActivity", "AdActivity",
        // 全屏广告插屏/激励视频 Activity 精确黑名单
        "FeeV4VideoActivity", "RewardvideoPortraitADActivity",
        "RewardvideoLandscapeADActivity", "qq.e.tg.",
        "TTFullScreenVideoActivity", "TTRewardVideoActivity",
        "BackupAdActivity",
        "FeeInterceptWebActivity"
    )

    // 活动 URL 关键词
    private val ACTIVITY_URL_KEYWORDS = arrayOf(
        "getvips", "receive_vip", "signin", "sign_in",
        "welfare", "listen_song", "recharge"
    )

    // 更新弹窗关键词（block_update 开启时生效）
    private val UPDATE_KEYWORDS = arrayOf(
        "发现新版本", "立即更新", "版本更新", "更新提示", "新版本上线",
        "抢先体验", "内测版", "立即升级"
    )

    @Volatile
    private var installed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 标记已识别为领取弹窗的 Dialog（兼作判定缓存：命中即不再重复全树扫描）
    private val flaggedDialogs = WeakHashMap<Dialog, Boolean>()

    // ===== 临时诊断（R62）：定位「每周限定好运·免费抱走VIP」弹层的展示路径 =====
    // 打开后会在三条防线入口打印命中的类名与可见文本；定位完成即置 false 并删除。
    private const val DIAG_POPUP = true

    /** 展平视图树里的可见文本（诊断用，截断到 160 字符） */
    private fun flattenText(view: View?): String {
        val sb = StringBuilder()
        fun walk(v: View?) {
            if (v == null || sb.length > 200) return
            if (v is TextView) {
                val t = v.text?.toString()
                if (!t.isNullOrBlank()) sb.append(t).append('|')
            }
            if (!v.contentDescription.isNullOrBlank()) sb.append(v.contentDescription).append('|')
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return if (sb.length > 160) sb.substring(0, 160) + "…" else sb.toString()
    }

    fun install(xposed: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        installed = true

        hookDialogSetContentView(xposed)
        hookDialogShow(xposed)
        hookWindowManagerAddView(xposed, classLoader)
        hookActivityStartActivity(xposed)
        hookPopupWindow(xposed, classLoader)

        ModuleLog.log("PopupBlocker 安装完成")
    }

    /**
     * 弹窗是否应让行给 AutoClicker：内容含让行关键词、且**不含**任何
     * 强阻止关键词（强阻止词命中说明是纯广告/推广弹窗，仍然拦死）。
     */
    private fun shouldYieldToAutoClicker(dialog: Dialog): Boolean = try {
        val yield = containsKeyword(dialog, YIELD_KEYWORDS)
        val hardBlock = containsKeyword(dialog, BLOCK_KEYWORDS)
        yield && !hardBlock
    } catch (_: Exception) {
        false
    }

    // ===== 1. Hook Dialog.setContentView — 标记领取弹窗 =====

    private fun hookDialogSetContentView(xposed: XposedModule) {
        try {
            val flagHooker = XposedInterface.Hooker { chain ->
                try {
                    val dialog = chain.thisObject as? Dialog
                    if (dialog != null && containsClaimKeyword(dialog)
                        // 让行弹窗不标记：含 AutoClicker 可点击词且无强阻止词，
                        // 标记后会在 show 阶段被检查1 拦死（冲突已修复）
                        && !shouldYieldToAutoClicker(dialog)
                    ) {
                        flaggedDialogs[dialog] = true
                        ModuleLog.log("标记领取弹窗(setContentView)")
                    }
                } catch (_: Exception) {
                    // 静默
                }
                chain.proceed()
            }
            val overloads = arrayOf(
                Reflector.findMethod(Dialog::class.java, "setContentView", Int::class.javaPrimitiveType),
                Reflector.findMethod(Dialog::class.java, "setContentView", View::class.java),
                Reflector.findMethod(Dialog::class.java, "setContentView", View::class.java, ViewGroup.LayoutParams::class.java),
            )
            var count = 0
            for (m in overloads) {
                if (m == null) continue
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(flagHooker)
                count++
            }
            if (count > 0) {
                ModuleLog.log("Dialog.setContentView Hook 安装成功")
            } else {
                ModuleLog.log("Dialog.setContentView Hook 失败: 未找到任何重载")
            }
        } catch (e: Exception) {
            ModuleLog.log("Dialog.setContentView Hook 失败: $e")
        }
    }

    // ===== 2. Hook Dialog.show() — 阻止领取弹窗 =====

    private fun hookDialogShow(xposed: XposedModule) {
        try {
            val show = Reflector.findMethod(Dialog::class.java, "show")
            if (show == null) {
                ModuleLog.log("Dialog.show Hook(PopupBlocker) 失败: 未找到方法")
                return
            }
            xposed.hook(show)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val dialog = chain.thisObject as? Dialog
                        ?: return@intercept chain.proceed()

                    if (DIAG_POPUP) try {
                        ModuleLog.log("DIAG Dialog.show: ${dialog.javaClass.name} | ${flattenText(dialog.window?.decorView)}")
                    } catch (_: Throwable) {
                    }

                    // 检查1：是否已标记
                    try {
                        if (flaggedDialogs[dialog] == true) {
                            ModuleLog.log("阻止领取弹窗显示(标记)")
                            return@intercept null // 阻止显示
                        }
                        // 检查1.5：让行判定——含 AutoClicker 可点击的领取词
                        // 且无强阻止词时放行（点击后由 AutoClicker 关闭）
                        if (shouldYieldToAutoClicker(dialog)) {
                            ModuleLog.log("领取弹窗让行给AutoClicker(自动点击)")
                            return@intercept chain.proceed()
                        }
                        // 检查2：DecorView 是否含领取关键词
                        if (containsClaimKeyword(dialog)) {
                            flaggedDialogs[dialog] = true
                            ModuleLog.log("阻止领取弹窗显示(内容)")
                            return@intercept null // 阻止显示
                        }
                        // 检查3：更新检测弹窗（block_update 开启时）
                        if (Settings.isFeature(dialog.context, Settings.KEY_BLOCK_UPDATE)
                            && containsKeyword(dialog, UPDATE_KEYWORDS)
                        ) {
                            ModuleLog.log("阻止更新弹窗")
                            return@intercept null // 阻止显示
                        }
                    } catch (_: Exception) {
                        // 静默
                    }

                    // 防线2：如果 show() 已执行（未被阻止），强制关闭
                    // （让行弹窗除外——它们交给 AutoClicker 点击后关闭）
                    val result = chain.proceed()
                    try {
                        if (!shouldYieldToAutoClicker(dialog)
                            && containsClaimKeyword(dialog) && dialog.isShowing
                        ) {
                            ModuleLog.log("强制关闭漏网领取弹窗")
                            forceCloseDialog(dialog)
                        }
                    } catch (_: Exception) {
                        // 静默
                    }
                    result
                }
            ModuleLog.log("Dialog.show Hook(PopupBlocker) 安装成功")
        } catch (e: Exception) {
            ModuleLog.log("Dialog.show Hook(PopupBlocker) 失败: $e")
        }
    }

    /**
     * 强制关闭 Dialog（多种方式）
     */
    private fun forceCloseDialog(dialog: Dialog) {
        mainHandler.post {
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (_: Exception) {
            }
            try {
                if (dialog.isShowing) dialog.cancel()
            } catch (_: Exception) {
            }
            try {
                dialog.window?.decorView?.visibility = View.GONE
            } catch (_: Exception) {
            }
        }
    }

    // ===== 3. Hook WindowManagerImpl.addView — 底层拦截 =====

    private fun hookWindowManagerAddView(xposed: XposedModule, classLoader: ClassLoader) {
        try {
            val wmClass = Class.forName("android.view.WindowManagerImpl", false, classLoader)
            val addView = Reflector.findMethod(wmClass, "addView", View::class.java, ViewGroup.LayoutParams::class.java)
            if (addView == null) {
                ModuleLog.log("WindowManagerImpl.addView Hook 失败: 未找到方法")
                return
            }
            xposed.hook(addView)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val view = chain.getArg(0) as? View
                        if (view != null) {
                            if (DIAG_POPUP) try {
                                val lp = chain.getArg(1) as? WindowManager.LayoutParams
                                ModuleLog.log("DIAG addView: ${view.javaClass.name} ctx=${view.context?.javaClass?.name} ty=${lp?.type} | ${flattenText(view)}")
                            } catch (_: Throwable) {
                            }
                            if (containsClaimKeyword(view)) {
                                ModuleLog.log("底层拦截领取浮层(WindowManager)")
                                return@intercept null // 阻止添加到窗口
                            }
                            // Round 54：更新弹窗不走 Dialog.show 而走 addView 的场景
                            // （「新版抢先体验」内测弹窗真机实锤：Dialog.show 检查3
                            //   从未命中，说明宿主用自绘浮层直接 addView）
                            if (view.context != null &&
                                Settings.isFeature(view.context, Settings.KEY_BLOCK_UPDATE) &&
                                containsKeyword(view, UPDATE_KEYWORDS)
                            ) {
                                ModuleLog.log("底层拦截更新浮层(WindowManager)")
                                return@intercept null
                            }
                        }
                    } catch (_: Exception) {
                        // 静默
                    }
                    chain.proceed()
                }
            ModuleLog.log("WindowManagerImpl.addView Hook 安装成功")
        } catch (e: Exception) {
            ModuleLog.log("WindowManagerImpl.addView Hook 失败: $e")
        }
    }

    // ===== 4. Hook 启动链路 — 阻止活动页 =====

    /**
     * 统一判定：Intent 是否应被阻止（类名关键词 / data URL 关键词）
     */
    private fun shouldBlockIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        return try {
            val className = intent.component?.className
            if (className != null) {
                for (kw in ACTIVITY_CLASS_KEYWORDS) {
                    if (className.contains(kw)) {
                        ModuleLog.log("阻止活动Activity启动: $className")
                        return true
                    }
                }
            }
            intent.data?.let { data ->
                val url = data.toString().lowercase()
                for (kw in ACTIVITY_URL_KEYWORDS) {
                    if (url.contains(kw.lowercase())) {
                        ModuleLog.log("阻止活动URL跳转: $url")
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun hookActivityStartActivity(xposed: XposedModule) {
        var count = 0
        // Activity.startActivity(Intent) 与 (Intent, Bundle) 两个重载
        val overloads = arrayOf(
            Reflector.findMethod(Activity::class.java, "startActivity", Intent::class.java),
            Reflector.findMethod(Activity::class.java, "startActivity", Intent::class.java, android.os.Bundle::class.java),
        )
        val startActivityHooker = XposedInterface.Hooker { chain ->
            try {
                if (shouldBlockIntent(chain.getArg(0) as? Intent)) {
                    return@Hooker null // 阻止启动
                }
            } catch (_: Exception) {
                // 静默
            }
            chain.proceed()
        }
        for (m in overloads) {
            if (m == null) continue
            xposed.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(startActivityHooker)
            count++
        }

        // Instrumentation.execStartActivity：所有 Activity 启动的必经点
        //（覆盖 startActivities/startActivityForResult 及非 Activity 上下文
        //  转发路径）。intent 参数位动态定位
        try {
            val instrClass = Class.forName("android.app.Instrumentation", false, Activity::class.java.classLoader)
            for (m in instrClass.declaredMethods) {
                if (m.name != "execStartActivity") continue
                val params = m.parameterTypes
                val intentIdx = params.indexOfFirst { it == Intent::class.java }
                if (intentIdx < 0) continue
                val idx = intentIdx
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        try {
                            if (shouldBlockIntent(chain.getArg(idx) as? Intent)) {
                                return@intercept null
                            }
                        } catch (_: Exception) {
                            // 静默
                        }
                        chain.proceed()
                    }
                count++
                break
            }
        } catch (e: Exception) {
            ModuleLog.log("Instrumentation Hook 失败(降级): $e")
        }

        if (count > 0) {
            ModuleLog.log("Activity启动拦截 Hook 安装成功($count 个点)")
        } else {
            ModuleLog.log("Activity.startActivity Hook 失败: 未找到方法")
        }
    }

    // ===== 5. Hook PopupWindow — 阻止 PopupWindow 弹窗 =====

    private fun hookPopupWindow(xposed: XposedModule, classLoader: ClassLoader) {
        try {
            val pwClass = Class.forName("android.widget.PopupWindow", false, classLoader)
            val pwHooker = XposedInterface.Hooker { chain ->
                val blocked = try {
                    val pw = chain.thisObject as? android.widget.PopupWindow
                    val contentView = pw?.contentView
                    if (DIAG_POPUP) try {
                        ModuleLog.log("DIAG PopupWindow: ${pw?.javaClass?.name} cv=${contentView?.javaClass?.name} | ${flattenText(contentView)}")
                    } catch (_: Throwable) {
                    }
                    if (contentView != null && containsClaimKeyword(contentView)) {
                        true
                    } else {
                        // Round 54：更新类 PopupWindow（block_update 开启时）
                        contentView != null &&
                            Settings.isFeature(contentView.context, Settings.KEY_BLOCK_UPDATE) &&
                            containsKeyword(contentView, UPDATE_KEYWORDS)
                    }
                } catch (_: Exception) {
                    false
                }
                if (blocked) {
                    ModuleLog.log("阻止 PopupWindow 弹窗(领取/更新)")
                    null // 阻止显示
                } else {
                    chain.proceed()
                }
            }
            var count = 0
            // 全重载覆盖：showAtLocation 1 个 + showAsDropDown 3 个重载
            val pwMethods = listOf(
                Reflector.findMethod(pwClass, "showAtLocation", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                Reflector.findMethod(pwClass, "showAsDropDown", View::class.java),
                Reflector.findMethod(pwClass, "showAsDropDown", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                Reflector.findMethod(pwClass, "showAsDropDown", View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            )
            for (m in pwMethods) {
                if (m == null) continue
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(pwHooker)
                count++
            }
            if (count > 0) {
                ModuleLog.log("PopupWindow Hook 安装成功")
            } else {
                ModuleLog.log("PopupWindow Hook 失败: 未找到方法")
            }
        } catch (e: Exception) {
            ModuleLog.log("PopupWindow Hook 失败: $e")
        }
    }

    // ===== 工具方法 =====

    /**
     * 检查 Dialog 是否含领取关键词。
     * 结果缓存在 flaggedDialogs：Dialog.show 前后（检查2/防线2）与
     * addView 路径会重复全树遍历同一 dialog，命中即缓存，消除重复扫描。
     */
    private fun containsClaimKeyword(dialog: Dialog): Boolean {
        if (flaggedDialogs.containsKey(dialog)) return true
        val hit = containsKeyword(dialog, BLOCK_KEYWORDS)
        if (hit) flaggedDialogs[dialog] = true
        return hit
    }

    /**
     * 检查 Dialog 内容是否含指定关键词
     */
    private fun containsKeyword(dialog: Dialog, keywords: Array<String>): Boolean = try {
        dialog.window?.decorView?.let { containsKeyword(it, keywords) } ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * 递归遍历 View 树，检查是否含领取关键词
     */
    private fun containsClaimKeyword(view: View?): Boolean =
        containsKeyword(view, BLOCK_KEYWORDS)

    /**
     * 递归遍历 View 树，检查是否含指定关键词
     */
    private fun containsKeyword(view: View?, keywords: Array<String>): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false

        if (view is TextView) {
            view.text?.let { text ->
                if (keywords.any { text.contains(it) }) return true
            }
            view.hint?.let { hint ->
                if (keywords.any { hint.contains(it) }) return true
            }
        }

        view.contentDescription?.let { desc ->
            if (keywords.any { desc.contains(it) }) return true
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsKeyword(view.getChildAt(i), keywords)) return true
            }
        }

        return false
    }
}
