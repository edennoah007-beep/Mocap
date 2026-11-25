# VStage — VTuber-style avatar recorder for Android

Upload a 3D model, it poses itself like a VTuber avatar and follows your face with the front camera.
Pick the mouth shape, use a green screen, and record an **MP4** to your gallery.

**Download the APK:** https://github.com/OWNER/REPO/releases/latest/download/VStage.apk

## Features
- **Models:** `.vrm` (VRoid), `.glb`, `.gltf`, `.fbx` (Mixamo). Built-in demo avatar. Remembers your last model.
- **Auto pose:** T-pose arms come down automatically; idle breathing, swaying and blinking.
  - *Follow me*: head, body lean, blinks and mouth copied from the front camera (MediaPipe).
  - *Auto*: moves on its own, no camera needed.
  - Arm poses: Relaxed, Wave, Cheer, Hands on hips, Think, or *Copy my arms* (camera).
  - Expressions: Neutral, Happy, Angry, Sad, Surprised, Relaxed.
- **Mouth:** copy your mouth (camera), move when you talk (mic), or hold Closed / A / I / U / E / O / Smile.
- **Background:** green screen (#00FF00), blue screen, black, white, custom color, your camera, or a picture.
- **Recording:** MP4 (H.264 + AAC mic audio), up to 1080p, 30/60 fps, countdown, all UI hidden while recording.
  Stop by double-tapping the screen, Volume Down, or the notification. Saved to **Movies/VStage**.

## Notes
- Needs internet the first time (3D engine + tracking models download, then are cached).
- Android 10+. Install: open the APK, allow "Install unknown apps".
- Mouth shapes need a model with blendshapes / shape keys (all VRoid VRMs have them; ARKit "perfect sync" models are supported).

## Build
GitHub Actions builds `VStage.apk` on every push to `main` and publishes it as a Release.
Locally: `./gradlew assembleDebug`.
