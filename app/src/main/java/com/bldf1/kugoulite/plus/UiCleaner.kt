package com.bldf1.kugoulite.plus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.PriorityQueue
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * UI 净化引擎：去广告 / 隐藏会员类入口。
 *
 * 设计要点：
 * - 关键词防御式匹配：类名关键词 + 文本关键词 + 资源 ID 名三路匹配，
 *   任一路命中即视为目标控件（混淆后 ID 名作为精确制导补充）；
 * - 持续隐藏：出生拦截（构造/挂载 hook）+ 周期守护扫描（4s）+
 *   多帧挂载复核（ID 规则延迟 tick 复判）多层兜底，防止广告 SDK
 *   延迟异步填充或复活；
 * - 所有匹配均做防御式判空，任何异常不得影响宿主 App 稳定性。
 */
object UiCleaner {

    private const val TARGET_PACKAGE = "com.kugou.android.lite"
    private const val MAX_DEPTH = 30

    /**
     * 严格版「已绘制」判据（R66，2b 零闪现取信用）。
     *
     * `wasDrawn()` 对 **0x0 的视图**会报"是"：宿主控件重写 `onAttachedToWindow` 不调
     * `super` ⇒ attachUptime 无记录 ⇒ 兜底 `?:0L` 把上一次生命的 drawingTime 残留
     * 误判成已绘制（代码里早有注释）。但**0x0 的视图从来没真正上过屏**，
     * 它不可能构成闪现 —— 直接拿来当"闪现"会大量假阳性。
     * ⇒ 只有 `wasDrawn && 尺寸 > 0` 才算"真的被用户看到过"。
     */
    private fun wasDrawnStrict(view: View): Boolean {
        if (!wasDrawn(view)) return false
        return view.width > 0 && view.height > 0
    }

    /**
     * 隐藏 feed 整卡时**是否把 item 根高度清零**（P0「发现页刷不到底」的根因开关，R65）。
     *
     * ⭐ 真机 A/B 实锤（默认 45 项全开、每轮 16 次上滑，判据＝宿主适配器「末次插入距窗口
     * 结束的秒数」）：
     *   · `= true`（清零，现行行为）：**4/4 轮卡死**，插入仅 4~5 次、尾部静默 18~20s；
     *   · `= false`（只 INVISIBLE、保留原高度）：**0/4 轮卡死**，插入 8~9 次、静默 0.1~2.3s。
     *
     * ⇒ 机理：清零塌缩了列表的「可滚动内容高度」⇒ 宿主 `DiscoveryCardPresenter`
     *   判定 feed 已到底 ⇒ **不再驱动卡片协议管线**（`DiscoveryCardProtocol` 计数归零）⇒
     *   没有新 item ⇒ 内容更不会增长 = 死锁。这也解释了它的**加法性**（藏得越多塌得越狠）
     *   与**概率性**（每个批次内容不同），以及"抖动/滚动手势救不回来"（不是滚动事件触发）。
     *
     * ⭐ 正解（R65 已落地）＝**收缩到 [COLLAPSED_ITEM_HEIGHT_PX]（40px 薄缝）而不是 0**：
     *   既不让列表的可滚动度量塌缩（0/4 卡死），也不会像"保留原高度"那样留整块空白。
     *   置 false（保留原高度）仅作对照实验用，会造成列表中间大片空白，**不要发布**。
     */
    private const val COLLAPSE_ITEM_HEIGHT = true

    /**
     * 隐藏 feed 整卡时 item 根**收缩到多高**（px）。**不要改成 0**（见下）。
     *
     * ⭐ R65 真机四档 A/B（默认 45 项全开、每轮 16 次上滑；判据＝宿主适配器
     * 「末次插入距窗口结束的秒数」，工具 `.workbuddy/tools/p0probe2.py`）：
     *   · `0`（完全塌缩）    → **4/4 轮卡死**（插入仅 4~5 次、尾部静默 18~20s）
     *   · `8`                → 1~2/4 轮卡死（边界，不可用）
     *   · **`40`（现值）**    → **0/4 轮卡死**（插入 10~11 次、静默 0.1~1.7s）
     *   · 不收缩（只 INVISIBLE 保留原高）→ 0/4，但留**整块**空白
     * ⇒ 阈值在 8~40 之间，取 40 留约 5 倍余量；视觉代价＝每个被隐藏区块只留
     *   ~40px（13dp）的薄缝，而不是整块空白。
     *
     * 机理：把高度清零会塌缩列表的「可滚动内容」度量，宿主 `DiscoveryCardPresenter`
     * 据此判定 feed 已到底 ⇒ 不再驱动卡片协议管线（`DiscoveryCardProtocol` 计数归零）
     * ⇒ 不插新 item ⇒ 死锁。这解释了它的**加法性**（藏得越多塌得越狠）、**概率性**
     * （每批内容不同）、"手势救不回"（不是滚动事件触发）以及与**性能无关**
     * （PerfProbe 实测占空比仅 0.1%~0.5%）。
     * ⚠️ 与 R61 的 `LIST_PAD_BOTTOM_PX`（0→1）是同一类坑：**"0" 往往是宿主的特殊判据**。
     * ⚠️ 试过用 paddingBottom 在列表末尾补偿被省掉的高度 ⇒ **无效（4/4）**，
     *   所以必须让 item 自己保留一个非零高度。
     */
    private const val COLLAPSED_ITEM_HEIGHT_PX = 40

    /**
     * 🔬 诊断开关：是否启用「周期 4s 巡检」（`startPeriodicScan`）。
     *
     * 背景：发现页信息流「刷不到底」呈**剂量效应**——关掉的净化规则越多越不容易卡，
     * 全关 45 项时连续 100 次上滑都不卡、默认配置 6~14 次就卡死；单条规则级二分不收敛。
     * R59b 曾把它置 false 验证「周期巡检是不是那份剂量」→ **仍然卡（第 6 次就停）**，
     * 故排除。保留开关（生产值 true）便于以后继续排查。
     */
    private const val PERIODIC_SCAN_ENABLED = true

    private val mainHandler = Handler(Looper.getMainLooper())

    // 当前前台 Activity（onResume 时更新）——酷狗页面切换为 Activity 内
    // fragment 切换（不触发 onResume），周期守护扫描依赖它拿到最新界面
    @Volatile
    internal var currentActivity: WeakReference<Activity>? = null

    // 被本模块隐藏的控件 → 所属规则（分离时恢复，保证回收池干净）
    private val hiddenByUs = WeakHashMap<View, Rule>()

    // INVISIBLE 隐藏标记：空标题槽位预隐藏用 INVISIBLE（保锚定链），
    // 复活压制 hook 压回时按此标记选 INVISIBLE 而非 GONE
    private val invisibleHides = WeakHashMap<View, Boolean>()

    // 被我们「平移补位」的视图（免费听图标空槽 → 搜索图标补位，见 fillFreeListenSlot）；
    // 以及被 alpha=0 隐空的免费听底板 dg1 —— 关闭该开关时一并还原
    private val shiftedByUs = WeakHashMap<View, Boolean>()
    private val neutralizedPlates = WeakHashMap<View, Boolean>()

    /**
     * ⭐ R67「改了即时生效」修复：neutralize 型改动**保存原始值**。
     *
     * `hiddenByUs` 只记「被 GONE/改高」的视图，而这两处改动**不在该体系里**：
     *  · 免费听图标：把 dg1 底板的 `background` 抹成 null（去掉胶囊底）
     *  · 我的页会员角标：把 iv5 底板的 `background` 换成卡面同色、并把高度对齐白卡
     * ⇒ 关掉开关时只还 alpha/可见性，**底板背景回不来**（用户看到"关了但没恢复"）。
     * 这里在首次改动前把原值存下来，`restoreRule` 里按 key 还原。
     * （用 containsKey 判断"只存第一次"，避免宿主重设背景后被我们覆盖成自己的值。）
     */
    private val savedBackgrounds = WeakHashMap<View, Drawable?>()
    private val savedAlphas = WeakHashMap<View, Float>()
    private val savedPlateHeights = WeakHashMap<View, Int>()

    /**
     * 净化规则（此处保留原文档注释，简要重述）：
     * sectionMode：文本命中后连同区块容器一起隐藏
     * hideParentLevels：命中后连同 N 层父容器一起隐藏
     * idNames：资源ID名匹配（混淆ID精确制导）
     * ancestorClass：祖先链必须包含的类名
     * maxYRatio：屏幕高度上限约束
     * useCardHide：整卡模式（hideCardWithRecheck）
     * excludeIdNames：ID 排除门禁
     */
    private class Rule(
        val key: String, val name: String,
        val classKeywords: List<String>, val textKeywords: List<String>,
        val region: FloatArray? = null,
        val minWRatio: Float = 0f,
        val minYRatio: Float = 0f,
        val maxYRatio: Float = 0f,
        val idNames: List<String> = listOf(),
        val ancestorClass: String? = null,
        val hideParentLevels: Int = 0,
        val sectionMode: Boolean = false,
        val useCardHide: Boolean = false,
        val excludeIdNames: List<String> = listOf(),
        // 类名排除门禁（真机实锤 15:0x）：同一资源 ID 在首页顶栏与我的页是
        // 完全不同的控件——「我的页赚钱入口」(dk_) 的祖先判定
        // TwoViewSwitchLayout 在首页顶栏同样成立，会把顶栏"乐库"入口一起
        // 隐藏（用户实锤：顶栏只剩"发现"）。按类名排除顶栏图标控件。
        val excludeClassNames: List<String> = listOf(),
        // 高度塌缩隐藏：GONE + layoutParams.height=0 + savedHeights
        // （我的页白卡内的整行推广条用——只 GONE 会留 180px 白洞）
        val collapseHeight: Boolean = false,
        // 兄弟 ID 门禁：同父容器内必须存在该 ID 的兄弟节点才命中
        // （赚钱 tab dk_ 与「我的」tab dk5 成对出现，凭此与其它页面同名
        // 顶栏图标区分，替代一刀切的 excludeClassNames）
        val requireSiblingIdName: String? = null,
        // 页面语义门禁（真机实锤 Round 19）：同一资源 ID 在主 pager 两页
        // 复用——页 0(发现) 时 dk_ 是「乐库」入口，页 1(我的) 时才是
        // 「赚钱」入口。结构判定不可靠（uiautomator 省略中间节点），
        // 必须按"我的页是否在屏幕上"判定（见 minePageOnScreen）
        val requireMinePage: Boolean = false,
        /**
         * ⭐ R85b「alpha 保险」开关（**按规则选择加入**，默认关）。
         *
         * 仅用于"宿主会把已隐藏视图**复活**、其它手段都拦不住"的规则（目前只有「播放页书签」）。
         * 置 true 后隐藏时会把 `alpha` 同时置 0 —— 宿主复活只改 visibility、碰不到 alpha，
         * 所以即使被复活也是全透明。
         *
         * ⚠️ 为什么默认关、且必须**按规则**开：置过 alpha 的视图在**每一条还原路径**上都要还原
         * （全仓有 8 处 `hiddenByUs.remove`），漏一处该视图就会**永久透明**。
         * 把范围收到单条规则，风险面才可控。（还原统一走 `restoreAlphaShield()` helper）
         */
        val alphaShield: Boolean = false,
        // ⚠️ 爬升门禁（真机实锤 Round 26，用户报「我的页右上角 搜索/信息中心/
        // 设置 图标被误伤」）：hideParentLevels="连父容器一起藏" 只有在父容器
        // **确属本规则的专属底板**时才成立。混淆 ID 跨页复用时父容器可能是
        // 完全无关的功能容器——dg0(LottieAnimationView 免费听动画) 在发现页的
        // 父是 dg1(HnVisualEffectLayout，免费听图标专属胶囊底板，连父藏正确)，
        // 在我的页的父却是 apg(LinearLayout 顶栏图标容器，内含 dg0/f5i/c67/f5j
        // 四个图标，即「免|搜索|信息中心|设置」) → 无条件爬升 = 把整个顶栏
        // 图标区带走（日志实锤 `UI净化[免费听图标] 隐藏: LinearLayout`；
        // 关掉 ui_free_listen 后三图标全部回来）。
        // 列入此表后：直接父 ID 命中 → 按 hideParentLevels 爬升；否则退化为只
        // 藏自身（levels=0），绝不牵连同层兄弟。
        val climbParentIdNames: List<String> = listOf(),
        // ⭐ 父容器 ID 门禁（Round 26）：**直接父容器**的 resourceName 必须命中
        // 白名单才算命中。比 requireMinePage 稳定——挂载瞬间父容器就已确定，
        // 可在**绘制之前**即时预隐藏（零闪现）；而页面语义门禁在挂载期仍反映
        // 旧页，会退化成"先绘制后隐藏"（Task #26 抖动）。父缺失/无 ID=不命中。
        val parentIdNames: List<String> = listOf(),
        // ⭐ 祖先 ID 门禁（Round 46）：祖先链（限深 12）任一级 resourceName 命中
        // 白名单才算命中。用于目标视图本身带通用混淆 ID（如红点数字 ip9）、
        // 但其**直接父容器无 ID** 的场景（父容器门禁够不着）——例如我的页顶栏
        // apg 内信息中心红点（c6_/ip9 挂在无 ID 的 FrameLayout 下）。
        val ancestorIdNames: List<String> = listOf()
    )

    private val RULES = listOf(
        Rule(Settings.KEY_UI_SPLASH, "开屏广告",
            listOf("SplashAd", "OpenAd", "SplashAdvert", "GdtAd", "tangramsplash"), listOf()),
        Rule(Settings.KEY_UI_BANNER, "首页Banner",
            // ⚠️ SlowSmoothSwipeViewPager 从 classKw 移除（真机验证发现误杀）：
            // 该类是通用轮播控件——发现页 feed 中部的运营推荐卡轮播
            // (ew, 1112px≈0.91 屏宽, 3 张正常运营图) 也用它，宽度>minWRatio 0.88
            // 被 scan 命中 → climbParent(1) 把整个 feed item GONE → feed 底部
            // 一大片空白占位（13:07 真机实锤）。广告轮播由 PERSISTENT_HIDE 的
            // Discovery*AdsView 出生拦截 + 「发现页Banner槽位」ela ID 制导覆盖，
            // 不需要类名兜底。与 PERSISTENT_HIDE 注释的历史决定（该类非广告
            // 专属，加入会连累乐库 Banner）一致化
            listOf("MosaicView"),
            listOf(),
            minWRatio = 0.88f, minYRatio = 0.25f, hideParentLevels = 1),
        // 发现页 feed 顶部的淘宝/天猫运营推广位（Round 51+，用户要求隐藏）：
        // 淘宝倒计时条（bnr=FrameLayout：「上淘宝更优惠」+「立即查看」+倒计时）
        // 与天猫活动大卡（fmx=FrameLayout：含 fn1「天猫双11」角标），都在发现页
        // feed RecyclerView(b7e) 里。活动文案随档期变化（双11/618/年货节…），
        // 固定文本会过期 → 用「淘宝/天猫」语义词触发 + 爬升白名单（bnr/fmx）
        // 双门禁：文本命中后只有直接父是 bnr/fmx 才连父隐藏；ID 跨页复用时
        // 退化为只藏文本，绝不牵连同层。
        Rule(Settings.KEY_UI_TAOBAO_BAR, "发现页淘宝推广条",
            listOf(), listOf("淘宝"), hideParentLevels = 1,
            climbParentIdNames = listOf("bnr")),
        Rule(Settings.KEY_UI_TMALL_CARD, "发现页天猫活动卡",
            listOf(), listOf("天猫"), hideParentLevels = 1,
            climbParentIdNames = listOf("fmx")),
        Rule(Settings.KEY_UI_PLAY_AD, "播放页广告",
            listOf("PlayAd", "PlayerAd", "SongAd", "PlayPageAd"), listOf()),
        // ⚠️ R79 修正：本条原先挂在 `KEY_UI_PLAY_AD`（去播放页广告）上 —— 结果面板上
        // 「去播放页书签」这个开关**没有任何实现引用 = 死开关**，而书签的实际隐藏被
        // 「去播放页广告」顺带控制（用户点书签开关毫无反应）。现改回它自己的 key。
        Rule(Settings.KEY_UI_PLAY_BOOKMARK, "播放页书签",
            listOf(), listOf(), idNames = listOf("dt7", "elq"), maxYRatio = 0.12f,
            // ⭐ R85b：宿主在预隐藏成功约 343ms 后会把该视图**复活并画出来**（真机实锤），
            // 绘制前兜底因同帧时序漏一帧 ⇒ 开「alpha 保险」（只影响本规则）。
            alphaShield = true),
        // ⚠️ R79 同上：「去每周福利条」也是死开关，实际由「去播放页广告」控制。已改回自己的 key。
        Rule(Settings.KEY_UI_WEEKLY_WELFARE, "每周福利条",
            listOf(), listOf("每周福利"), hideParentLevels = 1),
        // 咱一起自律入口（Round 51，用户要求移除）：播放页歌词下方左侧的
        // 情绪/场景标签胶囊（dq4=LinearLayout 图标+文字容器），文本随歌变化
        // （L-O-V-E 歌显示「咱一起自律」、另曲显示「伤感」）→ 不能按文本匹配，
        // 用 ID 制导 + 高度区间门禁（真机 y≈1833/2712≈0.68）限制到播放页该位置。
        Rule(Settings.KEY_UI_PLAY_DISCIPLINE, "咱一起自律入口",
            listOf(), listOf(), idNames = listOf("dq4"),
            minYRatio = 0.60f, maxYRatio = 0.75f),
        // ⚠️ R66：本规则关键词只有「广告/推广」两个**极通用的短词**，且**既无页面门禁也无 ID 门禁**
        // ⇒ 会在**其它页面**的正文里误命中。真机实锤（flashscan）：冷启留在发现页时，
        // feed 的**推荐语槽位 b5w** 文本为「7 推荐 - 你是那场电影 而我只是那场电影中的那段广告」
        // ⇒ 命中「广告」⇒ 把推荐语 TextView(794x41) 藏掉，且**藏的时候它已经上过屏**
        // （`严格=是`）＝用户看到推荐语闪一下。同一视图一次会话被藏 3 次。
        // 处方同「频道推荐/免费听区块」那两处历史坑（memory：**推荐语是 b5w，无门禁会误伤**）：
        // 排除推荐语槽位。（更彻底的"只在搜索页生效"需要搜索页祖先 ID，留待后续。）
        Rule(Settings.KEY_UI_SEARCH_AD, "搜索推广",
            listOf("SearchAd", "SearchPromo"), listOf("广告", "推广"),
            excludeIdNames = listOf("b5w")),
        Rule(Settings.KEY_UI_SEARCH_BANNER, "搜索推广Banner",
            listOf(), listOf(), idNames = listOf("gu6"),
            minYRatio = 0.20f, hideParentLevels = 2),
        Rule(Settings.KEY_UI_MEMBER_BAR, "会员栏",
            listOf("VipBar", "MemberBar", "VipBanner"), listOf("开通会员", "开通概念版", "即将过期", "自动续费")),
        Rule(Settings.KEY_UI_MEMBER_WIDGET, "会员控件",
            listOf("VipWidget", "MemberWidget", "VipEntry", "TmeMaskAdDialog"),
            listOf("立即开通", "领取会员")),
        Rule(
            Settings.KEY_UI_PLUS, "右下角加号",
            listOf("PlusEntry", "FloatEntry", "PlusButton", "FabEntry", "KGTransImageView"),
            listOf(),
            floatArrayOf(0.78f, 0.60f)
        ),
        Rule(Settings.KEY_UI_INVITE, "邀请提示",
            listOf("InviteTip", "InviteView"), listOf("邀请好友", "三个月会员", "邀好友", "组队领"),
            // ⚠️ ID 门禁（真机实锤 10-12）：feed 推荐语槽位 b5w 的文案可能包含
            // "组队领…VIP"（用户推荐语），无门禁会把**正常推荐卡文案**当成邀请
            // 提示隐藏，且发生在已绘制之后（闪现）。同 b6b/b5w 门禁思路。
            excludeIdNames = listOf("b5w")),
        Rule(Settings.KEY_UI_LAB, "概念实验室",
            listOf("ConceptLab", "Laboratory", "LabEntry"), listOf("概念实验室"),
            sectionMode = true, useCardHide = true),
        Rule(Settings.KEY_UI_LOGIN_GUIDE, "登录引导",
            listOf("LoginGuide", "GuideLogin", "LoginTip"), listOf("登录后畅听", "立即登录")),
        Rule(Settings.KEY_UI_FREE_LISTEN_ENTRY, "免费听入口",
            listOf(), listOf(), idNames = listOf("dka")),
        Rule(Settings.KEY_UI_FREE_LISTEN_ICON, "免费听图标",
            listOf(), listOf(), idNames = listOf("dg0"), maxYRatio = 0.12f),
            // ⚠️ 不再连父藏 dg1（2026-10-15 真机 A/B 实锤）：dg1(HnVisualEffectLayout)
            // 虽然只有 84×84（免图标位），但酷狗效果引擎内部联动——dg1 被藏后
            // **发现页整页背景渐变（含顶栏）消失变纯白**（用户报「顶栏背景不见了」）。
            // R54 会话曾误归因给 unclipContentPadding 并用顶栏玻璃遮住，玻璃移除后
            // 问题重现才定位到真因。只藏 dg0 自身，dg1 残留与否真机验证后再定。
        Rule(Settings.KEY_UI_MINE_TAB_BAR, "我的页分类栏",
            listOf("MineSpecialTabView"), listOf()),
        Rule(Settings.KEY_UI_MINE_EARN, "我的页赚钱入口",
            listOf(), listOf(), idNames = listOf("dk_"),
            // ⭐ R72 定稿：只靠 `ancestorClass` + `requireMinePage` 两道门禁。
            //   真机 dump 实锤（v5.2.9）：发现页顶栏的 dk_（乐库 tab）与我的页顶栏的 dk_
            //   （赚钱入口）**同类同 ID**（都是 MainTopImageView），但**祖先链不同**——
            //   发现页那支是 ConstraintLayout→FrameLayout→MainFragmentViewPage（**无** TwoViewSwitchLayout），
            //   我的页那支挂在 TwoViewSwitchLayout 下。尺寸旁证：102x54 vs 102x144。
            //   ⚠️ R66 曾在此加 `excludeClassNames=["MainTopImageView"]`，是**误诊**：
            //      R66 那条 `严格=是 102x144` 其实是**赚钱入口自己被藏晚了 1 帧**，
            //      被误判成"误伤乐库 tab" ⇒ 按类排除 = 把真目标一起废掉
            //      （用户实锤"我的页顶栏赚钱又出现了"）。已回退。
            //   ⚠️ 同时**去掉 `requireSiblingIdName="dk5"`**：已被 ancestorClass 覆盖，且
            //      **挂载瞬间兄弟 dk5 尚未 addView ⇒ 门禁必失败 ⇒ 只能等扫描才藏 ⇒ 闪现**。
            ancestorClass = "TwoViewSwitchLayout"),
            // ⭐ R74：**去掉 `requireMinePage`**。R72 的 dump 已证明 `ancestorClass` 足以区分
            //   （发现页那支祖先链里没有 TwoViewSwitchLayout）；而页面门禁的副作用是：
            //   **切回发现页时会把它当"门禁失败"还原** ⇒ 赚钱入口被画出来（严格=是 102x144）
            //   ⇒ 下次扫描再藏 ⇒ 闪现。去掉后它挂载即藏、跨页保持，不再有还原动作。
        // 我的页白卡内的两行推广（Round 18 实锤：ist=福利与活动/天天签到兑免费
        // VIP，bsb=免费听/全天会员免费领/领会员；关键词规则只隐了 bsa/c6n 两个
        // TextView，残留「免费听」文字+进度条 = 半隐藏残迹）——按行容器整条塌缩
        Rule(Settings.KEY_UI_MINE_SIGN_BAR, "我的页签到/领会员条",
            listOf(), listOf(), idNames = listOf("ist", "bsb"),
            collapseHeight = true),
        // 会员角标与入口：⚠️ 只藏角标图片 c6u，**不能藏 iv5**——iv5 是白卡
        // 右上角的白色底板（GONE 后露出页面底色 = 白卡右上角缺口，Round 19
        // 用户实锤）；apj=昵称右侧「会员中心」图标（KGTransImageView，desc
        // 命中但不走 setText/TextView 分支，必须 ID 制导）
        Rule(Settings.KEY_UI_MINE_MEMBER_BADGE, "我的页会员角标",
            listOf(), listOf(), idNames = listOf("c6u", "apj")),
        // 账号徽章：c6b=Lv.1 等级容器（iue/iug），ivf=「勋章」胶囊
        Rule(Settings.KEY_UI_MINE_MEDAL, "我的页等级勋章",
            listOf(), listOf(), idNames = listOf("c6b", "ivf")),
        // 我的页右上角搜索图标（Round 26，用户要求移除）：apg 是我的页顶栏
        // 四图标容器（dg0/f5i/c67/f5j = 免|搜索|信息中心|设置），只摘 f5i。
        // ⚠️ 用**父容器 ID 门禁**而非页面门禁：父容器在挂载瞬间就确定 → 绘制
        // 前即时预隐藏，不会像 requireMinePage 那样翻页时先闪一下再藏。
        Rule(Settings.KEY_UI_MINE_SEARCH, "我的页搜索图标",
            listOf(), listOf(), idNames = listOf("f5i"),
            parentIdNames = listOf("apg")),
        // 我的页右上角「信息中心/设置」图标的红点（Round 46，真机 dump 实锤）：
        // 信息中心 c67 的红点 = 无 ID FrameLayout → c6_(红点底 ImageView)+ip9(数字
        // TextView)；设置 f5j 的红点 = f5k(15x15 小圆点)。只藏叶子，图标本体保留。
        // ⚠️ 必须带 apg **祖先**门禁（新 ancestorIdNames）：c6_/ip9/f5k 是通用混淆
        // ID（红点数字/小圆点随处可能复用），且直接父容器都无 ID（父门禁够不着）——
        // 无门禁会全局误伤。
        Rule(Settings.KEY_UI_MINE_BADGE, "我的页顶栏红点",
            listOf(), listOf(), idNames = listOf("c6_", "ip9", "f5k"),
            ancestorIdNames = listOf("apg")),
        Rule(Settings.KEY_UI_TAB_BADGE, "TAB角标",
            listOf(), listOf(), idNames = listOf("at6"), minYRatio = 0.85f),
        Rule(Settings.KEY_UI_SEARCH_CLOSE, "搜索推广关闭钮",
            listOf(), listOf(), idNames = listOf("gu3"), minYRatio = 0.15f,
            hideParentLevels = 1),
        Rule(Settings.KEY_UI_SEARCH_INDICATOR, "搜索推广指示器",
            listOf(), listOf(), idNames = listOf("gu4"), minYRatio = 0.15f),
        Rule(Settings.KEY_UI_SEARCH_VIP_BAR, "搜索结果VIP条",
            listOf(), listOf(), idNames = listOf("ezd"), minYRatio = 0.10f),
        Rule(Settings.KEY_UI_VIP_EXPIRE, "VIP过期胶囊",
            listOf(), listOf(), idNames = listOf("c6a")),
        Rule(Settings.KEY_UI_VIP_LOOK_BAR, "VIP去看看残条",
            listOf(), listOf(), idNames = listOf("f96")),
        Rule(Settings.KEY_UI_PLAY_PUBLISH_TIP, "发布提示",
            listOf(), listOf("使用此音乐发布", "使用此应用发布")),
        Rule(Settings.KEY_UI_CHANNEL, "频道推荐区块",
            listOf(), listOf("频道推荐"), sectionMode = true, useCardHide = true),
        Rule(Settings.KEY_UI_FREE_LISTEN_BLOCK, "免费听区块",
            listOf(), listOf("免费听"), sectionMode = true, useCardHide = true,
            excludeIdNames = listOf("dka")),
        Rule(Settings.KEY_UI_DISCOVER_BANNER_SLOT, "发现页Banner槽位",
            listOf(), listOf(), idNames = listOf("ela")),
    )

