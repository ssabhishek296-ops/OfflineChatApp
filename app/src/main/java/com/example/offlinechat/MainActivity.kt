package com.example.offlinechat

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.File
import java.io.FileOutputStream
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.view.MotionEvent
import java.io.PipedInputStream
import java.io.PipedOutputStream
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private val SERVICE_ID = "com.example.offlinechat.SERVICE"
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val PREFS = "offline_chat_prefs"

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var scanStatus: TextView
    private lateinit var deviceListContainer: LinearLayout
    private lateinit var devicesScreen: LinearLayout
    private lateinit var chatScreen: LinearLayout
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var connectedToLabel: TextView
    private lateinit var messageInput: EditText
    private lateinit var btnMyName: TextView

    private var myName: String = "My Phone"
    // endpointId -> discovered device name, shown while not yet connected
    private val discoveredDevices = mutableMapOf<String, String>()
    // endpointId -> row view, so we can update/remove it
    private val deviceRowViews = mutableMapOf<String, View>()
    private var connectedEndpointId: String? = null
    private var connectedDeviceName: String = ""
    // payloadId -> the FILE payload, kept until its transfer completes
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()
    private val SAMPLE_RATE = 16000
    private var audioRecord: AudioRecord? = null
    private var isTalking = false

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { sendImage(it) }
    }

    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionsClient = Nearby.getConnectionsClient(this)

        scanStatus = findViewById(R.id.scanStatus)
        deviceListContainer = findViewById(R.id.deviceListContainer)
        devicesScreen = findViewById(R.id.devicesScreen)
        chatScreen = findViewById(R.id.chatScreen)
        chatContainer = findViewById(R.id.chatContainer)
        chatScroll = findViewById(R.id.chatScroll)
        connectedToLabel = findViewById(R.id.connectedToLabel)
        messageInput = findViewById(R.id.messageInput)
        btnMyName = findViewById(R.id.btnMyName)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        myName = prefs.getString("my_name", "") ?: ""

        btnMyName.setOnClickListener { showNameDialog() }
        findViewById<TextView>(R.id.btnSend).setOnClickListener { sendMessage() }
        findViewById<TextView>(R.id.btnAttach).setOnClickListener { pickImageLauncher.launch("image/*") }

        requestPermissions()
        createNotificationChannel()

        val callButton = findViewById<TextView>(R.id.btnCall)
        callButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTalking()
                    v.setBackgroundColor(Color.parseColor("#0B7A6E"))
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopTalking()
                    v.setBackgroundColor(Color.TRANSPARENT)
                    true
                }
                else -> false
            }
        }

        if (myName.isBlank()) {
            showNameDialog(firstTime = true)
        } else {
            startEverything()
        }
    }

    private fun showNameDialog(firstTime: Boolean = false) {
        val input = EditText(this)
        input.hint = "e.g. Rahul's Phone"
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(myName)

        AlertDialog.Builder(this)
            .setTitle("Set your device name")
            .setMessage("This is the name other nearby devices will see.")
            .setView(input)
            .setCancelable(!firstTime)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                myName = if (name.isBlank()) "My Phone" else name
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString("my_name", myName).apply()
                restartAdvertising()
                if (firstTime) startEverything()
            }
            .show()
    }

    private fun startEverything() {
        startAdvertising()
        startDiscovery()
    }

    private fun requestPermissions() {
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    private fun restartAdvertising() {
        connectionsClient.stopAdvertising()
        startAdvertising()
    }

    // ---------- ADVERTISING: makes this device visible under myName ----------
    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(myName, SERVICE_ID, connectionLifecycleCallback, options)
    }

    // ---------- DISCOVERY: automatically scans for nearby devices ----------
    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener { scanStatus.text = "ðŸ” Searching for nearby devices..." }
            .addOnFailureListener { scanStatus.text = "Discovery failed - ${it.message}" }
    }

    // ---------- Found a nearby device: show it in the tappable list ----------
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            discoveredDevices[endpointId] = info.endpointName
            addOrUpdateDeviceRow(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            discoveredDevices.remove(endpointId)
            deviceRowViews[endpointId]?.let { deviceListContainer.removeView(it) }
            deviceRowViews.remove(endpointId)
        }
    }

    private fun addOrUpdateDeviceRow(endpointId: String, name: String) {
        if (deviceRowViews.containsKey(endpointId)) return

        val row = TextView(this)
        row.text = "ðŸ“±  $name"
        row.textSize = 16f
        row.setTextColor(Color.parseColor("#1A1A1A"))
        row.setBackgroundResource(R.drawable.device_card)
        row.setPadding(20, 20, 20, 20)
        row.isClickable = true
        row.isFocusable = true

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(16, 8, 16, 8)
        row.layoutParams = params

        // Tap this device to connect instantly
        row.setOnClickListener {
            scanStatus.text = "Connecting to $name..."
            connectionsClient.requestConnection(myName, endpointId, connectionLifecycleCallback)
        }

        deviceListContainer.addView(row)
        deviceRowViews[endpointId] = row
    }

    // ---------- Handles connection request/accept/result/disconnect ----------
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept so tapping a device connects immediately, no extra prompt
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpointId = endpointId
                connectedDeviceName = discoveredDevices[endpointId] ?: "Device"
                connectionsClient.stopDiscovery()
                showChatScreen()
            } else {
                scanStatus.text = "Connection failed, try again"
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpointId = null
            showDeviceListScreen()
            startDiscovery()
        }
    }

    private fun showChatScreen() {
        devicesScreen.visibility = View.GONE
        chatScreen.visibility = View.VISIBLE
        connectedToLabel.text = "ðŸŸ¢ Connected to $connectedDeviceName"
        chatContainer.removeAllViews()
    }

    private fun showDeviceListScreen() {
        chatScreen.visibility = View.GONE
        devicesScreen.visibility = View.VISIBLE
        discoveredDevices.clear()
        deviceRowViews.clear()
        deviceListContainer.removeAllViews()
        scanStatus.text = "ðŸ” Searching for nearby devices..."
    }

    // ---------- Incoming messages and images ----------
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val message = String(payload.asBytes()!!)
                    addBubble(message, isMine = false)
                    showMessageNotification(connectedDeviceName, message)
                }
                Payload.Type.FILE -> {
                    incomingFilePayloads[payload.id] = payload
                }
                Payload.Type.STREAM -> {
                    val inputStream = payload.asStream()?.asInputStream()
                    if (inputStream != null) playIncomingAudio(inputStream)
                }
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val payload = incomingFilePayloads[update.payloadId] ?: return
                val file = payload.asFile() ?: return
                try {
                    val sourceUri = file.asUri()
                    val inputStream = if (sourceUri != null) {
                        contentResolver.openInputStream(sourceUri)
                    } else {
                        java.io.FileInputStream(file.asJavaFile())
                    }
                    val localFile = File(cacheDir, "recv_${update.payloadId}.jpg")
                    FileOutputStream(localFile).use { out -> inputStream?.copyTo(out) }
                    inputStream?.close()
                    addImageBubble(localFile.absolutePath, isMine = false)
                } catch (e: Exception) {
                    addBubble("âš ï¸ Failed to receive photo", isMine = false)
                }
                incomingFilePayloads.remove(update.payloadId)
            }
        }
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        val endpointId = connectedEndpointId ?: return
        if (text.isBlank()) return

        connectionsClient.sendPayload(endpointId, Payload.fromBytes(text.toByteArray()))
        addBubble(text, isMine = true)
        messageInput.setText("")
    }

    // ---------- Send a picked photo to the connected device ----------
    private fun sendImage(uri: Uri) {
        val endpointId = connectedEndpointId ?: return
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val tempFile = File(cacheDir, "send_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { out -> inputStream.copyTo(out) }
            inputStream.close()

            val payload = Payload.fromFile(tempFile)
            connectionsClient.sendPayload(endpointId, payload)
            addImageBubble(tempFile.absolutePath, isMine = true)
        } catch (e: Exception) {
            addBubble("âš ï¸ Failed to send photo", isMine = true)
        }
    }

    // ---------- Adds a WhatsApp-style chat bubble ----------
    private fun addBubble(text: String, isMine: Boolean) {
        val bubble = TextView(this)
        bubble.text = text
        bubble.textSize = 15f
        bubble.setTextColor(Color.parseColor("#1A1A1A"))
        bubble.setPadding(28, 18, 28, 18)
        bubble.setBackgroundResource(if (isMine) R.drawable.bubble_sent else R.drawable.bubble_received)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        row.gravity = if (isMine) Gravity.END else Gravity.START

        val bubbleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        bubbleParams.setMargins(60, 6, 16, 6)
        if (isMine) bubbleParams.setMargins(16, 6, 60, 6)
        bubble.layoutParams = bubbleParams

        row.addView(bubble)
        chatContainer.addView(row)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- Adds a photo bubble ----------
    private fun addImageBubble(filePath: String, isMine: Boolean) {
        val bitmap = BitmapFactory.decodeFile(filePath) ?: return

        val imageView = ImageView(this)
        imageView.setImageBitmap(bitmap)
        imageView.adjustViewBounds = true
        imageView.setBackgroundResource(if (isMine) R.drawable.bubble_sent else R.drawable.bubble_received)
        imageView.setPadding(6, 6, 6, 6)

        val imgParams = LinearLayout.LayoutParams(420, LinearLayout.LayoutParams.WRAP_CONTENT)
        imageView.layoutParams = imgParams

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        row.gravity = if (isMine) Gravity.END else Gravity.START

        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rowParams.setMargins(60, 6, 16, 6)
        if (isMine) rowParams.setMargins(16, 6, 60, 6)
        row.layoutParams = rowParams

        row.addView(imageView)
        chatContainer.addView(row)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startTalking() {
        val endpointId = connectedEndpointId ?: return
        if (isTalking) return
        isTalking = true

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize
            )
        } catch (e: SecurityException) {
            isTalking = false
            return
        }

        val pipedInputStream = PipedInputStream()
        val pipedOutputStream = PipedOutputStream(pipedInputStream)
        connectionsClient.sendPayload(endpointId, Payload.fromStream(pipedInputStream))

        audioRecord?.startRecording()
        Thread {
            val buffer = ByteArray(minBufSize)
            while (isTalking) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    try { pipedOutputStream.write(buffer, 0, read) } catch (e: Exception) { break }
                }
            }
            try { pipedOutputStream.close() } catch (e: Exception) {}
        }.start()
    }

    private fun stopTalking() {
        isTalking = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun playIncomingAudio(inputStream: java.io.InputStream) {
        Thread {
            val minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBufSize, AudioTrack.MODE_STREAM
            )
            audioTrack.play()
            val buffer = ByteArray(minBufSize)
            try {
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    audioTrack.write(buffer, 0, read)
                }
            } catch (e: Exception) {
            } finally {
                audioTrack.stop()
                audioTrack.release()
            }
        }.start()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "offline_chat_messages", "Chat Messages", NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun showMessageNotification(senderName: String, message: String) {
        val builder = NotificationCompat.Builder(this, "offline_chat_messages")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(senderName)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(this).notify(1001, builder.build())
        } catch (e: SecurityException) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
    }
}
