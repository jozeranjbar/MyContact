package com.mycontact.app

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        setContentView(R.layout.activity_welcome)

        findViewById<LinearLayout>(R.id.cardStarter).setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java).putExtra(ConnectActivity.EXTRA_ROLE, "starter"))
        }
        findViewById<LinearLayout>(R.id.cardReceiver).setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java).putExtra(ConnectActivity.EXTRA_ROLE, "receiver"))
        }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
