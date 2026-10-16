package com.bldf1.kugoulite.plus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.bldf1.kugoulite.plus.glass.GlassConfig
import com.bldf1.kugoulite.plus.glass.GlassTarget
import com.bldf1.kugoulite.plus.glass.LiquidGlass

/**
 * 原生 View 版设置面板（Round 25：移除 Compose + Miuix）
 *
 * ## 为什么回退到原生 View
 *
 * Round 23~24 用 Jetpack Compose + Miuix 实现了同一个面板，观感确实更好，
 * 但代价是 **APK 从 2.7MB 涨到 31MB**（Compose runtime/ui + Kotlin 2.4 工具链），
 * 对一个「没有任何界面、寄生在宿主 Activity 里」的 Xposed 模块来说不划算 ——
 * 面板总共只有 21 个开关 + 两个按钮，原生 View 完全够用，而且：
 *   - 无需为 Compose 挂 `ViewTree*Owner`（那段反射 + 双 LifecycleOwner 的坑一并消失）
 *   - Kotlin 工具链回到 AGP 内置版本，构建配置大幅简化
 *
 * ## 面板结构（Round 32：从底部弹出的弹层；Round 34：弹层本体换成液态玻璃）
 * ```
 * 弹层 = LiquidGlass（宽度=屏宽，贴屏幕底，**只圆上面两角**；内部录下弹层背后的宿主内容
 *                     → AGSL 折射/色散/模糊，见 glass/ 包与 FloatingTabBar 的胶囊同源实现）
 *  └ 内容层（LinearLayout，透明底 —— 底色交给玻璃；玻璃不可用时退回实色底板）
 *      ├ 头部区（可整块按住往下拖关闭）…同上
 *      ├ 中部：ScrollView（固定 400dp 高）…分组白卡在玻璃模式下改为**半透明白**
 *      └ 底部：关闭 按钮（R52c：重新领取按钮已移除）
 * ```
 * ⚠️ **玻璃层必须就是弹层本身**（LiquidGlass 是 FrameLayout，内容直接当它子视图加）。
 *    不要另起一个 FrameLayout 装「玻璃 + 内容」两层：`FrameLayout` 对
 *    **单一** MATCH_PARENT 子视图**不会**做二次测量（源码里是 `if (count > 1)`），
 *    在 wrap_content 的父容器里玻璃会被测成 0×0（什么都不显示）。
 * ⚠️ **玻璃不可用必须能退回实色底板**：AGSL/RT shader 缺失时玻璃什么都不画，
 *    此时若底板也是透明的，就是「深色遮罩上写深色字」——彻底不可读。
 *    故用 [LiquidGlass.isActive] 决定底板留不留。见 [Palette.cardGlass]。
 * ⚠️ 弹层贴屏幕底 ⇒ **底部要补系统导航条高度**（`bottomInset`），否则内容/按钮会被手势条压住。
 * ⚠️ 只圆上两角靠 `GlassConfig.cornerRadii = [r, r, 0, 0]`（`Outline.setPath` 实现，
 *    `setRoundRect` 只吃单一半径做不到）。
 *
 * ## 两条沿用至今的规矩
 * ① **配色不散落字面量**：统一从 [Palette] 取，深浅色由宿主的 `uiMode` 决定
 *    （与 Compose 版的 `MiuixTheme.colors = if (isDark) dark else light` 等价）。
 * ② **面板底板不能用卡片白**：底板 `surface`(#F7F7F7) + 分组白卡，才看得出层次；
 *    底板也用白色会变成「一坨白」（Compose 版首版就是踩了这个）。
 */
