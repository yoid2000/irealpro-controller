@file:Suppress("DEPRECATION")

package com.paul.voicekeys

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BleKeyboardTest {
    private lateinit var ble: BleKeyboard
    private lateinit var server: BluetoothGattServer
    private lateinit var callback: BluetoothGattServerCallback
    private lateinit var peer: BluetoothDevice
    private val services = mutableListOf<BluetoothGattService>()
    private val sent = mutableListOf<ByteArray>()
    private val errors = mutableListOf<String>()
    private var ready = false
    private var connected: BluetoothDevice? = null

    @Before fun setup() {
        val context = spy<Context>(RuntimeEnvironment.getApplication())
        val manager = mock<BluetoothManager>()
        val adapter = mock<BluetoothAdapter>()
        val advertiser = mock<BluetoothLeAdvertiser>()
        server = mock()
        peer = mock()
        whenever(peer.address).thenReturn("00:11:22:33:44:55")
        whenever(peer.name).thenReturn("Device B")
        whenever(peer.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        doReturn(manager).whenever(context).getSystemService(BluetoothManager::class.java)
        whenever(adapter.bluetoothLeAdvertiser).thenReturn(advertiser)
        whenever(adapter.name).thenReturn("Device A")
        whenever(manager.openGattServer(eq(context), any())).thenAnswer {
            callback = it.getArgument(1)
            server
        }
        whenever(server.addService(any())).thenAnswer { services += it.getArgument<BluetoothGattService>(0); true }
        whenever(server.notifyCharacteristicChanged(any(), any(), eq(false), any())).thenAnswer {
            sent += it.getArgument<ByteArray>(3).copyOf()
            BluetoothStatusCodes.SUCCESS
        }
        doAnswer {
            it.getArgument<AdvertiseCallback>(3).onStartSuccess(AdvertiseSettings.Builder().build())
            null
        }.whenever(advertiser).startAdvertising(any(), any(), any(), any())
        ble = BleKeyboard(context, adapter,
            onState = { available, host, _ -> ready = available; connected = host },
            onError = { errors += it }, onHost = {})
    }

    private fun idle(ms: Long = 0) { ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(ms)) }
    private fun publishServices() {
        ble.start("")
        repeat(3) { callback.onServiceAdded(BluetoothGatt.GATT_SUCCESS, services[it]); idle() }
        assertTrue(ready)
    }
    private fun reportInput() = services.first().characteristics.first {
        it.uuid == BleKeyboard.uuid(0x2a4d) && it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
    }
    private fun subscribe() {
        callback.onDescriptorWriteRequest(peer, 1, reportInput().getDescriptor(BleKeyboard.uuid(0x2902)),
            false, true, 0, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        idle()
    }
    private fun connect() {
        publishServices()
        ble.beginPairing()
        callback.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        idle()
        subscribe()
    }

    @Test fun publishesHidBatteryAndDeviceInformationBeforeAdvertisingReady() {
        publishServices()
        assertEquals(listOf(0x1812, 0x180f, 0x180a).map(BleKeyboard::uuid), services.map { it.uuid })
        assertTrue(errors.isEmpty())
        val report = reportInput()
        assertArrayEquals(byteArrayOf(1, 1), report.getDescriptor(BleKeyboard.uuid(0x2908)).value)
        assertTrue(report.permissions and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED != 0)
        assertNotNull(report.getDescriptor(BleKeyboard.uuid(0x2902)))
    }

    @Test fun requiresBondAndNotificationSubscriptionBeforeSendingKeys() {
        publishServices()
        ble.beginPairing()
        whenever(peer.bondState).thenReturn(BluetoothDevice.BOND_NONE)
        callback.onConnectionStateChange(peer, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        idle()
        subscribe()
        assertNull(connected)
        assertFalse(ble.sendReport(OutputKey.COMMA.report()))
        whenever(peer.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        subscribe()
        assertEquals(peer, connected)
        assertTrue(ble.sendReport(OutputKey.COMMA.report()))
    }

    @Test fun serializesKeyDownAndReleaseUntilAndroidAcknowledgesNotifications() {
        connect()
        val down = OutputKey.COMMA.report()
        assertTrue(ble.sendReport(down))
        assertTrue(ble.sendReport(ByteArray(8)))
        assertEquals(1, sent.size)
        assertArrayEquals(down, sent[0])
        callback.onNotificationSent(peer, BluetoothGatt.GATT_SUCCESS)
        idle()
        assertEquals(2, sent.size)
        assertArrayEquals(ByteArray(8), sent[1])
    }

    @Test fun cancellingDiscardsUnsentKeysButReleasesAnAlreadySentKey() {
        connect()
        ble.sendReport(OutputKey.COMMA.report())
        ble.sendReport(OutputKey.SPACE.report())
        ble.releaseKeys()
        callback.onNotificationSent(peer, BluetoothGatt.GATT_SUCCESS)
        idle()
        assertEquals(2, sent.size)
        assertArrayEquals(OutputKey.COMMA.report(), sent[0])
        assertArrayEquals(ByteArray(8), sent[1])
    }

    @Test fun stoppingDiscardsLateCallbacksAndClosesTheGattServer() {
        connect()
        ble.sendReport(OutputKey.COMMA.report())
        ble.sendReport(OutputKey.SPACE.report())
        ble.stop()
        callback.onNotificationSent(peer, BluetoothGatt.GATT_SUCCESS)
        idle()
        verify(server).close()
        assertEquals(1, sent.size)
        assertFalse(ble.sendReport(OutputKey.PERIOD.report()))
    }

    @Test fun reportMapLongReadsHonorTheRequestedOffset() {
        publishServices()
        val map = services.first().getCharacteristic(BleKeyboard.uuid(0x2a4b))
        callback.onCharacteristicReadRequest(peer, 7, 22, map)
        idle()
        verify(server).sendResponse(eq(peer), eq(7), eq(BluetoothGatt.GATT_SUCCESS), eq(22),
            argThat { contentEquals(BluetoothKeyboard.DESCRIPTOR.copyOfRange(22, 44)) })
    }

    @Test fun firstReportMapReadIsLimitedToDefaultMtuAndCanBeContinued() {
        publishServices()
        val map = services.first().getCharacteristic(BleKeyboard.uuid(0x2a4b))
        callback.onCharacteristicReadRequest(peer, 9, 0, map)
        idle()
        verify(server).sendResponse(eq(peer), eq(9), eq(BluetoothGatt.GATT_SUCCESS), eq(0),
            argThat { contentEquals(BluetoothKeyboard.DESCRIPTOR.copyOfRange(0, 22)) })
    }

    @Test fun failedServicePublicationNeverReportsKeyboardReady() {
        ble.start("")
        callback.onServiceAdded(BluetoothGatt.GATT_FAILURE, services.first())
        idle()
        assertFalse(ready)
        assertTrue(errors.single().contains("rejected"))
        verify(server).close()
    }

    @Test fun missingNotificationCompletionTimesOutAndDisconnects() {
        connect()
        ble.sendReport(OutputKey.COMMA.report())
        idle(2000)
        assertFalse(ready)
        verify(server).cancelConnection(peer)
        assertTrue(errors.single().contains("stalled"))
    }
}
