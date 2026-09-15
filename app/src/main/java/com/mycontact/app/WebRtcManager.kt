package com.mycontact.app

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Native equivalent of the WebRTCCore + DataChannelManager + Signaling
 * sections of MyContact.html: builds the ICE server list from settings,
 * owns the RTCPeerConnection/DataChannel, waits for full ICE gathering
 * before producing a connection code (no trickle ICE, same as the original),
 * and exposes offer/answer creation for both the "starter" and "receiver"
 * roles.
 */
class WebRtcManager(
    private val context: Context,
    private val prefs: Prefs,
    val listener: Listener
) {
    interface Listener {
        fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState)
        fun onDataChannelOpen()
        fun onDataChannelClosed()
        fun onControlMessage(msg: ControlMessage)
        fun onBinaryChunk(bytes: ByteArray)
        fun onRemoteTrackAdded(track: MediaStreamTrack)
        fun onLog(line: String) {}
    }

    private val gson = Gson()
    private val eglBase: EglBase = EglBase.create()
    fun eglContext(): EglBase.Context = eglBase.eglBaseContext

    private val factory: PeerConnectionFactory by lazy {
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    var pc: PeerConnection? = null
        private set
    private var dc: DataChannel? = null
    var dcOpen: Boolean = false
        private set

    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    var localAudioTrack: AudioTrack? = null
        private set
    var localVideoTrack: VideoTrack? = null
        private set

    /** Mirrors WebRTCCore.buildIceServers() exactly: Google STUN, public
     *  OpenRelay TURN fallback, then the user's personal Metered TURN
     *  domain/key from Settings if both are filled in. */
    fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
        )
        val domain = prefs.turnDomain.trim()
        val key = prefs.turnKey.trim()
        if (domain.isNotEmpty() && key.isNotEmpty()) {
            servers.add(PeerConnection.IceServer.builder("turn:$domain:80").setUsername("user").setPassword(key).createIceServer())
            servers.add(PeerConnection.IceServer.builder("turn:$domain:443").setUsername("user").setPassword(key).createIceServer())
            servers.add(PeerConnection.IceServer.builder("turn:$domain:443?transport=tcp").setUsername("user").setPassword(key).createIceServer())
        }
        return servers
    }

    private var iceGatheringComplete: CompletableDeferred<Boolean>? = null

    fun createPeerConnection(): PeerConnection {
        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                pc?.addIceCandidate(candidate)
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                listener.onLog("ICE gathering: $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    iceGatheringComplete?.complete(true)
                }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state != null) listener.onIceConnectionStateChanged(state)
            }
            override fun onDataChannel(channel: DataChannel?) {
                if (channel != null) bindDataChannel(channel)
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                receiver?.track()?.let { listener.onRemoteTrackAdded(it) }
            }
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                transceiver?.receiver?.track()?.let { listener.onRemoteTrackAdded(it) }
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
        }
        val newPc = factory.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("PeerConnection creation failed")
        pc = newPc
        return newPc
    }

    private fun bindDataChannel(channel: DataChannel) {
        dc = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                dcOpen = channel.state() == DataChannel.State.OPEN
                if (dcOpen) listener.onDataChannelOpen() else if (channel.state() == DataChannel.State.CLOSED) listener.onDataChannelClosed()
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer ?: return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                if (buffer.binary) {
                    listener.onBinaryChunk(bytes)
                } else {
                    try {
                        val msg = gson.fromJson(String(bytes, Charsets.UTF_8), ControlMessage::class.java)
                        listener.onControlMessage(msg)
                    } catch (e: Exception) {
                        Log.w("WebRtcManager", "bad control message", e)
                    }
                }
            }
        })
    }

    fun createMainDataChannel(): DataChannel {
        val init = DataChannel.Init().apply { ordered = true }
        val channel = pc!!.createDataChannel("main", init)
        bindDataChannel(channel)
        return channel
    }

    // ---- SDP coroutine wrappers -------------------------------------------------

    private suspend fun createOfferSuspend(): SessionDescription = suspendCancellableCoroutine { cont ->
        pc!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) cont.resume(desc) else cont.resumeWithException(IllegalStateException("null offer"))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { cont.resumeWithException(IllegalStateException(error)) }
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    private suspend fun createAnswerSuspend(): SessionDescription = suspendCancellableCoroutine { cont ->
        pc!!.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) cont.resume(desc) else cont.resumeWithException(IllegalStateException("null answer"))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { cont.resumeWithException(IllegalStateException(error)) }
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    private suspend fun setLocalDescSuspend(desc: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        pc!!.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) { cont.resumeWithException(IllegalStateException(error)) }
        }, desc)
    }

    suspend fun setRemoteDescSuspend(desc: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        pc!!.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { cont.resume(Unit) }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) { cont.resumeWithException(IllegalStateException(error)) }
        }, desc)
    }

    /** Mirrors WebRTCCore.waitForIceGatheringComplete — resolves true/"timeout". */
    private suspend fun waitForIceGatheringComplete(timeoutMs: Long = 12000): Boolean {
        if (pc?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return true
        val deferred = CompletableDeferred<Boolean>()
        iceGatheringComplete = deferred
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        iceGatheringComplete = null
        return result == true
    }

    /** Starter role, step 1+2: create pc + data channel + offer, wait for ICE, return code. */
    suspend fun createOfferCode(): String {
        createPeerConnection()
        createMainDataChannel()
        val offer = createOfferSuspend()
        setLocalDescSuspend(offer)
        val ok = waitForIceGatheringComplete(12000)
        if (!ok) listener.onLog("جمع‌آوری ICE کامل نشد؛ با مسیرهای موجود ادامه می‌دهیم.")
        val local = pc!!.localDescription
        return SignalingCodec.encode(local)
    }

    /** Starter role, step 3: accept the receiver's answer code. */
    suspend fun acceptAnswer(code: String) {
        val payload = SignalingCodec.decode(code)
        if (payload.type != "answer") throw SignalingCodec.InvalidCodeException("کدی که وارد کردید یک Answer نیست. لطفاً کد صحیح را Paste کنید.")
        setRemoteDescSuspend(SessionDescription(SessionDescription.Type.ANSWER, payload.sdp))
    }

    /** Receiver role: accept offer code, create pc, produce answer code. */
    suspend fun acceptOfferAndCreateAnswerCode(code: String): String {
        val payload = SignalingCodec.decode(code)
        if (payload.type != "offer") throw SignalingCodec.InvalidCodeException("کدی که وارد کردید یک Offer نیست. لطفاً کد صحیح را Paste کنید.")
        createPeerConnection()
        setRemoteDescSuspend(SessionDescription(SessionDescription.Type.OFFER, payload.sdp))
        val answer = createAnswerSuspend()
        setLocalDescSuspend(answer)
        val ok = waitForIceGatheringComplete(12000)
        if (!ok) listener.onLog("جمع‌آوری ICE کامل نشد؛ با مسیرهای موجود ادامه می‌دهیم.")
        return SignalingCodec.encode(pc!!.localDescription)
    }

    // ---- ICE restart (auto reconnect over an already-open data channel) --------

    suspend fun createIceRestartOfferMessage(): ControlMessage {
        pc!!.restartIce()
        val offer = createOfferSuspend()
        setLocalDescSuspend(offer)
        waitForIceGatheringComplete(8000)
        val local = pc!!.localDescription
        return ControlMessage(kind = ControlMessage.KIND_ICE_RESTART_OFFER, sdp = local.description, sdpType = local.type.canonicalForm())
    }

    suspend fun handleIceRestartOffer(msg: ControlMessage): ControlMessage {
        setRemoteDescSuspend(SessionDescription(SessionDescription.Type.OFFER, msg.sdp))
        val answer = createAnswerSuspend()
        setLocalDescSuspend(answer)
        waitForIceGatheringComplete(8000)
        val local = pc!!.localDescription
        return ControlMessage(kind = ControlMessage.KIND_ICE_RESTART_ANSWER, sdp = local.description, sdpType = local.type.canonicalForm())
    }

    suspend fun handleIceRestartAnswer(msg: ControlMessage) {
        setRemoteDescSuspend(SessionDescription(SessionDescription.Type.ANSWER, msg.sdp))
    }

    // ---- Call renegotiation (add/remove audio+video tracks) --------------------

    fun addAudioTrack(): AudioTrack {
        val source = factory.createAudioSource(MediaConstraints())
        audioSource = source
        val track = factory.createAudioTrack("audio0", source)
        localAudioTrack = track
        pc?.addTrack(track, listOf("mycontact-stream"))
        return track
    }

    fun addVideoTrack(capturer: VideoCapturer): VideoTrack {
        videoCapturer = capturer
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val source = factory.createVideoSource(capturer.isScreencast)
        videoSource = source
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(640, 480, 24)
        val track = factory.createVideoTrack("video0", source)
        localVideoTrack = track
        pc?.addTrack(track, listOf("mycontact-stream"))
        return track
    }

    fun stopLocalMedia() {
        try { videoCapturer?.stopCapture() } catch (e: Exception) {}
        videoCapturer?.dispose()
        videoCapturer = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
    }

    suspend fun createCallOfferMessage(callKind: String): ControlMessage {
        val offer = createOfferSuspend()
        setLocalDescSuspend(offer)
        waitForIceGatheringComplete(8000)
        val local = pc!!.localDescription
        return ControlMessage(kind = ControlMessage.KIND_CALL_OFFER, sdp = local.description, sdpType = local.type.canonicalForm(), callKind = callKind)
    }

    suspend fun createCallAnswerMessage(offerMsg: ControlMessage): ControlMessage {
        setRemoteDescSuspend(SessionDescription(SessionDescription.Type.OFFER, offerMsg.sdp))
        val answer = createAnswerSuspend()
        setLocalDescSuspend(answer)
        waitForIceGatheringComplete(8000)
        val local = pc!!.localDescription
        return ControlMessage(kind = ControlMessage.KIND_CALL_ANSWER, sdp = local.description, sdpType = local.type.canonicalForm())
    }

    suspend fun applyCallAnswer(msg: ControlMessage) {
        setRemoteDescSuspend(SessionDescription(SessionDescription.Type.ANSWER, msg.sdp))
    }

    fun removeAllSenders() {
        pc?.senders?.forEach { s -> try { if (s.track() != null) pc?.removeTrack(s) } catch (e: Exception) {} }
    }

    // ---- Sending over the data channel ------------------------------------------

    fun sendControl(msg: ControlMessage): Boolean {
        val channel = dc ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val json = gson.toJson(msg)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false)
        return channel.send(buffer)
    }

    fun sendBinary(bytes: ByteArray): Boolean {
        val channel = dc ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
    }

    fun bufferedAmount(): Long = dc?.bufferedAmount() ?: 0L

    fun closeConnection() {
        try { dc?.close() } catch (e: Exception) {}
        stopLocalMedia()
        try { pc?.close() } catch (e: Exception) {}
        pc = null
        dc = null
        dcOpen = false
    }

    fun release() {
        closeConnection()
        eglBase.release()
    }
}
