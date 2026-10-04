package com.aashish.s25cameraprobe

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DecimalFormat
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var reportView: TextView
    private lateinit var scanButton: Button
    private lateinit var copyButton: Button
    private lateinit var shareButton: Button

    private var lastReport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        scanButton.setOnClickListener { ensurePermissionThenScan() }
        copyButton.setOnClickListener { copyReport() }
        shareButton.setOnClickListener { shareReport() }

        ensurePermissionThenScan()
    }

    private fun buildUi(): View {
        val pad = dp(18)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(12, 12, 12))
        }

        val title = TextView(this).apply {
            text = "S25 Ultra Camera Probe"
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Camera2 / RAW / 10-bit capability scanner"
            setTextColor(Color.rgb(170, 170, 170))
            textSize = 14f
            setPadding(0, dp(4), 0, dp(14))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        scanButton = Button(this).apply { text = "SCAN" }
        copyButton = Button(this).apply { text = "COPY" }
        shareButton = Button(this).apply { text = "SHARE" }

        fun addButton(button: Button) {
            buttonRow.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
            )
        }

        addButton(scanButton)
        addButton(copyButton)
        addButton(shareButton)

        reportView = TextView(this).apply {
            setTextColor(Color.rgb(225, 225, 225))
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(24))
            text = "Ready."
        }

        val scroll = ScrollView(this).apply {
            addView(
                reportView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(buttonRow)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        return root
    }

    private fun ensurePermissionThenScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        } else {
            runProbe()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                runProbe()
            } else {
                reportView.text = "Camera permission was denied.\n\nGrant Camera permission and tap SCAN."
            }
        }
    }

    private fun runProbe() {
        scanButton.isEnabled = false
        reportView.text = "Scanning Camera2 capabilities…"

        Thread {
            val result = try {
                buildCameraReport()
            } catch (t: Throwable) {
                buildString {
                    appendLine("PROBE FAILED")
                    appendLine()
                    appendLine("${t.javaClass.simpleName}: ${t.message}")
                    appendLine()
                    appendLine(t.stackTraceToString())
                }
            }

            runOnUiThread {
                lastReport = result
                reportView.text = result
                scanButton.isEnabled = true
            }
        }.start()
    }

    private fun buildCameraReport(): String {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val publicIds = cameraManager.cameraIdList.toList()

        val discoveredPhysicalIds = linkedSetOf<String>()
        for (cameraId in publicIds) {
            try {
                val c = cameraManager.getCameraCharacteristics(cameraId)
                discoveredPhysicalIds.addAll(c.physicalCameraIds)
            } catch (_: Throwable) {
                // Keep scanning other IDs.
            }
        }

        val allIds = (publicIds + discoveredPhysicalIds).distinct()

        return buildString {
            appendLine("S25 ULTRA CAMERA PROBE")
            appendLine("======================")
            appendLine("Generated by: S25 Camera Probe v0.1")
            appendLine()
            appendLine("DEVICE")
            appendLine("Manufacturer : ${Build.MANUFACTURER}")
            appendLine("Brand        : ${Build.BRAND}")
            appendLine("Model        : ${Build.MODEL}")
            appendLine("Device       : ${Build.DEVICE}")
            appendLine("Android      : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Public IDs   : ${publicIds.joinToString()}")
            appendLine("Physical IDs : ${if (discoveredPhysicalIds.isEmpty()) "none discovered" else discoveredPhysicalIds.joinToString()}")
            appendLine()

            for (cameraId in allIds) {
                val kind = if (publicIds.contains(cameraId)) "PUBLIC / OPENABLE" else "PHYSICAL / INTERNAL"
                try {
                    val c = cameraManager.getCameraCharacteristics(cameraId)
                    appendCamera(cameraId, c, kind)
                } catch (t: Throwable) {
                    appendLine("CAMERA $cameraId [$kind]")
                    appendLine("Could not read characteristics: ${t.javaClass.simpleName}: ${t.message}")
                }
                appendLine()
            }

            appendLine("END OF REPORT")
        }
    }

    private fun StringBuilder.appendCamera(
        id: String,
        c: CameraCharacteristics,
        kind: String
    ) {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val physicalIds = c.physicalCameraIds
        val streamMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        appendLine("CAMERA $id [$kind]")
        appendLine("--------${"-".repeat(id.length)}")
        appendLine("Facing       : ${lensFacingName(c.get(CameraCharacteristics.LENS_FACING))}")
        appendLine("Hardware     : ${hardwareLevelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}")

        val focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        appendLine("Focal lengths: ${focalLengths?.joinToString { "${format(it)} mm" } ?: "n/a"}")

        val apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        appendLine("Apertures    : ${apertures?.joinToString { "f/${format(it)}" } ?: "n/a"}")

        appendLine("Physical IDs : ${if (physicalIds.isEmpty()) "none" else physicalIds.joinToString()}")
        appendLine("Logical multi: ${yesNo(caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA))}")

        val sensorPixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val sensorPhysical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        appendLine("Pixel array  : ${sensorPixels?.let { "${it.width} × ${it.height}" } ?: "n/a"}")
        appendLine(
            "Sensor size  : ${
                sensorPhysical?.let { "${format(it.width)} × ${format(it.height)} mm" } ?: "n/a"
            }"
        )

        appendLine()
        appendLine("CAPABILITIES")
        appendLine("RAW_SENSOR           : ${yesNo(caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))}")
        appendLine("Manual sensor        : ${yesNo(caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))}")
        appendLine("Manual post process  : ${yesNo(caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING))}")
        appendLine("10-bit dynamic range : ${yesNo(caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT))}")
        appendLine("High-speed video     : ${yesNo(caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO))}")
        appendLine("Ultra-high-res sensor: ${yesNo(caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR))}")
        appendLine("All capabilities     : ${caps.joinToString { capabilityName(it) }}")

        appendLine()
        appendLine("10-BIT / DYNAMIC RANGE")
        val drProfiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        if (drProfiles == null) {
            appendLine("Profiles      : not exposed")
        } else {
            val supported = drProfiles.supportedProfiles.sorted()
            appendLine("Profiles      : ${supported.joinToString { dynamicRangeName(it) }}")
            appendLine("HLG10         : ${yesNo(supported.contains(DynamicRangeProfiles.HLG10))}")
            appendLine("HDR10         : ${yesNo(supported.contains(DynamicRangeProfiles.HDR10))}")
            appendLine("HDR10+        : ${yesNo(supported.contains(DynamicRangeProfiles.HDR10_PLUS))}")
        }

        appendLine()
        appendLine("MANUAL EXPOSURE")
        appendLine("ISO range     : ${rangeText(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE))}")
        val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        appendLine(
            "Exposure time : ${
                exp?.let {
                    "${nsToShutter(it.lower)} … ${nsToShutter(it.upper)}"
                } ?: "n/a"
            }"
        )
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        appendLine("Manual focus  : ${if ((minFocus ?: 0f) > 0f) "YES (max ${format(minFocus!!)} diopters)" else "NO / fixed focus"}")

        val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        appendLine("AE FPS ranges : ${fpsRanges?.joinToString { "${it.lower}-${it.upper}" } ?: "n/a"}")

        appendLine()
        appendLine("PROCESSING CONTROLS")
        val nrModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        val edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        val toneModes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        appendLine("Noise reduction: ${nrModes?.joinToString { noiseReductionName(it) } ?: "n/a"}")
        appendLine("Edge/sharpening: ${edgeModes?.joinToString { edgeName(it) } ?: "n/a"}")
        appendLine("Tone mapping   : ${toneModes?.joinToString { tonemapName(it) } ?: "n/a"}")
        appendLine("NR can be OFF  : ${yesNo(nrModes?.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF) == true)}")
        appendLine("Edge can be OFF: ${yesNo(edgeModes?.contains(CameraMetadata.EDGE_MODE_OFF) == true)}")

        appendLine()
        appendLine("STABILIZATION")
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        val eis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        appendLine("OIS modes      : ${ois?.joinToString { oisName(it) } ?: "n/a"}")
        appendLine("EIS modes      : ${eis?.joinToString { eisName(it) } ?: "n/a"}")

        appendLine()
        appendLine("RAW OUTPUT SIZES")
        val rawSizes = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.sortedByDescending { it.width.toLong() * it.height }
            .orEmpty()

        if (rawSizes.isEmpty()) {
            appendLine("No RAW_SENSOR output sizes exposed.")
        } else {
            rawSizes.forEach { appendLine("${it.width} × ${it.height}  (${megapixels(it)} MP)") }
        }

        appendLine()
        appendLine("VIDEO OUTPUT SIZES / APPROX MAX NORMAL FPS")
        val videoSizes = try {
            streamMap?.getOutputSizes(MediaRecorder::class.java)
                ?.sortedByDescending { it.width.toLong() * it.height }
                .orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }

        if (videoSizes.isEmpty()) {
            appendLine("No MediaRecorder sizes exposed.")
        } else {
            videoSizes.take(30).forEach { size ->
                val fps = approximateMaxFps(streamMap, size)
                appendLine("${size.width} × ${size.height}  ~${fps ?: "?"} fps max")
            }
            if (videoSizes.size > 30) {
                appendLine("… ${videoSizes.size - 30} additional sizes omitted")
            }
        }

        appendLine()
        appendLine("COMMON VIDEO MODES")
        val common = listOf(
            Size(7680, 4320),
            Size(3840, 2160),
            Size(1920, 1080),
            Size(1280, 720)
        )
        for (size in common) {
            val available = videoSizes.any { it == size }
            val fps = if (available) approximateMaxFps(streamMap, size) else null
            appendLine("${size.width}×${size.height}: ${if (available) "YES (~${fps ?: "?"} fps normal)" else "NO"}")
        }

        appendLine()
        appendLine("CONSTRAINED HIGH-SPEED VIDEO")
        val hsSizes = try {
            streamMap?.highSpeedVideoSizes
                ?.sortedByDescending { it.width.toLong() * it.height }
                .orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }

        if (hsSizes.isEmpty()) {
            appendLine("No constrained high-speed modes exposed.")
        } else {
            for (size in hsSizes) {
                val ranges = try {
                    streamMap?.getHighSpeedVideoFpsRangesFor(size)
                } catch (_: Throwable) {
                    null
                }
                appendLine("${size.width} × ${size.height}: ${ranges?.joinToString { "${it.lower}-${it.upper} fps" } ?: "fps n/a"}")
            }
        }

        appendLine()
        appendLine("INTERPRETATION FLAGS")
        appendLine("RAW DNG candidate     : ${yesNo(caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) && rawSizes.isNotEmpty())}")
        appendLine("10-bit video candidate: ${yesNo(caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) && drProfiles != null)}")
        appendLine("Minimal NR candidate  : ${yesNo(nrModes?.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF) == true)}")
        appendLine("Minimal edge candidate: ${yesNo(edgeModes?.contains(CameraMetadata.EDGE_MODE_OFF) == true)}")
    }

    private fun approximateMaxFps(
        map: android.hardware.camera2.params.StreamConfigurationMap?,
        size: Size
    ): Int? {
        if (map == null) return null
        return try {
            val ns = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
            if (ns > 0L) (1_000_000_000.0 / ns.toDouble()).roundToInt() else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun dynamicRangeName(value: Long): String = when (value) {
        DynamicRangeProfiles.STANDARD -> "STANDARD"
        DynamicRangeProfiles.HLG10 -> "HLG10"
        DynamicRangeProfiles.HDR10 -> "HDR10"
        DynamicRangeProfiles.HDR10_PLUS -> "HDR10+"
        else -> "PROFILE($value)"
    }

    private fun capabilityName(value: Int): String = when (value) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RES_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "DYNAMIC_RANGE_10_BIT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "COLOR_SPACE_PROFILES"
        else -> "CAP($value)"
    }

    private fun hardwareLevelName(value: Int?): String = when (value) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "unknown"
    }

    private fun lensFacingName(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "unknown"
    }

    private fun noiseReductionName(value: Int): String = when (value) {
        CameraMetadata.NOISE_REDUCTION_MODE_OFF -> "OFF"
        CameraMetadata.NOISE_REDUCTION_MODE_FAST -> "FAST"
        CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL -> "MINIMAL"
        CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "ZSL"
        else -> "NR($value)"
    }

    private fun edgeName(value: Int): String = when (value) {
        CameraMetadata.EDGE_MODE_OFF -> "OFF"
        CameraMetadata.EDGE_MODE_FAST -> "FAST"
        CameraMetadata.EDGE_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG -> "ZSL"
        else -> "EDGE($value)"
    }

    private fun tonemapName(value: Int): String = when (value) {
        CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE -> "CONTRAST_CURVE"
        CameraMetadata.TONEMAP_MODE_FAST -> "FAST"
        CameraMetadata.TONEMAP_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.TONEMAP_MODE_GAMMA_VALUE -> "GAMMA_VALUE"
        CameraMetadata.TONEMAP_MODE_PRESET_CURVE -> "PRESET_CURVE"
        else -> "TONEMAP($value)"
    }

    private fun oisName(value: Int): String = when (value) {
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
        else -> "OIS($value)"
    }

    private fun eisName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "ON"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "PREVIEW_STABILIZATION"
        else -> "EIS($value)"
    }

    private fun nsToShutter(ns: Long): String {
        if (ns <= 0) return "n/a"
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) {
            "${format(seconds)} s"
        } else {
            val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
            "1/$denominator s"
        }
    }

    private fun megapixels(size: Size): String =
        DecimalFormat("0.0").format(size.width.toDouble() * size.height / 1_000_000.0)

    private fun <T : Comparable<T>> rangeText(range: android.util.Range<T>?): String =
        range?.let { "${it.lower} … ${it.upper}" } ?: "n/a"

    private fun yesNo(value: Boolean): String = if (value) "YES" else "NO"

    private fun format(value: Float): String = DecimalFormat("0.##").format(value.toDouble())
    private fun format(value: Double): String = DecimalFormat("0.##").format(value)

    private fun copyReport() {
        if (lastReport.isBlank()) {
            Toast.makeText(this, "Run the scan first.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("S25 Camera Probe", lastReport))
        Toast.makeText(this, "Report copied.", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        if (lastReport.isBlank()) {
            Toast.makeText(this, "Run the scan first.", Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "S25 Ultra Camera Probe Report")
                    putExtra(Intent.EXTRA_TEXT, lastReport)
                },
                "Share camera report"
            )
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }
}
