package com.suyin.smsrelay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    // 每次回到界面时 +1，让权限状态重新检查
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        KeepAliveService.start(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF3370FF))) {
                MainScreen(resumeTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

private fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(resumeTick: Int) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(Settings.load(context)) }
    var logs by remember { mutableStateOf(Settings.loadLog(context)) }

    // 改动后自动保存；加密密钥没填满 32 位时先不保存，避免半截密钥生效
    LaunchedEffect(config) {
        if (keyProblem(config) == null) Settings.save(context, config)
    }

    // 后台转发完成后，记录会实时刷新
    DisposableEffect(Unit) {
        val prefs = Settings.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Settings.KEY_LOG || key == null) logs = Settings.loadLog(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("短信转发") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PermissionCard(resumeTick, config)

            Section("飞书机器人") {
                SwitchRow("转发到飞书", config.feishuEnabled) { config = config.copy(feishuEnabled = it) }
                OutlinedTextField(
                    value = config.feishuWebhook,
                    onValueChange = { config = config.copy(feishuWebhook = it) },
                    label = { Text("Webhook 地址") },
                    placeholder = { Text("https://open.feishu.cn/open-apis/bot/v2/hook/…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = config.feishuSecret,
                    onValueChange = { config = config.copy(feishuSecret = it) },
                    label = { Text("签名密钥（开启了签名校验才填）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section("Bark（iPhone 弹窗通知）") {
                SwitchRow("转发到 Bark", config.barkEnabled) { config = config.copy(barkEnabled = it) }
                OutlinedTextField(
                    value = config.barkUrl,
                    onValueChange = { config = config.copy(barkUrl = it) },
                    label = { Text("Bark 地址") },
                    placeholder = { Text("https://api.day.app/你的key/") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "在 iPhone 的 Bark 里复制任意一个示例链接粘贴到这里即可",
                    style = MaterialTheme.typography.bodySmall,
                )
                BarkEncryptionFields(config.barkEncryptKey) { config = config.copy(barkEncryptKey = it) }
            }

            TemplateCard(config) { config = it }

            Button(onClick = {
                keyProblem(config)?.let { toast(context, it); return@Button }
                if (Forwarder.enabledChannels(context).isEmpty()) {
                    toast(context, "请先打开至少一个转发渠道")
                    return@Button
                }
                val sms = Sms("测试", "这是一条测试消息，你的验证码是 123456。", System.currentTimeMillis())
                thread { Forwarder.forwardNow(context.applicationContext, sms) }
                toast(context, "测试消息已发出，看下方记录")
            }) { Text("发送测试消息") }
            Text("设置会自动保存，不用点保存按钮", style = MaterialTheme.typography.bodySmall)

            RealmeGuideCard()

            Section("最近记录") {
                if (logs.isEmpty()) {
                    Text("还没有转发记录", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA) }
                    logs.forEach { log ->
                        Text(
                            "${if (log.ok) "✅" else "❌"} ${fmt.format(Date(log.time))} ${log.channel} · ${log.from}\n${log.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (log.ok) Color.Unspecified else Color(0xFFD32F2F),
                        )
                    }
                    TextButton(onClick = { Settings.clearLog(context) }) { Text("清空记录") }
                }
            }
        }
    }
}

/** 加密密钥格式不对时返回提示文字 */
private fun keyProblem(config: Config): String? {
    val key = config.barkEncryptKey.trim()
    return if (key.isNotEmpty() && !Senders.isValidBarkKey(key)) {
        "加密密钥必须正好 32 位（当前 ${key.length} 位），还没有保存"
    } else null
}

@Composable
private fun BarkEncryptionFields(key: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value = key,
        onValueChange = onChange,
        label = { Text("加密密钥（32 位，留空则不加密）") },
        singleLine = true,
        supportingText = {
            Text(
                when {
                    key.isEmpty() -> "⚠️ 未加密：Bark 服务器能看到短信内容"
                    Senders.isValidBarkKey(key.trim()) -> "🔒 已启用加密"
                    else -> "还差 ${32 - key.trim().length} 位（需要正好 32 位）"
                }
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onChange(Senders.randomString(32)) }) { Text("随机生成") }
        OutlinedButton(
            enabled = key.isNotEmpty(),
            onClick = {
                clipboard.setText(AnnotatedString(key))
                toast(context, "已复制")
            },
        ) { Text("复制") }
    }
    Text(
        "iPhone 上的设置：Bark 首页 → 推送加密 → 加密设置，算法选 AES256，模式选 GCM，Key 填和这里完全相同的 32 位密钥，IV 随便填 12 位，然后点完成。",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** 一项检查：标题、是否通过、没通过时的说明和修复操作 */
private class Check(val label: String, val ok: Boolean, val hint: String, val fix: () -> Unit)

@SuppressLint("BatteryLife")
@Composable
private fun PermissionCard(resumeTick: Int, config: Config) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        KeepAliveService.start(context)
    }
    fun openAppSettings() = context.startActivity(
        Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
    )

    // 回到界面、授权结束或改了配置后重新检查
    val checks = remember(resumeTick, tick, config) {
        listOf(
            Check(
                "接收和读取短信",
                hasPermission(context, Manifest.permission.RECEIVE_SMS) &&
                    hasPermission(context, Manifest.permission.READ_SMS),
                "没有这个权限就收不到短信。点授权没反应的话：设置 → 应用 → 短信转发 → 权限 → 短信，选“允许”",
            ) { launcher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) },
            Check(
                "通知使用权（锁屏时唤醒）",
                NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
                "在列表里找到“短信转发（锁屏唤醒）”并打开",
            ) { context.startActivity(Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            Check(
                "通知",
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    hasPermission(context, Manifest.permission.POST_NOTIFICATIONS),
                "用来显示“正在后台监听短信”的常驻通知",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            },
            Check(
                "不限制后台耗电",
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
                "在弹窗里选“允许”，否则系统会在后台限制本 App",
            ) {
                context.startActivity(
                    Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}"))
                )
            },
            Check(
                "后台可以使用网络",
                context.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus !=
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                "手机开了省流量模式，本 App 在后台不能联网。请在打开的页面里允许本 App 不受限制地使用数据",
            ) {
                try {
                    context.startActivity(
                        Intent(SystemSettings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}"))
                    )
                } catch (e: Exception) {
                    openAppSettings()
                }
            },
            Check(
                "后台服务正在运行",
                KeepAliveService.running,
                "点“去开启”重新启动；如果反复停止，请按下面“realme 后台设置”逐项设置",
            ) {
                KeepAliveService.start(context)
                scope.launch { delay(500); tick++ }
            },
            Check(
                "已设置转发渠道",
                (config.feishuEnabled && config.feishuWebhook.isNotBlank()) ||
                    (config.barkEnabled && config.barkUrl.isNotBlank()),
                "在下面填好飞书或 Bark 的地址，并打开对应的开关",
            ) { toast(context, "请在下面填好飞书或 Bark 的地址并打开开关") },
        )
    }
    val failed = checks.count { !it.ok }

    Section("权限检查") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (failed == 0) "✅ 全部通过，可以正常转发" else "⚠️ 还有 $failed 项没设置好，短信可能转发不了",
                color = if (failed == 0) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (failed == 0) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "查看") }
            }
        }
        // 有问题时始终展开；全部通过时默认收起
        if (failed > 0 || expanded) {
            checks.forEach { check ->
                StatusRow(check.label, check.ok, check.fix)
                if (!check.ok) {
                    Text(
                        check.hint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 28.dp),
                    )
                }
            }
            Text(
                "“允许自启动”“锁屏后断网”这类 realme 设置没法自动检测，请对照下面的说明检查",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateCard(config: Config, onChange: (Config) -> Unit) {
    val context = LocalContext.current
    val isCustom = config.templateId == Templates.CUSTOM

    // 自定义模板的输入框要记住光标位置，点参数时插到光标处
    var titleField by remember { mutableStateOf(TextFieldValue(config.customTitle)) }
    var bodyField by remember { mutableStateOf(TextFieldValue(config.customBody)) }
    var bodyFocused by remember { mutableStateOf(false) }
    LaunchedEffect(config.customTitle) {
        if (titleField.text != config.customTitle) {
            titleField = TextFieldValue(config.customTitle, TextRange(config.customTitle.length))
        }
    }
    LaunchedEffect(config.customBody) {
        if (bodyField.text != config.customBody) {
            bodyField = TextFieldValue(config.customBody, TextRange(config.customBody.length))
        }
    }

    Section("推送模板") {
        Text("选择转发到 iPhone 时显示的样式", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Templates.PRESETS.forEach { t ->
                FilterChip(
                    selected = config.templateId == t.id,
                    onClick = { onChange(config.copy(templateId = t.id)) },
                    label = { Text(t.name) },
                )
            }
            FilterChip(
                selected = isCustom,
                onClick = { onChange(config.copy(templateId = Templates.CUSTOM)) },
                label = { Text("自定义") },
            )
        }

        if (!isCustom) {
            TextButton(onClick = {
                val t = Templates.current(config)
                onChange(config.copy(templateId = Templates.CUSTOM, customTitle = t.title, customBody = t.body))
                toast(context, "已复制到自定义，可以修改了")
            }) { Text("以这个模板为基础自定义") }
        } else {
            OutlinedTextField(
                value = titleField,
                onValueChange = {
                    titleField = it
                    onChange(config.copy(customTitle = it.text))
                },
                label = { Text("标题") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) bodyFocused = false },
            )
            OutlinedTextField(
                value = bodyField,
                onValueChange = {
                    bodyField = it
                    onChange(config.copy(customBody = it.text))
                },
                label = { Text("正文") },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) bodyFocused = true },
            )
            Text("点下面的参数，插入到光标所在位置：", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Templates.PARAMS.forEach { (name, _) ->
                    AssistChip(
                        onClick = {
                            val tag = "{$name}"
                            if (bodyFocused) {
                                bodyField = bodyField.insert(tag)
                                onChange(config.copy(customBody = bodyField.text))
                            } else {
                                titleField = titleField.insert(tag)
                                onChange(config.copy(customTitle = titleField.text))
                            }
                        },
                        label = { Text(name) },
                    )
                }
            }
            Text(
                Templates.PARAMS.joinToString("\n") { (name, desc) -> "{$name}：$desc" } +
                    "\n\n小技巧：用 [ ] 括起来的部分，里面的参数为空时整段不显示。" +
                    "比如 [验证码 {验证码} · ]，没识别到验证码时这段就自动隐藏",
                style = MaterialTheme.typography.bodySmall,
            )
            val unknown = Templates.unknownParams(config.customTitle + config.customBody)
            if (unknown.isNotEmpty()) {
                Text(
                    "⚠️ 不认识的参数：${unknown.joinToString("、") { "{$it}" }}，会原样显示",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD32F2F),
                )
            }
        }

        // 用示例短信预览效果
        val preview = Templates.render(context, config, Templates.SAMPLE)
        Text("预览（示例短信）", style = MaterialTheme.typography.labelMedium)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(preview.title, fontWeight = FontWeight.Bold)
                Text(preview.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 在光标处插入文字，有选中内容时替换选中部分 */
private fun TextFieldValue.insert(tag: String): TextFieldValue {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    val newText = text.replaceRange(start, end, tag)
    return TextFieldValue(newText, TextRange(start + tag.length))
}

@Composable
private fun RealmeGuideCard() {
    val context = LocalContext.current
    Section("realme 后台设置（很重要）") {
        Text(
            """
            realme 会清理后台，不设置的话过一段时间就收不到转发了。请逐项设置：
            1. 设置 → 应用 → 应用管理 → 短信转发 → 耗电管理：打开“允许后台运行”“允许自启动”“允许关联启动”
            2. 打开最近任务（底部上滑停住），长按本 App 卡片 → 点“锁定”
            3. 设置 → 电池 → 关闭“省电模式”中对本 App 的限制（如有）
            4. 关闭“锁屏后断网”：在设置顶部搜索“断网”或“锁屏”找到它并关掉。开着的话，锁屏期间的短信要等解锁后才能转发
            5. 保持通知栏里的“正在后台监听短信”常驻通知，不要关掉它
            """.trimIndent(),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        }) { Text("打开本 App 的系统设置") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(if (ok) "✅" else "⚠️")
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onFix) { Text("去开启") }
    }
}

private fun toast(context: Context, text: String) =
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
