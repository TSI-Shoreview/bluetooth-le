package com.capacitorjs.community.plugins.bluetoothle

import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

class FSAuthor : Service() {
    private var device: Device? = null

    companion object {
        var running = true
        val NUSService = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUSWrite = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUSRead = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        device = intent?.getSerializableExtra("bt-dev") as Device
        val svc = UUID.fromString(intent?.getStringExtra("svc")) ?: return 1
        val char = UUID.fromString(intent?.getStringExtra("char")) ?: return 1

        val notifIntent = Intent(this, Waiter::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notifIntent, PendingIntent.FLAG_IMMUTABLE)

        val foregroundNotif = NotificationCompat.Builder(this, "runForever")
        foregroundNotif.setOngoing(true)

        foregroundNotif
            .setContentTitle("OmniTrak")
            .setContentText("Running for BLE stuff")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)

        val notif = foregroundNotif.build()

        startForeground(1, notif)

        Thread(Runnable {
            while (running) {
                Log.d("TSI::BLE::FS", "Waiting...")
                device!!.write(svc, char, "ping", BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, 5000) { _: CallbackResponse -> null }
                Thread.sleep(5000)
            }
        }).start()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    override fun onDestroy() {
        super.onDestroy()

        running = false
    }
}