package com.mycontact.app

import android.util.Base64
import com.google.gson.Gson
import org.webrtc.SessionDescription

/**
 * Encodes/decodes the manual offer/answer "connection code" exactly the way
 * the original app does: base64(JSON{sdp, type}). Because we always wait for
 * ICE gathering to finish before encoding, the candidates are already baked
 * into localDescription.sdp — no separate candidate list is needed, matching
 * WebRTCCore.encodeDescription / decodeDescription in MyContact.html.
 */
object SignalingCodec {
    private val gson = Gson()

    data class Payload(val sdp: String, val type: String)

    fun encode(desc: SessionDescription): String {
        val payload = Payload(sdp = desc.description, type = desc.type.canonicalForm())
        val json = gson.toJson(payload)
        return Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    class InvalidCodeException(message: String) : Exception(message)

    fun decode(code: String): Payload {
        val bytes = try {
            Base64.decode(code.trim(), Base64.DEFAULT)
        } catch (e: Exception) {
            throw InvalidCodeException("کد وارد شده معتبر نیست (خطای Base64).")
        }
        val json = String(bytes, Charsets.UTF_8)
        val obj = try {
            gson.fromJson(json, Payload::class.java)
        } catch (e: Exception) {
            throw InvalidCodeException("کد وارد شده خراب یا ناقص است.")
        }
        if (obj == null || obj.sdp.isBlank() || obj.type.isBlank()) {
            throw InvalidCodeException("ساختار کد نامعتبر است.")
        }
        return obj
    }
}
