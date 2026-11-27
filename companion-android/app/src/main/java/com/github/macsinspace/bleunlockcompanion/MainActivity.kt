package com.github.macsinspace.bleunlockcompanion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var toggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        statusView = TextView(this).apply { textSize = 18f; gravity = Gravity.START }
        root.addView(statusView)
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { onClick() }; root.addView(this)
        }
        button("Scan pairing QR from Mac") { scanQr() }
        button("Paste pairing code") { pasteDialog() }
        toggle = button("Start") {
            if (CompanionService.instance != null) CompanionService.stop(this) else ensurePermissionsThenStart()
        }
        button("Unpair") {
            CompanionService.stop(this)
            KeyStorage.clear(this)
            render()
        }
        setContentView(root)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }

    /** `adb shell am start -n <pkg>/.MainActivity --es pair "bleunlock-companion:1:..."` for testing. */
    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("pair")?.let { pair(it) }
    }

    private fun render() {
        val paired = KeyStorage.isPaired(this)
        val running = CompanionService.instance != null
        val id = KeyStorage.keyId(this)?.toHex() ?: "-"
        statusView.text = buildString {
            append(if (paired) "Paired (key id $id)\n" else "Not paired — scan the QR code shown by BLEUnlock on your Mac.\n")
            append("Service: ${if (running) CompanionService.status else "Stopped"}\n")
        }
        toggle.text = if (running) "Stop" else "Start"
        toggle.isEnabled = paired
    }

    private fun pair(text: String) {
        val k = Protocol.parsePairing(text)
        if (k == null) {
            Toast.makeText(this, "Not a BLEUnlock pairing code", Toast.LENGTH_LONG).show()
            return
        }
        val wasRunning = CompanionService.instance != null
        if (wasRunning) CompanionService.stop(this)
        KeyStorage.store(this, k)
        Toast.makeText(this, "Paired", Toast.LENGTH_SHORT).show()
        ensurePermissionsThenStart()
        render()
    }

    private fun scanQr() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { it.rawValue?.let(::pair) }
            .addOnFailureListener { Toast.makeText(this, "Scan failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun pasteDialog() {
        val field = EditText(this).apply { hint = "bleunlock-companion:1:…" }
        AlertDialog.Builder(this)
            .setTitle("Pairing code")
            .setView(field)
            .setPositiveButton("Pair") { _, _ -> pair(field.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ensurePermissionsThenStart() {
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            if (KeyStorage.isPaired(this)) CompanionService.start(this)
            render()
        } else {
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        val needed = setOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        val ok = needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (ok && KeyStorage.isPaired(this)) CompanionService.start(this)
        else if (!ok) Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
        render()
    }
}
