package com.aashish.s25cameraprobe

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.widget.TextView

/** Device integration check: real session, DNG write and recorder finalization. */
class CameraSmoke: Instrumentation() {
    private var stabilizationProbe=false
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments); stabilizationProbe=arguments?.getString("stabilizationProbe")=="true"; start() }
    override fun onStart() {
        val output=Bundle()
        if(stabilizationProbe) {
            val manager=targetContext.getSystemService(android.hardware.camera2.CameraManager::class.java)
            val ids=(manager.cameraIdList.toList()+manager.cameraIdList.flatMap { manager.getCameraCharacteristics(it).physicalCameraIds }).distinct()
            for(id in ids) {
                val c=manager.getCameraCharacteristics(id)
                output.putString("camera_$id", "OIS=${c[android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.joinToString()} EIS=${c[android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.joinToString()} physicalKeys=${c.availablePhysicalCameraRequestKeys?.joinToString { it.name }} stabilizationRequestKeys=${c.availableCaptureRequestKeys?.filter { it.name.contains("stabil",true) || it.name.contains("ois",true) }?.joinToString { it.name }}")
            }
            finish(-1,output); return
        }
        var activity:CameraActivity?=null
        try {
            activity=startActivitySync(Intent(targetContext,CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraActivity
            val a=activity
            fun field(name:String):Any?=CameraActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)
            fun status():String { var result=""; runOnMainSync { result=(field("status") as TextView).text.toString() }; return result }
            fun invoke(name:String) { runOnMainSync { (field("status") as TextView).text="Test in progress" }; val m=CameraActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }; (field("handler") as Handler).post { m.invoke(a) } }
            fun waitFor(label:String,condition:()->Boolean) { val until=System.currentTimeMillis()+20000; while(System.currentTimeMillis()<until) { if(condition()) return; Thread.sleep(200) }; error("$label: ${status()}") }
            runOnMainSync { (field("settingsScroll") as android.view.View).visibility=android.view.View.VISIBLE; ((field("lens") as android.widget.Spinner).parent as android.view.View).visibility=android.view.View.VISIBLE }; waitFor("Preview") { status().contains("RAW ready") }; output.putString("preview",status()); output.putString("photo_modes",field("photos").toString()); output.putString("video_modes",field("videos").toString()); output.putString("capabilities",(field("capabilityText") as TextView).text.toString())
            invoke("takePhoto"); waitFor("DNG") { status().contains("RAW DNG saved") }; output.putString("photo",status())
            val oisResult=field("lastCaptureResult") as android.hardware.camera2.CaptureResult
            check(oisResult[android.hardware.camera2.CaptureResult.LENS_OPTICAL_STABILIZATION_MODE]==1) { "Default physical 1x OIS did not report ON" }
            output.putString("ois","Physical main lens driver confirms OIS ON")
            runOnMainSync { (field("processingSwitches") as List<android.widget.Switch>)[6].isChecked=false }
            invoke("takePhoto"); waitFor("OIS OFF photo") { status().contains("RAW DNG saved") }
            check((field("lastCaptureResult") as android.hardware.camera2.CaptureResult)[android.hardware.camera2.CaptureResult.LENS_OPTICAL_STABILIZATION_MODE]==0) { "OIS OFF not applied" }
            runOnMainSync { (field("processingSwitches") as List<android.widget.Switch>)[6].isChecked=true }
            output.putString("ois_toggle","Driver OIS ON and OFF verified")
            val cameraSession=field("session") as android.hardware.camera2.CameraCaptureSession
            (field("handler") as Handler).post { cameraSession.stopRepeating() }
            Thread.sleep(700)
            val frame=field("previewFrame") as android.view.View
            val rect=android.graphics.Rect()
            runOnMainSync { (field("texture") as android.view.View).getGlobalVisibleRect(rect) }
            val before=uiAutomation.takeScreenshot() ?: error("No screenshot")
            val lut=PreviewLut(targetContext.assets.open("luts/Hollywood-Gold.cube"))
            val apply=CameraActivity::class.java.getDeclaredMethod("applyPreviewLook").apply { isAccessible=true }
            runOnMainSync { CameraActivity::class.java.getDeclaredField("currentLut").apply { isAccessible=true }.set(a,lut); apply.invoke(a) }
            Thread.sleep(500)
            val after=uiAutomation.takeScreenshot() ?: error("No screenshot")
            var difference=0L; var count=0
            for(y in rect.top+10 until rect.bottom-10 step 8) for(x in rect.left+10 until rect.right-10 step 8) {
                val v=before.getPixel(x,y); val w=after.getPixel(x,y)
                difference+=kotlin.math.abs(android.graphics.Color.red(v)-android.graphics.Color.red(w))+kotlin.math.abs(android.graphics.Color.green(v)-android.graphics.Color.green(w))+kotlin.math.abs(android.graphics.Color.blue(v)-android.graphics.Color.blue(w)); count+=3
            }
            check(count>0 && difference.toDouble()/count>1.0) { "LUT did not change the rendered viewfinder" }
            output.putString("preview_lut","GPU LUT verified on frozen camera frame; mean RGB change ${difference.toDouble()/count}")
            before.recycle(); after.recycle()
            runOnMainSync { CameraActivity::class.java.getDeclaredField("currentLut").apply { isAccessible=true }.set(a,null); apply.invoke(a) }
            val repeat=CameraActivity::class.java.getDeclaredMethod("repeatPreview",Boolean::class.javaPrimitiveType).apply { isAccessible=true }
            (field("handler") as Handler).post { repeat.invoke(a,false) }

            runOnMainSync { (field("modeVideo") as android.widget.Button).performClick() }
            waitFor("Steady viewfinder") { (field("lastPreviewResult") as? android.hardware.camera2.CaptureResult)?.get(android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)==2 }
            output.putString("steady_viewfinder","Video tab preview: driver mode 2")
            invoke("startVideo"); waitFor("Record") { status().startsWith("Recording") }; Thread.sleep(2500)
            val steadyResult=field("lastPreviewResult") as android.hardware.camera2.CaptureResult
            check(steadyResult[android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE]==2) { "S25 managed stabilization did not activate: ${steadyResult[android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE]}" }
            output.putString("steady_main","1080p30 HLG: driver PREVIEW_STABILIZATION=2; OIS=${steadyResult[android.hardware.camera2.CaptureResult.LENS_OPTICAL_STABILIZATION_MODE]}")
            val stop=CameraActivity::class.java.getDeclaredMethod("stopVideo",Boolean::class.javaPrimitiveType).apply { isAccessible=true }
            (field("handler") as Handler).post { stop.invoke(a,false) }
            waitFor("MP4") { status().startsWith("Video saved") }; output.putString("video",status())
                        // Recreate preview after recorder shutdown, then test manual capture and each lens.
            val create=CameraActivity::class.java.getDeclaredMethod("createSession",Boolean::class.javaPrimitiveType).apply { isAccessible=true }
            (field("handler") as Handler).post { create.invoke(a,false) }
            waitFor("Preview after video") { status().contains("RAW ready") }
            val mainVideoModes=field("videos") as List<Any>
            for((prefix,rate) in listOf("3840x2160" to 30,"1920x1080" to 60)) {
                val choice=mainVideoModes.first { it.toString().startsWith(prefix) && it.toString().contains("$rate fps") }
                runOnMainSync { CameraActivity::class.java.getDeclaredField("selectedVideo").apply { isAccessible=true }.set(a,choice) }
                invoke("startVideo"); waitFor("Physical main $prefix/$rate") { status().startsWith("Recording") }; Thread.sleep(2200)
                val result=field("lastPreviewResult") as android.hardware.camera2.CaptureResult
                check(result[android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE]==2) { "Physical main steady inactive $prefix/$rate" }
                output.putString("steady_physical_${prefix}_$rate","Mode 2; OIS=${result[android.hardware.camera2.CaptureResult.LENS_OPTICAL_STABILIZATION_MODE]}")
                (field("handler") as Handler).post { stop.invoke(a,false) }
                waitFor("Physical main video saved") { status().startsWith("Video saved") }
                (field("handler") as Handler).post { create.invoke(a,false) }
                waitFor("Physical main preview restored") { status().contains("RAW ready") }
            }
            runOnMainSync { (field("modePhoto") as android.widget.Button).performClick() }
            waitFor("Optical photo viewfinder") { (field("lastPreviewResult") as? android.hardware.camera2.CaptureResult)?.get(android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)==0 }
            runOnMainSync { (field("manual") as android.widget.Switch).isChecked=true }
            invoke("takePhoto"); waitFor("Manual DNG") { status().contains("RAW DNG saved") }; output.putString("manual",status())
            val manualResult=field("lastCaptureResult") as android.hardware.camera2.CaptureResult
            check(manualResult[android.hardware.camera2.CaptureResult.CONTROL_AE_MODE]==0) { "Manual AE OFF not applied" }
            fun checkProcessing() {
                val result=field("lastCaptureResult") as android.hardware.camera2.CaptureResult
                check(result[android.hardware.camera2.CaptureResult.NOISE_REDUCTION_MODE]==0) { "Driver did not report NR OFF" }
                check(result[android.hardware.camera2.CaptureResult.EDGE_MODE]==0) { "Driver did not report sharpening OFF" }
            }
            checkProcessing(); output.putString("processing","Driver confirms NR OFF / edge OFF")
            val toggles=field("processingSwitches") as List<android.widget.Switch>
            runOnMainSync { toggles[0].isChecked=true; toggles[1].isChecked=true }
            invoke("takePhoto"); waitFor("Processing ON photo") { status().contains("RAW DNG saved") }
            val enabledResult=field("lastCaptureResult") as android.hardware.camera2.CaptureResult
            check(enabledResult[android.hardware.camera2.CaptureResult.NOISE_REDUCTION_MODE]==1) { "NR ON not applied" }
            check(enabledResult[android.hardware.camera2.CaptureResult.EDGE_MODE]==1) { "Sharpening ON not applied" }
            runOnMainSync { toggles[0].isChecked=false; toggles[1].isChecked=false }
            output.putString("processing_toggle","OFF and ON metadata verified")
            val modes=field("photoMode") as android.widget.Spinner
            runOnMainSync { (modes.parent as android.view.View).visibility=android.view.View.VISIBLE }
            Thread.sleep(300)
            val jpegIndex=(0 until modes.count).firstOrNull { modes.getItemAtPosition(it).toString().startsWith("JPEG") }
            if(jpegIndex!=null) {
                runOnMainSync { (field("status") as TextView).text="Switching photo mode"; modes.setSelection(jpegIndex) }
                waitFor("JPEG preview") { status().contains("JPEG only") }
                invoke("takePhoto"); waitFor("JPEG save") { status().contains("JPEG saved") }
                output.putString("jpeg",status())
                val aspect=CameraActivity::class.java.getDeclaredMethod("selectAspectRatio",Float::class.javaPrimitiveType).apply { isAccessible=true }
                for(ratio in listOf(1f,16f/9,4f/3)) {
                    runOnMainSync { (field("status") as TextView).text="Changing ratio"; aspect.invoke(a,ratio) }
                    waitFor("Aspect preview") { status().contains("JPEG only") }
                    invoke("takePhoto"); waitFor("Aspect JPEG saved") { status().contains("JPEG saved") }
                    val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    targetContext.contentResolver.openInputStream(android.net.Uri.parse(field("lastPhotoPath") as String))!!.use { android.graphics.BitmapFactory.decodeStream(it,null,options) }
                    check(kotlin.math.abs(options.outWidth.toFloat()/options.outHeight-ratio)<0.025f) { "Saved JPEG ratio differs: ${options.outWidth}x${options.outHeight}" }
                    output.putString("aspect_$ratio","Saved JPEG ${options.outWidth}x${options.outHeight}")
                }
                runOnMainSync { (field("status") as TextView).text="Switching photo mode"; modes.setSelection(0) }
                waitFor("RAW restored") { status().contains("RAW ready") }
            }
            val lenses=field("lens") as android.widget.Spinner
            for(index in 1 until lenses.count) {
                runOnMainSync { lenses.setSelection(index) }
                Thread.sleep(1500)
                waitFor("Lens $index preview") { status().contains("RAW ready") }
                invoke("takePhoto"); waitFor("Lens $index DNG") { status().contains("RAW DNG saved") }
                checkProcessing(); output.putString("lens_$index",status())
            }
            runOnMainSync { (field("status") as TextView).text="Switching lens"; lenses.setSelection(0); (field("manual") as android.widget.Switch).isChecked=false }
            waitFor("Main lens restored") { status().contains("RAW ready") }
            val videoModes=field("videoMode") as android.widget.Spinner
            runOnMainSync { (videoModes.parent as android.view.View).visibility=android.view.View.VISIBLE }; Thread.sleep(300)
            for((width,fps,hdr) in listOf(Triple(3840,30,true),Triple(1920,60,true),Triple(1920,120,false),Triple(1920,240,false))) {
                val index=(0 until videoModes.count).first { videoModes.getItemAtPosition(it).toString().startsWith("${width}x${if(width==3840) 2160 else 1080} · $fps fps") }
                runOnMainSync { videoModes.setSelection(index); (field("hlg") as android.widget.Switch).isChecked=hdr }
                Thread.sleep(300)
                invoke("startVideo"); waitFor("$width/$fps record") { status().startsWith("Recording") }; Thread.sleep(2200)
                if(fps<=60) {
                    val result=field("lastPreviewResult") as android.hardware.camera2.CaptureResult
                    check(result[android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE]==2) { "Steady inactive at $width/$fps: ${result[android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE]}" }
                    output.putString("steady_${width}_${fps}","Driver confirms mode 2; OIS=${result[android.hardware.camera2.CaptureResult.LENS_OPTICAL_STABILIZATION_MODE]}")
                }
                (field("handler") as Handler).post { stop.invoke(a,false) }
                waitFor("$width/$fps saved") { status().startsWith("Video saved") }
                val uri=android.net.Uri.parse(field("lastVideoPath") as String)
                val extractor=android.media.MediaExtractor()
                targetContext.contentResolver.openFileDescriptor(uri,"r")!!.use { descriptor ->
                    extractor.setDataSource(descriptor.fileDescriptor)
                    val track=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/")==true }
                    val format=extractor.getTrackFormat(track)
                    check(format.getInteger(android.media.MediaFormat.KEY_WIDTH)==width)
                    extractor.selectTrack(track)
                    var first=-1L; var last=-1L; var samples=0
                    while(extractor.sampleTime>=0) { val time=extractor.sampleTime; if(first<0) first=time; last=time; samples++; extractor.advance() }
                    val measured=(samples-1)*1e6/(last-first)
                    check(kotlin.math.abs(measured-fps)<fps*0.2) { "Recorded FPS $measured does not match $fps" }
                    output.putString("video_${width}_${fps}","Saved; measured ${String.format(java.util.Locale.US,"%.1f",measured)} fps; ${if(hdr) "HLG" else "SDR"}")
                }
                extractor.release()
                (field("handler") as Handler).post { create.invoke(a,false) }
                waitFor("Preview restored") { status().contains("RAW ready") }
            }
            output.putString("result","PASS"); finish(-1,output)
        } catch(e:Throwable) { output.putString("result","FAIL: $e"); finish(0,output) }
        finally { activity?.let { runOnMainSync { it.finish() } } }
    }
}
