package com.bldf1.kugoulite.plus

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference

/**
 * 模块设置入口 + 悬浮设置面板（寄生在酷狗概念版主界面，模块 APP 本身无界面）
 *
 * 在主界面（MediaActivity）长按底部 tab 栏约 0.8 秒呼出/收起设置面板。
 * 面板直接加入 Activity 的 content View 树（遮罩 + **贴底弹层**），
 * 不创建任何新窗口，无需悬浮窗权限。
 *
 * ⚠️ Round 25：面板 UI 由 **Compose + Miuix** 改回**原生 View**
 * （[NativeSettingsPanel]）。Compose 版观感更好但让 APK 从 2.7MB 涨到 31MB，
 * 对无界面的 Xposed 模块不划算；改回原生后一并去掉了
 * 「手工挂 ViewTree*Owner / 双 LifecycleOwner」那一整套坑。
 *
 * ⚠️ Round 32：面板从**屏幕居中卡片**改成**从底部弹出的弹层**（BottomSheet 观感）：
 *   · 贴屏幕底、宽度=屏宽、**只圆上面两角**（`NativeSettingsPanel` 提供）
 *   · 入场：弹层从屏幕下方 `translationY` 滑到 0 + 遮罩淡入
 *   · 关闭：反向滑出后移除；也可**按住头部往下拖**关闭（拖动时遮罩同步变淡）
 *   · 弹层贴底 ⇒ 底部补系统导航条高度，避免按钮被手势条压住
 *   ⚠️ 关闭走动画后 `overlay` 会在移除前短暂残留 ⇒ 用 `panelClosing` 守卫防止
 *      「关闭按钮 + 遮罩」同时触发导致重复移除；`togglePanel` 再次长按时也靠它吞掉。
 *
 * ⚠️ Round 33：长按底栏呼出面板时**宿主也响应了点击**。根因是我们只 observe
 *   **不消费**事件：
 *     · 宿主底栏的长按阈值（~500ms）**短于**我们的 800ms ⇒ 宿主先吃到长按；
 *     · 触摸目标在 ACTION_DOWN 时就已锁定，面板盖在上面也拦不住 UP ⇒
 *       我们呼出面板后，宿主的 tab 视图照样 `performClick`。
 *   修法（见 [handleTouch] 一节）：
 *     ① DOWN 落在底栏区域 ⇒ 立刻把**命中链上所有 `isLongClickable` 的视图临时静音**
 *        （原值可还原），宿主的长按不再触发；
 *     ② 我们的 800ms 长按成立 ⇒ 本次手势剩余事件（MOVE/UP/CANCEL）**全部吞掉**，
 *        并把收尾事件**改写为 `ACTION_CANCEL`** 送宿主（清按下态，且 CANCEL 不触发 click）；
 *     ③ 普通点击 / 底栏内滑动：判定失败时**先归还静音再放行** ⇒ tab 点击、滑动都不受影响。
 *   ⚠️ 静音必须"有名有姓地归还"：每条分支（UP / CANCEL / MOVE 取消 / 超时兜底）都要归还，
 *      否则底栏会永久失去长按能力。
 */
object SettingsEntry {

    private const val MAIN_ACTIVITY = "com.kugou.android.app.MediaActivity"

    // 底部 tab 栏区域：屏幕高度底部 10%
    private const val BOTTOM_REGION_RATIO = 0.90f

    // 长按时长
    private const val LONG_PRESS_MS = 800L

    // 滑出判定：纵向移动超过该像素即取消
    private const val CANCEL_DELTA_PX = 60f

    const val PANEL_OVERLAY_TAG = "kugou_vip_settings_overlay"
    const val PANEL_SHEET_TAG = "kugou_vip_settings_sheet"

    // 遮罩色（弹层本体由面板自己提供圆角/底色）
    // ⚠️ Round 34：弹层改成液态玻璃后遮罩调轻（0x66 → 0x59）。玻璃录的是**遮罩之下**的
    // 宿主内容（遮罩是我们自己的 overlay，录进去会把面板也录进来 ⇒ 递归），
    // 遮罩越重，面板里外亮度差越大，透出来的背景色就越"跳"。
    private const val SCRIM_ALPHA = 0x59

    // 出入场动画时长
    private const val ANIM_IN_MS = 280L
    private const val ANIM_OUT_MS = 200L

