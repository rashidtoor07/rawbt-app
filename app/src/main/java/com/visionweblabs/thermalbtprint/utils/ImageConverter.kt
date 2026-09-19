package com.visionweblabs.thermalbtprint.utils

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

object ImageConverter {

    fun convertBitmapToEscPos(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val bos = ByteArrayOutputStream()

        // GS v 0 m xL xH yL yH
        // m=0: normal size
        val xL = (width / 8) % 256
        val xH = (width / 8) / 256
        val yL = height % 256
        val yH = height / 256

        bos.write(0x1D)
        bos.write(0x76)
        bos.write(0x30)
        bos.write(0x00)
        bos.write(xL)
        bos.write(xH)
        bos.write(yL)
        bos.write(yH)

        for (y in 0 until height) {
            for (x in 0 until width / 8) {
                var byte = 0
                for (bit in 0 until 8) {
                    val px = x * 8 + bit
                    if (px < width) {
                        val pixel = bitmap.getPixel(px, y)
                        val gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                        if (gray < 128) {
                            byte = byte or (1 shl (7 - bit))
                        }
                    }
                }
                bos.write(byte)
            }
        }

        return bos.toByteArray()
    }
}
