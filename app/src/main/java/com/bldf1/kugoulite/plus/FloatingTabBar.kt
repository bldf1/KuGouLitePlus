package com.bldf1.kugoulite.plus

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.bldf1.kugoulite.plus.glass.GlassConfig
import com.bldf1.kugoulite.plus.glass.GlassTarget
import com.bldf1.kugoulite.plus.glass.LiquidGlass
import java.lang.ref.WeakReference

/**
 * 悬浮底栏（Round 24）
 *
 * ## 设计取向：**改造宿主自己的底栏**，而不是另建一套 tab
 *
 * 酷狗概念版的底栏是一棵原生视图树（层级见下），里面除了「发现/我的」两个 tab，
 * 还有中间的迷你播放圆钮（`fkb`：CircleProgressBar + KGImageView）、红点、毛玻璃
 * 背景（`fke`）。若全部隐藏再自绘一套 Compose 底栏，就要重新实现播放进度环、
 * tab 状态同步、红点、点击路由 —— 极易与宿主状态失步。
 *
 * 这里只改**尺寸与轮廓**，把「贴满屏宽的直角条」变成「贴底居中的圆角胶囊」。
 * 图标/文字/播放钮/点击行为**全部是宿主的原生实现**，一个都没碰 —— 这正是
 * 用户要的「原生图标」。
 *
 * ## 真机 dump 出来的层级（5.2.9 / 小米 22081212C 1220x2712 / density=3）
 * ```
 * FrameLayout{fb15e4d 0,2442-1220,2712}                 ← 底栏外层（270px），不动它
 *  └ ConstraintLayout{fv2 0,0-1220,270}                 ← ★ 底栏本体（胶囊对象）
 *     ├ FrameLayout{fkf 0,0-1220,270}                  ← 毛玻璃包装（★ 一起缩）
 *     │   └ HnVisualEffectLayout{fke 0,0-1220,270}     ← 荣耀模糊容器（★★ 见下）
 *     │       └ View{fkg}
 *     ├ Space{fk9 0,-46-1124,2} / View{fkh 0,-46-1124,2}  ← 顶部细缝，无关
 *     ├ FrameLayout{asx 0,0-1220,268}                  ← tab 容器（★ 一起缩）
 *     │   ├ RelativeLayout{asy} → asz(图标) / at0(「发现」)
 *     │   └ RelativeLayout{at1} → at2 / at3 / at6(红点)
 *     └ RelativeLayout{fkb 484,15-640,171}             ← 迷你播放钮 156x156
 * ```
 * 内容最低点：播放钮 y=171、tab 文字 y=157 ⇒ 本体高度取 **186px(62dp)**，
 * 上下各留 ~15px；原 270px 里多出的 84px 是给手势区留的白，胶囊化正好省掉。
 *
 * ## 九个必须记住的点（全是真机踩出来的）
 *
 * ① **锚点用 `at0`**：全树唯一，模块里 `autoRecoverBlankDiscover` 已在用它。
 *    沿 `at0 → asy → asx → fv2` 上溯三级拿本体，比新引入混淆 ID 更稳
 *    （每级校验 resourceName，不符即放弃）。
 *
 * ② **必须同时收缩子视图高度**：`asx`/`fkf` 是**定高**（268/270）的，
 *    ConstraintLayout 对「上下都约束 + 定高」的子视图会**垂直居中** ——
 *    只缩 `fv2` 会让它们溢出上下边被裁掉（实测：`asx` 跑到 `0,-82-1124,186`，
 *    播放钮顶部被切）。所以三者高度必须一起改成同一值。
 *
 * ③ **★★ 底色必须画在 `fkf` 内部的独立层上，不能只给 `fv2` 设 background**：
 *    给 `fv2` 设 `background` 后**实测完全不显示**（胶囊内部像素 = 页面底色
 *    243,249,252，与胶囊外逐像素一致，连页面那 1 个灰阶的渐变都对得上）。
 *    原因：`fkf` → `fke`（`com.hihonor.uikit.transparency.api.HnVisualEffectLayout`，
 *    荣耀 UI 套件的实时模糊容器）是**铺满整块胶囊**且 `V.FED..C`（可见+绘制+画子）的，
 *    它按设计就是「把底栏背后内容糊一层」，在不支持的机型上退化成**直接透出背后内容**
 *    —— 于是把 `fv2` 的画布整个盖掉。
 *    解法：在 `fkf` 里**最后**插入一层自己的视图（FrameLayout 子视图，MATCH_PARENT），
 *    它在 `fke` **之后**绘制 ⇒ 永远盖在模糊层之上。用 FrameLayout.LayoutParams
 *    也顺带避免了引 `androidx.constraintlayout` 依赖。
 *
 * ⑦ **填充层 = QmDeve 液态玻璃（Round 28，替换掉原来的实色 `GradientDrawable`）**：
 *    见 `glass/` 包（`LiquidGlass` / `LiquidGlassImpl` / `GlassConfig` / `GlassShader`，
 *    均自 QmDeve/AndroidLiquidGlassView 移植为 Kotlin，MIT）。
 *    · 玻璃**背后**取 `FragmentViewHome` 下的 `MainContainerLayout`（与底栏同级的兄弟子树）
 *      —— 取错成祖先会把玻璃自己录进去，无限递归。
 *    · 原库 `record()` 用 target 尺寸开录、host 尺寸摆位，本项目两者尺寸不同 ⇒
 *      必须改成 **host 尺寸**（`LiquidGlassImpl.record()`，已改）。
 *    · AGSL 源码**内嵌成 Kotlin 常量**，不走 `res/raw` —— 原库读的是
 *      `target.getResources()`，在 Xposed 宿主里那是**酷狗的资源表**，模块的资源 ID 不存在。
 *    · 开关 `Settings.KEY_UI_LIQUID_GLASS`（默认开）；关掉即清空填充层（胶囊透出背后内容）。
 *
 * ④ **只改 `fv2` 不改 `fb15e4d`**：外层容器尺寸可能被宿主用于内容区底部留白，
 *    动它有连带风险；改造内层完全够用。
 *
 * ⑤ **阴影靠 `fv2` 的 elevation**：`fv2` 的圆角 outline 由我们提供，
 *    elevation 阴影由**父容器** `fb15e4d`（1220x270）绘制，四周余量够，能正常铺开
 *    （实测胶囊下方有 2646→2692 的阴影渐变）。若把 elevation 放到 `fkf` 或填充层上，
 *    会被 `fv2` 的 `clipChildren` 裁掉，看不到阴影。
 *
 * ⑥ **自愈**：宿主的导航栏代码随时可能重排/换树（切 tab、主题切换、页面重建），
 *    `verify()` 每 4s 校验一次，**翻页钩子还会立刻调 `reapplyIfBroken()`**。
 *    ⚠️ Round 27 教训：校验**不能只看本体 `fv2` 的高度** —— 宿主翻页时会把
 *    `fkf`(→270) / `asx`(→146) 的定高打回原值，`fkb` 又锚在 `fkf` 上跟着
 *    上偏 82px，迷你播放钮被胶囊上缘裁平（用户报「渲染错误」），而 `fv2`
 *    高度始终是 186 ⇒ 旧校验完全无感。现在校验项 = 本体高度 + 填充层挂载 +
 *    **每个定高子视图的高度与父子关系**。
 *
 * ⑧ **必须关掉宿主自带的胶囊底色（Round 29）**：`fkf` 里除了我们插的填充层，还有
 *    宿主自己的一棵底色子树 `fke`(HnVisualEffectLayout) → `fkg`(View)。它画在玻璃
 *    **之下**，玻璃覆盖不到的地方（录到的内容 alpha=0）就透出这层白 ⇒ 胶囊看着仍是
 *    原来的实色栏 ——「把液态玻璃关掉后胶囊还是一条实心白药丸」就是它。
 *    处置：`fke`/`fkg` 置 INVISIBLE（同尺寸不影响布局）、`fv2`/`fkf`/`asx` 的 background
 *    清空；`geometryBroken()` 校验、`restore()` 还原。
 *
 * ⑨ **必须让内容流到胶囊背后（Round 30）**：宿主按「底栏占位高」(topMargin+height=204px)
 *    给内容列表留 `paddingBottom`，且 `clipToPadding=true` ⇒ 内容在 `2712-204=2508` 被
 *    硬裁（条目其实一直排到 2694）。胶囊在 2460–2646 正好跨过这条裁线：上半背后有内容、
 *    下半只剩背景 ⇒ 看着像被一条白色底栏横切（用户报「悬浮胶囊被白色底栏截断」）。
 *    处置：内容根里滚动容器的 `clipToPadding=false`（padding 照样占位、末尾条目仍能滚到
 *    胶囊上方，但内容不再被裁）。`geometryBroken()` 校验、`restore()` 还原。
 *
 * ⑩ ⭐ **自愈必须「事件驱动 + 同帧修复」（Round 31）**：宿主在**点击底栏 tab 时**（即使
 *    点的就是当前页，不发生翻页）也会重排底栏 —— 实测把 `fkf` 的定高打回 **270**，于是
 *    ConstraintLayout 把 `asx`（发现/我的 tab）**上移 40px**、锚在 `fkf` 上的播放钮
 *    **上移 82px**；而 `fv2` 本体高度始终是 186 ⇒ 看现象就是「3 个 tab 向上跳一下」。
 *    ⚠️ 旧实现只有两条恢复路径：① 翻页钩子（点**当前页** tab 不触发 `setCurrentItem`）
 *    ② `safeScan` 里 **4s 限流**的 `verify()` ⇒ 跳完要等好几秒才弹回来（用户报
 *    「点 tab 后 3 个 tab 向上移位，过一会又恢复」）。
 *    处置：挂**窗口级** `OnPreDrawListener`，在**绘制之前**跑一次极廉价的几何校验，
 *    坏了就立刻重新施加并 `return false` 取消本帧绘制 ⇒ 错位帧根本不落屏（零闪现）。
 *    ⚠️ 必须有**熔断**：连续修不好（例如子视图找不到了）时立刻摘掉监听，否则每帧都
 *    重新施加 + 取消绘制 = 界面冻结。
 *
 * ⑪ **玻璃关闭时必须有实色兜底底色（Round 45）**：关掉液态玻璃后走 ③ 的 else 分支，
 *    玻璃层被移除且**什么都不挂** —— 胶囊内部 100% 透明，页面内容（歌名、封面）直接
 *    透过胶囊，叠上 6dp elevation 阴影就成了「半透明影子悬空在页面上」（用户报
 *    「关闭液态玻璃后悬浮底栏渲染有问题」）。Round 28 之前是有实色 GradientDrawable
 *    兜底的，换玻璃时把它整层撤掉了，却忘了给「关玻璃」留后路。
 *    处置：玻璃挂不上（开关关 / 内容根缺失）时，在 `fkf` 里挂一层实色兜底视图
 *    （矩形 ColorDrawable 即可，`fv2` 的 clipToOutline 会把它裁成圆角）；颜色优先取
 *    宿主 `fkg` 的 ColorDrawable（深浅色由宿主主题适配），取不到按深浅色给默认值。
 *    `geometryBrokenFast()` 校验挂载与背景、`restore()` 移除、换玻璃时撤下。
 *
 * ⑫ **push 页盖顶时隐藏胶囊（Round 45，搜索页不显示底栏）**：搜索页
 *    （SearchMainFragment）是 push 进 `FragmentStackView{ajd}` 的，而底栏挂在 ajd 的
 *    **另一支**上 ⇒ 搜索页盖不住它，胶囊悬浮在搜索页上（用户报「搜索界面不要显示
 *    底栏」）。真机实锤（dumpsys）：push 页打开时宿主把 `FragmentViewHome` 置 GONE、
 *    栈顶换成非 home 的 FragmentView* 支（搜索 = FragmentViewNormalAnimationFirst，
 *    播放页 = FragmentViewPlayer3 且宿主自己把底栏容器置 GONE）。
 *    判定（O(1)，可进每帧 preDraw）：从 `fv2` 沿祖先链上溯找 `FragmentStackView`
 *    （⚠️ 不能数固定级数 —— 真机实锤宿主会把导航栏支 reparent 到
 *    FrameworkContentView 下，首版数 3 级整轮失效），数 ajd 下**可见**的
 *    FragmentView* 支 —— home 可见 = 主页；只有非 home 的 frag 支可见 = push 页
 *    盖顶 ⇒ 把 `fv2` 置 INVISIBLE（不动 GONE：INVISIBLE 不参与绘制连阴影一起
 *    消失，但保留布局，恢复时零 relayout 抖动）。隐藏/恢复各记一条日志。
 */