    // 广告 SDK 专属视图（出生拦截 + 挂载拦截双保险）
    private val PERSISTENT_HIDE = listOf(
        "com.tencent.ams.mosaic.MosaicView" to Settings.KEY_UI_BANNER,
        "com.tencent.ams.mosaic.MosaicView\$MosaicViewImpl" to Settings.KEY_UI_BANNER,
        "com.tencent.ams.fusion.dynamic.SplashAdDynamicView" to Settings.KEY_UI_SPLASH,
        "com.kugou.android.app.boot.gdt.GdtAdFrame" to Settings.KEY_UI_SPLASH,
        "com.qq.e.comm.plugin.tangramsplash.video.SplashVideoView" to Settings.KEY_UI_SPLASH,
        "com.kugou.android.app.ads.views.DiscoveryAdsView" to Settings.KEY_UI_DISCOVER_ADS,
        "com.kugou.android.app.ads.views.Discovery2AdsView" to Settings.KEY_UI_DISCOVER_ADS,
        "com.kugou.android.app.ads.views.DiscoveryFlowAdsView" to Settings.KEY_UI_DISCOVER_ADS,
        "com.kugou.android.app.ads.views.SkinDiscoveryFlowAdsView" to Settings.KEY_UI_DISCOVER_ADS,
        "com.kugou.android.app.ads.views.DiscoveryFreeModeAdsView" to Settings.KEY_UI_DISCOVER_ADS,
        "com.kugou.android.app.ads.views.NoticeAdsView" to Settings.KEY_UI_DISCOVER_ADS,
        "com.kugou.android.app.ads.views.LowFreeAdsView" to Settings.KEY_UI_DISCOVER_ADS,
        "com.kugou.android.app.ads.views.CommentGDTAdsView" to Settings.KEY_UI_DISCOVER_ADS,
        "com.kugou.android.ads.tme.views.TmePlayerVideoAdsView" to Settings.KEY_UI_PLAY_AD,
        "com.kugou.android.ads.feev4.mvp.FeeV4PlayVideoView" to Settings.KEY_UI_PLAY_AD,
        "com.kugou.android.app.player.view.PlayerAdMaskView" to Settings.KEY_UI_PLAY_AD,
        "com.kugou.android.netmusic.search.banner.BannerHorizontalScrollView" to Settings.KEY_UI_SEARCH_BANNER,
    )

    // 构造/挂载拦截时需连外层容器一起隐藏的类 → 爬升层数
    private val PERSISTENT_CLIMB = mapOf(
        "com.kugou.common.base.SlowSmoothViewPager" to 2,
        "com.tencent.ams.mosaic.MosaicView" to 1,
        "com.tencent.ams.mosaic.MosaicView\$MosaicViewImpl" to 1,
        "com.kugou.android.app.ads.views.DiscoveryAdsView" to 1,
        "com.kugou.android.app.ads.views.Discovery2AdsView" to 1,
        "com.kugou.android.app.ads.views.DiscoveryFlowAdsView" to 1,
        "com.kugou.android.app.ads.views.SkinDiscoveryFlowAdsView" to 1,
        "com.kugou.android.app.ads.views.DiscoveryFreeModeAdsView" to 1,
        "com.kugou.android.app.ads.views.NoticeAdsView" to 1,
        "com.kugou.android.app.ads.views.LowFreeAdsView" to 1,
        "com.kugou.android.ads.tme.views.TmePlayerVideoAdsView" to 1,
        "com.kugou.android.ads.feev4.mvp.FeeV4PlayVideoView" to 1,
    )

    private val labRule by lazy { RULES.first { it.key == Settings.KEY_UI_LAB } }
    private val plusRule by lazy { RULES.first { it.key == Settings.KEY_UI_PLUS } }
    private val tabBadgeRule by lazy { RULES.first { it.key == Settings.KEY_UI_TAB_BADGE } }
    private fun ruleFor(featureKey: String): Rule = RULES.first { it.key == featureKey }
    private val freeListenRule by lazy { RULES.first { it.key == Settings.KEY_UI_FREE_LISTEN_ENTRY } }
    private val freeListenBlockRule by lazy { RULES.first { it.key == Settings.KEY_UI_FREE_LISTEN_BLOCK } }
    private val channelRule by lazy { RULES.first { it.key == Settings.KEY_UI_CHANNEL } }
    private val memberBarRule by lazy { RULES.first { it.key == Settings.KEY_UI_MEMBER_BAR } }
    /** 文本关键词规则预筛表（挂载/文本钩子热路径用；避免每次遍历全部规则） */
    private val TEXT_RULES by lazy {
        RULES.filter { it.textKeywords.isNotEmpty() && !it.sectionMode && !it.useCardHide }
    }

    /** 文本关键词规则全集（scan 用；含 sectionMode / useCardHide 的区块规则） */
    private val TEXT_SCAN_RULES by lazy { RULES.filter { it.textKeywords.isNotEmpty() } }

    /** 带类名关键词的规则（scan 用；配合 classRulesFor 记忆化） */
    private val CLASS_RULES by lazy { RULES.filter { it.classKeywords.isNotEmpty() } }

    /**
     * className → 命中的类名规则（按 Class 记忆化）。
     *
     * ⚠️ 这是本轮最大的掉帧优化：`className.contains(kw, ignoreCase = true)` 是
     * 大小写不敏感子串搜索，成本远超普通比较。原 `scan` 对**每个节点**都把全部
     * 规则跑一遍（`for (rule in RULES)`），一条树上千节点 × 数十条规则 = 十万次
     * 级搜索，每 4s 一次、启动窗口内**每帧**一次 → 主线程持续被占满。
     * 同一个 Class 的类名判定结果恒定，记忆化后每类只算一次，其余为 map 命中。
     */
    private val classRuleCache = ConcurrentHashMap<String, List<Rule>>()

    private fun classRulesFor(className: String): List<Rule> {
        classRuleCache[className]?.let { return it }
        val hit = CLASS_RULES.filter { r -> r.classKeywords.any { className.contains(it, ignoreCase = true) } }
        if (classRuleCache.size < 4096) classRuleCache[className] = hit
        return hit
    }

    /** 「右下角加号」类名关键词（结构门禁预隐藏用；与规则定义同一份来源） */
    private val PLUS_CLASS_KEYWORDS by lazy { plusRule.classKeywords }

    /**
     * 类名是否为悬浮按钮类（结果按 Class 缓存）。
     * 真机卡顿实锤：preHideSelf 会被每个可见节点调用，而
     * `className.contains(kw, ignoreCase=true) × 6` 是字符串操作 —— 每帧上千次
     * 就成了掉帧主因。同一个 Class 的判定结果恒定，缓存成 Boolean 即可。
     */
    private val fabClassCache = ConcurrentHashMap<String, Boolean>()

    private fun isFabClass(cn: String): Boolean {
        fabClassCache[cn]?.let { return it }
        val hit = PLUS_CLASS_KEYWORDS.any { cn.contains(it, ignoreCase = true) }
        fabClassCache[cn] = hit
        return hit
    }
    private val MEMBER_PROMO_KEYWORDS = arrayOf(
        "会员中心", "开学季会员限时特惠", "福利与活动", "全天会员免费领", "领会员", "1天概念版VIP"
    )

    // onTextSet 预筛表（热路径：setText 全局最热 hook）
    //
    // ⚠️ 零闪现关键（真机 10-12）：预筛集合必须**覆盖全部规则关键词的首字**。
    // 漏字的后果：该文本在 setText 瞬间被跳过 → 只能等挂载判定/周期扫描
    // （最长 4s）才隐藏 → 用户看到元素"闪一下再消失"。历史硬编码集合漏了
    // 广/推/邀/登/组/三/回/天/全/自 等首字（"广告""推广""邀请好友""回到首页"
    // 全部漏网）。改为从 RULES + MEMBER_PROMO_KEYWORDS **自动派生**，杜绝再漏。
    private val promoCharTable: BooleanArray by lazy {
        val table = BooleanArray(0x10000)
        fun addFirst(s: String) {
            if (s.isNotEmpty()) {
                val c = s[0].code
                if (c in table.indices) table[c] = true
            }
        }
        for (r in RULES) for (k in r.textKeywords) addFirst(k)
        for (k in MEMBER_PROMO_KEYWORDS) addFirst(k)
        for (c in '0'..'9') table[c.code] = true
        // 兜底：历史集合字符（部分命中走 ID 门禁/后缀判定，首字仍需放行）
        for (c in "概即会免使频去领签福开赠充续立每音来听") table[c.code] = true
        table
    }

    /** 文本是否可能命中任意规则（查表 O(n)，热路径） */
    private fun mayBePromo(t: CharSequence): Boolean {
        for (i in 0 until t.length) {
            val c = t[i].code
            if (c < 0x10000 && promoCharTable[c]) return true
        }
        return false
    }

    private val labVerifyGen = WeakHashMap<View, Int>()
    private val LAB_VERIFY_TICKS = longArrayOf(50, 100, 200, 400, 800)
    private val LAB_TITLE_TICKS = longArrayOf(100, 200, 400)
    private const val SKIP_TEXT = "跳过"