    // 按住头部下拖超过弹层高度的这个比例 ⇒ 松手即关闭
    private const val DRAG_CLOSE_RATIO = 0.28f

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTrigger: Runnable? = null
    private var regionThresholdY = 0f
    private var downY = 0f

    // ---- Round 33：手势归属状态（全部只在主线程读写，dispatchTouchEvent 即在主线程）----

    /** DOWN 落在底栏区域、正在为「我们的长按」跟踪本次手势 */
    private var tracking = false

    /** 我们的 800ms 长按已成立（面板已呼出）⇒ 本次手势剩余事件不再交给宿主 */
    private var ourLongPressFired = false

    /** 需要给宿主补一个 ACTION_CANCEL（长按成立的那一帧起生效） */
    private var pendingCancel = false

    /** 本次手势是否已经给宿主补过 CANCEL（每个手势最多补一次） */
    private var cancelSent = false

    /** 被我们临时静音 `isLongClickable` 的宿主视图（手势结束必须归还） */
    private val mutedLongClickable = ArrayList<View>()

    /** 静音兜底定时器：事件流丢失（收不到 UP）时也要把长按能力还回去 */
    private var muteGuard: Runnable? = null

    /** 命中测试复用矩形，避免在热路径上反复分配 */
    private val hitRect = Rect()

    // 面板宿主（弱引用，避免持有已销毁的 Activity）
    private var panelHost: WeakReference<Activity>? = null

    // 已打开的面板（用于状态回写刷新）；关闭时必须置空，避免持有整棵视图树
    private var panel: WeakReference<NativeSettingsPanel>? = null

    /** 关闭动画进行中：期间忽略再次关闭/toggle 请求 */
    @Volatile
    private var panelClosing = false

