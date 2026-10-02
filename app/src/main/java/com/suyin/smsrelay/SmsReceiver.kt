package com.suyin.smsrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import kotlin.concurrent.thread

/** 系统收到短信时会调用这里 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        // 双卡手机：0 是卡1，1 是卡2
        val slot = intent.getIntExtra(SubscriptionManager.EXTRA_SLOT_INDEX, -1)
        val sim = if (slot >= 0) "卡${slot + 1}" else ""

        // 长短信会被拆成好几段，按发件人拼回去
        val messages = parts.groupBy { it.originatingAddress ?: "未知号码" }.map { (from, list) ->
            Sms(
                from = from,
                body = list.joinToString("") { it.messageBody ?: "" },
                time = list.first().timestampMillis,
                sim = sim,
            )
        }
        Log.i("SmsRelay", "收到 ${messages.size} 条短信")

        // goAsync 让系统在后台线程发完之前不要冻结本进程
        val pending = goAsync()
        thread {
            try {
                messages.filter { Dedupe.claim(context, it) }.forEach { Forwarder.forwardNow(context, it) }
            } finally {
                pending.finish()
            }
        }
    }
}
