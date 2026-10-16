package com.bldf1.kugoulite.plus

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap

/**
 * 酷狗概念版 API 封装
 *
 * 核心接口：
 * 1. 领取每日听歌VIP  POST /youth/v1/recharge/receive_vip_listen_song
 * 2. 升级概念版VIP    POST /youth/v1/listen_song/upgrade_vip_reward
 *
 * 签名算法：MD5(salt + sorted_kv_string + body + salt)
 * 概念版 salt：LnT6xpN3khm36zse0QzvmgTZ3waWdRSA
 */
object KugouApi {

    // 概念版签名盐值
    private const val SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"

    // 网关地址
    private const val BASE_URL = "https://gateway.kugou.com"

    // 概念版固定参数
    private const val DEFAULT_APPID = "3116"
    private const val DEFAULT_CLIENTVER = "11440"
    private const val SOURCE_ID = "90139"

    // 请求头
    private const val USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"

    // 听歌上报接口专用（参考 KuGouMusicApi youth_listen_song）
    private const val LISTEN_CLIENTVER = "10566"
    private const val LISTEN_UA = "Android13-1070-10566-201-0-ReportPlaySongToServerProtocol-wifi"

    // 结果码
    const val CODE_SUCCESS = 0
    const val CODE_ALREADY = 1
    const val CODE_RISK = 2
    const val CODE_ERROR = -1

    /**
     * 登录凭证，从酷狗 OkHttp 请求中捕获
     */
    class Credentials {
        var token: String? = null
        var userid: String? = null
        var dfid: String? = null
        var mid: String? = null
        var appid: String? = null
        var clientver: String? = null

        val isValid: Boolean
            get() = !token.isNullOrEmpty() && !userid.isNullOrEmpty()
    }

    /**
     * 领取结果
     */
    class ClaimResult {
        var code: Int = CODE_ERROR
        var message: String? = null
        var rawResponse: String? = null

        val isSuccess: Boolean
            get() = code == CODE_SUCCESS
    }