    fun install(xposed: XposedModule, classLoader: ClassLoader) {
        try {
            val onResume = Reflector.findMethod(Activity::class.java, "onResume")
            if (onResume == null) {
                ModuleLog.log("UI净化引擎安装失败: 未找到方法")
                return
            }
            xposed.hook(onResume)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val activity = chain.thisObject as? Activity
                        if (activity != null && TARGET_PACKAGE == activity.packageName) {
                            currentActivity = WeakReference(activity)
                            scheduleScan(activity)
                            // 零闪现保险：启动窗口内逐帧"画前扫描"
                            try { attachPreDrawScan(activity) } catch (_: Throwable) {}
                            // 频道手势拦截延迟挂载：MediaActivity 运行时类只在
                            // 首次 resume 时可可靠 hook（install 时机太早 hook 不生效）
                            ChannelGestureBlocker.currentActivityRef = WeakReference(activity)
                            try { ChannelGestureBlocker.ensureMediaHook(activity) } catch (_: Throwable) {}
                        }
                    } catch (_: Exception) {
                    }
                    result
                }
            ModuleLog.log("UI净化引擎安装成功")
            installPersistentHide(xposed, classLoader)
            installAttachDetachHooks(xposed)
            installAddViewHook(xposed)
            installInnerAddHooks(xposed)
            installInflateHook(xposed)
            installSetIdHook(xposed)
            installTextHook(xposed)
            installVisibilityHook(xposed)
            installPromoPageBlocker(xposed, classLoader)
            installMainPagerTracker(xposed, classLoader)
            startPeriodicScan()
        } catch (e: Exception) {
            ModuleLog.log("UI净化引擎安装失败: $e")
        }
    }

    // ID 制导规则挂载复核节奏
    private val ID_RULE_RECHECK_TICKS = longArrayOf(50, 150, 300, 600, 1000)

    // ID→规则 预索引（挂载钩子热路径 O(1) 查表）
    private val idRuleMap: Map<String, List<Rule>> = buildMap {
        for (rule in RULES) {
            for (id in rule.idNames) {
                put(id, (get(id) ?: emptyList()) + rule)
            }
        }
    }

    // ===== 挂载复核统一调度器 =====
    private data class RecheckTask(val view: View, val rule: Rule, val atUptime: Long, val tick: Long)

    private val recheckQueue = PriorityQueue<RecheckTask>(compareBy { it.atUptime })
    private val recheckSeen = HashSet<Triple<View, Rule, Long>>()
    private var recheckPumpRunning = false

    private fun scheduleIdRecheck(view: View, rule: Rule, delay: Long) {
        val key = Triple(view, rule, delay)
        synchronized(recheckSeen) {
            if (!recheckSeen.add(key)) return
        }
        synchronized(recheckQueue) {
            recheckQueue.offer(RecheckTask(view, rule, SystemClock.uptimeMillis() + delay, delay))
        }
        pumpRechecks()
    }

    private fun pumpRechecks() {
        if (recheckPumpRunning) return
        recheckPumpRunning = true
        mainHandler.post(object : Runnable {
            override fun run() {
                try {
                    val now = SystemClock.uptimeMillis()
                    synchronized(recheckQueue) {
                        while (recheckQueue.isNotEmpty() && recheckQueue.peek()!!.atUptime <= now) {
                            val task = recheckQueue.poll()!!
                            synchronized(recheckSeen) {
                                recheckSeen.remove(Triple(task.view, task.rule, task.tick))
                            }
                            executeIdRecheck(task.view, task.rule)
                        }
                    }
                    val nextAt = synchronized(recheckQueue) { recheckQueue.peek()?.atUptime }
                    if (nextAt != null) {
                        mainHandler.postDelayed(this, (nextAt - SystemClock.uptimeMillis()).coerceAtLeast(1))
                        return
                    }
                } catch (_: Exception) {
                }
                recheckPumpRunning = false
            }
        })
    }

    /** 挂载多帧复核任务体 */
    private fun executeIdRecheck(view: View, rule: Rule) {
        try {
            if (!view.isAttachedToWindow) return
            if (!matchIdRule(view, rule)) {
                // ⭐ R74：**布局未稳定时不许还原**。
                // 机理（真机实锤）：几何门禁（maxYRatio/region 等）在"还没量出尺寸或刚挂载
                // 位置未定"时会**瞬时判失败** ⇒ 这里把已经藏好的视图还原 ⇒ 它被画出来
                // （严格=是 = 用户看到闪一下）⇒ 下一次扫描又藏回去 = **藏→还原→画→再藏** 的闪现链。
                // 挂载期本来就是"先藏、后复核"，复核这一步必须等量出尺寸再采信。
                if (view.width <= 0 || view.height <= 0) return
                if (hiddenByUs[view] === rule && view.visibility != View.VISIBLE) {
                    restoreAlphaShield(view)
                    hiddenByUs.remove(view)
                    invisibleHides.remove(view)
                    view.visibility = View.VISIBLE
                    // 还原必须留痕，否则"谁把它放回来的"永远查不到（R74 补）
                    ModuleLog.log(
                        "UI净化[复核还原] ${rule.name} ${view.javaClass.simpleName}" +
                            " ${view.width}x${view.height} v=${Integer.toHexString(view.hashCode())}"
                    )
                }
                return
            }
            if (rule.name == "发现页Banner槽位") {
                hideFeedItemSlot(view, rule)
                return
            }
            hideByIdRule(view, rule, "recheck")
        } catch (_: Exception) {
        }
    }

    /**
     * 主页面翻页跟踪（只读不改参数）：主 pager（发现/我的）任何翻页动作后
     * 重评页面门禁 + 重扫——保证「发现↔我的」切换时 dk_（乐库/赚钱）身份
     * 立刻跟着换。参数一个都不改（主 pager W(1)=我的 tab 绝不能拦，19:41
     * 踩过坑），ExceptionMode.PROTECTIVE 保证异常不外溢。
     */
    private var mainPagerHookInstalled = false

    private fun installMainPagerTracker(xposed: XposedModule, classLoader: ClassLoader) {
        if (mainPagerHookInstalled) return
        try {
            val cls = Class.forName("com.kugou.common.base.ViewPager", false, classLoader)
            val methods = LinkedHashSet<Method>()
            var c: Class<*>? = cls
            while (c != null && c != Any::class.java) {
                for (m in c.declaredMethods) {
                    if (m.parameterTypes.isEmpty()) continue
                    if (m.parameterTypes[0] != Int::class.javaPrimitiveType) continue
                    if (m.name == "W" || m.name == "setCurrentItem" || m.name == "setCurrentItemInternal") {
                        methods.add(m)
                    }
                }
                c = c.superclass
            }
            if (methods.isEmpty()) {
                ModuleLog.log("主页面跟踪: 未找到翻页方法，跳过")
                return
            }
            for (m in methods) {
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        // 0) 目标页预置：点「我的」时**动画开始前**就把屏幕外那份
                        //    dk_（即将滑入的我的页实例）隐藏 —— 根治"赚钱闪一下"
                        try {
                            val target = chain.getArg(0) as? Int
                            if (target != null && target != 0) preHideOffscreenGated()
                        } catch (_: Throwable) {
                        }
                        // 记录主 pager 当前页（页面身份判定的确定性依据）
                        try {
                            val pager = chain.thisObject
                            val target0 = chain.getArg(0) as? Int
                            if (pager != null && target0 != null
                                && pager.javaClass.name.contains("MainFragmentViewPage")
                            ) {
                                if (GATE_DIAG && (!mainPageKnown || mainPageIndex != target0)) {
                                    ModuleLog.log("主页面索引: known=$mainPageKnown $mainPageIndex -> $target0")
                                }
                                mainPageIndex = target0
                                mainPageKnown = true
                            }
                        } catch (_: Throwable) {
                        }
                        val r = chain.proceed()
                        // ⭐ Round 27：宿主在翻页后会重设底栏定高子视图的
                        // LayoutParams（fkf/asx/fkb 全跑偏，播放钮被裁）。
                        // 本钩子跑在宿主 relayout **之前**，故 post 到下一帧
                        // 再补救；150ms 再补一次兜住动画期的二次重排。
                        try {
                            mainHandler.post { FloatingTabBar.reapplyIfBroken() }
                            mainHandler.postDelayed(
                                { FloatingTabBar.reapplyIfBroken() }, 150
                            )
                        } catch (_: Throwable) {
                        }
                        try {
                            val item = chain.getArg(0) as? Int
                            if (item != null) {
                                if (item == 0) {
                                    // 目标=发现页：**立刻恢复**，不要再判隐藏。
                                    // 真机实锤（10-12）：离开我的页时其页面 x 仍≈0，
                                    // 门禁仍成立 → applyGatedHidesNow 会多隐藏一次
                                    // 顶栏图标，约 630ms 后才被恢复 = 用户看到闪一下。
                                    reevaluateGatedHides("翻-发现")
                                    mainHandler.postDelayed({
                                        currentActivity?.get()?.let { a ->
                                            if (!a.isFinishing && !a.isDestroyed) safeScan(a)
                                        }
                                    }, 600)
                                } else {
                                    // 1) 同步门禁判定：切页后立刻隐藏本页该隐藏的
                                    applyGatedHidesNow()
                                    // 2) 动画期补判（只走门禁规则，代价极小）
                                    mainHandler.postDelayed({ applyGatedHidesNow() }, 120)
                                    mainHandler.postDelayed({ applyGatedHidesNow() }, 350)
                                    // 3) 只保留一次延迟全量复核
                                    mainHandler.postDelayed({
                                        reevaluateGatedHides("翻-我的")
                                        currentActivity?.get()?.let { a ->
                                            if (!a.isFinishing && !a.isDestroyed) safeScan(a)
                                        }
                                    }, 600)
                                }
                            }
                        } catch (_: Throwable) {
                        }
                        r
                    }
            }
            mainPagerHookInstalled = true
            ModuleLog.log("主页面翻页跟踪安装成功(${methods.size} 个方法)")
        } catch (t: Throwable) {
            ModuleLog.log("主页面翻页跟踪安装失败: $t")
        }
    }

    private fun startPeriodicScan() {
        if (!PERIODIC_SCAN_ENABLED) {
            ModuleLog.log("UI净化[诊断] 周期巡检已关闭（PERIODIC_SCAN_ENABLED=false）")
            return
        }
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    val a = currentActivity?.get()
                    if (a != null && !a.isFinishing && !a.isDestroyed) {
                        // 巡检临时关闭（Round 18）：每 20s 刷 600 行把启动/隐藏
                        // 日志滚出 445 行上限，诊断期不可用；需要时再打开
                        run {
                            val __tg = PerfProbe.start()
                            reevaluateGatedHides("周期4s")
                            PerfProbe.end(PerfProbe.GATED, __tg)
                        }
                        safeScan(a)
                    }
                } catch (_: Exception) {
                }
                mainHandler.postDelayed(this, 4000)
            }
        }, 4000)
    }

    // ===== 以下方法由恢复任务追加 =====

    // ===== setText 零闪现拦截 =====

    @Volatile
    private var textHookInstalled = false

    /** Hook TextView.setText(CharSequence, BufferType)：文本写入瞬间按关键词判定并隐藏 */
    private fun installTextHook(xposed: XposedModule) {
        if (textHookInstalled) return
        textHookInstalled = true
        try {
            val m = TextView::class.java.getDeclaredMethod(
                "setText", CharSequence::class.java, TextView.BufferType::class.java
            )
            xposed.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val v = chain.thisObject as? TextView
                        val t = chain.getArg(0) as? CharSequence
                        if (v != null && t != null && t.isNotEmpty()) {
                            onTextSet(v, t)
                        }
                    } catch (_: Throwable) {
                    }
                    result
                }
            ModuleLog.log("UI净化: setText 拦截安装成功(零闪现)")
        } catch (t: Throwable) {
            ModuleLog.log("UI净化: setText 拦截安装失败(降级): $t")
        }
    }

    /** setText 关键词判定（每段命中即 return，见各分支注释） */
    private fun onTextSet(v: TextView, t: CharSequence) {
        // 1. 构造期无父容器：无人恢复，一律跳过
        if (v.parent == null) return

        // 2. 复用恢复安全网：规则不再匹配时恢复显示
        val held = hiddenByUs[v]
        if (held != null && v.visibility != View.VISIBLE && !ruleStillMatches(v, held)) {
            restoreAlphaShield(v)
            hiddenByUs.remove(v)
            invisibleHides.remove(v)
            v.visibility = View.VISIBLE
            return
        }

        // 3. 预筛：整段文本不含任何规则关键词首字则直接返回（热路径快速剔除）
        //    ⚠️ 表由 RULES 自动派生（见 promoCharTable），保证不漏关键词
        if (t.isNotEmpty() && !mayBePromo(t)) return

        val isOwn = isOwnPanel(v)

        // 4. TAB角标
        if (Settings.isFeature(v.context, Settings.KEY_UI_TAB_BADGE) && !isOwn && resourceName(v) == "at5") {
            val parent = v.parent as? View
            if (parent != null && resourceName(parent) == "at6") {
                if (parent.visibility != View.GONE) {
                    parent.visibility = View.GONE
                    hiddenByUs[parent] = tabBadgeRule
                    ModuleLog.log("UI净化[TAB角标] 隐藏父容器(at6)")
                }
            } else if (v.visibility != View.GONE) {
                v.visibility = View.GONE
                hiddenByUs[v] = tabBadgeRule
                ModuleLog.log("UI净化[TAB角标] 隐藏角标自身")
            }
            return
        }

        // 5. 概念实验室
        if (t.contains("概念实验室") && Settings.isFeature(v.context, Settings.KEY_UI_LAB) && !isOwn) {
            hideCardWithRecheck(v, labRule)
            return
        }

        // 6. 会员栏
        if (Settings.isFeature(v.context, Settings.KEY_UI_MEMBER_BAR)) {
            if (t.contains("即将过期")) {
                if (v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    hiddenByUs[v] = memberBarRule
                }
                return
            }
            if (!isOwn && MEMBER_PROMO_KEYWORDS.any { t.contains(it) }) {
                hideMemberCard(v, memberBarRule)
                mainHandler.postDelayed({ v.parent?.let { scheduleMemberPass(v.context) } }, 60)
                return
            }
        }

        // 7. 免费听入口（ID 制导）
        if (Settings.isFeature(v.context, Settings.KEY_UI_FREE_LISTEN_ENTRY) && !isOwn
            && resourceName(v) == "dka" && t.contains("免费听")
        ) {
            hideMemberCard(v, freeListenRule)
            return
        }

        // 8. 免费听区块（⚠️ ID 门禁 b6b：推荐语含"免费听"字样时不能误伤推荐卡）
        if (Settings.isFeature(v.context, Settings.KEY_UI_FREE_LISTEN_BLOCK) && !isOwn
            && t.contains("免费听") && resourceName(v) == "b6b"
        ) {
            hideCardWithRecheck(v, freeListenBlockRule)
            return
        }

        // 9. 播放页发布提示
        if (Settings.isFeature(v.context, Settings.KEY_UI_PLAY_PUBLISH_TIP) && !isOwn
            && (t.contains("使用此音乐发布") || t.contains("使用此应用发布"))
        ) {
            if (v.visibility != View.GONE) {
                v.visibility = View.GONE
                hiddenByUs[v] = ruleFor(Settings.KEY_UI_PLAY_PUBLISH_TIP)
            }
            return
        }

        // 10. 频道推荐（⚠️ ID 门禁 b6b：推荐语含"频道推荐"字样时不能误伤整个推荐卡）
        if (Settings.isFeature(v.context, Settings.KEY_UI_CHANNEL) && !isOwn
            && t.contains("频道推荐") && resourceName(v) == "b6b"
        ) {
            hideCardWithRecheck(v, channelRule)
            return
        }

        // 11. 概念实验室"去创作"按钮
        if (Settings.isFeature(v.context, Settings.KEY_UI_LAB) && !isOwn
            && t.toString().trim() == "去创作"
        ) {
            if (resourceName(v) == "yk") {
                if (v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    hiddenByUs[v] = labRule
                }
            } else {
                ModuleLog.log("UI净化[实验室] 去创作按钮(非yk id) 跳过: ${resourceName(v)}")
            }
            return
        }
    }

    private fun scheduleScan(activity: Activity) {
        for (delay in longArrayOf(400, 1500, 4000, 8000, 15000)) {
            mainHandler.postDelayed({ safeScan(activity) }, delay)
        }
    }

    // 白屏恢复防抖（每次触发只自动点一次，5s 后重置）
    private var blankRecoverDone = false

    // 悬浮底栏扫描节流时间戳
    private var lastBarTickAt = 0L

    /** 每次新手势（DOWN）时重置——确保白屏后下一次 safeScan 必定触发恢复 */
    fun resetBlankRecover() {
        blankRecoverDone = false
    }

    private fun safeScan(activity: Activity) {
        val __tSafe = PerfProbe.start()
        try {
            if (activity.isFinishing || activity.isDestroyed) return
            val decor = activity.window?.decorView ?: return
            val hidden = IntArray(1)
            val now = android.os.SystemClock.uptimeMillis()
            // 频道预览白屏自恢复：下拉被拦后酷狗可能把发现页视图隐藏（留白屏）。
            // ⚠️ 限流（本轮）：白屏是**持续状态**，不需要每次扫描（含启动期每帧）
            // 都做一次全树遍历判定 —— 8s 一次足够，省下大量主线程时间。
            if (now - lastBlankProbeAt > 8000L) {
                lastBlankProbeAt = now
                try {
                    val __tb = PerfProbe.start()
                    autoRecoverBlankDiscover(decor, activity)
                    PerfProbe.end(PerfProbe.BLANK, __tb)
                } catch (_: Throwable) {}
            }
            // 悬浮底栏（Round 24）：未改造时 500ms 一次尝试定位；已改造则 4s 一次存活校验。
            // 关掉开关立即还原宿主原样。
            if (Settings.isFeature(activity, Settings.KEY_UI_FLOATING_BAR)) {
                val due = now - lastBarTickAt > (if (FloatingTabBar.isApplied()) 4000L else 500L)
                if (due) {
                    lastBarTickAt = now
                    try {
                        if (FloatingTabBar.isApplied()) FloatingTabBar.verify()
                        else FloatingTabBar.ensure(activity, decor)
                    } catch (_: Throwable) {
                    }
                }
            } else if (FloatingTabBar.isApplied()) {
                try { FloatingTabBar.restore() } catch (_: Throwable) {}
            }
            val t0 = System.nanoTime()
            scan(decor, activity, hidden, 0)
            PerfProbe.add(PerfProbe.SCAN, System.nanoTime() - t0)
            recordScanCost(System.nanoTime() - t0, now)
            // 开屏广告只在启动期出现 —— 非启动窗口不再为此做全树遍历
            if (now <= preDrawUntilUptime && Settings.isFeature(activity, Settings.KEY_UI_SPLASH)) {
                tryAutoClickSkip(decor)
            }
            if (Settings.isFeature(activity, Settings.KEY_UI_FREE_LISTEN_BLOCK)) {
                val __tf = PerfProbe.start()
                findFreeListenTexts(decor, 0)
                PerfProbe.end(PerfProbe.FREE_LISTEN, __tf)
            }
        } catch (_: Exception) {
        }
        PerfProbe.end(PerfProbe.SAFE_SCAN, __tSafe)
        PerfProbe.tick()
        CardProbe.tick()
    }

    /** 白屏探测限流时间戳（见 safeScan） */
    private var lastBlankProbeAt: Long = 0L

    // ===== 引擎耗时统计 =====
    //
    // 每次全树扫描前后各取一次 nanoTime（成本 ~几十纳秒，相对扫描本身可忽略），
    // 每分钟汇总一条日志。用于长期观测净化引擎对宿主主线程的真实占用，
    // 也为"掉帧是否由本引擎引起"提供一线证据（扫描耗时若 <1ms 而仍掉帧，
    // 应怀疑宿主自身或其它模块）。
    private var scanCount = 0L
    private var scanTotalNs = 0L
    private var scanMaxNs = 0L
    private var perfReportAt = 0L
    private const val PERF_REPORT_INTERVAL_MS = 60_000L

    private fun recordScanCost(costNs: Long, now: Long) {
        scanCount++
        scanTotalNs += costNs
        if (costNs > scanMaxNs) scanMaxNs = costNs
        if (now - perfReportAt < PERF_REPORT_INTERVAL_MS) return
        perfReportAt = now
        if (scanCount <= 0L) return
        val avgMs = scanTotalNs.toDouble() / scanCount / 1_000_000.0
        val maxMs = scanMaxNs.toDouble() / 1_000_000.0
        ModuleLog.log(
            "UI净化[性能] 全树扫描 $scanCount 次 / 平均 ${
                String.format(java.util.Locale.US, "%.2f", avgMs)
            }ms / 峰值 ${String.format(java.util.Locale.US, "%.2f", maxMs)}ms"
        )
        scanCount = 0L
        scanTotalNs = 0L
        scanMaxNs = 0L
    }

    /**
     * 频道预览白屏自恢复：可见 TextView ≤2 且"发现"tab 存在 → 视为白屏，
     * 对 tab 父容器 performClick 回切发现页（tab 点击不走 BACK 链路，无风险）。
     */
    private fun autoRecoverBlankDiscover(decor: View, activity: Activity) {
        if (blankRecoverDone) return
        if (activity.javaClass.name != "com.kugou.android.app.MediaActivity") return
        var visibleTexts = 0
        var hasDiscoverTab = false
        var onChannelPage = false
        var tabView: View? = null
        var tabBar: View? = null
        fun walk(v: View?) {
            if (v == null || visibleTexts > 8) return
            // ⚠️ 不对 GONE 节点剪枝：白屏时底栏（含 at0）整体 GONE，
            // 剪枝会让 hasDiscoverTab 永远 false → 白屏检测失效。
            // GONE 子树仍要遍历（找 at0/回到首页 判定用）
            if (v.visibility == View.VISIBLE) {
                if (v is TextView && v.text?.isNotEmpty() == true && v.width > 0) visibleTexts++
            }
            if (v is TextView && v.text == "回到首页") onChannelPage = true
            if (v.id != View.NO_ID) {
                // Round 50：改走带记忆化的 resourceName——此处原是绕过缓存的直调，
                // 白屏自恢复虽低频，但与热路径共用一套资源名查表更一致
                val rn = resourceName(v)
                if (rn == "at0") { hasDiscoverTab = true; tabView = v.parent as? View }
                // 底栏本体（tab 容器）——仅用于兜底计算点击坐标
                if (rn == "asx") tabBar = v
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) walk(v.getChildAt(k))
        }
        walk(decor)
        // 白屏/频道页判定：发现 tab（at0）不可见且"回到首页"可见 = 在频道页；
        // 或可见文本极少（纯白屏）
        if ((onChannelPage && !hasDiscoverTab) || (visibleTexts <= 2 && hasDiscoverTab)) {
            blankRecoverDone = true
            val (tx, ty) = discoverTapPoint(tabView, tabBar)
            ModuleLog.log("UI净化[频道] 检测到频道页/白屏（texts=$visibleTexts backHome=$onChannelPage），注入发现 tab 点击恢复 @(${tx.toInt()},${ty.toInt()})")
            // 注入 Motionevent 到 decorView（等效 adb tap，之前 tabView.performClick
            // 无效——白屏时酷狗的 tab 容器状态异常，必须走完整分发链）
            val downTime = android.os.SystemClock.uptimeMillis()
            val evDown = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, tx, ty, 0)
            val evUp = MotionEvent.obtain(downTime, downTime + 80,
                MotionEvent.ACTION_UP, tx, ty, 0)
            decor.dispatchTouchEvent(evDown)
            decor.dispatchTouchEvent(evUp)
            evDown.recycle()
            evUp.recycle()
            mainHandler.postDelayed({ blankRecoverDone = false }, 5000)
        }
    }

    /**
     * 计算「发现」tab 的点击坐标。
     *
     * ⚠️ Round 24：原实现硬编码 `(306f, 2515f)`，这在底栏被改成**贴底胶囊**
     * （`FloatingTabBar`，左右各内缩 16dp、高度 62dp）之后 x/y 全变，必须动态算。
     * 优先级：发现 tab 自身中心 → tab 容器左半区中心 → 原硬编码常量兜底。
     * 视图未布局（白屏时整条底栏可能 GONE，宽高为 0）才回落到常量。
     */
    private fun discoverTapPoint(tab: View?, bar: View?): Pair<Float, Float> {
        val loc = IntArray(2)
        if (tab != null && tab.width > 0 && tab.height > 0) {
            tab.getLocationOnScreen(loc)
            if (loc[1] > 0) return (loc[0] + tab.width / 2f) to (loc[1] + tab.height / 2f)
        }
        if (bar != null && bar.width > 0 && bar.height > 0) {
            bar.getLocationOnScreen(loc)
            if (loc[1] > 0) return (loc[0] + bar.width * 0.25f) to (loc[1] + bar.height / 2f)
        }
        return 306f to 2515f
    }

    /**
     * 递归扫描 View 树按规则隐藏。
     *
     * ⚠️ 性能结构（本轮重构）：原实现对**每个节点**都遍历全部规则（约 30 条），
     * 每条都先做 `className.contains(kw, ignoreCase = true)`——大小写不敏感子串
     * 搜索，一条树上千节点 × 数十条 = 十万次级搜索；加上每节点重复
     * `resourceName`/`extractText`，单次扫描即数十毫秒。现改为**按来源收窄候选**：
     *   ① 类名规则 —— classRulesFor(className) 记忆化，未命中直接 0 条；
     *   ② ID 规则   —— idRuleMap O(1) 查表；
     *   ③ 文本规则 —— 仅 TextView + mayBePromo 预筛后线性匹配。
     * 单个节点通常只有 0~2 条候选，判定成本下降一个数量级，语义与原先完全一致。
     */
    private fun scan(view: View?, activity: Activity, hidden: IntArray, depth: Int) {
        if (view == null || hidden[0] > 200 || depth > MAX_DEPTH) return
        val className = view.javaClass.name
        val rn = if (view.id != View.NO_ID) resourceName(view) else null
        val text = if (view is TextView) extractText(view) else null
        var stop = false

        // ① 类名关键词规则（按 Class 记忆化）
        val byClass = classRulesFor(className)
        for (i in byClass.indices) {
            if (hidden[0] > 200) return
            if (applyRuleToView(view, byClass[i], className, rn, text, activity, hidden)) {
                stop = true; break
            }
        }

        // ② ID 制导规则（O(1) 查表）
        if (!stop && rn != null) {
            val byId = idRuleMap[rn]
            if (byId != null) {
                for (i in byId.indices) {
                    if (hidden[0] > 200) return
                    if (applyRuleToView(view, byId[i], className, rn, text, activity, hidden)) {
                        stop = true; break
                    }
                }
            }
        }

        // ③ 文本关键词规则（预筛后匹配；已在①处理过的规则不重复跑）
        if (!stop && text != null && TEXT_SCAN_RULES.isNotEmpty() && mayBePromo(text)) {
            for (i in TEXT_SCAN_RULES.indices) {
                if (hidden[0] > 200) return
                val rule = TEXT_SCAN_RULES[i]
                if (rule.textKeywords.none { text.contains(it) }) continue
                if (byClass.isNotEmpty() && byClass.contains(rule)) continue
                if (applyRuleToView(view, rule, className, rn, text, activity, hidden)) {
                    stop = true; break
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) scan(view.getChildAt(i), activity, hidden, depth + 1)
        }
    }

    /**
     * 单节点 × 单规则的完整判定与隐藏（原 scan 循环体，抽取复用）。
     *
     * @param rn   已算好的资源名（调用方已按 view.id 判定，可能 null）
     * @param text 已算好的文本（非 TextView 为 null）
     * @return true 表示"命中了模块自身面板，本视图应停止后续规则判定"
     */
    private fun applyRuleToView(
        view: View, rule: Rule, className: String, rn: String?, text: CharSequence?,
        activity: Activity, hidden: IntArray
    ): Boolean {
        if (!Settings.isFeature(activity, rule.key)) return false
        var matched = false
        for (kw in rule.classKeywords) {
            if (className.contains(kw, ignoreCase = true)) {
                if (rule.name == "右下角加号" && !inMainContainer(view)) continue
                matched = true; break
            }
        }
        if (!matched && rule.idNames.isNotEmpty()) {
            if (rn != null && rn in rule.idNames) matched = true
        }
        if (!matched && rule.textKeywords.isNotEmpty() && text != null) {
            if (rn == null || rn !in rule.excludeIdNames) {
                for (kw in rule.textKeywords) { if (text.contains(kw)) { matched = true; break } }
            }
        }
        if (!matched) return false
        if (rule.region != null) {
            val dm = view.resources.displayMetrics
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            matched = loc[0] > dm.widthPixels * rule.region[0] && loc[1] > dm.heightPixels * rule.region[1]
        }
        // 类名排除门禁（如顶栏 MainTopImageView 不能被"我的页赚钱入口"命中）
        if (rule.excludeClassNames.isNotEmpty()
            && rule.excludeClassNames.any { className.contains(it) }
        ) {
            matched = false
        }
        if (matched && rule.minWRatio > 0f) {
            matched = view.width >= view.resources.displayMetrics.widthPixels * rule.minWRatio
        }
        if (matched && rule.minYRatio > 0f) {
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            matched = loc[1] >= view.resources.displayMetrics.heightPixels * rule.minYRatio
        }
        if (matched && rule.maxYRatio > 0f) {
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            matched = loc[1] <= view.resources.displayMetrics.heightPixels * rule.maxYRatio
        }
        if (matched && rule.ancestorClass != null) matched = hasAncestor(view, rule.ancestorClass)
        if (matched && rule.ancestorIdNames.isNotEmpty() &&
            !hasAncestorById(view, rule.ancestorIdNames)) matched = false
        if (matched && !gateOk(view, rule)) matched = false
        // 文本触发 + 爬升白名单类规则（淘宝条/天猫卡）：目标是**容器**，文本命中
        // 但直接父不在白名单（= 歌名/推荐语等正文恰好含关键词，或 ID 复用）时
        // 整条放弃——绝不退化成 GONE 文本自身（那会把正文文字藏没）
        if (matched && rule.textKeywords.isNotEmpty() && rule.climbParentIdNames.isNotEmpty()
            && climbLevels(view, rule) == 0) matched = false
        if (!matched) return false
        if (isOwnPanel(view)) return true
        if (rule.sectionMode) {
            // ⚠️ ID 门禁（真机实锤 14:18）：区块标题的 ID 是 b6b（"频道推荐"
            // "私人专属好歌"等区块头共用槽位），歌曲推荐语是 b5w。feed 推荐
            // 语可能含关键词（"免费听新歌"），无门禁会误伤整个推荐卡 item
            // （整卡隐藏 GONE+height=0 → feed 底部大片空缺+分页 footer 错乱）
            if (rn != "b6b") return false
            var did = false
            (view as? TextView)?.let {
                did = if (rule.useCardHide) hideCardWithRecheck(it, rule)
                else { applyTitleRowHide(it, rule); hideLabGridNearTitle(it); true }
            }
            if (did) { hidden[0]++; ModuleLog.log("UI净化[${rule.name}] 标题已隐藏(扫描)") }
            return false
        }
        if (view.visibility != View.GONE || rule.name == "我的页会员角标") {
            if (rule.name == "发现页Banner槽位") {
                if (hideFeedItemSlot(view, rule)) hidden[0]++
            } else if (hideByIdRule(view, rule, "scan")) hidden[0]++
        }
        return false
    }

    /** Hook View.setVisibility：被本模块隐藏的视图压制复活；其它视图设 VISIBLE 时按 invisibleHides 标记压回 */
    private fun installVisibilityHook(xposed: XposedModule) {
        try {
            val m = Reflector.findMethod(View::class.java, "setVisibility", Int::class.javaPrimitiveType) ?: return
            xposed.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val v = chain.thisObject as? View
                    val arg = chain.getArg(0) as? Int
                    if (v != null && arg != null) {
                        if (BADGE_DIAG) diagBadge(v, "setVis=$arg")
                        val held = hiddenByUs[v]
                        if (held != null) {
                            if (arg != View.VISIBLE) {
                                // 记录标记便于区分 INVISIBLE/GONE 两种压制
                                invisibleHides[v] = arg == View.INVISIBLE
                                return@intercept chain.proceed()
                            }
                            // 应用试图复活被隐藏的视图：压回
                            val press = if (invisibleHides[v] == true) View.INVISIBLE else View.GONE
                            return@intercept if (press != arg) press else chain.proceed()
                        }
                        // ⚠️ 零闪现：控件被**设为可见**的瞬间就按规则判定并隐藏
                        // （真机实锤 10-12：底栏角标 at6 的数据回来后才 setVisibility
                        //  VISIBLE，晚于启动窗口 → 只能等周期扫描 = 闪现）。
                        // 先让 VISIBLE 生效再隐藏，本帧即画不出；隐藏会再进本 hook，
                        // 但那时 hiddenByUs 已有记录、arg=GONE，走上面的分支，不会递归。
                        if (arg == View.VISIBLE) {
                            val r = chain.proceed()
                            try {
                                // 自身判定（O(1)）+ **有界**子树遍历：我的页「赚钱」
                                // 入口是靠父容器变可见才画出来的，必须走子树；但预算
                                // 收紧到 100 节点，避免翻页时整树遍历导致掉帧。
                                preHideSelf(v, fromVisibility = true)
                                if (v is ViewGroup && v.childCount > 0) {
                                    val __tv = PerfProbe.start()
                                    preHideTree(v, 0, fromVisibility = true,
                                        budget = IntArray(1).also { it[0] = 32 })
                                    PerfProbe.end(PerfProbe.PRE_TREE, __tv)
                                }
                            } catch (_: Throwable) {
                            }
                            return@intercept r
                        }
                    }
                    chain.proceed()
                }
            ModuleLog.log("UI净化: setVisibility 复活压制安装成功")
        } catch (t: Throwable) {
            ModuleLog.log("UI净化: setVisibility hook 失败(降级): $t")
        }
        // ===== Round 52c：setFlags 可见性压制 =====
        // 新机（PJE110/ColorOS）真机实锤：下拉发现页时宿主**绕过 setVisibility**
        // 直接 View.setFlags(VISIBLE, VISIBILITY_MASK) 复活顶栏「免费听」tab
        // （dka）——setVisibility 钩子全程无调用记录（DIAG 实锤），dka 却在
        // 50ms 内从 GONE 变为可布局参与（扫描救回）；启动窗口（2.5s 画前扫描）
        // 之外的下拉场景无任何拦截 = 「免费听出现又消失」闪现。
        // 补 hook setFlags：mask 含 VISIBILITY_MASK 时按同一套压制逻辑处理。
        // 热路径开销：非可见性调用（DRAW/SCROLL 等 mask）仅一次 int 比较即放行。
        try {
            val mf = Reflector.findMethod(
                View::class.java, "setFlags",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            if (mf != null) {
                // View.VISIBILITY_MASK 为 @hide 常量，取字面量（0x0000001C）
                val visMask = 0x0000001C
                xposed.hook(mf)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val v = chain.thisObject as? View
                        val flags = chain.getArg(0) as? Int
                        val mask = chain.getArg(1) as? Int
                        if (v == null || flags == null || mask == null ||
                            (mask and visMask) == 0
                        ) {
                            return@intercept chain.proceed()
                        }
                        val arg = flags and mask and visMask
                        val held = hiddenByUs[v]
                        if (held != null) {
                            if (arg != View.VISIBLE) {
                                invisibleHides[v] = arg == View.INVISIBLE
                                return@intercept chain.proceed()
                            }
                            // 应用试图复活被隐藏的视图：压回（改写 flags 的可见位）
                            val press = if (invisibleHides[v] == true) View.INVISIBLE else View.GONE
                            if (press == arg) return@intercept chain.proceed()
                            return@intercept (flags and visMask.inv()) or (press and visMask)
                        }
                        if (arg == View.VISIBLE) {
                            val r = chain.proceed()
                            try {
                                preHideSelf(v, fromVisibility = true)
                                if (v is ViewGroup && v.childCount > 0) {
                                    val __tv = PerfProbe.start()
                                    preHideTree(v, 0, fromVisibility = true,
                                        budget = IntArray(1).also { it[0] = 32 })
                                    PerfProbe.end(PerfProbe.PRE_TREE, __tv)
                                }
                            } catch (_: Throwable) {
                            }
                            return@intercept r
                        }
                        chain.proceed()
                    }
                ModuleLog.log("UI净化: setFlags 可见性压制安装成功(R52c)")
            } else {
                ModuleLog.log("UI净化: setFlags 未找到方法(跳过)")
            }
        } catch (t: Throwable) {
            ModuleLog.log("UI净化: setFlags hook 安装失败(降级): $t")
        }
    }

    private fun ruleStillMatches(v: View, rule: Rule): Boolean {
        // ID 制导：ID 名必须仍匹配
        if (rule.idNames.isNotEmpty()) {
            val rn = resourceName(v) ?: return false
            if (rn !in rule.idNames) return false
        }
        // 文本制导：文本仍含关键词（v 自身或子树）
        if (rule.textKeywords.isNotEmpty()) {
            val t = extractText(v)
            val selfHit = t != null && rule.textKeywords.any { t.contains(it) }
            if (!selfHit && !subtreeHasId(v, rule, 0) && !subtreeStillMatches(v, rule, 0)) return false
        }
        if (rule.classKeywords.isNotEmpty()) {
            val cn = v.javaClass.name
            if (rule.classKeywords.none { cn.contains(it, ignoreCase = true) }) return false
        }
        val dm = v.resources.displayMetrics
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        if (v.width == 0 && v.height == 0) return true // 未布局跳过区域判定
        if (rule.minWRatio > 0f && v.width < dm.widthPixels * rule.minWRatio) return false
        if (rule.minYRatio > 0f && loc[1] < dm.heightPixels * rule.minYRatio) return false
        if (rule.maxYRatio > 0f && loc[1] > dm.heightPixels * rule.maxYRatio) return false
        if (rule.region != null && (loc[0] <= dm.widthPixels * rule.region[0]
                    || loc[1] <= dm.heightPixels * rule.region[1])) return false
        if (rule.ancestorClass != null && !hasAncestor(v, rule.ancestorClass)) return false
        if (rule.ancestorIdNames.isNotEmpty() && !hasAncestorById(v, rule.ancestorIdNames)) return false
        return true
    }

    private fun subtreeHasId(v: View?, rule: Rule, depth: Int): Boolean {
        if (v == null || depth > MAX_DEPTH) return false
        val rn = resourceName(v)
        if (rn != null && rn in rule.idNames) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (subtreeHasId(v.getChildAt(i), rule, depth + 1)) return true
        }
        return false
    }

    private fun subtreeStillMatches(v: View?, rule: Rule, depth: Int): Boolean {
        if (v == null || depth > MAX_DEPTH) return false
        val t = extractText(v)
        if (t != null && rule.textKeywords.any { t.contains(it) }) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (subtreeStillMatches(v.getChildAt(i), rule, depth + 1)) return true
        }
        return false
    }

    /** 广告 SDK 专属视图出生拦截：PERSISTENT_HIDE 全表 hook 构造器，实例化即藏 */
    private fun installPersistentHide(xposed: XposedModule, classLoader: ClassLoader) {
        var ok = 0
        for ((cls, key) in PERSISTENT_HIDE) {
            try {
                val c = Class.forName(cls, false, classLoader)
                val rule = ruleFor(key)
                val climb = PERSISTENT_CLIMB[cls] ?: 0
                // 原版行为：hook **全部**声明构造器（广告视图类常无公共无参
                // 构造器，只挑无参会 0/17 全部落空）
                var hooked = 0
                for (ctor in c.declaredConstructors) {
                    ctor.isAccessible = true
                    xposed.hook(ctor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                val v = result as? View
                                if (v != null && Settings.isFeature(v.context, key)) {
                                    val target = if (climb > 0) climbParent(v, climb) else v
                                    if (target.visibility != View.GONE) {
                                        target.visibility = View.GONE
                                        hiddenByUs[target] = rule
                                    }
                                }
                            } catch (_: Throwable) {}
                            result
                        }
                    hooked++
                }
                if (hooked > 0) ok++
            } catch (_: Throwable) {
            }
        }
        ModuleLog.log("UI净化: 广告视图出生拦截安装 $ok/${PERSISTENT_HIDE.size}")
    }

    /** 挂载/分离 hook：所有 View 挂载即按规则预隐藏（零闪现），分离即还原（回收池干净） */
    private fun installAttachDetachHooks(xposed: XposedModule) {
        try {
            val attach = Reflector.findMethod(View::class.java, "onAttachedToWindow")
            val detach = Reflector.findMethod(View::class.java, "onDetachedFromWindow")
            attach?.let {
                xposed.hook(it)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            (chain.thisObject as? View)?.let { v ->
                                // 记录本次挂载时刻：wasDrawn 用它区分"本次真的上过屏"
                                // 与"上一次生命残留的 drawingTime"
                                attachUptime[v] = android.os.SystemClock.uptimeMillis()
                                onViewAttached(v)
                            }
                        } catch (_: Throwable) {}
                        result
                    }
            }
            detach?.let {
                xposed.hook(it)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        try { (chain.thisObject as? View)?.let { v -> onViewDetached(v) } } catch (_: Throwable) {}
                        chain.proceed()
                    }
            }
            if (attach != null && detach != null) ModuleLog.log("UI净化: 挂载/分离 hook 安装成功")
        } catch (t: Throwable) {
            ModuleLog.log("UI净化: 挂载/分离 hook 安装失败: $t")
        }
    }

    // ===== addView 零闪现预隐藏（真机取证 10-12）=====
    //
    // 问题：`View.onAttachedToWindow` 是**基类**方法，自定义控件若重写且不调
    // super，挂载钩子对它永不触发。真机日志实锤：MainTopImageView /
    // KGTransImageView / at6 / dka 等全部**没有**"即时预隐藏(挂载)"记录，
    // 只能等 200~800ms 后的扫描/复核才隐藏 → 取证实测 10 次隐藏里 9 次
    // `已绘制=是`（元素已经上屏过 = 用户看到闪一下）。
    //
    // 解法：hook `ViewGroup.addView(View,int,LayoutParams)` —— 父容器必然调用，
    // 时机比 onAttachedToWindow 更早（此时尚未 measure/draw），在这里按 ID 规则
    // 预隐藏即**真正零闪现**。
    private var addViewHookInstalled = false

    private var innerAddHookInstalled = false

    private fun installAddViewHook(xposed: XposedModule) {
        if (addViewHookInstalled) return
        addViewHookInstalled = true
        try {
            val m = Reflector.findMethod(
                ViewGroup::class.java, "addView",
                View::class.java, Int::class.javaPrimitiveType, ViewGroup.LayoutParams::class.java
            )
            if (m == null) {
                ModuleLog.log("UI净化: addView 预隐藏 hook 未找到方法")
                return
            }
            xposed.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        // ⚠️ 零闪现 + 性能（Round 22）：只对**刚加入的这个节点**做自检，
                        // 不再遍历其子树 —— 子树里的每个后代都是各自 addView 加进来的，
                        // 早已触发同一条 hook 自检过；inflate 出来的子树同理
                        // （LayoutInflater 逐层调用 parent.addView）。
                        // 原实现对每个加入的节点都做一次 24 节点浅遍历 ⇒ 同一棵子树被
                        // addView/inflate/setId 三条钩子重复扫 2~3 次，是启动期挂载路径
                        // 的最大浪费（真机 A/B：见「UI净化[性能]」日志）。
                        (chain.getArg(0) as? View)?.let { child ->
                            // Round 48：addView 时刻也是"本次生命起点"——宿主控件
                            // （KGTransImageView/at6 等）重写 onAttachedToWindow 不调
                            // super ⇒ attach 钩子对它们永不触发 ⇒ attachUptime 无记录
                            // ⇒ wasDrawn 兜底 `?:0L` 把上一次生命残留的 drawingTime
                            // 误判成"已绘制=是"（0x0 的未绘制视图也报"是"）。
                            // addView 覆盖面更广、时刻更早（≤attach<首帧），作为
                            // wasDrawn 基准既不漏报真绘制、也不误报复用残留。
                            attachUptime[child] = android.os.SystemClock.uptimeMillis()
                            val __t = PerfProbe.start()
                            preHideSelf(child, fromMount = true)
                            PerfProbe.end(PerfProbe.PRE_SELF, __t)
                        }
                    } catch (_: Throwable) {
                    }
                    result
                }
            ModuleLog.log("UI净化: addView 预隐藏 hook 安装成功(零闪现)")
        } catch (t: Throwable) {
            ModuleLog.log("UI净化: addView 预隐藏 hook 安装失败: $t")
        }
    }

    private var inflateHookInstalled = false

    /** ⭐ R82：补 hook 两条**此前完全没覆盖的"加入父容器"路径**。
     *
     * 为什么（真机 dump 实锤）：播放页「书签槽」(`dt7`/`elq` 那个 ConstraintLayout)
     * **从未被预隐藏**，而同一时刻它的兄弟（同一条规则命中的 KGUIFrameLayout）被挂载钩子
     * 正常藏掉了 ⇒ 说明该节点走的是**我们没 hook 的加入路径**。
     * 自定义容器（uilib 的 `KGUIFrameLayout` 等）常用 `addViewInLayout`，
     * RecyclerView 复用走 `attachViewToParent` —— 这两条当时都没挂。
     *
     * 处理方式与 addView 完全一致：**只对刚加入的这个节点自检**，
     * ⚠️ 不遍历子树 —— R22 已经量过，"每个节点都做一次浅遍历"会让同一棵子树被三条钩子
     * 重复扫 2~3 次，是启动期挂载路径的最大浪费，当时专门删掉了。
     */
    private fun installInnerAddHooks(xposed: XposedModule) {
        if (innerAddHookInstalled) return
        innerAddHookInstalled = true
        var ok = 0
        // ① ViewGroup.addViewInLayout(View, int, ViewGroup.LayoutParams, boolean)
        try {
            val m = Reflector.findMethod(
                ViewGroup::class.java, "addViewInLayout",
                View::class.java, Int::class.javaPrimitiveType,
                ViewGroup.LayoutParams::class.java, Boolean::class.javaPrimitiveType
            )
            if (m != null) {
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val r = chain.proceed()
                        try {
                            (chain.getArg(0) as? View)?.let { child ->
                                attachUptime[child] = android.os.SystemClock.uptimeMillis()
                                val __t = PerfProbe.start()
                                preHideSelf(child, fromMount = true)
                                PerfProbe.end(PerfProbe.PRE_SELF, __t)
                            }
                        } catch (_: Throwable) {
                        }
                        r
                    }
                ok++
            }
        } catch (_: Throwable) {
        }
        // ② ViewGroup.attachViewToParent(View, int, ViewGroup.LayoutParams)
        try {
            val m2 = Reflector.findMethod(
                ViewGroup::class.java, "attachViewToParent",
                View::class.java, Int::class.javaPrimitiveType, ViewGroup.LayoutParams::class.java
            )
            if (m2 != null) {
                xposed.hook(m2)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val r = chain.proceed()
                        try {
                            (chain.getArg(0) as? View)?.let { child ->
                                attachUptime[child] = android.os.SystemClock.uptimeMillis()
                                val __t = PerfProbe.start()
                                preHideSelf(child, fromMount = true)
                                PerfProbe.end(PerfProbe.PRE_SELF, __t)
                            }
                        } catch (_: Throwable) {
                        }
                        r
                    }
                ok++
            }
        } catch (_: Throwable) {
        }
        ModuleLog.log("UI净化: 内部加入路径钩子安装 $ok/2（addViewInLayout / attachViewToParent）")
    }

    private fun installInflateHook(xposed: XposedModule) {
        if (inflateHookInstalled) return
        inflateHookInstalled = true
        var n = 0
        try {
            val cls = android.view.LayoutInflater::class.java
            val sigs: List<Array<Class<*>?>> = listOf(
                arrayOf(Int::class.javaPrimitiveType, ViewGroup::class.java, Boolean::class.javaPrimitiveType),
                arrayOf(Int::class.javaPrimitiveType, ViewGroup::class.java),
                arrayOf(org.xmlpull.v1.XmlPullParser::class.java, ViewGroup::class.java)
            )
            for (sig in sigs) {
                val m = try { Reflector.findMethod(cls, "inflate", *sig) } catch (_: Throwable) { null } ?: continue
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val r = chain.proceed()
                        try {
                            // ⚠️ 零闪现 + 性能（Round 22）：inflate 出的整棵子树，其每个
                            // 后代在 inflate 过程中都走过一次 parent.addView（→ 各自自检）
                            // 或 setId（→ 也自检，XML 里的 android:id 由 inflater 逐视图
                            // setId 落地），因此这里只需自检**返回的那个根**：它是唯一可能
                            // "未加入任何父容器"（inflate(..., null)）而漏掉 addView 的节点。
                            (r as? View)?.let { root ->
                                attachUptime[root] = android.os.SystemClock.uptimeMillis()
                                val __t = PerfProbe.start()
                                preHideSelf(root, fromMount = true)
                                PerfProbe.end(PerfProbe.PRE_SELF, __t)
                            }
                        } catch (_: Throwable) {
                        }
                        r
                    }
                n++
            }
            ModuleLog.log("UI净化: inflate 预隐藏 hook 安装 $n 个重载(零闪现)")
        } catch (t: Throwable) {
            ModuleLog.log("UI净化: inflate 预隐藏 hook 安装失败: $t")
        }
    }

    // ===== setId 零闪现预隐藏（真机取证 10-12）=====
    //
    // 酷狗顶栏等控件是**先 addView、后 setId**（程序化构建），因此 addView /
    // inflate 时刻 `view.id == NO_ID`，ID 规则查不到 → 只能等周期扫描 →
    // 已绘制后才隐藏 = 闪现。hook setId 可在 ID 落地的瞬间预隐藏，仍然早于
    // 本帧绘制（setId 只 invalidate，不立即 draw）。
    private var setIdHookInstalled = false

    private fun installSetIdHook(xposed: XposedModule) {
        if (setIdHookInstalled) return
        setIdHookInstalled = true
        try {
            val m = Reflector.findMethod(View::class.java, "setId", Int::class.javaPrimitiveType)
            if (m == null) {
                ModuleLog.log("UI净化: setId 预隐藏 hook 未找到方法")
                return
            }
            xposed.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val r = chain.proceed()
                    try {
                        // ⚠️ 零闪现 + 性能（Round 22）：只需自检本视图 —— ID 落地瞬间即判定；
                        // 其子视图的 ID 是各自 setId 设置的，早已各自自检过。
                        // 追整个 24 节点子树纯属重复劳动。
                        (chain.thisObject as? View)?.let { v ->
                            attachUptime[v] = android.os.SystemClock.uptimeMillis()
                            val __t = PerfProbe.start()
                            preHideSelf(v, fromMount = true)
                            PerfProbe.end(PerfProbe.PRE_SELF, __t)
                        }
                    } catch (_: Throwable) {
                    }
                    r
                }
            ModuleLog.log("UI净化: setId 预隐藏 hook 安装成功(零闪现)")
        } catch (t: Throwable) {
            ModuleLog.log("UI净化: setId 预隐藏 hook 安装失败: $t")
        }
    }

    /** 对刚加入视图树的子树按 ID 规则预隐藏（早于 measure/draw → 零闪现） */
    // ===== 画前扫描（零闪现最后一道保险，真机 10-12）=====
    //
    // 少数控件（顶栏 dka 免费听、底栏 at6 角标）由自定义容器构建，既不走
    // 我们 hook 的 addView 重载、也不经过 inflate/setId 预隐藏 → 只能等
    // 200~800ms 后的扫描才隐藏 = 冷启动闪现。这里在 **每一帧绘制前**
    // (ViewTreeObserver.OnPreDrawListener) 跑一次扫描：任何刚出现的可净化
    // 控件都会在**本帧绘制之前**被隐藏 → 视觉上零闪现。
    // 只在 Activity resume 后的启动窗口内启用（默认 2.5s），避免长期逐帧扫描。
    @Volatile
    private var preDrawUntilUptime: Long = 0L
    private val preDrawDecorSeen = java.util.WeakHashMap<View, Boolean>()
    /** 可见触发隐藏的计数（死循环断路器，见 preHideSelf） */
    private val visHideCount = java.util.WeakHashMap<View, Int>()

    private val preDrawListener = android.view.ViewTreeObserver.OnPreDrawListener {
        try {
            // ⭐ R75「绘制前兜底」（**永久生效**，不只在启动窗口）：
            // 把"仍登记为应隐藏、却已经变回 VISIBLE"的视图，在**本帧绘制之前**按回去。
            // 为什么需要它：真机实锤「邀请提示」——预隐藏是成功的
            // （`即时预隐藏(文本挂载) … 已绘制=否`，且 hiddenByUs 已登记、视图已 GONE），
            // 142ms 后它却变成"已绘制"状态被再藏一次；而 `setVisibility` 与 `setFlags`
            // 两个压制钩子**都在、且都会读 hiddenByUs** ⇒ 复活走的是一条**绕过它们**的未知路径。
            // 与其继续追宿主用了哪条路径，不如在离屏前兜一层：不管谁把它放回来，这一帧都画不出。
            // 成本：只遍历 hiddenByUs（通常几十项），**不跑全树扫描**；只有真的变回 VISIBLE 才动手。
            // ⚠️ 合法还原（restoreRule/分离还原/复核还原）都会**先把条目从 hiddenByUs 摘掉**，
            //    所以它们不受影响。
            if (hiddenByUs.isNotEmpty()) {
                for ((v, r) in hiddenByUs) {
                    if (v.visibility == View.VISIBLE) {
                        v.visibility = if (invisibleHides[v] == true) View.INVISIBLE else View.GONE
                        ModuleLog.log(
                            "UI净化[绘制前兜底] ${r.name} ${v.javaClass.simpleName}" +
                                " v=${Integer.toHexString(v.hashCode())} 已按回"
                        )
                    }
                }
            }
            if (android.os.SystemClock.uptimeMillis() <= preDrawUntilUptime) {
                currentActivity?.get()?.let { a ->
                    if (!a.isFinishing && !a.isDestroyed) safeScan(a)
                }
            }
        } catch (_: Throwable) {
        }
        true
    }

    /**
     * 每个 Activity 首次 resume 时挂上"画前扫描"，并开启启动窗口。
     *
     * ⚠️ 窗口时长（本轮）：原为 6s ——在这 6 秒里 OnPreDrawListener **每一帧**
     * 都跑一次全树扫描（60fps × 6s ≈ 360 次），是冷启动掉帧（实测 8.7% janky、
     * 90 分位 44ms）的头号来源。冷启动真正"元素首次上屏"的窗口只有 1~2 秒，
     * 收紧到 2.5s 已覆盖全部零闪现需求，扫描次数直接减半以上。
     */
    private const val PREDRAW_WINDOW_MS = 2500L

    private fun attachPreDrawScan(activity: Activity) {
        preDrawUntilUptime = android.os.SystemClock.uptimeMillis() + PREDRAW_WINDOW_MS
        try {
            val decor = activity.window?.decorView ?: return
            if (preDrawDecorSeen.containsKey(decor)) return
            val vto = decor.viewTreeObserver
            if (!vto.isAlive) return
            vto.addOnPreDrawListener(preDrawListener)
            preDrawDecorSeen[decor] = true
            ModuleLog.log("UI净化: 画前扫描已挂载(启动窗口 ${PREDRAW_WINDOW_MS}ms，零闪现保险)")
        } catch (t: Throwable) {
            ModuleLog.log("UI净化: 画前扫描挂载失败: $t")
        }
    }

    private fun preHideTree(view: View, depth: Int, fromVisibility: Boolean = false, budget: IntArray? = null) {
        if (depth > 16) return
        // 节点预算：一次遍历最多 150 个节点（真机 10-12 卡顿实锤：翻页时
        // setVisibility(VISIBLE) 触发的整树遍历 × 频繁翻页 = 掉帧）
        val b = budget ?: IntArray(1).also { it[0] = 150 }
        if (b[0] <= 0) return
        b[0]--
        try {
            preHideSelf(view, fromVisibility)
        } catch (_: Throwable) {
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                preHideTree(view.getChildAt(i), depth + 1, fromVisibility, b)
            }
        }
    }

    /**
     * 页面切换后的**同步**门禁判定：把"切到本页后应当隐藏"的视图在**本帧绘制前**
     * 隐藏。真机实锤（10-12）：我的页「赚钱」入口原本要等翻页后 200ms 的扫描才
     * 隐藏 → 用户看到"赚钱闪一下"。改为在 setCurrentItem 内同步判定（有界 400 节点）。
     */
    private fun applyGatedHidesNow() {
        // ⚠️ 限流（真机 10-12 卡顿实锤）：ViewPager 滑动动画期间
        // setCurrentItemInternal 会**逐帧调用**，原来的实现每次调用都走 400 节点
        // 的树遍历 → 掉帧主因。同一目标页 150ms 内只判一次。
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastGatedAt < 150L) return
        lastGatedAt = now
        try {
            val a = currentActivity?.get() ?: return
            if (a.isFinishing || a.isDestroyed) return
            val decor = a.window?.decorView ?: return
            val budget = IntArray(1).also { it[0] = 400 }
            walkGated(decor, 0, budget)
        } catch (_: Throwable) {
        }
    }

    private var lastGatedAt: Long = 0L

    /**
     * 主 pager 当前页索引（0=发现 / 1=我的）。
     * 只由**主 pager**（类名含 MainFragmentViewPage）的 setCurrentItem 钩子写入，
     * 避免其它 ViewPager（Banner/轮播）翻页污染页面身份判定。
     */
    @Volatile
    private var mainPageIndex: Int = 0

    @Volatile
    private var mainPageKnown: Boolean = false

    /**
     * **目标页预置**（真机 10-12，解决"我的页赚钱闪一下"）
     *
     * 机制：`dk_` 在发现页是「乐库」、在我的页是「赚钱」，两份是**不同实例**
     * （巡检实锤：一份 x=54/216 在屏内，另一份 x=-1166/-1004 在屏外）。
     * 点「我的」时页面滑入并绘制，门禁判定随后才隐藏 → 闪一下。
     *
     * 解法：在 `setCurrentItem(我的)` **动画开始前**，按**位置**挑出"当前在屏幕外
     * 的那份"（=即将滑入的我的页的那份）预先隐藏 —— 滑入时已是 GONE 不闪；
     * 而发现页那份此刻在屏内，**不受影响**（不会重现"顶栏乐库消失"的老问题）。
     */
    private fun preHideOffscreenGated() {
        try {
            val a = currentActivity?.get() ?: return
            if (a.isFinishing || a.isDestroyed) return
            val screenW = a.resources.displayMetrics.widthPixels
            val loc = IntArray(2)
            // ⭐ Round 52e：精准预置——遍历 requireMinePage 视图注册表（O(n)，n=个位数），
            // 不再全树 DFS。旧 walkOffscreenGated 用 400 节点预算遍历 decor 全树，
            // OPPO 新机视图树庞大，DFS 前 400 节点根本走不到我的页深处（ht8/dk_）
            // → 预置从未生效 → 切回我的页后「赚钱」tab+金币动画可见 ~2.2s
            //（周期扫描间隔）才被藏 = 用户看到的「金币特效被触发」。
            val it = gatedViews.keys.iterator()
            while (it.hasNext()) {
                val v = it.next()
                try {
                    if (!v.isAttachedToWindow) continue
                    val rn = resourceName(v) ?: continue
                    val rules = idRuleMap[rn] ?: continue
                    v.getLocationOnScreen(loc)
                    // 屏幕外 = 完全不与 [0, screenW) 相交（主 pager 离屏页被平移 ±页宽）
                    val offscreen = loc[0] + v.width <= 0 || loc[0] >= screenW
                    if (!offscreen) continue
                    for (rule in rules) {
                        if (!rule.requireMinePage) continue
                        if (!Settings.isFeature(v.context, rule.key)) continue
                        if (!matchIdRule(v, rule, skipPageGate = true)) continue
                        hideByIdRule(v, rule, "offscreen")
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * requireMinePage 门禁视图注册表（Round 52e）：hideByIdRule 成功且规则带
     * requireMinePage 时注册。用于切页瞬间的**精准**离屏预置（preHideOffscreenGated）
     * ——替代全树 DFS（budget 不可靠）。WeakHashMap 不阻止回收。
     */
    private val gatedViews = java.util.WeakHashMap<View, Boolean>()

    private fun walkGated(v: View?, depth: Int, budget: IntArray) {
        if (v == null || depth > MAX_DEPTH || budget[0] <= 0) return
        budget[0]--
        try {
            if (v.id != View.NO_ID) {
                val rn = resourceName(v)
                if (rn != null) {
                    val rules = idRuleMap[rn]
                    if (rules != null) {
                        for (rule in rules) {
                            if (!rule.requireMinePage) continue
                            if (!Settings.isFeature(v.context, rule.key)) continue
                            if (!matchIdRule(v, rule)) continue
                            hideByIdRule(v, rule, "gated")
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walkGated(v.getChildAt(i), depth + 1, budget)
        }
    }

    /**
     * 单个视图的"预隐藏"判定（**不含子树递归**）：ID 制导规则 + 右下角加号结构门禁。
     *
     * 被三条路径复用：addView / inflate / setId / 以及 **setVisibility(VISIBLE)**
     * ——最后一条是零闪现的关键：角标 at6 这类控件是"数据回来后才被设为可见"的，
     * 在 `setVisibility(VISIBLE)` 的同一调用里立刻隐藏，本帧就画不出来。
     */
    private fun preHideSelf(view: View, fromVisibility: Boolean = false, fromMount: Boolean = false) {
        // ⚠️ 快速路径必须放在最前（真机 10-12 性能，本轮再前移）：绝大多数节点
        // 既没有资源 ID、类名也不是悬浮按钮类 —— 直接返回。这类节点**永远不可能
        // 被本引擎隐藏**，因此不需要计数器，也无须做资源查表。
        val hasId = view.id != View.NO_ID
        val maybeFab = isFabClass(view.javaClass.name)
        if (!hasId && !maybeFab) return
        // ⚠️ 死循环断路器（真机 10-12 实锤）：页面门禁规则（requireMinePage）
        // 会被"页面切换恢复"和"可见即隐藏"来回拉扯——同一视图被隐藏 40+ 次
        // （乐库 tab 反复 setVisibility(VISIBLE) → 隐藏 → 恢复 → …）。
        // 两条防线：可见触发的路径**跳过页面门禁规则**；同一视图可见触发
        // 隐藏超过 3 次后彻底停用该通路。
        // 注：计数器是 WeakHashMap（每次访问会清理失效表项），放在廉价的结构
        // 预筛之后，避免为不可能被隐藏的节点白付这份开销。
        if (fromVisibility) {
            val n = (visHideCount[view] ?: 0)
            if (n > 3) return
            visHideCount[view] = n + 1
        }
        // 结构门禁预隐藏（零闪现）：把"位置门禁"改成"结构门禁"——
        // 「右下角加号」原用 region(0.78,0.60) 屏幕坐标门禁，必须等布局
        // 完成才能判定 → 无法在 measure 前决定 → 冷启动必闪。改为：
        //   在主容器链上（MainContainerLayout/FragmentViewHome/
        //   MainFragmentViewPage）+ 不在任何列表容器内部（排除 feed 里
        //   普通 KGTransImageView 图片）→ 立刻隐藏。
        // ⚠️ 顺序即性能（真机 10-12 卡顿实锤）：**先做最便宜的类名判断**，
        // 只有类名疑似悬浮按钮时才付 `Settings.isFeature`/`isOwnPanel`/
        // inMainContainer/hasRecycleAncestor 这些沿祖先链走的代价。
        // 原顺序把 isOwnPanel 放在类名之前 → preHideSelf 每次调用都爬一遍祖先链。
        try {
            if (maybeFab
                && Settings.isFeature(view.context, Settings.KEY_UI_PLUS)
                && !isOwnPanel(view)
                && inMainContainer(view) && !hasRecycleAncestor(view)
            ) {
                hideByIdRule(view, plusRule, "selfPlus")
            }
        } catch (_: Throwable) {
        }
        // ID 制导规则（无 ID 的节点不必查资源名——热路径）
        try {
            if (!hasId) return
            val rn = resourceName(view) ?: return
            val rules = idRuleMap[rn] ?: return
            for (rule in rules) {
                if (!Settings.isFeature(view.context, rule.key)) continue
                // 可见触发路径跳过页面门禁规则（避免与"页面切换恢复"互拉成环）
                if (fromVisibility && rule.requireMinePage) continue
                if (!matchIdRule(view, rule, fromMount = fromMount)) continue
                if (rule.name == "发现页Banner槽位") {
                    hideFeedItemSlot(view, rule)
                } else {
                    hideByIdRule(view, rule, "selfId")
                }
                ID_RULE_RECHECK_TICKS.forEach { d -> scheduleIdRecheck(view, rule, d) }
            }
        } catch (_: Throwable) {
        }
    }

    /** 挂载判定链（核心）：ID 制导即时预隐藏 + 文本关键词预隐藏 + 各专属路径 */
    private fun onViewAttached(view: View) {
        val name = view.javaClass.name
        val ctx = view.context
        // ⚠️ 性能（真机 10-12）：`isOwnPanel` 沿祖先链走（O(深度)），原在本函数内
        // 被 **6 处重复调用** → 同一视图反复爬祖先链。
        // ⚠️ 实测教训：不要用"惰性局部函数 own()"——Kotlin 会把捕获的可变局部量
        // 编译成闭包/Ref 对象，**每次挂载都分配** → GC 压力更大（实测 90th 19→25ms）。
        // 改为：只有 TextView 才可能用到该判定，非 TextView 直接 false，只算一次。
        val isOwn = if (view is TextView) isOwnPanel(view) else false
        if (BADGE_DIAG) diagBadge(view, "挂载")

        // ⚠️ 性能（本轮）：文本只在挂载入口取一次并复用。
        // 原实现对本函数里 4~5 个 TextView 分支各调一次 `extractText(view)`，
        // 同一个 TextView 最多物化 5 次文本 → 滚动时每秒上千次字符串分配 = GC/掉帧。
        // 现在统一在入口求值一次（extractText 已改为返回 CharSequence，零分配）。
        val textView = view as? TextView
        val text: CharSequence? = if (textView != null) extractText(textView) else null
        val memberBarOn = if (textView != null) Settings.isFeature(ctx, Settings.KEY_UI_MEMBER_BAR) else false

        // 发现页 feed 滚动容器捕获：供 ChannelGestureBlocker 做 feedAtTop/
        // feedPulledDown 判定（下拉刷新与进频道手势互斥依赖此引用）
        if (name == "com.kugou.android.rnf.DiscoveryVerticalKGRecyclerView"
            || name.endsWith("DiscoveryVerticalKGRecyclerView")
        ) {
            ChannelGestureBlocker.feedRef = WeakReference(view)
        }

        // ID 制导规则：idRuleMap O(1) 查表；即时预隐藏 + 统一复核调度器
        val rnAttach = resourceName(view)
        if (rnAttach != null) {
            val rules = idRuleMap[rnAttach]
            if (rules != null) {
                for (rule in rules) {
                    if (!Settings.isFeature(ctx, rule.key)) continue
                    // 类名排除门禁（顶栏 MainTopImageView 不能被"我的页赚钱入口"命中）
                    if (rule.excludeClassNames.isNotEmpty()
                        && rule.excludeClassNames.any { name.contains(it) }
                    ) continue
                    val instantHide = rule.ancestorClass == null || hasAncestor(view, rule.ancestorClass)
                    var already = false
                    if (instantHide && gateOk(view, rule) && view.visibility != View.GONE) {
                        if (rule.name == "发现页Banner槽位") {
                            hideFeedItemSlot(view, rule)
                            already = true
                        } else if (hideByIdRule(view, rule, "attach")) {
                            already = true
                            ModuleLog.log("UI净化[${rule.name}] 即时预隐藏(挂载) id=$rnAttach v=${Integer.toHexString(view.hashCode())} 已绘制=${if (wasDrawn(view)) "是" else "否"}")
                        }
                    }
                    // ⚠️ 复核节奏按需分级（真机 10-12 卡顿实锤）：挂载时**已经隐藏
                    // 成功**的视图不需要 5 档复核——原来每个命中 ID 的视图都排 5 个
                    // 任务，翻页时上千视图 → 复核队列爆炸 = 掉帧。已隐藏的只留 1 档兜底。
                    if (already) {
                        scheduleIdRecheck(view, rule, 800L)
                    } else {
                        ID_RULE_RECHECK_TICKS.forEach { delay -> scheduleIdRecheck(view, rule, delay) }
                    }
                }
            }
        }

        // 文本关键词规则挂载即时预隐藏
        //
        // ⚠️ 性能（真机 10-12）：原实现把 `isOwnPanel(view)`（沿祖先链走，O(深度)）
        // 和 `extractText(view)` 放在 ~30 条规则的循环**内部** → 每个 TextView
        // 挂载最多各跑 30 次 = 翻页/滚动掉帧主因之一。
        // 现在：预算文本规则表 + 循环外求值一次 + mayBePromo 预筛，
        // 循环体只剩纯字符串匹配。
        if (textView != null && text != null && TEXT_RULES.isNotEmpty()) {
            val t0 = text
            if (mayBePromo(t0) && !isOwn) {
                for (rule in TEXT_RULES) {
                    if (!Settings.isFeature(ctx, rule.key)) continue
                    if (rule.textKeywords.none { t0.contains(it) }) continue
                    val rn = resourceName(view)
                    if (rn != null && rn in rule.excludeIdNames) continue
                    if (rule.hideParentLevels > 0) {
                        // Round 51+：连父类规则（如淘宝条/天猫卡/每周福利条）在
                        // 挂载期直接走 hideByIdRule 爬升藏父容器——若只 GONE 文本
                        // 自身，容器底板/按钮会留到下轮全树扫描（最长数秒），
                        // 出现「文字没了条还在」的残缺窗口
                        // （文本触发+爬升白名单类：父不匹配直接跳过，防正文误伤，
                        //   与 applyRuleToView 的放弃逻辑一致）
                        if (rule.climbParentIdNames.isEmpty() || climbLevels(view, rule) > 0) {
                            hideByIdRule(view, rule, "mount-text")
                        }
                    } else {
                        view.visibility = View.GONE
                        hiddenByUs[view] = rule
                        ModuleLog.log("UI净化[${rule.name}] 即时预隐藏(文本挂载) text=\"$t0\" id=${rn ?: "-"} v=${Integer.toHexString(view.hashCode())} 已绘制=${if (wasDrawn(view)) "是" else "否"}")
                    }
                    break
                }
            }
        }

        // 概念实验室区块标题（⚠️ ID 门禁 b6b：推荐语含"概念实验室"不能误伤推荐卡）
        if (textView != null && Settings.isFeature(ctx, Settings.KEY_UI_LAB) && !isOwn) {
            if (text != null && text.contains("概念实验室")) {
                if (resourceName(view) == "b6b") {
                    hideCardWithRecheck(textView, labRule)
                }
                return
            }
            // ⚠️ Round 53 真机实锤（红米 be1d7d5d）：搜索页真输入框
            // KGAutoCompleteTextView(id/gte) 是 TextView 子类 + clickable +
            // 创建瞬间 text 为空（提示文案走 hint，不算 text）→ 被此处当成
            // 「空标题槽位」预隐藏成 INVISIBLE；复核 scheduleLabTitleRecheck
            // 只认 text 非空才恢复 ⇒ 永久不可见，IME 焦点错挂假输入框
            // MarqueeTextView(b75) ⇒ 搜索完全无法输入（模块关则正常）。
            // 修法：排除一切可编辑/触摸可聚焦的输入控件。
            if ((text == null || text.isEmpty()) && view.isClickable
                && textView !is android.widget.EditText
                && !textView.isFocusableInTouchMode
            ) {
                ModuleLog.log("UI净化[空标题槽位] 预隐藏 v=${Integer.toHexString(view.hashCode())}")
                applyTitleRowHide(textView, labRule)
                scheduleLabTitleRecheck(view, 0)
            }
        }

        // 即将过期红标
        if (textView != null && text != null && memberBarOn && !isOwn) {
            if (text.contains("即将过期") && view.visibility != View.GONE) {
                view.visibility = View.GONE
                hiddenByUs[view] = memberBarRule
                ModuleLog.log("UI净化[会员栏] 即将过期标已隐藏")
            }
        }

        // 会员到期横幅
        if (textView != null && text != null && memberBarOn && !isOwn) {
            if (text.contains("即将过期") || text.contains("仅剩") || text.contains("续费仅")) {
                hideMemberCard(textView, memberBarRule)
            }
        }

        // 我的页分类排（MineSpecialTabView 专属类零误伤）
        if (name.contains("MineSpecialTabView")
            && Settings.isFeature(ctx, Settings.KEY_UI_MINE_TAB_BAR)
            && !isOwn
        ) {
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
                hiddenByUs[view] = ruleFor(Settings.KEY_UI_MINE_TAB_BAR)
                ModuleLog.log("UI净化[我的页分类栏] 隐藏(挂载判定)")
            }
            return
        }

        // 会员推广卡（我的页）：文本命中→卡片爬升隐藏+定向补扫
        if (textView != null && text != null && memberBarOn && !isOwn) {
            if (MEMBER_PROMO_KEYWORDS.any { text.contains(it) }) {
                hideMemberCard(textView, memberBarRule)
                scheduleMemberPass(ctx)
            }
        }
    }

    /** ⭐ R85b「alpha 保险」还原：把隐藏时置 0 的 alpha 按记录还回原值（幂等；无记录时空转）。 */
    private fun restoreAlphaShield(v: View) {
        try {
            val a = savedAlphas[v] ?: return
            if (v.alpha != a) v.alpha = a
            savedAlphas.remove(v)
        } catch (_: Throwable) {
        }
    }

    /** 分离还原：保证回收池干净 */
    private fun onViewDetached(view: View) {
        try {
            if (BADGE_DIAG) diagBadge(view, "分离")
            val rule = hiddenByUs[view] ?: return
            // 还原高度（feed 槽位 height=0 的情形）
            val h = savedHeights[view]
            if (h != null && h >= 0) {
                (view.layoutParams as? ViewGroup.LayoutParams)?.let { lp ->
                    lp.height = h
                    view.layoutParams = lp
                }
                savedHeights.remove(view)
            }
            restoreAlphaShield(view)
            hiddenByUs.remove(view)
            invisibleHides.remove(view)
            if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
            ModuleLog.log("UI净化[分离还原] ${rule.name} v=${Integer.toHexString(view.hashCode())}")
        } catch (_: Exception) {
        }
    }

    /** 标题行 INVISIBLE（保锚定链不塌陷），并记代际 */
    private fun applyTitleRowHide(title: TextView, rule: Rule) {
        try {
            if (title.visibility != View.INVISIBLE) {
                title.visibility = View.INVISIBLE
                hiddenByUs[title] = rule
                invisibleHides[title] = true
                labVerifyGen[title] = labVerifyGen[title] ?: 0
            }
        } catch (_: Exception) {}
    }

    private fun rowContainsGrid(row: ViewGroup): Boolean {
        // 检查行内是否含网格
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (c is ViewGroup && c.javaClass.name.contains("RecyclerView")) return true
        }
        return false
    }

    private fun restoreGrid(grid: View, why: String) {
        try {
            if (hiddenByUs.containsKey(grid)) {
                restoreAlphaShield(grid)
                hiddenByUs.remove(grid); invisibleHides.remove(grid)
                if (grid.visibility != View.VISIBLE) grid.visibility = View.VISIBLE
                ModuleLog.log("UI净化[网格恢复] $why")
            }
        } catch (_: Exception) {}
    }

    private fun restoreTitle(title: TextView?) {
        try {
            if (title == null) return
            if (hiddenByUs.containsKey(title)) {
                restoreAlphaShield(title)
                hiddenByUs.remove(title); invisibleHides.remove(title)
                if (title.visibility != View.VISIBLE) title.visibility = View.VISIBLE
            }
        } catch (_: Exception) {}
    }

    /** 实验室网格逐帧复核：标题含"概念实验室"则保持隐藏，否则恢复 */
    private fun verifyLabGrid(grid: View, gen: Int, attempt: Int) {
        try {
            if (!grid.isAttachedToWindow) return
            if (labVerifyGen[grid] != gen) return
            val title = findSectionTitle(grid)
            val isLab = title != null && title.text?.contains("概念实验室") == true
            if (isLab) {
                if (grid.visibility != View.GONE) {
                    grid.visibility = View.GONE
                    hiddenByUs[grid] = labRule
                }
            } else if (attempt >= LAB_VERIFY_TICKS.size - 1) {
                restoreGrid(grid, "标题非实验室")
            } else {
                mainHandler.postDelayed({ verifyLabGrid(grid, gen, attempt + 1) }, LAB_VERIFY_TICKS[attempt + 1])
            }
        } catch (_: Exception) {}
    }

    private fun scheduleLabTitleRecheck(view: TextView, attempt: Int) {
        try {
            if (attempt >= LAB_TITLE_TICKS.size) return
            mainHandler.postDelayed({
                try {
                    if (!view.isAttachedToWindow) return@postDelayed
                    val t = view.text?.toString()?.trim()
                    if (t.isNullOrEmpty()) {
                        scheduleLabTitleRecheck(view, attempt + 1)
                    } else if (t.contains("概念实验室")) {
                        applyTitleRowHide(view, labRule)
                    } else {
                        restoreTitle(view)
                    }
                } catch (_: Exception) {}
            }, LAB_TITLE_TICKS[attempt])
        } catch (_: Exception) {}
    }

    /** 标题附近的实验室网格预隐藏（分步藏：标题行 INVISIBLE+网格 GONE） */
    private fun hideLabGridNearTitle(title: TextView) {
        try {
            val p = title.parent as? ViewGroup ?: return
            for (i in 0 until p.childCount) {
                val c = p.getChildAt(i)
                if (c === title) continue
                if (c is ViewGroup && rowContainsGrid(c)) {
                    if (c.visibility != View.GONE) {
                        c.visibility = View.GONE
                        hiddenByUs[c] = labRule
                        labVerifyGen[c] = (labVerifyGen[c] ?: 0) + 1
                        verifyLabGrid(c, labVerifyGen[c]!!, 0)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // ===== 免费听推广页翻页拦截（完整版，按 javap 字节码还原） =====
    //
    // 背景：主 pager MainFragmentViewPage 只有**两页**：0=发现 fragment /
    // 1=我的。免费听推广页藏在"我的"页 pager 内部——SwipeViewPage
    // （com.kugou.common.swipeTab.SwipeViewPage）翻到页(1)时推广内容整页
    // 可见。本拦截把页(1)的内容根 INVISIBLE 占位不绘制：拖动预览与平滑
    // 扫过都是空白，推广内容任何时刻不可见，"发现↔乐库"横滑动画连贯。
    private var promoTabArgs: IntArray? = null
    private var promoLastLog: Long = 0L
    private var promoInfoMethod: Method? = null
    private var promoPageField: Field? = null

    /**
     * 推广页翻页拦截安装。三件事：
     * 1. hook SwipeViewPage 全部翻页方法（W / setCurrentItemInternal，
     *    4 参或 3 参且首参 int）——翻页时改写参数钉住页 0
     * 2. 反射定位 C(I)（infoForPosition）与其返回类型的 Object 字段
     *    （ItemInfo.page 根视图），供 hidePromoPageContent 精确取页根
     * 3. hook Z(int,int,int)（startScroll 签名）记录滚动时长取证
     */
    private fun installPromoPageBlocker(xposed: XposedModule, classLoader: ClassLoader) {
        try {
            val swipeCls = Class.forName("com.kugou.common.swipeTab.SwipeViewPage", false, classLoader)
            // getCurrentItem：供翻页 hook 内读取当前页
            val getCurrentItem: Method? = try {
                swipeCls.getMethod("getCurrentItem")
            } catch (_: Exception) {
                null
            }

            // 1. 收集全部翻页方法（沿父类链）：名称 W 或 setCurrentItemInternal，
            //    且 (4 参首参 int) 或 (3 参首参 int)
            val pageMethods = LinkedHashSet<Method>()
            var c: Class<*>? = swipeCls
            while (c != null && c != Any::class.java) {
                for (m in c.declaredMethods) {
                    if (m.name != "W" && m.name != "setCurrentItemInternal") continue
                    val ps = m.parameterTypes
                    val four = ps.size == 4 && ps[0] == Int::class.javaPrimitiveType
                    val three = ps.size == 3 && ps[0] == Int::class.javaPrimitiveType
                    if (four || three) pageMethods.add(m)
                }
                c = c.superclass
            }
            if (pageMethods.isEmpty()) {
                ModuleLog.log("免费听推广页翻页拦截: SwipeViewPage 未找到翻页方法，跳过")
                return
            }
            for (m in pageMethods) {
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain -> promoPageHook(chain, swipeCls, m, getCurrentItem) }
            }
            ModuleLog.log("免费听推广页翻页拦截: 已 hook ${pageMethods.size} 个翻页方法")

            // 2. 定位 C(I)：非 void 非 primitive 返回、单 int 参——infoForPosition
            try {
                var cMethod: Method? = null
                var cls: Class<*>? = swipeCls
                while (cls != null && cMethod == null) {
                    for (m in cls.declaredMethods) {
                        if (m.name != "C") continue
                        if (m.parameterTypes.size != 1) continue
                        if (m.parameterTypes[0] != Int::class.javaPrimitiveType) continue
                        if (m.returnType == Void.TYPE || m.returnType.isPrimitive) continue
                        cMethod = m
                        break
                    }
                    cls = cls.superclass
                }
                var pageField: Field? = null
                val rt = cMethod?.returnType
                if (rt != null) {
                    for (f in rt.declaredFields) {
                        val mods = f.modifiers
                        if (!java.lang.reflect.Modifier.isStatic(mods) && f.type == Any::class.java) {
                            pageField = f
                            break
                        }
                    }
                }
                cMethod?.isAccessible = true
                pageField?.isAccessible = true
                promoInfoMethod = cMethod
                promoPageField = pageField
                ModuleLog.log(
                    "免费听推广页: infoForPosition=${cMethod?.declaringClass?.simpleName ?: "无"}" +
                        " 返回=${cMethod?.returnType?.simpleName ?: "无"}" +
                        " page字段=${pageField?.name ?: "无"}"
                )
            } catch (t: Throwable) {
                ModuleLog.log("免费听推广页: infoForPosition 定位失败: $t")
            }

            // 3. hook Z(int,int,int)：滚动动画参数取证（只打日志不干预）
            try {
                var zMethod: Method? = null
                var cls2: Class<*>? = swipeCls
                while (cls2 != null && zMethod == null) {
                    for (m in cls2.declaredMethods) {
                        if (m.name != "Z") continue
                        if (m.parameterTypes.size != 3) continue
                        if (m.parameterTypes.all { it == Int::class.javaPrimitiveType }) {
                            zMethod = m
                            break
                        }
                    }
                    cls2 = cls2.superclass
                }
                val zm = zMethod
                val nField = try {
                    var f: Field? = null
                    var cl: Class<*>? = swipeCls
                    while (cl != null && f == null) {
                        f = try {
                            cl.getDeclaredField("n")
                        } catch (_: Throwable) {
                            cl = cl.superclass
                            null
                        }
                    }
                    f?.isAccessible = true
                    f
                } catch (_: Throwable) {
                    null
                }
                if (zm != null) {
                    xposed.hook(zm)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            try {
                                val pager = chain.thisObject as? ViewGroup
                                if (pager != null && pager.javaClass == swipeCls) {
                                    chain.proceed()
                                    val sc = nField?.get(pager) as? android.widget.Scroller
                                    ModuleLog.log(
                                        "UI净化[推广页] Z(x=${chain.getArg(0)} v=${chain.getArg(2)}) " +
                                            "dur=${sc?.duration ?: -1} scrollX=${pager.scrollX}"
                                    )
                                    return@intercept null
                                }
                            } catch (_: Throwable) {
                            }
                            chain.proceed()
                        }
                }
            } catch (_: Throwable) {
            }
        } catch (e: Exception) {
            ModuleLog.log("免费听推广页翻页拦截安装失败: $e")
        }
    }

    /**
     * 翻页 hook 体：arg0==1（翻向"我的"页/推广页）且 pager 未在滚动
     * （scrollX % width == 0）时，改写参数钉住页 0 并隐藏推广页内容。
     * arg0!=1 时放行（发现页正常翻页），仅按 3s 限频记录取证信息。
     */
    private fun promoPageHook(
        chain: XposedInterface.Chain, swipeCls: Class<*>, m: Method, getCurrentItem: Method?
    ): Any? {
        val pager = chain.thisObject as? ViewGroup ?: return chain.proceed()
        if (pager.javaClass != swipeCls) return chain.proceed()
        val target = (chain.getArg(0) as? Int) ?: return chain.proceed()
        val width = pager.width

        if (target != 1 || width <= 0 || pager.scrollX % width != 0) {
            // 取证（3s 限频）：记录翻页参数与当前页
            val now = SystemClock.uptimeMillis()
            if (now - promoLastLog > 3000) {
                promoLastLog = now
                val cur = try {
                    getCurrentItem?.invoke(pager)?.toString() ?: "?"
                } catch (_: Throwable) {
                    "?"
                }
                val args = m.parameterTypes.mapIndexed { i, pt ->
                    "${pt.simpleName}=${chain.getArg(i)}"
                }.joinToString(",")
                ModuleLog.log("UI净化[推广页] 翻页 target=$target cur=$cur scrollX=${pager.scrollX} args=[$args]")
            }
            if (target != 1) return chain.proceed()
        }

        // 翻向页 1（推广页）：改写参数钉住页 0
        val cur = try {
            (getCurrentItem?.invoke(pager) as? Int) ?: 0
        } catch (_: Throwable) {
            0
        }
        val pinned = if (cur == 2) 0 else 2
        val hiddenOk = hidePromoPageContent(pager)

        // 重建参数数组：首参改为钉住页，其余参数沿用上次记录/原链参数
        val newArgs = arrayOfNulls<Any?>(m.parameterTypes.size)
        newArgs[0] = pinned
        if (m.parameterTypes.size > 1 && m.parameterTypes[1] == java.lang.Boolean.TYPE) {
            newArgs[1] = hiddenOk
        }
        promoTabArgs?.let { saved ->
            val n = minOf(m.parameterTypes.size, saved.size + 1)
            for (i in 1 until n) {
                if (i == 1 && m.parameterTypes[1] == java.lang.Boolean.TYPE && !hiddenOk) {
                    // hidden=false 时不复用历史参数（会让 app 认为已滑动完成）
                    continue
                }
                newArgs[i] = saved[i - 1]
            }
        }
        for (i in newArgs.indices) {
            if (newArgs[i] == null) newArgs[i] = chain.getArg(i)
        }
        ModuleLog.log(
            "UI净化[推广页] 拦截翻页: target=$target cur=$cur pinned=$pinned " +
                "hidden=$hiddenOk saved=${promoTabArgs?.joinToString(",") ?: "无"}"
        )
        return chain.proceed(newArgs)
    }

    /**
     * 推广页内容根隐形。精确路径：反射调用父类 C(I)（infoForPosition）取
     * 页(1)的 ItemInfo，其 Object 字段=页根视图（PagerAdapter.instantiateItem
     * 返回值）——左侧坐标猜测仅作 fallback。
     * INVISIBLE 占位不绘制：拖动预览与平滑扫过都是空白，推广内容任何
     * 时刻不可见。记入 hiddenByUs+invisibleHides → 复活压制 hook 维持
     * INVISIBLE；分离时自然还原（回收池干净）。幂等；未布局/取不到返回 false。
     */
    private fun hidePromoPageContent(pager: ViewGroup): Boolean {
        var ok = false
        try {
            val w = pager.width
            if (w <= 0) return false
            var page: View? = null
            var viaInfo = false
            // 精确路径：C(1) → ItemInfo → Object 字段 = 页根视图
            val cm = promoInfoMethod
            val pf = promoPageField
            if (cm != null && pf != null) {
                try {
                    val info = cm.invoke(pager, 1)
                    val v = info?.let { pf.get(it) } as? View
                    if (v != null && v.parent === pager) {
                        page = v
                        viaInfo = true
                    }
                } catch (_: Throwable) {
                }
            }
            // fallback：坐标猜测（页(1) 按 position*width 布局）
            if (page == null) {
                for (i in 0 until pager.childCount) {
                    val c = pager.getChildAt(i) ?: continue
                    if (c.left == w) {
                        page = c
                        break
                    }
                }
            }
            val root = page ?: return false
            if (root.visibility != View.INVISIBLE) {
                root.visibility = View.INVISIBLE
                hiddenByUs[root] = freeListenRule
                invisibleHides[root] = true
                ModuleLog.log(
                    "UI净化[免费听推广页] 内容已隐形 page1=${Integer.toHexString(root.hashCode())} " +
                        "cls=${root.javaClass.simpleName} via=${if (viaInfo) "infoForPosition" else "left坐标"}"
                )
            }
            ok = true
        } catch (_: Exception) {
            ok = false
        }
        return ok
    }

    private fun findSectionTitle(grid: View): TextView? {
        // 向上找相邻标题
        val p = grid.parent as? ViewGroup ?: return null
        for (i in 0 until p.childCount) {
            val c = p.getChildAt(i)
            if (c is TextView && c.text?.contains("概念实验室") == true) return c
        }
        return null
    }

    private fun findTitleExcluding(root: ViewGroup, exclude: View, depth: Int): TextView? {
        if (depth > 4) return null
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c === exclude) continue
            if (c is TextView) return c
            if (c is ViewGroup) findTitleExcluding(c, exclude, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * 零闪现取证：目标是否**在本次挂载之后被绘制过**。
     *
     * ⚠️ 关键修正（真机 10-12 实锤）：`View.getDrawingTime() != 0` **不能**单独
     * 作为判据——视图被分离/重挂（RecyclerView 复用、底栏重建）后 drawingTime
     * 会保留**上一次生命**的时间戳，于是 at6 角标（本次挂载时 vis=GONE，从来没
     * 画过）与 i4w（复用 item）都被误判成"已绘制=是"。
     * 正确判据：drawingTime 必须 **≥ 本次挂载时刻** 才算真的上过屏。
     */
    private fun wasDrawn(v: View): Boolean = try {
        val dt = v.drawingTime
        dt != 0L && dt >= (attachUptime[v] ?: 0L)
    } catch (_: Throwable) {
        false
    }

    /** 视图最近一次挂载的时刻（uptimeMillis；drawingTime 同时间基） */
    private val attachUptime = java.util.WeakHashMap<View, Long>()

    private fun isRecycleContainer(v: View): Boolean {
        val cn = v.javaClass.name
        return cn.contains("RecyclerView") || cn.contains("ViewPager") || cn.contains("AbsListView")
    }

    /** 页面级滚动容器（爬升终点）：卡片不会跨越这些容器 */
    private fun isScrollContainer(v: View): Boolean {
        val cn = v.javaClass.name
        return cn.contains("NestedScrollView") || cn.contains("ScrollView")
            || cn.contains("CoordinatorLayout")
    }

    /**
     * 用户信息卡（头像/昵称/等级）：绝不能被会员推广的整卡隐藏吞掉。
     * 真机实锤——我的页 HomeCardConstraintLayout 同时装昵称与"福利与活动/
     * 会员中心"入口，命中促销关键词后整卡 GONE → 我的页顶部空白。
     */
    private fun isUserInfoCard(v: View?): Boolean {
        var node: View? = v
        var depth = 0
        while (node != null && depth < 6) {
            val cn = node.javaClass.name
            if (cn.contains("HomeCard") || cn.contains("UserInfo") || cn.contains("MineHead")) return true
            node = node.parent as? View
            depth++
        }
        return false
    }

    /**
     * 页面级容器（绝不能被卡片隐藏逻辑 GONE）：
     * 真机实锤——我的页根容器 MineInterruptFrameLayout、内容滚动容器
     * KtvScrollableLayout 被「会员栏」整卡隐藏 → 我的页整页空白。
     */
    private fun isPageLevelContainer(v: View): Boolean {
        val cn = v.javaClass.name
        return isRecycleContainer(v) || isScrollContainer(v)
            || cn.contains("Interrupt")
            || cn.contains("ScrollableLayout")
            || cn.contains("FragmentStackView")
            || cn.contains("MainContainerLayout")
            || cn.contains("FragmentViewHome")
            || cn.contains("MainFragmentViewPage")
    }

    private fun scheduleMemberPass(context: Context) {
        try {
            val a = currentActivity?.get() ?: return
            if (a.isFinishing || a.isDestroyed) return
            if (a.window?.decorView == null) return
            mainHandler.post { safeScan(a) }
            for (delay in longArrayOf(300, 800, 2000)) {
                mainHandler.postDelayed({ safeScan(a) }, delay)
            }
        } catch (_: Exception) {}
    }

    private fun findMemberTexts(view: View?, depth: Int) {
        if (view == null || depth > MAX_DEPTH) return
        if (view is TextView && !isOwnPanel(view)) {
            val t = view.text?.toString()
            if (t != null && MEMBER_PROMO_KEYWORDS.any { t.contains(it) }) {
                hideMemberCard(view, memberBarRule)
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) findMemberTexts(view.getChildAt(i), depth + 1)
    }

    /**
     * 每轮 `safeScan` 都会跑的「免费听」补扫（R63 定点）。
     *
     * ⚠️⚠️ **必须与规则集一样的 `b6b` ID 门禁**（R63 修）：原实现只排除 `dka`，
     * 于是**任何**文本含「免费听」的 TextView 都会走 `hideCardWithRecheck`
     * → 爬升到 **RecyclerView 的 item 根** 设 `INVISIBLE + height=0`，
     * 并排 50/150/400ms 三次复核。而规则集那一路（见 `preHideSelf` 中
     * `t.contains("免费听") && resourceName(v) == "b6b"`）明确写了
     * 「推荐语含"免费听"字样时不能误伤推荐卡」—— 本函数漏了这一层，
     * 属于比规则集更宽的误伤面：**歌名/推荐语/新插入 item 只要含这四个字就会被整卡藏掉**。
     *
     * 后果（R63 真机实锤）：feed 分批插入期间被反复改 item 根的高度/可见性，
     * 宿主 `DiscoveryRec5Adapter` 的「换一批」会**概率性中止**——
     * 默认 45 项全开时 **4/4 轮**卡死（末次插入后静默 18~20s），
     * 全关 45 项 **0/2** 正常（插入 7~9 次、尾部静默 <2.5s）。
     */
    private fun findFreeListenTexts(view: View?, depth: Int) {
        if (view == null || depth > MAX_DEPTH) return
        if (view is TextView && !isOwnPanel(view)) {
            // ⚠️ ID 门禁：只认区块标题槽位 b6b（与规则集一致，绝不按正文文案误伤整卡）
            if (resourceName(view) == "b6b") {
                val t = view.text?.toString()
                if (t != null && t.contains("免费听")) hideCardWithRecheck(view, freeListenBlockRule)
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) findFreeListenTexts(view.getChildAt(i), depth + 1)
    }

    private fun hideInviteCornerCard(view: View?, depth: Int) {
        if (view == null || depth > MAX_DEPTH) return
        if (view is ImageView && view.visibility == View.VISIBLE && !isOwnPanel(view)) {
            try {
                val dm = view.resources.displayMetrics
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val cx = loc[0] + view.width / 2f
                val cy = loc[1] + view.height / 2f
                if (cx > dm.widthPixels * 0.85f && cy > dm.heightPixels * 0.10f
                    && cy < dm.heightPixels * 0.22f && view.width < 300 && view.height < 300
                ) {
                    var target: View = view
                    var node = view.parent as? ViewGroup
                    var levels = 0
                    while (node != null && levels < 5) {
                        if (node.width > dm.widthPixels * 0.6f || node.height > 400 || isRecycleContainer(node)) break
                        target = node; node = node.parent as? ViewGroup; levels++
                    }
                    if (target.visibility != View.GONE) {
                        target.visibility = View.GONE
                        hiddenByUs[target] = memberBarRule
                        ModuleLog.log("UI净化[会员推广] 隐藏邀好友入口: ${target.javaClass.simpleName}")
                    }
                }
            } catch (_: Exception) {}
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) hideInviteCornerCard(view.getChildAt(i), depth + 1)
    }

    /** 会员推广卡：爬升到全宽卡容器隐藏（页面级容器即停防整页误伤） */
    private fun hideMemberCard(textView: TextView, rule: Rule) {
        try {
            var target: View = textView
            var node = textView.parent as? ViewGroup
            val dm = textView.resources.displayMetrics
            // ⚠️ 用户信息卡（头像/昵称）绝不隐藏（真机实锤 15:07）：
            // 我的页顶部 HomeCardConstraintLayout 同时装着昵称"Willie"和
            // "福利与活动/会员中心"入口——「会员栏」按 MEMBER_PROMO_KEYWORDS
            // ⚠️ 用户信息卡（头像/昵称）边界（真机实锤 15:07 + 参照图对比 15:2x）：
            // 我的页顶部 HomeCardConstraintLayout 同时装着昵称"Willie"/等级和
            // "福利与活动/会员中心"推广入口。做法不是"整条放弃隐藏"（那样推广行
            // 会全部留下），而是**爬升到用户信息卡即停**——目标是它下面一层的
            // 推广行容器：推广行照常隐藏，昵称/等级完好。
            // ⚠️ 真机实锤（15:00）：原逻辑一路爬到 MineInterruptFrameLayout
            // （我的页根容器，1220x2313）→ 整页 GONE。挂载时布局尚未完成
            // （尺寸 0x0），高度预算拦不住，必须按"页面级容器类名"硬停 +
            // 限制爬升层数。
            val maxCardHeight = (dm.heightPixels * 0.4f).toInt()
            var levels = 0
            while (node != null && levels < 3) {
                if (isUserInfoCard(node)) break
                if (isPageLevelContainer(node)) break
                if (node.height > maxCardHeight) break
                target = node
                if (node.width >= dm.widthPixels * 0.8f) break
                node = node.parent as? ViewGroup
                levels++
            }
            if (target.visibility != View.GONE) {
                target.visibility = View.GONE
                hiddenByUs[target] = rule
                ModuleLog.log("UI净化[${rule.name}] 隐藏(卡片): ${target.javaClass.simpleName} ${target.width}x${target.height} 触发文本=\"${extractText(textView) ?: ""}\" 已绘制=${if (wasDrawn(target)) "是" else "否"} 严格=${if (wasDrawnStrict(target)) "是" else "否"}")
                // 布局后复核（真机实锤 15:04）：挂载时尺寸 0x0，爬升判定无法
                // 用高度约束——若隐藏的容器布局后超过屏高 35%，说明爬升越界
                // （如我的页用户信息卡 HomeCardConstraintLayout 被误整卡隐藏，
                // 昵称/头像消失），立即还原。
                val tv = target
                mainHandler.postDelayed({
                    try {
                        if (tv.visibility == View.GONE && tv.height > dm.heightPixels * 0.35f) {
                            tv.visibility = View.VISIBLE
                            restoreAlphaShield(tv)
                            hiddenByUs.remove(tv)
                            ModuleLog.log("UI净化[${rule.name}] 卡片过高(${tv.height}px)已还原: ${tv.javaClass.simpleName}")
                        }
                    } catch (_: Throwable) {}
                }, 350)
            }
        } catch (_: Exception) {}
    }

    // 容器隐藏前的原高度（恢复时还原）
    private val savedHeights = WeakHashMap<View, Int>()

    /** feed 槽位容器隐藏（ela 专用）：GONE+height=0 彻底塌缩 */
    private fun hideFeedItemSlot(view: View, rule: Rule): Boolean {
        try {
            val parent = view.parent as? ViewGroup
            val target: ViewGroup? = when {
                parent != null && isRecycleContainer(parent) -> view as? ViewGroup
                else -> {
                    var block: ViewGroup? = null
                    var node = parent
                    while (node != null) {
                        if (isRecycleContainer(node)) { block = null; break }
                        block = node
                        node = node.parent as? ViewGroup
                    }
                    block
                }
            }
            if (target == null) return false
            if (target.visibility == View.GONE && target.layoutParams?.height == 0) return true
            savedHeights[target] = target.layoutParams?.height ?: -2
            target.visibility = View.GONE
            (target.layoutParams as? ViewGroup.LayoutParams)?.let { lp -> lp.height = 0; target.layoutParams = lp }
            hiddenByUs[target] = rule
            ModuleLog.log(
                "UI净化[${rule.name}] 隐藏(feed槽位): ${target.javaClass.simpleName}" +
                    " v=${Integer.toHexString(target.hashCode())}" +
                    " 已绘制=${if (wasDrawn(target)) "是" else "否"}" +
                    " 严格=${if (wasDrawnStrict(target)) "是" else "否"}" +
                    " ${target.width}x${target.height}"
            )
            return true
        } catch (_: Exception) { return false }
    }

    /**
     * ID 制导规则的统一隐藏入口（挂载预隐藏 / 多帧复核 / 周期扫描三处共用）。
     * collapseHeight=true 时 GONE+height=0 并记 savedHeights（我的页白卡内的
     * 整行推广条：只 GONE 会被父容器按原高留白洞）；否则按 hideParentLevels
     * 爬升后 GONE。返回是否本次真正改动。
     */
    private fun hideByIdRule(view: View, rule: Rule, src: String = ""): Boolean {
        return try {
            // ⚠️ 类名排除门禁（最后一道闸，10-12）：把所有调用路径都兜住——
            // 真机取证发现「我的页赚钱入口」(dk_) 在页面门禁 apply→restore→
            // apply 抖动中，仍有一次在**已绘制后**把首页顶栏 MainTopImageView
            // (乐库) 隐藏（用户看到乐库闪一下）。在统一隐藏入口再判一次类名，
            // 任何路径都无法隐藏被排除的控件类。
            if (rule.excludeClassNames.isNotEmpty()
                && rule.excludeClassNames.any { view.javaClass.name.contains(it) }
            ) return false
            // 会员角标特判（Round 20）：贴纸 c6u 隐形后，底板 iv5 的渐变光晕
            // 仍留在白卡右上角 = 一块比白卡暗的灰蓝印子（用户实锤"底板和白卡
            // 颜色不一样"）。⚠️ 不能 GONE 底板——它是约束子，GONE 会改变
            // HomeCardConstraintLayout 求解导致白卡缩到 836 宽（Round 18 缺口
            // 真相）。做法=保持几何不变，只抹掉底板背景（幂等，每次复核都跑）
            if (rule.name == "我的页会员角标" && resourceName(view) == "c6u") {
                neutralizeBadgePlate(view)
            }
            // ⭐ 免费听图标（2026-10-15）：dg1 alpha=0 必须放在 **visibility 早退之前**。
            // 挂载预隐藏瞬间 dg0.parent 还是 null（addView/inflate 钩子期视图未挂树），
            // 首跑只能 GONE dg0 自身；之后的 recheck 复核进来又会被下方「已 GONE
            // 提前返回」挡住 —— alpha 永远没机会执行（diag 日志实锤 parent=null）。
            // 未挂载时 neutralizeFreeListenPlate 静默返回，等 recheck 时 parent 已
            // 挂好在这里补跑（幂等）。
            if (rule.name == "免费听图标" && resourceName(view) == "dg0") {
                neutralizeFreeListenPlate(view)
            }
            // ⚠️ 爬升门禁（Round 26）：配了 climbParentIdNames 的规则，只有直接
            // 父容器的 ID 命中白名单才允许爬升，否则退化为只藏自身
            val levels = climbLevels(view, rule)
            val target = if (rule.collapseHeight) view else climbParent(view, levels)
            if (rule.collapseHeight) {
                if (target.visibility == View.GONE && target.layoutParams?.height == 0) return false
                savedHeights[target] = target.layoutParams?.height ?: -2
                (target.layoutParams as? ViewGroup.LayoutParams)?.let { lp ->
                    lp.height = 0; target.layoutParams = lp
                }
            } else {
                if (target.visibility == View.GONE) return false
            }
            target.visibility = View.GONE
            hiddenByUs[target] = rule
            invisibleHides[target] = false
            // ⭐ R85「alpha 保险」：宿主会通过我们没覆盖的路径把已隐藏的视图**复活**。
            //   真机实锤（播放页书签槽）：预隐藏在尺寸 0 时就成功了（R82 的功劳），
            //   但 ~343ms 后宿主把**同一实例**复活并画出（已绘制=是 严格=是 129x120），
            //   R75 的绘制前兜底因**同帧时序**（宿主的复活发生在我们那次 onPreDraw 之后、
            //   该帧绘制之前）漏掉了那一帧。
            //   宿主复活只改 visibility、**不会动 alpha** ⇒ 这里额外把 alpha 置 0：
            //   即使被复活，那一帧也画不出来（仍走一次绘制，但用户看不到）。
            //   原值存入 savedAlphas（复用 R67 体系），由 restoreRule 在关开关时还原。
            //   ⚠️ 两处必须成对：只加隐藏不接还原 ⇒ 会引入"关掉开关也回不来"的新 bug。
            if (rule.alphaShield && target.alpha != 0f) {
                if (!savedAlphas.containsKey(target)) savedAlphas[target] = target.alpha
                target.alpha = 0f
            }
            // Round 52e：requireMinePage 视图注册——切页瞬间精准离屏预置用
            if (rule.requireMinePage) gatedViews[target] = true
            ModuleLog.log(
                "UI净化[${rule.name}] 隐藏: ${target.javaClass.simpleName}" +
                    (if (rule.collapseHeight) " GONE+height=0" else "") +
                    " v=${Integer.toHexString(target.hashCode())}" +
                    " 已绘制=${if (wasDrawn(target)) "是" else "否"}" +
                    " 严格=${if (wasDrawnStrict(target)) "是" else "否"}" +
                    // ⭐ R85b：alpha 也打进日志 —— 加了「alpha 保险」之后，
                    // `严格=是` 这个"已绘制"代理判据**不再等于用户看得见**：
                    // alpha=0 的视图照样会被 onDraw（只是像素全透明）。
                    // 判断有没有真闪，要看「被复活那一刻的 alpha」是否为 0。
                    " alpha=${target.alpha}" +
                    " ${target.width}x${target.height}" +
                    (if (GATE_DIAG) " src=$src" else "") +
                    (if (GATE_DIAG && rule.requireMinePage) gateDiag(target, rule) else "")
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 免费听图标的胶囊底板处理（2026-10-15）：
     *
     * dg0(Lottie 免图标) 只藏自身；其父 dg1(HnVisualEffectLayout，84×84 圆形
     * 白色胶囊底板，效果引擎自绘、background 为 null) **绝不能 GONE/INVISIBLE**
     * —— 真机 A/B 实锤：藏它连带发现页整页背景渐变（含顶栏）一起消失（酷狗
     * 效果引擎内部联动，R54 曾误归因给 unclipContentPadding 并用顶栏玻璃遮症状）。
     *
     * 处置 = alpha=0：不改 visibility、视图仍在绘制管线（效果引擎仍算挂载、
     * 渐变保留），绘制输出全透明 → 空胶囊隐掉。幂等：alpha!=0 才设。
     * ⚠️ 挂载瞬间 dg0.parent 可能为 null（视图未挂树）——此时静默返回，
     * scheduleIdRecheck 的多帧复核会再次进入本路径补跑。
     */
    private fun neutralizeFreeListenPlate(dg0: View) {
        try {
            val plate = dg0.parent as? View ?: return
            if (resourceName(plate) != "dg1") return
            // ⭐ R67：首次改动前存原始背景 / alpha（关开关时要还回去）
            if (!savedBackgrounds.containsKey(plate)) savedBackgrounds[plate] = plate.background
            if (!savedAlphas.containsKey(plate)) savedAlphas[plate] = plate.alpha
            if (plate.background != null) {
                plate.background = null
                plate.invalidate()
            }
            if (plate.alpha != 0f) {
                plate.alpha = 0f
                plate.invalidate()
                ModuleLog.log(
                    "UI净化[免费听图标] dg1 alpha=0（隐空胶囊、保背景渐变）" +
                        " 已绘制=${if (wasDrawn(plate)) "是" else "否"}" +
                        " 严格=${if (wasDrawnStrict(plate)) "是" else "否"}" +
                        " ${plate.width}x${plate.height}"
                )
            }
            neutralizedPlates[plate] = true
            fillFreeListenSlot(plate)
        } catch (_: Throwable) {
        }
    }

    /**
     * 把「搜索」图标挪进免费听图标腾出的空槽（2026-10-15）。
     *
     * dg1 只能用 alpha=0 隐空（GONE 会连带整页背景渐变消失，见上），**布局仍占 84px**；
     * 而顶栏右簇是「固定位置 + 靠右对齐」而非链式约束（对照：tab 行的 dka 隐藏后 dk_
     * 会自动补位到 216，右簇的 dlo 却始终钉在 1082）⇒ 搜索图标(830-914) 与最右图标
     * (1082-1166) 之间留下 168px 空位（用户报「还有空白占位」）。
     *
     * 处置 = 给搜索容器 `dlw` 设 `translationX = dg1.left - dlw.left`（=126px），
     * 让它正好落到空槽上，右簇恢复「搜索 + 最右图标」紧凑右对齐。
     * `translationX` 同时移动绘制与触摸命中区，所以点击位置也跟着走。
     *
     * 幂等：每次复核按当前布局重算；两视图需已布局（left>0）且同父；关闭开关时由
     * [restoreRule] 归零。
     */
    private fun fillFreeListenSlot(plate: View) {
        val parent = plate.parent as? ViewGroup ?: return
        var search: View? = null
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c !== plate && resourceName(c) == "dlw") { search = c; break }
        }
        val s = search ?: return
        // 未布局（left=0）时等下一次多帧复核；不同父时坐标不可比，直接放弃
        if (plate.left <= 0 || s.left <= 0) return
        val shift = (plate.left - s.left).toFloat()
        if (shift == 0f || s.translationX == shift) return
        s.translationX = shift
        shiftedByUs[s] = true
        ModuleLog.log("UI净化[免费听图标] 搜索图标补位 dlw ${s.left}→${plate.left}px")
    }

    /**
     * 计算本规则对 [view] 实际允许的爬升层数。
     *
     * 配了 `climbParentIdNames` 的规则（Round 26）：直接父容器的资源 ID 必须
     * 命中白名单才爬升，否则返回 0（只藏自身）——防止同 ID 跨页复用时把
     * 无关的父容器（及其同层兄弟）整块牵走。父容器缺失/无 ID 视为不命中。
     */
    private fun climbLevels(view: View, rule: Rule): Int {
        val lv = rule.hideParentLevels
        if (lv <= 0 || rule.climbParentIdNames.isEmpty()) return lv
        val p = view.parent as? View ?: return 0
        val pn = resourceName(p) ?: return 0
        return if (pn in rule.climbParentIdNames) lv else 0
    }

    /** 挂载期即可判的附加门禁：兄弟 ID 要求（赚钱 tab dk_ 必须与 dk5 同在） */
    private fun gateOk(view: View, rule: Rule): Boolean {
        // 父容器 ID 门禁（Round 26）：mount 期即可判定，比页面门禁稳定
        if (rule.parentIdNames.isNotEmpty()) {
            val p = view.parent as? View ?: return false
            val pn = resourceName(p) ?: return false
            if (pn !in rule.parentIdNames) return false
        }
        val sn = rule.requireSiblingIdName
        if (sn != null) {
            val p = view.parent as? ViewGroup ?: return false
            var sib = false
            for (i in 0 until p.childCount) {
                val c = p.getChildAt(i) ?: continue
                if (c !== view && resourceName(c) == sn) { sib = true; break }
            }
            if (!sib) return false
        }
        if (rule.requireMinePage && !minePageOnScreen(view)) return false
        return true
    }

    /**
     * 当前前台页是否「我的」页：找我的页专属白卡 iss 并判断其在屏幕内。
     * 主 pager 离屏页被平移到 ±页宽（c2e@x=1220），据此区分——比结构判定
     * 可靠（uiautomator/dumpsys 都会省略中间非重要节点，祖先链不可信）。
     * 我的页尚未创建时返回 false（此时发现页 dk_ 应保持「乐库」可见）。
     */
    private fun minePageOnScreen(from: View): Boolean {
        try {
            var r = false
            var via = "pager"
            // ⚠️ 优先用**确定性信号**（真机 10-12 实锤）：主 pager 的当前页索引由
            // setCurrentItem 钩子直接记录。"我的页白卡 iss 的 x 坐标"判据太脆——
            // 我的页落位后 iss 的 x 未必落在窄阈值内 → 门禁判 false → 刚隐藏的
            // 「赚钱」入口立刻被 reevaluateGatedHides 恢复 → 用户看到"赚钱还在"。
            if (mainPageKnown) {
                r = mainPageIndex == 1
            } else {
                // ⚠️ 兜底路径记忆化（本轮，冷启动关键）：首次翻页前 mainPageKnown=false，
                // 而门禁规则（requireMinePage）在 scan / preHideSelf / matchIdRule 里会被
                // 反复求值——原实现每次都做**全树查找 iss**（上千节点）。启动窗口内扫描
                // 是逐帧的，等于每帧数次全树遍历 = 冷启动掉帧主因之一。
                // 页面身份在启动期不会变化（此时还没有翻页），200ms 缓存完全安全。
                via = "iss"
                val now = android.os.SystemClock.uptimeMillis()
                if (minePageMemoValid && now - minePageMemoAt < 200L) {
                    r = minePageMemo
                } else {
                    val decor = from.rootView
                    val dm = from.resources.displayMetrics
                    val loc = IntArray(2)
                    r = decor != null && findOnScreenId(decor, "iss", dm.widthPixels, loc, 0)
                    minePageMemo = r
                    minePageMemoAt = now
                    minePageMemoValid = true
                }
            }
            return r
        } catch (_: Throwable) {
            return false
        }
    }

    private var minePageMemo = false
    private var minePageMemoAt = 0L
    private var minePageMemoValid = false

    /** 全树查找 ID 名命中且位于屏幕内的可见视图 */
    private fun findOnScreenId(v: View?, idName: String, screenW: Int, loc: IntArray, depth: Int): Boolean {
        if (v == null || depth > MAX_DEPTH) return false
        if (v.width > 0 && v.visibility == View.VISIBLE && resourceName(v) == idName) {
            v.getLocationOnScreen(loc)
            // ⚠️ 判据收紧（真机 10-12，根治"我的页赚钱闪一下"）：原判据 |x| < 屏宽/4
            // 意味着翻页动画进行到 ~75% 时门禁就成立 → 在页面**已画出来一半**时
            // 才隐藏"赚钱" = 用户看到闪一下。改为要求页面**基本落位**（|x| ≤ 24，
            // 页宽 1220：离屏页 x≈±1220，落位页 x≈0），配合"目标页预置"在动画开始
            // 前就隐藏，动画期间不再来回切换身份。
            if (loc[0] > -24 && loc[0] < 24) return true
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (findOnScreenId(v.getChildAt(i), idName, screenW, loc, depth + 1)) return true
            }
        }
        return false
    }

    /**
     * 白卡的最终观感色：卡面 Drawable 是 `#80ffffff`（50% 半透明白），叠在页面
     * 底色 (220,245,253) 上 = 实测卡面 (238,250,254)。
     * ⚠️ 不能用 #FFFFFF，也不能用卡面原本的半透明白：半透明白再叠 50% 白 =
     * 双重提亮到 247（R20e 踩坑，比原光晕印子还显眼）。
     */
    private const val CARD_WHITE = 0xFFEEFAFE.toInt()

    /** 已涂过的底板 → 我们设置的 Drawable 引用（判断背景是否被 app 覆盖） */
    private val flattenedPlates = WeakHashMap<View, Drawable>()

    /** 底板专用背景：卡面同色实底 + 右侧两角 8dp 圆角（实测白卡四角 R≈25px=8dp） */
    private var platePaint: GradientDrawable? = null

    /**
     * 抹平会员角标底板（iv5）与白卡的色差（Round 20 用户实锤"底板和白卡颜色
     * 不一样"）。结构事实（480dpi，density=3.0）：
     * 1. 底板 iv5 (836,297) 330x237 是白卡 iss (54,297) 1112x225 的**兄弟**
     *    （同属 isr），绘制顺序在后 —— 其背景是**以贴纸为中心的渐变光晕**
     *    （自定义 Drawable，探测类名 "i"），把该区域从卡面 238 压暗到最暗 222
     *    （Δ16）。贴纸 c6u 隐形后光晕残留成一块灰蓝印子；
     * 2. 底板右边缘 x=1166 与白卡右边缘**完全贴齐**，上沿同为 297，因此把底板
     *    涂成卡面同色、右侧两角按卡面 R=8dp 收圆，右边缘就完全无缝；
     * 3. 底板下沿 534 比白卡 522 多 12px（原光晕只画到贴纸范围，故此前看不出），
     *    全矩形平涂会露出一条 4dp 白边 → 先对齐下沿。
     * 做法：只换背景色 + 裁下沿，**几何其余不变、不 GONE**（iv5 是约束子，
     * GONE 会令 HomeCardConstraintLayout 求解发散把白卡缩到 836 宽）。
     * ⚠️ 必须**幂等可重入**：app 绑定阶段会重设 iv5.background 覆盖我们的，
     * 用引用比对判断，被覆盖就重涂（挂载预隐藏 / 多帧复核 / 每 4s 周期扫描
     * 都会调用本函数）。
     */
    private fun neutralizeBadgePlate(sticker: View) {
        try {
            // 底板 = 贴纸父视图 iv5（贴纸可能复用，加 ID / 近亲兜底）
            val plate = (sticker.parent as? View)?.takeIf { resourceName(it) == "iv5" }
                ?: findSiblingId(sticker, "iv5", 3)
                ?: return
            val card = findSiblingId(plate, "iss", 3)
            // 底板下沿比白卡多 12px → 对齐，否则露一条 4dp 白边
            if (card != null && card.height > 0 && plate.height > 0) {
                val want = (card.top + card.height - plate.top).coerceAtLeast(1)
                val lp = plate.layoutParams as? ViewGroup.LayoutParams
                if (lp != null && lp.height != want) {
                    // ⭐ R67：首次改动前存原始高度
                    if (!savedPlateHeights.containsKey(plate)) savedPlateHeights[plate] = lp.height
                    lp.height = want
                    plate.layoutParams = lp
                }
            }
            val d = platePaint ?: buildPlatePaint(plate) ?: return
            if (plate.background !== d && !savedBackgrounds.containsKey(plate)) {
                // ⭐ R67：首次改动前存宿主原来的底板背景
                savedBackgrounds[plate] = plate.background
            }
            if (plate.background !== d) {
                plate.background = d
                flattenedPlates[plate] = d
                plate.invalidate()
                ModuleLog.log(
                    "UI净化[我的页会员角标] 底板涂卡面色 #${
                        Integer.toHexString(CARD_WHITE).uppercase()
                    } plate=${resourceName(plate)} ${plate.width}x${plate.height} " +
                        "card=${card?.let { resourceName(it) }} v=${Integer.toHexString(plate.hashCode())}"
                )
            }
        } catch (_: Throwable) {
        }
    }

    /** 底板背景：卡面同色实底，右侧两角 8dp（左上/左下在白卡内部，直角不可见） */
    private fun buildPlatePaint(plate: View): GradientDrawable? {
        return try {
            val r = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8f, plate.resources.displayMetrics
            )
            val d = GradientDrawable()
            d.setColor(CARD_WHITE)
            d.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            platePaint = d
            d
        } catch (_: Throwable) {
            null
        }
    }

    /** 在兄弟/近亲层级按 ID 名找视图（最多上溯 levels 层） */
    private fun findSiblingId(from: View, idName: String, levels: Int): View? {
        var node = from.parent as? ViewGroup
        var depth = 0
        while (node != null && depth < levels) {
            for (i in 0 until node.childCount) {
                val c = node.getChildAt(i) ?: continue
                if (c !== from && resourceName(c) == idName) return c
            }
            node = node.parent as? ViewGroup
            depth++
        }
        return null
    }

    /** 归还被隐藏的视图（先 hiddenByUs.remove 再设 VISIBLE——顺序不可反） */
    private fun restoreHidden(v: View, why: String) {
        try {
            val h = savedHeights.remove(v)
            if (h != null && h >= 0) {
                (v.layoutParams as? ViewGroup.LayoutParams)?.let { lp ->
                    lp.height = h; v.layoutParams = lp
                }
            }
            restoreAlphaShield(v)
            hiddenByUs.remove(v)
            invisibleHides.remove(v)
            if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
            ModuleLog.log("UI净化[$why] 恢复: ${v.javaClass.simpleName} v=${Integer.toHexString(v.hashCode())}")
        } catch (_: Throwable) {
        }
    }

    /**
     * 页面门禁重评：主 pager 翻页后，把"页面门禁不再通过"的隐藏还回去
     * （发现页顶栏 dk_ 在切回发现时必须恢复为「乐库」，否则复活压制会
     * 让它一直隐形）
     */
    private fun reevaluateGatedHides(why: String = "周期") {
        try {
            mainHandler.post {
                try {
                    for ((v, rule) in hiddenByUs.entries.toList()) {
                        if (!rule.requireMinePage) continue
                        if (gateOk(v, rule)) continue
                        restoreHidden(v, "页面切换[$why${gateDiag(v, rule)}]")
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** 门禁抖动诊断开关（取证用；Round 22 验证完毕 → 关闭，避免额外日志与全树查找） */
    private const val GATE_DIAG = false

    /** 门禁求值快照：mainPageKnown/index、兄弟 ID 是否在、位置法原始判据 */
    private fun gateDiag(v: View, rule: Rule): String {
        if (!GATE_DIAG) return ""
        return try {
            val sb = StringBuilder(" known=").append(mainPageKnown).append(" idx=").append(mainPageIndex)
            val sn = rule.requireSiblingIdName
            if (sn != null) {
                val p = v.parent as? ViewGroup
                var sib = false
                if (p != null) {
                    for (i in 0 until p.childCount) {
                        val c = p.getChildAt(i) ?: continue
                        if (c !== v && resourceName(c) == sn) { sib = true; break }
                    }
                }
                sb.append(" sib=").append(sib)
            }
            if (rule.requireMinePage) {
                sb.append(" rawPos=").append(minePageRawPos(v))
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                sb.append(" x=").append(loc[0])
            }
            sb.toString()
        } catch (_: Throwable) {
            " diagErr"
        }
    }

    /** 位置法（不看 mainPageKnown）的"我的页是否在屏"原始判据，仅诊断用 */
    private fun minePageRawPos(from: View): Boolean {
        return try {
            val decor = from.rootView ?: return false
            val dm = from.resources.displayMetrics
            findOnScreenId(decor, "iss", dm.widthPixels, IntArray(2), 0)
        } catch (_: Throwable) {
            false
        }
    }

    private fun hideBlockContainer(textView: TextView, rule: Rule): Boolean {
        try {
            // ⚠️ 双重铁律（两轮真机实锤）：
            // 1. 必须爬到 item 根（回收容器直接子）——只藏 item 内部容器，
            //    item 测量高度不塌缩
            // 2. 必须显式 height=0——RecyclerView/LinearLayoutManager 对 GONE
            //    child 仍按其测量高度分配空间（同 hideFeedItemSlot 的坑：
            //    "只 GONE 仍占位出空白——铁律"），GONE 不绘白不渲染但占位！
            var itemRoot: ViewGroup? = null
            var node = textView.parent as? ViewGroup
            while (node != null) {
                // 页面级容器即停（真机实锤：我的页无 RecyclerView 时原逻辑
                // 会爬到 MineInterruptFrameLayout 把整页 GONE → 我的页空白）
                if (isPageLevelContainer(node)) break
                itemRoot = node
                node = node.parent as? ViewGroup
            }
            val target = itemRoot ?: return false
            if (target === textView.rootView) return false
            val p = target.parent as? View
            if (p == null || !isRecycleContainer(p)) return false
            // ⚠️ R59：这里**只能用 INVISIBLE，不能用 GONE**。
            // 真机实锤（20:36 日志）：设成 GONE 后 ~14ms 内 RecyclerView 就把该 item
            // 从窗口分离（onDetachedFromWindow）⇒ onViewDetached 触发「分离还原」把
            // 它恢复成 VISIBLE ⇒ 4s 后的周期扫描又把它藏回去 ⇒ **hide↔restore 无限振荡**
            // （免费听区块/频道推荐区块/概念实验室 三个规则都在振，每 4s 一轮），
            // 期间该 item 的测量高度在 0 与正常之间来回跳 = feed 布局持续抖动。
            // INVISIBLE 仍然是「参与布局」的可见性，RV 不会因为它分离该 child；
            // 高度由下面的 height=0 收掉，视觉上同样不占位、不绘制。
            // 复活压制照旧：invisibleHides 标记 ⇒ setVisibility hook 压回 INVISIBLE。
            val alreadyHidden = target.visibility != View.VISIBLE
            val alreadyZero = target.layoutParams?.height == 0
            val alreadyCollapsed = target.layoutParams?.height == COLLAPSED_ITEM_HEIGHT_PX
            if (alreadyHidden && (alreadyCollapsed || !COLLAPSE_ITEM_HEIGHT)) return true
            if (!alreadyHidden) {
                savedHeights[target] = target.layoutParams?.height ?: -2
                target.visibility = View.INVISIBLE
                invisibleHides[target] = true
            }
            if (COLLAPSE_ITEM_HEIGHT && !alreadyCollapsed) {
                (target.layoutParams as? ViewGroup.LayoutParams)?.let { lp ->
                    lp.height = COLLAPSED_ITEM_HEIGHT_PX
                    target.layoutParams = lp
                }
            }
            hiddenByUs[target] = rule
            ModuleLog.log("UI净化[${rule.name}] 隐藏(整卡·item根): ${target.javaClass.simpleName} INVISIBLE+height=0")
            return true
        } catch (_: Exception) { return false }
    }

    /** 整卡模式：先藏容器，再 50/150/400ms 三档补爬（文本晚于布局到达） */
    private fun hideCardWithRecheck(textView: TextView, rule: Rule): Boolean {
        val did = hideBlockContainer(textView, rule)
        for (delay in longArrayOf(50, 150, 400)) {
            mainHandler.postDelayed({
                try {
                    if (textView.isAttachedToWindow) hideBlockContainer(textView, rule)
                } catch (_: Throwable) {}
            }, delay)
        }
        return did
    }

    /**
     * 开关重新开启时立即重扫当前界面（Settings.setFeature 调用）
     */
    fun rescan(ctx: Context?) {
        try {
            val activity = currentActivity?.get() ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            if (activity.window?.decorView == null) return
            mainHandler.post { safeScan(activity) }
            for (delay in longArrayOf(300, 800, 2000)) {
                mainHandler.postDelayed({ safeScan(activity) }, delay)
            }
        } catch (_: Exception) {
            // 静默
        }
    }

    /** 开关关闭时立即恢复被该规则隐藏的控件（主线程化：WeakHashMap 只在主线程访问） */
    fun restoreRule(ruleKey: String) {
        mainHandler.post {
            // 免费听图标关掉：把补位平移的搜索图标放回原位、dg1 的 alpha 还回去
            //（这两项不在 hiddenByUs 体系里，它只记「被 GONE 的视图」）
            if (ruleKey == Settings.KEY_UI_FREE_LISTEN_ICON) {
                for (v in shiftedByUs.keys.toList()) {
                    try { v.translationX = 0f } catch (_: Throwable) {}
                }
                shiftedByUs.clear()
                for (p in neutralizedPlates.keys.toList()) {
                    try {
                        if (p.alpha == 0f) p.alpha = 1f
                        // ⭐ R67：把被抹掉的胶囊底板背景还回去（此前只还 alpha
                        //    ⇒ 关开关后免费听图标"光秃秃"没有底）
                        if (savedBackgrounds.containsKey(p)) {
                            val bg = savedBackgrounds[p]
                            if (p.background !== bg) {
                                p.background = bg
                                p.invalidate()
                                ModuleLog.log("UI净化[即时生效] 免费听底板背景已还原")
                            }
                            savedBackgrounds.remove(p)
                        }
                        savedAlphas.remove(p)
                    } catch (_: Throwable) {}
                }
                neutralizedPlates.clear()
            }
            // ⭐ R67：我的页会员角标——把被换掉的底板背景与改过的高度还原
            //（此前完全没管，关开关后底板仍是"卡面同色 + 被对齐过的下沿"）
            if (ruleKey == Settings.KEY_UI_MINE_MEMBER_BADGE) {
                for (plate in flattenedPlates.keys.toList()) {
                    try {
                        if (savedBackgrounds.containsKey(plate)) {
                            val bg = savedBackgrounds[plate]
                            if (plate.background !== bg) {
                                plate.background = bg
                                plate.invalidate()
                                ModuleLog.log("UI净化[即时生效] 会员角标底板背景已还原")
                            }
                            savedBackgrounds.remove(plate)
                        }
                        val h = savedPlateHeights[plate]
                        if (h != null) {
                            (plate.layoutParams as? ViewGroup.LayoutParams)?.let { lp ->
                                lp.height = h
                                plate.layoutParams = lp
                            }
                            savedPlateHeights.remove(plate)
                        }
                    } catch (_: Throwable) {}
                }
                flattenedPlates.clear()
            }
            val snapshot = hiddenByUs.entries.toList()
            for ((view, rule) in snapshot) {
                if (rule.key != ruleKey) continue
                try {
                    // 还原被 hideBlockContainer/hideFeedItemSlot 清零的高度
                    // （只恢复 VISIBLE 不还原 height 的话 item 仍是 0 高不可见）
                    val h = savedHeights[view]
                    if (h != null && h >= 0) {
                        (view.layoutParams as? ViewGroup.LayoutParams)?.let { lp ->
                            lp.height = h
                            view.layoutParams = lp
                        }
                        savedHeights.remove(view)
                    }
                    // ⭐ R85：把「alpha 保险」置的 0 还回原值（与隐藏侧成对，缺了它
                    //   就会出现"关掉开关书签栏仍然看不见"）。
                    savedAlphas[view]?.let { a -> view.alpha = a }
                    savedAlphas.remove(view)
                    if (view.visibility != View.VISIBLE) {
                        view.visibility = View.VISIBLE
                        ModuleLog.log("UI净化[即时生效] 恢复: ${view.javaClass.name}")
                    }
                } catch (_: Exception) {}
                restoreAlphaShield(view)
                hiddenByUs.remove(view)
                invisibleHides.remove(view)
            }
        }
    }

    private fun isOwnPanel(view: View): Boolean {
        var v: View? = view
        var depth = 0
        while (v != null && depth < 20) {
            if (v.tag == SettingsEntry.PANEL_OVERLAY_TAG) return true
            v = v.parent as? View
            depth++
        }
        return false
    }

    /**
     * 资源 ID → 资源名缓存（热路径）。
     *
     * `Resources.getResourceEntryName` 走 AssetManager 查表并分配字符串；
     * scan / preHideSelf / onViewAttached / matchIdRule / gateOk 会在滚动与
     * 切页时按节点高频调用它 → 单次成本虽小，乘以每帧数百节点即成为掉帧因子。
     * 资源 ID 在进程内恒定，故用 SparseArray（无装箱）做 int→String 记忆化。
     */
    private val idNameCache = android.util.SparseArray<String>(64)

    private fun resourceName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID) return null
        // Round 50：miss 查表也放进同一把锁 —— miss 每 ID 只发生一次，锁内查表
        // 无额外竞争代价，反而避免两个线程同时 miss 时对同一 ID 重复查表+重复分配
        synchronized(idNameCache) {
            idNameCache.get(id)?.let { return it }
            return try {
                val n = view.resources.getResourceEntryName(id)
                if (idNameCache.size() < 8192) idNameCache.put(id, n)
                n
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 角标诊断开关（默认关；见 diagBadge 的性能说明）。
     * ⚠️ 必须是 `const val`：调用点写成 `if (BADGE_DIAG) diagBadge(v, "setVis=$arg")`
     * 才能被编译期折叠为常量 false，从而**连字符串拼接一起消除**。原实现把
     * `"setVis=$arg"` 直接作为实参传入，导致每次 setVisibility 都白白分配一个
     * String（App 自身的 setVisibility 调用极频繁 = 明确的掉帧/GC 来源）。
     */
    private const val BADGE_DIAG = false

    /**
     * 角标诊断（at6 容器 / at5 数字）：定位"数据到达后才显示"的可见性路径。
     * 只在启动窗口内启用，避免污染 setVisibility 热路径。
     */
    private fun diagBadge(view: View, tag: String) {
        // ⚠️ 性能：诊断默认关闭。它会在**每次挂载/分离/setVisibility** 做一次
        // resourceName（资源查表）——纯诊断开销，实测是热路径可见成本。
        // 需要重新定位角标路径时把 BADGE_DIAG 改回 true 即可。
        if (!BADGE_DIAG) return
        try {
            if (android.os.SystemClock.uptimeMillis() > preDrawUntilUptime + 8000L) return
            val rn = resourceName(view) ?: return
            if (rn != "at6" && rn != "at5") return
            val p = view.parent as? View
            ModuleLog.log(
                "角标诊断[$tag] $rn vis=${view.visibility} ${view.width}x${view.height}" +
                    " 父=${p?.javaClass?.simpleName ?: "-"}/${if (p != null) resourceName(p) ?: "-" else "-"}" +
                    " 已绘制=${if (wasDrawn(view)) "是" else "否"}"
            )
        } catch (_: Throwable) {
        }
    }

    /** 主界面容器归属判定（加号悬浮球区域判定前置校验） */
    /** 祖先链上是否有列表容器（feed 列表内的普通图片不能被结构门禁误伤） */
    private fun hasRecycleAncestor(v0: View): Boolean {
        var p = v0.parent as? View
        var depth = 0
        while (p != null && depth < 32) {
            if (isRecycleContainer(p)) return true
            p = p.parent as? View
            depth++
        }
        return false
    }

    private fun inMainContainer(v0: View): Boolean {
        var p = v0.parent as? View
        var depth = 0
        while (p != null && depth < 32) {
            val cn = p.javaClass.name
            if (cn.contains("MainFragmentViewPage") || cn.contains("MainContainerLayout")
                || cn.contains("FragmentViewHome")
            ) return true
            p = p.parent as? View
            depth++
        }
        return false
    }

    private fun hasAncestor(view: View, className: String): Boolean {
        var p = view.parent as? View
        var depth = 0
        while (p != null && depth < 32) {
            if (p.javaClass.name.contains(className)) return true
            p = p.parent as? View
            depth++
        }
        return false
    }

    /** 祖先 ID 门禁（Round 46）：沿父链限深 12 找 resourceName 命中；见 Rule.ancestorIdNames */
    private fun hasAncestorById(view: View, idNames: List<String>): Boolean {
        var p = view.parent as? View
        var depth = 0
        while (p != null && depth < 12) {
            val rn = resourceName(p)
            if (rn != null && rn in idNames) return true
            p = p.parent as? View
            depth++
        }
        return false
    }

    /** 命中后向上爬 levels 层找外层容器整体隐藏；碰到共享回收容器即停 */
    private fun climbParent(view: View, levels: Int): View {
        var target: View = view
        var node = view.parent as? ViewGroup
        var climbed = 0
        while (node != null && climbed < levels) {
            if (isRecycleContainer(node)) break
            target = node
            node = node.parent as? ViewGroup
            climbed++
        }
        return target
    }

    /** ID 制导规则的完整判定：ID名+区域+祖先；未布局时跳过区域判定 */
    private fun matchIdRule(
        view: View, rule: Rule, skipPageGate: Boolean = false, fromMount: Boolean = false,
    ): Boolean {
        val rn = resourceName(view) ?: return false
        if (rn !in rule.idNames) return false
        // ⚠️ 类名排除门禁（真机实锤 10-12）：复核路径此前漏检这里——
        // 「我的页赚钱入口」(dk_) 在首页顶栏复核对 MainTopImageView 仍然通过，
        // 把"乐库"入口在**已绘制之后**再隐藏一次（用户看到乐库闪一下/消失）。
        if (rule.excludeClassNames.isNotEmpty()
            && rule.excludeClassNames.any { view.javaClass.name.contains(it) }
        ) return false
        // ⚠️ 未布局（width==0 且 height==0）只跳过**几何/区域**判定，**不跳过语义门禁**。
        // Round 22 铁律（真机实锤，本轮的闪烁根因）：原实现在未布局时直接 `return true`，
        // 连页面门禁/兄弟门禁一起绕过 → 发现页顶栏 dk_（乐库）在挂载瞬间被
        // 「我的页赚钱入口」误命中隐藏；而一旦被 GONE，该视图**永远不参与 measure**
        // → width 恒为 0 → 每次恢复后 14ms 复查又走这条早退分支再隐藏 =
        // 无限 apply→restore→apply 拉锯（约 1.8s 一轮，且"已绘制=是" = 用户可见闪烁）。
        // Round 48（0 闪现专项）：**挂载时刻（addView/inflate/setId 钩子）区域判定
        // 整体跳过** —— 此时视图未 measure，location 要么全 0（首挂）、要么是
        // RecyclerView 复用前的**旧位置**（从 mCachedViews 取出 re-add 的 item），
        // 拿去判 minY/maxY 必错：搜索推广关闭钮/指示器×6、TAB角标×2、播放页书签×3
        // 的「已绘制=是」全走这条弯路（挂载被区域门禁拒掉 → 等扫描才藏 = 已画一帧）。
        // 改为挂载即藏（0 闪现），布局完成后多帧复核用**完整判定**，误藏由
        // executeIdRecheck 自动恢复（恢复逻辑已在，真位置不符 → VISIBLE）。
        if (!fromMount) {
            val laidOut = !(view.width == 0 && view.height == 0)
            if (laidOut) {
                val dm = view.resources.displayMetrics
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                if (rule.minYRatio > 0f && loc[1] < dm.heightPixels * rule.minYRatio) return false
                if (rule.maxYRatio > 0f && loc[1] > dm.heightPixels * rule.maxYRatio) return false
                if (rule.minWRatio > 0f && view.width < dm.widthPixels * rule.minWRatio) return false
                if (rule.region != null && (loc[0] <= dm.widthPixels * rule.region[0]
                            || loc[1] <= dm.heightPixels * rule.region[1])) return false
            }
        }
        if (rule.ancestorClass != null && !hasAncestor(view, rule.ancestorClass)) return false
        if (rule.ancestorIdNames.isNotEmpty() && !hasAncestorById(view, rule.ancestorIdNames)) return false
        // 页面门禁可跳过（目标页预置用：翻页动画开始前按"目标页"决定，此时
        // minePageOnScreen 仍反映旧页，必须绕过）
        if (!(skipPageGate && rule.requireMinePage) && !gateOk(view, rule)) return false
        return true
    }

    /**
     * 取出视图可匹配的文本。
     *
     * ⚠️ 返回 CharSequence 而非 String（真机性能）：本函数在挂载/扫描热路径上
     * 对每个 TextView 都会调用，原实现 `it.toString()` 会为**每一段文本**分配
     * 一个 String（滚动时每秒上千次 → GC 压力 → 掉帧）。规则匹配用
     * `CharSequence.contains`，无需物化 String；仅日志处才 toString。
     */
    private fun extractText(view: View): CharSequence? {
        if (view is TextView) {
            view.text?.let { if (it.isNotEmpty()) return it }
            view.hint?.let { if (it.isNotEmpty()) return it }
        }
        view.contentDescription?.let { if (it.isNotEmpty()) return it }
        return null
    }

    private fun tryAutoClickSkip(root: View) {
        findAndClickSkip(root, 0)
    }

    private fun findAndClickSkip(view: View?, depth: Int): Boolean {
        if (view == null || depth > MAX_DEPTH) return false
        if (view is TextView && view.visibility == View.VISIBLE) {
            val text = view.text?.toString()?.trim()
            if (text == SKIP_TEXT) {
                // 只点屏幕上部 1/4 的"跳过"（防误点播放器"跳过片头"）
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val dm = view.resources.displayMetrics
                if (loc[1] > dm.heightPixels * 0.25f) return false
                view.performClick()
                ModuleLog.log("UI净化[开屏广告] 自动点击跳过")
                return true
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (findAndClickSkip(view.getChildAt(i), depth + 1)) return true
            }
        }
        return false
    }

    private fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? =
        Reflector.findMethod(clazz, name, *paramTypes)
}
