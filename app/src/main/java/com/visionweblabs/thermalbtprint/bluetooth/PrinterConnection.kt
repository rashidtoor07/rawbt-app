package com.visionweblabs.thermalbtprint.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.*

class PrinterConnection(val device: BluetoothDevice) {

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "PrinterConnection"
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket?.connect()
            outputStream = socket?.outputStream
            true
        } catch (e: Exception) {
            // IOException, or SecurityException when BLUETOOTH_CONNECT is revoked
            Log.e(TAG, "Connection failed", e)
            close()
            false
        }
    }

    suspend fun sendData(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val out = outputStream ?: return@withContext false
            out.write(data)
            out.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send data", e)
            false
        }
    }

    fun isConnected(): Boolean {
        return socket?.isConnected == true
    }

    fun close() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing socket", e)
        } finally {
            outputStream = null
            socket = null
        }
    }
}
