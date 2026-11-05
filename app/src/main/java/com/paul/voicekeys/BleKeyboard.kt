@file:Suppress("MissingPermission", "DEPRECATION")

package com.paul.voicekeys

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/** Standard HID-over-GATT keyboard, independent of Android's classic HID service. */
class BleKeyboard(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val onState: (Boolean, BluetoothDevice?, String) -> Unit,
    private val onError: (String) -> Unit,
    private val onHost: (BluetoothDevice) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var advertiserCallback: AdvertiseCallback? = null
    private var bondReceiver: BroadcastReceiver? = null
    private var generation = 0
    private var active = false
    private var ready = false
    private var advertising = false
    private var pairingOpen = false
    private var selectedAddress = ""
    private var peer: BluetoothDevice? = null
    private var protocol = 1
    private var peerMtu = 23
    private var suspended = false
    private var reportSubscribed = false
    private var bootSubscribed = false
    private var input: BluetoothGattCharacteristic? = null
    private var bootInput: BluetoothGattCharacteristic? = null
    private var activeReport = ByteArray(8)
    private val notifications = ArrayDeque<ByteArray>()
    private var inFlight = false
    private val setupTimeout = Runnable { fail("Android did not publish the BLE keyboard services. Tap Retry keyboard setup.") }
    private val notificationTimeout = Runnable { fail("BLE keyboard transmission stalled. Reconnect device B.") }

    fun start(address: String) {
        if (active) return
        selectedAddress = address
        active = true
        ready = false
        val session = ++generation
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) { fail("This phone did not provide Bluetooth LE advertising."); return }
        state("Publishing BLE keyboard services…")
        val services = services()
        var added = 0
        val callback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) = post(session) {
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Android rejected a BLE keyboard service ($status)."); return@post }
                Log.i(TAG, "BLE service added: ${service.uuid}")
                added++
                if (added < services.size) {
                    if (server?.addService(services[added]) != true) fail("Could not add the next BLE keyboard service.")
                } else {
                    ready = true
                    main.removeCallbacks(setupTimeout)
                    advertise()
                }
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = post(session) {
                Log.i(TAG, "BLE connection: state=$newState status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (peer != null && peer?.address != device.address ||
                        !pairingOpen && selectedAddress.isNotBlank() && selectedAddress != device.address) {
                        server?.cancelConnection(device)
                        return@post
                    }
                    peer = device
                    peerMtu = 23
                    protocol = 1; suspended = false; reportSubscribed = false; bootSubscribed = false
                    clearNotifications()
                    state("${device.name ?: "Device B"} connected; waiting for secure keyboard setup…")
                } else if (peer?.address == device.address) {
                    peer = null
                    reportSubscribed = false; bootSubscribed = false
                    clearNotifications()
                    state("BLE keyboard disconnected. Reconnect from B's Bluetooth settings.")
                    advertiserCallback?.let { adapter.bluetoothLeAdvertiser?.stopAdvertising(it) }
                    advertiserCallback = null; advertising = false
                    advertise()
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = post(session) {
                if (peer?.address == device.address) peerMtu = mtu.coerceIn(23, 517)
                Log.i(TAG, "BLE MTU: $mtu")
            }

            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                characteristic: BluetoothGattCharacteristic) = post(session) {
                val value = when (characteristic.uuid) {
                    uuid(0x2a4d), uuid(0x2a22) -> if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) activeReport else byteArrayOf(0)
                    uuid(0x2a4e) -> byteArrayOf(protocol.toByte())
                    uuid(0x2a19) -> byteArrayOf(context.getSystemService(BatteryManager::class.java)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100).toByte())
                    else -> characteristic.value ?: byteArrayOf()
                }
                Log.i(TAG, "BLE read ${characteristic.uuid}: offset=$offset total=${value.size}")
                respondRead(device, requestId, offset, value)
            }

            override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                descriptor: BluetoothGattDescriptor) = post(session) {
                val value = if (descriptor.uuid == CCCD) {
                    val enabled = when (descriptor.characteristic.uuid) {
                        uuid(0x2a4d) -> reportSubscribed
                        uuid(0x2a22) -> bootSubscribed
                        else -> false
                    }
                    if (peer?.address == device.address && enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                } else descriptor.value ?: byteArrayOf()
                respondRead(device, requestId, offset, value)
            }

            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
                characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean,
                offset: Int, value: ByteArray) = post(session) {
                var status = BluetoothGatt.GATT_SUCCESS
                Log.i(TAG, "BLE write ${characteristic.uuid}: offset=$offset length=${value.size}")
                if (preparedWrite) status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                else if (offset != 0) status = BluetoothGatt.GATT_INVALID_OFFSET
                else if (peer?.address != device.address) status = BluetoothGatt.GATT_FAILURE
                else when (characteristic.uuid) {
                    uuid(0x2a4e) -> {
                        if (value.size != 1 || value[0].toInt() !in 0..1) status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                        else { protocol = value[0].toInt(); publishPeer() }
                    }
                    uuid(0x2a4c) -> {
                        if (value.size != 1 || value[0].toInt() !in 0..1) status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                        else {
                            val wasSuspended = suspended
                            suspended = value[0].toInt() == 0
                            if (suspended) { notifications.clear(); activeReport = ByteArray(8) }
                            if (wasSuspended && !suspended) releaseKeys()
                            publishPeer()
                        }
                    }
                    uuid(0x2a4d), uuid(0x2a32) -> {
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 || value.size != 1)
                            status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                    }
                    else -> status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                }
                if (responseNeeded) server?.sendResponse(device, requestId, status, offset, null)
            }

            override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
                descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean,
                offset: Int, value: ByteArray) = post(session) {
                var status = BluetoothGatt.GATT_SUCCESS
                if (preparedWrite) status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                else if (offset != 0) status = BluetoothGatt.GATT_INVALID_OFFSET
                else if (descriptor.uuid != CCCD || peer?.address != device.address) status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                else if (!value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) &&
                    !value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                else {
                    val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    when (descriptor.characteristic.uuid) {
                        uuid(0x2a4d) -> reportSubscribed = enabled
                        uuid(0x2a22) -> bootSubscribed = enabled
                    }
                    Log.i(TAG, "BLE notifications: report=$reportSubscribed boot=$bootSubscribed bonded=${device.bondState == BluetoothDevice.BOND_BONDED}")
                    publishPeer()
                }
                if (responseNeeded) server?.sendResponse(device, requestId, status, offset, null)
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) = post(session) {
                if (peer?.address != device.address || !inFlight) return@post
                Log.i(TAG, "BLE keyboard notification completed: status=$status")
                main.removeCallbacks(notificationTimeout)
                inFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("BLE keyboard report failed ($status). Reconnect B."); return@post }
                sendNext()
            }
        }
        bondReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) post(session) { publishPeer() }
            }
        }.also { ContextCompat.registerReceiver(context, it, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            android.Manifest.permission.BLUETOOTH_CONNECT, main, ContextCompat.RECEIVER_EXPORTED) }
        server = manager.openGattServer(context, callback)
        if (server == null || server?.addService(services.first()) != true) {
            fail("Android could not open the BLE keyboard server.")
            return
        }
        main.postDelayed(setupTimeout, 10000)
    }

    fun selectHost(device: BluetoothDevice) {
        if (peer?.address != device.address && peer != null) {
            server?.cancelConnection(peer!!)
            peer = null; reportSubscribed = false; bootSubscribed = false
            clearNotifications()
        }
        selectedAddress = device.address
        beginPairing()
    }

    fun beginPairing() {
        pairingOpen = true
        if (ready) advertise()
    }

    private fun advertise() {
        if (!active || !ready) return
        if (advertising) { state(pairingMessage()); return }
        val session = generation
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = post(session) {
                advertising = true
                Log.i(TAG, "BLE HID advertising started")
                state(pairingMessage())
            }
            override fun onStartFailure(errorCode: Int) = post(session) { fail("BLE keyboard advertising failed ($errorCode).") }
        }
        advertiserCallback = callback
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(uuid(0x1812))).build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        adapter.bluetoothLeAdvertiser?.startAdvertising(settings, data, scan, callback)
    }

    private fun pairingMessage() = if (pairingOpen || selectedAddress.isBlank())
        "BLE keyboard ready. On B, use Pair new device and select ${adapter.name ?: "this phone"}. Accept pairing on both devices."
    else "BLE keyboard ready. Reconnect from B's Bluetooth settings, or tap Allow BLE pairing to pair a new host."
    private fun usable(): Boolean = peer?.bondState == BluetoothDevice.BOND_BONDED &&
        (if (protocol == 0) bootSubscribed else reportSubscribed) && !suspended

    private fun publishPeer() {
        if (!active) return
        if (usable()) {
            pairingOpen = false
            peer?.let { selectedAddress = it.address; onHost(it) }
            state("BLE keyboard connected and ready.")
        } else state(if (suspended) "B suspended the BLE keyboard. Voice control is OFF."
            else "Waiting for B to pair securely and enable keyboard input…")
    }

    fun sendReport(report: ByteArray): Boolean {
        if (!active || !usable() || report.size != 8 || notifications.size >= 32) return false
        activeReport = report.copyOf()
        notifications.addLast(report.copyOf())
        sendNext()
        return active
    }

    /** Drop unsent key-downs; let an already in-flight packet finish, then release. */
    fun releaseKeys() {
        notifications.clear()
        activeReport = ByteArray(8)
        if (active && usable()) { notifications.addLast(activeReport.copyOf()); sendNext() }
    }

    private fun sendNext() {
        if (!active || inFlight || notifications.isEmpty() || !usable()) return
        val device = peer ?: return
        val characteristic = (if (protocol == 0) bootInput else input) ?: return
        val report = notifications.removeFirst()
        inFlight = true
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            server?.notifyCharacteristicChanged(device, characteristic, false, report) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = report
            server?.notifyCharacteristicChanged(device, characteristic, false) == true
        }
        if (!accepted) { inFlight = false; fail("Android could not send a BLE keyboard report."); return }
        main.postDelayed(notificationTimeout, 2000)
    }

    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray) {
        if (offset !in 0..value.size) server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
        else server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
            value.copyOfRange(offset, minOf(value.size, offset + peerMtu - 1)))
    }

    private fun state(message: String) { onState(ready, if (usable()) peer else null, message) }
    private fun post(session: Int, action: () -> Unit) { main.post { if (active && generation == session) action() } }
    private fun clearNotifications() { notifications.clear(); inFlight = false; activeReport = ByteArray(8); main.removeCallbacks(notificationTimeout) }
    private fun fail(message: String) {
        Log.w(TAG, message)
        stop()
        onState(false, null, message)
        onError(message)
    }

    fun stop() {
        active = false; ready = false; pairingOpen = false; generation++
        main.removeCallbacks(setupTimeout)
        clearNotifications()
        advertiserCallback?.let { runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        advertiserCallback = null; advertising = false
        bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bondReceiver = null
        peer?.let { runCatching { server?.cancelConnection(it) } }
        peer = null
        server?.close(); server = null
        reportSubscribed = false; bootSubscribed = false
    }

    private fun services(): List<BluetoothGattService> {
        fun characteristic(id: Int, properties: Int, permissions: Int, value: ByteArray) =
            BluetoothGattCharacteristic(uuid(id), properties, permissions).also { it.value = value }
        fun cccd() = BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or
            BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED).also { it.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE }
        val read = BluetoothGattCharacteristic.PROPERTY_READ
        val write = BluetoothGattCharacteristic.PROPERTY_WRITE
        val noResponse = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val notify = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        val encryptedRead = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        val encryptedWrite = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        val hid = BluetoothGattService(uuid(0x1812), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        hid.addCharacteristic(characteristic(0x2a4a, read, BluetoothGattCharacteristic.PERMISSION_READ, byteArrayOf(0x11, 0x01, 0, 0x02)))
        hid.addCharacteristic(characteristic(0x2a4b, read, encryptedRead, BluetoothKeyboard.DESCRIPTOR))
        hid.addCharacteristic(characteristic(0x2a4e, read or noResponse, encryptedRead or encryptedWrite, byteArrayOf(1)))
        hid.addCharacteristic(characteristic(0x2a4c, noResponse, encryptedWrite, byteArrayOf(0)))
        input = characteristic(0x2a4d, read or notify, encryptedRead, ByteArray(8)).also {
            it.addDescriptor(cccd())
            it.addDescriptor(BluetoothGattDescriptor(uuid(0x2908), BluetoothGattDescriptor.PERMISSION_READ).also { d -> d.value = byteArrayOf(1, 1) })
            hid.addCharacteristic(it)
        }
        hid.addCharacteristic(characteristic(0x2a4d, read or write or noResponse, encryptedRead or encryptedWrite, byteArrayOf(0)).also {
            it.addDescriptor(BluetoothGattDescriptor(uuid(0x2908), BluetoothGattDescriptor.PERMISSION_READ).also { d -> d.value = byteArrayOf(1, 2) })
        })
        bootInput = characteristic(0x2a22, read or notify, encryptedRead, ByteArray(8)).also { it.addDescriptor(cccd()); hid.addCharacteristic(it) }
        hid.addCharacteristic(characteristic(0x2a32, read or write or noResponse, encryptedRead or encryptedWrite, byteArrayOf(0)))
        val battery = BluetoothGattService(uuid(0x180f), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        battery.addCharacteristic(characteristic(0x2a19, read, BluetoothGattCharacteristic.PERMISSION_READ, byteArrayOf(100)))
        val info = BluetoothGattService(uuid(0x180a), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        info.addCharacteristic(characteristic(0x2a29, read, BluetoothGattCharacteristic.PERMISSION_READ, "VoiceKeys".toByteArray()))
        info.addCharacteristic(characteristic(0x2a24, read, BluetoothGattCharacteristic.PERMISSION_READ, "Android BLE keyboard".toByteArray()))
        // Unassigned development vendor/product identity; no third-party vendor is impersonated.
        info.addCharacteristic(characteristic(0x2a50, read, BluetoothGattCharacteristic.PERMISSION_READ, byteArrayOf(1, 0, 0, 1, 0, 1, 0)))
        return listOf(hid, battery, info)
    }

    companion object {
        private const val TAG = "VoiceKeysBluetooth"
        fun uuid(id: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(id))
        private val CCCD = uuid(0x2902)
    }
}