    /**
     * 获取今日日期（北京时间），格式 yyyy-MM-dd
     * 酷狗接口的 receive_day 使用北京时间日期
     */
    val todayKey: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            return sdf.format(Date())
        }

    /**
     * 获取本地日期（北京时间），格式 yyyy-MM-dd
     * 用于判断"今日是否已领取"，按用户所在时区的自然日计算
     */
    val localDayKey: String
        get() = todayKey

    /**
     * MD5 哈希
     */
    fun md5(input: String): String = try {
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } catch (_: Exception) {
        ""
    }

    /**
     * 构建签名
     * 算法：MD5(salt + sorted_kv_string + body + salt)
     * sorted_kv_string: 将参数按 key 字母序排列，拼接为 key1=value1key2=value2...
     */
    private fun buildSignature(params: Map<String, String>, body: String): String {
        val sb = StringBuilder()
        for (key in params.keys.sorted()) {
            sb.append(key).append("=").append(params[key])
        }
        return md5(SALT + sb + body + SALT)
    }

    /**
     * 构建公共参数 Map（不含签名）
     */
    private fun buildCommonParams(creds: Credentials): TreeMap<String, String> {
        val params = TreeMap<String, String>()
        params["appid"] = creds.appid?.takeIf { it.isNotEmpty() } ?: DEFAULT_APPID
        params["clientver"] = creds.clientver?.takeIf { it.isNotEmpty() } ?: DEFAULT_CLIENTVER
        params["clienttime"] = (System.currentTimeMillis() / 1000).toString()
        params["mid"] = creds.mid ?: ""
        params["dfid"] = creds.dfid ?: ""
        params["uuid"] = "-"
        params["token"] = creds.token ?: ""
        params["userid"] = creds.userid ?: "0"
        return params
    }

    /**
     * 构建 Cookie 字符串
     */
    private fun buildCookie(params: Map<String, String>): String =
        "token=${params["token"]}" +
            "; userid=${params["userid"]}" +
            "; dfid=${params["dfid"]}" +
            "; KUGOU_API_MID=${params["mid"]}"

    /**
     * 领取每日听歌VIP
     *
     * POST /youth/v1/recharge/receive_vip_listen_song
     * 参数: source_id=90139, receive_day=yyyy-MM-dd + 公共参数 + signature
     */
    fun claimVip(creds: Credentials?): ClaimResult {
        val result = ClaimResult()
        if (creds == null || !creds.isValid) {
            result.code = CODE_ERROR
            result.message = "凭证无效"
            return result
        }

        val params = buildCommonParams(creds)
        params["source_id"] = SOURCE_ID
        params["receive_day"] = todayKey

        val body = "" // 该接口参数全部在 URL query 中，body 为空
        params["signature"] = buildSignature(params, body)

        val urlStr = buildUrl("$BASE_URL/youth/v1/recharge/receive_vip_listen_song", params)
        val cookie = buildCookie(params)

        ModuleLog.log("领取VIP请求: ${maskUrl(urlStr)}")

        val response = doPost(urlStr, cookie, body)
        result.rawResponse = response
        parseClaimResponse(result, response)
        return result
    }

    /**
     * 升级概念版VIP（获得更高音质）
     *
     * POST /youth/v1/listen_song/upgrade_vip_reward
     * 参数: kugouid=userid, ad_type=1 + 公共参数 + signature
     */
    fun upgradeVip(creds: Credentials?): ClaimResult {
        val result = ClaimResult()
        if (creds == null || !creds.isValid) {
            result.code = CODE_ERROR
            result.message = "凭证无效"
            return result
        }

        val params = buildCommonParams(creds)
        params["kugouid"] = creds.userid ?: ""
        params["ad_type"] = "1"

        val body = ""
        params["signature"] = buildSignature(params, body)

        val urlStr = buildUrl("$BASE_URL/youth/v1/listen_song/upgrade_vip_reward", params)
        val cookie = buildCookie(params)

        ModuleLog.log("升级VIP请求: ${maskUrl(urlStr)}")

        val response = doPost(urlStr, cookie, body)
        result.rawResponse = response

        if (response == null) {
            result.code = CODE_ERROR
            result.message = "升级请求失败"
            return result
        }

        try {
            val json = JSONObject(response)
            val status = json.optInt("status", 0)
            val errorCode = json.optInt("error_code", 0)
            val errorMsg = json.optString("error_msg", "")

            if (status == 1) {
                result.code = CODE_SUCCESS
                result.message = "升级概念版VIP成功"
            } else {
                result.code = CODE_ERROR
                result.message = if (errorMsg.isEmpty()) "升级失败，错误码: $errorCode" else errorMsg
            }
        } catch (_: Exception) {
            result.code = CODE_ERROR
            result.message = "解析升级响应失败"
        }
        return result
    }

    /**
     * 解析领取响应
     * status=1 → 成功
     * error_code=131001 → 今日已领取
     * error_code=20028 → 账号风控
     */
    private fun parseClaimResponse(result: ClaimResult, response: String?) {
        if (response.isNullOrEmpty()) {
            result.code = CODE_ERROR
            result.message = "网络请求失败，无响应"
            return
        }

        try {
            val json = JSONObject(response)
            val status = json.optInt("status", 0)
            val errorCode = json.optInt("error_code", 0)
            val errorMsg = json.optString("error_msg", "")

            ModuleLog.log("领取响应: status=$status error_code=$errorCode")

            if (status == 1) {
                result.code = CODE_SUCCESS
                result.message = "领取成功，获得1天畅听VIP"
            } else if (errorCode == 131001) {
                result.code = CODE_ALREADY
                result.message = "今日已领取"
            } else if (errorCode == 20028) {
                result.code = CODE_RISK
                result.message = "账号风控，请前往手机端领取"
            } else {
                result.code = CODE_ERROR
                result.message =
                    if (errorMsg.isEmpty()) "领取失败，错误码: $errorCode" else "领取失败: $errorMsg"
            }
        } catch (e: Exception) {
            result.code = CODE_ERROR
            result.message = "解析响应失败: ${e.message}"
            ModuleLog.log("解析响应异常: $e")
        }
    }

    /**
     * 构建完整 URL（参数拼接为 query string）
     */
    private fun buildUrl(baseUrl: String, params: Map<String, String>): String {
        val sb = StringBuilder(baseUrl).append("?")
        for ((key, value) in params) {
            sb.append(key).append("=").append(value).append("&")
        }
        var url = sb.toString()
        if (url.endsWith("&")) {
            url = url.substring(0, url.length - 1)
        }
        return url
    }

    /**
     * 听歌上报（听歌任务打卡）
     *
     * POST /youth/v2/report/listen_song
     * 该接口使用独立 clientver 与 UA，JSON body 参与签名
     */
    fun reportListenSong(creds: Credentials, mixsongid: Long): ClaimResult {
        val result = ClaimResult()
        if (!creds.isValid) {
            result.code = CODE_ERROR
            result.message = "凭证无效"
            return result
        }

        val params = buildCommonParams(creds)
        params["clientver"] = LISTEN_CLIENTVER
        val body = "{\"mixsongid\":$mixsongid}"
        params["signature"] = buildSignature(params, body)

        val urlStr = buildUrl("$BASE_URL/youth/v2/report/listen_song", params)
        val cookie = buildCookie(params)

        ModuleLog.log("听歌上报请求: mixsongid=$mixsongid")

        val response = doPost(urlStr, cookie, body, LISTEN_UA, "application/json; charset=utf-8")
        result.rawResponse = response

        if (response == null) {
            result.code = CODE_ERROR
            result.message = "听歌上报请求失败"
            return result
        }

        try {
            val json = JSONObject(response)
            if (json.optInt("status", 0) == 1) {
                result.code = CODE_SUCCESS
                result.message = "听歌上报成功"
            } else {
                result.code = CODE_ERROR
                val errorCode = json.optInt("error_code", 0)
                val errorMsg = json.optString("error_msg", "")
                result.message = if (errorMsg.isEmpty()) "听歌上报失败，错误码: $errorCode" else errorMsg
            }
        } catch (_: Exception) {
            result.code = CODE_ERROR
            result.message = "解析听歌上报响应失败"
        }
        return result
    }

    /**
     * 执行 POST 请求（网络抖动自动重试 2 次，指数退避）
     */
    private fun doPost(
        urlStr: String, cookie: String, body: String,
        userAgent: String = USER_AGENT,
        contentType: String = "application/x-www-form-urlencoded"
    ): String? {
        var last: String? = null
        for (attempt in 0..2) {
            val r = doPostOnce(urlStr, cookie, body, userAgent, contentType)
            if (r != null) return r
            last = r
            if (attempt < 2) {
                try {
                    Thread.sleep(1000L shl attempt)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return last
    }

    private fun doPostOnce(
        urlStr: String, cookie: String, body: String,
        userAgent: String, contentType: String
    ): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.doInput = true

            // 请求头
            conn.setRequestProperty("Content-Type", contentType)
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("kg-rc", "1")
            conn.setRequestProperty("kg-thash", "5d816a0")
            conn.setRequestProperty("kg-rec", "1")
            conn.setRequestProperty("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")

            if (body.isNotEmpty()) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val responseCode = conn.responseCode
            ModuleLog.log("HTTP响应码: $responseCode")

            val stream = if (responseCode in 200..299) {
                conn.inputStream
            } else {
                // 4xx/5xx 时 errorStream 可能为 null，inputStream 会抛 IOException，兜底返回 null
                conn.errorStream ?: return null
            }

            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                return reader.readText()
            }
        } catch (e: Exception) {
            ModuleLog.log("请求异常: $e")
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 遮蔽 URL 中的敏感信息（token），用于日志输出
     */
    private fun maskUrl(url: String?): String {
        if (url == null) return ""
        return url.replace(Regex("token=[^&]+"), "token=***")
    }
}
