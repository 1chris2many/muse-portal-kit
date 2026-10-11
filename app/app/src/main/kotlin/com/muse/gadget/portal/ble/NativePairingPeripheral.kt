package com.muse.gadget.portal.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.muse.gadget.ble.BleFraming
import com.muse.gadget.identity.Identity
import com.muse.gadget.pairing.*
import com.muse.gadget.portal.DeviceStore
import com.muse.gadget.util.Json
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** Pairing is explicit, single-peer and closes after 10 minutes. No host shell access. */
class NativePairingPeripheral(
    private val context: Context, private val store: DeviceStore, sdkToken: String,
    private val validate: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit, private val onPaired: () -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = manager.adapter
    private var server: BluetoothGattServer? = null
    private var peer: BluetoothDevice? = null
    private var mtu = 23
    private var sending = false
    private var subscribed = false
    private var previousName: String? = null
    private val txQueue = ArrayDeque<ByteArray>()
    private val assembler = BleFraming.Reassembler()
    private val pairing = PairingSession(store.nodeId, Identity.deviceId(store.mac), store.mac, "0.2.0", sdkToken)
    private val setup = PortalSetup(pairing, store.nodeId, {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork != null
    }, { obj, generation ->
        worker.execute {
            try {
                val credentials = JSONObject(Json.stringify(obj))
                validate(credentials)
                check(pairing.commitProvisioning(generation) { store.save("pairing", credentials); true })
                pairing.encryptStatus("auth_ok", generation)?.let { enqueue(it) }
                onStatus("Paired. Connecting to your existing Muse…")
                main.postDelayed({ stop(); onPaired() }, 2000)
            } catch (_: Exception) {
                pairing.encryptStatus("error_auth", generation)?.let { enqueue(it) }
                onStatus("Pairing validation failed; credentials were not saved.")
            }
        }
    }, {
        @Suppress("DEPRECATION")
        val ssid=(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .activeNetworkInfo?.extraInfo?.trim('"')
        ssid?.takeUnless{it.isBlank() || it=="<unknown ssid>"}
    })
    private val ccc = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val adv = object: AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { onStatus("Pair in Muse → Settings → Devices: ${Identity.bleName(store.nodeId)}") }
        override fun onStartFailure(errorCode: Int) { onStatus("Bluetooth advertising failed ($errorCode).") }
    }
    private val callback = object: BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (peer != null && peer != device) server?.cancelConnection(device) else peer=device
                } else if(peer == device) {
                    peer=null; subscribed=false; sending=false; txQueue.clear(); mtu=23
                    worker.execute { setup.disconnected(); assembler.reset() }
                }
            }
        }
        override fun onMtuChanged(device: BluetoothDevice, value: Int) { main.post { if(peer==device) mtu=value } }
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if(status!=BluetoothGatt.GATT_SUCCESS) { onStatus("Could not create Bluetooth pairing service."); return }
            // Put name in scan response: service UUID + name exceeds legacy advertisement budget.
            adapter.bluetoothLeAdvertiser.startAdvertising(
                AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build(),
                AdvertiseData.Builder().addServiceUuid(ParcelUuid(Identity.GATT_SERVICE_UUID)).build(),
                AdvertiseData.Builder().setIncludeDeviceName(true).build(), adv)
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            main.post {
                val ok=peer==device && descriptor.uuid==ccc && !preparedWrite && offset==0 &&
                    (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)||value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
                if(ok) subscribed=value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if(responseNeeded) server?.sendResponse(device,requestId,if(ok)0 else BluetoothGatt.GATT_FAILURE,0,null)
                pump()
            }
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            main.post {
                val ok=peer==device && characteristic.uuid==Identity.GATT_RX_UUID && !preparedWrite && offset==0 && value.size<=512
                if(responseNeeded) server?.sendResponse(device,requestId,if(ok)0 else BluetoothGatt.GATT_FAILURE,0,null)
                if(ok) worker.execute {
                    try { assembler.feed(value)?.let { setup.handle(String(it)).forEach(::enqueue) } }
                    catch (_: Exception) { assembler.reset(); setup.disconnected(); main.post { server?.cancelConnection(device) }; onStatus("Pairing rejected; restart pairing on the phone.") }
                }
            }
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic) {
            val ok=peer==device && characteristic.uuid==Identity.GATT_TX_UUID && offset==0
            server?.sendResponse(device,requestId,if(ok)BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,0,byteArrayOf())
        }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) { main.post {
            sending=false
            if(status==0) pump() else {txQueue.clear(); server?.cancelConnection(device)}
        } }
    }
    fun start() {
        check(context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)) {
            "This firmware lacks native BLE support; use USB-assisted setup."
        }
        check(adapter!=null && adapter.isEnabled && adapter.isMultipleAdvertisementSupported) { "Enable Bluetooth; this Portal must support BLE advertising." }
        previousName=adapter.name
        check(adapter.setName(Identity.bleName(store.nodeId))) { "Portal rejected the Bluetooth advertising name." }
        server=manager.openGattServer(context,callback)
        check(server!=null) { "Portal could not open a Bluetooth GATT server." }
        val service=BluetoothGattService(Identity.GATT_SERVICE_UUID,BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(Identity.GATT_RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,BluetoothGattCharacteristic.PERMISSION_WRITE))
        val tx=BluetoothGattCharacteristic(Identity.GATT_TX_UUID,BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ)
        tx.addDescriptor(BluetoothGattDescriptor(ccc,BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(tx)
        check(server!!.addService(service)) { "Portal rejected the Muse Bluetooth service." }
        main.postDelayed({ stop(); onStatus("Pairing window closed.") },600000)
    }
    private fun enqueue(text: String) { main.post {
        if(server!=null) {txQueue.addAll(BleFraming.encode(text.toByteArray(),minOf(mtu-6,157).coerceAtLeast(1))); pump()}
    } }
    @Suppress("DEPRECATION") private fun pump() {
        val device=peer ?: return
        if(sending || !subscribed || txQueue.isEmpty()) return
        val tx=server?.getService(Identity.GATT_SERVICE_UUID)?.getCharacteristic(Identity.GATT_TX_UUID) ?: return
        tx.value=txQueue.removeFirst(); sending=true
        if(server?.notifyCharacteristicChanged(device,tx,false)!=true) {sending=false;txQueue.clear();onStatus("Bluetooth notification failed.")}
    }
    fun stop() {
        main.removeCallbacksAndMessages(null)
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(adv)
        server?.close();server=null
        previousName?.let { adapter?.setName(it) }; previousName=null
        worker.shutdown(); txQueue.clear();peer=null
    }
}
