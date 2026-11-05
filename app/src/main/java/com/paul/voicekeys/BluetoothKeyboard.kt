@file:Suppress("MissingPermission")

package com.paul.voicekeys

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

class BluetoothKeyboard(
    private val context: Context,
    private val onState: (Boolean, String?, String) -> Unit,
    private val onError: (String) -> Unit,
    private val onSent: (OutputKey) -> Unit,
    private var preferBle: Boolean = false,
    private val onHostSelected: (BluetoothDevice) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java).adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var selectedAddress = ""
    private var registered = false
    private var registering = false
    private var requested = false
    private var foreground = false
    private var closed = false
    private var unregistering = false
    private var registrationUncertain = false
    private var registrationAttempts = 0
    private var profileGeneration = 0
    private var pendingDevice: BluetoothDevice? = null
    private var setupStatus = "Waiting for Bluetooth setup."
    private var ble: BleKeyboard? = null
    private var bootProtocol = false
    private var activeReport = ByteArray(8)
    private val queue = KeyPacingQueue()
    private var allowed = false
    val connected: Boolean get() = registered && host != null
    private val pump = Runnable { pumpKeys() }
    private val registerLater = Runnable { register() }
    private val profileTimeout = Runnable {
        if (foreground && hid == null) {
            requested = false
            profileGeneration++
            setupFailed("Android did not expose the classic Bluetooth keyboard service within 8 seconds. In Connections, choose Use BLE keyboard to try the standard Bluetooth Low Energy keyboard connection.")
        }
    }
    private val registrationTimeout = Runnable {
        if (foreground && registering) {
            registering = false
            setupFailed("Android did not confirm keyboard registration within 10 seconds. Tap Retry keyboard setup. Close other remote-keyboard apps on A first.")
        }
    }
    private val unregisterTimeout = Runnable {
        if (foreground && unregistering) {
            unregistering = false
            setupFailed("Android did not reset its keyboard service. Turn Bluetooth off and on on A, then tap Retry keyboard setup.")
        }
    }
    private val connectionTimeout = Runnable {
        if (foreground && host == null) {
            setupFailed("Device B did not connect within 15 seconds. On B, enable keyboard/input access for A, then tap Connect selected B again.")
        }
    }
    private val release = Runnable {
        activeReport = ByteArray(8)
        val device = host
        if (device != null && !sendKeyboardReport(device, activeReport)) {
            fail("Could not release the key; reconnect device B.")
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            if (closed || ble != null) return
            Log.i(TAG, "HID registration callback: registered=$isRegistered, foreground=$foreground")
            handler.removeCallbacks(registrationTimeout)
            registered = isRegistered
            registering = false
            registrationUncertain = false
            if (!isRegistered) { host = null; disarm(); handler.removeCallbacks(connectionTimeout) }
            if (isRegistered && (!foreground || unregistering)) {
                requestUnregister()
                return
            }
            if (isRegistered && pluggedDevice != null && pluggedDevice.address == selectedAddress &&
                hid?.getConnectionState(pluggedDevice) == BluetoothProfile.STATE_CONNECTED) {
                host = pluggedDevice
            }
            if (isRegistered) {
                setupStatus = "Registered — ready to connect to device B."
                publish()
                connectPending()
                return
            }
            val resetCompleted = unregistering
            unregistering = false
            handler.removeCallbacks(unregisterTimeout)
            if (foreground) {
                if (resetCompleted) scheduleRegistration(300)
                else retryRejectedRegistration()
            }
            publish()
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (closed || ble != null) return
            Log.i(TAG, "HID connection callback: state=$state, selected=${device.address == selectedAddress}")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                if (device.address != selectedAddress || !foreground) {
                    hid?.disconnect(device)
                    return
                }
                host = device
                handler.removeCallbacks(connectionTimeout)
                setupStatus = "Keyboard connected."
                bootProtocol = false
                activeReport = ByteArray(8)
                sendKeyboardReport(device, activeReport)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && host?.address == device.address) {
                host = null
                disarm()
                setupStatus = "Keyboard disconnected. Tap Connect selected B to reconnect."
            } else if (state == BluetoothProfile.STATE_CONNECTING && device.address == selectedAddress) {
                setupStatus = "Connecting to ${device.name ?: "device B"}…"
            }
            publish()
        }
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && (id.toInt() == 1 || id.toInt() == 0)) {
                hid?.replyReport(device, type, id, activeReport.copyOf())
            } else if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && (id.toInt() == 1 || id.toInt() == 0)) {
                hid?.replyReport(device, type, id, byteArrayOf(0))
            } else hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT)
                BluetoothHidDevice.ERROR_RSP_SUCCESS else BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }
        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            bootProtocol = protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE
        }
        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            if (host?.address == device.address) { host = null; disarm(); publish() }
        }
    }

    private fun serviceListener(generation: Int) = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (closed || ble != null || generation != profileGeneration) {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
                return
            }
            handler.removeCallbacks(profileTimeout)
            hid = proxy as BluetoothHidDevice
            Log.i(TAG, "Android HID profile opened")
            if (foreground) scheduleRegistration(300)
        }
        override fun onServiceDisconnected(profile: Int) {
            if (closed || ble != null || generation != profileGeneration) return
            hid = null; registered = false; registering = false; requested = false
            unregistering = false; registrationUncertain = false
            cancelSetupTimers()
            host = null; disarm(); publish()
            if (foreground) setupFailed("Android's Bluetooth keyboard service disconnected. Tap Retry keyboard setup.")
        }
    }

    fun enterForeground(address: String) {
        if (closed) return
        if (!foreground) registrationAttempts = 0
        foreground = true
        selectedAddress = address
        if (adapter?.isEnabled != true) {
            setupStatus = "Turn on Bluetooth on device A."
            publish(); return
        }
        if (unregistering) {
            handler.removeCallbacks(unregisterTimeout)
            handler.postDelayed(unregisterTimeout, 5000)
            return
        }
        if (preferBle) { startBle(); return }
        openProfileOrRegister()
    }

    private fun openProfileOrRegister() {
        if (!foreground || closed || ble != null) return
        if (hid != null) { scheduleRegistration(300); return }
        if (!requested) {
            setupStatus = "Opening Android's Bluetooth keyboard service…"
            publish()
            requested = adapter?.getProfileProxy(context, serviceListener(++profileGeneration), BluetoothProfile.HID_DEVICE) == true
            if (!requested) setupFailed("Android did not provide the Bluetooth HID keyboard profile. Check Bluetooth is on, then tap Retry keyboard setup.")
            else handler.postDelayed(profileTimeout, 8000)
        }
    }

    private fun scheduleRegistration(delay: Long) {
        if (!foreground || closed || registering || unregistering) return
        if (registered) { connectPending(); return }
        if (registrationAttempts >= 3) return
        setupStatus = "Registering keyboard with Android…"
        publish()
        handler.removeCallbacks(registerLater)
        // onResume can precede Android updating this process's foreground importance.
        handler.postDelayed(registerLater, delay)
    }

    private fun register() {
        if (registered || registering || unregistering || !foreground || closed) return
        if (adapter?.isEnabled != true) { setupFailed("Turn on Bluetooth on device A, then retry keyboard setup."); return }
        val proxy = hid ?: return
        registering = true
        registrationAttempts++
        setupStatus = "Registering keyboard with Android (attempt $registrationAttempts of 3)…"
        publish()
        Log.i(TAG, "Requesting HID registration, attempt $registrationAttempts")
        val sdp = BluetoothHidDeviceAppSdpSettings("VoiceKeys Keyboard", "Voice command keyboard",
            "VoiceKeys", BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR)
        registrationUncertain = proxy.registerApp(sdp, null, null, context.mainExecutor, callback)
        if (!registrationUncertain) {
            registering = false
            retryRejectedRegistration()
        } else handler.postDelayed(registrationTimeout, 10000)
    }

    private fun retryRejectedRegistration() {
        if (!foreground) return
        if (registrationAttempts < 3) scheduleRegistration(750)
        else setupFailed("Android rejected keyboard registration after 3 attempts. Close any other remote-keyboard apps on A and tap Retry keyboard setup. If it persists, turn Bluetooth off and on on A.")
    }

    fun retryRegistration() {
        if (!foreground || closed) return
        disarm()
        if (preferBle) {
            ble?.stop()
            registered = false; host = null
            startBle()
            return
        }
        registrationAttempts = 0
        cancelSetupTimers()
        if (adapter?.isEnabled != true) { setupFailed("Turn on Bluetooth on device A, then retry keyboard setup."); return }
        if (registered || registering || registrationUncertain || unregistering) requestUnregister()
        else {
            if (hid == null) { requested = false; profileGeneration++ }
            openProfileOrRegister()
        }
    }

    private fun requestUnregister() {
        unregistering = true
        registered = false
        registering = false
        host = null
        disarm()
        setupStatus = "Resetting Android's keyboard registration…"
        publish()
        if (hid?.unregisterApp() == true) {
            handler.removeCallbacks(unregisterTimeout)
            if (foreground) handler.postDelayed(unregisterTimeout, 5000)
        } else {
            unregistering = false
            registrationUncertain = false
            if (foreground) scheduleRegistration(300)
        }
    }

    fun selectAndConnect(device: BluetoothDevice) {
        if (!foreground || closed) return
        disarm()
        host?.takeIf { it.address != device.address }?.let { hid?.disconnect(it) }
        if (host?.address != device.address) host = null
        selectedAddress = device.address
        if (ble != null) { ble?.selectHost(device); return }
        pendingDevice = device
        if (!registered) {
            setupStatus = "${device.name ?: "Device B"} selected. Waiting for keyboard registration…"
            publish()
            if (registrationUncertain && !registering && !unregistering) retryRegistration()
            else if (!registering && !unregistering) {
                registrationAttempts = 0
                openProfileOrRegister()
            }
            return
        }
        connectPending()
    }

    private fun connectPending() {
        if (!registered || !foreground || closed) return
        val device = pendingDevice ?: return
        pendingDevice = null
        if (hid?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
            host = device; setupStatus = "Keyboard connected."; publish()
        } else {
            setupStatus = "Connecting to ${device.name ?: "device B"}…"
            publish()
            if (hid?.connect(device) != true) setupFailed("Connection request failed. On device B, enable keyboard/input access for A and retry.")
            else {
                handler.removeCallbacks(connectionTimeout)
                handler.postDelayed(connectionTimeout, 15000)
            }
        }
    }

    fun bondedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()
    fun arm() { check(connected && foreground); allowed = true }
    fun disarm() {
        allowed = false
        handler.removeCallbacks(pump)
        handler.removeCallbacks(release)
        queue.clear()
        activeReport = ByteArray(8)
        if (ble != null) ble?.releaseKeys()
        else host?.let { device -> runCatching { sendKeyboardReport(device, activeReport) } }
    }
    fun enqueue(key: OutputKey) {
        if (!allowed || !foreground || !connected) return
        try { queue.enqueue(key) } catch (e: IllegalStateException) { fail(e.message ?: "Key queue overflow."); return }
        handler.removeCallbacks(pump)
        pumpKeys()
    }
    fun test(key: OutputKey) {
        if (!connected) { fail("Connect device B before testing."); return }
        arm(); enqueue(key)
    }
    private fun pumpKeys() {
        if (!allowed || !foreground || !connected) return
        val now = SystemClock.elapsedRealtime()
        val delay = queue.waitMillis(now) ?: return
        if (delay > 0) { handler.postDelayed(pump, delay); return }
        val key = queue.take(now)
        activeReport = key.report()
        if (!sendKeyboardReport(host!!, activeReport)) { fail("Keyboard transmission failed."); return }
        onSent(key)
        handler.removeCallbacks(release)
        handler.postDelayed(release, 25)
        if (queue.size > 0) handler.postDelayed(pump, 40)
    }
    private fun reportId() = if (bootProtocol) 0 else 1
    private fun sendKeyboardReport(device: BluetoothDevice, report: ByteArray): Boolean =
        ble?.sendReport(report) ?: (hid?.sendReport(device, reportId(), report) == true)
    private fun publish() { onState(registered, host?.let { it.name ?: it.address }, setupStatus) }
    private fun fail(message: String) { disarm(); onError(message) }
    private fun setupFailed(message: String) {
        setupStatus = message
        Log.w(TAG, message)
        publish()
        fail(message)
    }
    private fun cancelSetupTimers() {
        handler.removeCallbacks(registerLater)
        handler.removeCallbacks(profileTimeout)
        handler.removeCallbacks(registrationTimeout)
        handler.removeCallbacks(unregisterTimeout)
        handler.removeCallbacks(connectionTimeout)
    }

    fun useBleKeyboard() {
        if (!foreground || closed) return
        disarm()
        cancelSetupTimers()
        profileGeneration++
        runCatching { hid?.unregisterApp() }
        hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hid = null; requested = false; registered = false; registering = false
        unregistering = false; registrationUncertain = false; host = null; pendingDevice = null
        preferBle = true
        startBle()
        ble?.beginPairing()
    }

    fun useClassicKeyboard() {
        if (!foreground || closed) return
        disarm()
        ble?.stop(); ble = null
        preferBle = false
        registered = false; host = null; registrationAttempts = 0
        openProfileOrRegister()
    }

    fun advertiseBleKeyboard() { ble?.beginPairing() }

    private fun startBle() {
        val bluetooth = adapter ?: return
        if (ble == null) ble = BleKeyboard(context, bluetooth,
            onState = { ready, device, detail ->
                registered = ready
                host = device
                setupStatus = detail
                publish()
            }, onError = { fail(it) }, onHost = { device ->
                selectedAddress = device.address
                onHostSelected(device)
            })
        ble?.start(selectedAddress)
    }

    fun leaveForeground() {
        foreground = false
        pendingDevice = null
        cancelSetupTimers()
        disarm()
        ble?.stop()
        host?.let { runCatching { hid?.disconnect(it) } }
        host = null
        if (ble == null && (registered || registering || registrationUncertain)) {
            unregistering = runCatching { hid?.unregisterApp() == true }.getOrDefault(false)
        }
        registered = false; registering = false
        setupStatus = "Keyboard setup paused while the app is closed."
        publish()
    }
    fun close() {
        leaveForeground()
        closed = true
        profileGeneration++
        hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hid = null; requested = false
    }

    companion object {
        private const val TAG = "VoiceKeysBluetooth"
        internal val DESCRIPTOR = intArrayOf(
            0x05,0x01, 0x09,0x06, 0xa1,0x01, 0x85,0x01,
            0x05,0x07, 0x19,0xe0, 0x29,0xe7, 0x15,0x00, 0x25,0x01,
            0x75,0x01, 0x95,0x08, 0x81,0x02,
            0x95,0x01, 0x75,0x08, 0x81,0x01,
            0x95,0x05, 0x75,0x01, 0x05,0x08, 0x19,0x01, 0x29,0x05, 0x91,0x02,
            0x95,0x01, 0x75,0x03, 0x91,0x01,
            0x95,0x06, 0x75,0x08, 0x15,0x00, 0x25,0x65,
            0x05,0x07, 0x19,0x00, 0x29,0x65, 0x81,0x00, 0xc0,
        ).map { it.toByte() }.toByteArray()
    }
}