internal object FloatingTabBar {

    private const val TAG = "悬浮底栏"
    private const val HOST_PKG = "com.kugou.android.lite"

    // 胶囊几何（dp）
    private const val SIDE_MARGIN_DP = 16
    private const val CAPSULE_HEIGHT_DP = 62
    private const val TOP_MARGIN_DP = 6
    private const val ELEVATION_DP = 6

    /**
     * 逐帧自愈的熔断阈值（Round 31）：连续这么多次「重新施加后几何仍然不合规」就
     * 摘掉 preDraw 监听。没有它的话，一旦某次施加注定无法生效（子视图被宿主换掉等），
     * 就会每帧重新施加 + `return false` 取消绘制 ⇒ 屏幕永久不刷新（假死）。
     */
    private const val HEAL_MAX_STREAK = 5

    // ===== 液态玻璃参数（Round 28，单位 px，全部按 density 换算）=====
    /** 折射带宽（dp）：从胶囊边缘往里多宽的范围内发生折射 */
    private const val GLASS_REFRACTION_HEIGHT_DP = 8f
    /** 折射位移（dp）：负 = 向内采样（边缘像凸透镜），正值会往外拉出黑边 */
    private const val GLASS_REFRACTION_OFFSET_DP = -14f
    /** 背景模糊 sigma（dp）：让背后文字糊掉但仍看得出形状 */
    private const val GLASS_BLUR_DP = 4f
    /** 色散强度：0 = 无色散；过大边缘会出现明显彩虹边 */
    private const val GLASS_DISPERSION = 0.35f
    /** 对比度 / 白点 / 彩度：轻微提亮 + 提纯，避免胶囊看着"脏" */
    private const val GLASS_CONTRAST = 0.12f
    private const val GLASS_WHITE_POINT = 0.10f
    private const val GLASS_CHROMA = 1.15f
    /** 染色：浅色主题盖白、深色主题盖深灰 */
    private const val GLASS_TINT_LIGHT = 0.10f
    private const val GLASS_TINT_DARK = 0.22f

    /**
     * 背景重录最小间隔 ms（0 = 每次 PreDraw 都录）。
     *
     * ⚠️ 真机实测（Round 28，120Hz 屏）必须节流：PreDraw 监听挂在**窗口级**
     * ViewTreeObserver 上（同一窗口所有视图共用一个 VTO），所以**任何**动画都会
     * 触发我们重录整棵内容树 —— 包括底栏自己的迷你播放钮进度环（播放时一直在转）。
     * 实测 16 次往返切页：不节流 janky 8.06%/90 分位 12ms vs 关玻璃 2.45%/9ms；
     * 16ms 让重录降到 ≤60fps（背景本身是模糊+折射的，看不清 8ms 的时序差）。
     */
    private const val GLASS_RECORD_INTERVAL_MS = 16L

    // 锚点链：at0(发现文字) → asy(发现tab) → asx(tab容器) → fv2(底栏本体)
    private val CHAIN = arrayOf("at0", "asy", "asx", "fv2")

    /** 底栏本体 ID（= CHAIN 末级） */
    private const val BAR_ID = "fv2"

    /** 需要与本体同步收缩高度的子视图（定高，不缩会被居中溢出裁切） */
    private val SHRINK_IDS = arrayOf("asx", "fkf")

    /**
     * 宿主自带的胶囊底色层（Round 29）。
     *
     * `fkf` 里有一棵 `fke`(HnVisualEffectLayout，荣耀实时模糊容器) → `fkg`(实心 View)
     * 的底色子树，是**宿主自己**给底栏铺的底。它画在我们的玻璃**之下**：玻璃覆盖不到
     * 的地方（录到的内容 alpha=0）就透出这层白 ⇒ 胶囊看着仍像原来的实色栏
     * （用户报「底栏还在渲染」）。处置见 [applyTo] ②c。
     */
    private val HOST_FILL_IDS = arrayOf("fke", "fkg")

    /** 一并清空 background 的容器（可能自带浅色底，一起交给玻璃负责） */
    private val BG_CLEAR_IDS = arrayOf("fv2", "fkf", "asx")

    /** 内容根里滚动容器的类名片段（关掉它们的 clipToPadding，让内容流到胶囊背后） */
    private val SCROLL_CLASS_HINTS = arrayOf("RecyclerView", "ScrollView", "ListView", "ScrollLayout")

