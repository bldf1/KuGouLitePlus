package com.bldf1.kugoulite.plus

import android.content.Context
import java.io.File

/**
 * 凭证与每日状态存储
 *
 * 数据存储在酷狗概念版的 SharedPreferences 中（Hook 运行在酷狗进程内，可直接访问）。
 * - 凭证缓存：避免每次启动都依赖 OkHttp 捕获
 * - 每日领取状态：防止一天内重复领取
 */
object PrefStore {

    private const val PREFS_NAME = "kugoulite_vip_module"

    private const val KEY_TOKEN = "token"
    private const val KEY_USERID = "userid"
    private const val KEY_DFID = "dfid"
    private const val KEY_MID = "mid"
    private const val KEY_APPID = "appid"
    private const val KEY_CLIENTVER = "clientver"
    private const val KEY_LAST_CLAIM_DATE = "last_claim_date"
    private const val KEY_LAST_CLAIM_SUCCESS = "last_claim_success_date"

    private fun getPrefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 保存凭证到缓存
     */
    fun saveCreds(ctx: Context, creds: KugouApi.Credentials?) {
        if (creds == null) return
        val editor = getPrefs(ctx).edit()
        editor.putString(KEY_TOKEN, creds.token)
        editor.putString(KEY_USERID, creds.userid)
        editor.putString(KEY_DFID, creds.dfid)
        editor.putString(KEY_MID, creds.mid)
        editor.putString(KEY_APPID, creds.appid)
        editor.putString(KEY_CLIENTVER, creds.clientver)
        editor.apply()
    }

    /**
     * 从缓存加载凭证
     */
    fun loadCreds(ctx: Context): KugouApi.Credentials? {
        val sp = getPrefs(ctx)
        val token = sp.getString(KEY_TOKEN, null)
        if (token.isNullOrEmpty()) return null

        return KugouApi.Credentials().apply {
            this.token = token
            userid = sp.getString(KEY_USERID, null)
            dfid = sp.getString(KEY_DFID, null)
            mid = sp.getString(KEY_MID, null)
            appid = sp.getString(KEY_APPID, null)
            clientver = sp.getString(KEY_CLIENTVER, null)
        }
    }

    /**
     * 检查今天是否已领取（按北京时间自然日）
     */
    fun isClaimedToday(ctx: Context): Boolean =
        KugouApi.localDayKey == getPrefs(ctx).getString(KEY_LAST_CLAIM_DATE, "")

    /**
     * 标记今天已尝试领取/未领取（按北京时间自然日）
     */
    fun setClaimedToday(ctx: Context, claimed: Boolean) {
        val editor = getPrefs(ctx).edit()
        if (claimed) {
            editor.putString(KEY_LAST_CLAIM_DATE, KugouApi.localDayKey)
        } else {
            editor.remove(KEY_LAST_CLAIM_DATE)
        }
        editor.apply()
    }

    /**
     * 检查今天是否已成功领取（按北京时间自然日）
     */
    fun isClaimedSuccessToday(ctx: Context): Boolean =
        KugouApi.localDayKey == getPrefs(ctx).getString(KEY_LAST_CLAIM_SUCCESS, "")

    /**
     * 标记今天已成功领取（按北京时间自然日）
     */
    fun setClaimedSuccessToday(ctx: Context, success: Boolean) {
        val editor = getPrefs(ctx).edit()
        if (success) {
            editor.putString(KEY_LAST_CLAIM_SUCCESS, KugouApi.localDayKey)
        } else {
            editor.remove(KEY_LAST_CLAIM_SUCCESS)
        }
        editor.apply()
    }

    /**
     * 从酷狗概念版自身的 SharedPreferences 中尝试读取凭证（备用方案）
     *
     * 当 OkHttp Hook 尚未捕获到凭证时，遍历酷狗的 SP 文件查找 token/userid。
     */
    fun tryReadFromAppPrefs(ctx: Context): KugouApi.Credentials? {
        return try {
            val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
            if (!prefsDir.exists()) return null

            val files = prefsDir.listFiles() ?: return null

            var token: String? = null
            var userid: String? = null
            var dfid: String? = null
            var mid: String? = null

            for (file in files) {
                if (!file.name.endsWith(".xml")) continue
                // 跳过模块自身的 SP
                if (file.name.startsWith(PREFS_NAME)) continue

                val prefsName = file.name.replace(".xml", "")
                val sp = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

                if (token == null) {
                    token = sp.getString("token", null)
                }
                if (userid == null) {
                    userid = sp.getString("userid", null)
                        ?: sp.getString("uid", null)
                        ?: sp.getString("kugouid", null)
                }
                if (dfid == null) {
                    dfid = sp.getString("dfid", null)
                }
                if (mid == null) {
                    mid = sp.getString("mid", null) ?: sp.getString("KUGOU_API_MID", null)
                }

                if (token != null && userid != null) break
            }

            if (!token.isNullOrEmpty() && !userid.isNullOrEmpty()) {
                ModuleLog.log("从酷狗SP读取到凭证: userid=$userid")
                KugouApi.Credentials().apply {
                    this.token = token
                    this.userid = userid
                    this.dfid = dfid
                    this.mid = mid
                }
            } else {
                null
            }
        } catch (e: Exception) {
            ModuleLog.log("读取酷狗SP失败: $e")
            null
        }
    }
}
