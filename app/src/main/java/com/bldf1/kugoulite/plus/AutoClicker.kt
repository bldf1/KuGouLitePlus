package com.bldf1.kugoulite.plus

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.HashSet

/**
 * 自动点击器（libxposed 拦截器链模型）
 *
 * 酷狗概念版启动时会弹出活动领取弹窗，需要用户手动点击"领取"按钮。
 * 本模块自动识别并点击弹窗中的领取按钮，实现全自动领取。
 *
 * 覆盖三种弹窗类型：
 * 1. 原生 Dialog / AlertDialog（含 MaterialDialog 等第三方库）
 * 2. WebView 加载的 H5 活动页面
 * 3. Activity 形式的活动页面
 */
object AutoClicker {

    // 自动点击关键词（匹配到则 performClick）
    private val CLICK_KEYWORDS = arrayOf(
        "领取", "签到", "立即领取", "去领取", "马上领取",
        "一键签到", "立即签到", "领取会员", "领取VIP", "领VIP",
        "立即领取", "点击领取", "免费领取"
    )

    // 活动 URL 关键词
    private val ACTIVITY_URL_KEYWORDS = arrayOf(
        "getvips", "receive_vip", "vip", "activity", "signin", "sign_in",
        "welfare", "listen_song", "recharge", "concept"
    )

    // 活动 Activity 类名关键词
    private val ACTIVITY_CLASS_KEYWORDS = arrayOf(
        "Sign", "Vip", "Welfare", "Receive", "Recharge", "WebActivity",
        "H5Activity", "BrowserActivity"
    )

    @Volatile
    private var installed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 防止重复点击：记录5秒内已点击的按钮文本
    private val clickedSet = HashSet<String>()
    private var lastCleanTime = 0L

    // WebView 注入去重：loadUrl(3路)+onPageFinished(2路) 对同一页面最多
    // 重复注入 5 次；按 url 记录已安排注入，命中后不再重复排任务
    private val injectedUrls = HashSet<String>()

    fun install(xposed: XposedModule, classLoader: ClassLoader) {
        if (installed) return
        installed = true

        hookDialogShow(xposed)
        hookWebView(xposed)
        hookActivityOnResume(xposed)
        hookWebViewClientOnPageFinished(xposed, classLoader)

        ModuleLog.log("AutoClicker 安装完成")
    }

    // ===== 1. Hook Dialog.show() — 自动点击原生弹窗 =====

