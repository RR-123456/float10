package com.pragon.mobile

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Where Pragon's voice comes out and which microphone it listens with (Pragon Live and Pragon PA).
 *  AUTO     headset / AirPods when connected (their microphone too, unless switched off), otherwise the loudspeaker
 *  SPEAKER  loudspeaker, always
 *  EARPIECE the phone's earpiece ("normal" call volume)
 * [forced] is used by Pragon PA while it talks to a caller (it must come out loud so the caller can hear it).
 * release() puts the phone's audio back the way it was, so music and calls sound normal again afterwards.
 */
object AudioRoute {
    const val AUTO = "auto"
    const val SPEAKER = "speaker"
    const val EARPIECE = "earpiece"

    @Volatile var forced: String? = null
    /** Called when the user changes the route, so a running Live session can move its audio at once. */
    @Volatile var onChange: (() -> Unit)? = null

    private var taken = false
    private var prevSpeaker = false
    private var askedBt = false

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("pragon_audio", Context.MODE_PRIVATE)
    private fun am(c: Context) = c.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun saved(c: Context): String = sp(c).getString("mode", AUTO) ?: AUTO
    fun mode(c: Context): String = forced ?: saved(c)
    fun setMode(c: Context, m: String) { sp(c).edit().putString("mode", m).apply(); onChange?.invoke() }
    /** v10: OFF by default. Using the AirPods microphone forces Bluetooth "call mode" (SCO): 1-2 s to switch and poor sound. Phone mic + AirPods speakers is faster. */
    fun headsetMic(c: Context): Boolean = sp(c).getBoolean("hs_mic", false)
    fun setHeadsetMic(c: Context, on: Boolean) { sp(c).edit().putBoolean("hs_mic", on).apply(); onChange?.invoke() }

    private fun isBt(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun isWired(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET

    fun headset(c: Context): AudioDeviceInfo? {
        val outs = am(c).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outs.firstOrNull { isWired(it) } ?: outs.firstOrNull { isBt(it) }
    }

    fun headsetName(c: Context): String? = try { headset(c)?.productName?.toString() } catch (e: Exception) { null }

    /** Android 12+ needs the Bluetooth permission to move a call-style stream onto AirPods. Ask once. */
    private fun btOk(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        if (Perm.has(c, android.Manifest.permission.BLUETOOTH_CONNECT)) return true
        if (!askedBt) { askedBt = true; Perm.request(c, android.Manifest.permission.BLUETOOTH_CONNECT) }
        return false
    }

    /** Applies the current route and returns the output device the audio track should prefer (null = let Android decide). */
    @Suppress("DEPRECATION")
    fun apply(c: Context): AudioDeviceInfo? {
        val a = am(c)
        try {
            if (!taken) { taken = true; prevSpeaker = a.isSpeakerphoneOn }
            val m = mode(c)
            val hs = if (m == AUTO) headset(c) else null
            if (hs != null) {
                if (isBt(hs)) {
                    if (!headsetMic(c) || !btOk(c)) {
                        // headset for sound, the phone's own microphone for listening
                        stopSco(a)
                        if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice()
                        a.isSpeakerphoneOn = false
                        return hs
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        val dev = a.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        }
                        if (dev != null) { a.setCommunicationDevice(dev); return dev }
                        return hs
                    }
                    a.startBluetoothSco(); a.isBluetoothScoOn = true
                    return null
                }
                // wired / USB headset
                if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice()
                a.isSpeakerphoneOn = false
                return hs
            }
            stopSco(a)
            val wantType = if (m == EARPIECE) AudioDeviceInfo.TYPE_BUILTIN_EARPIECE else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            val dev = a.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == wantType }
            if (Build.VERSION.SDK_INT >= 31 && dev != null) a.setCommunicationDevice(dev)
            a.isSpeakerphoneOn = m != EARPIECE
            return dev
        } catch (e: Exception) {
            return null
        }
    }

    @Suppress("DEPRECATION")
    private fun stopSco(a: AudioManager) {
        try { if (a.isBluetoothScoOn) { a.stopBluetoothSco(); a.isBluetoothScoOn = false } } catch (e: Exception) { }
    }

    /** Puts the phone's audio back (speaker off again, Bluetooth call audio released). Safe to call twice. */
    @Suppress("DEPRECATION")
    fun release(c: Context) {
        if (!taken) return
        taken = false
        try {
            val a = am(c)
            if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice()
            stopSco(a)
            a.isSpeakerphoneOn = prevSpeaker
        } catch (e: Exception) { }
    }

    /** One spoken sentence about the current choice. */
    fun describe(c: Context): String {
        val call = Prefs.callMe(c)
        val hs = headsetName(c)
        return when (saved(c)) {
            SPEAKER -> "Speaker it is, $call."
            EARPIECE -> "Earpiece, $call."
            else -> if (hs != null) "Automatic: I'll use $hs, $call."
                    else "Automatic: your headset when it connects, the speaker until then, $call."
        }
    }
}
