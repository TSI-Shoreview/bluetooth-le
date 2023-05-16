package com.tsi.plugins.bluetoothle

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import java.util.UUID

interface BLESystem {
    fun initialize(ctx: Context, displayStrings: DisplayStrings): InitStatus
    fun isEnabled(): Boolean
    fun enable(ctx: Context): Boolean?
    fun disable(): Boolean?
    fun startEnabledNotifications(ctx: Context, bleCtx: BLEContext)
    fun stopEnabledNotifications(ctx: Context)
    fun requestDevice(ctx: Context, bleCtx: BLEContext, scanFilters: List<ScanFilter>, scanSettings: ScanSettings, namePrefix: String)
    fun requestLEScan(ctx: Context, bleCtx: BLEContext, scanFilters: List<ScanFilter>, scanSettings: ScanSettings, namePrefix: String, allowDuplicates: Boolean)
    fun stopLEScan()
    fun getConnectedDevices(ctx: Context): List<BluetoothDevice>
    fun connect(ctx: Context, bleCtx: BLEContext, deviceId: String, timeout: Long)
    fun createBond(ctx: Context, bleCtx: BLEContext, deviceId: String)
    fun isBonded(ctx: Context, bleCtx: BLEContext, deviceId: String): Boolean
    fun disconnect(ctx: Context, bleCtx: BLEContext, deviceId: String, timeout: Long)
    fun getServices(ctx: Context, bleCtx: BLEContext, deviceId: String): MutableList<BluetoothGattService>
    fun discoverServices(ctx: Context, bleCtx: BLEContext, deviceId: String, timeout: Long)
    fun getMtu(ctx: Context, bleCtx: BLEContext, deviceId: String): Int
    fun requestConnectionPriority(ctx: Context, bleCtx: BLEContext, deviceId: String, priority: Int)
    fun readRssi(ctx: Context, bleCtx: BLEContext, deviceId: String, timeout: Long)
    fun read(ctx: Context, bleCtx: BLEContext, deviceId: String, svc: UUID, char: UUID, timeout: Long)
    fun write(ctx: Context, bleCtx: BLEContext, deviceId: String, svc: UUID, char: UUID, value: String, writeType: Int, timeout: Long)
    fun readDescriptor(ctx: Context, bleCtx: BLEContext, deviceId: String, descriptor: Triple<UUID, UUID, UUID>, timeout: Long)
    fun writeDescriptor(ctx: Context, bleCtx: BLEContext, deviceId: String, descriptor: Triple<UUID, UUID, UUID>, value: String, timeout: Long)
    fun startNotifications(ctx: Context, bleCtx: BLEContext, deviceId: String, char: Pair<UUID, UUID>)
    fun stopNotifications(ctx: Context, bleCtx: BLEContext, deviceId: String, char: Pair<UUID, UUID>)
}