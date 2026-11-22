# Stand-In Gamer (Android)

Your 3D character floats over any Android game while the phone records the **whole screen to MP4**
(H.264 video + AAC mic audio, saved in **Movies/Stand-In**). Live motion capture from the front camera
keeps running the whole time — body, hands, fingers, face and mouth.

The app is the full Stand-In web app (upload → auto-rig → capture → go live) running inside the
phone, plus a native screen recorder and a floating character window.

## Get the APK — easiest way (free, no install, about 5 minutes)

1. Create a free GitHub account and a new **empty repository** (private is fine).
2. Upload everything in this folder (keep the folders, including the hidden `.github` folder).
   Easiest: on the repo page choose **uploading an existing file** and drag the whole folder in,
   or use GitHub Desktop.
3. Open the **Actions** tab. The **Build APK** job starts by itself (or press **Run workflow**).
4. When it turns green, open the run and download **StandInGamer-apk** under *Artifacts*.
   Unzip it — inside is `app-debug.apk`.
5. Copy it to your phone, open it and allow **Install unknown apps** when Android asks.

## Or build it on your computer

Open this folder in **Android Studio** (Hedgehog or newer) → wait for Gradle sync →
**Build → Build App Bundle(s) / APK(s) → Build APK(s)**. Or from a terminal with the Android SDK
installed: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Using it

1. Open **Stand-In Gamer**, allow camera and microphone.
2. **Model** → upload your character (.glb / .vrm / .fbx / .obj) → **Rig** → **Capture** → start the camera.
3. Go to **Go live** → *Gamer edition* → pick Landscape/Portrait, quality and size → **Start gamer mode**.
4. Allow **Display over other apps** the first time (Android opens the setting; switch it on, come back, press Start again).
5. When Android asks what to record, choose **Entire screen** (not "a single app"),
   otherwise your character won't be in the video.
6. Open your game and play. Drag **✥ Move** to place your character, **⤢** to resize.
   The buttons fade out after 3 seconds so they stay out of your video; tap the character to show them.
7. Tap **■ Stop** (or **Stop & save** in the notification). The app comes back and you can
   **Share last MP4** or **Play it**. Files are in your gallery under *Movies/Stand-In*.

## Good to know

- Needs Android 10 or newer and an internet connection (the 3D engine and tracking models load online).
- Game sound is **not** in the recording yet — only your microphone. (Android's game-audio capture
  needs a different recorder; ask for it as the next upgrade.)
- Leave **Voice lip-sync** off in gamer mode: the recorder already uses the microphone and Android
  may give the page silence. Face tracking still moves the mouth.
- Running a game, the camera tracker and the recorder together is heavy. If it stutters, use the
  **Lite** body model and turn off **Hands & fingers** on the Capture step.
- Some games block screen recording (they show black). That's the game's choice and can't be bypassed.
- The APK is signed with a debug key — fine for your own phone, not for the Play Store.

## The website version

`web-stand-in-mocap.html` is the same app for your computer: download it and open it in Chrome or Edge.
It does live mocap, auto-rigging, CG replacement, recording (MP4 in Chrome 126+) and live streaming
(Twitch direct, YouTube/Discord through OBS). To capture an Android game on a computer, mirror the
phone with the free tool **scrcpy** and pick its window under "Capture game screen".
