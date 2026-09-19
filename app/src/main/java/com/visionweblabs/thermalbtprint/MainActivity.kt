package com.visionweblabs.thermalbtprint

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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

        updateStatus()
        if (checkPermissions()) {
            updateDeviceList()
        }

        // Start local HTTP server service
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
            .feed(2)
            .cut()
            .build()

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

    private fun checkPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        return permissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        ActivityCompat.requestPermissions(this, permissions, 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            updateDeviceList()
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
