package com.foresight.gateway.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.foresight.gateway.capture.CaptureForegroundService

/** Minimal visible control surface; it never owns capture after the service starts. */
class GatewayActivity : Activity() {
    private lateinit var endpointInput: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        endpointInput = EditText(this).apply {
            hint = "rtsp://LAPTOP_IP:8554/foresight-phone"
            setText(preferences().getString(PREF_LAST_ENDPOINT, ""))
        }
        statusText = TextView(this).apply {
            textSize = 16f
        }
        setContentView(buildContent())
        requestCapturePermissions()
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun buildContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(48, 48, 48, 48)

        addView(endpointInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        addView(Button(this@GatewayActivity).apply {
            text = "Start Capture"
            setOnClickListener { startCapture() }
        })
        addView(Button(this@GatewayActivity).apply {
            text = "Stop Capture"
            setOnClickListener { stopCapture() }
        })
        addView(statusText)
    }

    private fun startCapture() {
        if (!hasCapturePermissions()) {
            requestCapturePermissions()
            return
        }
        val intent = Intent(this, CaptureForegroundService::class.java)
            .setAction(CaptureForegroundService.ACTION_START)
            .putExtra(CaptureForegroundService.EXTRA_ENDPOINT, endpointInput.text.toString().trim())
        preferences().edit().putString(PREF_LAST_ENDPOINT, endpointInput.text.toString().trim()).apply()
        startForegroundService(intent)
        statusText.text = "Starting capture..."
    }

    private fun stopCapture() {
        startService(
            Intent(this, CaptureForegroundService::class.java)
                .setAction(CaptureForegroundService.ACTION_STOP),
        )
        statusText.text = "Stopping capture..."
    }

    private fun renderStatus() {
        val status = CaptureForegroundService.currentStatus
        val metadata = status.metadata
        statusText.text = buildString {
            append("Status: ${status.lifecycle}")
            status.detail?.let { append("\nDetail: $it") }
            metadata?.let {
                append("\nSession: ${it.captureSessionId}")
                append("\nSource: ${it.source.sourceDevice}")
                append("\nEndpoint: ${it.streamEndpoint}")
            }
        }
    }

    private fun requestCapturePermissions() {
        requestPermissions(requiredPermissions(), REQUEST_CAPTURE_PERMISSIONS)
    }

    private fun hasCapturePermissions(): Boolean =
        requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun preferences() = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)

    companion object {
        private const val REQUEST_CAPTURE_PERMISSIONS = 1
        private const val PREFERENCES_NAME = "foresight_gateway"
        private const val PREF_LAST_ENDPOINT = "last_rtsp_endpoint"
    }
}
