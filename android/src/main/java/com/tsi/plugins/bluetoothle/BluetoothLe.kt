package com.tsi.plugins.bluetoothle

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.*
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.ServiceConnection
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.provider.Settings.*
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.location.LocationManagerCompat
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.*
import java.lang.System


@CapacitorPlugin(
    name = "BluetoothLe",
    permissions = [
        Permission(
            strings = [
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ], alias = "ACCESS_COARSE_LOCATION"
        ),
        Permission(
            strings = [
                Manifest.permission.ACCESS_FINE_LOCATION,
            ], alias = "ACCESS_FINE_LOCATION"
        ),
        Permission(
            strings = [
                Manifest.permission.BLUETOOTH,
            ], alias = "BLUETOOTH"
        ),
        Permission(
            strings = [
                Manifest.permission.BLUETOOTH_ADMIN,
            ], alias = "BLUETOOTH_ADMIN"
        ),
        Permission(
            strings = [
                // Manifest.permission.BLUETOOTH_SCAN
                "android.permission.BLUETOOTH_SCAN",
            ], alias = "BLUETOOTH_SCAN"
        ),
        Permission(
            strings = [
                // Manifest.permission.BLUETOOTH_ADMIN
                "android.permission.BLUETOOTH_CONNECT",
            ], alias = "BLUETOOTH_CONNECT"
        ),
    ]
)
class BluetoothLe : Plugin() {
    companion object {
        private val TAG = BluetoothLe::class.java.simpleName

        const val PERM_BT = "BLUETOOTH"
        const val PERM_BT_SCAN = "BLUETOOTH_SCAN"
        const val PERM_BT_CONNECT = "BLUETOOTH_CONNECT"
        const val PERM_BT_ADMIN = "BLUETOOTH_ADMIN"
        const val PERM_ACCESS_FINE_LOC = "ACCESS_FINE_LOCATION"
        const val PERM_ACCESS_COARSE_LOC = "ACCESS_COARSE_LOCATION"

        // maximal scan duration for requestDevice
        private const val MAX_SCAN_DURATION: Long = 30000
        private const val CONNECTION_TIMEOUT: Float = 10000.0F
        private const val DEFAULT_TIMEOUT: Float = 5000.0F
    }

