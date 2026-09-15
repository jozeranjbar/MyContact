package com.mycontact.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.random.Random

/**
 * Chunked file transfer over the WebRTC DataChannel — the native equivalent
 * of the original FileTransfer section. Protocol: a "file-meta" JSON control
 * message announces the transfer, then binary messages each carry a small
 * fixed header (8-char transferId + 4-byte big-endian chunk index) followed
 * by up to 16KB of file data, and a final "file-done" control message closes
 * it out. This lets several transfers interleave on one channel, unlike a
 * single implicit "current transfer" slot.
 */
class FileTransferManager(
    private val context: Context,
    private val rtc: WebRtcManager,
    private val listener: Listener
) {
    interface Listener {
        fun onOutgoingProgress(transferId: String, pct: Int)
        fun onOutgoingDone(transferId: String)
        fun onOutgoingFailed(transferId: String, reason: String)
        fun onIncomingStart(transferId: String, fileName: String, fileSize: Long, mime: String?)
        fun onIncomingProgress(transferId: String, pct: Int)
        fun onIncomingDone(transferId: String, localPath: String)
        fun onIncomingFailed(transferId: String, reason: String)
    }

    private val CHUNK_SIZE = 16 * 1024
    private val HEADER_LEN = 12 // 8 bytes transferId + 4 bytes chunk index

    private data class OutgoingState(var cancelled: Boolean = false)
    private data class IncomingState(
        val file: File,
        val stream: FileOutputStream,
        val fileName: String,
        val fileSize: Long,
        var receivedBytes: Long = 0,
        var lastActivity: Long = System.currentTimeMillis()
    )

    private val outgoing = HashMap<String, OutgoingState>()
    private val incoming = HashMap<String, IncomingState>()

    private fun newTransferId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    fun sendFile(uri: Uri, scope: CoroutineScope) {
        val resolver = context.contentResolver
        var name = "file"
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }
        val mime = resolver.getType(uri)
        val transferId = newTransferId()
        val totalChunks = if (size > 0) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt() else 0
        outgoing[transferId] = OutgoingState()

        rtc.sendControl(
            ControlMessage(
                kind = ControlMessage.KIND_FILE_META,
                transferId = transferId,
                fileName = name,
                fileSize = size,
                mime = mime,
                totalChunks = totalChunks
            )
        )

        scope.launch(Dispatchers.IO) {
            try {
                val input: InputStream = resolver.openInputStream(uri)
                    ?: throw IllegalStateException("قابل خواندن نیست")
                input.use { stream ->
                    val idBytes = transferId.toByteArray(Charsets.US_ASCII)
                    val buf = ByteArray(CHUNK_SIZE)
                    var sent = 0L
                    var idx = 0
                    while (true) {
                        if (outgoing[transferId]?.cancelled == true) return@launch
                        val n = stream.read(buf)
                        if (n <= 0) break
                        val packet = ByteBuffer.allocate(HEADER_LEN + n)
                        packet.put(idBytes)
                        packet.putInt(idx)
                        packet.put(buf, 0, n)
                        // Basic backpressure: avoid flooding the SCTP send buffer.
                        while (rtc.bufferedAmount() > 1_000_000 && outgoing[transferId]?.cancelled != true) {
                            Thread.sleep(20)
                        }
                        val ok = rtc.sendBinary(packet.array())
                        if (!ok) throw IllegalStateException("کانال داده بسته شد")
                        sent += n
                        idx++
                        val pct = if (size > 0) ((sent * 100) / size).toInt() else 0
                        listener.onOutgoingProgress(transferId, pct.coerceIn(0, 100))
                    }
                    rtc.sendControl(ControlMessage(kind = ControlMessage.KIND_FILE_DONE, transferId = transferId))
                    listener.onOutgoingDone(transferId)
                }
            } catch (e: Exception) {
                rtc.sendControl(ControlMessage(kind = ControlMessage.KIND_FILE_FAIL, transferId = transferId, text = e.message))
                listener.onOutgoingFailed(transferId, e.message ?: "انتقال ناموفق بود.")
            } finally {
                outgoing.remove(transferId)
            }
        }
    }

    fun cancelOutgoing(transferId: String) {
        outgoing[transferId]?.cancelled = true
    }

    /** Called from WebRtcManager.Listener.onControlMessage for file-* kinds. */
    fun onControlMessage(msg: ControlMessage) {
        when (msg.kind) {
            ControlMessage.KIND_FILE_META -> {
                val id = msg.transferId ?: return
                val dir = File(context.cacheDir, "incoming").apply { mkdirs() }
                val safeName = (msg.fileName ?: "file").replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val target = File(dir, "${id}_$safeName")
                incoming[id] = IncomingState(target, FileOutputStream(target), safeName, msg.fileSize ?: 0L)
                listener.onIncomingStart(id, safeName, msg.fileSize ?: 0L, msg.mime)
            }
            ControlMessage.KIND_FILE_DONE -> {
                val id = msg.transferId ?: return
                val st = incoming.remove(id) ?: return
                try { st.stream.flush(); st.stream.close() } catch (e: Exception) {}
                listener.onIncomingDone(id, st.file.absolutePath)
            }
            ControlMessage.KIND_FILE_FAIL -> {
                val id = msg.transferId ?: return
                val st = incoming.remove(id)
                try { st?.stream?.close() } catch (e: Exception) {}
                st?.file?.delete()
                listener.onIncomingFailed(id, msg.text ?: "طرف مقابل انتقال را لغو کرد.")
            }
        }
    }

    /** Called from WebRtcManager.Listener.onBinaryChunk. */
    fun onBinaryChunk(bytes: ByteArray) {
        if (bytes.size < HEADER_LEN) return
        val id = String(bytes, 0, 8, Charsets.US_ASCII)
        val idx = ByteBuffer.wrap(bytes, 8, 4).int
        val st = incoming[id] ?: return
        try {
            st.stream.write(bytes, HEADER_LEN, bytes.size - HEADER_LEN)
            st.receivedBytes += (bytes.size - HEADER_LEN)
            st.lastActivity = System.currentTimeMillis()
            val pct = if (st.fileSize > 0) ((st.receivedBytes * 100) / st.fileSize).toInt() else 0
            listener.onIncomingProgress(id, pct.coerceIn(0, 100))
        } catch (e: Exception) {
            listener.onIncomingFailed(id, "خطا در نوشتن فایل دریافتی.")
        }
    }

    /** Mirrors checkStalledTransfers + cancelAllActiveTransfers in the original. */
    fun cancelAllActiveTransfers(reason: String) {
        outgoing.keys.toList().forEach { id ->
            outgoing.remove(id)
            listener.onOutgoingFailed(id, reason)
        }
        incoming.keys.toList().forEach { id ->
            val st = incoming.remove(id)
            try { st?.stream?.close() } catch (e: Exception) {}
            st?.file?.delete()
            listener.onIncomingFailed(id, reason)
        }
    }

    fun checkStalledTransfers(staleMs: Long = 20_000) {
        val now = System.currentTimeMillis()
        incoming.filterValues { now - it.lastActivity > staleMs }.keys.toList().forEach { id ->
            val st = incoming.remove(id)
            try { st?.stream?.close() } catch (e: Exception) {}
            st?.file?.delete()
            listener.onIncomingFailed(id, "انتقال فایل بیش از حد بدون فعالیت ماند و لغو شد.")
        }
    }
}
