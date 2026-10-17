package com.suyin.smsrelay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// 界面配色
private val Brand = Color(0xFF3370FF)
private val PageBg = Color(0xFFF3F5F9)
private val Good = Color(0xFF16A34A)
private val Warn = Color(0xFFEA8A0C)
private val Bad = Color(0xFFDC2626)
private val Muted = Color(0xFF6B7280)

class MainActivity : ComponentActivity() {

    // 每次回到界面时 +1，让权限状态重新检查
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        KeepAliveService.start(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Brand,
                    secondaryContainer = Color(0xFFE3EBFF),
                    onSecondaryContainer = Color(0xFF1D3F99),
                    background = PageBg,
                    surface = Color.White,
                    surfaceContainerHighest = Color(0xFFF1F3F7),
                ),
            ) {
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

    // 打开 App 时自动检查一次更新，失败了不打扰
    val update = remember { UpdateController(context) }
    LaunchedEffect(Unit) { update.check(silent = true) }

    Scaffold(
        containerColor = PageBg,
        topBar = {
            TopAppBar(
                title = { Text("短信转发", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PageBg),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (update.state.release != null) UpdateCard(update)
            StatusCard(resumeTick, config)

            Section(
                "Bark",
                icon = Icons.Rounded.Notifications,
                subtitle = "推送到 iPhone，验证码可一键复制",
                action = { Switch(config.barkEnabled, { config = config.copy(barkEnabled = it) }) },
            ) {
                OutlinedTextField(
                    value = config.barkUrl,
                    onValueChange = { config = config.copy(barkUrl = it) },
                    label = { Text("Bark 地址") },
                    placeholder = { Text("https://api.day.app/你的key/") },
                    supportingText = { Text("在 iPhone 的 Bark 里复制任意一个示例链接粘贴到这里") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                BarkEncryptionFields(config.barkEncryptKey) { config = config.copy(barkEncryptKey = it) }
            }

            Section(
                "飞书机器人",
                icon = Icons.Rounded.Send,
                subtitle = "发到飞书群里",
                action = { Switch(config.feishuEnabled, { config = config.copy(feishuEnabled = it) }) },
            ) {
                OutlinedTextField(
                    value = config.feishuWebhook,
                    onValueChange = { config = config.copy(feishuWebhook = it) },
                    label = { Text("Webhook 地址") },
                    placeholder = { Text("https://open.feishu.cn/open-apis/bot/v2/hook/…") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = config.feishuSecret,
                    onValueChange = { config = config.copy(feishuSecret = it) },
                    label = { Text("签名密钥（开启了签名校验才填）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TemplateCard(config) { config = it }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        keyProblem(config)?.let { toast(context, it); return@Button }
                        if (Forwarder.enabledChannels(context).isEmpty()) {
                            toast(context, "请先打开至少一个转发渠道")
                            return@Button
                        }
                        val sms = Sms("测试", "这是一条测试消息，你的验证码是 123456。", System.currentTimeMillis())
                        thread { Forwarder.forwardNow(context.applicationContext, sms) }
                        toast(context, "测试消息已发出，看下方记录")
                    },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("发送测试消息", fontSize = 16.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("设置会自动保存，不用点保存按钮", style = MaterialTheme.typography.bodySmall, color = Muted)
            }

            RealmeGuideCard()
            LogCard(logs)
            AboutCard(update)
            Spacer(Modifier.height(8.dp))
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
    val valid = Senders.isValidBarkKey(key.trim())
    OutlinedTextField(
        value = key,
        onValueChange = onChange,
        label = { Text("加密密钥（32 位，留空则不加密）") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        supportingText = {
            Text(
                when {
                    key.isEmpty() -> "未加密：Bark 服务器能看到短信内容"
                    valid -> "已启用加密，Bark 服务器和苹果都看不到内容"
                    else -> "还差 ${32 - key.trim().length} 位（需要正好 32 位）"
                },
                color = if (valid) Good else Warn,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(onClick = { onChange(Senders.randomString(32)) }) { Text("随机生成") }
        FilledTonalButton(
            enabled = key.isNotEmpty(),
            onClick = {
                clipboard.setText(AnnotatedString(key))
                toast(context, "已复制")
            },
        ) { Text("复制") }
    }
    Hint(
        "iPhone 上的设置：Bark 首页 → 推送加密 → 加密设置，算法选 AES256，模式选 GCM，" +
            "Key 填和这里完全相同的 32 位密钥，IV 随便填 12 位，然后点完成。"
    )
}

/** 一项检查：标题、是否通过、没通过时的说明和修复操作 */
private class Check(val label: String, val ok: Boolean, val hint: String, val fix: () -> Unit)

/** 首页顶部的状态卡：全部正常时显示绿色，有问题时列出没通过的项 */
@SuppressLint("BatteryLife")
@Composable
private fun StatusCard(resumeTick: Int, config: Config) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        KeepAliveService.start(context)
    }
    // 后台服务是打开界面时才启动的，稍等一下再检查一次，免得误报“没运行”
    LaunchedEffect(Unit) { delay(800); tick++ }
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
                "点“去开启”重新启动；如果反复停止，请按下面“手机后台设置”逐项设置",
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
    val allOk = failed == 0

    val channels = buildList {
        if (config.barkEnabled && config.barkUrl.isNotBlank()) {
            add(if (Senders.isValidBarkKey(config.barkEncryptKey)) "Bark（已加密）" else "Bark")
        }
        if (config.feishuEnabled && config.feishuWebhook.isNotBlank()) add("飞书")
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (allOk) Color(0xFFE8F7EE) else Color(0xFFFFF4E5)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = allOk) { expanded = !expanded },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (allOk) Good else Warn),
                ) {
                    Icon(
                        if (allOk) Icons.Rounded.Check else Icons.Rounded.Warning,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (allOk) "正在转发" else "还有 $failed 项没设置好",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            !allOk -> "短信可能转发不了，请按下面提示设置"
                            else -> "收到短信后会立即转发到 ${channels.joinToString("、")}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                if (allOk) {
                    Icon(
                        if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        "权限检查",
                        tint = Muted,
                    )
                }
            }
            // 有问题时始终展开；全部通过时默认收起
            AnimatedVisibility(!allOk || expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    HorizontalDivider(color = Color.Black.copy(alpha = 0.06f), modifier = Modifier.padding(bottom = 6.dp))
                    checks.forEach { check -> StatusRow(check) }
                    Spacer(Modifier.height(4.dp))
                    Hint("“允许自启动”“锁屏后断网”这类手机设置没法自动检测，请对照下面的“手机后台设置”检查")
                }
            }
        }
    }
}

@Composable
private fun StatusRow(check: Check) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (check.ok) Good else Warn),
            ) {
                Icon(
                    if (check.ok) Icons.Rounded.Check else Icons.Rounded.Warning,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(check.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (!check.ok) {
                Button(
                    onClick = check.fix,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Warn),
                    modifier = Modifier.height(34.dp),
                ) { Text("去开启") }
            }
        }
        if (!check.ok) {
            Text(
                check.hint,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(start = 34.dp, top = 2.dp),
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

    Section("推送模板", icon = Icons.Rounded.Edit, subtitle = "推送到手机时显示的样式") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val chipColors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Brand,
                selectedLabelColor = Color.White,
            )
            Templates.PRESETS.forEach { t ->
                FilterChip(
                    selected = config.templateId == t.id,
                    onClick = { onChange(config.copy(templateId = t.id)) },
                    label = { Text(t.name) },
                    colors = chipColors,
                )
            }
            FilterChip(
                selected = isCustom,
                onClick = { onChange(config.copy(templateId = Templates.CUSTOM)) },
                label = { Text("自定义") },
                colors = chipColors,
            )
        }

        // 用示例短信预览效果，样子模仿 iPhone 通知
        val preview = Templates.render(context, config, Templates.SAMPLE)
        NotificationPreview(preview)

        if (!isCustom) {
            TextButton(
                onClick = {
                    val t = Templates.current(config)
                    onChange(config.copy(templateId = Templates.CUSTOM, customTitle = t.title, customBody = t.body))
                    toast(context, "已复制到自定义，可以修改了")
                },
                contentPadding = PaddingValues(0.dp),
            ) { Text("以这个模板为基础自定义") }
        } else {
            OutlinedTextField(
                value = titleField,
                onValueChange = {
                    titleField = it
                    onChange(config.copy(customTitle = it.text))
                },
                label = { Text("标题") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
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
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) bodyFocused = true },
            )
            Hint("点下面的参数，插入到光标所在位置：")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Templates.PARAMS.forEach { (name, _) ->
                    SuggestionChip(
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
            Hint(
                Templates.PARAMS.joinToString("\n") { (name, desc) -> "{$name}：$desc" } +
                    "\n\n小技巧：用 [ ] 括起来的部分，里面的参数为空时整段不显示。" +
                    "比如 [验证码 {验证码} · ]，没识别到验证码时这段就自动隐藏"
            )
            val unknown = Templates.unknownParams(config.customTitle + config.customBody)
            if (unknown.isNotEmpty()) {
                Text(
                    "不认识的参数：${unknown.joinToString("、") { "{$it}" }}，会原样显示",
                    style = MaterialTheme.typography.bodySmall,
                    color = Bad,
                )
            }
        }
    }
}

@Composable
private fun NotificationPreview(preview: Rendered) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFE9ECF2))
            .padding(10.dp),
    ) {
        Text("预览", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFFE5484D)),
            ) {
                Icon(Icons.Rounded.Notifications, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(
                        preview.title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("现在", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
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
    var expanded by remember { mutableStateOf(false) }
    val steps = listOf(
        "设置 → 应用 → 应用管理 → 短信转发 → 耗电管理：打开“允许后台运行”“允许自启动”“允许关联启动”",
        "打开最近任务（底部上滑停住），长按本 App 卡片 → 点“锁定”",
        "设置 → 电池 → 关闭“省电模式”中对本 App 的限制（如有）",
        "关闭“锁屏后断网”：在设置顶部搜索“断网”或“锁屏”找到它并关掉。开着的话，锁屏期间的短信要等解锁后才能转发",
        "保持通知栏里的“正在后台监听短信”常驻通知，不要关掉它",
    )
    Section(
        "手机后台设置",
        icon = Icons.Rounded.Settings,
        subtitle = "很重要：不设置的话，过一段时间就收不到转发了",
        onHeaderClick = { expanded = !expanded },
        action = {
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                if (expanded) "收起" else "展开",
                tint = Muted,
            )
        },
        content = if (!expanded) null else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Hint("以 realme / OPPO / 一加为例，其他手机的设置名字可能不同：")
                    steps.forEachIndexed { i, step ->
                        Row {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE3EBFF)),
                            ) {
                                Text("${i + 1}", color = Brand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                    FilledTonalButton(onClick = {
                        context.startActivity(
                            Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:${context.packageName}"))
                        )
                    }) { Text("打开本 App 的系统设置") }
                }
            }
        },
    )
}

