package com.mycontact.app

/**
 * JSON control-message envelope sent over the main DataChannel. Native
 * PeerConnection objects aren't the same objects as the browser's, so the
 * exact wire format is re-derived here rather than reused byte-for-byte —
 * but the message "kinds" mirror the original app's design one-for-one:
 * text chat, file transfer (meta/chunk-ack/done/fail), call signaling
 * (offer/answer/end), and ICE-restart reconnect (offer/answer).
 */
data class ControlMessage(
    val kind: String,
    val text: String? = null,
    val senderName: String? = null,
    val time: String? = null,
    // file transfer
    val transferId: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val mime: String? = null,
    val chunkIndex: Int? = null,
    val totalChunks: Int? = null,
    // call signaling / ICE restart (both carry an SDP)
    val sdp: String? = null,
    val sdpType: String? = null,
    val callKind: String? = null // "audio" | "video"
) {
    companion object {
        const val KIND_HELLO = "hello"                       // {senderName}
        const val KIND_TEXT = "text"                         // {text, senderName, time}
        const val KIND_FILE_META = "file-meta"               // {transferId, fileName, fileSize, mime, totalChunks}
        const val KIND_FILE_DONE = "file-done"                // {transferId}
        const val KIND_FILE_FAIL = "file-fail"                // {transferId, text=reason}
        const val KIND_CALL_OFFER = "call-offer"              // {sdp, sdpType, callKind}
        const val KIND_CALL_ANSWER = "call-answer"            // {sdp, sdpType}
        const val KIND_CALL_END = "call-end"
        const val KIND_ICE_RESTART_OFFER = "ice-restart-offer"   // {sdp, sdpType}
        const val KIND_ICE_RESTART_ANSWER = "ice-restart-answer" // {sdp, sdpType}
    }
}
