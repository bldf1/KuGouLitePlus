package com.bldf1.kugoulite.plus

import android.content.Context
import java.io.File

/**
 * 模块配置与运行状态读写（单进程：直接存取酷狗概念版的 SharedPreferences）
 *
 * 模块 APP 无界面，所有配置/状态都在 Hook 进程内直接读写。
 */
object Settings {

    private const val PREFS_NAME = "kugoulite_vip_config"

    // ===== 主功能 =====
    const val KEY_ENABLED = "enabled"
    const val KEY_AUTO_UPGRADE = "auto_upgrade"
    const val KEY_LISTEN_TASK = "listen_task"
    const val KEY_BLOCK_AD_NET = "block_ad_net"
    const val KEY_BLOCK_TASK_PROMO = "block_task_promo"

    // ===== 界面净化（每控件一个开关；Round 52 细分）=====
    const val KEY_UI_SPLASH = "ui_splash_ad"
    /** 首页Banner：MosaicView 广告轮播（含出生拦截） */
    const val KEY_UI_BANNER = "ui_banner_ad"
    /** 发现页广告轮播：Discovery*AdsView 系列出生拦截 */
    const val KEY_UI_DISCOVER_ADS = "ui_discover_ads"
    /** 发现页 feed 顶部的 Banner 槽位（ela ID 制导） */
    const val KEY_UI_DISCOVER_BANNER_SLOT = "ui_discover_banner_slot"
    /** 发现页淘宝推广条（「上淘宝更优惠」倒计时条） */
    const val KEY_UI_TAOBAO_BAR = "ui_taobao_bar"
    /** 发现页天猫活动大卡（「天猫双11」等档期卡） */
    const val KEY_UI_TMALL_CARD = "ui_tmall_card"
    const val KEY_UI_PLAY_AD = "ui_play_ad"
    const val KEY_UI_PLAY_BOOKMARK = "ui_play_bookmark"
    const val KEY_UI_WEEKLY_WELFARE = "ui_weekly_welfare"
    const val KEY_UI_PLAY_PUBLISH_TIP = "ui_play_publish_tip"
    /** 播放页歌词下方的「咱一起自律」频道入口（Round 51，默认隐藏） */
    const val KEY_UI_PLAY_DISCIPLINE = "ui_play_discipline"
    const val KEY_UI_SEARCH_AD = "ui_search_ad"
    const val KEY_UI_SEARCH_BANNER = "ui_search_banner"
    const val KEY_UI_SEARCH_CLOSE = "ui_search_close"
    const val KEY_UI_SEARCH_INDICATOR = "ui_search_indicator"
    const val KEY_UI_SEARCH_VIP_BAR = "ui_search_vip_bar"
    const val KEY_UI_MEMBER_BAR = "ui_member_bar"
    const val KEY_UI_MEMBER_WIDGET = "ui_member_widget"
    const val KEY_UI_MINE_SIGN_BAR = "ui_mine_sign_bar"
    const val KEY_UI_MINE_MEMBER_BADGE = "ui_mine_member_badge"
    const val KEY_UI_VIP_EXPIRE = "ui_vip_expire"
    const val KEY_UI_VIP_LOOK_BAR = "ui_vip_look_bar"
    const val KEY_UI_PLUS = "ui_plus_btn"
    const val KEY_UI_INVITE = "ui_invite_tip"
    const val KEY_UI_LAB = "ui_lab"
    const val KEY_UI_LOGIN_GUIDE = "ui_login_guide"
    const val KEY_UI_FREE_LISTEN_ENTRY = "ui_free_listen_entry"
    const val KEY_UI_FREE_LISTEN_ICON = "ui_free_listen_icon"
    const val KEY_UI_FREE_LISTEN_BLOCK = "ui_free_listen_block"
    const val KEY_UI_MINE_TAB_BAR = "ui_mine_tab_bar"
    const val KEY_UI_MINE_EARN = "ui_mine_earn"
    const val KEY_UI_MINE_MEDAL = "ui_mine_medal"
    const val KEY_UI_MINE_SEARCH = "ui_mine_search"
    /** 我的页右上角信息中心/设置图标的红点（Round 46，默认隐藏） */
    const val KEY_UI_MINE_BADGE = "ui_mine_badge"
    const val KEY_UI_TAB_BADGE = "ui_tab_badge"
    const val KEY_UI_CHANNEL = "ui_channel"
    /** 悬浮底栏：把宿主底栏收窄圆角成贴底胶囊（Round 24） */
    const val KEY_UI_FLOATING_BAR = "ui_floating_bar"
    /** 胶囊填充层改用液态玻璃材质（Round 28，API 33+；关掉则胶囊不含填充层） */
    const val KEY_UI_LIQUID_GLASS = "ui_liquid_glass"

