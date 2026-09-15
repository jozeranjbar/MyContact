package com.mycontact.app

import android.app.Application
import android.util.Log
import org.webrtc.PeerConnectionFactory

class MyContactApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val options = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        Log.i("MyContactApp", "WebRTC PeerConnectionFactory initialized")
        NotificationHelper.ensureChannel(this)
    }
}
