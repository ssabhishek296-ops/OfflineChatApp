package com.example.offlinechat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class MainActivity : AppCompatActivity() {

    // Service ID must be unique to your app — both devices must use the same one
    private val SERVICE_ID = "com.example.offlinechat.SERVICE"
    private val STRATEGY = Strategy.P2P_CLUSTER

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var statusText: TextView
    private lateinit var chatLog: TextView
    private lateinit var messageInput: EditText

    // Track connected endpoint(s) so we know who to send messages to
    private val connectedEndpoints = mutableSetOf<String>()

    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionsClient = Nearby.getConnectionsClient(this)
        statusText = findViewById(R.id.statusText)
        chatLog = findViewById(R.id.chatLog)
        messageInput = findViewById(R.id.messageInput)

        requestPermissions()

        findViewById<Button>(R.id.btnAdvertise).setOnClickListener { startAdvertising() }
        findViewById<Button>(R.id.btnDiscover).setOnClickListener { startDiscovery() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendMessage() }
    }

    private fun requestPermissions() {
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    // ---------- ADVERTISING (this device becomes discoverable) ----------
    private fun startAdvertising() {
        val deviceName = Build.MODEL
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()

        connectionsClient.startAdvertising(
            deviceName, SERVICE_ID, connectionLifecycleCallback, options
        ).addOnSuccessListener {
            statusText.text = "Status: Advertising as $deviceName"
        }.addOnFailureListener {
            statusText.text = "Status: Advertising failed - ${it.message}"
        }
    }

    // ---------- DISCOVERY (this device looks for others) ----------
    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, options
        ).addOnSuccessListener {
            statusText.text = "Status: Discovering..."
        }.addOnFailureListener {
            statusText.text = "Status: Discovery failed - ${it.message}"
        }
    }

    // ---------- Callback: fires when a nearby device is found ----------
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Auto-request connection when a device is found
            connectionsClient.requestConnection(
                Build.MODEL, endpointId, connectionLifecycleCallback
            )
        }

        override fun onEndpointLost(endpointId: String) {
            appendLog("Lost endpoint: $endpointId")
        }
    }

    // ---------- Callback: handles connection init, result, disconnect ----------
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept for simplicity; in production show a confirmation dialog
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                statusText.text = "Status: Connected to $endpointId"
                appendLog("Connected: $endpointId")
            } else {
                appendLog("Connection failed: $endpointId")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            appendLog("Disconnected: $endpointId")
        }
    }

    // ---------- Callback: handles incoming messages ----------
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val message = String(payload.asBytes()!!)
                appendLog("Them: $message")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Track progress for large payloads if needed
        }
    }

    // ---------- Send a text message to all connected devices ----------
    private fun sendMessage() {
        val text = messageInput.text.toString()
        if (text.isBlank() || connectedEndpoints.isEmpty()) return

        val payload = Payload.fromBytes(text.toByteArray())
        connectedEndpoints.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, payload)
        }
        appendLog("Me: $text")
        messageInput.setText("")
    }

    private fun appendLog(line: String) {
        chatLog.append("\n$line")
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
    }
}
