package com.bldf1.kugoulite.plus

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志工具，写入酷狗私有目录，并转发到 Xposed 框架日志。
 *
 * 性能说明（真机实测）：净化引擎隐藏控件时高频写日志，原实现**每行**都做
 * `mkdirs + length() + FileOutputStream(open) + write + close`（约 5 次系统调用）
 * 且全程持全局锁——滚动/切页时隐藏动作密集，主线程被 I/O 拖住 = 掉帧。
 * 现改为**常驻 BufferedWriter**（只 open 一次），每行仅一次 write + flush；
 * 保留按大小滚动与多线程串行化。
 */
object ModuleLog {
    private const val TAG = "[KuGouLiteVip]"
    private const val FALLBACK_LOG_FILE =
        "/data/data/com.kugou.android.lite/files/kugou_vip_module.log"
    private const val MAX_BYTES = 1024L * 1024L

    @Volatile
    private var module: XposedModule? = null

    @Volatile
    private var logFile: String? = null

    @JvmStatic
    fun setModule(m: XposedModule) {
        module = m
    }

    fun init(ctx: Context) {
        try {
            logFile = ctx.filesDir.absolutePath + "/kugou_vip_module.log"
            val f = File(logFile)
            f.parentFile?.mkdirs()
            if (f.exists() && f.length() > MAX_BYTES) f.delete()
        } catch (_: Exception) {
            logFile = FALLBACK_LOG_FILE
        }
    }

    private val timeFmt = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    // 多线程（OkHttp 线程池 / 主线程）同时 append 无锁会交错丢行，统一串行化。
    // 锁内只做 write/flush 与计数，不再做 open/close（那才是系统调用大头）。
    private val fileLock = Any()
    private var writer: BufferedWriter? = null
    private var written: Long = 0L

    /** 打开（或滚动重建）常驻写入流；调用方必须持有 fileLock */
    private fun reopenLocked(): BufferedWriter? {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        return try {
            val file = File(logFile ?: FALLBACK_LOG_FILE)
            file.parentFile?.mkdirs()
            if (file.length() > MAX_BYTES) file.delete()
            written = if (file.exists()) file.length() else 0L
            BufferedWriter(
                OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 8192
            ).also { writer = it }
        } catch (_: Exception) {
            null
        }
    }

    fun log(msg: String) {
        val line = "[" + timeFmt.get()!!.format(Date()) + "] " + msg
        val m = module
        if (m != null) {
            try {
                m.log(Log.INFO, TAG, line)
            } catch (_: Throwable) {
            }
        } else {
            Log.i(TAG, line)
        }
        try {
            synchronized(fileLock) {
                var w = writer
                if (w == null || written > MAX_BYTES) w = reopenLocked()
                val out = w ?: return
                out.write(line)
                out.write("\n")
                out.flush()
                written += line.length + 1
            }
        } catch (_: Exception) {
            // 写日志失败绝不影响宿主
        }
    }
}
