package com.tsi.plugins.bluetoothle

import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.*

/**
 * Author runs the BLE stack in a foreground Service, which can be bound by the Capacitor Plugin
 */
class Author : Service() {

    companion object {
        private val TAG = Author::class.java.simpleName

        var running = true

        val channelId = "$TAG::Foreground"
    }

    val bleRunner = BLERunner()
    lateinit var db: MessageDatabase

    inner class Local : Binder(), BLESystem {
        fun getBLERunner(): BLERunner = this@Author.bleRunner
        override fun initialize(ctx: Context, displayStrings: DisplayStrings): InitStatus {
            CoroutineScope(Dispatchers.IO).launch {
                debug("Initializing DB")
                initDBAsync().await()
                clearCacheAsync()
            }
            return this@Author.bleRunner.initialize(ctx, displayStrings)
        }

        suspend fun scrub() {
            clearCacheAsync().await()
        }

        fun catchupAsync(endTime: Long): Deferred<List<Message>> =
            CoroutineScope(Dispatchers.IO).async {
                debug("Reading messages from DB")
                val messages = db.messageDao().getFrom(endTime)
                debug("Removing DB entries")
                clearCacheAsync().await()
                debug("DB calls completed, returning ${messages.size} messages")
                return@async messages
        }

        override fun isEnabled(): Boolean {
            return this@Author.bleRunner.isEnabled()
        }

        override fun enable(ctx: Context): Boolean? {
            return this@Author.bleRunner.enable(ctx)
        }

        override fun disable(): Boolean? {
            return this@Author.bleRunner.disable()
        }

        override fun startEnabledNotifications(ctx: Context, bleCtx: BLEContext) {
            val onOGNotify = bleCtx.Get<T_ON_NOTIFY>(BLERunner.KEY_ON_NOTIFY)

            val onNotify = fun (key: String, value: Any, timestamp: Long) {
                val message = Message(timestamp, key, value as String)

                db.messageDao().insertAll(message)

                onOGNotify?.invoke(key, value, timestamp)
            }

            bleCtx.Set(BLERunner.KEY_ON_NOTIFY, onNotify)

            return this@Author.bleRunner.startEnabledNotifications(ctx, bleCtx)
        }

        override fun stopEnabledNotifications(ctx: Context) {
            return this@Author.bleRunner.stopEnabledNotifications(ctx)
        }

        override fun requestDevice(
            ctx: Context,
            bleCtx: BLEContext,
            scanFilters: List<ScanFilter>,
            scanSettings: ScanSettings,
            namePrefix: String
        ) {
            return this@Author.bleRunner.requestDevice(ctx, bleCtx, scanFilters, scanSettings, namePrefix)
        }

        override fun requestLEScan(
            ctx: Context,
            bleCtx: BLEContext,
            scanFilters: List<ScanFilter>,
            scanSettings: ScanSettings,
            namePrefix: String,
            allowDuplicates: Boolean
        ) {
            return this@Author.bleRunner.requestLEScan(ctx, bleCtx, scanFilters, scanSettings, namePrefix, allowDuplicates)
        }

        override fun stopLEScan() {
            return this@Author.bleRunner.stopLEScan()
        }

        override fun getConnectedDevices(ctx: Context): List<BluetoothDevice> {
            return this@Author.bleRunner.getConnectedDevices(ctx)
        }

        override fun connect(ctx: Context, bleCtx: BLEContext, deviceId: String, timeout: Long) {
            return this@Author.bleRunner.connect(ctx, bleCtx, deviceId, timeout)
        }

        override fun createBond(ctx: Context, bleCtx: BLEContext, deviceId: String) {
            return this@Author.bleRunner.createBond(ctx, bleCtx, deviceId)
        }

        override fun isBonded(ctx: Context, bleCtx: BLEContext, deviceId: String): Boolean {
            return this@Author.bleRunner.isBonded(ctx, bleCtx, deviceId)
        }

        override fun disconnect(ctx: Context, bleCtx: BLEContext, deviceId: String, timeout: Long) {
            return this@Author.bleRunner.disconnect(ctx, bleCtx, deviceId, timeout)
        }

        override fun getServices(
            ctx: Context,
            bleCtx: BLEContext,
            deviceId: String
        ): MutableList<BluetoothGattService> {
            return this@Author.bleRunner.getServices(ctx, bleCtx, deviceId)
        }

        override fun discoverServices(
            ctx: Context,
            bleCtx: BLEContext,
            deviceId: String,
            timeout: Long
        ) {
            return this@Author.bleRunner.discoverServices(ctx, bleCtx, deviceId, timeout)
        }

        override fun getMtu(ctx: Context, bleCtx: BLEContext, deviceId: String): Int {
            return this@Author.bleRunner.getMtu(ctx, bleCtx, deviceId)
        }

        override fun requestConnectionPriority(
            ctx: Context,
            bleCtx: BLEContext,
            deviceId: String,
            priority: Int
        ) {
            return this@Author.bleRunner.requestConnectionPriority(ctx, bleCtx, deviceId, priority)
        }

        override fun readRssi(ctx: Context, bleCtx: BLEContext, deviceId: String, timeout: Long) {
            return this@Author.bleRunner.readRssi(ctx, bleCtx, deviceId, timeout)
        }

        override fun read(
            ctx: Context,
            bleCtx: BLEContext,
            deviceId: String,
            svc: UUID,
            char: UUID,
            timeout: Long
        ) {
            return this@Author.bleRunner.read(ctx, bleCtx, deviceId, svc, char, timeout)
        }

        override fun write(
            ctx: Context,
            bleCtx: BLEContext,
            deviceId: String,
            svc: UUID,
            char: UUID,
            value: String,
            writeType: Int,
            timeout: Long
        ) {
            return this@Author.bleRunner.write(ctx, bleCtx, deviceId, svc, char, value, writeType, timeout)
        }

        override fun readDescriptor(
            ctx: Context,
            bleCtx: BLEContext,
            deviceId: String,
            descriptor: Triple<UUID, UUID, UUID>,
            timeout: Long
        ) {
            return this@Author.bleRunner.readDescriptor(ctx, bleCtx, deviceId, descriptor, timeout)
        }

        override fun writeDescriptor(
            ctx: Context,
            bleCtx: BLEContext,
            deviceId: String,
            descriptor: Triple<UUID, UUID, UUID>,
            value: String,
            timeout: Long
        ) {
            return this@Author.bleRunner.writeDescriptor(ctx, bleCtx, deviceId, descriptor, value, timeout)
        }

        override fun startNotifications(
            ctx: Context,
            bleCtx: BLEContext,
            deviceId: String,
            char: Pair<UUID, UUID>
        ) {
            val onOGNotify = bleCtx.Get<T_ON_NOTIFY>(BLERunner.KEY_ON_NOTIFY)

            val onNotify = fun (key: String, value: Any, timestamp: Long) {
                val message = Message(timestamp, key, value as String)

                db.messageDao().insertAll(message)

                onOGNotify?.invoke(key, value, timestamp)
            }

            bleCtx.Set(BLERunner.KEY_ON_NOTIFY, onNotify)

            return this@Author.bleRunner.startNotifications(ctx, bleCtx, deviceId, char)
        }

        override fun stopNotifications(
            ctx: Context,
            bleCtx: BLEContext,
            deviceId: String,
            char: Pair<UUID, UUID>
        ) {
            return this@Author.bleRunner.stopNotifications(ctx, bleCtx, deviceId, char)
        }
    }

