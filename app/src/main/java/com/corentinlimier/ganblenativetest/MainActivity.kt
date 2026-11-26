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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.corentinlimier.ganblenativetest.databinding.ActivityMainBinding
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native BLE test harness for the GAN cube Android connectivity
 * investigation (see https://github.com/CorentinLimier/gan-ble-raw-logger).
 *
 * Connects directly via BluetoothGatt (by MAC, or pick one from a scan),
 * with native-only BLE controls (connection priority, MTU, GATT cache
 * refresh, autoConnect) as independent toggles. Once connected and
 * notifications are enabled, the UI switches to a minimal view: just
 * Disconnect/Reset gyro/Reset cube and a live, colored 3D cube rendered
 * from the GAN cube's own decoded BLE data (GYRO orientation, MOVE/
 * FACELETS state) — not the phone's own sensors.
 *
 * All logging goes to Logcat only (tag "GanBleNativeTest") — nothing is
 * rendered in-app, since that was the source of a real performance
 * problem (see commit history) and isn't needed once decoding/rendering
 * work.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bluetoothGatt: BluetoothGatt? = null
    private var stateChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var keyIv: Pair<ByteArray, ByteArray>? = null
    private var matchedGen: String? = null
    private var notifyCount = 0

    private var pendingAction: (() -> Unit)? = null

    private data class FoundDevice(val address: String, var name: String?, var rssi: Int)

    private var scanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val foundDevices = LinkedHashMap<String, FoundDevice>()
    private lateinit var deviceListAdapter: ArrayAdapter<String>

    // Decoded cube state (Gen4 only — see tryHandleGen4Event). Mutated by
    // FACELETS sync events (overwritten wholesale) and MOVE events (replayed
    // via the same Kociemba cubie move tables as the web tool).
    private data class CubeState(val cp: IntArray, val co: IntArray, val ep: IntArray, val eo: IntArray)

    private fun solvedCubeState() = CubeState(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    )

    private var cubeState = solvedCubeState()
    private var cubeStateSynced = false

    // Gyro orientation re-anchoring for the Reset gyro button: null means
    // "not yet established" — the next GYRO sample becomes the new home pose.
    private data class Quat(val x: Float, val y: Float, val z: Float, val w: Float)
    private var gyroBasis: Quat? = null

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

        // -------------------------------------------------------------------
        // Kociemba facelets conversion + cubie move tables — direct ports of
        // the already-validated logic in the web tool's protocol.js (cross-
        // checked there against gan-web-bluetooth's own documented example).
        // Corner/edge numbering: corners 0-7 = URF UFL ULB UBR DFR DLF DBL
        // DRB; edges 0-11 = UR UF UL UB DR DF DL DB FR FL BL BR.
        // -------------------------------------------------------------------

        private val CORNER_FACELET_MAP = arrayOf(
            intArrayOf(8, 9, 20), intArrayOf(6, 18, 38), intArrayOf(0, 36, 47), intArrayOf(2, 45, 11),
            intArrayOf(29, 26, 15), intArrayOf(27, 44, 24), intArrayOf(33, 53, 42), intArrayOf(35, 17, 51)
        )
        private val EDGE_FACELET_MAP = arrayOf(
            intArrayOf(5, 10), intArrayOf(7, 19), intArrayOf(3, 37), intArrayOf(1, 46),
            intArrayOf(32, 16), intArrayOf(28, 25), intArrayOf(30, 43), intArrayOf(34, 52),
            intArrayOf(23, 12), intArrayOf(21, 41), intArrayOf(50, 39), intArrayOf(48, 14)
        )

        fun toKociembaFacelets(cp: IntArray, co: IntArray, ep: IntArray, eo: IntArray): String {
            val faces = "URFDLB"
            val facelets = CharArray(54)
            for (i in 0 until 54) facelets[i] = faces[i / 9]
            for (c in 0 until 8) {
                for (p in 0 until 3) {
                    facelets[CORNER_FACELET_MAP[c][(p + co[c]) % 3]] = faces[CORNER_FACELET_MAP[cp[c]][p] / 9]
                }
            }
            for (e in 0 until 12) {
                for (p in 0 until 2) {
                    facelets[EDGE_FACELET_MAP[e][(p + eo[e]) % 2]] = faces[EDGE_FACELET_MAP[ep[e]][p] / 9]
                }
            }
            return String(facelets)
        }

        // Cubie-level move tables for a single CW quarter turn, face order
        // 0..5 = U R F D L B (matches the GAN protocol's own face numbering).
        private val MOVE_CP = arrayOf(
            intArrayOf(3, 0, 1, 2, 4, 5, 6, 7), intArrayOf(4, 1, 2, 0, 7, 5, 6, 3),
            intArrayOf(1, 5, 2, 3, 0, 4, 6, 7), intArrayOf(0, 1, 2, 3, 5, 6, 7, 4),
            intArrayOf(0, 2, 6, 3, 4, 1, 5, 7), intArrayOf(0, 1, 3, 7, 4, 5, 2, 6)
        )
        private val MOVE_CO = arrayOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0), intArrayOf(2, 0, 0, 1, 1, 0, 0, 2),
            intArrayOf(1, 2, 0, 0, 2, 1, 0, 0), intArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(0, 1, 2, 0, 0, 2, 1, 0), intArrayOf(0, 0, 1, 2, 0, 0, 2, 1)
        )
        private val MOVE_EP = arrayOf(
            intArrayOf(3, 0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11), intArrayOf(8, 1, 2, 3, 11, 5, 6, 7, 4, 9, 10, 0),
            intArrayOf(0, 9, 2, 3, 4, 8, 6, 7, 1, 5, 10, 11), intArrayOf(0, 1, 2, 3, 5, 6, 7, 4, 8, 9, 10, 11),
            intArrayOf(0, 1, 10, 3, 4, 5, 9, 7, 8, 2, 6, 11), intArrayOf(0, 1, 2, 11, 4, 5, 6, 10, 8, 9, 3, 7)
        )
        private val MOVE_EO = arrayOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(0, 1, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0), intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), intArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 1)
        )

        private fun applyMoveOnce(state: CubeState, face: Int): CubeState {
            val mcp = MOVE_CP[face]; val mco = MOVE_CO[face]; val mep = MOVE_EP[face]; val meo = MOVE_EO[face]
            val newCp = IntArray(8); val newCo = IntArray(8)
            for (i in 0 until 8) {
                newCp[i] = state.cp[mcp[i]]
                newCo[i] = (state.co[mcp[i]] + mco[i]) % 3
            }
            val newEp = IntArray(12); val newEo = IntArray(12)
            for (i in 0 until 12) {
                newEp[i] = state.ep[mep[i]]
                newEo[i] = (state.eo[mep[i]] + meo[i]) % 2
            }
            return CubeState(newCp, newCo, newEp, newEo)
        }

        /** direction: 0 = CW (one quarter turn), 1 = CCW (applied as three CW turns). */
        private fun applyMove(state: CubeState, face: Int, direction: Int): CubeState {
            var result = state
            val times = if (direction == 0) 1 else 3
            repeat(times) { result = applyMoveOnce(result, face) }
            return result
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                log("Permissions granted.")
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
                log("Selected from scan list: ${selected.name ?: "(unknown name)"}  ${selected.address}  ${selected.rssi} dBm")
                if (scanning) stopScan()
            }
        }

        binding.btnScan.setOnClickListener { onScanClicked() }
        binding.btnConnect.setOnClickListener { onConnectClicked() }
        binding.btnDisconnect.setOnClickListener { onDisconnectClicked() }
        binding.btnResetGyro.setOnClickListener { onResetGyroClicked() }
        binding.btnResetCube.setOnClickListener { onResetCubeClicked() }

        binding.cubeView.setFacelets(CubeView.SOLVED_FACELETS)
        log("Ready. Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), device: ${Build.MODEL}")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (scanning) stopScan()
        closeGatt()
    }

    // -----------------------------------------------------------------------
    // Connected / disconnected UI mode
    // -----------------------------------------------------------------------

    private fun setConnectedUiMode(connected: Boolean) {
        runOnUiThread {
            binding.preConnectGroup.visibility = if (connected) View.GONE else View.VISIBLE
            binding.postConnectGroup.visibility = if (connected) View.VISIBLE else View.GONE
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
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

        foundDevices.clear()
        deviceListAdapter.clear()
        log("Starting BLE scan for every nearby device (auto-stops after 15s)…")
        scanning = true
        runOnUiThread { binding.btnScan.text = "Stop scan" }

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
        runOnUiThread { binding.btnScan.text = "Scan for devices" }
        log("Scan stopped. ${foundDevices.size} distinct device(s) found.")
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val name = result.device.name ?: result.scanRecord?.deviceName
            val existing = foundDevices[address]
            if (existing == null) {
                foundDevices[address] = FoundDevice(address, name, result.rssi)
                log("Found device: ${name ?: "(unknown name)"}  $address  ${result.rssi} dBm")
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
        commandChar = null
        matchedGen = null
        keyIv = buildKeyIv(mac)
        cubeState = solvedCubeState()
        cubeStateSynced = false
        gyroBasis = null
        runOnUiThread { binding.cubeView.setFacelets(CubeView.SOLVED_FACELETS) }

        val autoConnect = binding.swAutoConnect.isChecked
        log("connectGatt(autoConnect=$autoConnect, TRANSPORT_LE) to $mac …")
        runOnUiThread {
            binding.tvStatus.text = "Connecting…"
            binding.btnConnect.isEnabled = false
        }

        bluetoothGatt = device.connectGatt(this, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun onDisconnectClicked() {
        closeGatt()
        setConnectedUiMode(false)
        runOnUiThread {
            binding.btnConnect.isEnabled = true
            binding.tvStatus.text = "Disconnected"
        }
        log("Disconnected.")
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

    private fun onResetGyroClicked() {
        gyroBasis = null
        log("Gyro orientation reference reset (client-side only, no command sent to the cube).")
        toast("Gyro reset")
    }

    @SuppressLint("MissingPermission")
    private fun onResetCubeClicked() {
        val gatt = bluetoothGatt
        val cmdChar = commandChar
        val ki = keyIv
        if (gatt == null || cmdChar == null || ki == null) {
            log("Cannot reset cube: not fully connected yet.", "warn")
            toast("Not connected yet")
            return
        }
        // GAN Gen4 REQUEST_RESET command — tells the cube to treat its CURRENT
        // physical position as solved. Only meaningful if it's actually solved.
        val payload = byteArrayOf(
            0xD2.toByte(), 0x0D, 0x05, 0x39, 0x77, 0x00, 0x00, 0x01,
            0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0x00, 0x00, 0x00
        )
        val msg = ByteArray(20)
        System.arraycopy(payload, 0, msg, 0, payload.size)
        val encrypted = encryptPayload(msg, ki.first, ki.second)

        @Suppress("DEPRECATION")
        cmdChar.value = encrypted
        @Suppress("DEPRECATION")
        val ok = gatt.writeCharacteristic(cmdChar)
        log("Sent REQUEST_RESET to command characteristic -> $ok")
        toast(if (ok) "Reset cube command sent" else "Failed to send reset command")

        cubeState = solvedCubeState()
        cubeStateSynced = true
        runOnUiThread { binding.cubeView.setFacelets(CubeView.SOLVED_FACELETS) }
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
                    runOnUiThread { binding.tvStatus.text = "Connected — discovering…" }

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
                    setConnectedUiMode(false)
                    runOnUiThread {
                        binding.tvStatus.text = "Disconnected"
                        binding.btnConnect.isEnabled = true
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("onMtuChanged mtu=$mtu status=$status")
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
                runOnUiThread { binding.tvStatus.text = "Connected, no GAN service found" }
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
            matchedGen = match.name
            commandChar = service.getCharacteristic(UUID.fromString(match.command))
            log("Matched ${match.name}. State characteristic properties: ${describeProps(sChar.properties)}")

            val notifEnabled = gatt.setCharacteristicNotification(sChar, true)
            log("setCharacteristicNotification(true) -> $notifEnabled")
            val cccd = sChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                log("CCCD descriptor not found on state characteristic!", "error")
                return
            }

            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = gatt.writeDescriptor(cccd)
            log("writeDescriptor(CCCD, ENABLE_NOTIFICATION) call returned $ok (result arrives async via onDescriptorWrite)")
            runOnUiThread { binding.tvStatus.text = "Connected, enabling notifications…" }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            log("onDescriptorWrite status=$status (${if (success) "SUCCESS" else "FAILED"})", if (success) "ok" else "error")
            if (success) {
                setConnectedUiMode(true)
                toast("Connected — turn a face")
            } else {
                runOnUiThread { binding.tvStatus.text = "Connected, notify FAILED" }
            }
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
        val logThisOne = binding.swLogNotifications.isChecked
        if (logThisOne) log("notification #$notifyCount (${raw.size} bytes) raw: ${raw.toHex()}")
        val ki = keyIv ?: return
        try {
            val decrypted = decryptPayload(raw, ki.first, ki.second)
            if (logThisOne) log("  decrypted: ${decrypted.toHex()}")
            if (matchedGen == "Gen4") {
                tryHandleGen4Event(decrypted, logThisOne)
            }
        } catch (e: Exception) {
            if (logThisOne) log("  decrypt failed: ${e.message}", "warn")
        }
    }

    // -----------------------------------------------------------------------
    // Gen4 event decoding (GYRO/MOVE/FACELETS) — same bit layout as the
    // already-validated web tool's protocol.js. Scoped to Gen4 only (the
    // GAN12 ui Maglev used throughout this investigation); Gen2/Gen3 use a
    // different byte layout entirely and are not decoded here.
    // -----------------------------------------------------------------------

    private fun decodeSignMag16(raw: Int): Float {
        val sign = if ((raw ushr 15) == 1) -1f else 1f
        val magnitude = (raw and 0x7FFF).toFloat() / 0x7FFF
        return sign * magnitude
    }

    private fun quatMultiply(a: Quat, b: Quat): Quat = Quat(
        x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
        w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
    )

    private fun quatConjugate(q: Quat): Quat = Quat(-q.x, -q.y, -q.z, q.w)

    private fun tryHandleGen4Event(decrypted: ByteArray, logThisOne: Boolean) {
        if (decrypted.isEmpty()) return
        val eventType = decrypted[0].toInt() and 0xFF
        when (eventType) {
            0xEC -> tryHandleGen4Gyro(decrypted, logThisOne)
            0x01 -> tryHandleGen4Move(decrypted, logThisOne)
            0xED -> tryHandleGen4Facelets(decrypted, logThisOne)
        }
    }

    private fun tryHandleGen4Gyro(decrypted: ByteArray, logThisOne: Boolean) {
        if (decrypted.size < 12) return
        fun u16(offset: Int): Int =
            ((decrypted[offset].toInt() and 0xFF) shl 8) or (decrypted[offset + 1].toInt() and 0xFF)

        val raw = Quat(
            x = decodeSignMag16(u16(4)),
            y = decodeSignMag16(u16(6)),
            z = decodeSignMag16(u16(8)),
            w = decodeSignMag16(u16(2))
        )
        var basis = gyroBasis
        if (basis == null) {
            basis = quatConjugate(raw)
            gyroBasis = basis
        }
        val display = quatMultiply(basis, raw)
        if (logThisOne) {
            log("  GYRO quaternion x=${"%.3f".format(raw.x)} y=${"%.3f".format(raw.y)} z=${"%.3f".format(raw.z)} w=${"%.3f".format(raw.w)}")
        }
        binding.cubeView.setQuaternion(display.x, display.y, display.z, display.w)
    }

    private fun tryHandleGen4Move(decrypted: ByteArray, logThisOne: Boolean) {
        if (decrypted.size < 9) return
        if (!cubeStateSynced) return // mirror the web tool: ignore moves before the first FACELETS sync
        val b8 = decrypted[8].toInt() and 0xFF
        val direction = (b8 ushr 6) and 0x3
        val faceCode = b8 and 0x3F
        val faceCodes = intArrayOf(2, 32, 8, 1, 16, 4)
        val face = faceCodes.indexOf(faceCode)
        if (face < 0) return

        cubeState = applyMove(cubeState, face, direction)
        val facelets = toKociembaFacelets(cubeState.cp, cubeState.co, cubeState.ep, cubeState.eo)
        binding.cubeView.setFacelets(facelets)
        if (logThisOne) {
            val moveStr = "URFDLB"[face].toString() + (if (direction == 1) "'" else "")
            log("  MOVE: $moveStr -> $facelets")
        }
    }

    private fun tryHandleGen4Facelets(decrypted: ByteArray, logThisOne: Boolean) {
        if (decrypted.size < 16) return
        val serial = (decrypted[2].toInt() and 0xFF) or ((decrypted[3].toInt() and 0xFF) shl 8)
        val bits = decrypted.joinToString("") { (it.toInt() and 0xFF).toString(2).padStart(8, '0') }
        fun bitWord(start: Int, len: Int): Int = bits.substring(start, start + len).toInt(2)

        val cp = IntArray(8); val co = IntArray(8)
        for (i in 0 until 7) {
            cp[i] = bitWord(32 + i * 3, 3)
            co[i] = bitWord(53 + i * 2, 2)
        }
        cp[7] = 28 - cp.take(7).sum()
        co[7] = (3 - (co.take(7).sum() % 3)) % 3

        val ep = IntArray(12); val eo = IntArray(12)
        for (i in 0 until 11) {
            ep[i] = bitWord(69 + i * 4, 4)
            eo[i] = bitWord(113 + i, 1)
        }
        ep[11] = 66 - ep.take(11).sum()
        eo[11] = (2 - (eo.take(11).sum() % 2)) % 2

        cubeState = CubeState(cp, co, ep, eo)
        cubeStateSynced = true
        val facelets = toKociembaFacelets(cp, co, ep, eo)
        binding.cubeView.setFacelets(facelets)
        if (logThisOne) log("  FACELETS sync serial=$serial -> $facelets")
    }

    // -----------------------------------------------------------------------
    // AES encryption/decryption (same scheme as the web tool: AES-128-CBC,
    // key/iv salted with reversed MAC bytes, first+last 16-byte chunk each
    // (en/de)crypted independently with a fresh cipher keyed only by key/iv)
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

    private fun encryptChunk(key: ByteArray, iv: ByteArray, data: ByteArray, offset: Int) {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val chunk = data.copyOfRange(offset, offset + 16)
        val encrypted = cipher.doFinal(chunk)
        System.arraycopy(encrypted, 0, data, offset, 16)
    }

    private fun encryptPayload(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        if (data.size < 16) throw IllegalArgumentException("payload shorter than 16 bytes")
        val result = data.copyOf()
        encryptChunk(key, iv, result, 0)
        if (result.size > 16) encryptChunk(key, iv, result, result.size - 16)
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

    private fun log(msg: String, level: String = "info") {
        when (level) {
            "error" -> Log.e("GanBleNativeTest", msg)
            "warn" -> Log.w("GanBleNativeTest", msg)
            "ok" -> Log.i("GanBleNativeTest", msg)
            else -> Log.d("GanBleNativeTest", msg)
        }
    }
}
