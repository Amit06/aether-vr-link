package com.example.aethervr

import android.content.ComponentName
import android.service.vr.VrListenerService
import android.util.Log

/**
 * Native Android OS VrListenerService (`android.service.vr.VrListenerService`).
 * Engaged when the foreground Activity activates VR mode, allowing low-latency
 * sensor scheduling and display persistence optimizations.
 */
class AetherVrListenerService : VrListenerService() {
    override fun onCurrentVrActivityChanged(component: ComponentName?) {
        super.onCurrentVrActivityChanged(component)
        Log.i("AetherVR", "Native VrListenerService bound to: ${component?.flattenToShortString()}")
    }
}
