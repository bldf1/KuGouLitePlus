package com.bldf1.kugoulite.plus.lyric

import android.app.Application
import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import com.bldf1.kugoulite.plus.ModuleLog
import com.bldf1.kugoulite.plus.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.io.File
import java.security.MessageDigest

/**
 * 词幕（Lyricon）歌词提供器。
 *
 * 链路：
 * ```
 *  酷狗播放服务(.support 进程)         我们                SystemUI 中心服务        词幕
 *  MediaSession.setMetadata  ──hook──▶ 找本地 .krc ──▶ IProviderService ──▶ 状态栏歌词
 *  MediaSession.setPlaybackState ────▶ setPlaybackState(PlaybackState)
 * ```
 *
 * ⚠️ **只在 `.support` 进程工作**：实测 `dumpsys media_session` 里
 * `KugouPlaybackService` 的 ownerPid 就是 `com.kugou.android.lite.support`，
 * 主进程里根本没有 MediaSession 实例，装上去也不会被调用。
 */
internal object LyriconBridge {

    /** 提供给词幕识别的「提供者」身份＝本模块包名 */
    private const val PROVIDER_PACKAGE = "com.bldf1.kugoulite.plus"

    /** 歌词还没落盘时的重试次数与间隔（酷狗点播时才下载歌词） */
    private const val MAX_ATTEMPTS = 6
    private const val RETRY_MS = 600L

    /** 开关轮询节流：面板在主进程写、这里在 .support 读，靠这个把改动带过来 */
    private const val RECONCILE_THROTTLE_MS = 3_000L

    @Volatile
    private var installed = false

    private var appCtx: Context? = null
    private var provider: LyriconProvider? = null
    private var worker: Handler? = null

    @Volatile
    private var lastSignature: String? = null

    @Volatile
    private var lastReconcileAt = 0L

    /** 记录「已经试过但没找到」的签名，避免同一首歌反复扫目录 */
    private val missed = java.util.Collections.synchronizedSet(HashSet<String>())

    // ==================== 安装 ====================

    /**
     * 安装 MediaSession 钩子。非 `.support` 进程直接跳过（本 App 只有那边有播放会话）。
     *
     * @param processName 当前进程名（来自 ModuleLoadedParam）
     */
    fun install(module: XposedModule, classLoader: ClassLoader, processName: String) {
        if (installed) return
        if (!isSupportProcess(processName)) {
            ModuleLog.log("词幕歌词: 进程 $processName 无 MediaSession，跳过")
            return
        }
        installed = true

        try {
            val cls = Class.forName("android.media.session.MediaSession", false, classLoader)

            module.hook(cls.getDeclaredMethod("setPlaybackState", PlaybackState::class.java))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        onPlaybackState(chain.getArg(0) as? PlaybackState)
                    } catch (_: Throwable) {
                    }
                    result
                }

