package com.bldf1.kugoulite.plus

import android.os.SystemClock

/**
 * 🔬 性能取证（R63）：把净化引擎热路径的耗时按类别累计，每 N 秒打一行日志。
 *
 * 目的：P0「发现页刷不到底」的机制已确认为**加法性**——引擎在宿主「分批插入」期间
 * 累积的主线程工作量打断了换批链。要知道该优化谁，就得先知道时间花在哪。
 *
 * 输出形如：
 *   PERF 5.0s | scan=12.3ms 补扫=3.1ms 白屏=0.0ms 门禁=1.2ms preSelf=8.9ms preTree=4.4ms vis=1.0ms
 *
 * ⚠️ 诊断代码：ENABLED=false 时全部计数短路（只多一次 boolean 判断），可长期留在代码里。
 */
object PerfProbe {

    /** 诊断开关：排查性能/卡死时置 true */
    const val ENABLED = false

    const val SCAN = 0          // 主扫描 scan(decor,…)
    const val FREE_LISTEN = 1   // findFreeListenTexts 全树补扫
    const val BLANK = 2         // autoRecoverBlankDiscover 白屏探测全树
    const val GATED = 3         // reevaluateGatedHides 门禁重算
    const val PRE_SELF = 4      // preHideSelf 合计
    const val PRE_TREE = 5      // preHideTree 合计
    const val SAFE_SCAN = 6     // 整个 safeScan
    const val VIS = 7           // setVisibility 钩子合计

    private const val N = 8
    private val acc = LongArray(N)
    private val cnt = IntArray(N)
    private var lastLog = 0L

    fun add(slot: Int, ns: Long) {
        if (!ENABLED || ns <= 0) return
        acc[slot] += ns
        cnt[slot]++
    }

    fun start(): Long = if (ENABLED) System.nanoTime() else 0L

    fun end(slot: Int, t0: Long) {
        if (!ENABLED || t0 == 0L) return
        add(slot, System.nanoTime() - t0)
    }

    /** 周期输出（挂在 4s 巡检上；不足 4s 不重复打） */
    fun tick() {
        if (!ENABLED) return
        val now = SystemClock.uptimeMillis()
        if (lastLog != 0L && now - lastLog < 4000L) return
        val span = if (lastLog == 0L) 0L else now - lastLog
        lastLog = now
        if (span == 0L) return
        val names = arrayOf("scan", "免费听补扫", "白屏探测", "门禁重算", "preSelf", "preTree", "safeScan", "setVis")
        val sb = StringBuilder("PERF ").append(span).append("ms |")
        for (i in 0 until N) {
            sb.append(' ').append(names[i]).append('=')
                .append(acc[i] / 1000).append("us/").append(cnt[i]).append("次")
        }
        for (i in 0 until N) {
            acc[i] = 0
            cnt[i] = 0
        }
        ModuleLog.log(sb.toString())
    }

    /** 归零（关开关/换页时） */
    fun reset() {
        for (i in 0 until N) {
            acc[i] = 0
            cnt[i] = 0
        }
        lastLog = 0L
    }
}