    /**
     * ⚠️ **既不 unclip、也不递归子树**的滚动容器（发现页主列表）。
     *
     * `DiscoveryVerticalKGRecyclerView`：宿主自定义沉浸列表。
     *  · **绝不能动它的 `clipToPadding`**：宿主拿这个标志当「顶栏淡化蒙罩」的开关，
     *    设成 false 后滚动时内容会裸顶到顶栏区且没有蒙罩，tab/图标叠在清晰内容上
     *    不可读（R54 起用户反复报的「顶栏背景透明」；R57 真机二分组 + 单点排除实锤）。
     *  · **也不能就这么放着**：它 paddingBottom=192 ⇒ 内容被 LayoutManager 收在
     *    2712-192=**2520** 处，而胶囊在 2460-2646 ⇒ 下半截背后没有内容可折射，
     *    玻璃把浅色底提亮成**纯白**（用户报「底栏背景没有隐藏」）。
     *  · 正解见 [CLEAR_LIST_PAD]：**只把 paddingBottom 收缩到 [LIST_PAD_BOTTOM_PX]（1px，
     *    不是 0）**（内容铺到屏幕底），完全不碰 clipToPadding（宿主的顶栏蒙罩逻辑不受影响）。
     *  · 子树不再递归：树内嵌套滚动控件多（HScrollFixRecyclerView 等，padding 多为 0），
     *    逐个 unclip 无收益且有连带风险。
     */
    private val UNCLIP_EXCLUDE_HINTS = arrayOf("DiscoveryVertical")

    /** 找滚动容器的 DFS 限深 */
    private const val CLIP_MAX_DEPTH = 30

    /**
     * 沉浸主列表底部内边距收到多小（px）。0 = 完全清零。
     *
     * 用途：让内容铺到胶囊背后（替代 R30 的 unclip）。
     *
     * ⚠️⚠️ **R58/R61 真机实锤：把它设成 0 会连带把「顶栏背景/淡化蒙层」一起弄没**
     *    （用户报「顶栏背景又没了」）——宿主/效果引擎疑似把 `getPaddingBottom()`
     *    当作「是否给底栏预留 = 普通模式」的判据；一旦为 0，发现页进入全屏沉浸模式，
     *    顶栏那层浅色渐变/蒙层不再绘制，列表内容裸顶到状态栏，tab 叠在清晰内容上。
     *    取证路径：`ui_floating_bar` 关掉 → 顶栏背景回来；`CLEAR_LIST_PAD=false`
     *    单点复现 → 顶栏背景回来（其余净化开关全开）。原生参考（45 项全关）顶栏
     *    背景正常，内容被干净裁在列表顶(273)之下。
     *
     * ⇒ 改成**只收到 [LIST_PAD_BOTTOM_PX]（1px）**：内容照样铺满胶囊背后
     *    （被限制在 [0, 2711]，视觉上与 0 等价），但 `paddingBottom != 0`，
     *    宿主的判据不被触发。
     *
     * ⚠️ 曾经的失败组合（2026-10-15）：清零 paddingBottom **且**把 clipToPadding 设
     *    false ⇒ 与宿主拉锯重排 ⇒ feed 完全不响应滑动、长按底栏也失效。
     *    只动 padding、**不碰 clipToPadding** 是前提。
     */
    private const val CLEAR_LIST_PAD = true

    /** 沉浸主列表 paddingBottom 收缩到的值（px）；见 [CLEAR_LIST_PAD] 注释 */
    private const val LIST_PAD_BOTTOM_PX = 1

    /**
     * 临时诊断开关：打印底栏子树各节点的 background/可见性。
     * Round 29 用它一击命中白底来源（`fkg` = ColorDrawable `#FFF3F9FC`）后已置 false；
     * 以后再出「某层没被替换掉」类问题，改回 true 跑一次冷启动即可取证。
     */
    private const val DIAG_BAR_FILL = false

    /** 胶囊填充层挂载点（毛玻璃包装 FrameLayout） */
    private const val FILL_HOST_ID = "fkf"

    // ===== Round 45：关玻璃实色兜底 + push 页隐藏 =====

    /** 兜底底色（fkg 颜色取不到时的退路）：浅色 = 宿主原底色，深色 = 深灰 */
    private val FALLBACK_COLOR_LIGHT = 0xFFF3F9FC.toInt()
    private val FALLBACK_COLOR_DARK = 0xFF202124.toInt()

    /** 宿主 fragment 容器支的类名前缀 / home 支类名（push 页判定，见文件头 ⑫） */
    private const val FRAG_BRANCH_PREFIX = "com.kugou.common.base.uiframe.FragmentView"
    private const val FRAG_BRANCH_HOME = "com.kugou.common.base.uiframe.FragmentViewHome"

    /** push 页（搜索页等）盖顶时胶囊被我们隐藏中 */
    @Volatile
    private var overlayHidden = false

    // ⚠️ Round 34：「内容根」的类名后缀 / DFS 限深已移到 glass/GlassTarget.kt，
    // 与设置面板共用一份（两边各写一份，改一处漏一处）。

    private val idCache = HashMap<String, Int>()

    /** Class → 是否滚动容器（isScrollContainer 记忆化） */
    private val scrollClassCache = HashMap<Class<*>, Boolean>()

    /** Class → 是否「沉浸主列表」（不递归其子树；见 UNCLIP_EXCLUDE_HINTS） */
    private val immersiveClassCache = HashMap<Class<*>, Boolean>()

    /**
     * `View.getClipToPadding()` / `setClipToPadding()` 在 SDK 桩里都是 `@hide`
     * （运行期是 public 方法），只能反射；按 Class 取一次缓存。
     */
    private val clipMethodCache =
        HashMap<Class<*>, Pair<java.lang.reflect.Method?, java.lang.reflect.Method?>>()

    /** 原始属性快照（用于关闭开关时还原） */
    private class Snapshot {
        var bar: View? = null
        var barLp: FrameLayout.LayoutParams? = null
        var barOutline: ViewOutlineProvider? = null
        var barClip: Boolean = false
        var barElevation: Float = 0f

        /** 我们插入的胶囊填充层（Round 28 起 = 液态玻璃层），以及它当时的宿主 */
        var fillHost: ViewGroup? = null
        var fill: View? = null

        /** 玻璃挂不上时的实色兜底层（Round 45，见文件头 ⑪）及其宿主 */
        var fallback: View? = null
        var fallbackHost: ViewGroup? = null

        /** 改造后本体的高度，供 verify() 判断是否被宿主重排 */
        var appliedHeight: Int = 0

        /** 被我们改过高度的子视图 → 原高度 */
        val shrink = ArrayList<Pair<View, Int>>()

        /** 被我们置为 INVISIBLE 的宿主底色层 → 原可见性（Round 29） */
        val hiddenFill = ArrayList<Pair<View, Int>>()

        /** 被我们清空 background 的容器 → 原 background（Round 29） */
        val clearedBg = ArrayList<Pair<View, Drawable?>>()

        /** 被我们关掉 clipToPadding 的滚动容器 → 原值（Round 30） */
        val unclipped = ArrayList<Pair<View, Boolean>>()

        /**
         * 被我们清掉底部内边距的沉浸主列表 → 原 paddingBottom（R58）。
         *
         * ⚠️ 只关 clipToPadding **解不开布局**：宿主给主列表的 paddingBottom(192) 是
         * 「给底栏预留的位置」，LayoutManager 会把内容排在 `高度-padding` 处收住
         * （实测内容硬停在 2520），所以裁线之下根本没内容可折射 —— 必须连同 padding
         * 一起清掉，内容才会真正铺到屏幕底、被胶囊折射到。
         */
        val paddedBottom = ArrayList<Pair<View, Int>>()

        /** 施加时的 Activity / decorView 弱引用（供自愈时重新施加，不持强引用） */
        var activityRef: WeakReference<Activity>? = null
        var decorRef: WeakReference<View>? = null

        /** 施加次数（首次必记日志，重复施加限流） */
        var applyCount: Int = 0
    }

    @Volatile
    private var applied = false

    private var snapshot: Snapshot? = null

    private var lastLogAt = 0L

    // ===== Round 31：事件驱动的逐帧自愈 =====

    /** 逐帧自愈监听挂载在哪个视图的 VTO 上（窗口级；挂在 decor 上）——弱引用防泄漏 Activity */
    private var healHostRef: WeakReference<View>? = null
    private val healHost: View? get() = healHostRef?.get()

    /** 正在重新施加时不要被自己的 requestLayout 再触发（防重入） */
    @Volatile
    private var healing = false

    /** 连续「修了还是坏」的次数 —— 超限即熔断，避免每帧取消绘制把界面冻住 */
    private var healStreak = 0

