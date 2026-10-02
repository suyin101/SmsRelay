package com.suyin.smsrelay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlin.concurrent.thread

/**
 * 通知监听服务，用来应对 realme 的后台冻结。
 * App 被冻结时短信广播会被系统扣住，直到解锁才送达；
 * 但系统每次推送通知都会短暂唤醒本 App。所以看到短信 App 弹通知时，
 * 直接去收件箱读最新短信并转发。和 SmsReceiver 之间靠 Dedupe 去重。
 */
class WakeListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "通知监听已连接")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != Telephony.Sms.getDefaultSmsPackage(this)) return
        Log.i(TAG, "短信 App 弹出通知，检查收件箱")
        val context = applicationContext
        thread { SmsInbox.forwardRecent(context) }
    }
}

private const val TAG = "SmsRelay"

object SmsInbox {
    private const val KEY_LAST_ID = "inbox_last_id"
    private const val RECENT_MS = 5 * 60_000L

    /** 转发收件箱里最近 5 分钟内、还没处理过的短信 */
    @Synchronized
    fun forwardRecent(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "没有读取短信权限，无法从收件箱补发")
            return
        }
        val prefs = Settings.prefs(context)
        var lastId = prefs.getLong(KEY_LAST_ID, 0)
        val found = mutableListOf<Pair<Long, Sms>>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms._ID} > ? AND ${Telephony.Sms.DATE} > ?",
            arrayOf(lastId.toString(), (System.currentTimeMillis() - RECENT_MS).toString()),
            "${Telephony.Sms._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                found += c.getLong(0) to Sms(
                    from = c.getString(1) ?: "未知号码",
                    body = c.getString(2) ?: "",
                    time = c.getLong(3),
                )
            }
        }
        for ((id, sms) in found) {
            if (Dedupe.claim(context, sms)) {
                Log.i(TAG, "从收件箱转发短信 #$id")
                Forwarder.forwardNow(context, sms)
            }
            lastId = maxOf(lastId, id)
        }
        prefs.edit().putLong(KEY_LAST_ID, lastId).apply()
    }
}

/** 同一条短信可能从广播和收件箱两条路收到，只转发一次 */
object Dedupe {
    private const val WINDOW_MS = 10 * 60_000L
    private val recent = LinkedHashMap<String, Long>()

    /** 第一次见到这条短信返回 true，之后 10 分钟内再见到返回 false */
    @Synchronized
    fun claim(context: Context, sms: Sms): Boolean {
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value > WINDOW_MS }
        // 号码可能带或不带 +86，只比较最后 11 位数字
        val key = sms.from.filter { it.isDigit() }.takeLast(11) + "|" + sms.body
        if (key in recent) {
            Log.i(TAG, "重复短信，跳过")
            return false
        }
        recent[key] = now
        return true
    }
}
