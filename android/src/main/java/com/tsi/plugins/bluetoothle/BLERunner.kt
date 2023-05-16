package com.tsi.plugins.bluetoothle

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.util.Log
import androidx.core.app.ActivityCompat
import java.lang.IllegalStateException
import java.util.UUID

enum class InitStatus {
    SUCCESS,
    ERR_BLE_NOT_SUPPORTED,
    ERR_BLE_NOT_AVAILABLE
}

typealias T_ON_FAIL = (String) -> Unit
typealias T_ON_SUCCESS = () -> Unit
typealias T_ON_SUCCESS_W_VAL = (Any) -> Unit
typealias T_ON_DEVICE = (BluetoothDevice) -> Unit
typealias T_ON_DISCONNECT = (String) -> Unit
typealias T_ON_NOTIFY = (String, Any, Long?) -> Unit
typealias T_ON_SCAN_RESULT = (ScanResult) -> Unit

class BLERunner : Binder(), BLESystem {
    companion object {
        private val TAG = BLERunner::class.java.simpleName

        val channelId = "$TAG::Foreground"

        const val KEY_ON_DEVICE = "onDevice"
        const val KEY_ON_SUCCESS = "onSuccess"
        const val KEY_ON_FAIL = "onFail"
        const val KEY_ON_DISCONNECT = "onDisconnect"
        const val KEY_ON_NOTIFY = "onNotify"
        const val KEY_ON_SCAN_RESULT = "onScanResult"

        var MAX_SCAN_DURATION: Long = 30000

        fun strFromInitStatus(status: InitStatus) : String {
            return when (status) {
                InitStatus.SUCCESS -> "Initialization Success!"
                InitStatus.ERR_BLE_NOT_AVAILABLE -> "BLE not available."
                InitStatus.ERR_BLE_NOT_SUPPORTED -> "BLE not supported."
            }
        }
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var stateReceiver: BroadcastReceiver? = null
    private var deviceMap = HashMap<String, Device>()
    private var deviceScanner: DeviceScanner? = null
    private var displayStrings: DisplayStrings? = null
    private var aliases: Array<String> = arrayOf()

    private fun debug(msg: String) {
        Log.d(TAG, msg)
    }

    override fun initialize(ctx: Context, displayStrings: DisplayStrings) : InitStatus {
        this.displayStrings = displayStrings
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return InitStatus.ERR_BLE_NOT_SUPPORTED
        }

        bluetoothAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        if (bluetoothAdapter == null) {
            return InitStatus.ERR_BLE_NOT_AVAILABLE
        }

        return InitStatus.SUCCESS
    }

    private fun assertBLEAdapter(): Boolean? {
        if (bluetoothAdapter !== null) return true
        return null
    }

    private fun hasPermission(ctx: Context, perm: String): Boolean? {
        if (ActivityCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) return null
        return true
    }

    override fun isEnabled(): Boolean {
        assertBLEAdapter() ?: return false
        return bluetoothAdapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    override fun enable(ctx: Context): Boolean? {
        assertBLEAdapter() ?: return false
        return bluetoothAdapter?.enable()
    }

    @SuppressLint("MissingPermission")
    override fun disable(): Boolean? {
        assertBLEAdapter() ?: return false
        return bluetoothAdapter?.disable()
    }

    override fun startEnabledNotifications(ctx: Context, bleCtx: BLEContext) {
        assertBLEAdapter() ?: return
        createStateReceiver(ctx, bleCtx)
    }

    private fun createStateReceiver(ctx: Context, bleCtx: BLEContext) {
        val onEnabledChanged = bleCtx.Get<T_ON_NOTIFY>(KEY_ON_NOTIFY)
        if (stateReceiver == null) {
            stateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        val enabled = state == BluetoothAdapter.STATE_ON

                        onEnabledChanged?.invoke("onEnabledChanged", enabled, null)
                    }
                }
            }

