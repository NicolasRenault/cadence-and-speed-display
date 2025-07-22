package com.example.cadenceandspeeddisplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult // Explicit import for ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.UUID
import com.google.android.gms.location.*


class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val foundDevices = mutableMapOf<String, BluetoothDevice>()
    private val REQUEST_PERMISSION_ALL = 1 // Combined request code
    private val SCAN_PERIOD: Long = 10000 // 10 seconds scan period

    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var autoConnecting = false // Flag to manage auto-connection to target device

    private var bluetoothGatt: BluetoothGatt? = null

    private lateinit var btnSelectDevice: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvCadence: TextView
    private lateinit var tvSpeed: TextView

    // Cadence calculation variables
    private var previousCrankRevolutions: Int = -1
    private var previousCrankEventTime: Int = -1
    private var currentCadence: Double = 0.0

    // GPS and Speed related variables
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val cadenceTimeoutHandler = Handler(Looper.getMainLooper())
    private var cadenceTimeoutRunnable: Runnable? = null
    private val CADENCE_STALE_DATA_TIMEOUT_MS = 3000L

    private val TARGET_DEVICE_ADDRESS = "C2:23:F0:E9:B0:EE"

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSelectDevice = findViewById(R.id.btnSelectDevice)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvCadence = findViewById(R.id.tvCadence)
        tvSpeed = findViewById(R.id.tvSpeed)

        tvCadence.text = "--"
        tvSpeed.text = "--"

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        bluetoothAdapter = bluetoothManager?.adapter ?: run {
            Toast.makeText(this, "BluetoothAdapter not available", Toast.LENGTH_LONG).show()
            Log.e(TAG, "BluetoothAdapter not available, cannot initialize BLE features.")
            finish()
            return
        }
        // Initialize bluetoothLeScanner only if adapter is valid
        if (bluetoothAdapter.isEnabled) { // Check if BT is on before getting scanner
            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        }


        requestPermissionsIfNeeded()

        btnSelectDevice.setOnClickListener {
            if (bluetoothGatt != null && bluetoothGatt?.device != null) {
                Toast.makeText(this, "Already connected. Disconnect first to scan.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!bluetoothAdapter.isEnabled) {
                Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Ensure scanner is available
            if (bluetoothLeScanner == null) {
                bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
                if (bluetoothLeScanner == null) {
                    Toast.makeText(this, "Bluetooth LE Scanner could not be initialized.", Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "Bluetooth LE Scanner is null even after trying to re-initialize.")
                    return@setOnClickListener
                }
            }
            btnSelectDevice.text = "Searching..."
            startBleScan(true) // Show dialog if target not found after scan
        }

        btnDisconnect.setOnClickListener {
            disconnectDevice()
        }

        //Speed
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2000L // interval
        ).apply {
            setMinUpdateIntervalMillis(1000L) // fastest interval
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    if (location.hasSpeed()) {
                        val speedFloat = location.speed * 3.6f
                        val speed = speedFloat.toInt()

                        Log.d(TAG, "Speed (Float): $speedFloat km/h, Speed (Int): $speed km/h")
                        tvSpeed.text = "$speed"
                    } else {
                        Log.d(TAG, "Location has no speed.")
                        tvSpeed.text = "N/A" // Or keep "GPS Wait" if it's still waiting for a fix with speed
                    }
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsNeeded = mutableListOf<String>()

        // Bluetooth Permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else { // Pre-Android 12
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            // ACCESS_FINE_LOCATION is also needed for BLE scan on these older versions
        }

        // Location Permission (needed for BLE scan pre-S AND for GPS speed)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // If ACCESS_COARSE_LOCATION is also desired for some reason, and not implied by FINE:
        // if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        //     permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        // }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), REQUEST_PERMISSION_ALL)
        }
    }

    /**
     * Checks for primary BLE operational permissions.
     * For pre-S, this includes ACCESS_FINE_LOCATION for scanning.
     * For S+, it's BLUETOOTH_SCAN and BLUETOOTH_CONNECT.
     */
    private fun hasRequiredBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        } else {
            (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        }
    }

    /**
     * Specifically checks for ACCESS_FINE_LOCATION permission, needed for GPS.
     */
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan(showDialogOnTimeout: Boolean = false) {
        if (!hasRequiredBlePermissions()) {
            requestPermissionsIfNeeded() // Ask for permissions if not already granted
            Toast.makeText(this, "Required Bluetooth/Location permissions not granted for scan", Toast.LENGTH_SHORT).show()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth is not enabled", Toast.LENGTH_SHORT).show()
            return
        }
        if (scanning) {
            Log.d(TAG, "Scan already in progress.")
            return
        }
        if (bluetoothGatt != null && bluetoothGatt?.device != null) {
            Toast.makeText(this, "A device is already connected. Disconnect first.", Toast.LENGTH_SHORT).show()
            return
        }

        // Ensure bluetoothLeScanner is initialized
        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "startBleScan: BluetoothLeScanner is null and could not be re-initialized.")
                Toast.makeText(this, "Bluetooth LE Scanner not available", Toast.LENGTH_SHORT).show()
                return
            }
        }

        foundDevices.clear()
        scanning = true
        autoConnecting = false
        Log.d(TAG, "Starting BLE scan (auto-connect for '$TARGET_DEVICE_ADDRESS')...")
        Toast.makeText(this, "Scanning for devices...", Toast.LENGTH_SHORT).show()

        handler.removeCallbacksAndMessages(null) // Clear previous scan timeouts
        handler.postDelayed({
            if (scanning && !autoConnecting) {
                stopBleScan()
                Log.d(TAG, "Scan stopped by timeout.")
                if (showDialogOnTimeout && foundDevices.isNotEmpty()) {
                    showDeviceSelectionDialog()
                } else if (showDialogOnTimeout) {
                    Toast.makeText(this, "No BLE devices found after scan.", Toast.LENGTH_LONG).show()
                } else if (!autoConnecting){ // Auto-scan without dialog, target not found
                    Toast.makeText(this, "$TARGET_DEVICE_ADDRESS not found.", Toast.LENGTH_LONG).show()
                }
            }
        }, SCAN_PERIOD)

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bluetoothLeScanner?.startScan(null, scanSettings, bleScanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on startScan: ${e.message}")
            Toast.makeText(this, "Permission issue starting scan.", Toast.LENGTH_SHORT).show()
            scanning = false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException on startScan (Bluetooth off?): ${e.message}")
            Toast.makeText(this, "Bluetooth might be off. Cannot start scan.", Toast.LENGTH_SHORT).show()
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (scanning) {
            // Permission check for stopping scan on S+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_SCAN permission not granted, cannot reliably stop scan on S+ via API.")
                // Update internal state even if API call might fail or be skipped
                scanning = false
                handler.removeCallbacksAndMessages(null) // Still clear any pending timeout
                return
            }
            scanning = false
            try {
                bluetoothLeScanner?.stopScan(bleScanCallback)
                Log.d(TAG, "BLE Scan stopped.")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException on stopScan: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException on stopScan (Bluetooth off?): ${e.message}")
            }
            handler.removeCallbacksAndMessages(null) // Clear timeout when scan is stopped
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) { // Use explicit import ScanResult
            result?.device?.let { device ->
                // Check for BLUETOOTH_CONNECT before accessing device.name on S+
                val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        device.name ?: "Unnamed Device"
                    } else {
                        "Name N/A (Permission)"
                    }
                } else { // Pre-S, name access was less restricted but could still be null
                    device.name ?: "Unnamed Device"
                }
                processFoundDevice(device, deviceName)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>?) { // Use explicit import ScanResult
            results?.forEach { result ->
                result.device?.let { device ->
                    val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            device.name ?: "Unnamed Device"
                        } else {
                            "Name N/A (Permission)"
                        }
                    } else {
                        device.name ?: "Unnamed Device"
                    }
                    processFoundDevice(device, deviceName)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            val errorMessage = when (errorCode) {
                else -> "Unknown scan error: $errorCode"
            }
            Toast.makeText(this@MainActivity, "Scan failed: $errorMessage", Toast.LENGTH_LONG).show()
            scanning = false
            autoConnecting = false
        }
    }

    private fun processFoundDevice(device: BluetoothDevice, deviceName: String) {
        val deviceAddress = device.address
        if (!foundDevices.containsKey(deviceAddress)) {
            Log.d(TAG, "Device found: $deviceName ($deviceAddress)")
            foundDevices[deviceAddress] = device
        }

        if (!autoConnecting && deviceAddress.equals(TARGET_DEVICE_ADDRESS, ignoreCase = true)) {
            Log.i(TAG, "Target device '$TARGET_DEVICE_ADDRESS' ($deviceName) found! Attempting to connect.")
            Toast.makeText(this@MainActivity, "Found $TARGET_DEVICE_ADDRESS. Connecting...", Toast.LENGTH_SHORT).show()
            autoConnecting = true
            stopBleScan()
            connectToDevice(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        if (foundDevices.isEmpty()) {
            Toast.makeText(this, "No BLE devices found to select.", Toast.LENGTH_SHORT).show()
            return
        }

        val displayItems = foundDevices.values.map { device ->
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    device.name ?: "Unnamed"
                } else {
                    "Name N/A"
                }
            } else {
                device.name ?: "Unnamed"
            }
            if (name == "Name N/A" || name == "Unnamed") "${device.address} (Unnamed)" else "$name (${device.address})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select BLE Device")
            .setItems(displayItems) { _, which ->
                val selectedDevice = foundDevices.values.elementAtOrNull(which)
                selectedDevice?.let { connectToDevice(it) }
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth Connect permission not granted.", Toast.LENGTH_SHORT).show()
            requestPermissionsIfNeeded()
            return
        }

        if (bluetoothGatt != null) { // Close any existing connection first
            Log.d(TAG, "Existing GATT connection found. Closing it before new connection to ${device.address}.")
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        val deviceDisplayName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            device.name ?: device.address
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            device.name ?: device.address
        } else {
            device.address // Fallback if name cannot be accessed due to permission
        }

        Log.d(TAG, "Attempting to connect to device: $deviceDisplayName (${device.address})")
        Toast.makeText(this, "Connecting to $deviceDisplayName...", Toast.LENGTH_SHORT).show()

        // Reset calculation variables for a new connection
        previousCrankRevolutions = -1
        previousCrankEventTime = -1
        currentCadence = 0.0
        runOnUiThread {
            tvCadence.text = "--"
        }

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                gatt.device.name ?: deviceAddress
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                gatt.device.name ?: deviceAddress
            } else {
                deviceAddress // Fallback
            }


            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server at $deviceName ($deviceAddress).")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Connected to $deviceName", Toast.LENGTH_SHORT).show()
                    btnDisconnect.text = "Disconnect from : ${gatt.device.name}"
                    btnDisconnect.visibility = View.VISIBLE
                    btnSelectDevice.isEnabled = false
                    btnSelectDevice.visibility = View.GONE

                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    Log.d(TAG, "FLAG_KEEP_SCREEN_ON added.")
                }
                autoConnecting = false

                // Discover services after successful connection
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "No BLUETOOTH_CONNECT permission to discover services for $deviceAddress.")
                    // Consider disconnecting or informing user if service discovery is critical
                    return
                }
                Log.i(TAG, "Attempting to start service discovery: ${bluetoothGatt?.discoverServices()}")

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server at $deviceName ($deviceAddress). Status: $status")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Disconnected from $deviceName", Toast.LENGTH_SHORT).show()
                    btnDisconnect.visibility = View.GONE
                    btnSelectDevice.isEnabled = true
                    btnSelectDevice.visibility = View.VISIBLE
                    btnSelectDevice.text = "Start"
                    tvCadence.text = "--"
                }
                autoConnecting = false
                try {
                    // Check permission before gatt.close() on S+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "No BLUETOOTH_CONNECT permission to close GATT on S+ for $deviceAddress.")
                    } else {
                        gatt.close()
                    }
                } catch (e: SecurityException) { // Should be caught by permission check above
                    Log.e(TAG, "SecurityException on gatt.close(): ${e.message}")
                }
                bluetoothGatt = null // Nullify the GATT object

                // Reset calculation variables on disconnect
                previousCrankRevolutions = -1
                previousCrankEventTime = -1
                currentCadence = 0.0
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // Permission check for operations within this callback on S+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "No BLUETOOTH_CONNECT permission during onServicesDiscovered for ${gatt.device.address}.")
                return // Cannot proceed without connect permission
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered successfully for ${gatt.device.address}.")
                val service = gatt.getService(YOUR_SERVICE_UUID)
                if (service == null) {
                    Log.w(TAG, "Service UUID not found: $YOUR_SERVICE_UUID on ${gatt.device.address}")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Target service not found", Toast.LENGTH_SHORT).show() }
                    return
                }

                val characteristic = service.getCharacteristic(YOUR_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    Log.w(TAG, "Characteristic UUID not found: $YOUR_CHARACTERISTIC_UUID in service $YOUR_SERVICE_UUID on ${gatt.device.address}")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Target characteristic not found", Toast.LENGTH_SHORT).show() }
                    return
                }

                val properties = characteristic.properties
                if ((properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0 ||
                    (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0) {
                    if (gatt.setCharacteristicNotification(characteristic, true)) {
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                        descriptor?.let {
                            if ((properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
                                    gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                } else {
                                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    gatt.writeDescriptor(it)
                                }
                                Log.d(TAG, "Enabling NOTIFICATION for ${characteristic.uuid}")
                            } else { // Must be PROPERTY_INDICATE
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
                                    gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                                } else {
                                    it.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                    gatt.writeDescriptor(it)
                                }
                                Log.d(TAG, "Enabling INDICATION for ${characteristic.uuid}")
                            }
                            // writeDescriptor call is now conditional based on API level above
                        } ?: run {
                            Log.w(TAG, "CCC Descriptor not found for characteristic: ${characteristic.uuid}")
                        }
                    } else {
                        Log.w(TAG, "Failed to set characteristic notification for ${characteristic.uuid}")
                    }
                } else {
                    Log.w(TAG, "Characteristic ${characteristic.uuid} does not support NOTIFY or INDICATE")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Characteristic cannot be notified", Toast.LENGTH_SHORT).show() }
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received error status: $status for ${gatt.device.address}")
            }
        }

        // Inside your gattCallback object
        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)

            if (descriptor.characteristic.uuid == YOUR_CHARACTERISTIC_UUID &&
                descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Notifications successfully enabled for CSC characteristic.")

                    // --- START Initial Cadence Timeout ---
                    // At this point, we expect data to start flowing.
                    // If no data arrives within the timeout period, set cadence to 0.
                    // Cancel any pre-existing one just in case (though unlikely here).
                    cadenceTimeoutRunnable?.let { cadenceTimeoutHandler.removeCallbacks(it) }

                    cadenceTimeoutRunnable = Runnable {
                        Log.i(TAG, "Initial cadence data did not arrive within ${CADENCE_STALE_DATA_TIMEOUT_MS}ms. Setting cadence to 0 RPM.")
                        currentCadence = 0.0
                        runOnUiThread {
                            tvCadence.text = "0" // Or "Cadence: -- RPM" until first valid data
                        }
                    }
                    cadenceTimeoutHandler.postDelayed(cadenceTimeoutRunnable!!, CADENCE_STALE_DATA_TIMEOUT_MS)
                    Log.d(TAG, "Initial cadence timeout scheduled.")
                    // --- END Initial Cadence Timeout ---

                } else {
                    Log.w(TAG, "Failed to enable notifications for CSC characteristic. Status: $status")
                    // Handle error: Maybe disconnect, retry, or inform user
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == YOUR_CHARACTERISTIC_UUID) {
                val data = characteristic.value

                var processedNewDistinctCrankData = false // Flag to track if we got usable crank data in *this* packet

                if (data.isNotEmpty()) {
                    val flags = data[0].toInt()
                    var byteOffset = 1

                    // Skip Wheel Revolution Data if present (doesn't affect processedNewDistinctCrankData for cadence)
                    if ((flags and 0b01) != 0) { // Wheel data present
                        if (data.size >= byteOffset + 6) {
                            byteOffset += 6
                        } else {
                            Log.w(TAG, "Flags indicate Wheel Data, but packet too short.")
                        }
                    }

                    // Process Crank Revolution Data if present
                    if ((flags and 0b10) != 0) { // Crank data present
                        if (data.size >= byteOffset + 4) {
                            val currentCrankRevolutionsData = (data[byteOffset + 1].toInt() and 0xFF shl 8) or
                                    (data[byteOffset].toInt() and 0xFF)
                            // byteOffset += 2 // Not needed if only reading these two from crank data
                            val currentCrankEventTimeData = (data[byteOffset + 2 +1].toInt() and 0xFF shl 8) or // Corrected offset
                                    (data[byteOffset+2].toInt() and 0xFF) // Corrected offset

                            var crankRevolutionDelta = 0
                            var crankTimeDelta = 0

                            if (previousCrankRevolutions != -1 && previousCrankEventTime != -1) {
                                crankRevolutionDelta = currentCrankRevolutionsData - previousCrankRevolutions
                                crankTimeDelta = currentCrankEventTimeData - previousCrankEventTime

                                if (crankRevolutionDelta < 0) { crankRevolutionDelta += 65536 }
                                if (crankTimeDelta < 0) { crankTimeDelta += 65536 }

                                if (crankTimeDelta > 0 && crankRevolutionDelta > 0) {
                                    val timeDeltaSeconds = crankTimeDelta / 1024.0
                                    currentCadence = (crankRevolutionDelta / timeDeltaSeconds) * 60.0
                                    Log.d(TAG, "Cadence updated: ${currentCadence} RPM")
                                    processedNewDistinctCrankData = true // Valid new data processed
                                } else if (crankTimeDelta > 0 && crankRevolutionDelta == 0) {
                                    // Time progressed, no new revolutions. Cadence doesn't change here,
                                    // but sensor is active. If this is different from previous, it's "new".
                                    Log.d(TAG, "Crank time progressed, no new revolutions. Last cadence: ${currentCadence} RPM.")
                                    // We consider this "new" data if the event time changed, indicating sensor activity.
                                    if (currentCrankEventTimeData != previousCrankEventTime) {
                                        processedNewDistinctCrankData = true
                                    }
                                } else if (crankTimeDelta == 0 && crankRevolutionDelta > 0) {
                                    Log.w(TAG, "Revolutions changed but time did not. Cadence not updated.")
                                }
                            }

                            // Update previous values ONLY if new distinct data was received and processed
                            if (currentCrankRevolutionsData != previousCrankRevolutions || currentCrankEventTimeData != previousCrankEventTime) {
                                previousCrankRevolutions = currentCrankRevolutionsData
                                previousCrankEventTime = currentCrankEventTimeData
                                // If cadence wasn't updated above due to (crankTimeDelta > 0 && crankRevolutionDelta > 0) not being met,
                                // but the raw values changed, we still mark it as processedNewDistinctCrankData.
                                processedNewDistinctCrankData = true
                            }
                        } else {
                            Log.w(TAG, "Data too short for Crank Revolution fields.")
                        }
                    } else {
                        Log.d(TAG, "Crank Revolution Data not present in this packet.")
                    }

                    if (processedNewDistinctCrankData) {
                        runOnUiThread {
                            tvCadence.text = "${currentCadence.toInt()}"
                        }

                        cadenceTimeoutRunnable?.let { runnable ->
                            cadenceTimeoutHandler.removeCallbacks(runnable)
                        }

                        cadenceTimeoutRunnable = Runnable {
                            Log.i(TAG, "Cadence timed out after ${CADENCE_STALE_DATA_TIMEOUT_MS}ms (no new *distinct* crank data). Setting cadence to 0 RPM.")
                            currentCadence = 0.0
                            runOnUiThread {
                                tvCadence.text = "0"
                            }
                        }

                        cadenceTimeoutHandler.postDelayed(cadenceTimeoutRunnable!!, CADENCE_STALE_DATA_TIMEOUT_MS)


                        // If new distinct crank data was processed, the fact that we cancelled the timeout
                        // at the start of this method and will re-schedule it below effectively "resets" it
                        // for another 3 seconds *from this point of valid data*.
                    } else {
                        // This 'else' block executes if the current packet, despite arriving,
                        // did NOT contain new *distinct and usable crank data* to calculate/update cadence.
                        // The timeout scheduled below will eventually fire if this state persists.
                        Log.d(TAG, "Packet received but no new/valid CSC crank data processed. Cadence timeout continues.")
                    }

                } else { // data is empty
                    Log.w(TAG, "Received empty data packet for characteristic ${characteristic.uuid}")
                    // No new distinct crank data here either. Timeout continues.
                }
                //cadenceTimeoutHandler.postDelayed(cadenceTimeoutRunnable!!, CADENCE_STALE_DATA_TIMEOUT_MS)
                // --- End of Cadence Timeout Management: Part 2 ---
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.d(TAG, "onDestroy called.")
        if ((window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d(TAG, "FLAG_KEEP_SCREEN_ON cleared in onDestroy.")
        }

        stopBleScan()

        // Check for BLUETOOTH_CONNECT before gatt operations on S+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No BLUETOOTH_CONNECT permission in onDestroy, cannot disconnect/close GATT.")
        } else {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
        Log.d(TAG, "onDestroy: Resources released, GATT nulled.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_ALL) {
            var allBlePermissionsGranted = true
            var fineLocationGranted = false

            grantResults.forEachIndexed { index, result ->
                val permission = permissions[index]
                if (result == PackageManager.PERMISSION_DENIED) {
                    Toast.makeText(this, "${permission.substringAfterLast('.')} denied", Toast.LENGTH_SHORT).show()
                    if (permission == Manifest.permission.BLUETOOTH_SCAN ||
                        permission == Manifest.permission.BLUETOOTH_CONNECT ||
                        (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && (permission == Manifest.permission.BLUETOOTH || permission == Manifest.permission.BLUETOOTH_ADMIN))) {
                        allBlePermissionsGranted = false
                    }
                    if (permission == Manifest.permission.ACCESS_FINE_LOCATION) {
                        // If fine location is denied, BLE scan on pre-S might also fail.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) allBlePermissionsGranted = false
                    }
                } else { // Permission Granted
                    if (permission == Manifest.permission.ACCESS_FINE_LOCATION) {
                        fineLocationGranted = true
                    }
                }
            }

            if (allBlePermissionsGranted && fineLocationGranted) {
                Toast.makeText(this, "All required permissions granted.", Toast.LENGTH_SHORT).show()
                // You might want to auto-initiate scan or connection here if it was pending permissions
            } else if (allBlePermissionsGranted && !fineLocationGranted) {
                Toast.makeText(this, "Bluetooth permissions granted, but Location for GPS speed denied.", Toast.LENGTH_LONG).show()
            } else if (!allBlePermissionsGranted && fineLocationGranted) {
                Toast.makeText(this, "Location for GPS speed granted, but Bluetooth permissions denied.", Toast.LENGTH_LONG).show()
            }
            else {
                Toast.makeText(this, "Some critical permissions were denied. App functionality will be limited.", Toast.LENGTH_LONG).show()
                // Guide user to settings or explain consequences
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectDevice() {
        if (bluetoothGatt == null) {
            Log.d(TAG, "disconnectDevice: Not connected to any device, cannot disconnect.")
            Toast.makeText(this, "Not connected.", Toast.LENGTH_SHORT).show()
            return
        }

        // Check for BLUETOOTH_CONNECT permission on Android S (API 31) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_CONNECT permission not granted. Cannot disconnect GATT.")
                Toast.makeText(this, "Bluetooth Connect permission denied. Cannot disconnect.", Toast.LENGTH_LONG).show()
                // Optionally, request the permission again or guide the user
                // requestPermissionsIfNeeded()
                return
            }
        }
        // For pre-S versions, BLUETOOTH and BLUETOOTH_ADMIN would have been required for connection,
        // and are implicitly needed for disconnect.

        Log.d(TAG, "Attempting to disconnect from ${bluetoothGatt?.device?.address}")
        bluetoothGatt?.disconnect()
        // The actual gatt.close() and nullifying bluetoothGatt will be handled in
        // the onConnectionStateChange callback when newState is STATE_DISCONNECTED.
    }


    companion object {
        // Cycling Speed and Cadence Service UUID
        val YOUR_SERVICE_UUID: UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
        // CSC Measurement Characteristic UUID
        val YOUR_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A5B-0000-1000-8000-00805f9b34fb")
        // Client Characteristic Configuration Descriptor UUID (for enabling notifications/indications)
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TAG = "MainActivityBLE"
    }
}