    /**
     * 窗口级 `OnPreDrawListener`：绘制前做一次**极廉价**的几何校验，坏了就在本帧修好
     * 并取消绘制。宿主对底栏的任何重排（点击 tab / 翻页 / 主题切换）都会经过这里，
     * 因此不再依赖 4s 轮询，也不会出现「上移几秒再弹回」。
     */
    private val healListener = ViewTreeObserver.OnPreDrawListener {
        var draw = true
        try {
            if (applied && !healing) {
                val snap = snapshot
                if (snap != null) {
                    // Round 45：push 页（搜索页等）盖顶时隐藏胶囊 —— 纯 O(1) 引用/可见性比较
                    updateOverlayHidden(snap)
                }
                if (snap != null && geometryBrokenFast(snap)) {
                    if (healStreak >= HEAL_MAX_STREAK) {
                        detachHealWatch()
                        log(always = true) {
                            "自愈熔断：连续 ${healStreak} 次重新施加仍不合规，停用逐帧监听（改由 4s 轮询兜底）"
                        }
                    } else {
                        healStreak++
                        healing = true
                        try {
                            reapplyIfBroken()
                        } finally {
                            healing = false
                        }
                        // 取消本帧绘制：错位帧不落屏，等重新布局后再画
                        draw = false
                    }
                } else {
                    healStreak = 0
                }
            }
        } catch (_: Throwable) {
        }
        draw
    }

    /** 把逐帧自愈挂到 decor 的窗口 VTO 上（幂等；换 decor 时自动迁移） */
    private fun attachHealWatch(decor: View) {
        if (healHost === decor) return
        detachHealWatch()
        try {
            val vto = decor.viewTreeObserver
            if (vto.isAlive) {
                vto.addOnPreDrawListener(healListener)
                healHostRef = WeakReference(decor)
                healStreak = 0
            }
        } catch (_: Throwable) {
        }
    }

    private fun detachHealWatch() {
        val host = healHostRef?.get()
        healHostRef = null
        if (host == null) return
        try {
            val vto = host.viewTreeObserver
            if (vto.isAlive) vto.removeOnPreDrawListener(healListener)
        } catch (_: Throwable) {
        }
    }

    fun isApplied(): Boolean = applied

    /**
     * 定位并改造底栏（幂等；找不到就静默返回 false，下次扫描再试）
     */
    fun ensure(activity: Activity, decor: View): Boolean {
        val prev = snapshot?.bar
        if (prev != null && prev.parent != null) {
            if (!applied) applyTo(prev, decor, activity)
            return applied
        }
        val bar = locate(activity, decor) ?: return false
        applyTo(bar, decor, activity)
        return applied
    }

    /**
     * 自愈校验（4s 一次）：
     * ① 本体的高度是否仍是我们要的值（宿主 relayout 可能把定高改回去）
     * ② 填充层是否还挂在原来的毛玻璃包装上（宿主换树/重建导航栏会丢）
     * ③ ⭐ **定高子视图（asx/fkf）的高度是否还是我们设的值** —— 真机实锤
     *    （Round 27）：宿主在**翻页时会重设这些子视图的 LayoutParams**（fkf
     *    打回 270、asx 打成 146），ConstraintLayout 随即将它们垂直居中到
     *    负偏移；而 `fkb`（迷你播放钮）是锚在 `fkf` 上的，会跟着一起跑偏
     *    -82px ⇒ 播放钮顶部被胶囊上缘裁平，只露出圆的下 2/3（用户报
     *    「悬浮底栏渲染错误」）。仅查本体高度发现不了这种子视图回退。
     * 任一条不满足 ⇒ 直接重新施加（引用失效则退回 applied=false 走 ensure）
     */
    fun verify() {
        val snap = snapshot
        val bar = snap?.bar
        if (snap == null || bar == null) {
            applied = false
            return
        }
        // 整棵子树被换掉（切页/重建导航栏）⇒ 清理旧痕迹后交给扫描循环重新定位。
        // ⚠️ Round 28：只有"确实挂过填充层、但它掉链了"才算换树。玻璃开关关闭 / 内容根
        // 找不到时 fill 与 fillHost 都为空，若一并判成换树 ⇒ verify() 每 4s 还原一次、
        // 扫描循环再 apply 一次，日志出现「还原→改造」无限拉锯（底栏每 4s 抖一次）。
        // Round 45：实色兜底层（关玻璃时）同样纳入换树判定。
        if (bar.parent == null ||
            (snap.fill != null && snap.fillHost?.parent == null) ||
            (snap.fallback != null && snap.fallbackHost?.parent == null)
        ) {
            restore()
            return
        }
        // Round 31：首次施加时 decor 的 VTO 可能还不可用（或后来换过 decor）⇒ 这里补挂
        val decor = snap.decorRef?.get()
        if (decor != null) attachHealWatch(decor)
        // Round 45：preDraw 被熔断时，push 页的隐藏/恢复由 4s 轮询兜底
        updateOverlayHidden(snap)
        if (geometryBroken(snap)) {
            reapply(snap, bar)
            return
        }
    }

    /** 几何是否被宿主改回原样（本体高度 / 填充层挂载 / 定高子视图高度 / 宿主底色层 / 内容裁切） */
    private fun geometryBroken(snap: Snapshot): Boolean {
        if (geometryBrokenFast(snap)) return true
        // Round 30：内容裁切（clipToPadding）被宿主打开 ⇒ 裁线又出现，立即重新施加。
        // ⚠️ 首次收集前 snap.unclipped 为空：前若干次返回 true 逼扫描循环尽快再试一次
        //   （首次施加时列表可能还没出来/还没测量），限次避免空转。
        // ⚠️ 这一段含反射调用（每项一次 getClipToPadding），**不放进 preDraw 的逐帧路径**，
        //   只由 4s 一次的 verify() 承担 —— 宿主不会去动 clipToPadding，这个频率足够。
        if (snap.unclipped.isEmpty() && snap.applyCount <= 20) return true
        for ((v, _) in snap.unclipped) if (clipToPaddingOf(v)) return true
        // R58：沉浸主列表的 paddingBottom 被宿主塞回来 ⇒ 内容又会被拦在胶囊上方
        // ⚠️ 仅在校验 CLEAR_LIST_PAD 时才有意义；关掉它却保留这条 ⇒ 每 4s 无限重新施加
        //   （宿主反正会设回来），正是「拉锯打断输入」的成因。
        if (CLEAR_LIST_PAD) {
            for ((v, _) in snap.paddedBottom) if (v.paddingBottom > LIST_PAD_BOTTOM_PX) return true
        }
        return false
    }

    /**
     * 廉价版几何校验（Round 31，供每帧的 preDraw 用）：只做 O(1) 的整数与引用比较，
     * 不触碰反射、不遍历内容树。正常帧的开销约十次比较。
     */
    private fun geometryBrokenFast(snap: Snapshot): Boolean {
        val bar = snap.bar ?: return true
        if (bar.height != snap.appliedHeight) return true
        if (snap.fill?.parent !== snap.fillHost) return true
        // Round 45：玻璃挂不上时必须有实色兜底层（挂载掉了 / 背景被清 = 胶囊又变透明）
        if (snap.fill == null && snap.fallbackHost != null) {
            val fb = snap.fallback
            if (fb == null || fb.parent !== snap.fallbackHost || fb.background == null) return true
        }
        for ((v, _) in snap.shrink) {
            // 被宿主改回定高，或被移出本体（换树）都算坏
            if (v.parent !== bar) return true
            if (v.layoutParams?.height != snap.appliedHeight) return true
        }
        // Round 29：宿主把底色层重新点亮 / 把 background 塞回来也算坏（否则胶囊又变白条）
        for ((v, _) in snap.hiddenFill) if (v.visibility != View.INVISIBLE) return true
        for ((v, _) in snap.clearedBg) if (v.background != null) return true
        return false
    }

    /** 立即重新施加（幂等，复用首次快照）——翻页钩子用它把闪现窗口压到一帧内 */
    fun reapplyIfBroken() {
        val snap = snapshot ?: run { applied = false; return }
        val bar = snap.bar ?: run { applied = false; return }
        if (!applied) return
        if (bar.parent == null) {
            restore()
            return
        }
        if (!geometryBroken(snap)) return
        reapply(snap, bar)
    }

    /** 复用快照重新施加；引用已死则退回 applied=false 让 ensure() 重新定位 */
    private fun reapply(snap: Snapshot, bar: View) {
        val activity = snap.activityRef?.get()
        val decor = snap.decorRef?.get()
        if (activity == null || decor == null
            || activity.isFinishing || activity.isDestroyed
        ) {
            applied = false
            return
        }
        log { "宿主改回了底栏子视图几何，重新施加" }
        applyTo(bar, decor, activity)
    }

