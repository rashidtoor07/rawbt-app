package com.visionweblabs.thermalbtprint.utils

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import android.util.Log

object JsonPrintParser {
    private val gson = Gson()
    private const val TAG = "JsonPrintParser"

    fun parseAndBuild(json: String, builder: EscPosBuilder) {
        val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
        val commands: List<Map<String, Any>> = try {
            val parsed = gson.fromJson<List<Map<String, Any>>>(json, listType)
            parsed ?: throw JsonSyntaxException("Parsed result is null")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON array, trying single object", e)
            try {
                val singleItem = gson.fromJson<Map<String, Any>>(json, object : TypeToken<Map<String, Any>>() {}.type)
                singleItem?.let { listOf(it) } ?: throw JsonSyntaxException("Parsed result is null")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to parse JSON object", e2)
                throw IllegalArgumentException("Invalid JSON format. Expected array or object.")
            }
        }

        Log.d(TAG, "Parsed ${commands.size} commands")
        
        for ((index, cmd) in commands.withIndex()) {
            val align = when (cmd["align"] as? String) {
                "center" -> EscPosBuilder.ALIGN_CENTER
                "right" -> EscPosBuilder.ALIGN_RIGHT
                else -> EscPosBuilder.ALIGN_LEFT
            }
            builder.align(align)

            val bold = cmd["bold"] as? Boolean ?: false
            builder.bold(bold)

            val size = when (cmd["size"] as? String) {
                "double" -> EscPosBuilder.FONT_SIZE_DOUBLE
                "double_height" -> EscPosBuilder.FONT_SIZE_DOUBLE_HEIGHT
                "double_width" -> EscPosBuilder.FONT_SIZE_DOUBLE_WIDTH
                else -> EscPosBuilder.FONT_SIZE_NORMAL
            }
            builder.fontSize(size)

            val type = cmd["type"] as? String ?: "text"
            val text = cmd["text"] as? String ?: ""

            when (type) {
                "text" -> builder.text(text)
                "barcode" -> builder.barcode(text)
                "qr" -> builder.qrCode(text)
                // Every job already ends with footer + feed + cut, so a trailing cut would
                // put the footer on a separate scrap of paper
                "cut" -> if (index < commands.lastIndex) builder.cut()
                else -> {
                    Log.w(TAG, "Unknown command type: $type, treating as text")
                    builder.text(type)
                }
            }

            if (cmd["newline"] as? Boolean == true) {
                builder.lineBreak()
            }
        }
    }
}
