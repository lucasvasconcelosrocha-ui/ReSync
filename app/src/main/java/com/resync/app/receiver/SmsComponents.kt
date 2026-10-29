package com.resync.app.receiver

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import com.resync.app.engine.RestoreRepository
import com.resync.app.util.AppLogger

/**
 * Mandatory BroadcastReceiver for ROLE_SMS eligibility.
 * Handles SMS_DELIVER when ReSync is temporarily the default SMS app during restore.
 * Strictly ignores any incoming external Intent unless a restore operation is actively running.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (!RestoreRepository.isRestoreActive()) {
            AppLogger.w("SmsDeliverReceiver", "Intent externa de SMS ignorada pois nenhuma restauração de SMS está ativa.")
            return
        }
        // No-op: ReSync only holds default app role during restore operations.
    }
}

/**
 * Mandatory BroadcastReceiver for ROLE_SMS eligibility.
 * Handles WAP_PUSH_DELIVER when ReSync is temporarily the default SMS app during restore.
 * Strictly ignores any incoming external Intent unless a restore operation is actively running.
 */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (!RestoreRepository.isRestoreActive()) {
            AppLogger.w("MmsWapPushReceiver", "Intent externa de WAP PUSH ignorada pois nenhuma restauração de SMS está ativa.")
            return
        }
        // No-op
    }
}

/**
 * Mandatory Activity for ROLE_SMS eligibility.
 * Handles SENDTO intent for sms/smsto/mms/mmsto schemes.
 */
class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

/**
 * Mandatory Service for ROLE_SMS eligibility.
 * Handles RESPOND_VIA_MESSAGE action.
 * Strictly ignores any incoming external Intent unless a restore operation is actively running.
 */
class HeadlessSmsSendService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RestoreRepository.isRestoreActive()) {
            AppLogger.w("HeadlessSmsSendService", "Serviço de SMS invocado externamente sem restauração ativa. Encerrando.")
            stopSelf()
            return START_NOT_STICKY
        }
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