    // ===== 防检测 =====
    const val KEY_BYPASS_SIGNATURE = "bypass_signature"
    const val KEY_BLOCK_UPDATE = "block_update"

    /**
     * 词幕(Lyricon)歌词提供器（Round 35，默认开启）
     *
     * ⚠️ 这个开关由「面板所在的主进程」写、「播放服务所在的 :support 进程」读，
     * 所以读取方**必须**用 [readBool] 直读 prefs，不能走 [isFeature] 的内存缓存
     * （两个进程各有一份缓存，写不会互相失效 ⇒ 会出现「改了开关要重启才生效」）。
     */
    const val KEY_LYRIC_PROVIDER = "lyric_provider"

    // 状态键
    const val KEY_STATUS_MSG = "status_msg"
    const val KEY_STATUS_CODE = "status_code"
    const val KEY_STATUS_TIME = "status_time"

    private fun getPrefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 从 prefs XML 里抠 `<boolean name="lyric_provider" value="…"/>` 元素与它的值 */
    private val LYRIC_BOOL_TAG = Regex("""<boolean[^>]*\bname="lyric_provider"[^>]*>""")
    private val BOOL_VALUE_ATTR = Regex("value=\"(true|false)\"")

    fun isEnabled(ctx: Context): Boolean = getPrefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) {
        getPrefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
        ModuleLog.log("自动领取开关: $value")
    }

    fun isAutoUpgrade(ctx: Context): Boolean = getPrefs(ctx).getBoolean(KEY_AUTO_UPGRADE, true)

    /**
     * 是否网络层拦截广告请求（默认开启）
     */
    fun isBlockAdNet(ctx: Context): Boolean = getPrefs(ctx).getBoolean(KEY_BLOCK_AD_NET, true)

    /**
     * 是否屏蔽「任务 / 赚钱推广」相关接口（默认开启）
     *
     * 拦截对象：赚钱页任务中心、VIP 到期提示等 —— 这些接口返回的数据会触发
     * 「金币飞入动画」「即将过期提醒」等一次性推广元素；这些元素**不在页面视图树里**
     * 或是一次性动画，视图层规则拦不住 ⇒ 改在网络层让 app 认为"没有任务/没有到期提示"。
     */
    fun isBlockTaskPromo(ctx: Context): Boolean = getPrefs(ctx).getBoolean(KEY_BLOCK_TASK_PROMO, true)

    fun setAutoUpgrade(ctx: Context, value: Boolean) {
        getPrefs(ctx).edit().putBoolean(KEY_AUTO_UPGRADE, value).apply()
        ModuleLog.log("自动升级开关: $value")
    }

    /**
     * 通用功能开关（默认开启；用于听歌任务/界面净化/防检测等所有布尔配置）
     *
     * 内存缓存：isFeature 被 Dialog.show / 触摸事件 / setText 等热路径高频调用，
     * 每次都读 SharedPreferences（磁盘+mmap+解析）开销不可接受。
     * 首读缓存、写时刷新（Hook 与面板同进程，写必然经过 setFeature，无需失效机制）。
     */
    private val featureCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun isFeature(ctx: Context, key: String): Boolean =
        featureCache.getOrPut(key) { getPrefs(ctx).getBoolean(key, true) }

    /**
     * 直读开关（**绕过** [featureCache]）。
     *
     * 仅解决「同一进程内缓存不刷新」的问题；跨进程（见 [readLyricProvider]）仍然不够。
     */
    fun readBool(ctx: Context, key: String, default: Boolean): Boolean =
        try {
            getPrefs(ctx).getBoolean(key, default)
        } catch (_: Throwable) {
            default
        }

    /**
     * 读「词幕歌词提供器」开关 —— **直接解析落盘的 XML**。
     *
     * ⚠️ 这里不能用 SharedPreferences（哪怕 [readBool] 也不行）：
     * 宿主的每个进程各持有一份独立的 `SharedPreferencesImpl`，其 `mMap` 在首次
     * `getSharedPreferences` 时一次性载入，之后**不会**因为别的进程改了文件而重载
     * （能触发重载的 `MODE_MULTI_PROCESS` 早在 API 23 就被废弃、等同于 MODE_PRIVATE）。
     * 而这个开关恰好是「面板所在的主进程写、播放服务所在的 :support 进程读」，
     * 走 SharedPreferences 就会出现**改了开关要重启 App 才生效**。
     * 直接读文件则永远是最新落盘值（写入方用 [setLyricProvider] 的 commit 保证同步落盘）。
     */
    fun readLyricProvider(ctx: Context): Boolean = try {
        val f = File(ctx.dataDir, "shared_prefs/$PREFS_NAME.xml")
        if (!f.isFile) {
            true
        } else {
            val tag = LYRIC_BOOL_TAG.find(f.readText())?.value
            val v = tag?.let { BOOL_VALUE_ATTR.find(it)?.groupValues?.get(1) }
            v?.equals("true", ignoreCase = true) ?: true
        }
    } catch (_: Throwable) {
        readBool(ctx, KEY_LYRIC_PROVIDER, true)
    }

    /**
     * 写「词幕歌词提供器」开关。
     *
     * 用 `commit()` 而不是 `apply()`：apply 是异步落盘，而读取方在**另一个进程**
     * 直接解析 XML 文件，异步窗口内会读到旧值。这个开关是用户手动点的低频操作，
     * 一次同步写盘可以接受。
     */
    fun setLyricProvider(ctx: Context, value: Boolean) {
        featureCache[KEY_LYRIC_PROVIDER] = value
        try {
            getPrefs(ctx).edit().putBoolean(KEY_LYRIC_PROVIDER, value).commit()
        } catch (_: Throwable) {
        }
        ModuleLog.log("词幕歌词提供器开关: $value")
    }

    fun setFeature(ctx: Context, key: String, value: Boolean) {
        featureCache[key] = value
        getPrefs(ctx).edit().putBoolean(key, value).apply()
        ModuleLog.log("功能[$key]: $value")
        if (!key.startsWith("ui_")) return
        // 界面净化开关双向即时生效：关闭立即恢复控件，开启立即重扫隐藏
        if (value) UiCleaner.rescan(ctx)
        else UiCleaner.restoreRule(key)
    }

    fun getStatusMessage(ctx: Context): String? = getPrefs(ctx).getString(KEY_STATUS_MSG, null)

    /**
     * 回写运行状态（同时通知悬浮面板刷新显示）
     */
    fun putStatus(ctx: Context, message: String?, code: Int) {
        getPrefs(ctx).edit()
            .putString(KEY_STATUS_MSG, message)
            .putInt(KEY_STATUS_CODE, code)
            .putLong(KEY_STATUS_TIME, System.currentTimeMillis())
            .apply()
        SettingsEntry.onStatusUpdated()
    }
}
