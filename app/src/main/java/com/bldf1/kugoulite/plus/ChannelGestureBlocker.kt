package com.bldf1.kugoulite.plus

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference

/**
 * "下拉进入频道"手势拦截 + 频道页创建拦截
 *
 * 酷狗概念版发现页支持下拉进入频道页（MediaActivity 内 fragment 切换，
 * 频道页 fragment = com.kugou.android.app.home.channel.ChannelMainFragment）。
 *
 * 两层拦截：
 * 1. 手势层：顶栏区域（<0.15H，无滚动内容）起手的下拉拖过 0.12H 阈值 → 消费，
 *    防止跟手预览切换频道（阈值低于酷狗切换点 ~400px）
 * 2. 创建层：floor.b.i() / ChannelMainFragment.onViewCreated —— 频道 fragment
 *    创建点直接吞掉/移除（jadx 实锤 floor.b.i() 是唯一创建点），发现页零影响
 */
object ChannelGestureBlocker {

    private const val MAIN_ACTIVITY = "com.kugou.android.app.MediaActivity"

    // 起点通道：顶栏区域（<0.15H，含状态栏+标题带）。
    // ⚠️ feed 内容区/扩展区不拦截：feed 在顶时向下拖 = 正常浏览下方内容
    // （canScrollVertically(-1)==false 只代表上方无内容），实测全屏拦截会
    // 误伤正常滚动（16:56 教训）。酷狗自身在中部下拉切频道的行为随它去。
    private const val START_Y_RATIO = 0.15f

    // 触发拦截的最小向下拖动距离。
    // 时序：刷新"释放刷新"≈250px → 酷狗进频道切换≈400px → 取 325px 居中掐断
    private const val TRIGGER_DY_RATIO = 0.12f

    /** 发现页 feed 滚动容器（UiCleaner 挂载捕获），供 feedAtTop 判定 */
    @Volatile
    var feedRef: WeakReference<View>? = null

    // 供 UiCleaner 延迟挂载用的引用
    @Volatile
    internal var xposed: XposedModule? = null
    @Volatile
    internal var mediaHookDone = false
    internal var currentActivityRef = java.lang.ref.WeakReference<Activity>(null)

    // 手势跟踪状态（主线程事件序列）
    private var tracking = false
    private var intercepting = false
    private var downY = 0f
    private var thresholdY = 0f
    private var pendingCancel = false

    fun install(xposed: XposedModule, classLoader: ClassLoader? = null) {
        // 延迟挂载：保存引用，MediaActivity 首次 resume 时由 UiCleaner 调 ensureMediaHook
        this.xposed = xposed
        ModuleLog.log("频道手势拦截: 待 MediaActivity 首次 resume 时挂载（延迟 hook）")
    }

