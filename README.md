# OpenFrame

Camera2 camera for Android 13+, developed against a Galaxy S25 Ultra.

## Capture

- Live camera preview and public / physical lens selection.
- Single-frame RAW_SENSOR photos saved as DNG in Pictures/OpenFrame.
- Explicit JPEG fallback only when a selected camera has no RAW output.
- Manual ISO, exposure and focus, plus white balance presets.
- Silent 1080p30 video: SDR H.264 at 20 Mbps, or HLG10 HEVC Main10 at 30 Mbps saved in Movies/OpenFrame. Video exposure is limited to the frame interval.
- The original Camera2 capability report remains available through REPORT.

Noise reduction, edge enhancement, hot-pixel correction, shading correction, lens distortion correction, chromatic aberration correction and EIS default OFF when advertised by the selected camera. OIS defaults ON on supported lenses and can be switched OFF. Effects, scene modes and flash are disabled. The app does not use Samsung camera extensions, beauty filters, HDR merging or app-side image enhancement.

This is not a bypass of Samsung's camera HAL/ISP. RAW output still depends on the vendor driver. Preview, JPEG and H.264 video retain the platform image pipeline, including demosaicing, color processing and video encoding. No claim of completely unprocessed video is made. Enable 10-bit HLG HDR video to record HLG10 with HEVC Main10. Unsupported cameras or incompatible preview combinations are rejected without silently recording SDR. Saved HLG clips are checked for BT.2020 and HLG metadata. LOG recording is not implemented.

The hardware's marketing resolution does not guarantee that a third-party camera can obtain that resolution. RAW uses the largest advertised RAW_SENSOR stream, approximately 12 MP on the tested S25. Physical lens streams must be accepted by the phone's camera session.

## Build and device test

Use local Android SDK and JDK 17 with the Gradle 8.9 wrapper.

    gradlew.bat clean build assembleDebugAndroidTest

The CameraSmoke instrumentation runner tests a real preview session, RAW save, recorder stop/finalization, manual RAW capture and lens switching. It creates test photos and a short silent video on the connected phone.

    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    adb shell am instrument -w com.aashish.s25cameraprobe.test/com.aashish.s25cameraprobe.CameraSmoke

Grant Camera permission before running the integration check. The test APK is separate from the camera app.

## Verified on SM-S938B (Android 16)

Clean build and lint passed. Device integration checks passed for preview, DNG on all seven camera choices, manual RAW capture, and MP4 capture/finalization. RAW capture metadata reported noise reduction OFF and edge enhancement OFF for every tested lens. These metadata checks do not establish that the vendor applies no other processing.


HLG recording verified on the S25 Ultra: a real clip reported BT.2020 / HLG metadata, and its HEVC configuration independently confirmed Main10 with 10-bit luma and chroma. The HLG preview remains SDR; judge HDR brightness in an HDR-capable player.
## Expanded controls (0.2 development)

Photo mode lists all advertised RAW and JPEG sizes, including high-resolution output sizes and the maximum-resolution stream map when the ULTRA_HIGH_RESOLUTION_SENSOR capability is exposed. A maximum-resolution photo uses SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION with a matching output configuration. No upscaled 200/50 MP modes are advertised.

Video selection lists advertised recording resolutions at eligible 24/30/60 fps and fixed-rate constrained high-speed modes on public cameras. High-speed recording requires SDR and automatic exposure. Codec size/rate and bitrate support are checked before recording. The selected mode may still be rejected by the camera session; unsupported modes are not silently substituted. HLG uses HEVC Main10, SDR uses H.264. Bitrates are 20/30/50/80/100 Mbps when the encoder accepts them.

Independent switches control noise reduction, sharpening, hot pixels, shading, distortion, chromatic aberration, OIS and EIS. A switch is disabled when that lens does not advertise both requested OFF and ON modes. RAW data may not respond to image-processing controls in the same way as preview/JPEG/video.

Optional microphone audio requests RECORD_AUDIO and records AAC stereo at 48 kHz / 192 kbps. Torch is available on flash-equipped cameras.

The custom flat SDR curve is a Camera2 contrast curve, y=ln(1+15x)/ln(16). It is not Samsung LOG, calibrated scene-linear LOG, or an HDR mode. It is disabled when HLG is selected. Native LOG is not exposed through the standard API. HDR10/HDR10+ capability is shown but this recorder does not enable these modes. The S25 probe advertised HLG10 and STANDARD only.

The expanded controls were subsequently verified on the connected S25; see the checks below.

## Expanded controls verified on the connected S25

Fixed a startup race between initial lens selection and camera mode discovery. On-device tests passed RAW and JPEG capture, manual RAW, all seven lens choices, HLG recording, and driver metadata for NR/sharpening both OFF and ON. Camera 0 exposes RAW and JPEG up to 4080x3060 (12.5 MP), no 8K video, no HDR10/HDR10+, and no native LOG through standard Camera2. 4K HLG and high-speed recording subsequently passed the on-device tests below.


## Friendly UI and recording modes verified

Capture buttons stay outside the settings scroll area. Named S25 lenses and collapsible photo/video/manual/processing/help groups make supported controls easier to find. The help group explains original HLG capture versus Rec.709 export and can open Samsung Camera for its native full-resolution/LOG features; it does not select these private modes automatically.

On-device recording tests passed 3840x2160 HLG at 29.5 fps, 1920x1080 HLG at 59.5 fps, and SDR high-speed 1920x1080 at 120.0/238.4 fps, measured from encoded sample timestamps. RAW/JPEG, all lens choices and processing OFF/ON checks passed with this UI. HLG is not LOG and no automatic Rec.709 export is implemented.


## Camera UI, OIS and preview looks

