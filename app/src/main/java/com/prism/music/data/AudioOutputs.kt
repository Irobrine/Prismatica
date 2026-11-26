package com.prism.music.data

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Outputs music can play through: this phone's speaker, wired/USB, and connected Bluetooth devices (not microphones). */
private val OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_DOCK,
)

/** Whether [type] is an output music can play through (not a call channel such as Bluetooth SCO, nor a microphone). */
fun isMusicOutput(type: Int): Boolean = type in OUTPUT_TYPES

/** Whether [type] carries Bluetooth audio (Classic A2DP, LE Audio, or a hearing aid). */
fun isBluetooth(type: Int): Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
    type == AudioDeviceInfo.TYPE_BLE_SPEAKER || type == AudioDeviceInfo.TYPE_HEARING_AID

/** Outputs music can play through right now: this phone, wired/USB, and connected Bluetooth devices. */
fun outputOptions(context: Context): List<AudioDeviceInfo> {
    val am = context.getSystemService(AudioManager::class.java) ?: return emptyList()
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type in OUTPUT_TYPES }
        // One row per physical device (LE Audio and A2DP can both show for the same earbuds).
        .distinctBy { if (isBluetooth(it.type)) "bt:" + (it.addressOrBlank().ifBlank { it.productName.toString() }) else "t:${it.type}:${it.productName}" }
        .sortedBy { if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) 0 else if (isBluetooth(it.type)) 2 else 1 }
}

/** Where audio is going: the device picked in Prism if still there, otherwise the system's media route. */
fun currentOutput(context: Context, preferred: AudioDeviceInfo?): AudioDeviceInfo? {
    val options = outputOptions(context)
    if (preferred != null) options.firstOrNull { it.id == preferred.id }?.let { return it }
    val am = context.getSystemService(AudioManager::class.java)
    if (android.os.Build.VERSION.SDK_INT >= 33 && am != null) {
        runCatching {
            val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { routed ->
                options.firstOrNull { it.type == routed.type && (routed.address.isBlank() || it.address == routed.address) }
                    ?.let { return it }
            }
        }
    }
    return options.maxByOrNull { if (isBluetooth(it.type)) 3 else if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) 0 else 2 }
}
