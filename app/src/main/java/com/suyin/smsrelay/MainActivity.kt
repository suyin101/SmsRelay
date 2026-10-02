package com.suyin.smsrelay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
            PermissionCard(resumeTick)

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

@SuppressLint("BatteryLife")
@Composable
private fun PermissionCard(resumeTick: Int) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        KeepAliveService.start(context)
    }

    // 回到界面或授权结束后重新检查
    val smsOk = remember(resumeTick, tick) {
        hasPermission(context, Manifest.permission.RECEIVE_SMS) && hasPermission(context, Manifest.permission.READ_SMS)
    }
    val listenerOk = remember(resumeTick, tick) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    val notifyOk = remember(resumeTick, tick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    val batteryOk = remember(resumeTick, tick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    Section("权限") {
        StatusRow("接收和读取短信", smsOk) {
            launcher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
        }
        StatusRow("通知使用权（锁屏时唤醒）", listenerOk) {
            context.startActivity(Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        StatusRow("通知", notifyOk) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        StatusRow("不限制后台耗电", batteryOk) {
            context.startActivity(
                Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        }
        if (!smsOk || !listenerOk) {
            Text(
                "如果点授权没反应：到 设置 → 应用 → 短信转发 → 权限 → 短信，选择“允许”。通知使用权在列表里找“短信转发（锁屏唤醒）”打开",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
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
            4. 保持通知栏里的“正在后台监听短信”常驻通知，不要关掉它
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