The viewfinder now fills most of the capture screen. PHOTO/VIDEO tabs, a circular shutter, quick 0.6x/1x/3x/5x lens buttons and an OIS state button remain visible. Controls opens collapsible settings; Controls > Preview looks > Choose a free look selects a bundled creative LUT, with an intensity slider and Original reset. An external Rec.709 creative 3D .cube LUT can be imported through Android's document picker.

On the tested SM-S938B, automatic logical camera 0 advertises OIS OFF only, whereas physical main/telephoto lenses advertise ON/OFF. The app defaults to physical main 1x, preserves the requested OIS state across unsupported lenses, constructs requests with the selected physical camera ID and applies supported settings to both the logical request and physical override. OIS controls do not guarantee that vendor firmware uses every requested processing setting.

The GPU LUT effect changes only the SDR TextureView viewfinder. It does not modify the recorder surface, saved JPEG or DNG. HLG files remain original Rec.2100 HLG. This is a creative preview, not a calibrated HLG/LOG grading monitor. Use Rec.709 creative LUTs here; do not apply HLG-to-Rec.709 or Samsung LOG conversion LUTs directly to this SDR viewfinder.

Downloaded assets are in C:\Users\Administrator\Downloads\OpenFrame-Free-LUTs. Thirteen creative looks are available in this personal installation: Hollywood Gold and FreeVisuals' 12 Teal/Orange looks (Classic, Cinematic, Subtle, Heavy, Skin Safe, Vlog, Night, High Contrast, Faded, Travel, Action and Blockbuster). Their publisher permits free personal/commercial use, but prohibits redistributing or reselling the files as a standalone product. Review these third-party licenses before distributing an app containing them. The HLG BT.2020-to-Rec.709 conversion LUT is downloaded separately for editing and is not bundled into the SDR preview choices.

Sources:
- https://www.freevisuals.net/luts/free-hollywood-gold-warm-lut
- https://www.freevisuals.net/luts/free-12-teal-orange-luts-pack
- https://nopixels.net/articles/hlg-to-rec709/

Samsung's sign-in-gated pack was skipped at the user's request. No paid LUTs were purchased.
Final on-device check passed on SM-S938B: physical main OIS ON and OFF confirmed in capture results; GPU LUT changed a frozen viewfinder frame; manual AE OFF, NR/edge OFF and ON, RAW/JPEG and all camera choices passed. HLG 4K30 measured 29.6 fps; HLG 1080p60 measured 59.1 fps; SDR 1080p120/240 measured 120.0/235.0 fps. Clean build passed, followed by build/lint after the physical manual-setting correction. The updated app is installed. The separate integration-test APK was removed after verification.

User-provided 172.cube, 218.cube and 76.cube were added unchanged to this personal installation as IWLTBAP-172-LOG, IWLTBAP-218-LOG and IWLTBAP-76-LOG. Headers identify IWLTBAP 6540, 9430 and 7140 (LOG), respectively. These LOG-input looks are experimental on the SDR preview, not calibrated Samsung LOG or HLG conversions. Source headers prohibit resale, sharing and redistribution; do not publish an app containing these personal assets without appropriate rights.

## S25 Ultra stabilization profile

Samsung SM-S938 variants get a Steady video switch under Controls > Processing and stabilization, enabled by default. PHOTO keeps optical ON and digital OFF. VIDEO preview and supported normal recording modes request PREVIEW_STABILIZATION (2), with optical ON requested on physical lenses; Samsung's driver decides the applied optical mode. The live status reports applied optical/digital metadata, including an OFF report, rather than treating a request as proof of activation. Basic EIS (1) requests optical OFF to avoid conflicting independent stabilizers. Steady video can crop/warp the image and is separate from noise reduction and sharpening. High-speed modes and modes above 4K/60 use the optical path rather than requesting managed stabilization.

Stabilization is included in session parameters before configuration. Video uses the RECORD request template and continuous-video autofocus when available, including VIDEO-mode preview. The exposed OIS API only offers OFF/ON, not actuator travel, gain or strength. No hidden Samsung vendor values are guessed. Native Samsung correction strength is not claimed, and greater visible module movement is not a quality measurement.

Device tests on the physical main 1x lens confirmed mode 2 with OIS=1 at 1080p30 HLG, 4K30 HLG and 1080p60 HLG. The automatic logical camera confirmed mode 2 with OIS=0, so physical main remains the preferred lens. RAW/JPEG, optical ON/OFF, manual exposure, processing OFF/ON, all camera choices and 120/240 fps recording passed. Build/lint passed and the app was installed. After reconnecting, the final VIDEO/PHOTO button test passed: the VIDEO viewfinder reported managed mode 2, and switching back to PHOTO restored mode 0. The complete capture suite passed on the installed final build.

Android stabilization contract: https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_VIDEO_STABILIZATION_MODE

## Floating camera interface

The user-provided reference inspired a full-screen live viewfinder, floating circular shutter, camera-switch button, compact top controls and a translucent rounded bottom panel. Quick lenses and PHOTO/VIDEO selection live in the panel; LOOKS, MANUAL, FORMAT and CONTROLS open their relevant settings in an overlay drawer. The top ellipsis opens or closes the drawer. The left info button opens the real capability report. No unsupported depth/spatial/timelapse features are presented as working camera modes.

Quick lens switching now routes directly to camera selection, so it works while the complete lens picker is hidden. LUT rendering still affects only the viewfinder; overlays and captured media retain their original rendering. Build/lint passed and the redesigned APK was installed. After reconnection, visual inspection and the complete on-phone capture suite passed with the floating layout: RAW/JPEG, all lenses, optical ON/OFF, LUT preview, manual exposure, PHOTO/VIDEO transitions, managed stabilization, 4K HLG and high-speed recording. The temporary test helper was removed.
