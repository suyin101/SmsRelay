package com.suyin.smsrelay

import android.content.Context
import android.os.Build
import android.provider.Settings as SystemSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一个转发模板：标题对应 Bark 弹窗的粗体标题、飞书消息的第一行 */
data class Template(val id: String, val name: String, val title: String, val body: String)

/** 按模板生成的推送内容 */
data class Rendered(val title: String, val body: String) {
    /** 飞书只有一段文字，标题和正文拼在一起 */
    val text: String get() = listOf(title, body).filter { it.isNotEmpty() }.joinToString("\n")
}

object Templates {

    const val CUSTOM = "custom"

    /** 模板里能用的参数，和说明 */
    val PARAMS = listOf(
        "验证码" to "自动识别，没有验证码时为空",
        "发件人" to "对方号码",
        "卡号" to "卡1 / 卡2，单卡时为空",
        "时间" to "收到短信的时间",
        "短信内容" to "短信原文",
        "手机名称" to "这台安卓手机的名字",
    )

    val PRESETS = listOf(
        Template("standard", "标准", "[验证码 {验证码} · ]{发件人}[（{卡号}）]", "{短信内容}"),
        Template("code_first", "验证码优先", "[{验证码}]", "{短信内容}\n—— {发件人}"),
        Template("detail", "详细信息", "{发件人}[（{卡号}）]", "{短信内容}\n\n时间：{时间}\n手机：{手机名称}"),
        Template("simple", "极简", "新短信", "{短信内容}"),
        Template("privacy", "隐私", "[验证码 {验证码}]", "收到 {发件人} 的短信，请到安卓手机上查看"),
    )

    /** 示例短信，用来在设置页预览效果 */
    val SAMPLE = Sms("10690000", "【示例】你的验证码是 123456，5 分钟内有效。", System.currentTimeMillis(), "卡1")

    /** 当前选中的模板；自定义模板用用户填的内容 */
    fun current(config: Config): Template =
        if (config.templateId == CUSTOM) Template(CUSTOM, "自定义", config.customTitle, config.customBody)
        else PRESETS.find { it.id == config.templateId } ?: PRESETS.first()

    fun render(context: Context, config: Config, sms: Sms): Rendered {
        val template = current(config)
        val values = mapOf(
            "验证码" to (Senders.findCode(sms.body) ?: ""),
            "发件人" to sms.from,
            "卡号" to sms.sim,
            "时间" to SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(sms.time)),
            "短信内容" to sms.body,
            "手机名称" to deviceName(context),
        )
        // 标题为空时用号码兜底，正文为空时用原文兜底，避免推送空白消息
        val title = fill(template.title, values).ifEmpty { sms.from }
        val body = fill(template.body, values).ifEmpty { sms.body }
        return Rendered(title, body)
    }

    private val OPTIONAL = Regex("\\[([^\\[\\]]*)]")
    private val PARAM = Regex("\\{([^{}]+)\\}")

    /**
     * 把 {参数} 换成实际内容。
     * 用 [ ] 括起来的一段：里面的参数有一个为空，整段就不显示；
     * 里面没有参数的方括号原样保留，比如 [短信]。
     */
    private fun fill(template: String, values: Map<String, String>): String {
        val resolved = OPTIONAL.replace(template) { m ->
            val inner = m.groupValues[1]
            val names = PARAM.findAll(inner).map { it.groupValues[1] }.toList()
            when {
                names.isEmpty() -> m.value
                names.all { !values[it].isNullOrEmpty() } -> inner
                else -> ""
            }
        }
        // 只替换一遍，短信原文里就算有 {xx} 也不会被再次替换
        return PARAM.replace(resolved) { m -> values[m.groupValues[1]] ?: m.value }.trim()
    }

    /** 模板里写错的参数名，用来在设置页提醒 */
    fun unknownParams(text: String): List<String> {
        val known = PARAMS.map { it.first }.toSet()
        return PARAM.findAll(text).map { it.groupValues[1] }.filter { it !in known }.distinct().toList()
    }

    private fun deviceName(context: Context): String =
        SystemSettings.Global.getString(context.contentResolver, SystemSettings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
}
