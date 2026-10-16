package com.bldf1.kugoulite.plus.lyric

import android.util.Base64
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * KRC 明文歌词解析器。
 *
 * 格式：
 * ```
 * [ti:歌名]
 * [language:eyJjb250ZW50Ijpb...]        ← Base64 的 JSON，装罗马音/翻译段落
 * [1234,567]<0,300,0>逐<300,300,0>字<600,300,0>歌词
 * ```
 * - 行首 `[起始ms,持续ms]` 之后是带 `<偏移ms,持续ms,参数>` 标签的逐字内容；
 * - 文本一律用「游标截取」而不是按标签分组，这样歌词正文里出现 `<` `>` 也不会解析错。
 *
 * 移植并改写自 Lyricon 的 krckit（Copyright 2026 Proify, Tomakino，Apache-2.0）：
 * 去掉 kotlinx.serialization 依赖，改用 Android 自带的 org.json，且直接产出 RichLyricLine。
 */
internal object KrcParser {

    private val LINE_PATTERN = Pattern.compile("""^\[(\d+)\s*,\s*(\d+)](.*)$""")
    private val META_PATTERN = Pattern.compile("""^\[([a-zA-Z0-9_]+)\s*:(.*)$""")
    private val WORD_TAG_PATTERN = Pattern.compile("""<(\d+)\s*,\s*(\d+)\s*,\s*(\d+)>""")
    private val STRAY_TAG_PATTERN = Regex("""<\d+\s*,\s*\d+\s*,\s*\d+>""")

    private const val SECTION_ROMA = 0
    private const val SECTION_TRANSLATE = 1

    /** 解析结果：逐行歌词（已带逐字时间轴、罗马音、翻译） */
    fun parse(content: String?): List<RichLyricLine> {
        if (content.isNullOrBlank()) return emptyList()

        val metadata = HashMap<String, String>()
        val lines = ArrayList<RichLyricLine>(128)

        // 跨行 metadata 收集状态（部分 KRC 的 Base64 值里含换行）
        var metaKey: String? = null
        val metaValue = StringBuilder()

        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val lm = LINE_PATTERN.matcher(line)
            if (lm.matches()) {
                metaKey = null
                val start = lm.group(1)?.toLongOrNull() ?: 0L
                val dur = lm.group(2)?.toLongOrNull() ?: 0L
                val body = lm.group(3)?.trim().orEmpty()
                if (body.isNotEmpty()) {
                    try {
                        lines.add(parseLineBody(start, dur, body))
                    } catch (_: Throwable) {
                        // 单行异常隔离开，不影响整首
                    }
                }
                continue
            }

            val mm = META_PATTERN.matcher(line)
            if (mm.matches()) {
                val key = mm.group(1) ?: continue
                if (key.all { it.isDigit() }) continue
                var value = mm.group(2)?.trim().orEmpty()
                if (value.endsWith("]")) {
                    metadata[key] = value.dropLast(1).trim()
                    metaKey = null
                } else {
                    metaKey = key
                    metaValue.setLength(0)
                    metaValue.append(value)
                }
                continue
            }

            if (metaKey != null) {
                if (line.endsWith("]")) {
                    metaValue.append(line.dropLast(1).trim())
                    metadata[metaKey] = metaValue.toString()
                    metaKey = null
                } else {
                    metaValue.append(line)
                }
            }
        }

        lines.sortBy { it.begin }
        attachSecondary(lines, metadata)
        return lines
    }

    /** 解析单行正文：产出 text + words（逐字时间轴） */
    private fun parseLineBody(lineStart: Long, lineDur: Long, body: String): RichLyricLine {
        val words = ArrayList<LyricWord>(24)
        val text = StringBuilder(body.length)

        val wm = WORD_TAG_PATTERN.matcher(body)
        var prevTagEnd = -1
        var prevOffset = 0L
        var prevDuration = 0L

        while (wm.find()) {
            if (prevTagEnd != -1) {
                val chunk = body.substring(prevTagEnd, wm.start())
                val begin = lineStart + prevOffset
                words.add(LyricWord(begin = begin, end = begin + prevDuration, duration = prevDuration, text = chunk))
                text.append(chunk)
            }
            prevOffset = wm.group(1)?.toLongOrNull() ?: 0L
            prevDuration = wm.group(2)?.toLongOrNull() ?: 0L
            prevTagEnd = wm.end()
        }

        if (prevTagEnd != -1 && prevTagEnd <= body.length) {
            val chunk = body.substring(prevTagEnd)
            val begin = lineStart + prevOffset
            words.add(LyricWord(begin = begin, end = begin + prevDuration, duration = prevDuration, text = chunk))
            text.append(chunk)
        }

        // 没有任何字标签（普通 LRC 伪装行）：擦掉可能残留的坏死标签，保留纯文本
        val finalText =
            if (words.isEmpty()) body.replace(STRAY_TAG_PATTERN, "") else text.toString()

        return RichLyricLine(
            begin = lineStart,
            end = lineStart + lineDur,
            duration = lineDur,
            text = finalText,
            words = words.ifEmpty { null },
        )
    }

    /**
     * 从 `language` 元数据里取出罗马音 / 翻译，按行号对位挂到歌词行上。
     * 行数不一致就整体放弃（宁可没有，也不要错位）。
     */
    private fun attachSecondary(lines: List<RichLyricLine>, metadata: Map<String, String>) {
        if (lines.isEmpty()) return
        val key = metadata.keys.firstOrNull { it.equals("language", ignoreCase = true) } ?: return
        val rawValue = metadata[key]?.takeIf { it.isNotBlank() } ?: return
        val json = runCatching {
            String(Base64.decode(rawValue, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: rawValue

        val roma: List<String>?
        val translation: List<String>?
        try {
            val content = JSONObject(json).optJSONArray("content") ?: return
            var r: List<String>? = null
            var t: List<String>? = null
            for (i in 0 until content.length()) {
                val section = content.optJSONObject(i) ?: continue
                val arr = section.optJSONArray("lyricContent") ?: continue
                val flat = ArrayList<String>(arr.length())
                for (j in 0 until arr.length()) {
                    val row = arr.optJSONArray(j) ?: continue
                    val sb = StringBuilder()
                    for (k in 0 until row.length()) sb.append(row.optString(k))
                    flat.add(sb.toString())
                }
                when (section.optInt("type", -1)) {
                    SECTION_ROMA -> r = flat
                    SECTION_TRANSLATE -> t = flat
                }
            }
            roma = r
            translation = t
        } catch (_: Throwable) {
            return
        }

        val useRoma = roma?.takeIf { it.size == lines.size }
        val useTrans = translation?.takeIf { it.size == lines.size }
        if (useRoma == null && useTrans == null) return

        for (i in lines.indices) {
            // ⚠️ 空串转 null（Round 51）：酷狗 KRC 的翻译段常见空串行（如外语歌
            // 开头几行没给翻译），空串原样投给词幕会渲染一个空翻译占位；
            // trim 掉首尾空白后为空则置 null，让词幕直接跳过该行翻译
            useRoma?.let { lines[i].roma = it[i].trim().takeIf { s -> s.isNotEmpty() } }
            useTrans?.let { lines[i].translation = it[i].trim().takeIf { s -> s.isNotEmpty() } }
        }
    }

    /** 供调试：抓一行歌词文本用于日志（避免刷屏） */
    fun preview(lines: List<RichLyricLine>): String =
        lines.firstOrNull { !it.text.isNullOrBlank() }?.text ?: ""
}
