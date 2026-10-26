package com.example.btremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var hid: BluetoothHidDevice? = null
    private var target: BluetoothDevice? = null
    private lateinit var status: TextView
    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val handler = Handler(Looper.getMainLooper())

    private val sdp = BluetoothHidDeviceAppSdpSettings(
        "BT Remote Proto",
        "Telecommande Bluetooth de prototype",
        "AD TECH",
        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
        KEYBOARD_DESCRIPTOR
    )

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            setStatus(
                if (registered) "Prêt. Appuie sur « Choisir l'appareil »."
                else "Enregistrement HID refusé."
            )
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            setStatus(
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> "Connecté à ${device?.name ?: "l'appareil"}."
                    BluetoothProfile.STATE_CONNECTING -> "Connexion en cours..."
                    else -> "Déconnecté."
                }
            )
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            hid = proxy as BluetoothHidDevice
            hid?.registerApp(sdp, null, null, mainExecutor, hidCallback)
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            setStatus("Service HID arrêté.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (hasBluetoothPermission()) {
            start()
        } else {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            setStatus("Permission Bluetooth refusée.")
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun start() {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            setStatus("Active le Bluetooth puis relance l'appli.")
        } else {
            bt.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickTarget() {
        val h = hid ?: run {
            setStatus("HID pas encore prêt.")
            return
        }
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            setStatus("Aucun appareil appairé. Appaire le téléphone fissuré dans les réglages Bluetooth.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Appareil à contrôler")
            .setItems(devices.map { it.name ?: it.address }.toTypedArray()) { _, i ->
                target = devices[i]
                h.connect(devices[i])
                setStatus("Connexion à ${devices[i].name ?: devices[i].address}...")
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun tap(reportId: Int, press: ByteArray, release: ByteArray) {
        val h = hid
        val dev = target
        if (h == null || dev == null) {
            toast("Choisis d'abord l'appareil.")
            return
        }
        if (h.getConnectionState(dev) != BluetoothProfile.STATE_CONNECTED) {
            toast("Pas encore connecté.")
            return
        }
        h.sendReport(dev, reportId, press)
        // Relâchement après 30 ms, sinon le téléphone garde la touche enfoncée
        handler.postDelayed({ h.sendReport(dev, reportId, release) }, 30)
    }

    // Touches du clavier (page Keyboard)
    private fun pressKey(keyCode: Int) =
        tap(REPORT_KEYBOARD, byteArrayOf(0, 0, keyCode.toByte(), 0, 0, 0, 0, 0), ByteArray(8))

    // Touches multimédia et Accueil/Retour (page Consumer, 16 bits)
    private fun pressConsumer(usage: Int) =
        tap(
            REPORT_CONSUMER,
            byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte()),
            ByteArray(2)
        )

    // Veille, réveil, extinction (page Generic Desktop, System Control), un bit par action
    private fun pressSystem(bit: Int) =
        tap(REPORT_SYSTEM, byteArrayOf((1 shl bit).toByte()), ByteArray(1))

    private fun setStatus(message: String) {
        status.text = message
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun buildUi() {
        status = TextView(this).apply {
            textSize = 16f
            text = "Initialisation..."
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(status)
            addView(Button(this@MainActivity).apply {
                text = "Choisir l'appareil"
                setOnClickListener { pickTarget() }
            })
            addView(keyRow("↑" to { pressKey(UP) }))
            addView(keyRow("←" to { pressKey(LEFT) }, "Entrée" to { pressKey(ENTER) }, "→" to { pressKey(RIGHT) }))
            addView(keyRow("↓" to { pressKey(DOWN) }))
            addView(keyRow("Tab" to { pressKey(TAB) }, "Échap" to { pressKey(ESC) }, "Suppr" to { pressKey(BACKSPACE) }))
            addView(keyRow("Vol −" to { pressConsumer(VOLUME_DOWN) }, "Vol +" to { pressConsumer(VOLUME_UP) }, "Muet" to { pressConsumer(MUTE) }))
            addView(keyRow("Accueil" to { pressConsumer(HOME) }, "Retour" to { pressConsumer(BACK) }))
            addView(keyRow("Veille" to { pressSystem(SYS_SLEEP) }, "Réveil" to { pressSystem(SYS_WAKE) }, "Éteindre" to { pressSystem(SYS_POWER_OFF) }))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun keyRow(vararg keys: Pair<String, () -> Unit>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            keys.forEach { (label, action) ->
                addView(
                    Button(this@MainActivity).apply {
                        text = label
                        setOnClickListener { action() }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
        }

    companion object {
        // Identifiants de rapport (un par collection du descripteur)
        private const val REPORT_KEYBOARD = 1
        private const val REPORT_CONSUMER = 2
        private const val REPORT_SYSTEM = 3

        // Codes d'usage du clavier
        private const val UP = 0x52
        private const val DOWN = 0x51
        private const val LEFT = 0x50
        private const val RIGHT = 0x4F
        private const val ENTER = 0x28
        private const val ESC = 0x29
        private const val BACKSPACE = 0x2A
        private const val TAB = 0x2B

        // Codes d'usage Consumer
        private const val VOLUME_UP = 0xE9
        private const val VOLUME_DOWN = 0xEA
        private const val MUTE = 0xE2
        private const val HOME = 0x223
        private const val BACK = 0x224

        // Bits du System Control
        private const val SYS_POWER_OFF = 0
        private const val SYS_SLEEP = 1
        private const val SYS_WAKE = 2

        // Descripteur HID : clavier + Consumer Control + System Control
        private val KEYBOARD_DESCRIPTOR = intArrayOf(
            // Clavier (ID 1)
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01,
            0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
            0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            // Consumer Control (ID 2)
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01,
            0x85, 0x02,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0,
            // System Control (ID 3)
            0x05, 0x01, 0x09, 0x80, 0xA1, 0x01,
            0x85, 0x03,
            0x19, 0x81, 0x29, 0x83, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02,
            0x95, 0x05, 0x81, 0x01,
            0xC0
        ).map { it.toByte() }.toByteArray()
    }
}
