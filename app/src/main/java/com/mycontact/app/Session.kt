package com.mycontact.app

import android.content.Context
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection

/**
 * App-wide singleton holding the live connection + chat state — the native
 * counterpart of the original app's single global `State` object. It
 * outlives any one Activity so the WebRTC connection survives navigating
 * from the connect screen to chat to a call and back, exactly like the
 * single-page app kept everything in one JS context.
 */
object Session {
    lateinit var appContext: Context
    lateinit var prefs: Prefs
    var webRtc: WebRtcManager? = null
    var fileTransfer: FileTransferManager? = null

    var role: String? = null              // "starter" | "receiver"
    var peerName: String? = null
    var connected: Boolean = false
    var reconnecting: Boolean = false
    var everConnected: Boolean = false
    var inCall: Boolean = false
    var callKind: String? = null          // "audio" | "video"

    val history = mutableListOf<Message>()

    // Currently visible screens register themselves so events reach the UI
    // that's actually on screen — like calling UI.* directly in the original.
    var connectListener: ConnectEventListener? = null
    var chatListener: ChatEventListener? = null
    var callListener: CallEventListener? = null

    interface ConnectEventListener {
        fun onStatus(text: String, kind: String)
        fun onLog(line: String)
    }
    interface ChatEventListener {
        fun onStatus(text: String, kind: String)
        fun onMessage(m: Message)
        fun onTransferUpdate(transferId: String, transform: (Message) -> Message)
        fun onIncomingCallOffer(offer: ControlMessage)
    }
    interface CallEventListener {
        fun onRemoteTrack(track: MediaStreamTrack)
        fun onRemoteHangup()
        fun onCallAnswered()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        history.clear()
        history.addAll(prefs.loadHistory())
    }

    fun addHistory(m: Message) {
        history.add(m)
        prefs.saveHistory(history)
    }

    fun updateHistoryTransfer(transferId: String, transform: (Message) -> Message) {
        val idx = history.indexOfFirst { it.transferId == transferId }
        if (idx >= 0) {
            history[idx] = transform(history[idx])
            prefs.saveHistory(history)
        }
    }

    fun teardownConnection() {
        webRtc?.release()
        webRtc = null
        fileTransfer = null
        connected = false
        everConnected = false
        inCall = false
        callKind = null
        role = null
        peerName = null
    }
}