    private var bleRunner: BLESystem? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bleRunner = service as Author.Local
            bound = true
            val status = bleRunner!!.initialize(activity, displayStrings!!)
            if (status == InitStatus.SUCCESS) {
                val ret = JSObject()
                notifyListeners("foregroundInitSuccess", ret)
            } else {
                val ret = JSObject()
                ret.put("value", BLERunner.strFromInitStatus(status))
                notifyListeners("foregroundInitFailed", ret)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    private var displayStrings: DisplayStrings? = null
    private var aliases: Array<String> = arrayOf()

    private fun debug(message: String) {
        Log.d(TAG, message)
    }

    override fun load() {
        displayStrings = getDisplayStrings()
    }

    @PluginMethod
    fun initialize(call: PluginCall) {
        // Build.VERSION_CODES.S = 31
        if (Build.VERSION.SDK_INT >= 31) {
            val neverForLocation = call.getBoolean("androidNeverForLocation") ?: false
            aliases = if (neverForLocation) {
                arrayOf(
                    PERM_BT_SCAN,
                    PERM_BT_CONNECT
                )
            } else {
                arrayOf(
                    PERM_BT_SCAN,
                    PERM_BT_CONNECT,
                    PERM_ACCESS_FINE_LOC
                )
            }
        } else {
            aliases = arrayOf(
                PERM_ACCESS_COARSE_LOC,
                PERM_ACCESS_FINE_LOC,
                PERM_BT,
                PERM_BT_ADMIN
            )
        }
        requestPermissionForAliases(aliases, call, "checkPermission")
    }

    @PluginMethod
    fun initialized(call: PluginCall) {

    }

    @PermissionCallback
    private fun checkPermission(call: PluginCall) {
        val granted: List<Boolean> = aliases.map { alias ->
            getPermissionState(alias) == PermissionState.GRANTED
        }
        // all have to be true
        if (granted.all { it }) {
            runInitialization(call)
        } else {
            call.reject("Permission denied.")
        }
    }

    private fun runInitialization(call: PluginCall) {
        val isForeground = call.getBoolean("isForeground") ?: false

        if (isForeground && Build.VERSION.SDK_INT >= 26) {
            if (bound) {
                val ret = JSObject()
                ret.put("error", "BLE Service already initialized")
                call.resolve(ret)
                return
            }
            setupNotifChannel()
            startBLEService()

            call.resolve()
        } else {
            bleRunner = BLERunner()
            val status = bleRunner!!.initialize(activity, displayStrings!!)
            if (status == InitStatus.SUCCESS) {
                call.resolve()
            } else {
                call.reject(BLERunner.strFromInitStatus(status))
            }

            call.resolve()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupNotifChannel() {
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(Author.channelId, Author.channelId, importance).apply {
            description = "Notification channel for ${Author.channelId}"
        }

        val notifManager : NotificationManager = activity.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifManager.createNotificationChannel(channel)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startBLEService() {
        val intent = Intent(activity, Author::class.java).also { intent ->
            activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        activity.startForegroundService(intent)
    }


    @PluginMethod
    fun isEnabled(call: PluginCall) {
        val enabled = bleRunner?.isEnabled()
        val result = JSObject()
        result.put("value", enabled)
        call.resolve(result)
    }

    @PluginMethod
    fun enable(call: PluginCall) {
        val result = bleRunner?.enable(activity)
        if (result != true) {
            call.reject("Enable failed.")
            return
        }
        call.resolve()
    }

    @PluginMethod
    fun disable(call: PluginCall) {
        val result = bleRunner?.disable()
        if (result != true) {
            call.reject("Disable failed.")
            return
        }
        call.resolve()
    }

    @PluginMethod
    fun startEnabledNotifications(call: PluginCall) {
        try {
            val bleCtx = BLEContext()
            val onEnabledChanged = fun (key: String, value: Any) {
                val ret = JSObject()
                ret.put("value", value)
                notifyListeners(key, ret)
            }

            bleCtx.Set(BLERunner.KEY_ON_NOTIFY, onEnabledChanged)
            bleRunner?.startEnabledNotifications(context, bleCtx)
        } catch (e: Error) {
            Logger.error(
                TAG, "Error while registering enabled state receiver: ${e.localizedMessage}", e
            )
            call.reject("startEnabledNotifications failed.")
            return
        }
        call.resolve()
    }

    @PluginMethod
    fun stopEnabledNotifications(call: PluginCall) {
        bleRunner?.stopEnabledNotifications(context)
        call.resolve()
    }

    @PluginMethod
    fun isLocationEnabled(call: PluginCall) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = LocationManagerCompat.isLocationEnabled(lm)
        Logger.debug(TAG, "location $enabled")
        val result = JSObject()
        result.put("value", enabled)
        call.resolve(result)
    }

    @PluginMethod
    fun openLocationSettings(call: PluginCall) {
        val intent = Intent(ACTION_LOCATION_SOURCE_SETTINGS)
        activity.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    fun openBluetoothSettings(call: PluginCall) {
        val intent = Intent(ACTION_BLUETOOTH_SETTINGS)
        activity.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        val intent = Intent(ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:" + activity.packageName)
        activity.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    fun setDisplayStrings(call: PluginCall) {
        displayStrings = DisplayStrings(
            call.getString(
                "scanning", displayStrings!!.scanning
            ) as String,
            call.getString(
                "cancel", displayStrings!!.cancel
            ) as String,
            call.getString(
                "availableDevices", displayStrings!!.availableDevices
            ) as String,
            call.getString(
                "noDeviceFound", displayStrings!!.noDeviceFound
            ) as String,
        )
        call.resolve()
    }

    @PluginMethod
    fun requestDevice(call: PluginCall) {
        val scanFilters = getScanFilters(call) ?: return
        val scanSettings = getScanSettings(call) ?: return
        val namePrefix = call.getString("namePrefix", "") as String
        val bleCtx = BLEContext()

        val onDev: (BluetoothDevice) -> Unit = { dev ->
            val bleDev = getBleDevice(dev)
            call.resolve(bleDev)
        }
        val onFail: (String?) -> Unit = { message -> call.reject(message) }

        bleCtx.Set(BLERunner.KEY_ON_DEVICE, onDev)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)

        bleRunner?.requestDevice(activity, bleCtx, scanFilters, scanSettings, namePrefix)
    }

    @PluginMethod
    fun requestLEScan(call: PluginCall) {
        val scanFilters = getScanFilters(call) ?: return
        val scanSettings = getScanSettings(call) ?: return
        val namePrefix = call.getString("namePrefix", "") as String
        val allowDuplicates = call.getBoolean("allowDuplicates", false) as Boolean
        val bleCtx = BLEContext()

        val onScanSuccess: () -> Unit = { call.resolve() }
        val onScanResult: (ScanResult) -> Unit = { result ->
            val scanResult = getScanResult(result)
            try {
                notifyListeners("onScanResult", scanResult)
            } catch (e: ConcurrentModificationException) {
                Logger.error(TAG, "Error in notifyListeners: ${e.localizedMessage}", e)
            }
        }
        val onFail: (String?) -> Unit = { message -> call.reject(message) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onScanSuccess)
        bleCtx.Set(BLERunner.KEY_ON_SCAN_RESULT, onScanResult)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)

        bleRunner?.requestLEScan(
            context,
            bleCtx,
            scanFilters,
            scanSettings,
            namePrefix,
            allowDuplicates
        )
    }

    @PluginMethod
    fun stopLEScan(call: PluginCall) {
        bleRunner?.stopLEScan()
        call.resolve()
    }

    @PluginMethod
    fun getDevices(call: PluginCall) {
        val deviceIds = call.getArray("deviceIds").toList<String>()
        val bleDevices = JSArray()
        deviceIds.forEach { deviceId ->
            val bleDevice = JSObject()
            bleDevice.put("deviceId", deviceId)
            bleDevices.put(bleDevice)
        }
        val result = JSObject()
        result.put("devices", bleDevices)
        call.resolve(result)
    }

    @PluginMethod
    fun getConnectedDevices(call: PluginCall) {
        val devices = bleRunner?.getConnectedDevices(activity)
        val bleDevices = JSArray()
        devices?.forEach { device ->
            bleDevices.put(getBleDevice(device))
        }
        val result = JSObject()
        result.put("devices", bleDevices)
        call.resolve(result)
    }

    @PluginMethod
    fun connect(call: PluginCall) {
        debug("Attempting to Connect")
        val deviceId = getDeviceId(call) ?: return
        val timeout = call.getFloat("timeout", CONNECTION_TIMEOUT)!!.toLong()
        val bleCtx = BLEContext()

        debug("Connecting to $deviceId with Timeout of $timeout ms")

        val onSuccess = fun () {
            debug("Connect Successful")
            call.resolve()
        }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccess)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleRunner?.connect(activity, bleCtx, deviceId, timeout)
    }

    private fun onDisconnect(deviceId: String) {
        try {
            notifyListeners("disconnected|${deviceId}", null)
        } catch (e: ConcurrentModificationException) {
            Logger.error(TAG, "Error in notifyListeners: ${e.localizedMessage}", e)
        }
    }

    @PluginMethod
    fun createBond(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val bleCtx = BLEContext()

        val onSuccess = fun () { call.resolve() }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccess)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleRunner?.createBond(activity, bleCtx, deviceId)
    }

    @PluginMethod
    fun isBonded(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val bleCtx = BLEContext()

        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)

        val isBonded = bleRunner?.isBonded(activity, bleCtx, deviceId)
        val result = JSObject()
        result.put("value", isBonded)
        call.resolve(result)
    }

    @PluginMethod
    fun disconnect(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        val bleCtx = BLEContext()

        val onSuccess = fun () { call.resolve() }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccess)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleRunner?.disconnect(activity, bleCtx, deviceId, timeout)
    }

    @PluginMethod
    fun getServices(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val bleCtx = BLEContext()

        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)

        val services = bleRunner?.getServices(activity, bleCtx, deviceId)
        val bleServices = JSArray()
        services?.forEach { service ->
            val bleCharacteristics = JSArray()
            service.characteristics.forEach { characteristic ->
                val bleCharacteristic = JSObject()
                bleCharacteristic.put("uuid", characteristic.uuid)
                bleCharacteristic.put("properties", getProperties(characteristic))
                val bleDescriptors = JSArray()
                characteristic.descriptors.forEach { descriptor ->
                    val bleDescriptor = JSObject()
                    bleDescriptor.put("uuid", descriptor.uuid)
                    bleDescriptors.put(bleDescriptor)
                }
                bleCharacteristic.put("descriptors", bleDescriptors)
                bleCharacteristics.put(bleCharacteristic)
            }
            val bleService = JSObject()
            bleService.put("uuid", service.uuid)
            bleService.put("characteristics", bleCharacteristics)
            bleServices.put(bleService)
        }
        val ret = JSObject()
        ret.put("services", bleServices)
        call.resolve(ret)
    }

