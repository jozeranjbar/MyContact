package com.mycontact.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity(), Session.ChatEventListener {

    private lateinit var adapter: MessageAdapter
    private lateinit var rv: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private lateinit var statusPill: LinearLayout
    private lateinit var tvPeerSub: TextView

    private val pickDocument = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { sendFile(it) }
    }
    private val pickGallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { sendFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        rv = findViewById(R.id.rvMessages)
        tvEmpty = findViewById(R.id.tvEmptyChat)
        etInput = findViewById(R.id.etChatInput)
        btnSend = findViewById(R.id.btnSendMsg)
        tvStatus = findViewById(R.id.tvChatStatus)
        statusPill = findViewById(R.id.chatStatusPill)
        tvPeerSub = findViewById(R.id.tvChatPeerSub)

        adapter = MessageAdapter(Session.history) { m -> openFile(m) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        refreshEmptyState()

        setComposerEnabled(Session.connected)

        etInput.doAfterTextChangedCompat { updateSendEnabled() }

        btnSend.setOnClickListener { sendCurrentMessage() }

        findViewById<ImageButton>(R.id.btnChatSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnAttachFile).setOnClickListener { showAttachDialog() }
        findViewById<ImageButton>(R.id.btnStartCall).setOnClickListener { showCallDialog() }

        if (Session.connected) {
            onStatus("متصل", "good")
        }
        tvPeerSub.text = Session.peerName ?: getString(R.string.chat_not_connected)
    }

    override fun onStart() {
        super.onStart()
        Session.chatListener = this
    }

    override fun onStop() {
        super.onStop()
        Session.chatListener = null
    }

    private fun refreshEmptyState() {
        tvEmpty.visibility = if (Session.history.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setComposerEnabled(enabled: Boolean) {
        etInput.isEnabled = enabled
        etInput.hint = getString(if (enabled) R.string.msg_placeholder else R.string.msg_placeholder_disabled)
        updateSendEnabled()
    }

    private fun updateSendEnabled() {
        btnSend.isEnabled = Session.connected && etInput.text.toString().isNotBlank()
    }

    private fun sendCurrentMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        if (!Session.connected) { Toast.makeText(this, R.string.toast_need_connect_to_send, Toast.LENGTH_SHORT).show(); return }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val name = Session.prefs.displayName.ifBlank { null }
        Session.webRtc?.sendControl(ControlMessage(kind = ControlMessage.KIND_TEXT, text = text, senderName = name, time = time))
        val m = Message(type = "text", dir = "out", text = text, time = time, senderName = name)
        Session.addHistory(m)
        adapter.addMessage(m)
        rv.scrollToPosition(adapter.itemCount - 1)
        refreshEmptyState()
        etInput.setText("")
    }

    private fun showAttachDialog() {
        if (!Session.connected) { Toast.makeText(this, R.string.toast_need_connect_to_file, Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.send_dialog_title)
            .setMessage(R.string.send_dialog_desc)
            .setNegativeButton(R.string.btn_cancel, null)
            .setItems(arrayOf(getString(R.string.pick_gallery), getString(R.string.pick_file))) { _, which ->
                if (which == 0) pickGallery.launch("image/*") else pickDocument.launch("*/*")
            }
            .show()
    }

    private fun sendFile(uri: Uri) {
        Session.fileTransfer?.sendFile(uri, lifecycleScope)
    }

    private fun openFile(m: Message) {
        val path = m.filePath ?: return
        try {
            val file = File(path)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, null))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.file_no_longer_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCallDialog() {
        if (!Session.connected) { Toast.makeText(this, R.string.toast_need_connect_to_call, Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.call_dialog_title)
            .setMessage(R.string.call_dialog_desc)
            .setNegativeButton(R.string.btn_cancel, null)
            .setItems(arrayOf(getString(R.string.call_audio), getString(R.string.call_video))) { _, which ->
                val kind = if (which == 0) "audio" else "video"
                startActivity(Intent(this, CallActivity::class.java).putExtra(CallActivity.EXTRA_OUTGOING_KIND, kind))
            }
            .show()
    }

    // ---------------- Session.ChatEventListener ----------------

    override fun onStatus(text: String, kind: String) {
        runOnUiThread {
            tvStatus.text = text
            val bg = when (kind) {
                "good" -> R.drawable.pill_good
                "bad" -> R.drawable.pill_bad
                "progress" -> R.drawable.pill_progress
                else -> R.drawable.pill_idle
            }
            statusPill.setBackgroundResource(bg)
            setComposerEnabled(kind == "good")
            tvPeerSub.text = if (kind == "good") (Session.peerName ?: getString(R.string.chat_online)) else getString(R.string.chat_not_connected)
        }
    }

    override fun onMessage(m: Message) {
        runOnUiThread {
            adapter.addMessage(m)
            rv.scrollToPosition(adapter.itemCount - 1)
            refreshEmptyState()
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED).not()) {
                val body = m.text ?: m.fileName ?: ""
                NotificationHelper.notify(this, m.senderName ?: getString(R.string.app_name), body)
            }
        }
    }

    override fun onTransferUpdate(transferId: String, transform: (Message) -> Message) {
        runOnUiThread { adapter.updateTransfer(transferId, transform) }
    }

    override fun onIncomingCallOffer(offer: ControlMessage) {
        runOnUiThread {
            val kindLabel = if (offer.callKind == "video") getString(R.string.call_video) else getString(R.string.call_audio)
            AlertDialog.Builder(this)
                .setTitle(kindLabel)
                .setMessage(Session.peerName ?: getString(R.string.call_peer_default))
                .setCancelable(false)
                .setNegativeButton(R.string.btn_cancel) { _, _ ->
                    Session.webRtc?.sendControl(ControlMessage(kind = ControlMessage.KIND_CALL_END))
                }
                .setPositiveButton(R.string.btn_ok) { _, _ ->
                    startActivity(
                        Intent(this, CallActivity::class.java)
                            .putExtra(CallActivity.EXTRA_INCOMING_OFFER_SDP, offer.sdp)
                            .putExtra(CallActivity.EXTRA_INCOMING_OFFER_KIND, offer.callKind)
                    )
                }
                .show()
        }
    }
}

/** Small helper so we don't need to add a TextWatcher boilerplate at each call site. */
private fun EditText.doAfterTextChangedCompat(action: () -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = action()
    })
}