@Composable
private fun LogCard(logs: List<LogEntry>) {
    val context = LocalContext.current
    Section(
        "最近记录",
        icon = Icons.Rounded.List,
        action = {
            if (logs.isNotEmpty()) TextButton(onClick = { Settings.clearLog(context) }) { Text("清空") }
        },
    ) {
        if (logs.isEmpty()) {
            Text("还没有转发记录", style = MaterialTheme.typography.bodyMedium, color = Muted)
        } else {
            val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
            logs.forEachIndexed { i, log ->
                if (i > 0) HorizontalDivider(color = Color.Black.copy(alpha = 0.06f))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (log.ok) Good else Bad)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(log.from, fontWeight = FontWeight.Medium)
                        Text(
                            "${log.channel} · ${log.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (log.ok) Muted else Bad,
                        )
                    }
                    Text(fmt.format(Date(log.time)), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
}

/** 检查更新的进度 */
private sealed class UpdateState(val release: Release? = null) {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object Latest : UpdateState()
    class Found(release: Release) : UpdateState(release)
    class Downloading(release: Release, val percent: Int) : UpdateState(release)
    class Ready(release: Release, val file: File) : UpdateState(release)
    class Failed(val message: String, release: Release? = null) : UpdateState(release)
}

private class UpdateController(private val context: Context) {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    val currentVersion = Updater.currentVersion(context)

    suspend fun check(silent: Boolean) {
        if (state is UpdateState.Checking || state is UpdateState.Downloading) return
        state = UpdateState.Checking
        state = try {
            val release = withContext(Dispatchers.IO) { Updater.fetchLatest() }
            if (Updater.isNewer(release.version, currentVersion)) UpdateState.Found(release) else UpdateState.Latest
        } catch (e: Exception) {
            if (silent) UpdateState.Idle else UpdateState.Failed("检查失败：${Updater.describe(e)}")
        }
    }

    suspend fun download(release: Release) {
        state = UpdateState.Downloading(release, 0)
        state = try {
            val file = withContext(Dispatchers.IO) {
                Updater.download(context, release) { percent ->
                    // 下载在后台线程，进度切回主线程更新界面
                    ContextCompat.getMainExecutor(context).execute {
                        if (state is UpdateState.Downloading) state = UpdateState.Downloading(release, percent)
                    }
                }
            }
            UpdateState.Ready(release, file)
        } catch (e: Exception) {
            UpdateState.Failed("下载失败：${e.message}", release)
        }
        (state as? UpdateState.Ready)?.let { install(it.file) }
    }

    fun install(file: File) {
        if (!Updater.canInstall(context)) {
            toast(context, "请允许“短信转发”安装应用，然后回来点“安装”")
            Updater.openInstallPermissionSettings(context)
            return
        }
        Updater.install(context, file)
    }
}

@Composable
private fun UpdateCard(update: UpdateController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = update.state
    val release = state.release ?: return
    var showNotes by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Brand),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Refresh, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    "发现新版本 v${release.version}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                if (release.notes.isNotBlank()) {
                    Text(
                        if (showNotes) "收起" else "更新内容",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showNotes = !showNotes }
                            .padding(6.dp),
                    )
                }
            }
            Text(
                "当前 v${update.currentVersion} · 安装包 %.1f MB · 覆盖安装，设置和记录都会保留".format(release.size / 1048576.0),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            AnimatedVisibility(showNotes) {
                Text(
                    cleanNotes(release.notes),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(12.dp),
                )
            }
            val whiteButton = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand)
            when (state) {
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("正在下载… ${state.percent}%", style = MaterialTheme.typography.bodySmall, color = Color.White)
                }
                is UpdateState.Ready -> Button(onClick = { update.install(state.file) }, colors = whiteButton) { Text("安装") }
                else -> {
                    if (state is UpdateState.Failed) {
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFE08A))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { scope.launch { update.download(release) } }, colors = whiteButton) {
                            Text(if (state is UpdateState.Failed) "重新下载" else "下载并安装")
                        }
                        // App 里下载不动时，可以用浏览器下载
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) { Text("用浏览器下载") }
                    }
                }
            }
        }
    }
}