    private fun getProperties(characteristic: BluetoothGattCharacteristic): JSObject {
        val properties = JSObject()
        properties.put(
            "broadcast",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST > 0
        )
        properties.put(
            "read", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ > 0
        )
        properties.put(
            "writeWithoutResponse",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0
        )
        properties.put(
            "write", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0
        )
        properties.put(
            "notify", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0
        )
        properties.put(
            "indicate",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0
        )
        properties.put(
            "authenticatedSignedWrites",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE > 0
        )
        properties.put(
            "extendedProperties",
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS > 0
        )
        return properties
    }

    @PluginMethod
    fun discoverServices(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        val bleCtx = BLEContext()

        val onSuccess = fun () { call.resolve() }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccess)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleRunner?.discoverServices(activity, bleCtx, deviceId, timeout)
    }

    @PluginMethod
    fun getMtu(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val bleCtx = BLEContext()

        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)

        val mtu = bleRunner?.getMtu(activity, bleCtx, deviceId)
        val ret = JSObject()
        ret.put("value", mtu)
        call.resolve(ret)
    }

    @PluginMethod
    fun requestConnectionPriority(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val connectionPriority = call.getInt("connectionPriority") ?: -1
        val bleCtx = BLEContext()

        val onSuccess = fun () { call.resolve() }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccess)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleRunner?.requestConnectionPriority(activity, bleCtx, deviceId, connectionPriority)
    }

    @PluginMethod
    fun readRssi(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        val bleCtx = BLEContext()

        val onSuccessWVal = fun (value: Any) {
            val ret = JSObject()
            ret.put("value", value)
            call.resolve(ret)
        }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccessWVal)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleRunner?.readRssi(activity, bleCtx, deviceId, timeout)
    }

    @PluginMethod
    fun read(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val characteristic = getCharacteristic(call) ?: return
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        val bleCtx = BLEContext()

        val onSuccessWVal = fun (value: Any) {
            val ret = JSObject()
            ret.put("value", value)
            call.resolve(ret)
        }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccessWVal)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleRunner?.read(activity, bleCtx, deviceId, characteristic.first, characteristic.second, timeout)
    }

    @PluginMethod
    fun write(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val characteristic = getCharacteristic(call) ?: return
        val value = call.getString("value", null)
        if (value == null) {
            call.reject("Value required.")
            return
        }
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val bleCtx = BLEContext()

        val onSuccess = fun () { call.resolve() }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccess)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleRunner?.write(activity, bleCtx, deviceId, characteristic.first, characteristic.second, value, writeType, timeout)
    }

    @PluginMethod
    fun writeWithoutResponse(call: PluginCall) {
        debug("Writing without Response")
        val deviceId = getDeviceId(call) ?: return
        val characteristic = getCharacteristic(call) ?: return
        val value = call.getString("value", null)
        if (value == null) {
            call.reject("Value required.")
            return
        }
        debug("Service: ${characteristic.first} | Char: ${characteristic.second}")
        debug("Value: $value")
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val bleCtx = BLEContext()

        val onSuccess = fun () {
            debug("Successfully wrote!")
            call.resolve()
        }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccess)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleRunner?.write(activity, bleCtx, deviceId, characteristic.first, characteristic.second, value, writeType, timeout)
    }

    @PluginMethod
    fun readDescriptor(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val descriptor = getDescriptor(call) ?: return
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        val bleCtx = BLEContext()

        val onSuccessWVal = fun (value: Any) {
            val ret = JSObject()
            ret.put("value", value)
            call.resolve(ret)
        }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccessWVal)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleRunner?.readDescriptor(activity, bleCtx, deviceId, descriptor, timeout)
    }

    @PluginMethod
    fun writeDescriptor(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val descriptor = getDescriptor(call) ?: return
        val value = call.getString("value", null)
        if (value == null) {
            call.reject("Value required.")
            return
        }
        val timeout = call.getFloat("timeout", DEFAULT_TIMEOUT)!!.toLong()
        val bleCtx = BLEContext()

        val onSuccessWVal = fun () { call.resolve() }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccessWVal)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleRunner?.writeDescriptor(activity, bleCtx, deviceId, descriptor, value, timeout)
    }

    @PluginMethod
    fun startNotifications(call: PluginCall) {
        debug("Starting Notifications")
        val deviceId = getDeviceId(call) ?: return
        val characteristic = getCharacteristic(call) ?: return
        val bleCtx = BLEContext()

        debug("Starting notifications for $deviceId with Service: ${characteristic.first} | Char: ${characteristic.second}")

        val onSuccess = fun () {
            debug("Notification successfully started")
            call.resolve()
        }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }
        val onNotify = fun (key: String, value: Any, timestamp: Long?) {
            val ret = JSObject()
            ret.put("timestamp", timestamp)
            ret.put("value", value)
            notifyListeners(key, ret)
        }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccess)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleCtx.Set(BLERunner.KEY_ON_NOTIFY, onNotify)
        bleRunner?.startNotifications(activity, bleCtx, deviceId, characteristic) ?: call.reject("No Runner Available")
    }

    @PluginMethod
    fun stopNotifications(call: PluginCall) {
        val deviceId = getDeviceId(call) ?: return
        val characteristic = getCharacteristic(call) ?: return
        val bleCtx = BLEContext()

        val onSuccessWVal = fun () { call.resolve() }
        val onFail = fun (message: String) { call.reject(message) }
        val onDisconn = fun (deviceId: String) { onDisconnect(deviceId) }

        bleCtx.Set(BLERunner.KEY_ON_SUCCESS, onSuccessWVal)
        bleCtx.Set(BLERunner.KEY_ON_FAIL, onFail)
        bleCtx.Set(BLERunner.KEY_ON_DISCONNECT, onDisconn)
        bleRunner?.stopNotifications(activity, bleCtx, deviceId, characteristic)
    }

    @PluginMethod
    fun clearCache(call: PluginCall) {
        when (bleRunner!!) {
            is Author.Local -> {
                CoroutineScope(Dispatchers.IO).launch {
                    (bleRunner as Author.Local).scrub()
                }
            }
        }

        call.resolve()
    }

    @PluginMethod
    fun catchup(call: PluginCall) {
        debug("Running Catchup")
        val endTime = call.getFloat("endTime")?.toLong() ?: System.currentTimeMillis()
        debug("Catchup EndTime: $endTime")

        when (bleRunner!!) {
            is Author.Local -> {
                runBlocking {
                    debug("Running Author.Local catchupAsync in blocking mode")
                    val messages = (bleRunner as Author.Local).catchupAsync(endTime).await()
                    debug("Compiling messages into JSObjects for ${messages.size} messages.")
                    messages.forEach() { message ->
                        run {
                            val ret = JSObject()
                            ret.put("timestamp", message.timestamp)
                            ret.put("value", message.value)
                            notifyListeners(message.key, ret)
                        }
                    }
                    call.resolve()
                }
            }
        }

        call.reject("BLE Runner not running in foreground mode!")
    }

    private fun getScanFilters(call: PluginCall): List<ScanFilter>? {
        val filters: ArrayList<ScanFilter> = ArrayList()

        val services = (call.getArray("services", JSArray()) as JSArray).toList<String>()
        val name = call.getString("name", null)
        try {
            for (service in services) {
                val filter = ScanFilter.Builder()
                filter.setServiceUuid(ParcelUuid.fromString(service))
                if (name != null) {
                    filter.setDeviceName(name)
                }
                filters.add(filter.build())
            }
        } catch (e: IllegalArgumentException) {
            call.reject("Invalid service UUID.")
            return null
        }

        if (name != null && filters.isEmpty()) {
            val filter = ScanFilter.Builder()
            filter.setDeviceName(name)
            filters.add(filter.build())
        }

        return filters
    }

    private fun getScanSettings(call: PluginCall): ScanSettings? {
        val scanSettings = ScanSettings.Builder()
        val scanMode = call.getInt("scanMode", ScanSettings.SCAN_MODE_BALANCED) as Int
        try {
            scanSettings.setScanMode(scanMode)
        } catch (e: IllegalArgumentException) {
            call.reject("Invalid scan mode.")
            return null
        }
        return scanSettings.build()
    }

    @SuppressLint("MissingPermission")
    private fun getBleDevice(device: BluetoothDevice): JSObject {
        val bleDevice = JSObject()
        bleDevice.put("deviceId", device.address)
        if (device.name != null) {
            bleDevice.put("name", device.name)
        }

        val uuids = JSArray()
        device.uuids?.forEach { uuid -> uuids.put(uuid.toString()) }
        if (uuids.length() > 0) {
            bleDevice.put("uuids", uuids)
        }

        return bleDevice
    }

    @SuppressLint("MissingPermission")
    private fun getScanResult(result: ScanResult): JSObject {
        val scanResult = JSObject()

        val bleDevice = getBleDevice(result.device)
        scanResult.put("device", bleDevice)

        if (result.device.name != null) {
            scanResult.put("localName", result.device.name)
        }

        scanResult.put("rssi", result.rssi)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            scanResult.put("txPower", result.txPower)
        } else {
            scanResult.put("txPower", 127)
        }

        val manufacturerData = JSObject()
        val manufacturerSpecificData = result.scanRecord?.manufacturerSpecificData
        if (manufacturerSpecificData != null) {
            for (i in 0 until manufacturerSpecificData.size()) {
                val key = manufacturerSpecificData.keyAt(i)
                val bytes = manufacturerSpecificData.get(key)
                manufacturerData.put(key.toString(), bytesToString(bytes))
            }
        }
        scanResult.put("manufacturerData", manufacturerData)

        val serviceDataObject = JSObject()
        val serviceData = result.scanRecord?.serviceData
        serviceData?.forEach {
            serviceDataObject.put(it.key.toString(), bytesToString(it.value))
        }
        scanResult.put("serviceData", serviceDataObject)

        val uuids = JSArray()
        result.scanRecord?.serviceUuids?.forEach { uuid -> uuids.put(uuid.toString()) }
        scanResult.put("uuids", uuids)

        scanResult.put("rawAdvertisement", result.scanRecord?.bytes?.let { bytesToString(it) })
        return scanResult
    }

    private fun getDisplayStrings(): DisplayStrings {
        return DisplayStrings(
            config.getString(
                "displayStrings.scanning", "Scanning..."
            ),
            config.getString(
                "displayStrings.cancel", "Cancel"
            ),
            config.getString(
                "displayStrings.availableDevices", "Available devices"
            ),
            config.getString(
                "displayStrings.noDeviceFound", "No device found"
            ),
        )
    }

    private fun getDeviceId(call: PluginCall): String? {
        val deviceId = call.getString("deviceId", null)
        if (deviceId == null) {
            call.reject("deviceId required.")
            return null
        }
        return deviceId
    }

    private fun getCharacteristic(call: PluginCall): Pair<UUID, UUID>? {
        val serviceString = call.getString("service", null)
        val serviceUUID: UUID?
        try {
            serviceUUID = UUID.fromString(serviceString)
        } catch (e: IllegalArgumentException) {
            call.reject("Invalid service UUID.")
            return null
        }
        if (serviceUUID == null) {
            call.reject("Service UUID required.")
            return null
        }
        val characteristicString = call.getString("characteristic", null)
        val characteristicUUID: UUID?
        try {
            characteristicUUID = UUID.fromString(characteristicString)
        } catch (e: IllegalArgumentException) {
            call.reject("Invalid characteristic UUID.")
            return null
        }
        if (characteristicUUID == null) {
            call.reject("Characteristic UUID required.")
            return null
        }
        return Pair(serviceUUID, characteristicUUID)
    }

    private fun getDescriptor(call: PluginCall): Triple<UUID, UUID, UUID>? {
        val characteristic = getCharacteristic(call) ?: return null
        val descriptorString = call.getString("descriptor", null)
        val descriptorUUID: UUID?
        try {
            descriptorUUID = UUID.fromString(descriptorString)
        } catch (e: IllegalAccessException) {
            call.reject("Invalid descriptor UUID.")
            return null
        }
        if (descriptorUUID == null) {
            call.reject("Descriptor UUID required.")
            return null
        }
        return Triple(characteristic.first, characteristic.second, descriptorUUID)
    }
}
