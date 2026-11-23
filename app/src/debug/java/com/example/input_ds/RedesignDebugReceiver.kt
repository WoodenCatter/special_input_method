package com.example.input_ds

import android.content.*
import androidx.core.content.ContextCompat

internal fun registerRedesignDebug(context: Context, dispatch: (String) -> String): () -> Unit {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd").orEmpty().trim()
            val valid = cmd in setOf("tick", "fake incoming", "fake assistant", "fake contacts", "fake home reset") ||
                cmd.matches(Regex("nav (home|contacts|cact|chat|talk|invite|asst|ent|settings|tv)|signal [LRB]|freeze (on|off)|mode (light|dark|auto)|scan (auto|manual)|care (on|off)"))
            val result = if (valid) dispatch(cmd) else "invalid command"
            if (isOrderedBroadcast) setResult(if (result == "ok") -1 else 0, result, null)
        }
    }
    ContextCompat.registerReceiver(context, receiver, IntentFilter("com.example.input_ds.DEBUG"),
        android.Manifest.permission.DUMP, null, ContextCompat.RECEIVER_EXPORTED)
    return { context.unregisterReceiver(receiver) }
}
