package com.bldf1.kugoulite.plus

import android.content.Context

/**
 * 自动听歌任务
 *
 * 调用 /youth/v2/report/listen_song 上报听歌打卡（听歌领VIP的任务前提），
 * 随后由 ClaimWorker 执行领取。参考 KuGouMusicApi 的 youth_listen_song 实现。
 */
object ListenTask {

    // 上报曲目（KuGouMusicApi 默认 mixsongid）
    private val REPORT_SONGS = longArrayOf(666075191L)

    /**
     * 执行听歌上报（在领取线程内调用，随领取一起执行）
     */
    fun report(ctx: Context, creds: KugouApi.Credentials) {
        try {
            for (songId in REPORT_SONGS) {
                val result = KugouApi.reportListenSong(creds, songId)
                ModuleLog.log("听歌上报: code=${result.code} msg=${result.message}")
                if (result.code != KugouApi.CODE_SUCCESS) {
                    // 上报失败不阻断领取（领取本身不依赖打卡成功）
                    break
                }
            }
        } catch (e: Exception) {
            ModuleLog.log("听歌上报异常: $e")
        }
    }
}
