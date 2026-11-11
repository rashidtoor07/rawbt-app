package com.visionweblabs.thermalbtprint.utils

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

object ImageConverter {

    // Cheap printers overflow their buffer on one huge raster block, so send it in bands
    private const val BAND_HEIGHT = 128

    fun convertBitmapToEscPos(bitmap: Bitmap, height: Int = bitmap.height): ByteArray {
        val width = bitmap.width
        val rows = height.coerceIn(0, bitmap.height)
        val bytesPerRow = (width + 7) / 8
        val bos = ByteArrayOutputStream()
        val row = IntArray(width)

        var top = 0
        while (top < rows) {
            val band = minOf(BAND_HEIGHT, rows - top)
            // GS v 0 m xL xH yL yH
            bos.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
            bos.write(bytesPerRow % 256)
            bos.write(bytesPerRow / 256)
            bos.write(band % 256)
            bos.write(band / 256)

            for (y in top until top + band) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (xByte in 0 until bytesPerRow) {
                    var byte = 0
                    for (bit in 0 until 8) {
                        val x = xByte * 8 + bit
                        if (x < width && isDark(row[x])) {
                            byte = byte or (0x80 ushr bit)
                        }
                    }
                    bos.write(byte)
                }
            }
            top += band
        }

        return bos.toByteArray()
    }

    /** Index of the last row that has a dark pixel, or -1 when the bitmap is blank. */
    fun lastDarkRow(bitmap: Bitmap): Int {
        val width = bitmap.width
        val row = IntArray(width)
        for (y in bitmap.height - 1 downTo 0) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            if (row.any { isDark(it) }) return y
        }
        return -1
    }

    private fun isDark(pixel: Int): Boolean {
        if (Color.alpha(pixel) < 128) return false
        val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
        return luminance < 128
    }
}