    fun install(xposed: XposedModule) {
        try {
            // 只 hook MediaActivity 的 dispatchTouchEvent（不再 hook 全局
            // Activity）：所有 Activity 的每次触摸都进回调，类名字符串比较
            // 在主线程热路径上；收窄到目标类后粒度小一个量级。
            // MediaActivity 未加载时兜底 hook 全局入口
            val mainActivityCls = try {
                Class.forName(MAIN_ACTIVITY, false, Thread.currentThread().contextClassLoader)
            } catch (_: Throwable) {
                null
            }
            val dispatch = if (mainActivityCls != null) {
                Reflector.findMethod(mainActivityCls, "dispatchTouchEvent", MotionEvent::class.java)
            } else {
                null
            }
            if (dispatch == null) {
                installGlobalFallback(xposed)
                return
            }
            xposed.hook(dispatch)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // 只观察 + 仅在「设置长按成立」后吞掉本次手势的收尾事件
                    // （详见 handleTouch 的 Round 33 说明），其余一律放行原逻辑
                    if (handleTouch(chain)) return@intercept true
                    chain.proceed()
                }
            ModuleLog.log("模块设置入口 Hook 安装成功(主界面长按底栏)")
        } catch (e: Exception) {
            ModuleLog.log("模块设置入口 Hook 失败: $e")
        }
    }

    /**
     * 兜底：MediaActivity 类不可见时 hook 全局 Activity.dispatchTouchEvent，
     * 回调内按类名过滤（原行为）
     */
    private fun installGlobalFallback(xposed: XposedModule) {
        try {
            val dispatch = Reflector.findMethod(
                Activity::class.java, "dispatchTouchEvent", MotionEvent::class.java
            )
            if (dispatch == null) {
                ModuleLog.log("模块设置入口 Hook 失败: 未找到方法")
                return
            }
            xposed.hook(dispatch)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // 兜底链上所有 Activity 的触摸都进回调 ⇒ 先按类名收窄再处理
                    val activity = chain.thisObject as? Activity
                    if (activity == null || activity.javaClass.name != MAIN_ACTIVITY) {
                        return@intercept chain.proceed()
                    }
                    if (handleTouch(chain)) return@intercept true
                    chain.proceed()
                }
            ModuleLog.log("模块设置入口 Hook 安装成功(全局兜底)")
        } catch (e: Exception) {
            ModuleLog.log("模块设置入口 Hook 失败: $e")
        }
    }

    /**
     * 触摸事件统一处理。返回值 = 是否**吞掉**本事件（调用方据此决定跳过 `chain.proceed()`）。
     *
     * ⚠️ 这是 MediaActivity 上**每个触摸事件**都会走的最热路径（一次滑动上百个事件）。
     * 因此这里只在 ACTION_DOWN（手势起点）做重活（几何判定 + 命中测试 + 静音宿主长按），
     * MOVE/UP 只读内存里的状态位，**不做字符串比较、不查配置**。
     */
    private fun handleTouch(chain: XposedInterface.Chain): Boolean {
        var consumed = false
        try {
            val activity = chain.thisObject as? Activity
            val event = chain.getArg(0) as? MotionEvent
            if (activity != null && event != null) {
                consumed = when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> onGestureDown(activity, event)
                    MotionEvent.ACTION_MOVE -> onGestureMove(event)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onGestureEnd(event)
                    else -> false
                }
                // 长按成立后，把「本来要交给宿主的那一下」改写成 CANCEL：
                // 宿主视图收到 CANCEL 会清掉按下态，但**不会** performClick / performLongClick。
                if (consumed && pendingCancel) {
                    pendingCancel = false
                    cancelSent = true
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    try {
                        chain.proceed(arrayOf<Any?>(cancel))
                    } catch (_: Throwable) {
                        // 补 CANCEL 失败不影响主流程（我们仍吞掉原事件）
                    } finally {
                        cancel.recycle()
                    }
                }
            }
        } catch (_: Throwable) {
            // 静默：任何异常都不能影响宿主原有触摸分发
        }
        return consumed
    }

    // ===== 手势判定（Round 33）=====

    /** 手势起点：唯一做重活的地方。返回是否吞掉（DOWN 永远放行） */
    private fun onGestureDown(activity: Activity, event: MotionEvent): Boolean {
        // 新手势 ⇒ 清掉上一轮的残留状态并归还静音（正常流程上一步已归还，这里是兜底）
        releaseLongClickMute()
        tracking = false
        ourLongPressFired = false
        pendingCancel = false
        cancelSent = false

        val screenHeight = activity.resources.displayMetrics.heightPixels.toFloat()
        regionThresholdY = screenHeight * BOTTOM_REGION_RATIO
        if (event.y <= regionThresholdY) return false

        downY = event.y
        tracking = true

        // ① 静音宿主底栏的长按：宿主长按阈值(~500ms)短于我们的 800ms，
        //    不静音的话长按会**先**触发宿主自己的长按/点击。
        muteHostLongClick(activity, event.rawX, event.rawY)

        pendingTrigger?.let { mainHandler.removeCallbacks(it) }
        pendingTrigger = Runnable {
            pendingTrigger = null
            if (!tracking) return@Runnable
            // ② 长按成立：后续手势事件全部不交给宿主，并补一个 CANCEL 清按下态
            ourLongPressFired = true
            pendingCancel = true
            togglePanel(activity)
        }.also { mainHandler.postDelayed(it, LONG_PRESS_MS) }
        return false
    }

    /** 手势移动：只读状态位。返回是否吞掉 */
    private fun onGestureMove(event: MotionEvent): Boolean {
        if (ourLongPressFired) return true
        if (!tracking) return false
        val trigger = pendingTrigger
        if (trigger != null && (event.y < regionThresholdY ||
                    kotlin.math.abs(event.y - downY) > CANCEL_DELTA_PX)
        ) {
            // 判定为滑动/移出区域 ⇒ 不是长按，归还宿主长按能力并放行
            mainHandler.removeCallbacks(trigger)
            pendingTrigger = null
            tracking = false
            releaseLongClickMute()
        }
        return false
    }

    /** 手势收尾：返回是否吞掉 */
    private fun onGestureEnd(event: MotionEvent): Boolean {
        pendingTrigger?.let { mainHandler.removeCallbacks(it) }
        pendingTrigger = null
        val fired = ourLongPressFired
        tracking = false
        ourLongPressFired = false
        // 无论成败都先归还静音（归还后才放行 ⇒ 普通点击的 onClick 照常生效）
        releaseLongClickMute()
        if (!fired) return false
        // 长按已成立：本次收尾事件改写为 CANCEL 送宿主，自己吞掉
        if (!cancelSent) pendingCancel = true
        return true
    }

    /**
     * 把「DOWN 命中链上所有可长按的宿主视图」临时设成不可长按（原值可还原）。
     * 宿主 `CheckForLongPress` 会因 `PFLAG_LONG_CLICKABLE` 被清而放弃，
     * 从而不会抢在我们 800ms 之前响应长按。
     */
    private fun muteHostLongClick(activity: Activity, x: Float, y: Float) {
        try {
            val decor = activity.window?.decorView ?: return
            var v: View? = deepestHitAt(decor, x, y) ?: return
            var depth = 0
            while (v != null && depth++ < 24) {
                val cur = v
                if (cur !== decor && cur.isLongClickable) {
                    mutedLongClickable.add(cur)
                    cur.isLongClickable = false
                }
                val p = cur.parent
                v = if (p is View) p else null
            }
        } catch (_: Throwable) {
            // 命中测试失败 ⇒ 放弃静音（退化为仅靠①②的吞事件，仍能修掉"触发点击"）
        }
        // 诊断：长按是低频操作，每个手势最多一条，便于确认静音是否真的命中宿主视图
        if (mutedLongClickable.isNotEmpty()) {
            ModuleLog.log("底栏长按：已临时静音宿主长按 ${mutedLongClickable.size} 个视图")
        }
        // 兜底：万一收不到 UP（事件流丢失），也要把长按能力还回去
        val guard = Runnable { releaseLongClickMute() }
        muteGuard = guard
        mainHandler.postDelayed(guard, LONG_PRESS_MS + 1500L)
    }

    /** 归还所有被静音的宿主视图（幂等） */
    private fun releaseLongClickMute() {
        muteGuard?.let { mainHandler.removeCallbacks(it) }
        muteGuard = null
        if (mutedLongClickable.isEmpty()) return
        for (i in mutedLongClickable.indices) {
            try {
                mutedLongClickable[i].isLongClickable = true
            } catch (_: Throwable) {
            }
        }
        mutedLongClickable.clear()
    }

    /** 命中测试：返回屏幕坐标 (x,y) 处最深层的可见视图（含裁剪判定），找不到返回 null */
    private fun deepestHitAt(root: View, x: Float, y: Float): View? {
        val vg = root as? ViewGroup ?: return root
        for (i in vg.childCount - 1 downTo 0) {
            val c = vg.getChildAt(i) ?: continue
            if (c.visibility != View.VISIBLE) continue
            hitRect.setEmpty()
            if (!c.getGlobalVisibleRect(hitRect)) continue
            if (x < hitRect.left || x >= hitRect.right ||
                y < hitRect.top || y >= hitRect.bottom
            ) continue
            return deepestHitAt(c, x, y) ?: c
        }
        return root
    }

    // ===== 悬浮设置面板（原生 View，Round 32 起从底部弹出）=====

    private fun togglePanel(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        if (content == null) {
            ModuleLog.log("打开设置面板失败: content 不存在")
            return
        }
        val existing = content.findViewWithTag<View>(PANEL_OVERLAY_TAG)
        if (existing != null) {
            // 关闭动画期间 overlay 还没摘掉，此时重复长按应被吞掉（否则会动画到一半又被移除）
            if (!panelClosing) removePanel(activity)
            return
        }

        // 面板构建失败可以接受，宿主被带崩不可以。
        val overlay = try {
            buildOverlay(activity)
        } catch (t: Throwable) {
            ModuleLog.log("设置面板构建失败: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        try {
            content.addView(
                overlay,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        } catch (t: Throwable) {
            ModuleLog.log("设置面板挂载失败: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        panelHost = WeakReference(activity)
        ModuleLog.log(
            "设置面板已打开(底部弹层) 玻璃=${if (panel?.get()?.glassActive == true) "ok" else "off(实色底板)"}"
        )
    }

    private fun buildOverlay(activity: Activity): View {
        val ctx: Context = activity
        val dm = activity.resources.displayMetrics
        // ⚠️ ColorDrawable.setAlpha() 是「在基色 alpha 上做乘法调制」，不是直接覆盖：
        //    源码 useAlpha = (mBaseColor>>>24) * alpha >> 8。
        //    若基色给 Color.TRANSPARENT（alpha=0），则 useAlpha 恒为 0 —— 遮罩永远是全透明，
        //    draw() 里的 `if ((mUseColor>>>24) != 0)` 短路后干脆什么都不画。
        //    所以基色必须给不透明白/黑，再用 alpha=0 起步，后面的 setAlpha 才有效。
        val scrim = ColorDrawable(Color.BLACK).apply { alpha = 0 }
        val overlay = FrameLayout(activity).apply {
            tag = PANEL_OVERLAY_TAG
            background = scrim
            // 点击遮罩关闭
            setOnClickListener { removePanel(activity) }
        }

        val status = Settings.getStatusMessage(ctx) ?: "尚未运行"
        val panelView = NativeSettingsPanel.build(
            activity = activity,
            statusText = status,
            sections = buildSections(ctx),
            onToggle = { key, v -> applyToggle(ctx, key, v) },
            onReclaim = {
                Toast.makeText(activity, "已在后台重新领取", Toast.LENGTH_SHORT).show()
                removePanel(activity)
                // forceClaim 内含 SP 写 + 可能的酷狗 SP 全目录扫描（重 IO），移出主线程
                Thread { ClaimWorker.forceClaim(activity) }.start()
            },
            onClose = { removePanel(activity) },
            bottomInset = navBarBottomInset(activity),
        )
        panel = WeakReference(panelView)
        val sheet = panelView.view
        sheet.tag = PANEL_SHEET_TAG

        // 弹层贴屏幕底、宽度=屏宽（只圆上面两角由面板自己画）
        overlay.addView(
            sheet,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )
        attachDragToDismiss(activity, overlay, sheet, panelView.header, scrim)

        // ---- 入场：弹层从屏幕下方滑上来 + 遮罩淡入 ----
        // 先挪到屏幕外，保证首帧不会闪现在最终位置（translateY 是绘制属性，无需等测量）
        sheet.translationY = dm.heightPixels.toFloat()
        sheet.post {
            try {
                sheet.animate().translationY(0f)
                    .setDuration(ANIM_IN_MS)
                    .setInterpolator(DecelerateInterpolator(2f))
                    .start()
                ValueAnimator.ofInt(0, SCRIM_ALPHA).apply {
                    duration = ANIM_IN_MS
                    interpolator = DecelerateInterpolator(2f)
                    addUpdateListener { scrim.alpha = it.animatedValue as Int }
                    start()
                }
            } catch (_: Throwable) {
            }
        }
        return overlay
    }

    /** 底部系统导航条（手势条）高度：弹层贴屏幕底时补在内容下方 */
    private fun navBarBottomInset(activity: Activity): Int = try {
        val insets = activity.window?.decorView?.rootWindowInsets
        if (insets == null) 0
        else if (android.os.Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
    } catch (_: Throwable) {
        0
    }

    /**
     * 按住头部区往下拖关闭：拖动时弹层跟随手指、遮罩同步变淡；
     * 松手超过 [DRAG_CLOSE_RATIO] 阈值走关闭动画，否则回弹到原位。
     *
     * ⚠️ DOWN 必须返回 true 才能拿到后续 MOVE/UP —— 头部区没有点击行为，
     * 吞掉手势不会影响别的交互，同时顺带挡住「点到头部穿透到遮罩」。
     */
    private fun attachDragToDismiss(
        activity: Activity,
        overlay: View,
        sheet: View,
        header: View,
        scrim: ColorDrawable,
    ) {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
        var downRawY = 0f
        var dragging = false

        header.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY
                    dragging = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downRawY
                    if (!dragging && dy > slop) dragging = true
                    if (!dragging) return@setOnTouchListener true
                    val t = dy.coerceAtLeast(0f)
                    sheet.translationY = t
                    val h = sheet.height.takeIf { it > 0 } ?: 1
                    val left = (1f - (t / h).coerceIn(0f, 1f))
                    scrim.alpha = (SCRIM_ALPHA * left).toInt()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return@setOnTouchListener true
                    val h = sheet.height.takeIf { it > 0 } ?: 0
                    if (h > 0 && sheet.translationY > h * DRAG_CLOSE_RATIO) {
                        animateOut(activity, overlay, sheet, scrim)
                    } else {
                        // 回弹
                        sheet.animate().translationY(0f)
                            .setDuration(180)
                            .setInterpolator(DecelerateInterpolator(2f))
                            .start()
                        ValueAnimator.ofInt(scrim.alpha, SCRIM_ALPHA).apply {
                            duration = 180
                            addUpdateListener { scrim.alpha = it.animatedValue as Int }
                            start()
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    // ===== 分组开关构建（数据驱动；key 即 Settings 的配置键）=====
    private fun buildSections(ctx: Context): List<ToggleSection> {
        fun row(
            key: String,
            title: String,
            summary: String? = null,
            checked: Boolean = Settings.isFeature(ctx, key),
        ) = ToggleRow(key = key, title = title, summary = summary, checked = checked)

        return listOf(
            ToggleSection(
                "自动领取", listOf(
                    ToggleRow(
                        Settings.KEY_ENABLED, "自动领取 VIP",
                        "每天自动领「听歌领 VIP」，不用手动点", Settings.isEnabled(ctx)
                    ),
                    ToggleRow(
                        Settings.KEY_AUTO_UPGRADE, "自动升级概念版VIP",
                        "领到会员后自动升成概念版 VIP（等级与时长更高）",
                        Settings.isAutoUpgrade(ctx)
                    ),
                    row(
                        Settings.KEY_LISTEN_TASK, "自动听歌任务",
                        "代你完成每日听歌打卡，满足领取条件"
                    ),
                )
            ),
            // Round 52：界面净化细分——每控件一个开关，单一平铺列表（无分组）。
            // R52c：「拦截广告请求」从自动领取组挪来此处（同为广告净化类）
            ToggleSection(
                "界面净化", listOf(
                    ToggleRow(
                        Settings.KEY_BLOCK_AD_NET, "拦截广告请求",
                        "在网络层直接拦掉广告请求并返回空响应，覆盖全局与 GDT 广告 SDK",
                        Settings.isBlockAdNet(ctx)
                    ),
                    ToggleRow(
                        Settings.KEY_BLOCK_TASK_PROMO, "屏蔽任务/赚钱推广",
                        "拦掉「赚钱」页的任务与 VIP 到期提示接口：金币特效、「即将过期」提醒不再出现",
                        Settings.isBlockTaskPromo(ctx)
                    ),
                    row(Settings.KEY_UI_SPLASH, "去开屏广告", "跳过开屏广告，直接进首页"),
                    row(Settings.KEY_UI_BANNER, "去首页 Banner 广告", "隐藏发现页 MosaicView 广告轮播位"),
                    row(Settings.KEY_UI_DISCOVER_ADS, "去发现页广告轮播", "拦截 Discovery 系列广告视图出生"),
                    row(Settings.KEY_UI_DISCOVER_BANNER_SLOT, "去发现页 Banner 槽位", "隐藏发现页 feed 顶部的广告位槽"),
                    row(Settings.KEY_UI_TAOBAO_BAR, "去淘宝推广条", "隐藏「上淘宝更优惠」倒计时推广条"),
                    row(Settings.KEY_UI_TMALL_CARD, "去天猫活动卡", "隐藏「天猫双11」等活动大卡"),
                    row(Settings.KEY_UI_INVITE, "去邀请提示", "隐藏「邀请好友得会员」提示"),
                    row(Settings.KEY_UI_LAB, "去概念实验室", "隐藏「概念实验室」整块区块"),
                    row(Settings.KEY_UI_LOGIN_GUIDE, "去登录引导", "隐藏未登录时的登录引导条"),
                    row(Settings.KEY_UI_TAB_BADGE, "去TAB角标", "去掉底栏 tab 上的红点角标"),
                    row(Settings.KEY_UI_PLUS, "去右下角加号", "隐藏底部悬浮的创作「+」入口"),
                    row(Settings.KEY_UI_MEMBER_BAR, "去会员栏", "隐藏「开通会员 / 即将过期 / 自动续费」横幅"),
                    row(Settings.KEY_UI_MEMBER_WIDGET, "去会员控件", "隐藏「立即开通 / 领取会员」悬浮控件"),
                    row(Settings.KEY_UI_FREE_LISTEN_ENTRY, "去免费听入口", "隐藏发现页顶栏的「免费听」入口"),
                    row(Settings.KEY_UI_FREE_LISTEN_ICON, "去免费听图标", "隐藏发现页顶栏的免费听小图标"),
                    row(Settings.KEY_UI_FREE_LISTEN_BLOCK, "去免费听区块", "隐藏 feed 里的「免费听」推荐区块"),
                    row(Settings.KEY_UI_CHANNEL, "去频道推荐", "隐藏发现页的「频道推荐」区块"),
                    row(Settings.KEY_UI_PLAY_AD, "去播放页广告", "隐藏播放页广告视图（含视频广告）"),
                    row(Settings.KEY_UI_PLAY_BOOKMARK, "去播放页书签", "隐藏播放页顶部的书签栏"),
                    row(Settings.KEY_UI_WEEKLY_WELFARE, "去每周福利条", "隐藏播放页的「每周福利」条"),
                    row(Settings.KEY_UI_PLAY_PUBLISH_TIP, "去发布提示", "隐藏「使用此音乐发布」提示"),
                    row(
                        Settings.KEY_UI_CHANNEL_POST_GUIDE, "去频道发帖引导条",
                        "拦在源头不创建：频道页的「开启美好的一天 / 去发布」横幅（出现 5 秒后自行滑出）"
                    ),
                    row(Settings.KEY_UI_PLAY_DISCIPLINE, "去咱一起自律入口", "隐藏播放页歌词下方的「咱一起自律」频道胶囊"),
                    row(Settings.KEY_UI_SEARCH_AD, "去搜索推广", "隐藏搜索结果里的广告推广条"),
                    row(Settings.KEY_UI_SEARCH_BANNER, "去搜索推广Banner", "隐藏搜索页的推广 Banner"),
                    row(Settings.KEY_UI_SEARCH_CHANNEL, "去搜索页频道推荐", "隐藏搜索页下方的频道推荐区（推荐/周榜/次元… 及卡片列表）"),
                    row(Settings.KEY_UI_SEARCH_CLOSE, "去搜索推广关闭钮", "隐藏搜索推广自带的关闭按钮"),
                    row(Settings.KEY_UI_SEARCH_INDICATOR, "去搜索推广指示器", "隐藏搜索推广的轮播指示圆点"),
                    row(Settings.KEY_UI_SEARCH_VIP_BAR, "去搜索结果VIP条", "隐藏搜索结果上方的 VIP 条"),
                    row(Settings.KEY_UI_MINE_SIGN_BAR, "去我的页签到条", "隐藏我的页白卡内的签到/领会员推广行"),
                    row(Settings.KEY_UI_MINE_MEMBER_BADGE, "去我的页会员角标", "隐藏我的页头像旁的会员角标与图标"),
                    row(Settings.KEY_UI_VIP_EXPIRE, "去VIP过期胶囊", "隐藏 VIP 过期提示胶囊"),
                    row(Settings.KEY_UI_VIP_LOOK_BAR, "去VIP去看看残条", "隐藏「VIP去看看」残留条"),
                    row(Settings.KEY_UI_MINE_TAB_BAR, "去我的页分类栏", "隐藏我的页的分类 tab 栏"),
                    row(Settings.KEY_UI_MINE_EARN, "去我的页赚钱入口", "隐藏我的页的「赚钱」入口"),
                    row(Settings.KEY_UI_MINE_MEDAL, "去我的页等级勋章", "隐藏等级徽章与「勋章」胶囊"),
                    row(Settings.KEY_UI_MINE_SEARCH, "去我的页搜索图标", "隐藏我的页顶栏的搜索按钮"),
                    row(Settings.KEY_UI_MINE_BADGE, "去我的页顶栏红点", "隐藏信息中心与设置图标上的红点角标"),
                )
            ),
            ToggleSection(
                "底栏", listOf(
                    row(
                        Settings.KEY_UI_FLOATING_BAR, "悬浮底栏",
                        "把底栏收窄成贴底胶囊，图标与播放钮保持原生"
                    ),
                    row(
                        Settings.KEY_UI_LIQUID_GLASS, "液态玻璃底栏",
                        "胶囊内填充液态玻璃材质（实时折射背景，Android 13+）"
                    ),
                )
            ),
            ToggleSection(
                "歌词提供", listOf(
                    row(
                        Settings.KEY_LYRIC_PROVIDER, "提供词幕歌词",
                        "把酷狗本地 KRC 歌词（逐字 + 翻译）投给词幕，锁屏/状态栏显示歌词"
                    ),
                )
            ),
            ToggleSection(
                "防检测", listOf(
                    row(
                        Settings.KEY_BYPASS_SIGNATURE, "绕过签名校验",
                        "自包签名统一返回首见值，避免应用自校验失败"
                    ),
                    row(
                        Settings.KEY_BLOCK_UPDATE, "屏蔽更新检测",
                        "拦掉「发现新版本」弹窗与活动页"
                    ),
                )
            ),
        )
    }

    /** 开关回写：界面净化类走 setFeature（内部会 rescan/restore 即时生效） */
    private fun applyToggle(ctx: Context, key: String, value: Boolean) {
        when (key) {
            Settings.KEY_ENABLED -> Settings.setEnabled(ctx, value)
            Settings.KEY_AUTO_UPGRADE -> Settings.setAutoUpgrade(ctx, value)
            Settings.KEY_BLOCK_AD_NET -> {
                Settings.setFeature(ctx, Settings.KEY_BLOCK_AD_NET, value)
                NetCapture.setBlockAdNet(value)
                GdtAdBlocker.setEnabled(value)
            }
            Settings.KEY_BLOCK_TASK_PROMO -> {
                Settings.setFeature(ctx, Settings.KEY_BLOCK_TASK_PROMO, value)
                NetCapture.setBlockTaskPromo(value)
            }

            // 悬浮底栏：setFeature 的 ui_ 分支只做 rescan/restoreRule，
            // 而还原底栏不在规则体系里（它改的是宿主底栏的 LayoutParams），
            // 故关闭时必须显式调 restore()，否则要等 App 重启才恢复直角条。
            Settings.KEY_UI_FLOATING_BAR -> {
                Settings.setFeature(ctx, key, value)
                if (!value) FloatingTabBar.restore()
            }

            // 液态玻璃：只增删"填充层"，底栏几何不变 ⇒ 强制重放一次即可（幂等）
            Settings.KEY_UI_LIQUID_GLASS -> {
                Settings.setFeature(ctx, key, value)
                FloatingTabBar.forceReapply()
            }

            // 词幕歌词：提供器在 :support 进程读，走 commit() 同步写盘 + 直读 XML 才生效
            Settings.KEY_LYRIC_PROVIDER -> Settings.setLyricProvider(ctx, value)

            else -> Settings.setFeature(ctx, key, value)
        }
    }

    /** 关闭面板（带下滑出场动画；Activity 正在销毁时直接摘除） */
    fun removePanel(activity: Activity) {
        val content = try {
            activity.findViewById<ViewGroup>(android.R.id.content)
        } catch (_: Throwable) {
            null
        }
        val overlay = content?.findViewWithTag<View>(PANEL_OVERLAY_TAG)
        if (overlay == null) {
            panel = null
            panelHost = null
            return
        }
        // 出场动画进行中：忽略后续关闭请求，别把动画截断
        if (panelClosing) return
        // 宿主正在销毁 / 面板还没建完 ⇒ 别做动画，直接摘
        if (activity.isFinishing || activity.isDestroyed) {
            detachOverlay(content, overlay)
            return
        }
        val sheet = overlay.findViewWithTag<View>(PANEL_SHEET_TAG)
        if (sheet == null) {
            detachOverlay(content, overlay)
            return
        }
        val scrim = overlay.background as? ColorDrawable
        animateOut(activity, overlay, sheet, scrim)
    }

    /** 下滑出场动画；结束后摘除 overlay */
    private fun animateOut(
        activity: Activity,
        overlay: View,
        sheet: View,
        scrim: ColorDrawable?,
    ) {
        if (panelClosing) return
        panelClosing = true
        val fallback = (activity.resources?.displayMetrics?.heightPixels ?: 0).toFloat()
        val target = sheet.height.takeIf { it > 0 }?.toFloat() ?: fallback
        try {
            sheet.animate().translationY(target)
                .setDuration(ANIM_OUT_MS)
                .setInterpolator(DecelerateInterpolator(2f))
                .withEndAction {
                    panelClosing = false
                    val content = try {
                        activity.findViewById<ViewGroup>(android.R.id.content)
                    } catch (_: Throwable) {
                        null
                    }
                    detachOverlay(content, overlay)
                }
                .start()
            scrim?.let {
                ValueAnimator.ofInt(it.alpha, 0).apply {
                    duration = ANIM_OUT_MS
                    addUpdateListener { a -> it.alpha = a.animatedValue as Int }
                    start()
                }
            }
        } catch (_: Throwable) {
            panelClosing = false
            detachOverlay(
                try {
                    activity.findViewById<ViewGroup>(android.R.id.content)
                } catch (_: Throwable) {
                    null
                },
                overlay,
            )
        }
    }

    private fun detachOverlay(content: ViewGroup?, overlay: View) {
        panelClosing = false
        try {
            content?.removeView(overlay)
            ModuleLog.log("设置面板已关闭")
        } catch (_: Throwable) {
        } finally {
            panel = null
            panelHost = null
        }
    }

    /** 状态回写后刷新面板显示（面板未打开时静默） */
    fun onStatusUpdated() {
        mainHandler.post {
            try {
                val host = panelHost?.get() ?: return@post
                if (host.isFinishing || host.isDestroyed) return@post
                panel?.get()?.setStatus(Settings.getStatusMessage(host) ?: "尚未运行")
            } catch (_: Exception) {
            }
        }
    }
}
