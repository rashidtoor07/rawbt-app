package com.visionweblabs.thermalbtprint.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PrinterManager private constructor(private val context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("PrinterPrefs", Context.MODE_PRIVATE)

    private var connection: PrinterConnection? = null
    // Serializes jobs from HTTP, broadcast and UI so receipts never interleave
    private val printMutex = Mutex()
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: PrinterManager? = null

        fun getInstance(context: Context): PrinterManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrinterManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun savePrinterAddress(address: String) {
        sharedPreferences.edit().putString("last_printer_address", address).apply()
    }

    fun getSavedPrinterAddress(): String? {
        return sharedPreferences.getString("last_printer_address", null)
    }

    fun clearPrinterAddress() {
        sharedPreferences.edit().remove("last_printer_address").apply()
    }

    @SuppressLint("MissingPermission")
    suspend fun print(data: ByteArray, callback: (Boolean, String?) -> Unit) {
        val error = printMutex.withLock { printLocked(data) }
        callback(error == null, error)
    }

    @SuppressLint("MissingPermission")
    private suspend fun printLocked(data: ByteArray): String? {
        val address = getSavedPrinterAddress() ?: return "No printer selected"

        val device: BluetoothDevice = try {
            bluetoothAdapter?.getRemoteDevice(address)
        } catch (e: Exception) {
            null
        } ?: return "Printer device not found"

        if (bluetoothAdapter?.isEnabled != true) return "Bluetooth is off"

        // A cached socket can look connected after the printer was power-cycled,
        // so on a failed write reconnect once and retry.
        repeat(2) { attempt ->
            val conn0 = connection
            // Also reconnect when the user picked a different printer since the last job
            if (attempt > 0 || conn0?.isConnected() != true || conn0.device.address != address) {
                closeConnection()
                // Ongoing discovery makes RFCOMM connect slow or fail
                try { bluetoothAdapter?.cancelDiscovery() } catch (_: SecurityException) {}
                val conn = PrinterConnection(device)
                if (!conn.connect()) return "Failed to connect to printer"
                connection = conn
            }
            if (connection?.sendData(data) == true) return null
        }
        closeConnection()
        return "Failed to send data"
    }

    fun closeConnection() {
        connection?.close()
        connection = null
    }
}
