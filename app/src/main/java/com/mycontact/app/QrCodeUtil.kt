package com.mycontact.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR generation for connection codes. The original app hand-rolled its own
 * QR encoder in JS (verified via a round-trip self-test); natively, ZXing's
 * well-tested QRCodeWriter is the equivalent building block, wired up the
 * same way: render the code as a scannable square bitmap.
 */
object QrCodeUtil {
    fun generate(text: String, sizePx: Int = 800): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null // e.g. code too long for a single QR — caller falls back to copy/share only
        }
    }
}
