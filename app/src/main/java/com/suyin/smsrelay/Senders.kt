package com.suyin.smsrelay

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 一条要转发的短信 */
data class Sms(
    val from: String,
    val body: String,
    val time: Long,
    val sim: String = "",
)

/** 配置填错了（地址不对、签名不对等），重试也没用 */
class ConfigException(message: String) : Exception(message)

object Senders {

    private val CODE_KEYWORDS = Regex("验证码|校验码|动态码|动态密码|确认码|驗證碼|code", RegexOption.IGNORE_CASE)
    private val CODE_DIGITS = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])")

    /** 从短信里找验证码，找不到返回 null */
    fun findCode(body: String): String? {
        if (!CODE_KEYWORDS.containsMatchIn(body)) return null
        return CODE_DIGITS.find(body)?.value
    }

    private fun formatTime(time: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(time))

    private fun title(sms: Sms): String =
        if (sms.sim.isEmpty()) sms.from else "${sms.from}（${sms.sim}）"

    // ---------- 飞书 ----------

    fun sendFeishu(config: Config, sms: Sms) {
        if (!config.feishuWebhook.startsWith("https://")) {
            throw ConfigException("飞书 Webhook 地址不正确")
        }
        val text = buildString {
            findCode(sms.body)?.let { append("🔑 验证码：$it\n") }
            append("📩 ${title(sms)}\n")
            append("🕐 ${formatTime(sms.time)}\n")
            append("————————\n")
            append(sms.body)
        }
        val json = JSONObject()
            .put("msg_type", "text")
            .put("content", JSONObject().put("text", text))

        if (config.feishuSecret.isNotEmpty()) {
            // 飞书签名：用 "时间戳\n密钥" 作为 HMAC 的 key，对空内容签名
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec("$timestamp\n${config.feishuSecret}".toByteArray(), "HmacSHA256"))
            val sign = Base64.encodeToString(mac.doFinal(ByteArray(0)), Base64.NO_WRAP)
            json.put("timestamp", timestamp).put("sign", sign)
        }

        val resp = postJson(config.feishuWebhook, json)
        // 飞书出错时 HTTP 也是 200，要看返回里的 code
        val code = resp.optInt("code", resp.optInt("StatusCode", -1))
        if (code != 0) {
            throw ConfigException("飞书返回错误：${resp.optString("msg", resp.toString())}")
        }
    }

    // ---------- Bark ----------

    fun sendBark(config: Config, sms: Sms) {
        // 用户从 Bark 里复制的地址形如 https://api.day.app/你的key/推送内容
        val uri = Uri.parse(config.barkUrl)
        val key = uri.pathSegments.firstOrNull()
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrEmpty() || key.isNullOrEmpty()) {
            throw ConfigException("Bark 地址不正确")
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val server = "${uri.scheme}://${uri.host}$port"

        val code = findCode(sms.body)
        val payload = JSONObject()
            .put("title", if (code != null) "验证码 $code · ${title(sms)}" else title(sms))
            .put("body", sms.body)
            .put("group", "短信")
            .put("level", "timeSensitive")
        if (code != null) payload.put("copy", code)

        val resp = if (config.barkEncryptKey.isEmpty()) {
            postJson("$server/push", payload.put("device_key", key))
        } else {
            // 加密推送：Bark 服务器和苹果只能看到密文，只有 iPhone 上的 Bark 能解开
            val iv = randomString(12)
            val ciphertext = encryptForBark(payload.toString(), config.barkEncryptKey, iv)
            postForm("$server/$key", mapOf("ciphertext" to ciphertext, "iv" to iv))
        }
        if (resp.optInt("code", -1) != 200) {
            throw ConfigException("Bark 返回错误：${resp.optString("message", resp.toString())}")
        }
    }

    // ---------- Bark 加密 ----------

    // 去掉了 0/O、1/l/I 这些容易看错的字符，方便手动输入
    private const val KEY_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

    fun randomString(length: Int): String {
        val random = SecureRandom()
        return (1..length).map { KEY_CHARS[random.nextInt(KEY_CHARS.length)] }.joinToString("")
    }

    /** 密钥必须正好 32 个英文字母或数字（对应 Bark 的 AES256） */
    fun isValidBarkKey(key: String) = key.length == 32 && key.all { it.code in 33..126 }

    /** 与 Bark 设置“AES256 / GCM / noPadding”对应，输出 base64(密文+校验码) */
    private fun encryptForBark(text: String, key: String, iv: String): String {
        if (!isValidBarkKey(key)) throw ConfigException("Bark 加密密钥必须是 32 位英文字母或数字")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(), "AES"),
            GCMParameterSpec(128, iv.toByteArray()),
        )
        return Base64.encodeToString(cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    // ---------- 网络 ----------

    private fun postJson(url: String, json: JSONObject): JSONObject =
        post(url, "application/json; charset=utf-8", json.toString())

    private fun postForm(url: String, fields: Map<String, String>): JSONObject =
        post(
            url,
            "application/x-www-form-urlencoded; charset=utf-8",
            fields.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" },
        )

    private fun post(url: String, contentType: String, body: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body.toByteArray()) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (status >= 500) throw IOException("服务器错误 $status")
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ConfigException("返回内容无法识别（HTTP $status），请检查地址")
            }
        } finally {
            conn.disconnect()
        }
    }
}
