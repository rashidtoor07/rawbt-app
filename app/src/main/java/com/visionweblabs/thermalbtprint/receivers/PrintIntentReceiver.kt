package com.visionweblabs.thermalbtprint.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.visionweblabs.thermalbtprint.bluetooth.PrinterManager
import com.visionweblabs.thermalbtprint.utils.EscPosBuilder
import com.visionweblabs.thermalbtprint.utils.JsonPrintParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PrintIntentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.visionweblabs.thermalbtprint.PRINT") {
            val type = intent.getStringExtra("type") ?: "text"
            val dataString = intent.getStringExtra("data") ?: return

            val printerManager = PrinterManager.getInstance(context)
            val builder = EscPosBuilder().init()

            try {
                if (type == "json") {
                    JsonPrintParser.parseAndBuild(dataString, builder)
                } else {
                    builder.text(dataString).lineBreak()
                }

                val printData = builder.branding().feed(3).cut().build()
                
                // Keep the process alive until the print finishes; onReceive returning
                // alone lets the system kill it mid-job.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        printerManager.print(printData) { success, error ->
                            if (!success) {
                                Log.e("PrintIntentReceiver", "Print failed: $error")
                            }
                        }
                    } finally {
                        pending.finish()
                    }
                }
            } catch (e: Exception) {
                Log.e("PrintIntentReceiver", "Error processing print intent", e)
            }
        }
    }
}
