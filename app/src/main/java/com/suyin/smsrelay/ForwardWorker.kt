package com.suyin.smsrelay

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "SmsRelay"

object Forwarder {
    const val CHANNEL_FEISHU = "feishu"
    const val CHANNEL_BARK = "bark"

    enum class Outcome { SENT, CONFIG_ERROR, NETWORK_ERROR }

    fun enabledChannels(context: Context): List<String> {
        val config = Settings.load(context)
        return buildList {
            if (config.feishuEnabled) add(CHANNEL_FEISHU)
            if (config.barkEnabled) add(CHANNEL_BARK)
        }
    }

    /** 立即发送到一个渠道，并记录结果（网络错误除外，由调用方决定是否重试） */
    fun send(context: Context, channel: String, sms: Sms): Outcome {
        val config = Settings.load(context)
        return try {
            when (channel) {
                CHANNEL_FEISHU -> Senders.sendFeishu(config, sms)
                CHANNEL_BARK -> Senders.sendBark(config, sms)
            }
            log(context, channel, sms, true, "已转发")
            Outcome.SENT
        } catch (e: ConfigException) {
            log(context, channel, sms, false, e.message ?: "配置错误")
            Outcome.CONFIG_ERROR
        } catch (e: IOException) {
            Log.w(TAG, "$channel 网络错误：${e.message}")
            Outcome.NETWORK_ERROR
        }
    }

    fun log(context: Context, channel: String, sms: Sms, ok: Boolean, message: String) {
        val name = if (channel == CHANNEL_FEISHU) "飞书" else "Bark"
        Log.i(TAG, "$name ${if (ok) "成功" else "失败"}：$message")
        Settings.addLog(context, LogEntry(System.currentTimeMillis(), name, sms.from, ok, message))
    }

    /**
     * 收到短信后马上发送：持有唤醒锁防止锁屏时 CPU 休眠，各渠道并行发送。
     * 网络不通时交给 WorkManager，等联网后自动补发。
     * 会阻塞当前线程直到发送结束，不要在主线程调用。
     */
    fun forwardNow(context: Context, sms: Sms): Int {
        val channels = enabledChannels(context)
        if (channels.isEmpty()) return 0
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsRelay:forward")
        wakeLock.acquire(60_000)
        try {
            channels.map { channel ->
                thread {
                    if (send(context, channel, sms) == Outcome.NETWORK_ERROR) {
                        ForwardWorker.enqueueRetry(context, channel, sms)
                    }
                }
            }.forEach { it.join() }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
        return channels.size
    }
}

/** 直接发送失败后的补发任务：等联网后执行，失败会自动重试 */
class ForwardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val channel = inputData.getString(KEY_CHANNEL) ?: return@withContext Result.failure()
        val sms = Sms(
            from = inputData.getString(KEY_FROM) ?: "",
            body = inputData.getString(KEY_BODY) ?: "",
            time = inputData.getLong(KEY_TIME, System.currentTimeMillis()),
            sim = inputData.getString(KEY_SIM) ?: "",
        )
        when (Forwarder.send(applicationContext, channel, sms)) {
            Forwarder.Outcome.SENT -> Result.success()
            Forwarder.Outcome.CONFIG_ERROR -> Result.failure()
            Forwarder.Outcome.NETWORK_ERROR ->
                if (runAttemptCount < MAX_RETRIES) {
                    Result.retry()
                } else {
                    Forwarder.log(applicationContext, channel, sms, false, "多次重试仍失败，请检查网络")
                    Result.failure()
                }
        }
    }

    // Android 12 以下加急任务需要显示一个通知
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(2, KeepAliveService.buildNotification(applicationContext, "正在转发短信…"))

    companion object {
        private const val KEY_CHANNEL = "channel"
        private const val KEY_FROM = "from"
        private const val KEY_BODY = "body"
        private const val KEY_TIME = "time"
        private const val KEY_SIM = "sim"
        private const val MAX_RETRIES = 10

        fun enqueueRetry(context: Context, channel: String, sms: Sms) {
            Log.i(TAG, "$channel 发送失败，等联网后重试")
            val request = OneTimeWorkRequestBuilder<ForwardWorker>()
                .setInputData(
                    workDataOf(
                        KEY_CHANNEL to channel,
                        KEY_FROM to sms.from,
                        KEY_BODY to sms.body,
                        KEY_TIME to sms.time,
                        KEY_SIM to sms.sim,
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
