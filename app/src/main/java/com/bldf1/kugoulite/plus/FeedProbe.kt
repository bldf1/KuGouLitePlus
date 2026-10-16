package com.bldf1.kugoulite.plus

import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 🔬 临时诊断（R63）：确认「发现页刷不到底」到底是
 *   ①宿主**不再插入**新 item（分页被我们打断）还是
 *   ②宿主**照常插入**、只是新 item 被我们隐藏成 0 高（视觉/滚动距离没增长）。
 *
 * 做法：hook `androidx.recyclerview.widget.RecyclerView$Adapter` 的
 *  `notifyItemRangeInserted` / `notifyItemRangeRemoved` / `notifyDataSetChanged`，
 *  每次打印 **插入位置/个数 + 该 Adapter 的 getItemCount()**。
 *
 * 判读：
 *  - 若滑到卡住后**仍有新的「插入 total=N」日志**（N 递增）⇒ 是②，锅在我们隐藏太多；
 *  - 若卡住后**再无插入日志** ⇒ 是①，宿主分页真的停了，要往分页触发点查。
 *
 * ⚠️ 这是诊断代码：定位完成后请把 [ENABLED] 置 false 或整体删除（会打日志）。
 */
object FeedProbe {

    private const val TAG = "FEEDPROBE"

    /** 诊断开关；日常置 false。排查「刷不到底」时置 true 并冷启。 */
    private const val ENABLED = false

    /** 是否打印调用方堆栈（用来找出「谁在驱动宿主分批加载」，只打前 N 次） */
    private const val LOG_STACK = true

    private const val STACK_BUDGET = 14

    @Volatile
    private var stackBudget = STACK_BUDGET

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
            sb.append("\n      ← ").append(c.substringAfterLast('.')).append('#').append(e.methodName)
                .append(':').append(e.lineNumber)
            n++
            if (n >= 24) break
        }
        return sb.toString()
    }

    @Volatile
    private var installed = false

    @Volatile
    private var lastCountLog = 0L

    fun install(xposed: XposedModule, classLoader: ClassLoader) {
        if (!ENABLED || installed) return
        try {
            val adapterCls = Class.forName(
                "androidx.recyclerview.widget.RecyclerView\$Adapter", false, classLoader
            )
            val countMethod = try {
                adapterCls.getMethod("getItemCount")
            } catch (_: Throwable) {
                null
            }

            fun totalOf(self: Any?): String = try {
                (countMethod?.invoke(self) as? Int)?.toString() ?: "?"
            } catch (_: Throwable) {
                "?"
            }

            fun adapterName(self: Any?): String =
                self?.javaClass?.name?.substringAfterLast('.') ?: "?"

            // 1) 插入
            try {
                val m = adapterCls.getDeclaredMethod(
                    "notifyItemRangeInserted",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val self = chain.thisObject
                        val pos = chain.getArg(0) as? Int ?: -1
                        val cnt = chain.getArg(1) as? Int ?: -1
                        val an = adapterName(self)
                        var extra = ""
                        if (LOG_STACK && stackBudget > 0 && an.startsWith("DiscoveryRec")) {
                            stackBudget--
                            extra = stackOf()
                        }
                        ModuleLog.log(
                            "$TAG 插入 pos=$pos cnt=$cnt total=${totalOf(self)} @$an$extra"
                        )
                        chain.proceed()
                    }
                ModuleLog.log("$TAG notifyItemRangeInserted 安装成功")
            } catch (t: Throwable) {
                ModuleLog.log("$TAG notifyItemRangeInserted 失败: $t")
            }

            // 2) 移除
            try {
                val m = adapterCls.getDeclaredMethod(
                    "notifyItemRangeRemoved",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val self = chain.thisObject
                        val pos = chain.getArg(0) as? Int ?: -1
                        val cnt = chain.getArg(1) as? Int ?: -1
                        ModuleLog.log(
                            "$TAG 移除 pos=$pos cnt=$cnt total=${totalOf(self)} @${adapterName(self)}"
                        )
                        chain.proceed()
                    }
                ModuleLog.log("$TAG notifyItemRangeRemoved 安装成功")
            } catch (t: Throwable) {
                ModuleLog.log("$TAG notifyItemRangeRemoved 失败: $t")
            }

            // 3) 全量刷新（宿主「换一批」常用）
            try {
                val m = adapterCls.getDeclaredMethod("notifyDataSetChanged")
                xposed.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val self = chain.thisObject
                        val now = SystemClock.uptimeMillis()
                        val an2 = adapterName(self)
                        if (now - lastCountLog > 1000L) {
                            lastCountLog = now
                            var extra2 = ""
                            if (LOG_STACK && stackBudget > 0 && an2.startsWith("DiscoveryRec")) {
                                stackBudget--
                                extra2 = stackOf()
                            }
                            ModuleLog.log(
                                "$TAG 全量刷新 total=${totalOf(self)} @$an2$extra2"
                            )
                        }
                        chain.proceed()
                    }
                ModuleLog.log("$TAG notifyDataSetChanged 安装成功")
            } catch (t: Throwable) {
                ModuleLog.log("$TAG notifyDataSetChanged 失败: $t")
            }

            installed = true
        } catch (t: Throwable) {
            ModuleLog.log("$TAG 安装失败: $t")
        }
    }
}
