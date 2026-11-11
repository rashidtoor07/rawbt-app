package com.visionweblabs.thermalbtprint.printservice

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import com.visionweblabs.thermalbtprint.R
import com.visionweblabs.thermalbtprint.bluetooth.PrinterManager
import com.visionweblabs.thermalbtprint.utils.EscPosBuilder
import com.visionweblabs.thermalbtprint.utils.ImageConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Exposes the selected Bluetooth printer to Android's print framework, so Chrome and
 * other apps can print to it from their Print / Share menu.
 */
class BtPrintService : PrintService() {

    companion object {
        private const val TAG = "BtPrintService"
        private const val PRINTER_LOCAL_ID = "btprint"

        // Page height only controls pagination; trailing blank space is trimmed before printing
        private val MEDIA_58MM = PrintAttributes.MediaSize("BTPRINT_58MM", "58mm roll", 2283, 11693)
        private val MEDIA_80MM = PrintAttributes.MediaSize("BTPRINT_80MM", "80mm roll", 3150, 11693)

        private const val DOTS_58MM = 384
        private const val DOTS_80MM = 576
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = DiscoverySession()

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val data = printJob.document.data
        if (data == null) {
            printJob.fail(getString(R.string.print_no_document))
            return
        }
        val dots = if (printJob.info.attributes.mediaSize?.id == MEDIA_80MM.id) DOTS_80MM else DOTS_58MM
        printJob.start()

        scope.launch {
            val error = try {
                val bytes = withContext(Dispatchers.IO) { renderPdf(data, dots) }
                var printError: String? = null
                PrinterManager.getInstance(this@BtPrintService).print(bytes) { success, err ->
                    printError = if (success) null else err ?: "Print failed"
                }
                printError
            } catch (e: Exception) {
                Log.e(TAG, "Print job failed", e)
                e.message ?: e.toString()
            }

            if (printJob.isCancelled) return@launch
            if (error == null) printJob.complete() else printJob.fail(error)
        }
    }

    private fun renderPdf(data: ParcelFileDescriptor, dots: Int): ByteArray {
        // PdfRenderer needs a seekable file, which the spooler's descriptor is not guaranteed to be
        val file = File.createTempFile("printjob", ".pdf", cacheDir)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }

            val builder = EscPosBuilder().init()
            val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            try {
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val bitmap = try {
                        val height = (page.height.toLong() * dots / page.width).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(dots, height, Bitmap.Config.ARGB_8888).also {
                            // Unpainted PDF areas are transparent and would otherwise print black
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        }
                    } finally {
                        page.close()
                    }

                    val lastRow = ImageConverter.lastDarkRow(bitmap)
                    if (lastRow >= 0) builder.image(bitmap, lastRow + 1)
                    bitmap.recycle()
                }
            } finally {
                renderer.close()
            }

            return builder.branding().feed(3).cut().build()
        } finally {
            file.delete()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private inner class DiscoverySession : PrinterDiscoverySession() {

        override fun onStartPrinterDiscovery(priorityList: List<PrinterId>) {
            val id = generatePrinterId(PRINTER_LOCAL_ID)
            val capabilities = PrinterCapabilitiesInfo.Builder(id)
                .addMediaSize(MEDIA_58MM, true)
                .addMediaSize(MEDIA_80MM, false)
                .addResolution(PrintAttributes.Resolution("R203", "203 dpi", 203, 203), true)
                .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            val printer = PrinterInfo.Builder(id, getString(R.string.app_name), PrinterInfo.STATUS_IDLE)
                .setCapabilities(capabilities)
                .build()
            addPrinters(listOf(printer))
        }

        override fun onStopPrinterDiscovery() {}

        override fun onValidatePrinters(printerIds: List<PrinterId>) {}

        override fun onStartPrinterStateTracking(printerId: PrinterId) {}

        override fun onStopPrinterStateTracking(printerId: PrinterId) {}

        override fun onDestroy() {}
    }
}