    /** 关闭开关时还原成宿主原样 */
    fun restore() {
        val snap = snapshot
        applied = false
        snapshot = null
        // Round 31：先摘掉逐帧自愈，避免还原过程中被自己的 layout 触发
        detachHealWatch()
        if (snap == null) return
        try {
            for ((v, h) in snap.shrink) {
                v.layoutParams?.let { it.height = h; v.layoutParams = it }
            }
            // Round 29：把宿主的底色层还回去
            for ((v, vis) in snap.hiddenFill) v.visibility = vis
            for ((v, bg) in snap.clearedBg) v.background = bg
            // Round 30：还原内容列表的裁切
            for ((v, orig) in snap.unclipped) if (clipToPaddingOf(v) != orig) setClipToPaddingOf(v, orig)
            // R58：还原沉浸主列表被清零的底部内边距
            for ((v, pb) in snap.paddedBottom) {
                if (v.paddingBottom != pb) v.setPadding(v.paddingLeft, v.paddingTop, pb, v.paddingRight)
            }
            snap.fill?.let { f -> (f.parent as? ViewGroup)?.removeView(f) }
            // Round 45：移除实色兜底层
            snap.fallback?.let { f -> (f.parent as? ViewGroup)?.removeView(f) }
            val bar = snap.bar
            if (bar != null) {
                // Round 45：push 页隐藏中的话把胶囊点亮回来
                if (overlayHidden) {
                    overlayHidden = false
                    bar.visibility = View.VISIBLE
                }
                snap.barLp?.let { bar.layoutParams = it }
                bar.outlineProvider = snap.barOutline
                bar.clipToOutline = snap.barClip
                bar.elevation = snap.barElevation
                bar.invalidateOutline()
                bar.requestLayout()
            }
            log(always = true) { "已还原宿主底栏" }
        } catch (_: Throwable) {
        }
    }

    // ===== 内部实现 =====

    /**
     * 在底栏本体子树里按 resourceName 找一个节点（先扫直接子视图，找不到再退回全树
     * `findViewById` —— `fkg` 是孙子辈，靠后者命中）。
     */
    private fun findBarChild(bar: View, decor: View, activity: Activity, name: String): View? {
        val vg = bar as? ViewGroup
        if (vg != null) {
            for (i in 0 until vg.childCount) {
                val c = vg.getChildAt(i)
                if (resourceName(c) == name) return c
            }
        }
        val found = decor.findViewById<View>(idOf(activity, name)) ?: return null
        // ⚠️ 兜底路径必须验证确实落在底栏子树里：同 ID 跨页复用是本项目最大的误伤源，
        // 拿错了就会去改别的页面的视图（把无关容器隐藏/清背景）。
        return if (vg != null && isAncestorOf(vg, found)) found else null
    }

