package com.bldf1.kugoulite.plus.lyric

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

/**
 * KRC 歌词解密（酷狗私有格式）。
 *
 * 文件结构：前 4 字节文件头 + 从第 5 字节起与固定 16 字节密钥循环 XOR，
 * XOR 结果是一段 zlib 流，解压后即 UTF-8 明文歌词。
 *
 * 移植自 Lyricon 的 krckit（Copyright 2026 Proify, Tomakino，Apache-2.0）。
 * 只用到 JDK 自带的 java.util.zip，无任何第三方依赖。
 */
internal object KrcDecryptor {

    /** XOR 密钥（与酷狗 AS3 端的 newkeyBytes 一致） */
    private val DECRYPT_KEY = byteArrayOf(
        64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
        206.toByte(), 210.toByte(), 110, 105
    )

    /** 跳过文件头长度 */
    private const val OFFSET = 4

    /**
     * 解密并解压 KRC 字节流。
     *
     * @return 明文歌词；数据非法或解压失败返回 null（不抛异常）
     */
    fun decrypt(input: ByteArray): String? {
        if (input.size <= OFFSET) return null
        return try {
            val n = input.size - OFFSET
            val out = ByteArray(n)
            for (i in 0 until n) {
                val key = DECRYPT_KEY[i % DECRYPT_KEY.size].toInt()
                out[i] = (input[i + OFFSET].toInt() xor key).toByte()
            }
            inflate(out)
        } catch (_: Throwable) {
            null
        }
    }

    private fun inflate(data: ByteArray): String =
        ByteArrayInputStream(data).use { bais ->
            InflaterInputStream(bais).use { inflater ->
                ByteArrayOutputStream(data.size * 2).use { baos ->
                    val buf = ByteArray(2048)
                    while (true) {
                        val len = inflater.read(buf)
                        if (len == -1) break
                        baos.write(buf, 0, len)
                    }
                    baos.toString(StandardCharsets.UTF_8.name())
                }
            }
        }
}
