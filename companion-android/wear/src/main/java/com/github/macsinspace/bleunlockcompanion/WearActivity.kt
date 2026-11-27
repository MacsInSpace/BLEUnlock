package com.github.macsinspace.bleunlockcompanion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Minimal watch UI. The watch has no camera, so pairing is done from the Mac with
 * companion-android/pair-watch.sh, which delivers the code via:
 *   adb shell am start -n com.github.macsinspace.bleunlockcompanion/.WearActivity --es pair "<code>"
 */
class WearActivity : Activity() {
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
        val pad = (14 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad * 2, pad * 2, pad * 2, pad * 3) // room for the round bezel
        }
        statusView = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER }
        column.addView(statusView)
        toggle = Button(this).apply {
            setOnClickListener {
                if (CompanionService.instance != null) CompanionService.stop(this@WearActivity) else ensurePermissionsThenStart()
            }
        }
        column.addView(toggle)
        column.addView(Button(this).apply {
            text = "Unpair"
            setOnClickListener {
                CompanionService.stop(this@WearActivity)
                KeyStorage.clear(this@WearActivity)
                render()
            }
        })
        setContentView(ScrollView(this).apply { addView(column) })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }

    private fun handleIntent(intent: Intent?) {
        val code = intent?.getStringExtra("pair") ?: return
        intent.removeExtra("pair")
        val k = Protocol.parsePairing(code)
        if (k == null) { Toast.makeText(this, "Bad pairing code", Toast.LENGTH_LONG).show(); return }
        if (CompanionService.instance != null) CompanionService.stop(this)
        KeyStorage.store(this, k)
        Toast.makeText(this, "Paired", Toast.LENGTH_SHORT).show()
        ensurePermissionsThenStart()
    }

    private fun render() {
        val paired = KeyStorage.isPaired(this)
        val running = CompanionService.instance != null
        statusView.text = if (!paired) {
            "BLEUnlock\nNot paired.\nRun pair-watch.sh on the Mac."
        } else {
            "BLEUnlock\nKey ${KeyStorage.keyId(this)?.toHex()}\n${if (running) CompanionService.status else "Stopped"}"
        }
        toggle.text = if (running) "Stop" else "Start"
        toggle.isEnabled = paired
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
        val ok = listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (ok && KeyStorage.isPaired(this)) CompanionService.start(this)
        render()
    }
}
