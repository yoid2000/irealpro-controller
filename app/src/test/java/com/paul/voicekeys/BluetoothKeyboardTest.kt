package com.paul.voicekeys

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.time.Duration
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BluetoothKeyboardTest {
    private lateinit var adapter: BluetoothAdapter
    private lateinit var hid: BluetoothHidDevice
    private lateinit var listener: BluetoothProfile.ServiceListener
    private lateinit var callback: BluetoothHidDevice.Callback
    private lateinit var keyboard: BluetoothKeyboard
    private lateinit var device: BluetoothDevice
    private val errors = mutableListOf<String>()
    private var detail = ""
    private var registered = false

    @Before fun setup() {
        val context = mock<Context>()
        val manager = mock<BluetoothManager>()
        adapter = mock()
        hid = mock()
        device = mock()
        whenever(device.address).thenReturn("00:11:22:33:44:55")
        whenever(device.name).thenReturn("Device B")
        whenever(context.getSystemService(BluetoothManager::class.java)).thenReturn(manager)
        whenever(context.mainExecutor).thenReturn(Executor { it.run() })
        whenever(manager.adapter).thenReturn(adapter)
        whenever(adapter.isEnabled).thenReturn(true)
        whenever(adapter.getProfileProxy(eq(context), any(), eq(BluetoothProfile.HID_DEVICE))).thenAnswer {
            listener = it.getArgument(1)
            true
        }
        whenever(hid.registerApp(any(), isNull(), isNull(), any(), any())).thenAnswer {
            callback = it.getArgument(4)
            true
        }
        whenever(hid.unregisterApp()).thenReturn(true)
        whenever(hid.connect(any())).thenReturn(true)
        whenever(hid.getConnectionState(any())).thenReturn(BluetoothProfile.STATE_DISCONNECTED)
        keyboard = BluetoothKeyboard(context,
            onState = { ready, _, status -> registered = ready; detail = status },
            onError = { errors += it }, onSent = {})
    }

    private fun advance(ms: Long) { ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(ms)) }
    private fun openProfile() {
        keyboard.enterForeground("")
        listener.onServiceConnected(BluetoothProfile.HID_DEVICE, hid)
        advance(300)
    }

    @Test fun selectingBBeforeRegistrationConnectsOnceAfterConfirmation() {
        keyboard.enterForeground("")
        keyboard.selectAndConnect(device)
        verify(hid, never()).connect(any())
        listener.onServiceConnected(BluetoothProfile.HID_DEVICE, hid)
        advance(300)
        assertFalse(registered)
        verify(hid, never()).connect(any())
        callback.onAppStatusChanged(null, true)
        assertTrue(registered)
        verify(hid).connect(device)
        callback.onAppStatusChanged(null, true)
        verify(hid, times(1)).connect(device)
        assertTrue(errors.isEmpty())
    }

    @Test fun callbackThatNeverArrivesProducesTimeoutAndCanBeRetried() {
        openProfile()
        keyboard.selectAndConnect(device)
        advance(10000)
        assertFalse(registered)
        assertTrue(errors.single().contains("10 seconds"))
        keyboard.retryRegistration()
        verify(hid).unregisterApp()
        callback.onAppStatusChanged(null, false)
        advance(300)
        callback.onAppStatusChanged(null, true)
        verify(hid, times(2)).registerApp(any(), isNull(), isNull(), any(), any())
        verify(hid).connect(device)
    }

    @Test fun missingProfileCallbackProducesAnActionableError() {
        keyboard.enterForeground("")
        advance(8000)
        assertTrue(errors.single().contains("8 seconds"))
        assertTrue(detail.contains("Use BLE keyboard"))
        keyboard.retryRegistration()
        listener.onServiceConnected(BluetoothProfile.HID_DEVICE, hid)
        advance(300)
        verify(hid).registerApp(any(), isNull(), isNull(), any(), any())
    }

    @Test fun retryWhileProfileIsStillOpeningDoesNotLoseItsTimeout() {
        keyboard.enterForeground("")
        advance(1000)
        keyboard.retryRegistration()
        advance(8000)
        assertTrue(errors.single().contains("8 seconds"))
    }

    @Test fun lateProfileCallbackFromAnEarlierRetryCannotTakeOverSetup() {
        keyboard.enterForeground("")
        val oldListener = listener
        keyboard.retryRegistration()
        val oldProxy = mock<BluetoothHidDevice>()
        oldListener.onServiceConnected(BluetoothProfile.HID_DEVICE, oldProxy)
        verify(adapter).closeProfileProxy(BluetoothProfile.HID_DEVICE, oldProxy)
        listener.onServiceConnected(BluetoothProfile.HID_DEVICE, hid)
        advance(300)
        verify(hid).registerApp(any(), isNull(), isNull(), any(), any())
        verify(oldProxy, never()).registerApp(any(), isNull(), isNull(), any(), any())
    }

    @Test fun foregroundRegistrationRejectionRetriesOnlyThreeTimes() {
        whenever(hid.registerApp(any(), isNull(), isNull(), any(), any())).thenReturn(false)
        openProfile()
        advance(2000)
        verify(hid, times(3)).registerApp(any(), isNull(), isNull(), any(), any())
        assertTrue(errors.single().contains("3 attempts"))
        keyboard.enterForeground("") // Regaining window focus must not create an endless retry loop.
        advance(2000)
        verify(hid, times(3)).registerApp(any(), isNull(), isNull(), any(), any())
        keyboard.retryRegistration()
        advance(300)
        verify(hid, times(4)).registerApp(any(), isNull(), isNull(), any(), any())
    }

    @Test fun leavingAppCancelsAQueuedConnectionAndRegistrationTimers() {
        openProfile()
        keyboard.selectAndConnect(device)
        keyboard.leaveForeground()
        callback.onAppStatusChanged(null, false)
        advance(20000)
        verify(hid, never()).connect(any())
        assertFalse(registered)
        assertTrue(errors.isEmpty())
    }

    @Test fun automaticUnregistrationWhileVisibleTriggersRecovery() {
        openProfile()
        callback.onAppStatusChanged(null, true)
        callback.onAppStatusChanged(null, false)
        advance(750)
        verify(hid, times(2)).registerApp(any(), isNull(), isNull(), any(), any())
    }

    @Test fun returningBeforeUnregisterCompletesWaitsForThatCallback() {
        openProfile()
        callback.onAppStatusChanged(null, true)
        keyboard.leaveForeground()
        keyboard.enterForeground("")
        advance(1000)
        verify(hid, times(1)).registerApp(any(), isNull(), isNull(), any(), any())
        callback.onAppStatusChanged(null, false)
        advance(300)
        verify(hid, times(2)).registerApp(any(), isNull(), isNull(), any(), any())
    }
}
