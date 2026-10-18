package com.bldf1.kugoulite.plus

import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 频道页「发帖引导条」拦截（ChannelPostGuideNotice）
 *
 * 现象：进入频道页后底部滑入一条 55dp 横幅（标题 + 副标题 + 「去发布」按钮），
 * 带 5 秒倒计时，随后自行滑出消失。文案**不来自网络** —— 本地 dayTexts/nightTexts
 * 数组随机取（如「记录下这份松弛感，开启美好的一天」「深夜里的这首歌，藏着你的什么心事？」），
 * 所以网络层拦不掉（与「金币飞入动画」那类不同）。
 *
 * 为什么不用 UiCleaner 的 ID 规则（标题 cu7 / 按钮 cu8 —— 已验证这 6 个 ID
 * 只被 kg_channel_post_guide_notice_layout 一个布局使用，零跨页复用风险）：
 * 宿主 `z(ViewGroup)` 的执行顺序是 inflate → findViewById → setText → addView。
 * `cu7` 在 inflate 的 setId 阶段就命中规则，但那一刻它**还没挂到父容器** ⇒
 * 只能藏自身；等到 addView 阶段能爬升藏父了，`hideByIdRule` 又因为
 * 「目标已 GONE」提前返回 ⇒ 结果是标题没了、底板和「去发布」还在，
 * 一条残缺的空横幅（比不藏更难看）。改引擎去兼容这种时序会波及所有
 * hideParentLevels>0 的既有规则，不值得。
 *
 * 因此改为**源头拦截**：hook 创建点，开关开启时直接不执行原方法 ⇒
 * 不 inflate、不 addView、不启动倒计时，零闪现、零开销。
 * 关掉开关后宿主逻辑完全不受影响，下次触发即恢复（无需还原路径）。
 *
 * jadx 实锤（酷狗概念版 v5.2.9 / classes11.dex）：
 * ```
 * public final void z(ViewGroup viewContainer) {
 *     itemView = inflate(ml.j.kg_channel_post_guide_notice_layout, null, false)
 *     ...
 *     viewContainer.addView(itemView, FrameLayout.LayoutParams(-1, 55dp))
 *     itemView.setVisibility(0)
 *     inOutAnim 250ms → translationY 45dp        // 滑出
 *     q3.e().q("KEY_CHANNEL_POST_GUIDE_NOTICE_LAST_SHOW_TIME", now)   // 记录"今天已显示"
 * }
 * ```
 * 类名未混淆（`com.kugou.android.app.home.channel.ChannelPostGuideNotice`），
 * 方法名被混淆成单字母 `z` ⇒ 按「名字 + 只有一个参数且是 ViewGroup」定位，
 * 不写死多余特征；宿主改版后找不到就只记一条日志，静默降级（不影响其它功能）。
 */
object ChannelPostGuideBlocker {

    private const val NOTICE_CLASS = "com.kugou.android.app.home.channel.ChannelPostGuideNotice"

    @Volatile
    private var installed = false

    fun install(xposed: XposedModule, classLoader: ClassLoader?) {
        if (installed) return
        installed = true
        try {
            val cls = Class.forName(NOTICE_CLASS, false, classLoader)
            val createPoint = cls.declaredMethods.firstOrNull {
                it.name == "z" &&
                    it.parameterTypes.size == 1 &&
                    ViewGroup::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            if (createPoint == null) {
                ModuleLog.log("频道发帖引导条: 未找到创建点 z(ViewGroup)（宿主版本可能已变）")
                return
            }
            xposed.hook(createPoint)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // 参数就是横幅要挂进去的容器（FrameLayout），context 一定可用
                    val ctx = (chain.getArg(0) as? View)?.context
                    if (ctx != null && Settings.isFeature(ctx, Settings.KEY_UI_CHANNEL_POST_GUIDE)) {
                        ModuleLog.log("频道发帖引导条: 已拦截（源头不创建）")
                        null // 不执行原方法：不 inflate / 不 addView / 不起倒计时
                    } else {
                        chain.proceed()
                    }
                }
            ModuleLog.log("频道发帖引导条: 创建点 z(ViewGroup) 挂载成功")
        } catch (t: Throwable) {
            ModuleLog.log("频道发帖引导条: hook 失败: $t")
        }
    }
}
