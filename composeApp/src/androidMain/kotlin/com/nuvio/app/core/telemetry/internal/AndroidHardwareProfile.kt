package com.nuvio.app.core.telemetry.internal

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Display

/**
 * Hardware facts that explain most device-specific failures (design §2b). Registered once per launch as PostHog
 * super properties and attached to crash and report events. No serial numbers, MACs, IPs, account names or app lists.
 * Probes are guarded individually: a vendor quirk in one must not cost the rest.
 */
internal object AndroidHardwareProfile {

    fun collect(context: Context): Map<String, Any> = buildMap {
        runCatching { memory(context) }.getOrNull()?.let(::putAll)
        runCatching { chipAndOs(context) }.getOrNull()?.let(::putAll)
        runCatching { display(context) }.getOrNull()?.let(::putAll)
        runCatching { decoders() }.getOrNull()?.let(::putAll)
        runCatching { audio(context) }.getOrNull()?.let(::putAll)
        runCatching { storage() }.getOrNull()?.let(::putAll)
    }

    private fun memory(context: Context): Map<String, Any> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return mapOf(
            "hw_ram_total_mb" to (mem.totalMem / (1024 * 1024)).toInt(),
            "hw_low_ram" to (am?.isLowRamDevice ?: false),
            "hw_heap_limit_mb" to (am?.memoryClass ?: 0),
            "hw_large_heap_limit_mb" to (am?.largeMemoryClass ?: 0),
        )
    }

    private fun chipAndOs(context: Context): Map<String, Any> = buildMap {
        put("hw_abis", Build.SUPPORTED_ABIS.joinToString(","))
        put("hw_hardware", Build.HARDWARE.orEmpty())
        put("hw_board", Build.BOARD.orEmpty())
        put("hw_cpu_cores", Runtime.getRuntime().availableProcessors())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            put("hw_soc_manufacturer", Build.SOC_MANUFACTURER.orEmpty())
            put("hw_soc_model", Build.SOC_MODEL.orEmpty())
        }
        put("hw_sdk_int", Build.VERSION.SDK_INT)
        put("hw_manufacturer", Build.MANUFACTURER.orEmpty())
        put("hw_model", Build.MODEL.orEmpty())
        // YYYY-MM only: the month tells us the patch level; the day adds nothing.
        Build.VERSION.SECURITY_PATCH.takeIf { it.length >= 7 }?.let { put("hw_security_patch", it.take(7)) }
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val tv = ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val flavour = when {
            Build.MANUFACTURER.equals("amazon", ignoreCase = true) -> "fire_os"
            tv && context.packageManager.hasSystemFeature("com.google.android.tv") -> "google_tv"
            tv -> "android_tv"
            else -> "android"
        }
        put("hw_os_flavour", flavour)
        put("hw_device_class", if (tv) "tv" else if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone")
    }

    private fun display(context: Context): Map<String, Any> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyMap()
        return buildMap {
            val mode = d.mode
            put("hw_display_res", "${mode.physicalWidth}x${mode.physicalHeight}")
            put("hw_refresh_rates", d.supportedModes.map { it.refreshRate.toInt() }.distinct().sorted().joinToString(","))
            val hdr = (d.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)).toList().mapNotNull {
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "hdr10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "hlg"
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "dolby_vision"
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "hdr10_plus"
                    else -> null
                }
            }
            put("hw_hdr", hdr.joinToString(","))
        }
    }

    /** Hardware decoder presence and the largest size each advertises. Software-only decoders do not count. */
    private fun decoders(): Map<String, Any> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        val mimes = mapOf(
            "h264" to "video/avc",
            "hevc" to "video/hevc",
            "av1" to "video/av01",
            "vp9" to "video/x-vnd.on2.vp9",
            "dv" to "video/dolby-vision",
        )
        return buildMap {
            for ((label, mime) in mimes) {
                var hw = false
                var maxW = 0
                var maxH = 0
                for (info in list) {
                    if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                    val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
                    else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                    if (!isHw) continue
                    hw = true
                    runCatching {
                        val v = info.getCapabilitiesForType(mime).videoCapabilities
                        if (v != null) {
                            maxW = maxOf(maxW, v.supportedWidths.upper)
                            maxH = maxOf(maxH, v.supportedHeights.upper)
                        }
                    }
                }
                put("hw_dec_${label}", if (hw) "hw" else "none")
                if (hw && maxW > 0) put("hw_dec_${label}_max", "${maxW}x$maxH")
            }
        }
    }

    private fun audio(context: Context): Map<String, Any> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyMap()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        fun direct(encoding: Int): Boolean = runCatching {
            val fmt = AudioFormat.Builder().setEncoding(encoding).setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build()
            android.media.AudioTrack.isDirectPlaybackSupported(fmt, attrs)
        }.getOrDefault(false)
        val pass = buildList {
            if (direct(AudioFormat.ENCODING_AC3)) add("ac3")
            if (direct(AudioFormat.ENCODING_E_AC3)) add("eac3")
            if (direct(AudioFormat.ENCODING_DTS)) add("dts")
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val route = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.map { it.type }?.distinct()?.sorted()?.joinToString(",").orEmpty()
        return mapOf("hw_audio_passthrough" to pass.joinToString(","), "hw_audio_output_types" to route)
    }

    /** Free storage as a coarse bucket, never a byte count. */
    private fun storage(): Map<String, Any> {
        val free = StatFs(Environment.getDataDirectory().path).availableBytes / (1024L * 1024L * 1024L)
        val bucket = when {
            free < 1 -> "lt1gb"
            free < 4 -> "1to4gb"
            free < 16 -> "4to16gb"
            else -> "gt16gb"
        }
        return mapOf("hw_storage_free" to bucket)
    }
}
