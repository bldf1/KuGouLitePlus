package com.bldf1.kugoulite.plus

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 🔬 诊断（R64）：给宿主**卡片协议管线**装计数器，回答「停在哪一环」。
 *
 * 背景：FeedProbe 抓到的插入调用链是
 *   `ScheduledAction.run ← … ← DiscoveryCardPresenter.c ← x0 ← s0.invoke ← N ← w0 ← DiscoveryFragment2.I6 → notifyItemRangeInserted`
 * 即**每条卡片的响应到达时插一批**（rx 异步回调）。所以插入停止有两种完全不同的解释：
 *  ① 上游不再有卡片响应（协议/网络层停）→ 计数会归零；
 *  ② 响应还在来，但 presenter 没插（被我们的状态改动作废）→ 协议计数照涨、插入不涨。
 *
 * 做法：hook `DiscoveryCardProtocol` / `ChannelFeaturedListProtocol` 的**混淆短名方法**，
 * 只计数（不打印每次调用），每 4s 汇总一行；异常单独打（附方法名）。
 *
 * ⚠️ 诊断代码，默认关；定位完成置 false 或整体删除。
 */
object CardProbe {

    private const val ENABLED = false

    private val TARGETS = arrayOf(
        "com.kugou.android.app.home.discovery.protocol.DiscoveryCardProtocol",
        "com.kugou.android.app.home.channel.protocol.ChannelFeaturedListProtocol",
        "com.kugou.android.app.home.discovery.protocol.DiscoveryCardProtocol\$a",
        // R65：插入/协议的**全部驱动方**都在 presenter 里（堆栈实锤），
        // 把它也纳入计数就能回答"是驱动停了，还是驱动还在但不出活"
        "com.kugou.android.app.home.discovery.DiscoveryCardPresenter",
        "com.kugou.android.app.home.discovery.presenter.DiscoveryCardPresenter",
        "com.kugou.android.app.home.discovery.v5.presenter.DiscoveryCardPresenter"
    )

    private val counts = HashMap<String, Int>()
    private var lastLog = 0L
    private var installed = false

    /** 每个「类#方法」第一次被调用时打一次调用方堆栈（用来找驱动者） */
    private const val LOG_FIRST_STACK = true
    private val stackDone = HashSet<String>()

    private fun stackOf(): String {
        val st = Throwable().stackTrace
        val sb = StringBuilder()
        var n = 0
        for (e in st) {
            val c = e.className
            if (c.startsWith("java.") || c.startsWith("android.") || c.startsWith("dalvik.")
                || c.startsWith("io.github.libxposed") || c.startsWith("com.bldf1.kugoulite")
                || c.startsWith("kotlin.") || c.startsWith("org.lsposed")
            ) continue
            sb.append("\n        ← ").append(c.substringAfterLast('.')).append('#')
                .append(e.methodName).append(':').append(e.lineNumber)
            n++
            if (n >= 22) break
        }
        return sb.toString()
    }

    @Volatile
    private var hooked = 0

    fun install(xposed: XposedModule, classLoader: ClassLoader) {
        if (!ENABLED || installed) return
        installed = true
        for (name in TARGETS) {
            try {
                val c = Class.forName(name, false, classLoader)
                for (m: Method in c.declaredMethods) {
                    // 只跟混淆短名（KuGou 混淆后多为单/双字母），跳过 getter 等噪声
                    if (m.name.length > 2) continue
                    try {
                        xposed.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val key = c.simpleName + '#' + m.name
                                counts[key] = (counts[key] ?: 0) + 1
                                val first = LOG_FIRST_STACK && stackDone.add(key)
                                if (first) {
                                    ModuleLog.log("CARDPROBE 首次 $key 调用方:${stackOf()}")
                                }
                                try {
                                    chain.proceed()
                                } catch (t: Throwable) {
                                    ModuleLog.log("CARDPROBE $key 抛异常: $t")
                                    throw t
                                }
                            }
                        hooked++
                    } catch (_: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                ModuleLog.log("CARDPROBE $name 未找到: $t")
            }
        }
        ModuleLog.log("CARDPROBE 安装 $hooked 个方法")
    }

    /** 每 4s 汇总一次（挂在 safeScan 上调用） */
    fun tick(force: Boolean = false) {
        if (!ENABLED) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && lastLog != 0L && now - lastLog < 4000L) return
        val span = if (lastLog == 0L) 0L else now - lastLog
        lastLog = now
        if (span == 0L || counts.isEmpty()) return
        val sb = StringBuilder("CARDPROBE ").append(span).append("ms |")
        for ((k, v) in counts) sb.append(' ').append(k).append('=').append(v)
        counts.clear()
        ModuleLog.log(sb.toString())
    }
}
