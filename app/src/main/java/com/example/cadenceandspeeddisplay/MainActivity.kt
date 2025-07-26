package com.example.cadenceandspeeddisplay // Replace with your actual package name

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// Google Play Services Location
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority // For newer LocationRequest

import java.util.UUID
import kotlin.math.roundToInt

// GATT Service and Characteristic UUIDs (Ensure these are correct for your sensor)
val CYCLING_SPEED_AND_CADENCE_SERVICE_UUID: UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
val CSC_MEASUREMENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")


class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivityCSC"
    private val REQUEST_ENABLE_BT = 1
    private val BLUETOOTH_PERMISSIONS_REQUEST_CODE_S_PLUS = 3 // For Android S+ permissions
    private val LOCATION_PERMISSION_REQUEST_CODE_FOR_SPEED = 4 // New request code for location for speed
    private val REQUEST_CHECK_SETTINGS = 5 // For location settings dialog


    private var TARGET_DEVICE_ADDRESS: String? = "C2:23:F0:E9:B0:EE" // Set this if you want to auto-connect

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null // Still needed for Cadence

    private var scanning = false
    private var autoConnecting = true
    private val foundDevices = mutableMapOf<String, BluetoothDevice>()

    // --- Location Specific Variables ---
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var locationCallback: LocationCallback? = null
    private var requestingLocationUpdates = false
    // --- End Location Specific Variables ---

    // UI Elements
    lateinit var tvSpeedLabel: TextView
    lateinit var tvSpeed: TextView
    lateinit var tvSpeedUnit: TextView
    lateinit var tvAvgSpeedLabel: TextView
    lateinit var tvAvgSpeed: TextView
    lateinit var tvAvgSpeedUnit: TextView
    lateinit var tvMaxSpeedLabel: TextView
    lateinit var tvMaxSpeed: TextView
    lateinit var tvMaxSpeedUnit: TextView

    lateinit var tvCadenceLabel: TextView
    lateinit var tvCadence: TextView
    lateinit var tvCadenceUnit: TextView
    lateinit var tvAvgCadenceLabel: TextView
    lateinit var tvAvgCadence: TextView
    lateinit var tvAvgCadenceUnit: TextView
    lateinit var tvMaxCadenceLabel: TextView
    lateinit var tvMaxCadence: TextView
    lateinit var tvMaxCadenceUnit: TextView

    lateinit var tvTimerLabel: TextView
    lateinit var tvTimerValue: TextView

    lateinit var btnSelectDevice: Button
    lateinit var btnDisconnect: Button

    // Session and Timer Variables
    private var sessionStartTime: Long = 0L
    private var isSessionActive = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private lateinit var timerRunnable: Runnable

    // Metric calculation variables
    private var previousCrankRevolutions: Int = -1
    private var previousCrankEventTime: Int = -1
    private var currentCadenceRpm: Double = 0.0

    private var totalSpeedSumKmh = 0.0
    private var speedReadingsCount = 0
    private var currentMaxSpeedKmh = 0.0

    private var totalCadenceSumRpm = 0.0
    private var cadenceReadingsCount = 0
    private var currentMaxCadenceRpm = 0.0

    private var fusedLocationClient: FusedLocationProviderClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Elements
        tvSpeedLabel = findViewById(R.id.tvSpeedLabel)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvSpeedUnit = findViewById(R.id.tvSpeedUnit)
        tvAvgSpeedLabel = findViewById(R.id.tvAvgSpeedLabel)
        tvAvgSpeed = findViewById(R.id.tvAvgSpeed)
        tvAvgSpeedUnit = findViewById(R.id.tvAvgSpeedUnit)
        tvMaxSpeedLabel = findViewById(R.id.tvMaxSpeedLabel)
        tvMaxSpeed = findViewById(R.id.tvMaxSpeed)
        tvMaxSpeedUnit = findViewById(R.id.tvMaxSpeedUnit)

        tvCadenceLabel = findViewById(R.id.tvCadenceLabel)
        tvCadence = findViewById(R.id.tvCadence)
        tvCadenceUnit = findViewById(R.id.tvCadenceUnit)
        tvAvgCadenceLabel = findViewById(R.id.tvAvgCadenceLabel)
        tvAvgCadence = findViewById(R.id.tvAvgCadence)
        tvAvgCadenceUnit = findViewById(R.id.tvAvgCadenceUnit)
        tvMaxCadenceLabel = findViewById(R.id.tvMaxCadenceLabel)
        tvMaxCadence = findViewById(R.id.tvMaxCadence)
        tvMaxCadenceUnit = findViewById(R.id.tvMaxCadenceUnit)

        tvTimerLabel = findViewById(R.id.tvTimerLabel)
        tvTimerValue = findViewById(R.id.tvTimerValue)

        btnSelectDevice = findViewById(R.id.btnSelectDevice)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        // Initialize Bluetooth
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Initialize Location Services
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createLocationRequest()

        timerRunnable = object : Runnable {
            override fun run() {
                if (isSessionActive) {
                    val millis = System.currentTimeMillis() - sessionStartTime
                    val seconds = (millis / 1000) % 60
                    val minutes = (millis / (1000 * 60)) % 60
                    val hours = (millis / (1000 * 60 * 60)) % 24
                    tvTimerValue.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    timerHandler.postDelayed(this, 1000)
                }
            }
        }
        resetUiToDefault()

        btnSelectDevice.setOnClickListener {
            if (bluetoothGatt != null && isSessionActive) {
                Toast.makeText(this, "Session already active. Stop current session first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                bluetoothEnableResultLauncher.launch(enableBtIntent)
                return@setOnClickListener
            }
            if (bluetoothLeScanner == null) { // Initialize scanner if null
                bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
                if (bluetoothLeScanner == null) {
                    Toast.makeText(this, "Bluetooth LE Scanner not available.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            // Check location permission first, then Bluetooth permissions
            if (checkLocationPermission()) {
                requestBluetoothPermissionsIfNeeded()
            } else {
                requestLocationPermissionForSpeed()
            }
        }

        btnDisconnect.setOnClickListener {
            disconnectDevice() // Handles BT disconnect if connected
            // stopSession() handles UI, timer, and location updates stop
        }
    }

    private val bluetoothEnableResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled.", Toast.LENGTH_SHORT).show()
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            // After enabling BT, if location perm is granted, proceed to BT perm check
            if(checkLocationPermission()){
                requestBluetoothPermissionsIfNeeded()
            } else {
                requestLocationPermissionForSpeed() // Or ask for location again if needed
            }
        } else {
            Toast.makeText(this, "Bluetooth not enabled. App requires Bluetooth for cadence.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissionsToRequest = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (permissionsToRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), BLUETOOTH_PERMISSIONS_REQUEST_CODE_S_PLUS)
            } else {
                startBleScan(true) // All BT Permissions already granted
            }
        } else {
            // For older Android versions, location permission covers BLE scan, which we checked before calling this.
            // If ACCESS_FINE_LOCATION was granted (which it should have been to reach here for older OS), start scan.
            startBleScan(true)
        }
    }

    // --- Location Methods ---
    private fun createLocationRequest() {
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000) // Update every 1 second
            .setMinUpdateIntervalMillis(500) // Minimum interval if available faster
            .build()
    }

    private fun checkLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissionForSpeed() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE_FOR_SPEED
        )
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!checkLocationPermission()) {
            Log.w(TAG, "Attempted to start location updates without permission.")
            // UI should ideally prevent this, or request permission again here.
            Toast.makeText(this, "Location permission needed for speed.", Toast.LENGTH_SHORT).show()
            return
        }

        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            if (locationCallback == null) {
                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        locationResult.lastLocation?.let { location ->
                            if (isSessionActive) {
                                val speedMps = location.speed // Speed in meters/second
                                val speedKmh = speedMps * 3.6
                                runOnUiThread {
                                    tvSpeed.text = String.format("%.1f", speedKmh)
                                    updateSpeedMetrics(speedKmh)
                                }
                                Log.d(TAG, "Location Speed: $speedKmh km/h, Accuracy: ${location.accuracy}")
                            }
                        }
                    }
                }
            }
            fusedLocationClient?.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
            requestingLocationUpdates = true
            Log.d(TAG, "Started location updates.")
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    exception.startResolutionForResult(this@MainActivity, REQUEST_CHECK_SETTINGS)
                } catch (sendEx: IntentSender.SendIntentException) {
                    Log.e(TAG, "Error showing location settings dialog: ${sendEx.message}")
                }
            } else {
                Log.e(TAG, "Location settings are inadequate: ${exception.message}")
                Toast.makeText(this, "Location settings inadequate. GPS Speed unavailable.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopLocationUpdates() {
        if (requestingLocationUpdates && locationCallback != null) {
            fusedLocationClient?.removeLocationUpdates(locationCallback!!)
            requestingLocationUpdates = false
            locationCallback = null // Good practice to nullify to allow recreation
            Log.d(TAG, "Stopped location updates.")
        }
    }
    // --- End Location Methods ---

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CHECK_SETTINGS -> {
                if (resultCode == Activity.RESULT_OK) {
                    Log.i(TAG, "User enabled location settings.")
                    if (isSessionActive && !requestingLocationUpdates && checkLocationPermission()) {
                        startLocationUpdates()
                    }
                } else {
                    Log.w(TAG, "User did not enable location settings. GPS speed unavailable.")
                    Toast.makeText(this, "Location not enabled. GPS speed unavailable.", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_ENABLE_BT -> { //This is if you use the system's BT enable dialog, not ActivityResultLauncher
                if (resultCode == Activity.RESULT_OK) {
                    // Handled by bluetoothEnableResultLauncher now
                } else {
                    Toast.makeText(this, "Bluetooth not enabled.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE_FOR_SPEED -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Location permission granted for speed.")
                    // Now that location permission is granted, proceed with BT permission check
                    requestBluetoothPermissionsIfNeeded()
                    // If a session was intended to start and was waiting
                    if (isSessionActive && !requestingLocationUpdates) {
                        startLocationUpdates() // Attempt to start location updates if session is active
                    }
                } else {
                    Toast.makeText(this, "Location permission denied. GPS Speed will not be available.", Toast.LENGTH_LONG).show()
                    // Optionally, still proceed to BT permissions if user might want cadence only
                    requestBluetoothPermissionsIfNeeded()
                }
            }
            BLUETOOTH_PERMISSIONS_REQUEST_CODE_S_PLUS -> {
                var allGranted = true
                grantResults.forEach { if (it != PackageManager.PERMISSION_GRANTED) allGranted = false }
                if (allGranted && permissions.isNotEmpty()) {
                    startBleScan(true)
                } else {
                    Toast.makeText(this, "Bluetooth permissions denied. Cadence sensor cannot be used.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan(enable: Boolean) {
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothLeScanner not initialized before starting scan.")
            Toast.makeText(this, "BLE Scanner not ready.", Toast.LENGTH_SHORT).show()
            btnSelectDevice.text = "Start Session"
            btnSelectDevice.isEnabled = true
            return
        }

        if (enable) {
            if (scanning) return
            foundDevices.clear()
            scanning = true
            autoConnecting = false
            btnSelectDevice.text = "Searching..."
            btnSelectDevice.isEnabled = false

            val scanFilters = mutableListOf<ScanFilter>()
            val cscServiceFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(CYCLING_SPEED_AND_CADENCE_SERVICE_UUID))
                .build()
            scanFilters.add(cscServiceFilter)

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            Handler(Looper.getMainLooper()).postDelayed({
                if (scanning) {
                    stopBleScan()
                    if (foundDevices.isEmpty()) {
                        Toast.makeText(this, "No CSC devices found for cadence.", Toast.LENGTH_SHORT).show()
                    } else if (!autoConnecting) {
                        showDeviceSelectionDialog()
                    }
                }
            }, 10000) // Scan for 10 seconds

            bluetoothLeScanner?.startScan(scanFilters, scanSettings, bleScanCallback)
            Log.d(TAG, "BLE Scan Started for cadence sensor.")
        } else {
            stopBleScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (scanning) {
            bluetoothLeScanner?.stopScan(bleScanCallback)
            scanning = false
            Log.d(TAG, "BLE Scan Stopped.")
            if (!autoConnecting && (bluetoothGatt == null || !isSessionActive)) {
                btnSelectDevice.text = "Start Session"
                btnSelectDevice.isEnabled = true
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device -> processFoundDevice(device) }
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { result -> result.device?.let { device -> processFoundDevice(device) } }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started."
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed."
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error."
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported."
                else -> "Unknown scan error: $errorCode"
            }
            Toast.makeText(this@MainActivity, "Scan failed: $errorMessage", Toast.LENGTH_LONG).show()
            scanning = false
            autoConnecting = false
            runOnUiThread {
                btnSelectDevice.text = "Start Session"
                btnSelectDevice.isEnabled = true
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun processFoundDevice(device: BluetoothDevice) {
        val deviceAddress = device.address
        val deviceName = device.name
        if (!foundDevices.containsKey(deviceAddress)) {
            Log.d(TAG, "Device found for cadence: $deviceName ($deviceAddress)")
            foundDevices[deviceAddress] = device
        }

        if (TARGET_DEVICE_ADDRESS != null && !autoConnecting && deviceAddress.equals(TARGET_DEVICE_ADDRESS, ignoreCase = true)) {
            Log.i(TAG, "Target cadence device '$TARGET_DEVICE_ADDRESS' ($deviceName) found! Attempting to connect.")
            Toast.makeText(this, "Found $TARGET_DEVICE_ADDRESS. Connecting for cadence...", Toast.LENGTH_SHORT).show()
            autoConnecting = true
            stopBleScan()
            connectToDevice(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        if (foundDevices.isEmpty()) {
            Toast.makeText(this, "No BLE devices found for cadence.", Toast.LENGTH_SHORT).show()
            runOnUiThread {
                btnSelectDevice.text = "Start Session"
                btnSelectDevice.isEnabled = true
            }
            return
        }

        val displayItems = foundDevices.values.map { device ->
            val name = device.name
            if (name == device.address || name.endsWith("(Name N/A)")) name else "$name (${device.address})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Cadence Sensor")
            .setItems(displayItems) { _, which ->
                val selectedDevice = foundDevices.values.elementAtOrNull(which)
                selectedDevice?.let { connectToDevice(it) }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                runOnUiThread {
                    btnSelectDevice.text = "Start Session"
                    btnSelectDevice.isEnabled = true
                }
            }
            .setCancelable(false)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) { // For Cadence Sensor
        if (!checkBluetoothConnectPermission()) { // Redundant if S+ permissions flow is correct, but safe check
            Toast.makeText(this, "Bluetooth Connect permission not granted for cadence sensor.", Toast.LENGTH_SHORT).show()
            return
        }

        if (bluetoothGatt != null) {
            Log.d(TAG, "Existing GATT connection found. Closing it before new connection to ${device.address}.")
            bluetoothGatt?.close() // Close existing before creating new
            bluetoothGatt = null
        }

        Log.d(TAG, "Attempting to connect to cadence sensor: $device.name (${device.address})")
        Toast.makeText(this, "Connecting to $device.name for cadence...", Toast.LENGTH_SHORT).show()

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
            val deviceName = gatt.device.name

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT server (cadence sensor) at $deviceName ($deviceAddress).")
                    bluetoothGatt = gatt
                    autoConnecting = false
                    if (checkBluetoothConnectPermission()) {
                        Log.i(TAG, "Attempting to start service discovery for cadence: ${bluetoothGatt?.discoverServices()}")
                        startSession()
                    } else {
                        Log.w(TAG, "No BLUETOOTH_CONNECT permission to discover services for $deviceAddress.")
                        runOnUiThread { Toast.makeText(this@MainActivity, "Connect permission missing for cadence service discovery.", Toast.LENGTH_SHORT).show()}
                        disconnectDeviceInternal(gatt)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from GATT server (cadence sensor) at $deviceName ($deviceAddress).")
                    runOnUiThread {
                        if (isSessionActive) {
                            Toast.makeText(this@MainActivity, "Cadence sensor $deviceName disconnected", Toast.LENGTH_SHORT).show()
                        }
                        // If only cadence disconnects, session might continue with GPS speed.
                        // Or you might choose to stop the whole session. For now, just nullify gatt.
                        // If you want to stop the whole session: stopSession()
                    }
                    disconnectDeviceInternal(gatt, fromUser = false) // Cleans up this specific gatt
                }
            } else {
                Log.w(TAG, "GATT Error for cadence sensor $deviceName ($deviceAddress). Status: $status, NewState: $newState")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Connection Error with $deviceName: $status", Toast.LENGTH_SHORT).show()
                    // Optionally stop the whole session if cadence sensor fails: stopSession()
                }
                disconnectDeviceInternal(gatt, fromUser = false)
                autoConnecting = false
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!checkBluetoothConnectPermission()) {
                Log.w(TAG, "No BLUETOOTH_CONNECT permission during onServicesDiscovered for ${gatt.device.address}.")
                disconnectDeviceInternal(gatt)
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered for cadence sensor ${gatt.device.address}.")
                // Session is started when the "Start Session" button is pressed, which also starts GPS.
                // Here we just enable notifications for cadence.
                // If this is the *first* time (i.e., user clicked start, then selected device),
                // startSession() would have already been called or is about to be.
                // If it's a reconnect, session might already be active.
                enableCscMeasurementNotifications(gatt) // For cadence
                runOnUiThread {
                    // Update UI or state to indicate cadence sensor is ready
                    Toast.makeText(this@MainActivity, "Cadence sensor ready.", Toast.LENGTH_SHORT).show()
                    // If btnSelectDevice was showing "Searching for cadence..." you can update it.
                }
            } else {
                Log.w(TAG, "Service discovery failed for cadence sensor: $status on ${gatt.device.address}")
                runOnUiThread { Toast.makeText(this@MainActivity, "Cadence service discovery failed", Toast.LENGTH_SHORT).show() }
                disconnectDeviceInternal(gatt)
            }
        }

        // Using the variant with 'value' for newer Android versions if your minSDK allows,
        // otherwise, use the older one and access characteristic.value
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onCharacteristicChanged(gatt, characteristic) // Delegate for simplicity here, assuming minSDK is high enough or value is used below
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { //This one is called by the system
            super.onCharacteristicChanged(gatt, characteristic)
            if (!checkBluetoothConnectPermission()) return

            if (characteristic.uuid == CSC_MEASUREMENT_CHARACTERISTIC_UUID) {
                parseCscMeasurementDataForCadence(characteristic.value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!checkBluetoothConnectPermission()) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID &&
                    descriptor.characteristic.uuid == CSC_MEASUREMENT_CHARACTERISTIC_UUID) {
                    Log.i(TAG, "Notifications enabled for CSC Measurement (Cadence).")
                }
            } else {
                Log.w(TAG, "Failed to write CCCD for cadence: ${descriptor.characteristic.uuid}, status: $status")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableCscMeasurementNotifications(gatt: BluetoothGatt?) { // For Cadence
        if (gatt == null || !checkBluetoothConnectPermission()) {
            Log.e(TAG, "GATT is null or no connect permission for enabling cadence notifications.")
            return
        }
        val service = gatt.getService(CYCLING_SPEED_AND_CADENCE_SERVICE_UUID)
        if (service == null) {
            Log.w(TAG, "Cadence Service UUID not found on ${gatt.device.address}")
            disconnectDeviceInternal(gatt)
            return
        }
        val characteristic = service.getCharacteristic(CSC_MEASUREMENT_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.w(TAG, "Cadence Characteristic UUID not found on ${gatt.device.address}")
            disconnectDeviceInternal(gatt)
            return
        }

        val properties = characteristic.properties
        val canNotify = (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0

        if (canNotify) {
            if (gatt.setCharacteristicNotification(characteristic, true)) {
                val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                descriptor?.let {
                    val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(it, value)
                    } else {
                        it.value = value
                        gatt.writeDescriptor(it)
                    }
                    Log.d(TAG, "Enabling NOTIFICATION for cadence ${characteristic.uuid}")
                } ?: run {
                    Log.w(TAG, "CCC Descriptor not found for cadence characteristic: ${characteristic.uuid}")
                    disconnectDeviceInternal(gatt)
                }
            } else {
                Log.w(TAG, "Failed to set notification for cadence ${characteristic.uuid}")
                disconnectDeviceInternal(gatt)
            }
        } else {
            Log.w(TAG, "Cadence Characteristic does not support NOTIFY.")
            disconnectDeviceInternal(gatt)
        }
    }

    private fun parseCscMeasurementDataForCadence(data: ByteArray) {
        if (data.isEmpty()) {
            Log.w(TAG, "Received empty CSC data for cadence.")
            return
        }
        val flags = data[0].toInt()
        val crankRevolutionDataPresent = (flags and 0x02) > 0
        var offset = 1

        // Adjust offset if wheel data is present (even if we ignore it)
        // Wheel Revolution Data Present flag is bit 0
        if ((flags and 0x01) > 0) {
            offset += 6 // 4 bytes for Cumulative Wheel Revolutions, 2 bytes for Last Wheel Event Time
        }

        if (crankRevolutionDataPresent) {
            if (data.size < offset + 4) { // 2 bytes for Cumulative Crank Revolutions, 2 for Last Crank Event Time
                Log.e(TAG, "CSC data too short for crank data. Offset: $offset, Size: ${data.size}")
                return
            }
            val currentCrankRevs = (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8)
            val currentCrankEventTimeUnits = (data[offset + 2].toInt() and 0xFF) or
                    ((data[offset + 3].toInt() and 0xFF) shl 8)

            if (previousCrankRevolutions != -1 && previousCrankEventTime != -1) {
                val crankRevsDelta = if (currentCrankRevs >= previousCrankRevolutions) {
                    currentCrankRevs - previousCrankRevolutions
                } else { // Rollover for UInt16
                    (0xFFFF - previousCrankRevolutions) + currentCrankRevs + 1
                }

                val timeDeltaSeconds = if (currentCrankEventTimeUnits >= previousCrankEventTime) {
                    (currentCrankEventTimeUnits - previousCrankEventTime) / 1024.0
                } else { // Rollover for UInt16
                    ((0xFFFF - previousCrankEventTime) + currentCrankEventTimeUnits + 1) / 1024.0
                }

                if (timeDeltaSeconds > 0 && crankRevsDelta >= 0) { // Allow crankRevsDelta to be 0 for brief stops
                    currentCadenceRpm = if (crankRevsDelta == 0) 0.0 else (crankRevsDelta / timeDeltaSeconds) * 60.0
                    runOnUiThread {
                        tvCadence.text = currentCadenceRpm.roundToInt().toString()
                        updateCadenceMetrics(currentCadenceRpm)
                    }
                }
            }
            previousCrankRevolutions = currentCrankRevs
            previousCrankEventTime = currentCrankEventTimeUnits
            // ... (continuation from the previous parseCscMeasurementDataForCadence method)
        } else {
            // No crank data present in this packet
            // You might want to set cadence to 0 or leave as is after a timeout
            // runOnUiThread {
            //    tvCadence.text = "0" // Or "--"
            // }
        }
    }

    private fun updateSpeedMetrics(newSpeedKmh: Double) { // Called from LocationCallback
        if (!isSessionActive) return

        totalSpeedSumKmh += newSpeedKmh
        speedReadingsCount++
        val avgSpeedKmh = if (speedReadingsCount > 0) totalSpeedSumKmh / speedReadingsCount else 0.0
        tvAvgSpeed.text = String.format("%.1f", avgSpeedKmh)

        if (newSpeedKmh > currentMaxSpeedKmh) {
            currentMaxSpeedKmh = newSpeedKmh
            tvMaxSpeed.text = String.format("%.1f", currentMaxSpeedKmh)
        }
    }

    private fun updateCadenceMetrics(newCadenceRpm: Double) { // Called from parseCscMeasurementDataForCadence
        if (!isSessionActive) return

        totalCadenceSumRpm += newCadenceRpm
        cadenceReadingsCount++
        val avgCadenceRpm = if (cadenceReadingsCount > 0) totalCadenceSumRpm / cadenceReadingsCount else 0.0
        tvAvgCadence.text = avgCadenceRpm.roundToInt().toString()

        if (newCadenceRpm > currentMaxCadenceRpm) {
            currentMaxCadenceRpm = newCadenceRpm
            tvMaxCadence.text = currentMaxCadenceRpm.roundToInt().toString()
        }
    }

    private fun startSession() {
        if (!isSessionActive) {
            isSessionActive = true
            sessionStartTime = System.currentTimeMillis()
            timerHandler.post(timerRunnable)
            resetMetrics() // Reset all metrics (speed and cadence)

            // Attempt to start location updates for speed
            if (checkLocationPermission()) {
                startLocationUpdates()
            } else {
                // If permission not granted, user will be prompted or has been prompted.
                // Speed will show "--" until permission is granted and updates start.
                Toast.makeText(this, "Location permission needed for GPS speed.", Toast.LENGTH_LONG).show()
                // You could re-request here, but the main button flow should handle initial request.
            }

            // UI changes for session active
            runOnUiThread {
                btnSelectDevice.visibility = View.GONE
                btnDisconnect.visibility = View.VISIBLE
            }
            Log.i(TAG, "Session Started. Timer running. Attempting GPS for speed.")
            // Cadence sensor connection is handled separately by its own connect/discover flow.
        }
    }

    private fun stopSession(clearUiCompletely: Boolean = true) {
        if (isSessionActive) {
            isSessionActive = false
            timerHandler.removeCallbacks(timerRunnable)
            stopLocationUpdates() // Stop GPS when session stops
            Log.i(TAG, "Session Stopped.")
        }
        // Always reset button states
        runOnUiThread {
            btnSelectDevice.text = "Start Session"
            btnSelectDevice.isEnabled = true
            btnSelectDevice.visibility = View.VISIBLE
            btnDisconnect.visibility = View.GONE
            if (clearUiCompletely) {
                resetUiToDefault()
            }
        }
    }

    private fun resetUiToDefault() {
        tvSpeed.text = "--"
        tvAvgSpeed.text = "--"
        tvMaxSpeed.text = "--"
        tvCadence.text = "--"
        tvAvgCadence.text = "--"
        tvMaxCadence.text = "--"
        tvTimerValue.text = "00:00:00"
    }

    private fun resetMetrics() {
        // Speed metrics
        totalSpeedSumKmh = 0.0
        speedReadingsCount = 0
        currentMaxSpeedKmh = 0.0

        // Cadence metrics
        previousCrankRevolutions = -1
        previousCrankEventTime = -1
        currentCadenceRpm = 0.0
        totalCadenceSumRpm = 0.0
        cadenceReadingsCount = 0
        currentMaxCadenceRpm = 0.0

        runOnUiThread {
            resetUiToDefault()
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectDevice() { // User-initiated disconnect (stops session, disconnects BT if connected)
        Log.d(TAG, "User clicked disconnect button.")
        stopSession() // This will stop timer, location updates, and reset UI

        if (bluetoothGatt != null) {
            if (checkBluetoothConnectPermission()) {
                Log.d(TAG, "Disconnecting from GATT device: ${bluetoothGatt?.device?.address}")
                bluetoothGatt?.disconnect() // Triggers onConnectionStateChange for cleanup
            } else {
                Log.w(TAG, "No BLUETOOTH_CONNECT permission to disconnect GATT.")
                // Attempt to close gatt object anyway, though OS might restrict without permission
                try { bluetoothGatt?.close() } catch (e: SecurityException) { Log.e(TAG, "SecurityException on gatt.close(): ${e.message}")}
                bluetoothGatt = null // Nullify our reference as we can't properly disconnect
            }
        } else {
            Log.d(TAG, "Disconnect called but no active GATT connection.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectDeviceInternal(gatt: BluetoothGatt?, fromUser: Boolean = false) {
        val deviceAddress = gatt?.device?.address ?: "Unknown Device"
        Log.i(TAG, "Internal disconnect initiated for $deviceAddress. From user: $fromUser")

        gatt?.let {
            if (checkBluetoothConnectPermission()) {
                if (fromUser) { // Should not happen here, disconnectDevice is for user actions
                    it.disconnect()
                } else { // System/error initiated, just close
                    it.close()
                }
            } else {
                Log.w(TAG, "No BLUETOOTH_CONNECT permission to close GATT for $deviceAddress on internal disconnect.")
                try { it.close() } catch (e: SecurityException) { Log.e(TAG, "SecurityException on gatt.close() during internal disconnect: ${e.message}")}
            }
        }

        // If the gatt instance being disconnected is our main one, nullify it.
        if (bluetoothGatt == gatt || bluetoothGatt?.device?.address == deviceAddress) {
            bluetoothGatt = null
            Log.i(TAG, "bluetoothGatt instance for cadence sensor nullified.")
            runOnUiThread {
                // If session is not active through user action, but cadence sensor disconnects,
                // you might want to update cadence UI to "--"
                if (!isSessionActive || btnDisconnect.visibility == View.GONE) {
                    tvCadence.text = "--"
                    tvAvgCadence.text = "--"
                    tvMaxCadence.text = "--"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceName(device: BluetoothDevice?): String {
        if (device == null) return "Unknown Device"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                device.name ?: device.address
            } else {
                device.address + " (Name N/A)"
            }
        } else {
            device.name ?: device.address
        }
    }

    private fun checkBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed for older versions for basic connect/disconnect once device is obtained
        }
    }

    override fun onResume() {
        super.onResume()
        if (isSessionActive && !requestingLocationUpdates && checkLocationPermission()) {
            Log.d(TAG, "Resuming app with active session. Attempting to restart location updates.")
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        if (requestingLocationUpdates) {
            Log.d(TAG, "Pausing app. Stopping location updates to save battery.")
            stopLocationUpdates()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called. Cleaning up.")
        isSessionActive = false
        timerHandler.removeCallbacks(timerRunnable)
        stopLocationUpdates() // Ensure location updates are stopped

        if (scanning) { // Stop scan if activity is destroyed while scanning
            stopBleScan()
        }
        if (bluetoothGatt != null) {
            Log.d(TAG, "Closing GATT connection in onDestroy.")
            if (checkBluetoothConnectPermission()) {
                bluetoothGatt?.close()
            }
            bluetoothGatt = null
        }
    }
}
