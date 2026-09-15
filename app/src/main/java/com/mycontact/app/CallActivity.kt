package com.mycontact.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaStreamTrack
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class CallActivity : AppCompatActivity(), Session.CallEventListener {

    private lateinit var remoteRenderer: SurfaceViewRenderer
    private lateinit var localRenderer: SurfaceViewRenderer
    private lateinit var tvStatus: TextView
    private lateinit var tvPeerName: TextView

    private var callKind: String = "audio"
    private var micOn = true
    private var camOn = true
    private var isIncoming = false
    private var incomingOfferSdp: String? = null

    private val requestPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) proceedWithCall() else {
            Toast.makeText(this, "برای تماس به دسترسی میکروفون/دوربین نیاز است.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        remoteRenderer = findViewById(R.id.remoteRenderer)
        localRenderer = findViewById(R.id.localRenderer)
        tvStatus = findViewById(R.id.tvCallStatus)
        tvPeerName = findViewById(R.id.tvCallPeerName)
        tvPeerName.text = Session.peerName ?: getString(R.string.call_peer_default)

        incomingOfferSdp = intent.getStringExtra(EXTRA_INCOMING_OFFER_SDP)
        isIncoming = incomingOfferSdp != null
        callKind = if (isIncoming) (intent.getStringExtra(EXTRA_INCOMING_OFFER_KIND) ?: "audio")
                   else (intent.getStringExtra(EXTRA_OUTGOING_KIND) ?: "audio")

        findViewById<ImageButton>(R.id.btnToggleMic).setOnClickListener { toggleMic() }
        findViewById<ImageButton>(R.id.btnToggleCam).setOnClickListener { toggleCam() }
        findViewById<ImageButton>(R.id.btnToggleCam).visibility = if (callKind == "video") android.view.View.VISIBLE else android.view.View.GONE
        findViewById<ImageButton>(R.id.btnHangup).setOnClickListener { hangup(true) }

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (callKind == "video") needed.add(Manifest.permission.CAMERA)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) proceedWithCall() else requestPerms.launch(missing.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        Session.callListener = this
    }

    override fun onStop() {
        super.onStop()
        Session.callListener = null
    }

    private fun proceedWithCall() {
        val rtc = Session.webRtc ?: run { finish(); return }
        val eglContext = rtc.eglContext()
        remoteRenderer.init(eglContext, null)
        localRenderer.init(eglContext, null)
        remoteRenderer.setMirror(false)
        localRenderer.setMirror(true)

        rtc.addAudioTrack()
        if (callKind == "video") {
            val capturer = createFrontCameraCapturer()
            if (capturer != null) {
                val track: VideoTrack = rtc.addVideoTrack(capturer)
                track.addSink(localRenderer)
                localRenderer.visibility = android.view.View.VISIBLE
            }
        }
        Session.inCall = true
        Session.callKind = callKind

        lifecycleScope.launch {
            try {
                if (isIncoming) {
                    tvStatus.text = getString(R.string.call_connecting)
                    val offerMsg = ControlMessage(kind = ControlMessage.KIND_CALL_OFFER, sdp = incomingOfferSdp, sdpType = "offer", callKind = callKind)
                    val answer = rtc.createCallAnswerMessage(offerMsg)
                    rtc.sendControl(answer)
                    tvStatus.text = getString(R.string.status_connected)
                } else {
                    tvStatus.text = getString(R.string.call_connecting)
                    val offer = rtc.createCallOfferMessage(callKind)
                    rtc.sendControl(offer)
                }
            } catch (e: Exception) {
                Toast.makeText(this@CallActivity, e.message ?: "خطا در برقراری تماس", Toast.LENGTH_LONG).show()
                hangup(true)
            }
        }
    }

    private fun createFrontCameraCapturer(): org.webrtc.VideoCapturer? {
        val enumerator = Camera2Enumerator(this)
        val names = enumerator.deviceNames
        val frontName = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull()
        return frontName?.let { enumerator.createCapturer(it, null) as? CameraVideoCapturer }
    }

    private fun toggleMic() {
        micOn = !micOn
        Session.webRtc?.localAudioTrack?.setEnabled(micOn)
    }

    private fun toggleCam() {
        camOn = !camOn
        Session.webRtc?.localVideoTrack?.setEnabled(camOn)
    }

    private fun hangup(notifyPeer: Boolean) {
        if (notifyPeer) Session.webRtc?.sendControl(ControlMessage(kind = ControlMessage.KIND_CALL_END))
        Session.webRtc?.removeAllSenders()
        Session.webRtc?.stopLocalMedia()
        Session.inCall = false
        Session.callKind = null
        finish()
    }

    override fun onRemoteTrack(track: MediaStreamTrack) {
        if (track is VideoTrack) {
            runOnUiThread {
                remoteRenderer.visibility = android.view.View.VISIBLE
                track.addSink(remoteRenderer)
            }
        }
    }

    override fun onRemoteHangup() {
        runOnUiThread {
            Toast.makeText(this, R.string.call_remote_hangup, Toast.LENGTH_SHORT).show()
            hangup(false)
        }
    }

    override fun onCallAnswered() {
        runOnUiThread { tvStatus.text = getString(R.string.status_connected) }
    }

    override fun onDestroy() {
        try { remoteRenderer.release() } catch (e: Exception) {}
        try { localRenderer.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OUTGOING_KIND = "outgoing_kind"
        const val EXTRA_INCOMING_OFFER_SDP = "incoming_offer_sdp"
        const val EXTRA_INCOMING_OFFER_KIND = "incoming_offer_kind"
    }
}