            val intentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            ctx.registerReceiver(stateReceiver, intentFilter)
        }
    }

    override fun stopEnabledNotifications(ctx: Context) {
        if (stateReceiver != null) {
            ctx.unregisterReceiver(stateReceiver)
        }
        stateReceiver = null
    }

    override fun requestDevice(
        ctx: Context,
        bleCtx: BLEContext,
        scanFilters: List<ScanFilter>,
        scanSettings: ScanSettings,
        namePrefix: String
    ) {
        assertBLEAdapter() ?: return

        try {
            deviceScanner?.stopScanning()
        } catch (e: IllegalStateException) {
            debug(e.localizedMessage)
            val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
            onFail?.invoke(e.localizedMessage)
            return
        }

        deviceScanner = DeviceScanner(ctx,
            bluetoothAdapter!!,
            scanDuration = MAX_SCAN_DURATION,
            displayStrings = displayStrings!!,
            showDialog = true,
        )
        deviceScanner?.startScanning(
            scanFilters,
            scanSettings,
            false,
            namePrefix,
            { scanResponse ->
                run {
                    val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
                    if (scanResponse.success) {
                        if (scanResponse.device == null) {
                            onFail?.invoke("No device found.")
                        } else {
                            val onDevice = bleCtx.Get<T_ON_DEVICE>(KEY_ON_DEVICE)
                            onDevice?.invoke(scanResponse.device)
                        }
                    } else {
                        onFail?.invoke(scanResponse.message ?: "")
                    }
                }
            }, null
        )
    }

    override fun requestLEScan(
        ctx: Context,
        bleCtx: BLEContext,
        scanFilters: List<ScanFilter>,
        scanSettings: ScanSettings,
        namePrefix: String,
        allowDuplicates: Boolean
    ) {
        assertBLEAdapter() ?: return
        try {
            deviceScanner?.stopScanning()
        } catch (e: IllegalStateException) {
            debug("Error in requestLEScan: ${e.localizedMessage}")
            bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)?.invoke(e.localizedMessage)
            return
        }

        deviceScanner = DeviceScanner(
            ctx,
            bluetoothAdapter!!,
            scanDuration = null,
            displayStrings = displayStrings!!,
            showDialog = false,
        )

        deviceScanner?.startScanning(
            scanFilters,
            scanSettings,
            allowDuplicates,
            namePrefix,
            { scanResponse -> run {
                if (scanResponse.success) {
                    bleCtx.Get<T_ON_SUCCESS>(KEY_ON_SUCCESS)?.invoke()
                } else {
                    bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)?.invoke(scanResponse.message ?: "")
                }
            } },
            { result -> run {
                bleCtx.Get<T_ON_SCAN_RESULT>(KEY_ON_SCAN_RESULT)?.invoke(result)
            } }
        )
    }

    override fun stopLEScan() {
        assertBLEAdapter() ?: return
        try {
            deviceScanner?.stopScanning()
        } catch (e: IllegalStateException) {
            debug("Error in stopLEScan: ${e.localizedMessage}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun getConnectedDevices(ctx: Context): List<BluetoothDevice> {
        assertBLEAdapter() ?: return listOf()
        val bluetoothManager =
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
        return bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
    }

    private fun getOrCreateDevice(ctx: Context, bleCtx: BLEContext, deviceId: String): Device? {
        val device = deviceMap[deviceId]
        if (device != null) {
            return device
        }
        return try {
            val newDevice = Device(
                ctx.applicationContext, bluetoothAdapter!!, deviceId
            ) {
                val onDisconnect = bleCtx.Get<T_ON_DISCONNECT>(KEY_ON_DISCONNECT)
                onDisconnect?.invoke(deviceId)
            }
            deviceMap[deviceId] = newDevice
            newDevice
        } catch (e: IllegalArgumentException) {
            val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
            onFail?.invoke("Invalid DeviceId")
            null
        }
    }

    override fun connect(ctx: Context, bleCtx: BLEContext, deviceId: String, timeout: Long) {
        assertBLEAdapter() ?: return
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        device?.connect(timeout) { response ->
            run {
                if (response.success) {
                    val onSuccess = bleCtx.Get<T_ON_SUCCESS>(KEY_ON_SUCCESS)
                    onSuccess?.invoke()
                } else {
                    val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
                    onFail?.invoke(response.value)
                }
            }
        }
    }

    override fun createBond(ctx: Context, bleCtx: BLEContext, deviceId: String) {
        assertBLEAdapter() ?: return
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        device?.createBond { response ->
            run {
                if (response.success) {
                    val onSuccess = bleCtx.Get<T_ON_SUCCESS>(KEY_ON_SUCCESS)
                    onSuccess?.invoke()
                } else {
                    val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
                    onFail?.invoke(response.value)
                }
            }
        }
    }

    override fun isBonded(ctx: Context, bleCtx: BLEContext, deviceId: String): Boolean {
        assertBLEAdapter() ?: return false
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        return device?.isBonded() ?: false
    }

    override fun disconnect(ctx: Context, bleCtx: BLEContext, deviceId: String, timeout: Long) {
        assertBLEAdapter() ?: return
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        device?.disconnect(timeout) { response ->
            run {
                if (response.success) {
                    deviceMap.remove(device.getId())
                    val onSuccess = bleCtx.Get<T_ON_SUCCESS>(KEY_ON_SUCCESS)
                    onSuccess?.invoke()
                } else {
                    val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
                    onFail?.invoke(response.value)
                }
            }
        }
    }

    override fun getServices(ctx: Context, bleCtx: BLEContext, deviceId: String): MutableList<BluetoothGattService> {
        assertBLEAdapter() ?: return mutableListOf()
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        return device?.getServices() ?: mutableListOf()
    }

    override fun discoverServices(ctx: Context, bleCtx: BLEContext, deviceId: String, timeout: Long) {
        assertBLEAdapter() ?: return
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        device?.discoverServices(timeout) { response ->
            run {
                if (response.success) {
                    val onSuccess = bleCtx.Get<T_ON_SUCCESS>(KEY_ON_SUCCESS)
                    onSuccess?.invoke()
                } else {
                    val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
                    onFail?.invoke(response.value)
                }
            }
        }
    }

    override fun getMtu(ctx: Context, bleCtx: BLEContext, deviceId: String): Int {
        assertBLEAdapter() ?: return -1
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        return device?.getMtu() ?: -1
    }

    override fun requestConnectionPriority(ctx: Context, bleCtx: BLEContext, deviceId: String, priority: Int) {
        assertBLEAdapter() ?: return
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        if (priority < BluetoothGatt.CONNECTION_PRIORITY_BALANCED || priority > BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) {
            val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
            onFail?.invoke("Invalid connectionPriority")
            return
        }

        val result = device?.requestConnectionPriority(priority) ?: false
        if (result) {
            val onSuccess = bleCtx.Get<T_ON_SUCCESS>(KEY_ON_SUCCESS)
            onSuccess?.invoke()
        } else {
            val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
            onFail?.invoke("RequestConnectionPriority failed.")
        }
    }

    override fun readRssi(ctx: Context, bleCtx: BLEContext, deviceId: String, timeout: Long) {
        assertBLEAdapter() ?: return
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        device?.readRssi(timeout) { response ->
            run {
                if (response.success) {
                    val onSuccessWVal = bleCtx.Get<T_ON_SUCCESS_W_VAL>(KEY_ON_SUCCESS)
                    onSuccessWVal?.invoke(response.value)
                } else {
                    val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
                    onFail?.invoke(response.value)
                }
            }
        }
    }

    override fun read(ctx: Context, bleCtx: BLEContext, deviceId: String, svc: UUID, char: UUID, timeout: Long) {
        assertBLEAdapter() ?: return
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        device?.read(svc, char, timeout) { response ->
            run {
                if (response.success) {
                    val onSuccessWVal = bleCtx.Get<T_ON_SUCCESS_W_VAL>(KEY_ON_SUCCESS)
                    onSuccessWVal?.invoke(response.value)
                } else {
                    val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
                    onFail?.invoke(response.value)
                }
            }
        }
    }

    override fun write(ctx: Context, bleCtx: BLEContext, deviceId: String, svc: UUID, char: UUID, value: String, writeType: Int, timeout: Long) {
        assertBLEAdapter() ?: return
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        device?.write(svc, char, value, writeType, timeout) { response ->
            run {
                if (response.success) {
                    val onSuccess = bleCtx.Get<T_ON_SUCCESS>(KEY_ON_SUCCESS)
                    onSuccess?.invoke()
                } else {
                    val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
                    onFail?.invoke(response.value)
                }
            }
        }
    }

    override fun readDescriptor(
        ctx: Context,
        bleCtx: BLEContext,
        deviceId: String,
        descriptor: Triple<UUID, UUID, UUID>,
        timeout: Long
    ) {
        assertBLEAdapter() ?: return
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        device?.readDescriptor(descriptor.first, descriptor.second, descriptor.third, timeout) { response ->
            run {
                if (response.success) {
                    val onSuccessWVal = bleCtx.Get<T_ON_SUCCESS_W_VAL>(KEY_ON_SUCCESS)
                    onSuccessWVal?.invoke(response.value)
                } else {
                    val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
                    onFail?.invoke(response.value)
                }
            }
        }
    }

    override fun writeDescriptor(
        ctx: Context,
        bleCtx: BLEContext,
        deviceId: String,
        descriptor: Triple<UUID, UUID, UUID>,
        value: String,
        timeout: Long
    ) {
        assertBLEAdapter() ?: return
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        device?.writeDescriptor(descriptor.first, descriptor.second, descriptor.third, value, timeout) { response ->
            run {
                if (response.success) {
                    val onSuccess = bleCtx.Get<T_ON_SUCCESS>(KEY_ON_SUCCESS)
                    onSuccess?.invoke()
                } else {
                    val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
                    onFail?.invoke(response.value)
                }
            }
        }
    }

    override fun startNotifications(ctx: Context, bleCtx: BLEContext, deviceId: String, char: Pair<UUID, UUID>) {
        assertBLEAdapter() ?: {
            val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
            onFail?.invoke("No BLE Adapter")
        }
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        device?.setNotifications(char.first, char.second, true, { response ->
            run {
                val key = "notification|${device.getId()}|${char.first}|${char.second}"
                val onNotify = bleCtx.Get<T_ON_NOTIFY>(KEY_ON_NOTIFY)
                try {
                    onNotify?.invoke(key, response.value, System.currentTimeMillis())
                } catch (e: ConcurrentModificationException) {
                    debug("Error in notifyListeners: ${e.localizedMessage}")
                }
            }
        }) { response ->
            run {
                if (response.success) {
                    val onSuccess = bleCtx.Get<T_ON_SUCCESS>(KEY_ON_SUCCESS)
                    onSuccess?.invoke()
                } else {
                    val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
                    onFail?.invoke(response.value)
                }
            }
        }
    }

    override fun stopNotifications(ctx: Context, bleCtx: BLEContext, deviceId: String, char: Pair<UUID, UUID>) {
        assertBLEAdapter() ?: return
        val device = getOrCreateDevice(ctx, bleCtx, deviceId)
        device?.setNotifications(char.first, char.second, false, null) { response ->
            run {
                if (response.success) {
                    val onSuccess = bleCtx.Get<T_ON_SUCCESS>(KEY_ON_SUCCESS)
                    onSuccess?.invoke()
                } else {
                    val onFail = bleCtx.Get<T_ON_FAIL>(KEY_ON_FAIL)
                    onFail?.invoke(response.value)
                }
            }
        }
    }
}