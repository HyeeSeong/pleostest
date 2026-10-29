package com.example.pleostest

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pleostest.bridge.BridgeStatus
import com.example.pleostest.vehicle.*
import java.time.Instant

class MainActivity : AppCompatActivity() {
    private lateinit var readButton: Button
    private lateinit var output: TextView
    private lateinit var bridgeStatus: TextView
    private var bridge: BridgeService? = null
    private var bound = false
    private var uiTicket: DoorRequestTicket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.door_title)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply { setText(R.string.door_help) })
        readButton = Button(this).apply {
            setText(R.string.door_read)
            setOnClickListener {
                isEnabled = false
                output.setText(R.string.door_waiting)
                when (val admission = bridge?.reader?.read { result ->
                    uiTicket = null
                    output.text = render(result)
                    isEnabled = true
                }) {
                    is DoorAdmission.Accepted -> uiTicket = admission.ticket
                    DoorAdmission.Busy -> {
                        output.setText(R.string.door_busy)
                        isEnabled = true
                    }
                    DoorAdmission.Unavailable, null -> {
                        output.setText(R.string.door_not_ready)
                        isEnabled = true
                    }
                }
            }
        }
        content.addView(readButton)
        output = TextView(this).apply {
            setTextIsSelectable(true)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        output.setText(R.string.door_ready)
        content.addView(output)
        bridgeStatus = TextView(this).apply {
            setPadding(0, padding, 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(bridgeStatus)
        content.addView(Button(this).apply {
            setText(R.string.bridge_stop)
            setOnClickListener { bridge?.requestStop() }
        })
        if (savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Only decides whether the notification (and its stop action) is visible; the screen's
            // stop button works either way.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        bridgeStatus.setText(R.string.bridge_connecting)
        // Allowed because the screen is visible. The service outlives this screen; onStop only unbinds.
        val intent = Intent(this, BridgeService::class.java)
        startForegroundService(intent)
        bound = bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        uiTicket?.let { bridge?.reader?.cancel(it) }
        uiTicket = null
        bridge?.statusListener = null
        bridge = null
        if (bound) unbindService(connection)
        bound = false
        super.onStop()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as BridgeService.LocalBinder).service
            bridge = service
            service.statusListener = ::showStatus
            showStatus(service.status)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bridge?.statusListener = null
            bridge = null
            bridgeStatus.setText(R.string.bridge_connecting)
        }
    }

    private fun showStatus(status: BridgeStatus) {
        bridgeStatus.text = when (status) {
            BridgeStatus.Starting -> getString(R.string.bridge_starting)
            BridgeStatus.Listening -> getString(R.string.bridge_listening)
            is BridgeStatus.Failed -> getString(R.string.bridge_failed, status.errorType)
            BridgeStatus.Stopped -> getString(R.string.bridge_stopped)
        }
    }

    private fun render(result: DoorReadResult): String {
        val description = when (result.status) {
            DoorReadStatus.CLOSED -> R.string.door_closed
            DoorReadStatus.OPEN -> R.string.door_open
            DoorReadStatus.NO_VALUE -> R.string.door_no_value
            DoorReadStatus.UNKNOWN_VALUE -> R.string.door_unknown_value
            DoorReadStatus.CAPABILITY_FAILED -> R.string.door_capability_failed
            DoorReadStatus.READ_UNAVAILABLE -> R.string.door_read_unavailable
            DoorReadStatus.AREA_UNSUPPORTED -> R.string.door_area_unsupported
            DoorReadStatus.SUPPORT_UNCONFIRMED -> R.string.door_support_unconfirmed
            DoorReadStatus.READ_FAILED -> R.string.door_read_failed
            DoorReadStatus.TIMEOUT -> R.string.door_timeout
            DoorReadStatus.CANCELLED -> R.string.door_cancelled
        }
        return buildString {
            appendLine(getString(description))
            appendLine(getString(R.string.door_target, result.area))
            result.capability?.let {
                appendLine(getString(R.string.door_capability, it.access, it.reportedAreas,
                    it.minimum?.toString() ?: "?", it.maximum?.toString() ?: "?"))
            }
            if (result.receivedAtMillis != null) {
                appendLine(getString(R.string.door_value, result.rawValue?.toString() ?: "null",
                    Instant.ofEpochMilli(result.receivedAtMillis).toString()))
            }
            result.errorType?.let { appendLine(getString(R.string.door_error, it)) }
        }
    }
}