    /**
     * 由 UiCleaner 的 Activity.onResume hook 在 MediaActivity 首次 resume 时调用：
     * 用运行时实际类挂载（install 时 classLoader 找到的类与运行时实例不同，
     * hook 上也不命中——真机实锤）。
     */
    internal fun ensureMediaHook(activity: Activity) {
        if (activity.javaClass.name != MAIN_ACTIVITY) return
        if (mediaHookDone) return
        val xp = xposed ?: return
        mediaHookDone = true

        // 1. 手势层：hook MediaActivity 自己的 dispatchTouchEvent（重写了基类）
        val dispatch = Reflector.findMethod(activity.javaClass, "dispatchTouchEvent", MotionEvent::class.java)
        if (dispatch != null) {
            try {
                xp.hook(dispatch)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(dispatchHooker)
                ModuleLog.log("频道手势拦截: 已挂载 dispatchTouchEvent handle=true")
            } catch (t: Throwable) {
                ModuleLog.log("频道手势拦截: dispatch hook 失败: $t")
            }
        }

        // ===== 频道模式入口拦截（终极方案，语义级）=====
        // jadx 实锤：DiscoveryBaseFragment$q.a() 是"进入频道模式"回调——
        // Ge(false) 隐藏音乐入口 + floor.b.c() 隐藏发现页 + J.GONE 隐藏内容容器。
        // 用户下拉拖过"释放刷新"后酷狗调 a() 进频道模式 = 用户看到的"进频道"。
        // 吞掉 a() → 频道模式永不激活：发现页保持可见，下拉刷新照常完成，
        // 频道 fragment 不创建（floor.b.i 不会被调），无白屏无振动。
        try {
            val qCls = Class.forName(
                "com.kugou.android.app.home.discovery.DiscoveryBaseFragment\$q",
                false, activity.javaClass.classLoader
            )
            val aMethod = qCls.declaredMethods.firstOrNull {
                it.name == "a" && it.parameterTypes.isEmpty()
            }
            if (aMethod != null) {
                xp.hook(aMethod)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { ic ->
                        val qInst = ic.thisObject
                        ModuleLog.log("UI净化[频道] 放行进频道(q.a)，300ms 后调 q.b() 恢复并回发现页")
                        // 放行 a()（酷狗正常进频道）——300ms 后：
                        // 1) 调 q.b()（退出频道模式回调：Ge(true)+J.VISIBLE+floor.b.b()
                        //    → t1(true) 恢复发现页内容/顶栏 tab 状态）
                        // 2) 注入"发现" tab 点击，把 pager 切回发现页
                        // 注：floor.b.b() 不再 hook（它是恢复路径，吞掉会导致
                        //    发现页/我的页内容容器一直隐藏 = 空白，真机实锤）
                        val handler3 = android.os.Handler(android.os.Looper.getMainLooper())
                        handler3.postDelayed({
                            try {
                                val bM = qCls.declaredMethods.firstOrNull {
                                    it.name == "b" && it.parameterTypes.isEmpty()
                                }
                                bM?.invoke(qInst)
                                ModuleLog.log("UI净化[频道] 已调 q.b() 恢复状态")
                                val act3 = UiCleaner.currentActivity?.get() ?: return@postDelayed
                                val decor = act3.window?.decorView ?: return@postDelayed
                                val downTime = android.os.SystemClock.uptimeMillis()
                                val evDown = MotionEvent.obtain(downTime, downTime,
                                    MotionEvent.ACTION_DOWN, 306f, 2515f, 0)
                                val evUp = MotionEvent.obtain(downTime, downTime + 80,
                                    MotionEvent.ACTION_UP, 306f, 2515f, 0)
                                decor.dispatchTouchEvent(evDown)
                                decor.dispatchTouchEvent(evUp)
                                evDown.recycle()
                                evUp.recycle()
                                ModuleLog.log("UI净化[频道] 已注入发现 tab 点击返回")
                            } catch (t: Throwable) {
                                ModuleLog.log("UI净化[频道] 恢复失败: $t")
                            }
                        }, 300)
                        ic.proceed()
                    }
                ModuleLog.log("频道手势拦截: q.a() 挂载成功（放行+自动恢复）")
            } else {
                ModuleLog.log("频道手势拦截: q.a() 未找到")
            }
        } catch (t: Throwable) {
            ModuleLog.log("频道手势拦截: q.a hook 失败: $t")
        }
        // ===== floor.b.i()：频道 fragment 创建点（唯一创建点，jadx 实锤）=====
        // ⚠️ 不再 hook floor.b.b()：它是**恢复路径**（q.b() → floor.b.b() →
        // t1(true) 显示发现页内容 + 顶栏 tab）。吞掉它会导致发现页/我的页
        // 内容容器一直隐藏（真机实锤：我的页一片空白 + 顶栏只剩"发现"）。
        // 只拦创建点 i()：频道 fragment 不创建 → 无频道内容，配合 q.b() 恢复。
        try {
            val floorCls2 = Class.forName(
                "com.kugou.android.app.home.discovery.floor.b",
                false, activity.javaClass.classLoader
            )
            val iMethod2 = floorCls2.declaredMethods.firstOrNull {
                it.name == "i" && it.parameterTypes.isEmpty()
            }
            if (iMethod2 != null) {
                xp.hook(iMethod2)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { ic ->
                        ModuleLog.log("UI净化[频道] 已拦截 ChannelMainFragment 创建(floor.b.i)")
                        null
                    }
                ModuleLog.log("频道手势拦截: floor.b.i() 挂载成功")
            } else {
                ModuleLog.log("频道手势拦截: floor.b.i() 未找到")
            }
        } catch (t: Throwable) {
            ModuleLog.log("频道手势拦截: floor.b hook 失败: $t")
        }
    }

    /**
     * 统一拦截体：观察手势，判定进频道时消费。
     *
     * ⚠️ 性能（本轮）：`dispatchTouchEvent` 是**每个触摸事件**都要走的最热 hook，
     * 一次滑动就有上百个事件。原实现对**每个事件**都做「class 名比较 +
     * Settings.isFeature（配置查表）」；现在只在 **ACTION_DOWN（手势起点）判定一次**，
     * MOVE/UP 仅读内存里的手势状态位，判定成本从 O(事件数) 降为 O(手势数)。
     */
    private val dispatchHooker = XposedInterface.Hooker { chain ->
        var consumed = false
        try {
            val event = chain.getArg(0) as? MotionEvent
            if (event != null) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> onGestureDown(chain.thisObject as? Activity, event)
                    MotionEvent.ACTION_MOVE -> onGestureMove(event)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        tracking = false
                        intercepting = false
                    }
                }
                consumed = intercepting
                if (consumed && pendingCancel) {
                    pendingCancel = false
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    try {
                        chain.proceed(arrayOf<Any?>(cancel))
                    } catch (_: Throwable) {
                    } finally {
                        cancel.recycle()
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (consumed) true else chain.proceed()
    }

    /** 手势起点：仅此处做 class/开关判定；不满足则整段手势不跟踪 */
    private fun onGestureDown(activity: Activity?, event: MotionEvent) {
        tracking = false
        intercepting = false
        pendingCancel = false
        if (activity == null) return
        if (activity.javaClass.name != MAIN_ACTIVITY) return
        if (!Settings.isFeature(activity, Settings.KEY_UI_CHANNEL)) return
        val h = activity.resources.displayMetrics.heightPixels.toFloat()
        downY = event.y
        thresholdY = h * TRIGGER_DY_RATIO
        // 每次新手势重置白屏恢复防抖（确保白屏后 safeScan 必定触发恢复）
        UiCleaner.resetBlankRecover()
        // 仅顶栏通道（<0.15H）：feed 内下拉 = 正常浏览，不拦
        tracking = event.y < h * START_Y_RATIO
    }

    /** 手势移动：只读状态位，无配置查表/字符串比较 */
    private fun onGestureMove(event: MotionEvent) {
        if (tracking && !intercepting && event.y - downY > thresholdY) {
            intercepting = true
            pendingCancel = true
            ModuleLog.log("UI净化[频道] 下拉进频道手势已拦截")
        }
    }

    /** feed 是否已滚动到顶部 */
    private fun feedAtTop(): Boolean {
        val v = feedRef?.get() ?: return false
        return try {
            v.isAttachedToWindow && !v.canScrollVertically(-1)
        } catch (_: Throwable) {
            false
        }
    }
}
