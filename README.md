# S25 Ultra Camera Probe

A tiny Android Camera2 capability scanner intended for the Samsung Galaxy S25 Ultra.

This is **not the final camera app yet**. It is the hardware/API probe we need before building:

- RAW DNG photo capture
- 10-bit clean/minimal-processing video
- 10-bit LOG/flat video
- manual ISO / shutter / focus / white balance
- selectable OIS / EIS
- minimal noise reduction and edge enhancement

## What the probe reports

For every Camera2 camera ID it checks:

- logical + physical camera IDs
- lens facing, focal lengths, apertures
- Camera2 hardware level
- `RAW_SENSOR` capability and RAW resolutions
- manual sensor capability
- manual post-processing capability
- 10-bit dynamic-range capability
- HLG10 / HDR10 / HDR10+ profiles
- ISO and exposure-time ranges
- manual-focus availability
- AE FPS ranges
- noise-reduction modes
- edge/sharpening modes
- tone-map modes
- OIS/EIS modes
- MediaRecorder output sizes
- approximate normal-stream max FPS
- constrained high-speed modes

## Run it

1. Open the project folder in Android Studio.
2. Allow Gradle to sync. If Android Studio asks for a Gradle distribution, use Gradle 8.9 (the project uses Android Gradle Plugin 8.7.3).
3. Connect the S25 Ultra with USB debugging enabled.
4. Select the phone as the run target.
5. Press **Run**.
6. Grant Camera permission.
7. Tap **SCAN**.
8. Tap **COPY** or **SHARE**.
9. Send the complete report back into ChatGPT.

## Why this comes first

Samsung decides which low-level camera features are exposed to third-party apps through Camera2. We should not guess that 0.6×, 1×, 3× and 5× all expose the same RAW or 10-bit capabilities.

The report tells us exactly what the phone exposes so the actual recorder can be designed around real capabilities.

## Important

“Minimal processing” does not mean electrically bypassing the camera sensor ISP/HAL. A normal Android application still uses Android + Samsung's camera hardware stack. The final app will disable or minimize processing controls wherever Camera2 exposes that control, and use RAW sensor capture for photos wherever RAW is exposed.