    private fun debug(msg: String) {
        Log.d(TAG, msg)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val contentTitle = intent?.getStringExtra("notif-content-title")
        val contentText = intent?.getStringExtra("notif-content-text")
        val icon = intent?.getIntExtra("notif-icon", android.R.drawable.stat_sys_data_bluetooth) ?: android.R.drawable.stat_sys_data_bluetooth

        debug("Starting Foreground Service")
        debug("Title: $contentTitle; Text: $contentText")

        val notifIntent = Intent(this, Waiter::class.java)
        val pendingIntent = PendingIntent.getActivity(this.applicationContext, 0, notifIntent, PendingIntent.FLAG_IMMUTABLE)

        val foregroundNotif = NotificationCompat.Builder(this.applicationContext, channelId)
        foregroundNotif.setOngoing(true)

        foregroundNotif
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)

        val notif = foregroundNotif.build()

        startForeground(1, notif)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return Local()
    }

    override fun onDestroy() {
        super.onDestroy()

        running = false
    }

    fun initDBAsync() = CoroutineScope(Dispatchers.IO).async {
        db = Room.databaseBuilder(
            applicationContext,
            MessageDatabase::class.java, "messageDB"
        ).build()
    }

    fun clearCacheAsync(): Deferred<Unit> = CoroutineScope(Dispatchers.IO).async {
        debug("Clearing all DB tables")
        db.clearAllTables()
    }
}