package com.mycontact.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ConnectActivity : AppCompatActivity(), Session.ConnectEventListener {

    private lateinit var starterStep1Card: LinearLayout
    private lateinit var codeResultCard: LinearLayout
    private lateinit var answerInputCard: LinearLayout
    private lateinit var receiverStep1Card: LinearLayout
    private lateinit var tvConnectTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvConnectLog: TextView

    private var role: String = "starter"
    private var lastGeneratedCode: String = ""
    private var pendingScanTarget: Int = 0 // 0=none, 1=answer paste box, 2=offer paste box
    private var navigatedToChat: Boolean = false

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val value = result.data?.getStringExtra(QrScanActivity.EXTRA_RESULT) ?: return@registerForActivityResult
        when (pendingScanTarget) {
            1 -> findViewById<android.widget.EditText>(R.id.etAnswerInput).setText(value)
            2 -> findViewById<android.widget.EditText>(R.id.etOfferInput).setText(value)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)

        role = intent.getStringExtra(EXTRA_ROLE) ?: "starter"
        Session.role = role

        starterStep1Card = findViewById(R.id.starterStep1Card)
        codeResultCard = findViewById(R.id.codeResultCard)
        answerInputCard = findViewById(R.id.answerInputCard)
        receiverStep1Card = findViewById(R.id.receiverStep1Card)
        tvConnectTitle = findViewById(R.id.tvConnectTitle)
        tvStatus = findViewById(R.id.tvStatus)
        tvConnectLog = findViewById(R.id.tvConnectLog)

        findViewById<ImageButton>(R.id.btnConnectBack).setOnClickListener { confirmBack() }

        findViewById<TextView>(R.id.tvStep1Title).text = "۱  " + getString(R.string.step1_offer_title)
        findViewById<TextView>(R.id.tvStep3Title).text = "۳  " + getString(R.string.step3_answer_title)
        findViewById<TextView>(R.id.tvReceiverStep1Title).text = "۱  " + getString(R.string.step1_receiver_title)

        if (role == "starter") {
            tvConnectTitle.text = getString(R.string.connect_title_starter)
            starterStep1Card.visibility = android.view.View.VISIBLE
            findViewById<android.widget.Button>(R.id.btnRebuildCode).setOnClickListener { runCreateOffer() }
            findViewById<android.widget.Button>(R.id.btnConfirmConnect).setOnClickListener { submitAnswer() }
            findViewById<android.widget.Button>(R.id.btnScanAnswerQr).setOnClickListener {
                pendingScanTarget = 1
                scanLauncher.launch(Intent(this, QrScanActivity::class.java))
            }
            runCreateOffer()
        } else {
            tvConnectTitle.text = getString(R.string.connect_title_receiver)
            receiverStep1Card.visibility = android.view.View.VISIBLE
            findViewById<android.widget.Button>(R.id.btnCreateAnswer).setOnClickListener { submitOffer() }
            findViewById<android.widget.Button>(R.id.btnScanOfferQr).setOnClickListener {
                pendingScanTarget = 2
                scanLauncher.launch(Intent(this, QrScanActivity::class.java))
            }
        }

        findViewById<android.widget.Button>(R.id.btnShareCode).setOnClickListener { shareCode(lastGeneratedCode) }
        findViewById<ImageButton>(R.id.btnShowQr) // placeholder, real listener set after code exists
        findViewById<android.widget.Button>(R.id.btnShowQr).setOnClickListener {
            startActivity(Intent(this, QrDisplayActivity::class.java).putExtra(QrDisplayActivity.EXTRA_CODE, lastGeneratedCode))
        }
    }

    override fun onStart() {
        super.onStart()
        Session.connectListener = this
    }

    override fun onStop() {
        super.onStop()
        Session.connectListener = null
    }

    private fun setStatus(text: String, kind: String) {
        onStatus(text, kind)
    }

    override fun onStatus(text: String, kind: String) {
        runOnUiThread {
            tvStatus.text = text
            val bg = when (kind) {
                "good" -> R.drawable.pill_good
                "ready" -> R.drawable.pill_good
                "bad" -> R.drawable.pill_bad
                "progress" -> R.drawable.pill_progress
                else -> R.drawable.pill_idle
            }
            findViewById<LinearLayout>(R.id.statusPill).setBackgroundResource(bg)
            if (kind == "good" && !navigatedToChat) {
                navigatedToChat = true
                goToChat()
            }
        }
    }

    override fun onLog(line: String) {
        runOnUiThread {
            tvConnectLog.visibility = android.view.View.VISIBLE
            tvConnectLog.append(line + "\n")
        }
    }

    private fun runCreateOffer() {
        setStatus(getString(R.string.status_creating_offer), "progress")
        val bridge = SessionRtcBridge(lifecycleScope)
        val rtc = WebRtcManager(this, Session.prefs, bridge)
        Session.webRtc = rtc
        Session.fileTransfer = FileTransferManager(this, rtc, bridge)
        lifecycleScope.launch {
            try {
                setStatus(getString(R.string.status_waiting_ice), "progress")
                val code = rtc.createOfferCode()
                lastGeneratedCode = code
                starterStep1Card.visibility = android.view.View.GONE
                showGeneratedCode(true, code)
                setStatus(getString(R.string.status_ready_to_send_offer), "ready")
            } catch (e: Exception) {
                setStatus(getString(R.string.status_connection_error), "bad")
                Toast.makeText(this@ConnectActivity, e.message ?: "خطا", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showGeneratedCode(isOffer: Boolean, code: String) {
        codeResultCard.visibility = android.view.View.VISIBLE
        findViewById<TextView>(R.id.tvCodeResultTitle).text =
            "۲  " + getString(if (isOffer) R.string.step2_offer_ready else R.string.step2_answer_ready)
        findViewById<android.widget.EditText>(R.id.etGeneratedCode).setText(code)
        findViewById<android.widget.Button>(R.id.btnShareCode).text =
            getString(if (isOffer) R.string.btn_copy_share_offer else R.string.btn_share_answer)
        if (isOffer) answerInputCard.visibility = android.view.View.VISIBLE
    }

    private fun submitAnswer() {
        val code = findViewById<android.widget.EditText>(R.id.etAnswerInput).text.toString()
        if (code.isBlank()) { Toast.makeText(this, R.string.err_enter_answer, Toast.LENGTH_SHORT).show(); return }
        setStatus(getString(R.string.status_connecting), "progress")
        lifecycleScope.launch {
            try {
                Session.webRtc?.acceptAnswer(code)
                setStatus(getString(R.string.status_connecting), "progress")
            } catch (e: Exception) {
                setStatus(getString(R.string.status_connection_error), "bad")
                Toast.makeText(this@ConnectActivity, e.message ?: "خطا", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun submitOffer() {
        val code = findViewById<android.widget.EditText>(R.id.etOfferInput).text.toString()
        if (code.isBlank()) { Toast.makeText(this, R.string.err_enter_offer, Toast.LENGTH_SHORT).show(); return }
        setStatus(getString(R.string.status_creating_answer), "progress")
        val bridge = SessionRtcBridge(lifecycleScope)
        lifecycleScope.launch {
            try {
                val rtc = WebRtcManager(this@ConnectActivity, Session.prefs, bridge)
                Session.webRtc = rtc
                Session.fileTransfer = FileTransferManager(this@ConnectActivity, rtc, bridge)
                setStatus(getString(R.string.status_waiting_ice), "progress")
                val answerCode = rtc.acceptOfferAndCreateAnswerCode(code)
                lastGeneratedCode = answerCode
                receiverStep1Card.visibility = android.view.View.GONE
                showGeneratedCode(false, answerCode)
                setStatus(getString(R.string.status_ready_to_send_answer), "ready")
            } catch (e: Exception) {
                setStatus(getString(R.string.status_connection_error), "bad")
                Toast.makeText(this@ConnectActivity, e.message ?: "خطا", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goToChat() {
        startActivity(Intent(this, ChatActivity::class.java))
    }

    private fun shareCode(code: String) {
        if (code.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mycontact-code", code))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, code)
            putExtra(Intent.EXTRA_TITLE, "کد اتصال تماس من")
        }
        try {
            startActivity(Intent.createChooser(send, null))
        } catch (e: Exception) {
            Toast.makeText(this, "کد کپی شد.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmBack() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_back_title)
            .setMessage(R.string.confirm_back_desc)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                Session.teardownConnection()
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_ROLE = "role"
    }
}