    /**
     * 把「内容根」里滚动容器的 `clipToPadding` 关掉（Round 30）。
     *
     * 第一次调用时 DFS 收集并记下原值；之后每次施加只做幂等回写（宿主可能重新打开）。
     * ⚠️ 只取**整宽**的滚动容器（窄的横向轮播不动），避免无谓的连带影响。
     * ⚠️ 首次施加时视图可能还没测量完（width=0），此时不做宽度过滤，宁可多收也不漏收 ——
     *    `clipToPadding=false` 对列表本身是良性的，且 restore() 会还原。
     */
    private fun unclipContentPadding(root: ViewGroup, snap: Snapshot) {
        // 宿主换页会换掉整棵列表：先把已脱离窗口的旧引用剔掉，避免长期持有无用 View
        if (snap.unclipped.isNotEmpty()) {
            snap.unclipped.removeAll { !it.first.isAttachedToWindow }
        }
        val minWidth = if (root.width > 0) root.width * 3 / 4 else 0
        var fresh = 0
        fun walk(v: View, depth: Int) {
            if (depth > CLIP_MAX_DEPTH) return
            val immersiveMain = immersiveClassCache.getOrPut(v.javaClass) {
                UNCLIP_EXCLUDE_HINTS.any { v.javaClass.name.contains(it) }
            }
            if (immersiveMain) {
                // R58：沉浸主列表 **不动 clipToPadding**（宿主拿它当顶栏淡化蒙罩的开关，
                // R57 真机 A/B 实锤；动了会在滚动时内容裸顶顶栏区、蒙罩失效）——改成
                // 「清零给底栏预留的 paddingBottom」：LayoutManager 的内容区随之下移，
                // 内容照样铺到屏幕底、被胶囊折射到，而宿主的淡化逻辑完全不受影响。
                if (snap.paddedBottom.none { it.first === v }) {
                    snap.paddedBottom += v to v.paddingBottom
                }
                return
            }
            if (isScrollContainer(v) && v.width >= minWidth && snap.unclipped.none { it.first === v }) {
                snap.unclipped += v to clipToPaddingOf(v)
                fresh++
            }
            (v as? ViewGroup)?.let { vg ->
                for (i in 0 until vg.childCount) walk(vg.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        if (fresh > 0) {
            val sb = StringBuilder()
            for ((v, orig) in snap.unclipped) {
                sb.append('[').append(v.javaClass.simpleName)
                    .append(" pad=").append(v.paddingTop).append('/').append(v.paddingBottom)
                    .append(" clip=").append(orig)
                    .append(" ").append(v.width).append('x').append(v.height).append("] ")
            }
            ModuleLog.log("$TAG: 内容滚动容器 → $sb")
        }
        for ((v, _) in snap.unclipped) if (clipToPaddingOf(v)) setClipToPaddingOf(v, false)
        // R58/R61：把沉浸主列表「给底栏预留」的 paddingBottom 收缩到 LIST_PAD_BOTTOM_PX
        // （不是 0！为 0 会触发宿主进全屏沉浸模式、顶栏背景/蒙层消失 —— 见 CLEAR_LIST_PAD 注释）。
        // 首轮可能还没被宿主设过 padding（记到 0）⇒ 之后一旦看到更大就把它当成原值补记。
        if (CLEAR_LIST_PAD) {
            for (i in snap.paddedBottom.indices) {
                val v = snap.paddedBottom[i].first
                if (v.paddingBottom <= LIST_PAD_BOTTOM_PX) continue
                if (snap.paddedBottom[i].second == 0) snap.paddedBottom[i] = v to v.paddingBottom
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, LIST_PAD_BOTTOM_PX)
            }
        }
        if (fresh > 0) {
            val diag = snap.unclipped.joinToString(" ") {
                "${it.first.javaClass.simpleName}(clip=${clipToPaddingOf(it.first)})"
            }
            ModuleLog.log("$TAG: unclip 复核 → $diag padBottomClr=${snap.paddedBottom.size}")
        }
    }

    /** 类名片段判断滚动容器（按 Class 记忆化，避免反复做字符串 contains） */
    private fun isScrollContainer(v: View): Boolean = scrollClassCache.getOrPut(v.javaClass) {
        val n = v.javaClass.name
        SCROLL_CLASS_HINTS.any { n.contains(it) }
    }

    private fun clipMethods(v: View): Pair<java.lang.reflect.Method?, java.lang.reflect.Method?> =
        clipMethodCache.getOrPut(v.javaClass) {
            val get = try {
                v.javaClass.getMethod("getClipToPadding")
            } catch (_: Throwable) {
                null
            }
            val set = try {
                v.javaClass.getMethod("setClipToPadding", java.lang.Boolean.TYPE)
            } catch (_: Throwable) {
                null
            }
            get to set
        }

    private fun clipToPaddingOf(v: View): Boolean = try {
        clipMethods(v).first?.invoke(v) as? Boolean ?: true
    } catch (_: Throwable) {
        true
    }

    private fun setClipToPaddingOf(v: View, value: Boolean) {
        try {
            clipMethods(v).second?.invoke(v, value)
        } catch (_: Throwable) {
        }
    }

    /**
     * 临时诊断：把底栏子树（限深 5）每个节点的 resourceName / 可见性 / alpha /
     * background 类型与颜色打一行日志，用于定位「胶囊白底」到底是哪个节点画的。
     */
    private fun diagBarFill(bar: View) {
        if (!DIAG_BAR_FILL) return
        val sb = StringBuilder()
        fun walk(v: View, depth: Int) {
            if (depth > 5) return
            val bg = v.background
            sb.append('[').append(resourceName(v) ?: v.javaClass.simpleName)
                .append(" vis=").append(v.visibility)
                .append(" a=").append(v.alpha)
                .append(" bg=").append(bg?.javaClass?.simpleName ?: "null")
            if (bg is ColorDrawable) sb.append("(#").append(Integer.toHexString(bg.color)).append(')')
            sb.append("] ")
            (v as? ViewGroup)?.let { vg -> for (i in 0 until vg.childCount) walk(vg.getChildAt(i), depth + 1) }
        }
        walk(bar, 0)
        ModuleLog.log("$TAG: 诊断底色 → $sb")
    }

    /** 沿 at0 → asy → asx → fv2 上溯拿底栏本体；任一级 resourceName 不符即放弃 */
    private fun locate(activity: Activity, decor: View): View? {
        var cur: View? = decor.findViewById(idOf(activity, CHAIN[0])) ?: return null
        for (i in 1 until CHAIN.size) {
            val parent = cur?.parent as? View ?: return null
            if (resourceName(parent) != CHAIN[i]) return null
            cur = parent
        }
        return cur
    }

    private fun applyTo(bar: View, decor: View, activity: Activity) {
        val lp = bar.layoutParams
        if (lp !is FrameLayout.LayoutParams) {
            log { "底栏本体 LayoutParams 不是 FrameLayout 的（${lp?.javaClass?.name}），放弃" }
            return
        }
        val density = activity.resources.displayMetrics.density
        val side = (SIDE_MARGIN_DP * density).toInt()
        val height = (CAPSULE_HEIGHT_DP * density).toInt()
        val top = (TOP_MARGIN_DP * density).toInt()
        val radius = height / 2f
        val isDark = (activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        try {
            // ⚠️ 同一视图二次改造时必须复用首次快照 —— 否则会把「已改造后」的
            // LayoutParams/背景当成原始值存下来，关闭开关就还原不回宿主原样。
            val snap = snapshot?.takeIf { it.bar === bar } ?: Snapshot().also { s ->
                s.bar = bar
                s.barLp = FrameLayout.LayoutParams(lp)
                s.barOutline = bar.outlineProvider
                s.barClip = bar.clipToOutline
                s.barElevation = bar.elevation
            }

            // 诊断（仅首次、改造前采样）：打印底栏子树各节点的 background/可见性，
            // 定位胶囊白底来源。取证后把 DIAG_BAR_FILL 置 false 即可。
            if (DIAG_BAR_FILL && snap.applyCount == 0) diagBarFill(bar)

            // ---- ① 本体：收窄 + 圆角 + 阴影（只改 LayoutParams / outline，不碰 background）----
            val fresh = FrameLayout.LayoutParams(snap.barLp ?: lp)
            fresh.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            fresh.leftMargin = side
            fresh.rightMargin = side
            fresh.topMargin = top
            fresh.bottomMargin = 0
            fresh.height = height
            bar.layoutParams = fresh

            // R92：胶囊区域消费触摸，阻止事件穿透到底下的 feed/卡片
            // （用户反馈"点击悬浮底栏经常触发其他操作"）。只置 clickable：
            // tab 按钮和中间圆形封面照常响应，落在胶囊上的其余点击被吞掉。
            if (!bar.isClickable) bar.isClickable = true

            bar.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            bar.clipToOutline = true
            // elevation 必须在本体上：阴影由父容器 fb15e4d(1220x270) 绘制，余量够；
            // 放到子视图上会被本体的 clipChildren 裁掉。
            bar.elevation = ELEVATION_DP * density
            bar.invalidateOutline()

            // ---- ② 定高子视图同步收缩（否则被 ConstraintLayout 居中溢出裁掉）----
            // 已记录的直接重新施加（宿主 relayout 可能把定高改回去 ⇒ 幂等自愈）
            if (snap.shrink.isEmpty()) {
                for (name in SHRINK_IDS) {
                    val child = findBarChild(bar, decor, activity, name) ?: continue
                    if (child.parent !== bar) continue
                    snap.shrink += child to (child.layoutParams?.height ?: 0)
                }
            }
            for ((v, _) in snap.shrink) {
                v.layoutParams?.let { it.height = height; v.layoutParams = it }
            }

            // ---- ②c 关掉宿主自带的胶囊底色（Round 29）----
            // 真机实锤：把液态玻璃开关**关掉**后胶囊仍是**一条实心白药丸**（背后内容完全
            // 透不过来）⇒ 这层白不是我们画的，是宿主 `fkf` 里 `fke`(HnVisualEffectLayout，
            // 荣耀实时模糊容器) → `fkg`(实心 View) 铺的底。它画在我们玻璃**之下**：玻璃
            // 覆盖不到的地方（录到的内容 alpha=0）就透出这层白 ⇒ 胶囊看着仍像原来的实色栏。
            // 处置：`fke`/`fkg` 置 INVISIBLE（同尺寸，不影响布局），`fv2`/`fkf`/`asx` 的
            // background 一并清空 —— 胶囊的底色从此只由我们负责（关掉玻璃 = 真正透明）。
            if (snap.hiddenFill.isEmpty()) {
                for (name in HOST_FILL_IDS) {
                    val v = findBarChild(bar, decor, activity, name) ?: continue
                    snap.hiddenFill += v to v.visibility
                }
            }
            for ((v, _) in snap.hiddenFill) {
                if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE
            }

            if (snap.clearedBg.isEmpty()) {
                for (name in BG_CLEAR_IDS) {
                    val v = if (name == BAR_ID) bar else findBarChild(bar, decor, activity, name) ?: continue
                    if (v.background != null) snap.clearedBg += v to v.background
                }
            }
            for ((v, _) in snap.clearedBg) v.background = null

            // ---- ②d 让内容流到胶囊背后（Round 30）----
            // 宿主按「底栏占位高」(topMargin 18 + height 186 = 204) 给内容列表留了
            // paddingBottom，且 `clipToPadding=true` ⇒ 内容在 2712-204=**2508** 处被硬裁，
            // 2508 以下只剩背景色（实测条目其实一直排到 2694，只是被裁掉了）。
            // 我们的胶囊在 **2460-2646**，正好跨过这条裁线：上半 48px 背后有内容、下半是
            // 空白 ⇒ 看上去像被一条白色底栏横切（用户报「悬浮胶囊被白色底栏截断」）。
            // 处置：把内容根里滚动容器的 `clipToPadding` 关掉 —— padding **照样占位**
            // （列表末尾仍能滚到胶囊上方），但内容不再被裁，一直画到屏幕底 ⇒ 胶囊背后
            // 全程有内容可折射，裁线消失。
            val contentRoot = locateContentRoot(bar, decor)
            if (contentRoot != null) unclipContentPadding(contentRoot, snap)

            // ---- ③ 胶囊填充层：液态玻璃，插到「毛玻璃包装 fkf」的最后 ----
            // 详见文件头 ③：只给 fv2 设 background 会被 fke（HnVisualEffectLayout）
            // 整个盖掉，实测胶囊内部像素 = 页面底色。
            //
            // Round 28：原来的实色 GradientDrawable **整层撤掉**，换成 QmDeve 液态玻璃
            // （RenderNode 录下胶囊背后那块内容 → AGSL 折射/色散/模糊 → 画回胶囊内）。
            // 找不到「内容根」就**不挂** —— 玻璃没有背景源会画成黑块，比没有填充层更糟。
            val glassOn = Settings.isFeature(activity, Settings.KEY_UI_LIQUID_GLASS)
            val fillHost = decor.findViewById<View>(idOf(activity, FILL_HOST_ID)) as? ViewGroup
            val glassHost = if (glassOn) fillHost else null
            if (glassHost != null && contentRoot != null) {
                // 玻璃回归 ⇒ 撤掉实色兜底层（Round 45）
                removeFallbackFill(snap)
                val cfg = buildGlassConfig(density, radius, isDark)
                val existing = snap.fill as? LiquidGlass
                if (existing != null) {
                    // 复用实例：只刷新参数（深浅色/圆角变了），避免每次翻页重编译 AGSL
                    existing.config.copyFrom(cfg)
                    existing.updateParameters()
                } else {
                    snap.fill = LiquidGlass(activity, cfg)
                }
                val glass = snap.fill as LiquidGlass
                if (glass.parent !== glassHost) {
                    (glass.parent as? ViewGroup)?.removeView(glass)
                    glassHost.addView(
                        glass,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                glass.init(contentRoot)
                snap.fillHost = glassHost
            } else {
                // 开关关闭 / 找不到填充宿主 / 找不到内容根 ⇒ 清掉旧填充层
                snap.fill?.let { f -> (f.parent as? ViewGroup)?.removeView(f) }
                snap.fill = null
                snap.fillHost = null
                // Round 45（文件头 ⑪）：胶囊内部从此必须由实色兜底层负责 —— 不挂的话
                // 页面内容直接透过胶囊，叠上 elevation 阴影 = 「半透明影子悬空」。
                attachFallbackFill(snap, fillHost, activity)
            }

            snap.appliedHeight = height
            snap.activityRef = WeakReference(activity)
            snap.decorRef = WeakReference(decor)
            snap.applyCount++
            snapshot = snap
            applied = true
            bar.requestLayout()
            // Round 31：挂上逐帧自愈 —— 宿主任何重排（点 tab / 翻页）都会在绘制前被修正
            attachHealWatch(decor)

            // 首次施加必记；自愈重复施加限流（宿主每次翻页都改回去时会很频繁）
            log(always = snap.applyCount <= 1) {
                "已改造为贴底胶囊 (side=${side}px height=${height}px top=${top}px" +
                    " glass=${if (snap.fillHost != null) "ok" else if (!glassOn) "off" else "缺失"}" +
                    " fallback=${if (snap.fallback != null) "on" else "-"}" +
                    " root=${contentRoot?.javaClass?.simpleName ?: "-"}" +
                    " shrink=${snap.shrink.size} hideBg=${snap.hiddenFill.size}/${snap.clearedBg.size}" +
                    " unclip=${snap.unclipped.size} padClr=${snap.paddedBottom.size}" +
                    " dark=$isDark" +
                    if (snap.applyCount > 1) " 第${snap.applyCount}次" else ""
            }
        } catch (t: Throwable) {
            applied = false
            log { "改造失败: ${t.javaClass.simpleName}: ${t.message}" }
        }
    }

    // ===== 实色兜底层（Round 45，文件头 ⑪）=====

    /**
     * 挂实色兜底层（玻璃挂不上时）：矩形 ColorDrawable 即可 —— `fv2` 的
     * clipToOutline 会把它裁成圆角。加在 `fkf` 的**最后** ⇒ 画在 fke（已 INVISIBLE）
     * 之上。颜色优先取宿主 `fkg` 的底色（深浅色由宿主主题适配），取不到给默认值。
     * ⚠️ 直接 setColor 不透明色，不走 `setAlpha`（ColorDrawable 的 alpha 是调制不是
     * 覆盖，基色 alpha=0 时恒全透明 —— Round 34 弹层遮罩的教训）。
     */
    private fun attachFallbackFill(snap: Snapshot, host: ViewGroup?, activity: Activity) {
        if (host == null) return
        var v = snap.fallback
        if (v == null || v.parent !== host) {
            (v?.parent as? ViewGroup)?.removeView(v)
            v = View(activity)
            snap.fallback = v
            snap.fallbackHost = host
            host.addView(
                v,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        v.background = ColorDrawable(fallbackColor(snap, activity))
    }

    private fun removeFallbackFill(snap: Snapshot) {
        snap.fallback?.let { f -> (f.parent as? ViewGroup)?.removeView(f) }
        snap.fallback = null
        snap.fallbackHost = null
    }

    /** 兜底色：宿主 `fkg` 的 ColorDrawable 优先（Round 29 已把它 INVISIBLE，background 仍在） */
    private fun fallbackColor(snap: Snapshot, activity: Activity): Int {
        for ((v, _) in snap.hiddenFill) {
            if (resourceName(v) == "fkg") {
                (v.background as? ColorDrawable)?.let { return it.color }
            }
        }
        val isDark = (activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (isDark) FALLBACK_COLOR_DARK else FALLBACK_COLOR_LIGHT
    }

    // ===== push 页盖顶隐藏（Round 45，文件头 ⑫）=====

    /** FragmentStack 子支分类记忆化：0 = 无关支，1 = 非 home 的 fragment 支，2 = home 支 */
    private val branchKindCache = HashMap<Class<*>, Int>()

    private fun branchKind(v: View): Int = branchKindCache.getOrPut(v.javaClass) {
        val n = v.javaClass.name
        when {
            n == FRAG_BRANCH_HOME -> 2
            n.startsWith(FRAG_BRANCH_PREFIX) -> 1
            else -> 0
        }
    }

    /**
     * 是否有 push 页盖在主页上（搜索页等）：`FragmentStackView` 下只有非 home 的
     * FragmentView* 支可见 ⇒ 主页已 GONE、栈顶是二级页。O(子支数)（4 个）。
     * 层级对不上（宿主改版）时自然返回 false —— 功能静默失效，绝不误伤。
     */
    private fun overlayCovering(stack: ViewGroup): Boolean {
        var visibleFrag = 0
        for (i in 0 until stack.childCount) {
            val c = stack.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            when (branchKind(c)) {
                2 -> return false // home 可见 = 主页
                1 -> visibleFrag++
            }
        }
        return visibleFrag > 0
    }

    // ===== R99：搜索页只保留中间圆形封面（转盘）=====
    //
    // 判据终于确定了：**栈顶 push 容器的类名**。
    // 进程内遍历实测（adb 的 dumpsys/uiautomator 都拿不到，因为有唱片动画）：
    //   搜索页 → com.kugou.common.base.uiframe.FragmentViewNormalAnimationFirst
    //   播放页 → com.kugou.common.base.uiframe.FragmentViewPlayer3   （1220x2712 全屏）
    // 之前四种判据（容器内 EditText / 可见 Fragment 名 / 大图）都失败，
    // 根因是播放页**叠加**在搜索页之上、搜索页的一切都还活着；只有容器类名是分得开的。
    //
    // 做法：整条胶囊 INVISIBLE + 把转盘 fkb 摘到 DecorView ⇒ 屏幕上只剩圆形封面。
    // 播放页（Player3）不摘，保持整条隐藏。
    private const val SEARCH_CONTAINER = "FragmentViewNormalAnimationFirst"

    private var detachedWheel: View? = null
    /** 上一次判定过的栈顶容器（只在它变化时才重新遍历容器内容，避免每帧开销） */
    private var lastTopRef: java.lang.ref.WeakReference<View>? = null

    /** 是否在「搜索流程」中：进搜索页置 true、回主页置 false（R117） */
    private var inSearchFlow = false
    private var wheelOrigParent: java.lang.ref.WeakReference<ViewGroup>? = null
    private var wheelOrigLp: ViewGroup.LayoutParams? = null

    /** 栈顶的 push 页容器：取**最后一个**可见的（搜索页在播放页之下时仍 VISIBLE） */
    private fun topPushContainer(stack: ViewGroup): View? {
        var last: View? = null
        for (i in 0 until stack.childCount) {
            val c = stack.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (branchKind(c) == 1) last = c
        }
        return last
    }

    /** 容器里是否有可见的搜索输入框。频道页/歌单详情等也用同一个容器类，必须再确认内容 */
    private fun containsSearchBox(root: View): Boolean {
        val q = ArrayDeque<View>()
        q.add(root)
        var guard = 0
        while (q.isNotEmpty() && guard++ < 1500) {
            val v = q.removeFirst()
            if (v is android.widget.EditText && v.isShown && v.width > 0 && v.height > 0) return true
            if (v is ViewGroup) for (i in 0 until v.childCount) q.add(v.getChildAt(i))
        }
        return false
    }

    private fun detachWheel(snap: Snapshot): Boolean {
        val bar = snap.bar ?: return false
        val decor = snap.decorRef?.get() ?: return false
        val act = snap.activityRef?.get() ?: return false
        val dv = decor as? ViewGroup ?: return false
        detachedWheel?.let { if (it.parent === dv) return true }

        val wheel = try { findBarChild(bar, decor, act, "fkb") } catch (_: Throwable) { null }
            ?: return false
        // ⭐ R117：转盘显隐**由宿主决定** —— 宿主没设 VISIBLE（如某些页面设 GONE）就不摘
        if (wheel.visibility != View.VISIBLE) return false
        val hostVis = wheel.visibility
        return try {
            val locW = IntArray(2)
            val locD = IntArray(2)
            wheel.getLocationOnScreen(locW)
            decor.getLocationOnScreen(locD)
            detachedWheel?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
            wheelOrigParent = (wheel.parent as? ViewGroup)?.let { java.lang.ref.WeakReference(it) }
            wheelOrigLp = wheel.layoutParams
            (wheel.parent as? ViewGroup)?.removeView(wheel)
            val lp = FrameLayout.LayoutParams(
                wheelOrigLp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                wheelOrigLp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = locW[0] - locD[0]
            lp.topMargin = locW[1] - locD[1]
            dv.addView(wheel, lp)
            // 恢复宿主给的状态，不强制 VISIBLE（R117：显隐由宿主决定）
            wheel.visibility = hostVis
            detachedWheel = wheel
            log(always = true) { "搜索页：转盘已摘到 DecorView" }
            true
        } catch (t: Throwable) {
            log(always = true) { "搜索页：转盘摘取失败 $t" }
            false
        }
    }

    private fun reattachWheel(snap: Snapshot) {
        val w = detachedWheel ?: return
        detachedWheel = null
        try {
            (w.parent as? ViewGroup)?.removeView(w)
            val lp = wheelOrigLp
            val op = wheelOrigParent?.get()
            if (op != null && lp != null) {
                op.addView(w, lp)
            } else {
                val bar = snap.bar as? ViewGroup
                if (bar != null && lp != null) bar.addView(w, lp)
            }
        } catch (t: Throwable) {
            log(always = true) { "转盘装回失败 $t" }
        }
        wheelOrigParent = null
        wheelOrigLp = null
    }

    /**
     * 按当前 push 页状态切换胶囊可见性（每帧 preDraw / 4s 轮询各调一次）。
     * 用 INVISIBLE 而非 GONE：整棵子树不绘制（含 elevation 阴影），但保留布局，
     * 恢复时零 relayout 抖动；也不会干扰 geometryBrokenFast 的高度校验。
     */
    private fun updateOverlayHidden(snap: Snapshot) {
        val bar = snap.bar ?: return
        val stack = findFragmentStack(snap) ?: return
        val cover = overlayCovering(stack)
        if (cover != overlayHidden) {
            overlayHidden = cover
            bar.visibility = if (cover) View.INVISIBLE else View.VISIBLE
            log(always = true) {
                if (cover) "二级页盖顶：隐藏胶囊" else "回到主页：恢复胶囊"
            }
        }

        // ⭐ R101：**cover 没变也可能换了页面**（搜索页 → 播放页，两者都 cover=true），
        // 原来在这里 `if (cover == overlayHidden) return` 直接跳过 ⇒ 进播放页后
        // 转盘还挂在那儿收不回来（用户实锤：从搜索页点歌进播放页，底部仍有小圆）。
        // 现在每帧都按**栈顶容器**校正；只有栈顶变了才重新遍历容器内容（省开销）。
        if (!cover) {
            inSearchFlow = false
            if (detachedWheel != null) reattachWheel(snap)
            lastTopRef = null
            return
        }
        val top = topPushContainer(stack)
        if (top === lastTopRef?.get()) return
        val topName = top?.javaClass?.simpleName ?: "?"
        val wr = top?.let { java.lang.ref.WeakReference(it) }
        // 进搜索页（容器内有搜索框）→ 打开「搜索流程」
        if (top != null && topName == SEARCH_CONTAINER && containsSearchBox(top)) {
            inSearchFlow = true
        }
        // ⭐ R117：三态
        //   ① 播放页 → 收回（播放页有自己的大唱片）
        //   ② 搜索流程内（搜索页 + 新歌速递/排行榜/子页面，含从播放页返回）→ 确保在顶层
        //   ③ 其它 → 保持
        when {
            topName == "FragmentViewPlayer3" -> {
                if (detachedWheel != null) reattachWheel(snap)
                lastTopRef = wr
                log(always = true) { "栈顶=播放页 → 收回转盘" }
            }
            inSearchFlow -> {
                if (detachedWheel == null) {
                    if (detachWheel(snap)) lastTopRef = wr
                } else {
                    lastTopRef = wr
                }
                log(always = true) { "栈顶=$topName（搜索流程内）→ 转盘在顶层" }
            }
            else -> {
                lastTopRef = wr
                log(always = true) { "栈顶=$topName → 保持" }
            }
        }
    }

    /** FragmentStackView 的宿主资源 ID 名（真机 dump 实锤：app:id/ajd） */
    private const val STACK_ID = "ajd"

    /** FragmentStackView 引用缓存（弱引用，失效后按 decor 重新 findViewById） */
    private var stackRef: WeakReference<View>? = null

    /**
     * 拿 FragmentStackView：**必须从 decor 按 id 找，不能从底栏上溯**。
     * 真机 dump 两轮实锤（Round 45）：主页树上底栏支是 ajd 的直接子支
     * （fv2→fb15e4d→a2cce57→ajd），而搜索页等状态下宿主会把导航栏支
     * **reparent 到 FrameworkContentView 下**（fv2→24d92b3→46d4f73→
     * FrameworkContentView）—— 此时从 fv2 上溯到 decor 都碰不到 ajd
     * （首版数 3 级、二版沿链上溯各失效一轮）。ajd 自身资源名稳定
     * （app:id/ajd），引用缓存 + 失效重找，稳态每帧零查找。
     */
    private fun findFragmentStack(snap: Snapshot): ViewGroup? {
        stackRef?.get()?.let { s ->
            if (s.isAttachedToWindow && s.parent != null) return s as? ViewGroup
            stackRef = null
        }
        val activity = snap.activityRef?.get() ?: return null
        val decor = snap.decorRef?.get() ?: return null
        val s = decor.findViewById<View>(idOf(activity, STACK_ID)) ?: return null
        stackRef = WeakReference(s)
        return s as? ViewGroup
    }

    /** 按深浅色 + 胶囊圆角装配一份玻璃参数（每次 apply 重建，成本可忽略） */
    private fun buildGlassConfig(density: Float, radiusPx: Float, isDark: Boolean): GlassConfig {
        val cfg = GlassConfig()
        cfg.configure(
            GlassConfig.Overrides()
                .cornerRadius(radiusPx)
                .refractionHeight(GLASS_REFRACTION_HEIGHT_DP * density)
                .refractionOffset(GLASS_REFRACTION_OFFSET_DP * density)
                .blurRadius(GLASS_BLUR_DP * density)
                .dispersion(GLASS_DISPERSION)
                .contrast(GLASS_CONTRAST)
                .whitePoint(GLASS_WHITE_POINT)
                .chromaMultiplier(GLASS_CHROMA)
                .tintAlpha(if (isDark) GLASS_TINT_DARK else GLASS_TINT_LIGHT)
                .tintColor(if (isDark) 0.05f else 1f, if (isDark) 0.05f else 1f, if (isDark) 0.08f else 1f)
                .recordIntervalMs(GLASS_RECORD_INTERVAL_MS),
        )
        return cfg
    }

    /**
     * 找玻璃背后的「内容根」（Round 34 起委托给 [GlassTarget]，与设置面板共用一份）。
     *
     * 真机层级（5.2.9，`dumpsys activity` 实测）：
     * ```
     * FragmentStackView{ajd 0,0-1220,2712}
     *  ├ FragmentViewHome{f50f595 0,0-1220,2712}        ← 首页 fragment
     *  │  └ MainContainerLayout{92448cb 0,0-1220,2712}  ← ★ 内容根（含双层 pager）
     *  └ FrameLayout{2bc0376 0,0-1220,2712}             ← 底栏挂在**另一支**上
     *     └ fb15e4d{0,2442-1220,2712} → fv2 → fkf → …
     * ```
     * ⚠️ 两者是**兄弟**（同在 `FragmentStackView` 下），所以**不能**从底栏往上爬找
     * `FragmentViewHome` —— 爬不到（Round 28 首次实现就栽在这，日志 `root=-`）。
     * 硬门禁「结果不能是底栏的祖先」也一并交给 [GlassTarget.locate]。
     */
    private fun locateContentRoot(bar: View, decor: View): ViewGroup? =
        GlassTarget.locate(decor, mustNotBeAncestorOf = bar)

    /** v 的祖先链里是否有 anc（玻璃安全的最后一道保险） */
    private fun isAncestorOf(anc: ViewGroup, v: View): Boolean = GlassTarget.isAncestorOf(anc, v)

    /** 强制重新施加一次（玻璃开关切换时用；几何未坏也照做） */
    fun forceReapply() {
        val snap = snapshot ?: return
        val bar = snap.bar ?: return
        if (!applied || bar.parent == null) return
        reapply(snap, bar)
    }

    private fun idOf(ctx: Context, name: String): Int {
        idCache[name]?.let { return it }
        val id = try {
            ctx.resources.getIdentifier(name, "id", HOST_PKG)
        } catch (_: Throwable) {
            0
        }
        idCache[name] = id
        return id
    }

    /**
     * 资源 ID → 资源名缓存（Round 50）：与 UiCleaner 同款记忆化。
     * getResourceEntryName 走 AssetManager 查表并分配字符串；resourceName 在
     * applyTo（findBarChild 每个目标 ID 一次）与 overlayCovering 等路径被反复
     * 调用，翻页 reapply 时同一批 ID 重复查表。资源 ID 进程内恒定，int→String
     * 记忆化后 reapply 路径零查表。
     */
    private val idNameCache = android.util.SparseArray<String>(16)

    private fun resourceName(v: View): String? {
        val id = v.id
        if (id == View.NO_ID) return null
        synchronized(idNameCache) { idNameCache.get(id)?.let { return it } }
        return try {
            val n = v.resources.getResourceEntryName(id)
            synchronized(idNameCache) { idNameCache.put(id, n) }
            n
        } catch (_: Throwable) {
            null
        }
    }

    /** 日志：成功/还原/自愈必记；失败 10s 内不重复刷屏（lambda 避免不必要的字符串拼接） */
    private inline fun log(always: Boolean = false, msg: () -> String) {
        if (!always) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastLogAt < 10_000L) return
            lastLogAt = now
        }
        ModuleLog.log("$TAG: ${msg()}")
    }
}
