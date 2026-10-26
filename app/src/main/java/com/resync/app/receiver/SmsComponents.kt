package com.resync.app.receiver

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * Mandatory BroadcastReceiver for ROLE_SMS eligibility.
 * Handles SMS_DELIVER when ReSync is temporarily the default SMS app during restore.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // No-op: ReSync only holds default app role during restore operations.
    }
}

/**
 * Mandatory BroadcastReceiver for ROLE_SMS eligibility.
 * Handles WAP_PUSH_DELIVER when ReSync is temporarily the default SMS app during restore.
 */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
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
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
