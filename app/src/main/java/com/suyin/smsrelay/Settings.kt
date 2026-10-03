package com.suyin.smsrelay

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 用户填写的转发配置 */
data class Config(
    val feishuEnabled: Boolean = false,
    val feishuWebhook: String = "",
    val feishuSecret: String = "",
    val barkEnabled: Boolean = false,
    val barkUrl: String = "",
    /** Bark 加密密钥，32 位；留空则不加密 */
    val barkEncryptKey: String = "",
    /** 转发模板，见 Templates.PRESETS；选“自定义”时用下面两项 */
    val templateId: String = Templates.PRESETS.first().id,
    val customTitle: String = Templates.PRESETS.first().title,
    val customBody: String = Templates.PRESETS.first().body,
)

/** 一条转发记录，显示在首页“最近记录”里 */
data class LogEntry(
    val time: Long,
    val channel: String,
    val from: String,
    val ok: Boolean,
    val message: String,
)

object Settings {
    private const val PREFS = "settings"
    const val KEY_LOG = "log"
    private const val MAX_LOG = 50

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Config {
        val p = prefs(context)
        return Config(
            feishuEnabled = p.getBoolean("feishu_enabled", false),
            feishuWebhook = p.getString("feishu_webhook", "") ?: "",
            feishuSecret = p.getString("feishu_secret", "") ?: "",
            barkEnabled = p.getBoolean("bark_enabled", false),
            barkUrl = p.getString("bark_url", "") ?: "",
            barkEncryptKey = p.getString("bark_encrypt_key", "") ?: "",
            templateId = p.getString("template_id", null) ?: Templates.PRESETS.first().id,
            customTitle = p.getString("custom_title", null) ?: Templates.PRESETS.first().title,
            customBody = p.getString("custom_body", null) ?: Templates.PRESETS.first().body,
        )
    }

    fun save(context: Context, config: Config) {
        prefs(context).edit()
            .putBoolean("feishu_enabled", config.feishuEnabled)
            .putString("feishu_webhook", config.feishuWebhook.trim())
            .putString("feishu_secret", config.feishuSecret.trim())
            .putBoolean("bark_enabled", config.barkEnabled)
            .putString("bark_url", config.barkUrl.trim())
            .putString("bark_encrypt_key", config.barkEncryptKey.trim())
            .putString("template_id", config.templateId)
            .putString("custom_title", config.customTitle)
            .putString("custom_body", config.customBody)
            .apply()
    }

    @Synchronized
    fun addLog(context: Context, entry: LogEntry) {
        val p = prefs(context)
        val old = JSONArray(p.getString(KEY_LOG, "[]"))
        val updated = JSONArray().put(
            JSONObject()
                .put("time", entry.time)
                .put("channel", entry.channel)
                .put("from", entry.from)
                .put("ok", entry.ok)
                .put("message", entry.message)
        )
        for (i in 0 until minOf(old.length(), MAX_LOG - 1)) updated.put(old.get(i))
        p.edit().putString(KEY_LOG, updated.toString()).apply()
    }

    fun loadLog(context: Context): List<LogEntry> {
        val arr = JSONArray(prefs(context).getString(KEY_LOG, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            LogEntry(
                time = o.getLong("time"),
                channel = o.getString("channel"),
                from = o.getString("from"),
                ok = o.getBoolean("ok"),
                message = o.getString("message"),
            )
        }
    }

    fun clearLog(context: Context) {
        prefs(context).edit().remove(KEY_LOG).apply()
    }
}
