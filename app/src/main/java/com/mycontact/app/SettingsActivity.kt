package com.mycontact.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private data class FontScale(val value: Float, val labelRes: Int)
    private val fontScales = listOf(
        FontScale(0.88f, R.string.font_small),
        FontScale(1f, R.string.font_medium),
        FontScale(1.18f, R.string.font_large),
        FontScale(1.4f, R.string.font_xlarge)
    )

    private lateinit var etDisplayName: EditText
    private lateinit var etTurnDomain: EditText
    private lateinit var etTurnKey: EditText
    private lateinit var tvFontScaleLabel: TextView
    private lateinit var tvNotifStatus: TextView
    private lateinit var btnEnableNotif: Button
    private var fontScaleIndex = 1

    private val requestNotifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshNotifUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etDisplayName = findViewById(R.id.etDisplayName)
        etTurnDomain = findViewById(R.id.etTurnDomain)
        etTurnKey = findViewById(R.id.etTurnKey)
        tvFontScaleLabel = findViewById(R.id.tvFontScaleLabel)
        tvNotifStatus = findViewById(R.id.tvNotifStatus)
        btnEnableNotif = findViewById(R.id.btnEnableNotif)

        etDisplayName.setText(Session.prefs.displayName)
        etTurnDomain.setText(Session.prefs.turnDomain)
        etTurnKey.setText(Session.prefs.turnKey)
        fontScaleIndex = Session.prefs.fontScaleIndex.coerceIn(0, fontScales.size - 1)
        applyFontScaleLabel()

        findViewById<ImageButton>(R.id.btnSettingsBack).setOnClickListener { saveAndFinish() }

        findViewById<Button>(R.id.btnFontDec).setOnClickListener {
            if (fontScaleIndex > 0) { fontScaleIndex--; applyFontScaleLabel() }
        }
        findViewById<Button>(R.id.btnFontInc).setOnClickListener {
            if (fontScaleIndex < fontScales.size - 1) { fontScaleIndex++; applyFontScaleLabel() }
        }

        btnEnableNotif.setOnClickListener { requestNotifications() }
        refreshNotifUi()

        findViewById<Button>(R.id.btnClearChats).setOnClickListener { confirmClearChats() }
        findViewById<Button>(R.id.btnClearAllData).setOnClickListener { confirmClearAll() }
    }

    private fun applyFontScaleLabel() {
        tvFontScaleLabel.setText(fontScales[fontScaleIndex].labelRes)
        findViewById<Button>(R.id.btnFontDec).isEnabled = fontScaleIndex > 0
        findViewById<Button>(R.id.btnFontInc).isEnabled = fontScaleIndex < fontScales.size - 1
        Session.prefs.fontScaleIndex = fontScaleIndex
    }

    private fun refreshNotifUi() {
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }
        tvNotifStatus.text = if (enabled)
            "اعلان‌ها فعال است؛ وقتی برنامه در پس‌زمینه باشد، پیام و تماس ورودی اطلاع‌رسانی می‌شود."
        else
            "اعلان‌ها فعال نیست. با فعال کردن، از پیام و تماس ورودی وقتی برنامه در پس‌زمینه است باخبر می‌شوید."
        btnEnableNotif.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            refreshNotifUi()
        }
    }

    private fun confirmClearChats() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_clear_chats_title)
            .setMessage(R.string.confirm_clear_chats_desc)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_clear_chats) { _, _ ->
                Session.prefs.clearHistory()
                Session.history.clear()
                Toast.makeText(this, R.string.toast_chats_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_clear_all_title)
            .setMessage(R.string.confirm_clear_all_desc)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_clear_all) { _, _ ->
                Session.prefs.clearAll()
                Session.history.clear()
                etDisplayName.setText("")
                etTurnDomain.setText("")
                etTurnKey.setText("")
                fontScaleIndex = 1
                applyFontScaleLabel()
                Toast.makeText(this, R.string.toast_all_cleared, Toast.LENGTH_SHORT).show()
                startActivity(
                    android.content.Intent(this, WelcomeActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            }
            .show()
    }

    private fun saveAndFinish() {
        Session.prefs.displayName = etDisplayName.text.toString().trim()
        Session.prefs.turnDomain = etTurnDomain.text.toString().trim()
        Session.prefs.turnKey = etTurnKey.text.toString().trim()
        Session.prefs.fontScaleIndex = fontScaleIndex
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
    }
}