    private fun hookDialogShow(xposed: XposedModule) {
        try {
            val show = findMethod(Dialog::class.java, "show")
            if (show == null) {
                ModuleLog.log("Dialog.show Hook(AutoClicker) 失败: 未找到方法")
                return
            }
            xposed.hook(show)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val dialog = chain.thisObject as? Dialog
                    if (dialog != null) {
                        // 延迟 500ms 等待布局加载完成
                        mainHandler.postDelayed({ tryAutoClickDialog(dialog) }, 500)
                        // 二次尝试，防止布局延迟加载
                        mainHandler.postDelayed({ tryAutoClickDialog(dialog) }, 1500)
                    }
                    result
                }
            ModuleLog.log("Dialog.show Hook(AutoClicker) 安装成功")
        } catch (e: Exception) {
            ModuleLog.log("Dialog.show Hook(AutoClicker) 失败: $e")
        }
    }

    /**
     * 自动点击 Dialog 中的领取按钮，点击后关闭弹窗
     */
    private fun tryAutoClickDialog(dialog: Dialog?) {
        try {
            if (dialog == null || !dialog.isShowing) return

            val decorView = dialog.window?.decorView ?: return

            val clickableViews = ArrayList<View>()
            findClickableViews(decorView, clickableViews)

            for (view in clickableViews) {
                if (view is TextView) {
                    val text = view.text ?: continue
                    val buttonText = text.toString().trim()

                    for (keyword in CLICK_KEYWORDS) {
                        if (buttonText.contains(keyword)) {
                            if (shouldClick(buttonText)) {
                                ModuleLog.log("自动点击弹窗按钮: $buttonText")
                                view.performClick()
                                // 点击后延迟关闭弹窗
                                mainHandler.postDelayed({
                                    try {
                                        if (dialog.isShowing) {
                                            dialog.dismiss()
                                            ModuleLog.log("弹窗已关闭(dismiss)")
                                        }
                                    } catch (e: Exception) {
                                        try { dialog.cancel() } catch (_: Exception) {}
                                    }
                                }, 500)
                            }
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 静默处理
        }
    }

    // ===== 2. Hook WebView — 注入JS自动点击H5活动页面 =====

    private fun hookWebView(xposed: XposedModule) {
        // Hook loadUrl(String)
        try {
            findMethod(WebView::class.java, "loadUrl", String::class.java)?.let { loadUrl ->
                xposed.hook(loadUrl)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val url = chain.getArg(0) as? String
                        val result = chain.proceed()
                        if (url != null && !url.startsWith("javascript:") && isActivityUrl(url)) {
                            val webView = chain.thisObject as? WebView
                            if (webView != null) scheduleInject(webView, url)
                        }
                        result
                    }
                ModuleLog.log("WebView.loadUrl Hook 安装成功")
            }
        } catch (e: Exception) {
            ModuleLog.log("WebView.loadUrl Hook 失败: $e")
        }

        // Hook loadUrl(String, Map) 重载
        try {
            findMethod(WebView::class.java, "loadUrl", String::class.java, java.util.Map::class.java)?.let { loadUrl2 ->
                xposed.hook(loadUrl2)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val url = chain.getArg(0) as? String
                        val result = chain.proceed()
                        if (url != null && !url.startsWith("javascript:") && isActivityUrl(url)) {
                            val webView = chain.thisObject as? WebView
                            if (webView != null) scheduleInject(webView, url)
                        }
                        result
                    }
            }
        } catch (_: Exception) {
            // 某些 Android 版本可能没有此重载
        }
    }

    /**
     * Hook WebViewClient.onPageFinished — 页面加载完成后注入JS
     */
    private fun hookWebViewClientOnPageFinished(xposed: XposedModule, classLoader: ClassLoader) {
        try {
            val clientClass = Class.forName("android.webkit.WebViewClient", false, classLoader)
            val onPageFinished = findMethod(clientClass, "onPageFinished", WebView::class.java, String::class.java)
            if (onPageFinished == null) {
                ModuleLog.log("WebViewClient.onPageFinished Hook 失败: 未找到方法")
                return
            }
            xposed.hook(onPageFinished)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val webView = chain.getArg(0) as? WebView
                        val url = chain.getArg(1) as? String
                        if (webView != null && url != null && isActivityUrl(url)) {
                            scheduleInject(webView, url)
                        }
                    } catch (_: Exception) {
                        // 静默
                    }
                    result
                }
            ModuleLog.log("WebViewClient.onPageFinished Hook 安装成功")
        } catch (e: Exception) {
            ModuleLog.log("WebViewClient.onPageFinished Hook 失败: $e")
        }
    }

    /**
     * 判断 URL 是否为活动页面
     */
    private fun isActivityUrl(url: String): Boolean =
        ACTIVITY_URL_KEYWORDS.any { url.contains(it, ignoreCase = true) }

    /**
     * 排注入任务（按 url 去重）：同一页面只安排一次 3 档延迟注入
     */
    private fun scheduleInject(webView: WebView, url: String) {
        synchronized(injectedUrls) {
            if (!injectedUrls.add(url.substringBefore('#'))) return
        }
        ModuleLog.log("检测到活动WebView: $url")
        webView.postDelayed({ injectClickJs(webView) }, 2000)
        webView.postDelayed({ injectClickJs(webView) }, 4000)
        webView.postDelayed({ injectClickJs(webView) }, 6000)
    }

    /**
     * 注入 JS 自动点击页面上的领取按钮
     */
    private fun injectClickJs(webView: WebView?) {
        try {
            if (webView == null) return
            val js = "javascript:(function(){" +
                "var kws=['领取','签到','立即领取','去领取','马上领取','一键签到','立即签到'," +
                "'领取会员','领取VIP','领VIP','点击领取','免费领取','确定','sign','receive','getVip'];" +
                "var sels='button,a,[class*=btn],[class*=submit],[class*=receive],[class*=sign]," +
                "[class*=get],[class*=vip],[role=button],input[type=button],input[type=submit],div[onclick]';" +
                "var els=document.querySelectorAll(sels);" +
                "for(var i=0;i<els.length;i++){" +
                "  var t=(els[i].innerText||els[i].textContent||els[i].value||'').trim();" +
                "  for(var j=0;j<kws.length;j++){" +
                "    if(t.indexOf(kws[j])>=0){" +
                "      els[i].click();" +
                "      return true;" +
                "    }" +
                "  }" +
                "}" +
                "})();"
            webView.loadUrl(js)
            ModuleLog.log("已注入自动点击JS")
        } catch (e: Exception) {
            ModuleLog.log("注入JS失败: $e")
        }
    }

    // ===== 3. Hook Activity.onResume() — 自动点击Activity中的领取按钮 =====

    private fun hookActivityOnResume(xposed: XposedModule) {
        try {
            val onResume = findMethod(Activity::class.java, "onResume")
            if (onResume == null) {
                ModuleLog.log("Activity.onResume Hook 失败: 未找到方法")
                return
            }
            xposed.hook(onResume)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val activity = chain.thisObject as? Activity
                        if (activity != null) {
                            val className = activity.javaClass.name

                            val isActivityPage = ACTIVITY_CLASS_KEYWORDS.any { className.contains(it) }

                            if (isActivityPage) {
                                ModuleLog.log("检测到活动Activity: $className")
                                val decorView = activity.window.decorView
                                mainHandler.postDelayed({
                                    val clicked = tryAutoClickView(decorView)
                                    if (clicked) {
                                        // 点击成功后延迟关闭 Activity
                                        mainHandler.postDelayed({
                                            try {
                                                activity.finish()
                                                ModuleLog.log("活动Activity已关闭")
                                            } catch (_: Exception) {
                                                // 静默
                                            }
                                        }, 800)
                                    }
                                }, 1000)
                                mainHandler.postDelayed({ tryAutoClickView(decorView) }, 2500)
                            }
                        }
                    } catch (_: Exception) {
                        // 静默
                    }
                    result
                }
            ModuleLog.log("Activity.onResume Hook 安装成功")
        } catch (e: Exception) {
            ModuleLog.log("Activity.onResume Hook 失败: $e")
        }
    }

    /**
     * 自动点击 View 树中的领取按钮
     * @return true 表示点击成功
     */
    private fun tryAutoClickView(rootView: View?): Boolean {
        try {
            if (rootView == null) return false
            val clickableViews = ArrayList<View>()
            findClickableViews(rootView, clickableViews)

            for (view in clickableViews) {
                if (view is TextView) {
                    val text = view.text ?: continue
                    val buttonText = text.toString().trim()

                    for (keyword in CLICK_KEYWORDS) {
                        if (buttonText.contains(keyword)) {
                            if (shouldClick(buttonText)) {
                                ModuleLog.log("自动点击按钮: $buttonText")
                                view.performClick()
                            }
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 静默处理
        }
        return false
    }

    // ===== 工具方法 =====

    private fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>?): Method? =
        Reflector.findMethod(clazz, name, *paramTypes)

    /**
     * 递归查找所有可点击的 View（Button 或可点击的 TextView）
     */
    private fun findClickableViews(view: View?, out: MutableList<View>) {
        if (view == null || view.visibility != View.VISIBLE) return

        if (view is Button || (view is TextView && view.isClickable)) {
            out.add(view)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findClickableViews(view.getChildAt(i), out)
            }
        }
    }

    /**
     * 检查是否应该点击（5秒内去重，防止重复点击）
     */
    private fun shouldClick(buttonText: String): Boolean {
        val now = System.currentTimeMillis()
        // 每30秒清理一次
        if (now - lastCleanTime > 30000) {
            clickedSet.clear()
            lastCleanTime = now
        }

        val key = buttonText + "_" + now / 5000 // 5秒窗口
        if (clickedSet.contains(key)) {
            return false
        }
        clickedSet.add(key)
        return true
    }
}
