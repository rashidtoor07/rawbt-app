package com.visionweblabs.thermalbtprint.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.visionweblabs.thermalbtprint.bluetooth.PrinterManager
import com.visionweblabs.thermalbtprint.utils.EscPosBuilder
import com.visionweblabs.thermalbtprint.utils.JsonPrintParser
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class PrinterService : Service() {

    companion object {
        private const val MAX_BODY_BYTES = 5 * 1024 * 1024
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var serverSocket: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        startHttpServer()
    }

    private fun startForegroundService() {
        val channelId = "printer_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Printer Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Printer Service Running")
            .setContentText("Listening on http://127.0.0.1:8080")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startHttpServer() {
        serviceScope.launch {
            try {
                // Loopback only: other devices on the network must not be able to print
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 8080))
                }
                serverSocket = server
                Log.d("PrinterService", "HTTP Server started on port 8080")
                while (isActive) {
                    val clientSocket = server.accept()
                    launch { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                Log.e("PrinterService", "Server error", e)
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            // A client that stops sending must not hold the connection forever
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.inputStream)

            val method = readLine(input)?.substringBefore(' ') ?: return@withContext
            var contentLength = 0
            // Parse HTTP headers
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                }
            }

            // Browsers send a CORS preflight before a cross-origin JSON POST
            if (method == "OPTIONS") {
                respondPreflight(socket)
                return@withContext
            }
            require(contentLength in 0..MAX_BODY_BYTES) { "Invalid Content-Length" }

            // Content-Length counts bytes, so read bytes and decode afterwards
            val body = ByteArray(contentLength)
            var bytesRead = 0
            while (bytesRead < contentLength) {
                val read = input.read(body, bytesRead, contentLength - bytesRead)
                if (read == -1) break
                bytesRead += read
            }
            val jsonBody = String(body, 0, bytesRead, Charsets.UTF_8)

            val error = if (jsonBody.isNotBlank()) printFromJson(jsonBody) else null
            if (error == null) {
                respond(socket, "200 OK", JSONObject().put("status", "success"))
            } else {
                respond(socket, "500 Internal Server Error",
                    JSONObject().put("status", "error").put("message", error))
            }
        } catch (e: Exception) {
            Log.e("PrinterService", "Error handling client", e)
            try {
                respond(socket, "400 Bad Request",
                    JSONObject().put("status", "error").put("message", e.message ?: e.toString()))
            } catch (e2: Exception) {
                Log.e("PrinterService", "Error sending error response", e2)
            }
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e("PrinterService", "Error closing socket", e)
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
        }
    }

    private fun respond(socket: Socket, status: String, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.outputStream.apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun respondPreflight(socket: Socket) {
        val header = "HTTP/1.1 204 No Content\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n" +
            "Access-Control-Allow-Private-Network: true\r\n" +
            "Access-Control-Max-Age: 600\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        socket.outputStream.apply {
            write(header.toByteArray(Charsets.US_ASCII))
            flush()
        }
    }

    /** Returns null on success, otherwise the error message. */
    private suspend fun printFromJson(json: String): String? {
        val builder = EscPosBuilder().init()
        JsonPrintParser.parseAndBuild(json, builder)
        val printData = builder.branding().feed(3).cut().build()
        Log.d("PrinterService", "Print data size: ${printData.size} bytes")

        var error: String? = null
        PrinterManager.getInstance(this).print(printData) { success, err ->
            error = if (success) null else err ?: "Print failed"
        }
        if (error != null) Log.e("PrinterService", "Print failed: $error")
        return error
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        serverSocket?.close()
    }
}
