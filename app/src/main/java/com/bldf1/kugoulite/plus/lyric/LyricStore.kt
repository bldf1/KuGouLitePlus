package com.bldf1.kugoulite.plus.lyric

import android.content.Context
import java.io.File

/**
 * 本地 KRC 歌词定位。
 *
 * 酷狗概念版把播放过的歌词缓存成外部私有目录下的
 * `<externalFilesDir>/kugou/lyrics/歌手 - 歌名-<hash>.krc`
 * （个别版本会平铺在 `kugou/` 根下、且带 `lyrics` 前缀）。
 * 因为我们在酷狗自己的进程里，`getExternalFilesDir` 拿到的就是这个目录，
 * 不需要任何存储权限。
 */
internal object LyricStore {

    private const val DIR = "kugou"
    private const val SUB_DIR = "lyrics"
    private const val ROOT_PREFIX = "lyrics"

    fun find(ctx: Context, artist: String?, title: String?): File? {
        val a = artist?.trim()
        val t = title?.trim()
        if (a.isNullOrEmpty() || t.isNullOrEmpty()) return null
        val base = baseDir(ctx) ?: return null

        val target = sanitize("$a - $t")

        exact(base, target)?.let { return it }
        hasty(base, target)?.let { return it }
        return fuzzy(base, target)
    }

    private fun baseDir(ctx: Context): File? = try {
        ctx.getExternalFilesDir(null)?.let { File(it, DIR) }?.takeIf { it.isDirectory }
    } catch (_: Throwable) {
        null
    }

    /** 与酷狗落盘时的非法字符替换保持一致 */
    private fun sanitize(name: String): String = name
        .replace(":", "：")
        .replace("/", "／")
        .replace("\\", "＼")
        .replace("*", "×")
        .replace("?", "？")
        .replace("\"", "＂")
        .replace("|", "｜")

    private fun exact(base: File, target: String): File? {
        File(base, SUB_DIR).takeIf { it.isDirectory }?.list()?.forEach { name ->
            if (name.startsWith(target)) return File(File(base, SUB_DIR), name)
        }
        base.list()?.forEach { name ->
            if (name.startsWith(ROOT_PREFIX + target)) return File(base, name)
        }
        return null
    }

    /**
     * 酷狗有时落盘成 `歌手 - 歌名` 再截断（长标题），
     * 这里用「取文件名里 '-' 之前的部分」反向匹配一次，成本极低。
     */
    private fun hasty(base: File, target: String): File? {
        val short = target.substringBefore(" - ").trim()
        if (short.isEmpty()) return null
        File(base, SUB_DIR).takeIf { it.isDirectory }?.list()?.forEach { name ->
            if (name.startsWith(short) && name.contains(target.substringAfter(" - ", "").take(4))) {
                return File(File(base, SUB_DIR), name)
            }
        }
        return null
    }

    /** 编辑距离兜底（阈值与 Lyricon 官方提供器一致：相似度 >0.94 或编辑距离 ≤1.1） */
    private fun fuzzy(base: File, target: String): File? {
        val candidates = ArrayList<Pair<String, File>>(64)
        File(base, SUB_DIR).takeIf { it.isDirectory }?.list()?.forEach { name ->
            candidates.add(name.substringBeforeLast("-").trim() to File(File(base, SUB_DIR), name))
        }
        base.list()?.forEach { name ->
            if (name.startsWith(ROOT_PREFIX)) {
                val stem = name.removePrefix(ROOT_PREFIX).substringBeforeLast("-").trim()
                candidates.add(stem to File(base, name))
            }
        }
        for ((stem, file) in candidates) {
            if (!file.name.endsWith(".krc")) continue
            val sim = similarity(target, stem)
            val dist = (1.0 - sim) * maxOf(target.length, stem.length)
            if (sim > 0.94 || dist <= 1.1) return file
        }
        return null
    }

    /** 归一化 Levenshtein 相似度（滚动一维 DP，O(n·m) 时间 / O(m) 空间） */
    private fun similarity(s1: String, s2: String): Double {
        val a = s1.lowercase()
        val b = s2.lowercase()
        if (a == b) return 1.0
        val n = a.length
        val m = b.length
        if (n == 0 || m == 0) return 0.0
        val dp = IntArray(m + 1) { it }
        for (i in 1..n) {
            var prev = i
            var last = i - 1
            for (j in 1..m) {
                val next =
                    if (a[i - 1] == b[j - 1]) last
                    else minOf(last, prev, dp[j]) + 1
                last = dp[j]
                dp[j] = next
                prev = next
            }
        }
        return 1.0 - dp[m].toDouble() / maxOf(n, m)
    }
}
