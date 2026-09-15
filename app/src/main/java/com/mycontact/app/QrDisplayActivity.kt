package com.mycontact.app

import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class QrDisplayActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_display)
        val code = intent.getStringExtra(EXTRA_CODE) ?: ""
        val bmp = QrCodeUtil.generate(code)
        if (bmp != null) {
            findViewById<ImageView>(R.id.ivQrCode).setImageBitmap(bmp)
        } else {
            Toast.makeText(this, "این کد برای QR خیلی طولانی است؛ از کپی/اشتراک‌گذاری استفاده کنید.", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnCloseQr).setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_CODE = "code"
    }
}
