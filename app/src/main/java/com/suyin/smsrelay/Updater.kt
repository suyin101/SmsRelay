package com.suyin.smsrelay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import android.util.Log
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.security.MessageDigest

/** GitHub 上发布的一个版本 */
data class Release(
    val version: String,
    val notes: String,
    /** 依次尝试的下载地址 */
    val apkUrls: List<String>,
    val size: Long,
    /** GitHub 给出的安装包 SHA-256，可能为空 */
    val sha256: String,
    val pageUrl: String,
)

/** 检查更新：读 GitHub Releases 的最新版本，下载安装包并调起系统安装 */
object Updater {

    private const val TAG = "SmsRelay"

    private const val API = "https://api.github.com/repos/suyin101/SmsRelay/releases/latest"
    const val RELEASES_PAGE = "https://github.com/suyin101/SmsRelay/releases/latest"

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** 读取最新版本；网络不通时抛 IOException */
    fun fetchLatest(): Release {
        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val status = conn.responseCode
            if (status != 200) throw IOException("GitHub 返回 $status")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

            val assets = json.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0))
                .map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?: throw IOException("最新版本里没有安装包")
            val direct = apk.getString("browser_download_url")
            return Release(
                version = json.getString("tag_name").removePrefix("v"),
                notes = json.optString("body"),
                // 国内经常连不上 github.com：先走 API 的下载地址（会跳到 githubusercontent），
                // 再试国内镜像，最后才是原地址。下载后都要核对 SHA-256，镜像没法掉包
                apkUrls = listOf(
                    apk.getString("url"),
                    "https://ghfast.top/$direct",
                    "https://gh-proxy.com/$direct",
                    direct,
                ),
                size = apk.optLong("size"),
                sha256 = apk.optString("digest").removePrefix("sha256:"),
                pageUrl = json.optString("html_url", RELEASES_PAGE),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 按 1.2.10 这种格式逐段比较，remote 更新时返回 true */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 下载安装包到缓存目录，onProgress 收到 0~100；一个地址不通就换下一个 */
    fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "SmsRelay-v${release.version}.apk")

        var lastError: Exception? = null
        for (url in release.apkUrls) {
            try {
                downloadFrom(url, file, release, onProgress)
                return file
            } catch (e: Exception) {
                Log.w(TAG, "下载失败 $url：$e")
                file.delete()
                lastError = e
            }
        }
        throw IOException(describe(lastError))
    }

    private fun downloadFrom(url: String, file: File, release: Release, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/octet-stream")
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            val digest = MessageDigest.getInstance("SHA-256")
            onProgress(0)
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            // 和 GitHub 上记录的校验值对比，防止下载到不完整或被篡改的文件
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256.isNotEmpty() && !hex.equals(release.sha256, ignoreCase = true)) {
                throw IOException("安装包校验失败")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 把网络异常翻译成看得懂的话 */
    fun describe(e: Exception?): String = when (e) {
        is SocketTimeoutException -> "连接超时，可能是网络连不上 GitHub"
        is UnknownHostException -> "没有网络"
        null -> "未知错误"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** 安卓 8 起要单独允许本 App“安装未知应用” */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(SystemSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }

    /** 调起系统安装界面；签名相同，会直接覆盖安装，设置不会丢 */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