/** 去掉更新说明里的 Markdown 符号，方便直接显示 */
private fun cleanNotes(notes: String): String =
    notes.lines()
        .map { it.trim().removePrefix(">").trim().replace("**", "") }
        .map { if (it.startsWith("- ")) "• " + it.removePrefix("- ") else it.trimStart('#', ' ') }
        .joinToString("\n")
        .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

@Composable
private fun AboutCard(update: UpdateController) {
    val scope = rememberCoroutineScope()
    val state = update.state
    val status = when {
        state is UpdateState.Latest -> "已经是最新版本"
        state.release != null -> "有新版本，见页面顶部"
        state is UpdateState.Failed -> state.message
        else -> null
    }
    Section(
        "关于",
        icon = Icons.Rounded.Info,
        subtitle = "当前版本 v${update.currentVersion}",
        action = {
            FilledTonalButton(
                enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                onClick = { scope.launch { update.check(silent = false) } },
            ) { Text(if (state is UpdateState.Checking) "检查中…" else "检查更新") }
        },
        content = status?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is UpdateState.Failed && state.release == null) Bad else Good,
                )
            }
        },
    )
}

/** 白色圆角卡片，标题左边带图标，右边可放开关或按钮 */
@Composable
private fun Section(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    onHeaderClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onHeaderClick != null) Modifier.clickable(onClick = onHeaderClick) else Modifier),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFE3EBFF)),
                ) {
                    Icon(icon, null, tint = Brand, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                action?.invoke()
            }
            content?.invoke()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Muted)
}

private fun toast(context: Context, text: String) =
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
