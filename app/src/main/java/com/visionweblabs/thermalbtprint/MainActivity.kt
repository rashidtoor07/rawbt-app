package com.visionweblabs.thermalbtprint

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.visionweblabs.thermalbtprint.bluetooth.BluetoothDiscoveryManager
import com.visionweblabs.thermalbtprint.bluetooth.PrinterManager
import com.visionweblabs.thermalbtprint.services.PrinterService
import com.visionweblabs.thermalbtprint.utils.EscPosBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            updateStatus()
            if (checkPermissions()) {
                updateDeviceList()
            }
        } else {
            Toast.makeText(this, "Bluetooth enable cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var bluetoothDiscoveryManager: BluetoothDiscoveryManager
    private lateinit var printerManager: PrinterManager
    
    private lateinit var tvStatus: TextView
    private lateinit var lvDevices: ListView
    private lateinit var btnScan: Button
    private lateinit var btnTestPrint: Button
    private lateinit var btnStopService: Button
    private lateinit var etBarcode: EditText

    private var devicesList: List<BluetoothDevice> = emptyList()
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private var receiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (!discoveredDevices.contains(it) && !devicesList.contains(it)) { // Avoid duplicates
                            discoveredDevices.add(it)
                            updateDeviceList()
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Toast.makeText(context, "Discovery finished", Toast.LENGTH_SHORT).show()
                    // Update UI to indicate discovery finished
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bluetoothDiscoveryManager = BluetoothDiscoveryManager(this)
        printerManager = PrinterManager.getInstance(this)

        bluetoothAdapter = bluetoothDiscoveryManager.bluetoothAdapter ?: run {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Register the BroadcastReceiver
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(bluetoothReceiver, filter)
        receiverRegistered = true

        tvStatus = findViewById(R.id.tvStatus)
        lvDevices = findViewById(R.id.lvDevices)
        btnScan = findViewById(R.id.btnScan)
        btnTestPrint = findViewById(R.id.btnTestPrint)
        btnStopService = findViewById(R.id.btnStopService)
        etBarcode = findViewById(R.id.etBarcode)

        findViewById<Button>(R.id.btnPrintBarcode).setOnClickListener { printCode(qr = false) }
        findViewById<Button>(R.id.btnPrintQr).setOnClickListener { printCode(qr = true) }
        findViewById<Button>(R.id.btnBrowserPrinting).setOnClickListener { openPrintSettings() }

        btnScan.setOnClickListener {
            if (!bluetoothDiscoveryManager.hasBluetoothHardware()) {
                Toast.makeText(this, "Bluetooth hardware not available", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!bluetoothDiscoveryManager.isBluetoothEnabled()) {
                // Check if we have permission to enable Bluetooth
                if (!checkPermissions()) {
                    Toast.makeText(this, "Requesting Bluetooth permissions...", Toast.LENGTH_SHORT).show()
                    requestPermissions()
                    return@setOnClickListener
                }

                val enableIntent = bluetoothDiscoveryManager.getEnableBluetoothIntent()
                if (enableIntent != null) {
                    bluetoothEnableLauncher.launch(enableIntent)
                } else {
                    Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            if (checkPermissions()) {
                // Clear previous lists and start discovery
                devicesList = emptyList()
                discoveredDevices.clear()
                updateDeviceList() // Clear the listview

                bluetoothDiscoveryManager.stopDiscovery()
                bluetoothDiscoveryManager.startDiscovery()
                Toast.makeText(this, "Scanning for devices...", Toast.LENGTH_SHORT).show()
            } else {
                requestPermissions()
            }
        }

        btnTestPrint.setOnClickListener {
            testPrint()
        }

        btnStopService.setOnClickListener {
            stopPrinterService()
        }

        lvDevices.setOnItemClickListener { _, _, position, _ ->
            val device = devicesList[position]
            printerManager.savePrinterAddress(device.address)
            updateStatus()
            Toast.makeText(this, "Selected: ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
        }

        applySystemBarInsets(findViewById(R.id.root))

        updateStatus()
        if (checkPermissions()) {
            updateDeviceList()
            startPrinterService()
        } else {
            requestPermissions()
        }
    }

    // Android 15+ draws apps behind the navigation bar; keep the bottom buttons clear of it
    private fun applySystemBarInsets(root: View) {
        val left = root.paddingLeft
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(left + bars.left, view.paddingTop, right + bars.right, bottom + bars.bottom)
            insets
        }
    }

    // A connectedDevice foreground service may only start once Bluetooth permission is granted
    private fun startPrinterService() {
        val serviceIntent = Intent(this, PrinterService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun updateStatus() {
        val address = printerManager.getSavedPrinterAddress()
        if (address != null) {
            tvStatus.text = "Saved Printer: $address"
        } else {
            tvStatus.text = getString(R.string.status_disconnected)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateDeviceList() {
        val pairedDevices = bluetoothDiscoveryManager.getPairedDevices()
        val allDevices = (pairedDevices + discoveredDevices).distinctBy { it.address }
        
        devicesList = allDevices

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            devicesList.map { "${it.name ?: "Unknown"}\n${it.address}" }
        )
        lvDevices.adapter = adapter
    }

    private fun testPrint() {
        val data = EscPosBuilder()
            .init()
            .align(EscPosBuilder.ALIGN_CENTER)
            .fontSize(EscPosBuilder.FONT_SIZE_NORMAL)
            .bold(true)
            .text("BT PRINT TEST")
            .lineBreak()
            .bold(false)
            .fontSize(EscPosBuilder.FONT_SIZE_NORMAL)
            .text("Testing Printer Connection")
            .lineBreak()
            .align(EscPosBuilder.ALIGN_LEFT)
            .text("--------------------------------")
            .lineBreak()
            .text("Item 1              Rp 10.000")
            .lineBreak()
            .text("Item 2              Rp 20.000")
            .lineBreak()
            .text("--------------------------------")
            .lineBreak()
            .align(EscPosBuilder.ALIGN_RIGHT)
            .bold(true)
            .text("TOTAL: Rp 30.000")
            .lineBreak()
            .lineBreak()
            .align(EscPosBuilder.ALIGN_CENTER)
            .qrCode("BT Print - Test QR Code")
            .branding()
            .feed(2)
            .cut()
            .build()

        sendToPrinter(data)
    }

    private fun printCode(qr: Boolean) {
        val content = etBarcode.text.toString().trim()
        if (content.isEmpty()) {
            Toast.makeText(this, R.string.barcode_empty, Toast.LENGTH_SHORT).show()
            return
        }
        // CODE128 set B only encodes printable ASCII, and long values overflow 58mm paper
        if (!qr && (content.length > EscPosBuilder.MAX_BARCODE_LENGTH || content.any { it.code !in 32..126 })) {
            Toast.makeText(
                this,
                getString(R.string.barcode_invalid, EscPosBuilder.MAX_BARCODE_LENGTH),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val builder = EscPosBuilder().init().align(EscPosBuilder.ALIGN_CENTER)
        if (qr) builder.qrCode(content) else builder.barcode(content)
        sendToPrinter(builder.lineBreak().branding().feed(3).cut().build())
    }

    private fun openPrintSettings() {
        Toast.makeText(this, R.string.browser_printing_help, Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.print_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendToPrinter(data: ByteArray) {
        lifecycleScope.launch {
            printerManager.print(data) { success, error ->
                if (success) {
                    Toast.makeText(this@MainActivity, "Print success", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Print failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopPrinterService() {
        // Clear saved printer address
        printerManager.clearPrinterAddress()
        
        // Stop the service
        val serviceIntent = Intent(this, PrinterService::class.java)
        stopService(serviceIntent)
        
        // Update status
        updateStatus()
        
        Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
    }

    // BLUETOOTH_SCAN is declared neverForLocation, so Android 12+ needs no location permission
    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    private fun checkPermissions(): Boolean {
        return requiredPermissions().all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // grantResults is empty when the dialog is dismissed, so re-check rather than trust all{}
        if (requestCode == 100 && checkPermissions()) {
            updateDeviceList()
            startPrinterService()
        } else {
            Toast.makeText(this, "Permissions required for Bluetooth", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // onCreate may finish() early (no Bluetooth) before the receiver is registered
        if (receiverRegistered) unregisterReceiver(bluetoothReceiver)
        if (::bluetoothDiscoveryManager.isInitialized && checkPermissions()) {
            bluetoothDiscoveryManager.stopDiscovery()
        }
    }
}
