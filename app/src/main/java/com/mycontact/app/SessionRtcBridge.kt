package com.mycontact.app

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wires WebRtcManager + FileTransferManager callbacks into Session state
 * and whichever screen is currently registered — the native equivalent of
 * DataChannelManager + UI + ReconnectManager + CallManager acting on the
 * single global State object in the original app.
 */
class SessionRtcBridge(private val scope: CoroutineScope) : WebRtcManager.Listener, FileTransferManager.Listener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) = mainHandler.post(block)

    private fun nowTime(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    // ---------------- WebRtcManager.Listener ----------------

    override fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {
        ui {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                    Session.connected = true
                    Session.reconnecting = false
                    Session.chatListener?.onStatus("متصل", "good")
                    Session.connectListener?.onStatus("متصل", "good")
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    Session.chatListener?.onStatus("اتصال ناپایدار...", "progress")
                    scheduleReconnect()
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    Session.connected = false
                    Session.chatListener?.onStatus("خطای اتصال", "bad")
                    Session.fileTransfer?.cancelAllActiveTransfers("اتصال قطع شد؛ انتقال فایل ناتمام ماند.")
                    scheduleReconnect(immediate = true)
                }
                PeerConnection.IceConnectionState.CLOSED -> {
                    Session.connected = false
                    Session.chatListener?.onStatus("قطع", "idle")
                    Session.fileTransfer?.cancelAllActiveTransfers("اتصال قطع شد؛ انتقال فایل ناتمام ماند.")
                }
                else -> {}
            }
        }
    }

    override fun onDataChannelOpen() {
        ui {
            Session.connected = true
            Session.everConnected = true
            Session.chatListener?.onStatus("متصل", "good")
            Session.webRtc?.sendControl(ControlMessage(kind = ControlMessage.KIND_HELLO, senderName = Session.prefs.displayName))
        }
    }

    override fun onDataChannelClosed() {
        ui {
            Session.connected = false
            Session.chatListener?.onStatus("قطع", "idle")
        }
    }

    override fun onControlMessage(msg: ControlMessage) {
        ui {
            when (msg.kind) {
                ControlMessage.KIND_HELLO -> Session.peerName = msg.senderName
                ControlMessage.KIND_TEXT -> {
                    val m = Message(type = "text", dir = "in", text = msg.text, time = msg.time ?: nowTime(), senderName = msg.senderName)
                    Session.addHistory(m)
                    Session.chatListener?.onMessage(m)
                }
                ControlMessage.KIND_FILE_META, ControlMessage.KIND_FILE_DONE, ControlMessage.KIND_FILE_FAIL ->
                    Session.fileTransfer?.onControlMessage(msg)
                ControlMessage.KIND_CALL_OFFER -> Session.chatListener?.onIncomingCallOffer(msg)
                ControlMessage.KIND_CALL_ANSWER -> scope.launch {
                    Session.webRtc?.applyCallAnswer(msg)
                    ui { Session.callListener?.onCallAnswered() }
                }
                ControlMessage.KIND_CALL_END -> {
                    Session.inCall = false
                    Session.callListener?.onRemoteHangup()
                }
                ControlMessage.KIND_ICE_RESTART_OFFER -> scope.launch {
                    Session.reconnecting = true
                    val answer = Session.webRtc?.handleIceRestartOffer(msg)
                    if (answer != null) Session.webRtc?.sendControl(answer)
                }
                ControlMessage.KIND_ICE_RESTART_ANSWER -> scope.launch {
                    Session.webRtc?.handleIceRestartAnswer(msg)
                }
            }
        }
    }

    override fun onBinaryChunk(bytes: ByteArray) {
        Session.fileTransfer?.onBinaryChunk(bytes)
    }

    override fun onRemoteTrackAdded(track: MediaStreamTrack) {
        ui { Session.callListener?.onRemoteTrack(track) }
    }

    override fun onLog(line: String) {
        ui { Session.connectListener?.onLog(line) }
    }

    // ---------------- Reconnect (ICE restart over the still-open PC) --------

    private var reconnectRunnable: Runnable? = null

    private fun scheduleReconnect(immediate: Boolean = false) {
        if (Session.reconnecting) return
        if (Session.role != "starter") return // only the starter initiates, receiver just answers
        val delay = if (immediate) 0L else 3000L
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            val pc = Session.webRtc?.pc ?: return@Runnable
            if (pc.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED ||
                pc.iceConnectionState() == PeerConnection.IceConnectionState.FAILED
            ) {
                Session.reconnecting = true
                Session.chatListener?.onStatus("در حال تلاش برای اتصال مجدد...", "progress")
                scope.launch {
                    try {
                        val offer = Session.webRtc?.createIceRestartOfferMessage()
                        if (offer != null) Session.webRtc?.sendControl(offer)
                    } catch (e: Exception) {
                        ui {
                            Session.reconnecting = false
                            Session.chatListener?.onStatus("خطای اتصال", "bad")
                        }
                    }
                }
            }
        }
        reconnectRunnable = r
        mainHandler.postDelayed(r, delay)
    }

    // ---------------- FileTransferManager.Listener --------------------------

    override fun onOutgoingProgress(transferId: String, pct: Int) {
        ui {
            Session.updateHistoryTransfer(transferId) { it.copy(progress = pct) }
            Session.chatListener?.onTransferUpdate(transferId) { it.copy(progress = pct) }
        }
    }

    override fun onOutgoingDone(transferId: String) {
        ui {
            Session.updateHistoryTransfer(transferId) { it.copy(progress = 100) }
            Session.chatListener?.onTransferUpdate(transferId) { it.copy(progress = 100) }
        }
    }

    override fun onOutgoingFailed(transferId: String, reason: String) {
        ui {
            Session.updateHistoryTransfer(transferId) { it.copy(status = "failed", failReason = reason) }
            Session.chatListener?.onTransferUpdate(transferId) { it.copy(status = "failed", failReason = reason) }
        }
    }

    override fun onIncomingStart(transferId: String, fileName: String, fileSize: Long, mime: String?) {
        ui {
            val m = Message(type = "file", dir = "in", time = nowTime(), fileName = fileName, fileSize = fileSize, transferId = transferId, progress = 0)
            Session.addHistory(m)
            Session.chatListener?.onMessage(m)
        }
    }

    override fun onIncomingProgress(transferId: String, pct: Int) {
        ui {
            Session.updateHistoryTransfer(transferId) { it.copy(progress = pct) }
            Session.chatListener?.onTransferUpdate(transferId) { it.copy(progress = pct) }
        }
    }

    override fun onIncomingDone(transferId: String, localPath: String) {
        ui {
            Session.updateHistoryTransfer(transferId) { it.copy(progress = 100, filePath = localPath) }
            Session.chatListener?.onTransferUpdate(transferId) { it.copy(progress = 100, filePath = localPath) }
        }
    }

    override fun onIncomingFailed(transferId: String, reason: String) {
        ui {
            Session.updateHistoryTransfer(transferId) { it.copy(status = "failed", failReason = reason) }
            Session.chatListener?.onTransferUpdate(transferId) { it.copy(status = "failed", failReason = reason) }
        }
    }
}