            module.hook(cls.getDeclaredMethod("setMetadata", MediaMetadata::class.java))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        onMetadata(chain.getArg(0) as? MediaMetadata)
                    } catch (_: Throwable) {
                    }
                    result
                }

            ModuleLog.log("词幕歌词: MediaSession 钩子已安装 ($processName)")
        } catch (t: Throwable) {
            ModuleLog.log("词幕歌词: 钩子安装失败 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun isSupportProcess(name: String): Boolean =
        name.endsWith(":support") || name.endsWith(".support")

    // ==================== 生命周期 ====================

    /** 在 Application.onCreate 里调用：拿到 Context，创建并提供歌词 */
    fun onAppCreate(context: Context) {
        if (!installed) return
        appCtx = context
        val ok = reconcile(force = true)
        ModuleLog.log("词幕歌词: 初始化完成，注册=${if (ok) "ok" else "跳过"}")
    }

    /**
     * 对开关做一次「对账」：开＝注册，关＝注销。
     * 节流调用（播放状态变化频率低，足够把面板里的改动带进来）。
     *
     * @return 当前是否处于已注册状态
     */
    private fun reconcile(force: Boolean = false): Boolean {
        val ctx = appCtx ?: return false
        val now = System.currentTimeMillis()
        if (!force && now - lastReconcileAt < RECONCILE_THROTTLE_MS) {
            return provider != null
        }
        lastReconcileAt = now

        // ⚠️ 必须用 readLyricProvider（直读 XML）：开关由主进程（面板）写入，
        //    本进程的 SharedPreferences 与 featureCache 都不会失效，
        //    走它们会出现「改了开关要重启 App 才生效」。
        if (Settings.readLyricProvider(ctx)) return ensureRegistered()
        unregister()
        return false
    }

    private fun ensureRegistered(): Boolean {
        provider?.let { return true }
        val ctx = appCtx ?: return false
        return try {
            val p = LyriconFactory.createProvider(
                context = ctx,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = ctx.packageName,
                logo = null,
                metadata = null,
                processName = Application.getProcessName(),
            )
            // 断线重连后 CachedRemotePlayer 会自动把这些状态补发
            p.player.setDisplayTranslation(true)
            p.player.setDisplayRoma(false)
            p.autoSync = true
            val ok = p.register()
            provider = p
            ModuleLog.log(
                "词幕歌词: 提供器注册${if (ok) "已发起" else "未被接受"}" +
                    " (player=${ctx.packageName})"
            )
            ok
        } catch (t: Throwable) {
            ModuleLog.log("词幕歌词: 创建提供器失败 ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun unregister() {
        val p = provider ?: return
        provider = null
        try {
            p.unregister()
            p.destroy()
            ModuleLog.log("词幕歌词: 提供器已注销（开关关闭）")
        } catch (_: Throwable) {
        }
    }

    // ==================== 回调 ====================

    private fun onPlaybackState(state: PlaybackState?) {
        if (state == null) return
        if (!reconcile()) return
        // PlaybackState 自带播放位置/倍速，词幕据此自行推进进度，比布尔值精确
        provider?.player?.setPlaybackState(state)
    }

    private fun onMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        if (title.isNullOrBlank()) return

        val signature = signatureOf(id, title, artist, album)
        if (signature == lastSignature) return
        lastSignature = signature
        missed.remove(signature)

        if (!reconcile()) return
        val player = provider?.player ?: return

        var duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration < 0) duration = 0

        // 先发一份「没有歌词」的歌曲信息：立刻清掉上一首的残留歌词，标题也换成新歌
        runCatching { player.setSong(Song(id, title, artist, duration, null, null)) }

        val ctx = appCtx ?: return
        handler().post { resolveAndPublish(ctx, signature, id, title, artist, duration) }
    }

    /** 后台线程：定位 → 解密 → 解析 → 投递 */
    private fun resolveAndPublish(
        ctx: Context,
        signature: String,
        id: String?,
        title: String,
        artist: String?,
        duration: Long,
    ) {
        var file: File? = null
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            file = runCatching { LyricStore.find(ctx, artist, title) }.getOrNull()
            if (file != null) break
            try {
                Thread.sleep(RETRY_MS)
            } catch (_: InterruptedException) {
                return
            }
            // 期间用户可能已经切歌 / 关掉了开关
            if (signature != lastSignature || !reconcile()) return
        }

        if (file == null) {
            if (missed.add(signature)) {
                ModuleLog.log("词幕歌词: 未找到本地歌词「$artist - $title」（重试 $attempt 次）")
            }
            return
        }

        val lines = try {
            val bytes = file.readBytes()
            val plain = KrcDecryptor.decrypt(bytes)
            KrcParser.parse(plain)
        } catch (t: Throwable) {
            ModuleLog.log("词幕歌词: 解析失败 ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }

        if (lines.isEmpty()) {
            if (missed.add(signature)) {
                ModuleLog.log("词幕歌词: ${file.name} 解密/解析为空")
            }
            return
        }

        if (signature != lastSignature) return
        val player = provider?.player ?: return
        val realDuration = if (duration > 0) duration else (lines.lastOrNull()?.end ?: 0L)
        val ok = runCatching {
            player.setSong(
                Song(
                    id = id,
                    name = title,
                    artist = artist,
                    duration = realDuration,
                    metadata = null,
                    lyrics = lines,
                )
            )
        }.getOrDefault(false)

        val worded = lines.count { !it.words.isNullOrEmpty() }
        val trans = lines.count { !it.translation.isNullOrBlank() }
        ModuleLog.log(
            "词幕歌词: 已投递 ${file.name}｜${lines.size} 行（逐字 $worded、翻译 $trans）｜发送=$ok"
        )
    }

    private fun handler(): Handler {
        worker?.let { return it }
        synchronized(this) {
            worker?.let { return it }
            val t = HandlerThread("KuGouLyricProvider")
            t.start()
            val h = Handler(t.looper)
            worker = h
            return h
        }
    }

    private fun signatureOf(vararg parts: String?): String {
        val sb = StringBuilder()
        for (p in parts) sb.append(p ?: "").append('\u0000')
        return md5(sb.toString())
    }

    private fun md5(s: String): String = try {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        d.joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        s.hashCode().toString()
    }
}
