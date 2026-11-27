package com.github.macsinspace.bleunlockcompanion

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import com.github.macsinspace.bleunlockcompanion.core.R

/**
 * Foreground service that keeps a BLE advertising set + GATT challenge server alive.
 * Advertising is offloaded to the Bluetooth controller; the CPU only wakes to rotate
 * the token (every WINDOW_SECONDS via an idle-allowed alarm) and to answer challenges.
 *
 * On a watch it advertises at a lower rate, and stops advertising / refuses challenges
 * while the watch is off the wrist (low-latency off-body sensor), so a watch left on
 * the desk does not count as presence.
 */
@SuppressLint("MissingPermission") // permissions are checked in MainActivity before start
class CompanionService : Service() {
    companion object {
        const val TAG = "BLEUnlockCompanion"
        private const val CHANNEL = "status"
        @Volatile var instance: CompanionService? = null
        @Volatile var status: String = "Stopped"
            private set

        fun start(context: Context) {
            KeyStorage.setEnabled(context, true)
            context.startForegroundService(Intent(context, CompanionService::class.java))
        }

        fun stop(context: Context) {
            KeyStorage.setEnabled(context, false)
            context.stopService(Intent(context, CompanionService::class.java))
        }
    }

    private lateinit var btManager: BluetoothManager
    private var advSet: AdvertisingSet? = null
    private var advCallback: AdvertisingSetCallback? = null
    private var gattServer: BluetoothGattServer? = null
    private val pendingResponses = ConcurrentHashMap<String, ByteArray>()
    private var currentWindow = -1L
    private val isWatch by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH) }
    /** null = unknown / no sensor (treated as on-body). */
    @Volatile private var onBody: Boolean? = null
    private var sensorManager: SensorManager? = null

    private val offBodyListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val on = e.values.firstOrNull()?.let { it >= 1f } ?: return
            if (on == onBody) return
            onBody = on
            Log.i(TAG, "on-body=$on")
            if (on) startBle() else stopBle("Off wrist — paused")
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> refreshToken("screen on")
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> startBle()
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> stopBle("Bluetooth off")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        btManager = getSystemService(BluetoothManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Companion status", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        registerReceiver(systemReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        })
        if (isWatch) {
            val sm = getSystemService(SensorManager::class.java)
            val sensor = sm?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true)
            if (sensor != null) {
                sensorManager = sm
                sm.registerListener(offBodyListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            } else {
                Log.w(TAG, "no off-body sensor; wrist detection disabled")
            }
        }
        startBle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(systemReceiver)
        sensorManager?.unregisterListener(offBodyListener)
        cancelAlarm()
        stopBle("Stopped")
        instance = null
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle("BLEUnlock Companion")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun setStatus(s: String) {
        status = s
        Log.i(TAG, s)
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(s))
    }

    // MARK: BLE

    private fun startBle() {
        val key = KeyStorage.key()
        val keyId = KeyStorage.keyId(this)
        if (key == null || keyId == null) { setStatus("Not paired"); return }
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) { setStatus("Waiting for Bluetooth"); return }
        if (onBody == false) { setStatus("Off wrist — paused"); return }
        if (advSet != null || advCallback != null) return

        openGattServer()

        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            // ~250 ms on a phone; ~1 s on a watch to save its much smaller battery.
            .setInterval(if (isWatch) AdvertisingSetParameters.INTERVAL_HIGH else AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        val advData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Protocol.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        currentWindow = Protocol.window()
        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS && set != null) {
                    advSet = set
                    setStatus("Advertising (tx $txPower dBm)")
                } else {
                    advCallback = null
                    setStatus("Advertising failed: $status")
                }
            }
            override fun onAdvertisingSetStopped(set: AdvertisingSet?) { Log.i(TAG, "advertising set stopped") }
            override fun onScanResponseDataSet(set: AdvertisingSet?, status: Int) {
                Log.i(TAG, "scan response updated: status=$status window=$currentWindow")
            }
        }
        advCallback = cb
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) { advCallback = null; setStatus("BLE advertising unsupported"); return }
        adv.startAdvertisingSet(params, advData, scanResponse(key, keyId), null, null, cb)
        scheduleAlarm()
    }

    private fun stopBle(reason: String) {
        advCallback?.let { cb -> btManager.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb) }
        advCallback = null
        advSet = null
        gattServer?.close()
        gattServer = null
        pendingResponses.clear()
        setStatus(reason)
    }

    private fun scanResponse(key: javax.crypto.SecretKey, keyId: ByteArray): AdvertiseData =
        AdvertiseData.Builder()
            .addServiceData(ParcelUuid(Protocol.SERVICE_UUID), Protocol.advPayload(key, keyId, currentWindow))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

    /** Rotate the advertised token if the time window changed. */
    fun refreshToken(reason: String) {
        val w = Protocol.window()
        val set = advSet
        if (set != null && w != currentWindow) {
            val key = KeyStorage.key() ?: return
            val keyId = KeyStorage.keyId(this) ?: return
            currentWindow = w
            set.setScanResponseData(scanResponse(key, keyId))
            Log.i(TAG, "token rotated ($reason) window=$w")
        } else if (set == null && KeyStorage.isEnabled(this)) {
            startBle()
        }
        scheduleAlarm()
    }

    private fun alarmIntent(): PendingIntent =
        PendingIntent.getBroadcast(this, 0, Intent(this, TokenAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun scheduleAlarm() {
        val windowMs = Protocol.WINDOW_SECONDS * 1000
        val untilNext = windowMs - (System.currentTimeMillis() % windowMs) + 2000
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + untilNext, alarmIntent()
        )
    }

    private fun cancelAlarm() = getSystemService(AlarmManager::class.java).cancel(alarmIntent())

    // MARK: GATT challenge server

    private fun openGattServer() {
        if (gattServer != null) return
        val server = btManager.openGattServer(this, gattCallback) ?: run { Log.w(TAG, "openGattServer failed"); return }
        val svc = BluetoothGattService(Protocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                Protocol.CHALLENGE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        server.addService(svc)
        gattServer = server
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "central ${device.address} state=$newState")
            if (newState == BluetoothGatt.STATE_DISCONNECTED) pendingResponses.remove(device.address)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val server = gattServer ?: return
            if (ch.uuid != Protocol.CHALLENGE_UUID || preparedWrite || offset != 0 || value?.size != Protocol.NONCE_LEN) {
                if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            val key = KeyStorage.key()
            if (key == null || onBody == false) {
                if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            pendingResponses[device.address] = Protocol.challengeResponse(key, value)
            Log.i(TAG, "challenge answered for ${device.address}")
            if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic
        ) {
            val server = gattServer ?: return
            val resp = pendingResponses[device.address]
            if (ch.uuid != Protocol.CHALLENGE_UUID || resp == null || offset > resp.size) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
                return
            }
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, resp.copyOfRange(offset, resp.size))
            if (offset == 0) pendingResponses.remove(device.address) // single use
        }
    }
}

class TokenAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CompanionService.instance?.refreshToken("alarm")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (KeyStorage.isEnabled(context) && KeyStorage.isPaired(context)) {
            context.startForegroundService(Intent(context, CompanionService::class.java))
        }
    }
}