internal class NativeSettingsPanel private constructor(
    val view: View,
    /** 头部区（把手 + 标题）：供外层挂「按住下拖关闭」手势 */
    val header: View,
    /** R41：「领取状态」挂在 showsStatus 行（启用自动领取）的 summary TextView 上 */
    private val statusSummary: TextView?,
    private val statusBaseSummary: String?,
    /** 弹层玻璃是否真的生效（false = 已退回实色底板）。仅用于日志/诊断 */
    val glassActive: Boolean,
) {

    /** 状态文本回写（模块后台领券后刷新面板显示） */
    fun setStatus(text: String) {
        val base = statusBaseSummary
        if (statusSummary == null || base == null) return
        statusSummary.text = "$base\n领取状态：$text"
    }

    companion object {

        /**
         * 中部滚动区最大高度（dp）。25 个开关项远超此值，固定高度即可，免测量回环。
         * ⚠️ R37 起每一项都带一行说明（行高 ~48dp → ~74dp），故由 400 提到 520，
         * 否则视口占比过小、要滑很久；520 + 头部/按钮/nav inset ≈ 780dp < 屏高 904dp。
         */
        private const val LIST_MAX_H_DP = 520f
        /** 弹层上圆角（dp） */
        private const val CARD_RADIUS_DP = 20f
        private const val GROUP_RADIUS_DP = 16f
        private const val PAD_H_DP = 20f
        private const val ROW_PAD_V_DP = 11f
        private const val ROW_PAD_H_DP = 16f
        /** 拖动把手尺寸（dp） */
        private const val HANDLE_W_DP = 36f
        private const val HANDLE_H_DP = 4f
        private const val BOTTOM_PAD_DP = 12f

        // ===== Round 34：液态玻璃参数（移植自 QmDeve/AndroidLiquidGlassView，单位 dp）=====
        /**
         * 折射带宽 / 位移：弹层比胶囊大得多，带宽要放大才看得出边缘透镜感；
         * 但**不能太大** —— 顶栏/侧边会把背后深色专辑封面卷成一条脏灰带，
         * 实测 16dp/-20dp 偏重、12dp/-16dp 刚好（边缘仍看得出压缩）。
         */
        private const val GLASS_REFRACTION_HEIGHT_DP = 12f
        private const val GLASS_REFRACTION_OFFSET_DP = -16f
        /**
         * 背景模糊 sigma：弹层面积大，糊得比胶囊（4dp）重得多才像磨砂、背景才不抢字。
         * 实测 14dp 时卡片缝隙里仍能认出一块橙色专辑封面（像"脏斑"），
         * 16dp 之后退成均匀色晕 ⇒ 这是本项目里"该糊就糊透"的那一档。
         */
        private const val GLASS_BLUR_DP = 16f
        private const val GLASS_DISPERSION = 0.30f
        private const val GLASS_CONTRAST = 0.06f
        private const val GLASS_WHITE_POINT = 0.14f
        private const val GLASS_CHROMA = 1.06f

        /**
         * 染色：面板**首先得能读字**，所以比胶囊（浅 0.10 / 深 0.22）厚得多。
         *
         * 分工：**面板本体（标题区 / 卡片之间的缝隙）保持中度染色**，露出玻璃的色晕与
         * 边缘折射；**分组卡片用接近实白**（[Palette.cardGlass]）兜住所有正文。
         * 两者配合，既有玻璃观感，又不牺牲 12sp 次色文字的可读性。
         *
         * 真机调参记录：0.55 时背后的专辑封面/歌名仍认得出，标题与次色文字对比度明显被
         * 拉低（「洗白」）；0.62~0.68 背景只剩柔和色晕，标题恢复清晰。
         */
        private const val GLASS_TINT_LIGHT = 0.72f
        private const val GLASS_TINT_DARK = 0.70f

        /** R38：开源仓库地址（面板「关于」块可点击跳转，与 module.prop 的 source 保持一致） */
        // ⚠️ 必须与 module.prop 的 source 保持一致（面板「关于」里展示/跳转的就是它）。
        //    此前是作者主页 https://github.com/bldf1 ⇒ 用户点「关于」里的链接跳到的是作者主页，
        //    而不是仓库（用户实锤模块设置界面的关于没有更新）。已改为仓库地址。
        private const val REPO_URL = "https://github.com/bldf1/KuGouLitePlus"

        /**
         * 背景重录间隔 ms。
         *
         * ⚠️ PreDraw 监听挂在**窗口级** VTO（同窗口共用），任何动画都会触发重录整棵内容树；
         * 面板重录一次比胶囊贵得多（约占满屏下半）。但面板打开时遮罩挡住了宿主、
         * 背后内容基本不动 ⇒ 33ms（≈30fps）在弹出动画里也看不出时序差，却省掉一半重录。
         */
        private const val GLASS_RECORD_INTERVAL_MS = 33L

        fun build(
            activity: Activity,
            statusText: String,
            sections: List<ToggleSection>,
            onToggle: (String, Boolean) -> Unit,
            onReclaim: () -> Unit,
            onClose: () -> Unit,
            /** 系统导航条高度（px）：弹层贴屏幕底时补在下方，避免内容被手势条压住 */
            bottomInset: Int = 0,
        ): NativeSettingsPanel {
            val ctx: Context = activity
            val p = Palette.of(activity)
            val dp = activity.resources.displayMetrics.density
            fun px(v: Float) = (v * dp).toInt()

            val r = px(CARD_RADIUS_DP).toFloat()

            // ---- Round 34：弹层本体 = 液态玻璃 ----
            // 玻璃要「录下自己背后那块内容」，所以要给它一个「内容根」（宿主主容器），
            // 与底栏胶囊用的是同一棵子树（glass/GlassTarget 统一定位）。
            val glass = LiquidGlass(ctx, buildGlassConfig(p, r, dp))
            val glassTarget = try {
                val decor = activity.window?.decorView
                if (decor == null) null else GlassTarget.locate(decor, mustNotBeAncestorOf = glass)
            } catch (_: Throwable) {
                null
            }
            if (glassTarget != null) glass.init(glassTarget)
            val glassOn = glass.isActive
            // ⚠️ 玻璃不可用（AGSL/RT shader 缺失 / 找不到内容根）⇒ 必须留实色底板，
            //    否则「深色遮罩 + 深色字」彻底不可读。见类注释。
            glass.background = if (glassOn) null else buildCardBackground(p, r)
            glass.isClickable = true // 避免点弹层穿透到遮罩把面板关掉

            // ---- 内容层（透明底，底色交给玻璃）----
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, px(BOTTOM_PAD_DP) + bottomInset)
            }

            // ---- 头部（含拖动把手；整块可按住下拖关闭） ----
            val header = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            header.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 1000f
                    setColor(p.handle)
                }
                layoutParams = LinearLayout.LayoutParams(px(HANDLE_W_DP), px(HANDLE_H_DP)).also {
                    it.gravity = Gravity.CENTER_HORIZONTAL
                    it.topMargin = px(10f)
                }
            })
            header.addView(TextView(ctx).apply {
                text = "酷狗概念版+"
                setTextColor(p.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(px(PAD_H_DP), px(12f), px(PAD_H_DP), 0)
            })
            // 模块总介绍（R44：带上功能规模；领取状态在「启用自动领取」行内）
            header.addView(TextView(ctx).apply {
                text = "改动即时生效、无需重启"
                setTextColor(p.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(px(PAD_H_DP), px(4f), px(PAD_H_DP), 0)
            })
            card.addView(header)

            // ---- 中部：分组开关（滚动） ----
            // showsStatus 行（启用自动领取）的 summary TextView 在行构建时回传到这里，
            // 之后 setStatus() 只刷新它，不再有头部独立状态行。
            var statusSummary: TextView? = null
            var statusBase: String? = null
            val onStatusSummary: (TextView, String) -> Unit = { tv, base ->
                statusSummary = tv
                statusBase = base
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(6f), 0, 0)
            }
            for (section in sections) {
                inner.addView(TextView(ctx).apply {
                    text = section.title
                    setTextColor(p.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(px(PAD_H_DP), px(10f), px(PAD_H_DP), px(6f))
                })
                inner.addView(buildGroup(ctx, p, section, onToggle, glassOn, onStatusSummary).also { group ->
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    lp.setMargins(px(16f), 0, px(16f), 0)
                    group.layoutParams = lp
                })
            }

            // R41：showsStatus 行构建完成后初始化一次「领取状态：…」
            val s = statusSummary
            val b = statusBase
            if (s != null && b != null) s.text = "$b\n领取状态：$statusText"

            // ---- R38：关于（模块介绍 + 开源地址，随列表一起滚动） ----
            inner.addView(TextView(ctx).apply {
                text = "关于"
                setTextColor(p.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(px(PAD_H_DP), px(10f), px(PAD_H_DP), px(6f))
            })
            inner.addView(buildAbout(ctx, p, glassOn).also { group ->
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.setMargins(px(16f), 0, px(16f), px(4f))
                group.layoutParams = lp
            })
            val scroll = ScrollView(ctx).apply {
                addView(inner)
                // 固定高度：内容 21 项必然溢出，无需在布局后再测量一次（会引发二次布局回环）
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, px(LIST_MAX_H_DP)
                )
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            card.addView(scroll)

            // ---- 底部按钮（R52c：重新领取按钮已按用户要求移除，只留关闭） ----
            card.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(px(PAD_H_DP), px(8f), px(PAD_H_DP), 0)
                addView(makeButton(ctx, p, "关闭", onClose))
            })

            // ---- 把内容层叠到玻璃之上 ----
            // ⚠️ 玻璃（LiquidGlass）本身就是弹层，内容直接当它的子视图加 ——
            //    不能再套一层 FrameLayout 装「玻璃 + 内容」，否则单一 MATCH_PARENT
            //    子视图不会二次测量，玻璃会变 0×0（见类注释）。
            glass.addView(
                card,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            return NativeSettingsPanel(glass, header, statusSummary, statusBase, glassOn)
        }

        /**
         * 弹层玻璃参数。
         *
         * 与底栏胶囊同源（`glass/GlassShader` 逐字移植自 QmDeve/AndroidLiquidGlassView），
         * 但参数按「大面积、要读字」重调：折射带宽/位移放大、模糊加重、染色加厚。
         */
        private fun buildGlassConfig(p: Palette, radiusPx: Float, density: Float): GlassConfig =
            GlassConfig().apply {
                configure(
                    GlassConfig.Overrides()
                        // 只圆上两角（底部与屏幕齐平）：着色器按象限取半径，底部给 0 即可
                        .cornerRadii(radiusPx, radiusPx, 0f, 0f)
                        .refractionHeight(GLASS_REFRACTION_HEIGHT_DP * density)
                        .refractionOffset(GLASS_REFRACTION_OFFSET_DP * density)
                        .blurRadius(GLASS_BLUR_DP * density)
                        .dispersion(GLASS_DISPERSION)
                        .contrast(GLASS_CONTRAST)
                        .whitePoint(GLASS_WHITE_POINT)
                        .chromaMultiplier(GLASS_CHROMA)
                        .tintAlpha(if (p.isDark) GLASS_TINT_DARK else GLASS_TINT_LIGHT)
                        .tintColor(
                            if (p.isDark) 0.09f else 1f,
                            if (p.isDark) 0.09f else 1f,
                            if (p.isDark) 0.11f else 1f,
                        )
                        .recordIntervalMs(GLASS_RECORD_INTERVAL_MS),
                )
            }

        /** 弹层实色底板（**只圆上面两角**；玻璃不可用时的退路） */
        private fun buildCardBackground(p: Palette, radiusPx: Float) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // 顺序：左上(x,y)、右上(x,y)、右下(x,y)、左下(x,y)
            cornerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
            setColor(p.surface)
        }

        private fun buildGroup(
            ctx: Context,
            p: Palette,
            section: ToggleSection,
            onToggle: (String, Boolean) -> Unit,
            glassOn: Boolean,
            onStatusSummary: (TextView, String) -> Unit,
        ): View = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.resources.displayMetrics.density * GROUP_RADIUS_DP
                // 玻璃模式下用半透明白卡：既压住玻璃的折射纹理保证可读，又不像实色那样
                // 把下面的玻璃完全盖死（浅色 #FFFFFF 50% / 深色 #2C2C2E 50%）。
                setColor(if (glassOn) p.cardGlass else p.card)
            }
            for (row in section.rows) {
                addView(
                    buildRow(
                        ctx, p, row, onToggle,
                        if (row.showsStatus) onStatusSummary else null,
                    )
                )
            }
        }

        /**
         * R38：关于块 —— 模块介绍 + 开源地址（点击跳浏览器）。
         * 卡片底色与分组白卡同一套（玻璃模式半透明 / 普通模式实色），保持视觉一致。
         */
        private fun buildAbout(ctx: Context, p: Palette, glassOn: Boolean): View {
            val dp = ctx.resources.displayMetrics.density
            fun px(v: Float) = (v * dp).toInt()
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp * GROUP_RADIUS_DP
                    setColor(if (glassOn) p.cardGlass else p.card)
                }
                setPadding(px(14f), px(12f), px(14f), px(14f))
                addView(TextView(ctx).apply {
                    text = "酷狗概念版+ · 开源 LSPosed 模块"
                    setTextColor(p.textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                })
                addView(TextView(ctx).apply {
                    text = "完全开源：功能更新、问题反馈与完整源码都在 GitHub 仓库，点击下方链接打开。"
                    setTextColor(p.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, px(5f), 0, 0)
                    setLineSpacing(3f * dp, 1f)
                })
                addView(TextView(ctx).apply {
                    text = REPO_URL
                    setTextColor(p.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, px(9f), 0, 0)
                    paint.flags = paint.flags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                        } catch (_: Throwable) {
                            // 无浏览器等极端情况：静默忽略，不影响面板
                        }
                    }
                })
                // R52g：开源合规——上游项目致谢（Apache-2.0 要求保留版权与许可声明，
                // 详见仓库 THIRD_PARTY_NOTICES.md）
                addView(TextView(ctx).apply {
                    text = ("上游与致谢：自动领取接口参考 KuGouMusicApi；" +
                        "歌词解密/解析移植自 Lyricon krckit；" +
                        "液态玻璃着色器移植自 QmDeve/AndroidLiquidGlassView（MIT）；" +
                        "基于 libxposed API 构建（均 Apache-2.0）。")
                    setTextColor(p.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setPadding(0, px(10f), 0, 0)
                    setLineSpacing(3f * dp, 1f)
                })
            }
        }

        private fun buildRow(
            ctx: Context,
            p: Palette,
            row: ToggleRow,
            onToggle: (String, Boolean) -> Unit,
            onStatusSummary: ((TextView, String) -> Unit)? = null,
        ): View {
            val dp = ctx.resources.displayMetrics.density
            fun px(v: Float) = (v * dp).toInt()

            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = row.title
                    setTextColor(p.textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                })
                if (!row.summary.isNullOrEmpty()) {
                    addView(TextView(ctx).apply {
                        text = row.summary
                        setTextColor(p.textSecondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setPadding(0, px(2f), 0, 0)
                        // R41：showsStatus 行的 summary 交给面板持有，setStatus() 只刷新它
                        if (row.showsStatus) onStatusSummary?.invoke(this, row.summary)
                    })
                }
            }

            val sw = Switch(ctx).apply {
                isChecked = row.checked
                // ⚠️ 坑：系统默认的 track drawable 自带 alpha，用 trackTintList 染出来的
                // 是「30% 透明度的淡绿」（实测像素 (194,238,205)，而 #34C759 应为 (52,199,89)），
                // 观感非常脏。必须整块替换成自己画的实色圆角矩形。
                trackDrawable = buildSwitchTrack(p)
                thumbTintList = ColorStateList.valueOf(p.switchThumb)
                // 整行可点：Switch 之外的空白也该响应（与 Compose 版 SwitchPreference 行为一致）
                setOnCheckedChangeListener { _, v -> onToggle(row.key, v) }
            }

            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(ROW_PAD_H_DP), px(ROW_PAD_V_DP), px(ROW_PAD_H_DP), px(ROW_PAD_V_DP))
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(sw)
                // 行内空白点击 → 翻转开关
                setOnClickListener { sw.isChecked = !sw.isChecked }
            }
        }

        private fun makeButton(
            ctx: Context, p: Palette, label: String, onClick: () -> Unit
        ): View {
            val tv = TextView(ctx).apply {
                text = label
                setTextColor(p.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(0, ctx.resources.displayMetrics.density.toInt() * 10, 0,
                    ctx.resources.displayMetrics.density.toInt() * 10)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = ctx.resources.displayMetrics.density * 12f
                    setColor(p.surfaceVariant)
                }
                setOnClickListener { onClick() }
            }
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            return tv
        }

        /** 开关底槽：自己画的实色圆角矩形（开=绿 / 关=灰），绕开系统 track 的自带 alpha */
        private fun buildSwitchTrack(p: Palette) = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), pill(p.switchOn))
            addState(intArrayOf(), pill(p.switchOff))
        }

        private fun pill(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // 充分大的圆角 → 无论被系统拉伸成多宽多高都是胶囊形
            cornerRadius = 1000f
            setColor(color)
        }
    }

    /**
     * 配色（深浅两套）。与 Compose 版 `MiuixTheme.colorScheme` 的角色等价：
     * 面板里**不得**出现裸的 `0xFF...`，一律从这里取。
     *
     * Round 46：强调色/底色改用**酷狗概念版主题色**，让面板与宿主视觉一体：
     * 品牌青蓝 `#2FD6FF`（真机取色：我的页「收藏/最近/本地」图标 47,214,255 /
     * 63,216,255）。开关选中色直接用品牌色（白 thumb 压在上面，与宿主按钮一致）；
     * `accent` 用于**文字/链接**，浅色下取加深一档的 `#14AEE0` 保证可读对比
     * （#2FD6FF 在白底上对比不足）；深色用提亮一档的 `#3FD8FF`。
     * 底色整体带一点青调（surface/cardGlass），深色为青灰调。
     */
    private class Palette(
        val isDark: Boolean,
        val surface: Int,
        val surfaceVariant: Int,
        val card: Int,
        /** 玻璃模式下的分组卡片色（半透明，让弹层玻璃透出来） */
        val cardGlass: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accent: Int,
        val switchOn: Int,
        val switchOff: Int,
        val switchThumb: Int,
        /** 拖动把手（底部弹层） */
        val handle: Int,
    ) {
        companion object {
            fun of(activity: Activity): Palette {
                val dark = (activity.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                return if (dark) Palette(
                    isDark = true,
                    surface = 0xFF15191C.toInt(),
                    surfaceVariant = 0xFF222A2E.toInt(),
                    card = 0xFF222A2E.toInt(),
                    cardGlass = 0xCC222A2E.toInt(),
                    textPrimary = 0xFFF5F5F7.toInt(),
                    textSecondary = 0xFF98989E.toInt(),
                    accent = 0xFF3FD8FF.toInt(),
                    switchOn = 0xFF2FD6FF.toInt(),
                    switchOff = 0xFF39454A.toInt(),
                    switchThumb = 0xFFFFFFFF.toInt(),
                    handle = 0xFF39454A.toInt(),
                ) else Palette(
                    isDark = false,
                    surface = 0xFFF5FBFD.toInt(),
                    surfaceVariant = 0xFFE6F4FA.toInt(),
                    card = 0xFFFFFFFF.toInt(),
                    cardGlass = 0xCCF4FBFE.toInt(),
                    textPrimary = 0xFF1C1C1E.toInt(),
                    textSecondary = 0xFF8A8A8E.toInt(),
                    accent = 0xFF14AEE0.toInt(),
                    switchOn = 0xFF2FD6FF.toInt(),
                    switchOff = 0xFFD8EAF2.toInt(),
                    switchThumb = 0xFFFFFFFF.toInt(),
                    handle = 0xFFCFE4EE.toInt(),
                )
            }
        }
    }
}

/** 单个开关项 */
internal data class ToggleRow(
    val key: String,
    val title: String,
    val summary: String?,
    val checked: Boolean,
    /** R41：该行的 summary 下面挂「领取状态」动态行（后台领券后经 setStatus 刷新） */
    val showsStatus: Boolean = false,
)

/** 一个分组：标题 + 开关行 */
internal data class ToggleSection(val title: String, val rows: List<ToggleRow>)
