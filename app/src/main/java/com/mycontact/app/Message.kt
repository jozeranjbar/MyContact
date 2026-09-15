package com.mycontact.app

/**
 * Local chat-history record. Mirrors the shape of objects pushed into
 * State.history in the original app (type/dir/text/time/file fields).
 */
data class Message(
    val type: String,           // "text" | "file" | "system"
    val dir: String? = null,    // "out" | "in"
    val text: String? = null,
    val time: String? = null,
    val senderName: String? = null,
    // file fields
    val fileName: String? = null,
    val fileSize: Long? = null,
    val filePath: String? = null,   // local path once fully received/sent (replaces blob: URL)
    val transferId: String? = null,
    val progress: Int? = null,
    val status: String? = null,     // "failed" when transfer could not complete
    val failReason: String? = null
)
