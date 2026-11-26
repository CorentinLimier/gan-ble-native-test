package com.corentinlimier.ganblenativetest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Locale
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.corentinlimier.ganblenativetest.databinding.ActivityMainBinding
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal native BLE test harness for the GAN cube Android connectivity
 * investigation (see https://github.com/CorentinLimier/gan-ble-raw-logger).
 *
 * The Web Bluetooth-based tool in that repo intermittently hangs forever on
 * readValue()/startNotifications() on one specific Android phone, and
 * retrying (even many times) doesn't always recover it. Web Bluetooth does
 * not expose several native Android BLE controls that might matter here:
 * connection priority, MTU negotiation, GATT cache refresh, and autoConnect
 * mode. This app connects directly via BluetoothGatt (no scanning — same
 * known MAC address every time) with each of those as an independent
 * on/off toggle, so you can manually run repeated connect attempts with
 * different combinations and compare how often each one hangs.
 *
 * This is deliberately NOT automated with a retry loop (unlike the web
 * tool) — tap Connect, note the result, Disconnect, repeat with different
 * toggle combinations, so each attempt is a clean, comparable data point.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bluetoothGatt: BluetoothGatt? = null
    private var stateChar: BluetoothGattCharacteristic? = null
    private var keyIv: Pair<ByteArray, ByteArray>? = null
    private var notifyCount = 0

    private val logBuffer = StringBuilder()
    private var logLineCount = 0

    private var pendingAction: (() -> Unit)? = null

    private data class FoundDevice(val address: String, var name: String?, var rssi: Int)

    private var scanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val foundDevices = LinkedHashMap<String, FoundDevice>()
    private lateinit var deviceListAdapter: ArrayAdapter<String>

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        data class GenDef(val service: String, val command: String, val state: String, val name: String)

        private val GEN_SERVICES = listOf(
            GenDef(
                "6e400001-b5a3-f393-e0a9-e50e24dc4179",
                "28be4a4a-cd67-11e9-a32f-2a2ae2dbcce4",
                "28be4cb6-cd67-11e9-a32f-2a2ae2dbcce4",
                "Gen2"
            ),
            GenDef(
                "8653000a-43e6-47b7-9cb0-5fc21d4ae340",
                "8653000c-43e6-47b7-9cb0-5fc21d4ae340",
                "8653000b-43e6-47b7-9cb0-5fc21d4ae340",
                "Gen3"
            ),
            GenDef(
                "00000010-0000-fff7-fff6-fff5fff4fff0",
                "0000fff5-0000-1000-8000-00805f9b34fb",
                "0000fff6-0000-1000-8000-00805f9b34fb",
                "Gen4"
            )
        )

        // GAN Gen2/Gen3/Gen4 default encryption key/iv, salted per-device with
        // the reversed MAC bytes. Reproduced from the public GAN BLE protocol
        // (see upstream afedotov/gan-web-bluetooth, MIT) for interoperability.
        private val BASE_KEY =
            intArrayOf(0x01, 0x02, 0x42, 0x28, 0x31, 0x91, 0x16, 0x07, 0x20, 0x05, 0x18, 0x54, 0x42, 0x11, 0x12, 0x53)
                .map { it.toByte() }.toByteArray()
        private val BASE_IV =
            intArrayOf(0x11, 0x03, 0x32, 0x28, 0x21, 0x01, 0x76, 0x27, 0x20, 0x95, 0x78, 0x14, 0x32, 0x12, 0x02, 0x43)
                .map { it.toByte() }.toByteArray()
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                log("Permissions granted.", "ok")
                pendingAction?.invoke()
            } else {
                log("Permissions denied: $results", "error")
            }
            pendingAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.lvDevices.adapter = deviceListAdapter
        binding.lvDevices.setOnItemClickListener { _, _, position, _ ->
            val selected = foundDevices.values.sortedByDescending { it.rssi }.getOrNull(position)
            if (selected != null) {
                binding.etMac.setText(selected.address)
                log("Selected from scan list: ${selected.name ?: "(unknown name)"}  ${selected.address}  ${selected.rssi} dBm", "ok")
                if (scanning) stopScan()
            }
        }

        binding.btnScan.setOnClickListener { onScanClicked() }
        binding.btnConnect.setOnClickListener { onConnectClicked() }
        binding.btnDisconnect.setOnClickListener { onDisconnectClicked() }
        binding.btnClear.setOnClickListener {
            logBuffer.clear(); logLineCount = 0; binding.tvLog.text = ""
        }
        binding.btnCopy.setOnClickListener { copyLogToClipboard() }

        log("Ready. Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), device: ${Build.MODEL}")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (scanning) stopScan()
        closeGatt()
    }

    // -----------------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun runWithPermissions(action: () -> Unit) {
        if (!hasPermissions()) {
            pendingAction = action
            log("Requesting permissions: ${requiredPermissions().joinToString()}")
            requestPermissionLauncher.launch(requiredPermissions())
            return
        }
        action()
    }

    private fun onConnectClicked() = runWithPermissions { doConnect() }

    private fun onScanClicked() = runWithPermissions { toggleScan() }

    // -----------------------------------------------------------------------
    // Scanning (shows every nearby BLE device, not just GAN ones — useful to
    // spot e.g. a cube advertising under more than one MAC address)
    // -----------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun toggleScan() {
        if (scanning) {
            stopScan()
            return
        }
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth adapter not available or disabled.", "error")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            log("BluetoothLeScanner unavailable (adapter off?).", "error")
            return
        }

        if (bluetoothGatt != null) {
            log("Note: scanning while a GATT connection is active — this is itself one of the things the web investigation tested (scanning right before connect), worth watching for any effect on the active connection.", "warn")
        }

        foundDevices.clear()
        deviceListAdapter.clear()
        log("Starting BLE scan for every nearby device (auto-stops after 15s)…")
        scanning = true
        binding.btnScan.text = "Stop scan"

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, leScanCallback)
        scanHandler.postDelayed({ if (scanning) stopScan() }, 15000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        scanning = false
        binding.btnScan.text = "Scan for devices"
        log("Scan stopped. ${foundDevices.size} distinct device(s) found.", "ok")
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val name = result.device.name ?: result.scanRecord?.deviceName
            val existing = foundDevices[address]
            if (existing == null) {
                foundDevices[address] = FoundDevice(address, name, result.rssi)
                log("Found device: ${name ?: "(unknown name)"}  $address  ${result.rssi} dBm", "evt")
            } else {
                existing.rssi = result.rssi
                if (name != null) existing.name = name
            }
            refreshDeviceList()
        }

        override fun onScanFailed(errorCode: Int) {
            log("BLE scan failed, errorCode=$errorCode", "error")
            scanning = false
            runOnUiThread { binding.btnScan.text = "Scan for devices" }
        }
    }

    private fun refreshDeviceList() {
        val lines = foundDevices.values.sortedByDescending { it.rssi }.map {
            "${it.name ?: "(unknown name)"}   ${it.address}   ${it.rssi} dBm"
        }
        runOnUiThread {
            deviceListAdapter.clear()
            deviceListAdapter.addAll(lines)
            deviceListAdapter.notifyDataSetChanged()
        }
    }

    // -----------------------------------------------------------------------
    // Connect / disconnect
    // -----------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun doConnect() {
        val mac = binding.etMac.text.toString().trim()
        if (mac.isEmpty()) {
            log("Enter a MAC address first.", "error")
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth adapter not available or disabled.", "error")
            return
        }

        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            log("Invalid MAC address: $mac", "error")
            return
        }

        notifyCount = 0
        stateChar = null
        keyIv = buildKeyIv(mac)

        val autoConnect = binding.swAutoConnect.isChecked
        log("connectGatt(autoConnect=$autoConnect, TRANSPORT_LE) to $mac …")
        binding.tvStatus.text = "Connecting…"
        binding.btnConnect.isEnabled = false

        bluetoothGatt = device.connectGatt(this, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun onDisconnectClicked() {
        closeGatt()
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.isEnabled = false
        binding.tvStatus.text = "Disconnected"
        log("Disconnected.", "ok")
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    @SuppressLint("MissingPermission")
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        return try {
            val method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt) as Boolean
        } catch (e: Exception) {
            log("refresh() reflection failed: ${e.message}", "warn")
            false
        }
    }

    // -----------------------------------------------------------------------
    // GATT callback
    // -----------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log(
                "onConnectionStateChange status=$status newState=$newState (${describeConnState(newState)})",
                if (status == BluetoothGatt.GATT_SUCCESS) "ok" else "error"
            )
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    binding.tvStatus.text = "Connected — discovering…"
                    binding.btnDisconnect.isEnabled = true

                    // refresh()/requestConnectionPriority() have no async callback — safe to
                    // call back-to-back. requestMtu() DOES have one (onMtuChanged), and
                    // Android's single-operation-at-a-time GATT queue can silently drop a
                    // discoverServices() call issued while an MTU negotiation is still in
                    // flight — so defer it to onMtuChanged below instead of firing it here.
                    if (binding.swRefreshCache.isChecked) {
                        log("gatt.refresh() -> ${refreshGattCache(gatt)}")
                    }
                    if (binding.swConnPriority.isChecked) {
                        log("requestConnectionPriority(HIGH) -> ${gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)}")
                    }
                    if (binding.swRequestMtu.isChecked) {
                        log("requestMtu(247) requested -> ${gatt.requestMtu(247)} (result via onMtuChanged)")
                    } else {
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    binding.tvStatus.text = "Disconnected"
                    binding.btnConnect.isEnabled = true
                    binding.btnDisconnect.isEnabled = false
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("onMtuChanged mtu=$mtu status=$status")
            // Only call gatt here, now that the MTU request has actually completed —
            // see the comment in onConnectionStateChange for why this is deferred here.
            if (binding.swRequestMtu.isChecked) {
                gatt.discoverServices()
            }
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            log("onServicesDiscovered status=$status, ${gatt.services.size} services: ${gatt.services.joinToString { it.uuid.toString() }}")

            val match = GEN_SERVICES.firstOrNull { def ->
                gatt.services.any { it.uuid.toString().equals(def.service, ignoreCase = true) }
            }
            if (match == null) {
                log("No known GAN service UUID found among discovered services.", "error")
                binding.tvStatus.text = "Connected, no GAN service found"
                return
            }

            val service = gatt.getService(UUID.fromString(match.service))
            if (service == null) {
                log("Matched service ${match.service} disappeared between discovery and lookup!", "error")
                return
            }
            val sChar = service.getCharacteristic(UUID.fromString(match.state))
            if (sChar == null) {
                log("State characteristic not found on matched ${match.name} service.", "error")
                return
            }
            stateChar = sChar
            log("Matched ${match.name}. State characteristic properties: ${describeProps(sChar.properties)}", "ok")

            val notifEnabled = gatt.setCharacteristicNotification(sChar, true)
            log("setCharacteristicNotification(true) -> $notifEnabled")
            val cccd = sChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                log("CCCD descriptor not found on state characteristic!", "error")
                return
            }

            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            log("writeDescriptor(CCCD, ENABLE_NOTIFICATION) …")
            val ok = gatt.writeDescriptor(cccd)
            log("writeDescriptor() call returned $ok (result arrives async via onDescriptorWrite)")
            binding.tvStatus.text = "Connected, enabling notifications…"
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            log("onDescriptorWrite status=$status (${if (success) "SUCCESS" else "FAILED"})", if (success) "ok" else "error")
            binding.tvStatus.text = if (success) "Connected, notifications enabled — turn a face" else "Connected, notify FAILED"
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotification(characteristic.value ?: ByteArray(0))
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            log("onCharacteristicRead status=$status bytes=${characteristic.value?.toHex()}")
        }
    }

    private fun handleNotification(raw: ByteArray) {
        notifyCount++
        log("notification #$notifyCount (${raw.size} bytes) raw: ${raw.toHex()}", "evt")
        val ki = keyIv ?: return
        try {
            val decrypted = decryptPayload(raw, ki.first, ki.second)
            log("  decrypted: ${decrypted.toHex()}")
            tryHandleGen4Gyro(decrypted)
        } catch (e: Exception) {
            log("  decrypt failed: ${e.message}", "warn")
        }
    }

    // -----------------------------------------------------------------------
    // GYRO decoding (GAN Gen4 protocol only — matches the GAN12 ui Maglev
    // used throughout this investigation). Event type 0xEC, quaternion as
    // four 16-bit sign+15-bit-magnitude values (NOT plain two's complement),
    // same bit layout as the already-validated web tool's protocol.js.
    // Other event types (MOVE/FACELETS/HARDWARE/BATTERY) are left as raw/
    // decrypted hex only, same as before — this only adds GYRO -> CubeView.
    // -----------------------------------------------------------------------

    private fun decodeSignMag16(raw: Int): Float {
        val sign = if ((raw ushr 15) == 1) -1f else 1f
        val magnitude = (raw and 0x7FFF).toFloat() / 0x7FFF
        return sign * magnitude
    }

    private fun tryHandleGen4Gyro(decrypted: ByteArray) {
        if (decrypted.size < 12) return
        if ((decrypted[0].toInt() and 0xFF) != 0xEC) return // not a GYRO packet

        fun u16(offset: Int): Int =
            ((decrypted[offset].toInt() and 0xFF) shl 8) or (decrypted[offset + 1].toInt() and 0xFF)

        val qw = decodeSignMag16(u16(2))
        val qx = decodeSignMag16(u16(4))
        val qy = decodeSignMag16(u16(6))
        val qz = decodeSignMag16(u16(8))
        log("  GYRO quaternion x=${"%.3f".format(qx)} y=${"%.3f".format(qy)} z=${"%.3f".format(qz)} w=${"%.3f".format(qw)}")
        binding.cubeView.setQuaternion(qx, qy, qz, qw)
    }

    // -----------------------------------------------------------------------
    // AES decryption (same scheme as the web tool: AES-128-CBC, key/iv salted
    // with reversed MAC bytes, first+last 16-byte chunk each decrypted
    // independently with a fresh cipher keyed only by key/iv)
    // -----------------------------------------------------------------------

    private fun macToBytes(mac: String): ByteArray =
        mac.split(":", "-").map { it.toInt(16).toByte() }.toByteArray()

    private fun buildKeyIv(mac: String): Pair<ByteArray, ByteArray> {
        val salt = macToBytes(mac).reversedArray()
        val key = BASE_KEY.copyOf()
        val iv = BASE_IV.copyOf()
        for (i in 0 until 6) {
            key[i] = (((key[i].toInt() and 0xFF) + (salt[i].toInt() and 0xFF)) % 0xFF).toByte()
            iv[i] = (((iv[i].toInt() and 0xFF) + (salt[i].toInt() and 0xFF)) % 0xFF).toByte()
        }
        return Pair(key, iv)
    }

    private fun decryptChunk(key: ByteArray, iv: ByteArray, data: ByteArray, offset: Int) {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val chunk = data.copyOfRange(offset, offset + 16)
        val decrypted = cipher.doFinal(chunk)
        System.arraycopy(decrypted, 0, data, offset, 16)
    }

    private fun decryptPayload(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        if (data.size < 16) throw IllegalArgumentException("payload shorter than 16 bytes")
        val result = data.copyOf()
        if (result.size > 16) decryptChunk(key, iv, result, result.size - 16)
        decryptChunk(key, iv, result, 0)
        return result
    }

    // -----------------------------------------------------------------------
    // Small helpers
    // -----------------------------------------------------------------------

    private fun ByteArray.toHex(): String = joinToString(" ") { String.format("%02X", it) }

    private fun describeProps(props: Int): String {
        val parts = mutableListOf<String>()
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts.add("read")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts.add("write")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts.add("writeWithoutResponse")
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts.add("notify")
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts.add("indicate")
        return if (parts.isEmpty()) "(none)" else parts.joinToString(", ")
    }

    private fun describeConnState(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN($state)"
    }

    private fun copyLogToClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", logBuffer.toString()))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    private fun log(msg: String, level: String = "info") {
        Log.d("GanBleNativeTest", msg)
        // New formatter per call, not a shared instance: SimpleDateFormat isn't
        // thread-safe, and log() is called from multiple BLE callback threads.
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val tag = if (level == "info") "" else "[${level.uppercase()}] "
        val line = "[$ts] $tag$msg\n"
        runOnUiThread {
            logBuffer.append(line)
            logLineCount++
            if (logLineCount > 4000) {
                val keepFrom = logBuffer.length / 3
                logBuffer.delete(0, keepFrom)
            }
            binding.tvLog.text = logBuffer.toString()
            binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
