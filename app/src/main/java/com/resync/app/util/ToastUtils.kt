package com.resync.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Exibe um Toast de forma 100% segura, despachando para a Main Thread caso seja
 * chamado a partir de uma corrotina ou thread secundária (como Dispatchers.IO),
 * prevenindo o crash fatal "Can't toast on a thread that has not called Looper.prepare()".
 */
fun Context.showSafeToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    val appCtx = applicationContext
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Toast.makeText(appCtx, message, duration).show()
    } else {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appCtx, message, duration).show()
        }
    }
}
