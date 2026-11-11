package com.visionweblabs.thermalbtprint.utils

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

class EscPosBuilder {
    private val bos = ByteArrayOutputStream()

    companion object {
        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2

        const val FONT_SIZE_NORMAL = 0x00
        const val FONT_SIZE_DOUBLE_HEIGHT = 0x01
        const val FONT_SIZE_DOUBLE_WIDTH = 0x10
        const val FONT_SIZE_DOUBLE = 0x11

        const val BRANDING = "BTPrint by VisionWebLabs.com"
        const val MAX_BARCODE_LENGTH = 30
    }

    fun init(): EscPosBuilder {
        bos.write(0x1B)
        bos.write(0x40)
        // Set character code table to PC850 (supports more special chars)
        bos.write(0x1B)
        bos.write(0x74)
        bos.write(0x02)
        return this
    }

    fun text(text: String): EscPosBuilder {
        // Replace common problematic characters
        val normalized = text
            .replace("–", "-")  // en dash
            .replace("—", "-")  // em dash
            .replace("™", "(TM)")
            .replace("®", "(R)")
            .replace("©", "(C)")
            .replace("•", "*")
            .replace("…", "...")
        bos.write(normalized.toByteArray(Charsets.ISO_8859_1))
        return this
    }

    fun lineBreak(): EscPosBuilder {
        bos.write(0x0A)
        return this
    }

    fun feed(lines: Int): EscPosBuilder {
        bos.write(0x1B)
        bos.write(0x64)
        bos.write(lines)
        return this
    }

    fun align(align: Int): EscPosBuilder {
        bos.write(0x1B)
        bos.write(0x61)
        bos.write(align)
        return this
    }

    fun bold(on: Boolean): EscPosBuilder {
        bos.write(0x1B)
        bos.write(0x45)
        bos.write(if (on) 1 else 0)
        return this
    }

    fun underline(on: Boolean): EscPosBuilder {
        bos.write(0x1B)
        bos.write(0x2D)
        bos.write(if (on) 1 else 0)
        return this
    }

    fun fontSize(size: Int): EscPosBuilder {
        bos.write(0x1D)
        bos.write(0x21)
        bos.write(size)
        return this
    }

    fun cut(): EscPosBuilder {
        bos.write(0x1D)
        bos.write(0x56)
        bos.write(0)
        return this
    }

    fun openCashDrawer(): EscPosBuilder {
        bos.write(0x1B)
        bos.write(0x70)
        bos.write(0x00)
        bos.write(0x32)
        bos.write(0x32)
        return this
    }

    fun barcode(content: String): EscPosBuilder {
        // In CODE128 data "{" starts a code-set switch, so a literal "{" is sent as "{{"
        val data = content.replace("{", "{{").toByteArray(Charsets.US_ASCII)
        require(data.size + 2 <= 255) { "Barcode content too long" }
        // Human-readable text below the bars
        bos.write(byteArrayOf(0x1D, 0x48, 0x02))
        // Bar height in dots
        bos.write(byteArrayOf(0x1D, 0x68, 80))
        // Narrow bars for long content so it still fits on 58mm paper
        bos.write(0x1D)
        bos.write(0x77)
        bos.write(if (content.length <= 12) 2 else 1)
        // CODE128, code set B
        bos.write(0x1D)
        bos.write(0x6B)
        bos.write(73)
        bos.write(data.size + 2)
        bos.write(0x7B)
        bos.write(0x42)
        bos.write(data)
        return this
    }

    fun qrCode(content: String): EscPosBuilder {
        // This is a simplified version of QR Code generation for ESC/POS
        // Usually, it involves multiple steps (set model, set size, store data, print)
        val data = content.toByteArray()
        val len = data.size + 3
        val pL = len % 256
        val pH = len / 256

        // 1. Set model
        bos.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00))
        // 2. Set size
        bos.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x06))
        // 3. Set error correction
        bos.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x30))
        // 4. Store data
        bos.write(0x1D)
        bos.write(0x28)
        bos.write(0x6B)
        bos.write(pL)
        bos.write(pH)
        bos.write(0x31)
        bos.write(0x50)
        bos.write(0x30)
        bos.write(data)
        // 5. Print
        bos.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30))
        
        return this
    }

    fun image(bitmap: Bitmap, height: Int = bitmap.height): EscPosBuilder {
        val bytes = ImageConverter.convertBitmapToEscPos(bitmap, height)
        bos.write(bytes)
        return this
    }

    fun branding(): EscPosBuilder {
        return lineBreak()
            .align(ALIGN_CENTER)
            .fontSize(FONT_SIZE_NORMAL)
            .bold(false)
            .text(BRANDING)
            .lineBreak()
    }

    fun table(rows: List<Array<String>>, columnWidths: IntArray): EscPosBuilder {
        for (row in rows) {
            val line = StringBuilder()
            for (i in row.indices) {
                val text = row[i]
                val width = columnWidths[i]
                line.append(text.padEnd(width))
            }
            text(line.toString()).lineBreak()
        }
        return this
    }

    fun build(): ByteArray {
        return bos.toByteArray()
    }
}
