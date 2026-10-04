package com.aashish.s25cameraprobe

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.*
import android.media.*
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.view.*
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Camera2 capture; never claims to bypass the vendor HAL. */
class CameraActivity : Activity() {
    private lateinit var texture: TextureView
    private lateinit var status: TextView
    private lateinit var photo: Button
    private lateinit var video: Button
    private lateinit var lens: Spinner
    private lateinit var manual: Switch
    private lateinit var iso: SeekBar
    private lateinit var shutter: SeekBar
    private lateinit var focus: SeekBar
    private lateinit var values: TextView
    private lateinit var whiteBalance: Spinner
    private val wbModes=intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO,CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)
    private val manager by lazy { getSystemService(CameraManager::class.java) }
    private val thread = HandlerThread("OpenFrameCamera")
    private lateinit var handler: Handler
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var preview: Surface? = null
    private var chars: CameraCharacteristics? = null
    private var cameraIds = emptyList<String>()
    private var cameraId = "0"
    private var physicalId: String? = if(Build.MODEL=="SM-S938B") "5" else null
    private var choices = emptyList<Pair<String,String?>>()
    private var previewSize = Size(1920,1080)
    private var recorder: MediaRecorder? = null
    private var videoFile: File? = null
    private var recording = false
    private lateinit var hlg: Switch
    private var recordingHlg = false
    private lateinit var photoMode: Spinner
    private lateinit var videoMode: Spinner
    private lateinit var bitrate: Spinner
    private lateinit var capabilityText: TextView
    private lateinit var customFlat: Switch
    private data class PhotoMode(val size:Size,val format:Int,val maximum:Boolean=false) {
        override fun toString()="${if(format==ImageFormat.RAW_SENSOR) "RAW DNG" else "JPEG"} · ${String.format(Locale.US,"%.1f",size.width.toDouble()*size.height/1e6)} MP · $size${if(maximum) " full sensor" else ""}"
    }
    private data class VideoMode(val size:Size,val fps:Int,val highSpeed:Boolean=false) {
        override fun toString()="$size · $fps fps${if(highSpeed) " high-speed / SDR" else ""}"
    }
    private data class Processing(val label:String,val key:CaptureRequest.Key<Int>,val available:CameraCharacteristics.Key<IntArray>,val off:Int,val on:Int,var enabled:Boolean=false,var supported:Boolean=false)
    private val processing=listOf(
        Processing("Noise reduction",CaptureRequest.NOISE_REDUCTION_MODE,CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,0,CaptureRequest.NOISE_REDUCTION_MODE_FAST),
        Processing("Sharpening",CaptureRequest.EDGE_MODE,CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,0,CaptureRequest.EDGE_MODE_FAST),
        Processing("Hot pixel correction",CaptureRequest.HOT_PIXEL_MODE,CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES,0,CaptureRequest.HOT_PIXEL_MODE_FAST),
        Processing("Lens shading correction",CaptureRequest.SHADING_MODE,CameraCharacteristics.SHADING_AVAILABLE_MODES,0,CaptureRequest.SHADING_MODE_FAST),
        Processing("Distortion correction",CaptureRequest.DISTORTION_CORRECTION_MODE,CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES,0,CaptureRequest.DISTORTION_CORRECTION_MODE_FAST),
        Processing("Chromatic aberration correction",CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,0,CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST),
        Processing("Optical stabilization (OIS)",CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,0,1,enabled=true),
        Processing("Electronic stabilization (EIS)",CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,0,1)
    )
    private val processingSwitches=mutableListOf<Switch>()
    private var photos=emptyList<PhotoMode>()
    private var videos=emptyList<VideoMode>()
    private var selectedPhoto:PhotoMode?=null
    private var selectedVideo:VideoMode?=null
    private var recordingMode:VideoMode?=null
    private var menuUpdate=false
    private var lastVideoPath:String?=null
    private var lastPhotoPath:String?=null
    private lateinit var previewFrame:FrameLayout
    private var currentLut:PreviewLut?=null
    private var lutStrength=0.65f
    private var photoSelected=true
    private val s25Ultra=Build.MANUFACTURER.equals("samsung",true) && Build.MODEL.startsWith("SM-S938")
    private lateinit var steadyVideo:Switch
    private lateinit var stabilizationStatus:TextView
    @Volatile private var lastPreviewResult:CaptureResult?=null
    private var stabilizationReport=""
    private val lensButtons=mutableMapOf<String,Button>()
    private lateinit var ratioButton:Button
    private lateinit var oisButton:Button
    private lateinit var shutterButton:Button
    private lateinit var modePhoto:Button
    private lateinit var modeVideo:Button
    private lateinit var settingsScroll:ScrollView
    private lateinit var lutLabel:TextView
    private lateinit var audio:Switch
    private lateinit var torch:Switch
    private var recordingAudio=false
    private var active = false
    private var generation = 0
    private var capturePending = false
    private val rawImages = mutableMapOf<Long, Image>()
    private val rawResults = mutableMapOf<Long, CaptureResult>()
    private var jpeg = false
    private var lastCaptureResult: CaptureResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread.start(); handler = Handler(thread.looper)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(12,12,12,12) }
        texture = TextureView(this)
        val outer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        outer.addView(texture,LinearLayout.LayoutParams(-1,0,1f))
        outer.addView(ScrollView(this).apply { addView(root) },LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*0.48).toInt()))
        status = TextView(this).apply { setTextColor(Color.WHITE); text="OpenFrame · RAW camera" }
        root.addView(status)
        lens = Spinner(this); root.addView(lens)
        manual = Switch(this).apply { text="Manual exposure / focus"; setTextColor(Color.WHITE) }; root.addView(manual)
        values = TextView(this).apply { setTextColor(Color.WHITE) }; root.addView(values)
        fun slider(label:String,maxValue:Int):SeekBar {
            root.addView(TextView(this).apply { text=label; setTextColor(Color.LTGRAY) })
            return SeekBar(this).apply { max=maxValue; root.addView(this) }
        }
        iso=slider("ISO",100); iso.progress=15
        shutter=slider("Shutter (1/8000 to 1/6 sec)",100); shutter.progress=55
        focus=slider("Focus (infinity → close)",100)
        whiteBalance=Spinner(this).apply { adapter=ArrayAdapter(this@CameraActivity,android.R.layout.simple_spinner_dropdown_item,listOf("WB Auto","WB Daylight","WB Cloudy","WB Tungsten","WB Fluorescent")) }; root.addView(whiteBalance)
        whiteBalance.onItemSelectedListener=object:AdapterView.OnItemSelectedListener { override fun onNothingSelected(p:AdapterView<*>?) {} ; override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long) { handler.post { repeatPreview() } } }
        hlg=Switch(this).apply { text="10-bit HLG HDR video"; setTextColor(Color.WHITE); isChecked=true }; root.addView(hlg)
        fun selector(label:String):Spinner {
            root.addView(TextView(this).apply { text=label; setTextColor(Color.WHITE) })
            return Spinner(this).also { root.addView(it) }
        }
        photoMode=selector("Photo format / resolution")
        videoMode=selector("Video resolution / frame rate")
        bitrate=selector("Video bitrate")
        bitrate.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("20 Mbps","30 Mbps","50 Mbps","80 Mbps","100 Mbps")); bitrate.setSelection(1)
        photoMode.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p:AdapterView<*>?) {}
            override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long) {
                val choice=photos.getOrNull(position) ?: return
                if(!menuUpdate && selectedPhoto!=choice && recorder==null) { selectedPhoto=choice; handler.post { reopen() } }
            }
        }
        videoMode.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p:AdapterView<*>?) {}
            override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long) { selectedVideo=videos.getOrNull(position); if(!menuUpdate && !photoSelected && recorder==null && session!=null) handler.post { createSession(false) } }
        }
        customFlat=Switch(this).apply { text="Custom flat curve (SDR only; not Samsung LOG)"; setTextColor(Color.WHITE); setOnCheckedChangeListener { _,_ -> handler.post { repeatPreview() } } }; root.addView(customFlat)
        processing.forEach { setting ->
            val toggle=Switch(this).apply { text=setting.label; setTextColor(Color.WHITE); setOnCheckedChangeListener { _,on -> if(!menuUpdate) { setting.enabled=on; updateCaptureUi(); handler.post { repeatPreview() } } } }
            processingSwitches.add(toggle); root.addView(toggle)
        }
        capabilityText=TextView(this).apply { setTextColor(Color.LTGRAY); textSize=12f }; root.addView(capabilityText)
        audio=Switch(this).apply { text="Record microphone audio"; setTextColor(Color.WHITE); setOnCheckedChangeListener { _,on -> if(on && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),21) } }; root.addView(audio)
        torch=Switch(this).apply { text="Torch"; setTextColor(Color.WHITE); setOnCheckedChangeListener { _,_ -> handler.post { repeatPreview() } } }; root.addView(torch)
        hlg.setOnCheckedChangeListener { _,on ->
            customFlat.isEnabled=!on && chars?.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)==true
            if(on) customFlat.isChecked=false
        }
        val row=LinearLayout(this)
        photo=Button(this).apply { text="RAW PHOTO"; setOnClickListener { handler.post { takePhoto() } } }
        video=Button(this).apply { text="VIDEO"; setOnClickListener { handler.post { if(recording) stopVideo() else startVideo() } } }
        val probe=Button(this).apply { text="REPORT"; setOnClickListener { if(!recording) startActivity(Intent(this@CameraActivity,MainActivity::class.java)) } }
        listOf(photo,video,probe).forEach { row.addView(it,LinearLayout.LayoutParams(0,-2,1f)) }; root.addView(row)
        root.addView(TextView(this).apply { text="RAW / JPEG photos · SDR / HLG video · optional audio\nProcessing switches affect supported camera controls. Video and preview still use the phone’s processing pipeline."; setTextColor(Color.LTGRAY); textSize=12f })
        // An unobstructed viewfinder with a fixed mode strip and shutter.
        fun range(first:View,last:View):List<View> {
            return (root.indexOfChild(first)..root.indexOfChild(last)).map { root.getChildAt(it) }
        }
        fun labelled(spinner:Spinner)=listOf(root.getChildAt(root.indexOfChild(spinner)-1),spinner)
        val manualViews=range(manual,whiteBalance)
        val photoViews=labelled(photoMode)
        val videoViews=listOf(hlg)+labelled(videoMode)+labelled(bitrate)+listOf(audio)
        steadyVideo=Switch(this).apply { text="Steady video · S25 Ultra"; setTextColor(Color.WHITE); isChecked=s25Ultra; setOnCheckedChangeListener { _,_ -> if(!menuUpdate && recorder==null) handler.post { createSession(false) } } }
        val steadyHelp=TextView(this).apply { text="Camera-managed optical + digital stabilization for video. May crop the view. Photos use optical stabilization. High-speed modes use optical only. OIS travel is controlled by Samsung."; setTextColor(Color.LTGRAY); textSize=12f }
        val processingViews=listOf(steadyVideo,steadyHelp,customFlat)+processingSwitches+listOf(torch)
        root.removeAllViews(); outer.removeAllViews(); (root.parent as? ViewGroup)?.removeView(root); (probe.parent as? ViewGroup)?.removeView(probe)
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(16,4,16,4) }
        val title=TextView(this).apply { text="OPENFRAME"; setTextColor(Color.WHITE); textSize=16f; setTypeface(typeface,Typeface.BOLD) }
        header.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        val settingsButton=Button(this).apply { text="Controls"; isAllCaps=false }
        header.addView(settingsButton); outer.addView(header)
        status.setTextColor(Color.LTGRAY); status.textSize=12f; status.setPadding(20,2,20,8); outer.addView(status)
        stabilizationStatus=TextView(this).apply { textSize=11f; setTextColor(Color.LTGRAY); setPadding(20,0,20,6) }; outer.addView(stabilizationStatus)
        previewFrame=FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(texture,FrameLayout.LayoutParams(-1,-1)) }
        outer.addView(previewFrame,LinearLayout.LayoutParams(-1,0,1f))
        settingsScroll=ScrollView(this).apply { addView(root); visibility=View.GONE }
        outer.addView(settingsScroll,LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*0.35).toInt()))
        settingsButton.setOnClickListener { settingsScroll.visibility=if(settingsScroll.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
        val sectionBodies=mutableMapOf<String,LinearLayout>()
        fun section(title:String,views:List<View>) {
            val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(16,4,16,12); visibility=View.GONE }
            val button=Button(this).apply { text=title+"  ⌄"; isAllCaps=false; gravity=Gravity.START or Gravity.CENTER_VERTICAL; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(24,24,27)) }
            button.setOnClickListener { body.visibility=if(body.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
            sectionBodies[title]=body
            root.addView(button); root.addView(body)
            views.forEach { (it.parent as? ViewGroup)?.removeView(it); body.addView(it) }
        }
        section("Photo quality",photoViews)
        section("Video format and audio",videoViews)
        section("Manual camera",manualViews)
        section("Processing and stabilization",processingViews)
        val looks=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        lutLabel=TextView(this).apply { text="No preview LUT · Original capture"; setTextColor(Color.LTGRAY) }; looks.addView(lutLabel)
        fun lookButton(label:String,action:()->Unit) { looks.addView(Button(this).apply { text=label; isAllCaps=false; setOnClickListener { action() } }) }
        lookButton("Original") { currentLut=null; applyPreviewLook(); lutLabel.text="No preview LUT · Original capture" }
        val freeLooks=assets.list("luts")!!.filter { it.endsWith(".cube") }.sorted()
        val lookPicker=Spinner(this).apply { adapter=ArrayAdapter(this@CameraActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Choose a free look")+freeLooks.map { it.removeSuffix(".cube").removePrefix("FV_TealOrange_").replace("_"," ") }) }
        looks.addView(lookPicker)
        lookPicker.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p:AdapterView<*>?) {}
            override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long) { if(position>0) handler.post { loadPreviewLut(assets.open("luts/"+freeLooks[position-1]),freeLooks[position-1].removeSuffix(".cube").replace("_"," ")) } }
        }
        lookButton("Import Rec.709 creative .cube") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) },30) }
        looks.addView(TextView(this).apply { text="Preview intensity"; setTextColor(Color.LTGRAY) })
        looks.addView(SeekBar(this).apply { max=100; progress=65; setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener { override fun onStartTrackingTouch(s:SeekBar?) {}; override fun onStopTrackingTouch(s:SeekBar?) {}; override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean) { lutStrength=p/100f; if(user) applyPreviewLook() } }) })
        looks.addView(TextView(this).apply { text="Looks affect only the SDR viewfinder. HLG recordings and RAW stay original. Do not load Samsung LOG or HLG conversion LUTs on this SDR preview."; setTextColor(Color.LTGRAY); textSize=12f })
        section("Preview looks · LUT",listOf(looks))
        val help=TextView(this).apply { text="HLG is 10-bit HDR, not Samsung LOG. Grade original clips as Rec.2100 HLG, then export Rec.709. Full sensor modes remain in Samsung Camera."; setTextColor(Color.LTGRAY) }
        val samsung=Button(this).apply { text="Open Samsung Camera"; isAllCaps=false; setOnClickListener { try { startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).setPackage("com.sec.android.app.camera")) } catch(e:Exception) { message("Samsung Camera unavailable") } } }
        section("Capabilities and grading",listOf(help,capabilityText,samsung))
        // Floating capture controls over the live viewfinder.
        val screen=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        fun detach(view:View) { (view.parent as? ViewGroup)?.removeView(view) }
        fun glass(radius:Int=28)=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(Color.argb(205,45,43,42),Color.argb(225,24,24,27))).apply { cornerRadius=radius.dp().toFloat(); setStroke(1.dp(),Color.argb(65,255,255,255)) }
        fun pill(button:Button,label:String,description:String) {
            button.text=label; button.contentDescription=description; button.isAllCaps=false; button.textSize=13f; button.setTextColor(Color.WHITE)
            button.minWidth=0; button.minimumWidth=0; button.minHeight=0; button.minimumHeight=0; button.setPadding(4.dp(),0,4.dp(),0); button.background=glass(24)
        }
        detach(previewFrame); screen.addView(previewFrame,FrameLayout.LayoutParams(-1,-1))
        val overlay=FrameLayout(this)
        screen.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        header.removeAllViews(); header.setPadding(0,0,0,0)
        val flashButton=Button(this); pill(flashButton,"ϟ","Toggle torch")
        flashButton.setOnClickListener { if(torch.isEnabled && recorder==null) { torch.isChecked=!torch.isChecked; flashButton.setTextColor(if(torch.isChecked) Color.rgb(255,214,10) else Color.WHITE) } }
        header.addView(flashButton,LinearLayout.LayoutParams(44.dp(),44.dp()))
        oisButton=Button(this).apply { setOnClickListener { if(steadyRequested(false)) steadyVideo.isChecked=false else if(processing[6].supported) processingSwitches[6].isChecked=!processingSwitches[6].isChecked else message("Select physical 1×, 3× or 5× for OIS") } }
        pill(oisButton,"OIS","Optical and steady stabilization")
        header.addView(oisButton,LinearLayout.LayoutParams(88.dp(),44.dp()).apply { leftMargin=8.dp() })
        title.text="OPENFRAME"; title.textSize=11f; title.gravity=Gravity.CENTER; title.letterSpacing=0.12f
        header.addView(title,LinearLayout.LayoutParams(0,44.dp(),1f))
        ratioButton=Button(this); pill(ratioButton,"4:3","Choose capture aspect ratio")
        ratioButton.setOnClickListener { showAspectRatios() }
        header.addView(ratioButton,LinearLayout.LayoutParams(52.dp(),44.dp()).apply { rightMargin=8.dp() })
        pill(settingsButton,"•••","Open camera controls"); header.addView(settingsButton,LinearLayout.LayoutParams(44.dp(),44.dp()))
        detach(header); overlay.addView(header,FrameLayout.LayoutParams(-1,44.dp(),Gravity.TOP).apply { setMargins(18.dp(),10.dp(),18.dp(),0) })
        val messages=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(10.dp(),5.dp(),10.dp(),5.dp()); background=glass(12) }
        listOf(status,stabilizationStatus).forEach { detach(it); it.maxLines=1; it.ellipsize=android.text.TextUtils.TruncateAt.END; it.setPadding(0,0,0,0); it.textSize=10f; messages.addView(it) }
        overlay.addView(messages,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { setMargins(18.dp(),64.dp(),18.dp(),0) })
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(14.dp(),10.dp(),14.dp(),8.dp()); background=glass(); elevation=8.dp().toFloat() }
        val quick=LinearLayout(this).apply { gravity=Gravity.CENTER }
        for((label,id) in listOf("0.6×" to "2","1×" to "5","3×" to "6","5×" to "7")) {
            val button=Button(this); pill(button,label,"Select $label lens"); button.background=null
            button.setOnClickListener { if(recorder==null && !capturePending) choices.indexOfFirst { (it.second ?: it.first)==id }.takeIf { it>=0 }?.let { selectLens(it) } }
            lensButtons[id]=button; quick.addView(button,LinearLayout.LayoutParams(0,36.dp(),1f))
        }
        panel.addView(quick)
        val shortcuts=LinearLayout(this)
        fun openGroup(name:String) {
            sectionBodies.forEach { (key,body) -> body.visibility=if(key==name) View.VISIBLE else View.GONE }
            settingsScroll.visibility=View.VISIBLE
            settingsScroll.post { sectionBodies[name]?.let { settingsScroll.smoothScrollTo(0,it.top-48.dp()) } }
        }
        for((symbol,label,group) in listOf(Triple("◉","LOOKS","Preview looks · LUT"),Triple("±","MANUAL","Manual camera"),Triple("▣","FORMAT","Video format and audio"),Triple("☷","CONTROLS","Processing and stabilization"))) {
            val button=Button(this); pill(button,"$symbol\n$label","Open $label"); button.background=null; button.textSize=10f; button.setLineSpacing(3.dp().toFloat(),1f)
            button.setOnClickListener { openGroup(group) }; shortcuts.addView(button,LinearLayout.LayoutParams(0,56.dp(),1f))
        }
        panel.addView(shortcuts)
        val modes=LinearLayout(this).apply { gravity=Gravity.CENTER }
        modePhoto=Button(this).apply { setOnClickListener { if(recorder==null && !capturePending) { photoSelected=true; updateCaptureUi(); handler.post { createSession(false) } } } }; pill(modePhoto,"PHOTO","Photo mode")
        modeVideo=Button(this).apply { setOnClickListener { if(recorder==null && !capturePending) { photoSelected=false; updateCaptureUi(); handler.post { createSession(false) } } } }; pill(modeVideo,"VIDEO","Video mode")
        modes.addView(modePhoto,LinearLayout.LayoutParams(0,38.dp(),1f)); modes.addView(TextView(this).apply { text="⌄"; setTextColor(Color.LTGRAY); gravity=Gravity.CENTER; setOnClickListener { settingsScroll.visibility=if(settingsScroll.visibility==View.VISIBLE) View.GONE else View.VISIBLE } },LinearLayout.LayoutParams(38.dp(),38.dp())); modes.addView(modeVideo,LinearLayout.LayoutParams(0,38.dp(),1f))
        panel.addView(modes)
        overlay.addView(panel,FrameLayout.LayoutParams(-1,158.dp(),Gravity.BOTTOM).apply { setMargins(18.dp(),0,18.dp(),18.dp()) })
        val capture=FrameLayout(this)
        shutterButton=Button(this).apply { setTextColor(Color.BLACK); setPadding(0,0,0,0); text=""; setOnClickListener { handler.post { if(photoSelected) takePhoto() else if(recording) stopVideo() else startVideo() } } }
        capture.addView(shutterButton,FrameLayout.LayoutParams(78.dp(),78.dp(),Gravity.CENTER))
        pill(probe,"ⓘ","Camera capability report"); probe.textSize=26f
        capture.addView(probe,FrameLayout.LayoutParams(58.dp(),58.dp(),Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin=30.dp() })
        val flip=Button(this); pill(flip,"↻","Switch front and rear cameras"); flip.textSize=30f
        flip.setOnClickListener { if(recorder==null && !capturePending) { val front=chars?.get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_FRONT; val index=if(front) choices.indexOfFirst { it.first=="0" && it.second=="5" } else choices.indexOfFirst { it.first=="1" && it.second==null }; if(index>=0) selectLens(index) } }
        capture.addView(flip,FrameLayout.LayoutParams(58.dp(),58.dp(),Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin=30.dp() })
        overlay.addView(capture,FrameLayout.LayoutParams(-1,90.dp(),Gravity.BOTTOM).apply { bottomMargin=192.dp() })
        detach(lens); section("All lenses",listOf(lens))
        root.setPadding(12.dp(),12.dp(),12.dp(),12.dp()); root.background=glass()
        detach(settingsScroll); settingsScroll.background=glass(); settingsScroll.clipToOutline=true; settingsScroll.elevation=14.dp().toFloat()
        overlay.addView(settingsScroll,FrameLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*0.48).toInt(),Gravity.BOTTOM).apply { setMargins(18.dp(),0,18.dp(),184.dp()) })
        settingsButton.setOnClickListener { settingsScroll.visibility=if(settingsScroll.visibility==View.VISIBLE) View.GONE else View.VISIBLE }
        overlay.setOnApplyWindowInsetsListener { view,insets -> val bars=insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets }
        previewFrame.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> framePreview() }
        updateCaptureUi(); setContentView(screen); overlay.requestApplyInsets()
        cameraIds=manager.cameraIdList.toList()
        choices=cameraIds.map { it to null } + cameraIds.flatMap { id -> manager.getCameraCharacteristics(id).physicalCameraIds.filter { it !in cameraIds }.map { id to it } }
        lens.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,choices.map { (id,physical) ->
            val c=manager.getCameraCharacteristics(physical ?: id); if(Build.MODEL=="SM-S938B") when(physical ?: id) { "0" -> "Main · Auto lens"; "1" -> "Selfie · Wide"; "2" -> "Ultrawide · 0.6×"; "3" -> "Selfie · Close crop"; "5" -> "Main · 1×"; "6" -> "Telephoto · 3×"; "7" -> "Super telephoto · 5×"; else -> "Camera ${physical ?: id}" } else "${if(c[CameraCharacteristics.LENS_FACING]==CameraCharacteristics.LENS_FACING_FRONT) "Selfie" else "Rear"} · ${c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.joinToString()} mm"
        })
        choices.indexOfFirst { it.first==cameraId && it.second==physicalId }.takeIf { it>=0 }?.let { selectLens(it) }
        lens.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p:AdapterView<*>?) {}
            override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long) { selectLens(position) }
        }
        val listener=object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s:SeekBar?) {}
            override fun onStopTrackingTouch(s:SeekBar?) {}
            override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean) { updateValues(); if(user) handler.post { repeatPreview() } }
        }
        listOf(iso,shutter,focus).forEach { it.setOnSeekBarChangeListener(listener) }
        manual.setOnCheckedChangeListener { _,_ -> updateValues(); handler.post { repeatPreview() } }
        texture.surfaceTextureListener=object:TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t:SurfaceTexture,w:Int,h:Int) { if(active) handler.post { reopen() } }
            override fun onSurfaceTextureSizeChanged(t:SurfaceTexture,w:Int,h:Int) { transform() }
            override fun onSurfaceTextureUpdated(t:SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(t:SurfaceTexture):Boolean { handler.post { closeCamera() }; return true }
        }
    }
    override fun onResume() { super.onResume(); active=true; if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA),20) else if(texture.isAvailable) handler.post { reopen() } }
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray) { super.onRequestPermissionsResult(r,p,g); if(r==20 && g.firstOrNull()==PackageManager.PERMISSION_GRANTED && texture.isAvailable) handler.post { reopen() } else if(r==20) message("Camera permission is required"); if(r==21 && g.firstOrNull()!=PackageManager.PERMISSION_GRANTED) { audio.isChecked=false; message("Microphone permission denied; silent recording remains available") } }
    override fun onPause() { active=false; handler.post { closeCamera() }; super.onPause() }
    override fun onDestroy() { handler.post { closeCamera(); thread.quitSafely() }; super.onDestroy() }
    private fun selectLens(position:Int) {
        val selected=choices.getOrNull(position) ?: return
        if(cameraId==selected.first && physicalId==selected.second) { lens.setSelection(position); return }
        if(recorder!=null || capturePending) return
        cameraId=selected.first; physicalId=selected.second; lens.setSelection(position); updateCaptureUi()
        photos=emptyList(); selectedPhoto=null; selectedVideo=null
        if(active && texture.isAvailable) handler.post { reopen() }
    }
    private fun Int.dp()=(this*resources.displayMetrics.density).toInt()
    private fun updateCaptureUi() {
        if(!::shutterButton.isInitialized) return
        modePhoto.setTextColor(if(photoSelected) Color.rgb(255,214,10) else Color.WHITE)
        modeVideo.setTextColor(if(!photoSelected) Color.rgb(255,214,10) else Color.WHITE)
        listOf(modePhoto,modeVideo).forEach { it.background=null }
        lensButtons.forEach { (id,button) -> val selected=(physicalId ?: cameraId)==id; button.setTextColor(if(selected) Color.rgb(255,214,10) else Color.WHITE); button.background=if(selected) android.graphics.drawable.GradientDrawable().apply { cornerRadius=20.dp().toFloat(); setColor(Color.argb(110,255,255,255)) } else null }
        shutterButton.background=android.graphics.drawable.GradientDrawable().apply { shape=android.graphics.drawable.GradientDrawable.OVAL; setColor(if(photoSelected) Color.WHITE else Color.rgb(255,59,48)); setStroke(4.dp(),Color.LTGRAY) }
        shutterButton.text=if(recording) "■" else ""; shutterButton.contentDescription=if(recording) "Stop recording" else if(photoSelected) "Take photo" else "Record video"
        if(::ratioButton.isInitialized) { val size=if(photoSelected) selectedPhoto?.size else selectedVideo?.size; ratioButton.text=size?.let { aspectName(it) } ?: "Ratio"; ratioButton.isEnabled=recorder==null && !capturePending }
        shutterButton.isEnabled=!capturePending && session!=null && (recording || recorder==null)
        if(::oisButton.isInitialized) { oisButton.isEnabled=recorder==null && !capturePending; oisButton.text=if(steadyRequested(recording)) "STEADY" else if(!processing[6].supported) "OIS N/A" else if(processing[6].enabled) "OIS ON" else "OIS OFF"; oisButton.setTextColor(if(processing[6].supported && processing[6].enabled) Color.rgb(255,214,10) else Color.WHITE) }
    }
    private fun applyPreviewLook() { previewFrame.setRenderEffect(currentLut?.effect(lutStrength)); previewFrame.invalidate() }
    private fun loadPreviewLut(input:java.io.InputStream,label:String) {
        try { val lut=PreviewLut(input); runOnUiThread { currentLut=lut; applyPreviewLook(); lutLabel.text="$label · Preview only" } }
        catch(e:Exception) { input.close(); message("LUT: ${e.message}") }
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==30 && resultCode==RESULT_OK) data?.data?.let { uri -> handler.post { try { val input=contentResolver.openInputStream(uri) ?: error("Cannot read LUT"); loadPreviewLut(input,"Imported Rec.709 LUT") } catch(e:Exception) { message("LUT: ${e.message}") } } }
    }
    private fun message(text:String) { runOnUiThread { status.text=text } }
    private fun controls() { runOnUiThread { photo.isEnabled=session!=null && !recording && !capturePending; lens.isEnabled=recorder==null && !capturePending; hlg.isEnabled=recorder==null; photoMode.isEnabled=recorder==null && !capturePending; videoMode.isEnabled=recorder==null; bitrate.isEnabled=recorder==null; audio.isEnabled=recorder==null; steadyVideo.isEnabled=s25Ultra && recorder==null; video.text=if(recording) "Stop recording" else "Record video"; updateCaptureUi() } }
    private fun closeCamera() {
        generation++
        if(recording) stopVideo(false) else if(recorder!=null) abortVideo()
        session?.close(); session=null; device?.close(); device=null
        reader?.close(); reader=null; preview?.release(); preview=null
        rawImages.values.forEach { it.close() }; rawImages.clear(); rawResults.clear(); capturePending=false
        controls()
    }
    private fun reopen() {
        closeCamera(); if(!active || !texture.isAvailable || checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) return
        try {
            chars=manager.getCameraCharacteristics(physicalId ?: cameraId)
            val map=chars!![CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
            previewSize=map.getOutputSizes(SurfaceTexture::class.java).filter { it.width<=1920 }.minByOrNull { kotlin.math.abs(it.width.toFloat()/it.height-16f/9f) } ?: map.getOutputSizes(SurfaceTexture::class.java).first()
            if(photos.isEmpty()) discoverModes(map)
            val choice=selectedPhoto ?: photos.first()
            selectedPhoto=choice
            jpeg=choice.format==ImageFormat.JPEG
            val size=choice.size
            reader=ImageReader.newInstance(size.width,size.height,if(jpeg) ImageFormat.JPEG else ImageFormat.RAW_SENSOR,3)
            reader!!.setOnImageAvailableListener({ r ->
                val image=r.acquireNextImage() ?: return@setOnImageAvailableListener
                if(jpeg) saveJpeg(image) else { rawImages[image.timestamp]=image; saveRaw(image.timestamp) }
            },handler)
            val token=generation
            manager.openCamera(cameraId,object:CameraDevice.StateCallback() {
                override fun onOpened(c:CameraDevice) { if(token!=generation || !active) { c.close(); return }; device=c; createSession(false) }
                override fun onDisconnected(c:CameraDevice) { c.close(); if(device===c) { closeCamera(); message("Camera disconnected") } }
                override fun onError(c:CameraDevice,error:Int) { c.close(); if(token==generation) { closeCamera(); message("Camera unavailable ($error). Close other camera apps and reopen.") } }
            },handler)
            runOnUiThread { photo.text=if(jpeg) "Take photo" else "Take RAW"; updateValues(); transform() }
        } catch(e:Exception) { closeCamera(); message("Camera: ${e.message}") }
    }
    private fun transform() {
        if(texture.width==0) return
        val rotation=(chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)-display.rotation*90
        val m=Matrix(); val w=texture.width.toFloat(); val h=texture.height.toFloat()
        val buffer=RectF(0f,0f,previewSize.height.toFloat(),previewSize.width.toFloat()); buffer.offset(w/2-buffer.centerX(),h/2-buffer.centerY())
        if(rotation%180!=0) { m.setRectToRect(RectF(0f,0f,w,h),buffer,Matrix.ScaleToFit.FILL); val scale=maxOf(h/previewSize.width,w/previewSize.height); m.postScale(scale,scale,w/2,h/2); m.postRotate((rotation-90).toFloat(),w/2,h/2) }
        texture.setTransform(m)
    }
    private fun framePreview() {
        val size=(if(recording || !photoSelected) recordingMode?.takeIf { recording }?.size ?: selectedVideo?.size else selectedPhoto?.size) ?: return
        val rotation=((chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)-display.rotation*90+360)%360
        val aspect=if(rotation%180!=0) size.height.toFloat()/size.width else size.width.toFloat()/size.height
        val availableW=previewFrame.width; val availableH=previewFrame.height
        if(availableW<=0 || availableH<=0) return
        val width=minOf(availableW,(availableH*aspect).toInt()); val height=(width/aspect).toInt()
        val params=texture.layoutParams as FrameLayout.LayoutParams
        if(params.width!=width || params.height!=height) texture.layoutParams=FrameLayout.LayoutParams(width,height,Gravity.CENTER)
        transform()
    }
    private fun aspectName(size:Size):String {
        val ratio=size.width.toFloat()/size.height
        return listOf("4:3" to 4f/3,"16:9" to 16f/9,"1:1" to 1f).firstOrNull { kotlin.math.abs(ratio-it.second)<0.025f }?.first ?: "Native"
    }
    private fun showAspectRatios() {
        if(recorder!=null || capturePending) return
        val labels=listOf("4:3","16:9","1:1")
        val ratios=listOf(4f/3,16f/9,1f)
        val choices=labels.mapIndexed { i,label ->
            val available=if(photoSelected) photos.any { it.format==selectedPhoto?.format && kotlin.math.abs(it.size.width.toFloat()/it.size.height-ratios[i])<0.025f } else videos.any { it.fps==selectedVideo?.fps && it.highSpeed==selectedVideo?.highSpeed && kotlin.math.abs(it.size.width.toFloat()/it.size.height-ratios[i])<0.025f }
            label+if(available) "" else " · unavailable for this format"
        }
        android.app.AlertDialog.Builder(this).setTitle("Capture aspect ratio").setItems(choices.toTypedArray()) { _,index -> selectAspectRatio(ratios[index]) }.setNegativeButton("Cancel",null).show()
    }
    private fun selectAspectRatio(ratio:Float) {
        if(recorder!=null || capturePending) return
        if(photoSelected) {
            val choice=photos.filter { it.format==selectedPhoto?.format && kotlin.math.abs(it.size.width.toFloat()/it.size.height-ratio)<0.025f }.maxByOrNull { it.size.width.toLong()*it.size.height }
            if(choice==null) { message("This ratio is unavailable for this format. RAW keeps its native ratio; try JPEG."); return }
            selectedPhoto=choice; photoMode.setSelection(photos.indexOf(choice)); updateCaptureUi(); handler.post { reopen() }
        } else {
            val current=selectedVideo ?: return
            val choice=videos.filter { it.fps==current.fps && it.highSpeed==current.highSpeed && kotlin.math.abs(it.size.width.toFloat()/it.size.height-ratio)<0.025f }.minByOrNull { kotlin.math.abs(it.size.width.toLong()*it.size.height-current.size.width.toLong()*current.size.height) }
            if(choice==null) { message("This ratio is unavailable at the selected frame rate."); return }
            selectedVideo=choice; videoMode.setSelection(videos.indexOf(choice)); updateCaptureUi(); handler.post { createSession(false) }
        }
    }
    private fun createSession(forVideo:Boolean) {
        val c=device ?: return; val token=generation
        session?.close(); session=null; preview?.release()
        val target=if(forVideo) recordingMode?.size else if(photoSelected) selectedPhoto?.size else selectedVideo?.size
        val map=chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if(target!=null && map!=null) previewSize=map.getOutputSizes(SurfaceTexture::class.java).filter { it.width<=1920 }.minWithOrNull(compareBy<Size> { kotlin.math.abs(it.width.toFloat()/it.height-target.width.toFloat()/target.height) }.thenByDescending { it.width.toLong()*it.height }) ?: previewSize
        if(forVideo && recordingMode?.highSpeed==true) previewSize=recordingMode!!.size
        runOnUiThread { framePreview(); updateCaptureUi() }
        val t=texture.surfaceTexture ?: return; t.setDefaultBufferSize(previewSize.width,previewSize.height); preview=Surface(t)
        val surfaces=listOf(preview!!,if(forVideo) recorder!!.surface else reader!!.surface)
        val outputs=surfaces.mapIndexed { index,surface -> OutputConfiguration(surface).apply { physicalId?.let { setPhysicalCameraId(it) }; if(forVideo && recordingHlg && index==1) setDynamicRangeProfile(DynamicRangeProfiles.HLG10); if(!forVideo && index==1 && selectedPhoto?.maximum==true) { removeSensorPixelModeUsed(CaptureRequest.SENSOR_PIXEL_MODE_DEFAULT); addSensorPixelModeUsed(CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION) } } }
        val callback=object:CameraCaptureSession.StateCallback() {
            override fun onConfigured(s:CameraCaptureSession) {
                if(token!=generation || device!==c) { s.close(); return }; session=s
                try { repeatPreview(forVideo); if(forVideo) { recorder!!.start(); recording=true }; runOnUiThread { video.isEnabled=true }; controls(); message(if(forVideo) "Recording · ${recordingMode} ${if(recordingHlg) "10-bit HLG" else "SDR"} · processing controls applied" else if(jpeg) "JPEG only on this camera · processing controls applied" else "RAW ready · processing controls applied") }
                catch(e:Exception) { abortVideo(); message("Session: ${e.message}") }
            }
            override fun onConfigureFailed(s:CameraCaptureSession) { s.close(); abortVideo(); message("This camera rejected the capture streams. Select another camera.") }
        }
        if(forVideo && recordingMode?.highSpeed==true) c.createConstrainedHighSpeedCaptureSession(surfaces,callback,handler)
        else {
            val config=SessionConfiguration(SessionConfiguration.SESSION_REGULAR,outputs,java.util.concurrent.Executor { handler.post(it) },callback)
            val params=c.createCaptureRequest(if(forVideo || !photoSelected) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW,physicalId?.let { setOf(it) } ?: emptySet())
            applyStabilization(params,forVideo)
            config.setSessionParameters(params.build())
            stabilizationReport=""; lastPreviewResult=null
            c.createCaptureSession(config)
        }
    }
    private fun discoverModes(map:StreamConfigurationMap) {
        val c=chars!!
        val list=mutableListOf<PhotoMode>()
        fun add(m:StreamConfigurationMap?,maximum:Boolean) {
            if(m==null) return
            for(format in listOf(ImageFormat.RAW_SENSOR,ImageFormat.JPEG)) {
                val sizes=(m.getOutputSizes(format)?.toList() ?: emptyList())+(m.getHighResolutionOutputSizes(format)?.toList() ?: emptyList())
                sizes.distinct().sortedByDescending { it.width.toLong()*it.height }.forEach { list.add(PhotoMode(it,format,maximum)) }
            }
        }
        add(map,false)
        if(c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)==true) add(c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION],true)
        photos=list.distinct(); selectedPhoto=photos.first()
        val videoList=mutableListOf<VideoMode>()
        map.getOutputSizes(MediaRecorder::class.java).filter { it.width>=640 }.forEach { size ->
            val duration=map.getOutputMinFrameDuration(MediaRecorder::class.java,size)
            for(fps in listOf(24,30,60)) if(duration>0 && duration<=1_000_000_000L/fps+100000L && c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.any { it.contains(fps) }==true) videoList.add(VideoMode(size,fps))
        }
        if(physicalId==null) map.highSpeedVideoSizes?.forEach { size -> map.getHighSpeedVideoFpsRangesFor(size).filter { it.lower==it.upper }.forEach { videoList.add(VideoMode(size,it.upper,true)) } }
        videos=videoList.distinct().sortedWith(compareByDescending<VideoMode> { it.size.width }.thenBy { it.fps })
        selectedVideo=videos.firstOrNull { it.size.width==1920 && it.size.height==1080 && it.fps==30 && !it.highSpeed } ?: videos.firstOrNull()
        runOnUiThread {
            menuUpdate=true
            photoMode.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,photos); photoMode.setSelection(photos.indexOf(selectedPhoto))
            videoMode.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,videos); videoMode.setSelection(videos.indexOf(selectedVideo).coerceAtLeast(0))
            processing.forEachIndexed { i,p -> val modes=c[p.available] ?: intArrayOf(); p.supported=modes.contains(p.off) && modes.contains(p.on); processingSwitches[i].isEnabled=p.supported; processingSwitches[i].text=p.label+if(p.supported) "" else " (unavailable on this lens)"; processingSwitches[i].isChecked=p.supported && p.enabled }
            torch.isEnabled=c[CameraCharacteristics.FLASH_INFO_AVAILABLE]==true; if(!torch.isEnabled) torch.isChecked=false
            customFlat.isEnabled=!hlg.isChecked && c[CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES]?.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)==true
            val maxMp=photos.maxOf { it.size.width.toDouble()*it.size.height/1e6 }
            val profiles=c[CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES]?.supportedProfiles ?: emptySet()
            capabilityText.text="This lens: max photo ${String.format(Locale.US,"%.1f",maxMp)} MP; 8K: ${if(videos.any { it.size.width>=7680 }) "available" else "not exposed"}. HDR10: ${if(profiles.contains(DynamicRangeProfiles.HDR10)) "advertised; recorder not enabled" else "not exposed"}; HDR10+: ${if(profiles.contains(DynamicRangeProfiles.HDR10_PLUS)) "advertised; recorder not enabled" else "not exposed"}. Native Samsung LOG is not exposed. RAW sizes and JPEG sizes may differ."
            menuUpdate=false
        }
    }
    private fun isoValue():Int { val r=chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return 100; return (r.lower+(r.upper-r.lower)*iso.progress/100).coerceIn(r.lower,r.upper) }
    private fun exposure():Long { val r=chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return 10_000_000L; return (125_000.0*Math.pow(1333.333,shutter.progress/100.0)).toLong().coerceIn(r.lower,r.upper) }
    private fun updateValues() { values.text=if(manual.isChecked) "ISO ${isoValue()} · 1/${(1e9/exposure()).toInt()} s · focus ${focus.progress}%" else "Auto exposure / continuous focus" }
    private fun <T> requestSetting(b:CaptureRequest.Builder,key:CaptureRequest.Key<T>,value:T) {
        b.set(key,value)
        val physical=physicalId ?: return
        if(manager.getCameraCharacteristics(cameraId).availablePhysicalCameraRequestKeys?.contains(key)==true) b.setPhysicalCameraKey(key,value,physical)
    }
    private fun steadyRequested(forVideo:Boolean):Boolean {
        if(!::steadyVideo.isInitialized || !steadyVideo.isChecked || !s25Ultra || !(forVideo || !photoSelected)) return false
        val mode=if(forVideo) recordingMode else selectedVideo
        return mode!=null && !mode.highSpeed && mode.fps<=60 && mode.size.width<=3840 && mode.size.height<=2160 && chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.contains(2)==true
    }
    private fun stabilizationMode(forVideo:Boolean):Int = if(steadyRequested(forVideo)) 2 else if(processing[7].enabled && processing[7].supported && (if(forVideo) recordingMode?.highSpeed!=true else selectedVideo?.highSpeed!=true)) 1 else 0
    private fun applyStabilization(b:CaptureRequest.Builder,forVideo:Boolean) {
        val mode=stabilizationMode(forVideo)
        requestSetting(b,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,mode)
        val optical=if(mode!=1 && processing[6].enabled && processing[6].supported) 1 else 0
        requestSetting(b,CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,optical)
    }
    private fun apply(b:CaptureRequest.Builder,forVideo:Boolean=false) {
        val c=chars ?: return
        requestSetting(b,CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
        fun off(key:CaptureRequest.Key<Int>,available:CameraCharacteristics.Key<IntArray>,value:Int) {
            if(c[available]?.contains(value)==true) {
                requestSetting(b,key,value)
            }
        }
        processing.take(6).forEach { p -> off(p.key,p.available,if(p.enabled && p.supported) p.on else p.off) }
        applyStabilization(b,forVideo)
        if(customFlat.isChecked && customFlat.isEnabled && !(forVideo && recordingHlg)) {
            val curve=FloatArray(64)
            for(i in 0..31) { val x=i/31f; curve[2*i]=x; curve[2*i+1]=(kotlin.math.ln(1.0+15*x)/kotlin.math.ln(16.0)).toFloat() }
            requestSetting(b,CaptureRequest.TONEMAP_MODE,CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            requestSetting(b,CaptureRequest.TONEMAP_CURVE,TonemapCurve(curve,curve,curve))
        }
        val wb=wbModes[whiteBalance.selectedItemPosition.coerceAtLeast(0)]
        if(c[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]?.contains(wb)==true) requestSetting(b,CaptureRequest.CONTROL_AWB_MODE,wb)
        requestSetting(b,CaptureRequest.CONTROL_EFFECT_MODE,CaptureRequest.CONTROL_EFFECT_MODE_OFF)
        requestSetting(b,CaptureRequest.CONTROL_SCENE_MODE,CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        requestSetting(b,CaptureRequest.FLASH_MODE,if(torch.isChecked && torch.isEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        val capabilities=c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf()
        if(manual.isChecked && capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            requestSetting(b,CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF); requestSetting(b,CaptureRequest.SENSOR_SENSITIVITY,isoValue())
            val frame=1_000_000_000L/(recordingMode?.fps ?: 30); val time=if(forVideo) exposure().coerceAtMost(frame) else exposure()
            requestSetting(b,CaptureRequest.SENSOR_EXPOSURE_TIME,time); requestSetting(b,CaptureRequest.SENSOR_FRAME_DURATION,maxOf(time,frame))
            requestSetting(b,CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF)
            requestSetting(b,CaptureRequest.LENS_FOCUS_DISTANCE,(c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f)*focus.progress/100f)
        } else {
            requestSetting(b,CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)
            val af=c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
            val mode=if(forVideo || !photoSelected) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            if(af.contains(mode)) requestSetting(b,CaptureRequest.CONTROL_AF_MODE,mode)
        }
    }
    private fun repeatPreview(forVideo:Boolean=recording) {
        try { val b=device?.createCaptureRequest(if(forVideo || !photoSelected) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW,physicalId?.let { setOf(it) } ?: emptySet()) ?: return
            b.addTarget(preview ?: return); if(forVideo) b.addTarget(recorder?.surface ?: return); apply(b,forVideo)
            if(forVideo) {
                val fps=recordingMode?.fps ?: 30
                val ranges=chars!![CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES] ?: emptyArray()
                val range=if(recordingMode?.highSpeed==true) android.util.Range(fps,fps) else ranges.filter { it.contains(fps) }.minByOrNull { it.upper-it.lower }
                range?.let { requestSetting(b,CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,it) }
            }
            val s=session ?: return
            if(s is CameraConstrainedHighSpeedCaptureSession && forVideo) s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()),null,handler)
            else s.setRepeatingRequest(b.build(),object:CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session:CameraCaptureSession,request:CaptureRequest,result:TotalCaptureResult) {
                    val selected=physicalId?.let { result.physicalCameraResults[it] } ?: result
                    lastPreviewResult=selected
                    val eis=selected[CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE] ?: result[CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE]
                    val ois=selected[CaptureResult.LENS_OPTICAL_STABILIZATION_MODE]
                    val desired=stabilizationMode(forVideo)
                    val label=if(desired==2) { if(eis==2) "Steady active · optical ${if(ois==1) "ON" else "OFF"} · digital ON" else "Steady video requested · driver ${if(eis==0) "reports OFF" else "initializing"}" }
                        else if(desired==1) "Digital stabilization ${if(eis==1) "active" else "requested"} · optical OFF"
                        else "Optical ${if(ois==1) "ON" else "OFF"} · digital OFF" + if(steadyVideo.isChecked && (forVideo || !photoSelected)) " · Steady unavailable for this mode" else ""
                    if(label!=stabilizationReport) { stabilizationReport=label; runOnUiThread { stabilizationStatus.text=label; updateCaptureUi() } }
                }
            },handler)
        } catch(e:Exception) { message("Settings: ${e.message}") }
    }
    private fun takePhoto() {
        if(recording || capturePending) return
        try { val b=device?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE,physicalId?.let { setOf(it) } ?: emptySet()) ?: return
            b.addTarget(reader?.surface ?: return); apply(b); if(selectedPhoto?.maximum==true) b.set(CaptureRequest.SENSOR_PIXEL_MODE,CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            b.set(CaptureRequest.JPEG_ORIENTATION,(chars!![CameraCharacteristics.SENSOR_ORIENTATION] ?: 90))
            capturePending=true; controls(); message("Capturing…")
            session?.capture(b.build(),object:CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s:CameraCaptureSession,r:CaptureRequest,result:TotalCaptureResult) { if(!jpeg) { val selected=physicalId?.let { result.physicalCameraResults[it] } ?: result; val t=selected[CaptureResult.SENSOR_TIMESTAMP] ?: return; lastCaptureResult=selected; rawResults[t]=selected; saveRaw(t) } }
                override fun onCaptureFailed(s:CameraCaptureSession,r:CaptureRequest,f:CaptureFailure) { capturePending=false; controls(); message("Capture failed (${f.reason})") }
            },handler)
            val token=generation
            handler.postDelayed({ if(token==generation && capturePending) { rawImages.values.forEach { it.close() }; rawImages.clear(); rawResults.clear(); capturePending=false; controls(); message("Capture timed out. Try again.") } },10000)
        } catch(e:Exception) { capturePending=false; controls(); message("Photo: ${e.message}") }
    }
    private fun name(ext:String)="OpenFrame_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(Date())}.$ext"
    private fun save(ext:String,mime:String,video:Boolean=false,write:(java.io.OutputStream)->Unit) {
        val uri=contentResolver.insert(if(video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,name(ext)); put(MediaStore.MediaColumns.MIME_TYPE,mime); put(MediaStore.MediaColumns.RELATIVE_PATH,if(video) "Movies/OpenFrame" else "Pictures/OpenFrame"); put(MediaStore.MediaColumns.IS_PENDING,1) }) ?: error("Cannot create media file")
        try { contentResolver.openOutputStream(uri)!!.use(write); contentResolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null); if(video) lastVideoPath=uri.toString() else lastPhotoPath=uri.toString() }
        catch(e:Exception) { contentResolver.delete(uri,null,null); throw e }
    }
    private fun saveRaw(timestamp:Long) {
        val image=rawImages[timestamp] ?: return; val result=rawResults[timestamp] ?: return
        rawImages.remove(timestamp); rawResults.remove(timestamp)
        try { DngCreator(chars!!,result).use { creator -> creator.setOrientation(if(chars!![CameraCharacteristics.SENSOR_ORIENTATION]==270) 8 else if(chars!![CameraCharacteristics.SENSOR_ORIENTATION]==90) 6 else 1); save("dng","image/x-adobe-dng") { creator.writeImage(it,image) } }; message("RAW DNG saved · Pictures/OpenFrame") }
        catch(e:Exception) { message("DNG save: ${e.message}") }
        finally { image.close(); capturePending=false; controls() }
    }
    private fun saveJpeg(image:Image) { try { val buffer=image.planes[0].buffer; val bytes=ByteArray(buffer.remaining()); buffer.get(bytes); save("jpg","image/jpeg") { it.write(bytes) }; message("JPEG saved · Pictures/OpenFrame") } catch(e:Exception) { message("JPEG save: ${e.message}") } finally { image.close(); capturePending=false; controls() } }
    private fun startVideo() {
        if(device==null || capturePending || recorder!=null) return
        try {
            val sizes=chars!![CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(MediaRecorder::class.java)
            recordingAudio=audio.isChecked
            check(!recordingAudio || checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "Grant microphone permission or disable audio" }
            recordingMode=selectedVideo ?: error("No supported video mode")
            recordingHlg=hlg.isChecked
            check(!recordingMode!!.highSpeed || (!recordingHlg && !manual.isChecked)) { "High-speed requires HLG OFF and manual exposure OFF" }
            if(recordingHlg) {
                val profiles=chars!![CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES] ?: error("HLG unavailable on this camera")
                check(profiles.supportedProfiles.contains(DynamicRangeProfiles.HLG10)) { "HLG unavailable on this camera" }
                val constraints=profiles.getProfileCaptureRequestConstraints(DynamicRangeProfiles.HLG10)
                check(constraints.isEmpty() || constraints.contains(DynamicRangeProfiles.STANDARD)) { "This camera cannot combine HLG recording with SDR preview" }
            }
            val size=recordingMode!!.size
            check(sizes.contains(size)) { "Resolution not exposed for recording" }
            val codec=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info -> info.isEncoder && info.supportedTypes.any { it.equals(if(recordingHlg) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC,true) } && info.getCapabilitiesForType(if(recordingHlg) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.areSizeAndRateSupported(size.width,size.height,recordingMode!!.fps.toDouble()) } ?: error("No encoder supports this resolution / frame rate")
            val chosenBitrate=intArrayOf(20,30,50,80,100)[bitrate.selectedItemPosition.coerceAtLeast(0)]*1_000_000
            check(codec.getCapabilitiesForType(if(recordingHlg) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.bitrateRange.contains(chosenBitrate)) { "Bitrate unsupported by encoder" }
            videoFile=File(cacheDir,name("mp4")); recorder=MediaRecorder(this).apply { setVideoSource(MediaRecorder.VideoSource.SURFACE); if(recordingAudio) setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setVideoEncoder(if(recordingHlg) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264); if(recordingHlg) setVideoEncodingProfileLevel(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,if(size.width>=7680) MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6 else if(size.width>=3840) MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51 else MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4); if(recordingAudio) { setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioChannels(2); setAudioSamplingRate(48000); setAudioEncodingBitRate(192000) }; setVideoSize(size.width,size.height); setVideoFrameRate(recordingMode!!.fps); setVideoEncodingBitRate(chosenBitrate); setOrientationHint(chars!![CameraCharacteristics.SENSOR_ORIENTATION] ?: 90); setOutputFile(videoFile!!.absolutePath); prepare() }
            runOnUiThread { photo.isEnabled=false; video.isEnabled=false; lens.isEnabled=false }; createSession(true)
        } catch(e:Exception) { abortVideo(); message("Video: ${e.message}") }
    }
    private fun verifyHlg(file:File) {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
            check(format.getString(MediaFormat.KEY_MIME)==MediaFormat.MIMETYPE_VIDEO_HEVC) { "HLG encoder did not produce HEVC" }
            check(format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) && format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)==MediaFormat.COLOR_TRANSFER_HLG) { "Encoder did not preserve HLG transfer metadata; clip rejected" }
            check(format.containsKey(MediaFormat.KEY_COLOR_STANDARD) && format.getInteger(MediaFormat.KEY_COLOR_STANDARD)==MediaFormat.COLOR_STANDARD_BT2020) { "Encoder did not preserve BT.2020 metadata; clip rejected" }
        } finally { extractor.release() }
    }
    private fun abortVideo() { recorder?.release(); recorder=null; videoFile?.delete(); videoFile=null; recording=false; runOnUiThread { video.isEnabled=true }; controls() }
    private fun stopVideo(restart:Boolean=true) {
        if(!recording) return
        try { session?.stopRepeating(); session?.close(); session=null; recorder!!.stop(); val file=videoFile!!; if(recordingHlg) verifyHlg(file); save("mp4","video/mp4",true) { out -> file.inputStream().use { it.copyTo(out) } }; message("Video saved · ${if(recordingHlg) "10-bit HLG" else "SDR"} · Movies/OpenFrame") } catch(e:Exception) { message("Video not saved: ${e.message}") }
        finally { abortVideo(); if(restart && active) { val map=chars!![CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!; previewSize=map.getOutputSizes(SurfaceTexture::class.java).filter { it.width<=1920 }.minByOrNull { kotlin.math.abs(it.width.toFloat()/it.height-16f/9f) } ?: map.getOutputSizes(SurfaceTexture::class.java).first(); createSession(false) } }
    }
}
