# Smart Cast

Phone-to-TV app by Vikaranet. One APK that works as the **sender** on Android phones and the **receiver** on Android TV / Google TV / TV boxes / tablets, and also works with **any smart TV that has a web browser or DLNA** — no app needed on the TV.

## What it does

| Feature | How it reaches the TV | Works on |
|---|---|---|
| **Screen mirroring** (live, fitted to 16:9) | TV web browser, or Smart Cast receiver app | Samsung (Tizen), LG (webOS), Hisense (Vidaa), Philips, Sony, any Android TV / Google TV / Fire TV / TV box |
| **Cast photos, videos, music** | TV's own player via DLNA (full sound, up to 4K) or the TV browser | Samsung, LG, Sony, Philips, Panasonic, Hisense, TCL, most Android TVs |

### Fitting the picture to the TV
Phones are tall (≈20:9), TVs are wide (16:9). Every mirrored frame is rebuilt as a true 1280×720 or 1920×1080 picture:

- **Smart fill** (default) – whole screen visible, empty space filled with a blurred copy. No black bars, nothing cut off.
- **Fit** – whole screen visible with black bars.
- **Fill (crop)** – fills the TV edge to edge, edges cropped.

Turn the phone sideways for games and videos and they fill the TV almost completely.

## Using it

1. Phone and TV on the same Wi-Fi (or join the TV to the phone's hotspot).
2. Open Smart Cast on the phone. It shows an address like `192.168.1.5:8080`.
3. On the TV, either:
   - open the TV's **web browser** and type that address, then press OK for full screen; or
   - open **Smart Cast** on an Android/Google TV — it finds the phone by itself.
4. On the phone tap **Start mirroring**, or **Choose photo, video or song**.
   For best quality on videos, tap **Play with the TV's own player (DLNA)**.

## Building the APK (no computer setup needed)

1. Create a new repository on GitHub (e.g. `smart-cast`) and upload all files in this folder (keep the folder structure, including `.github`).
2. Open the repository's **Actions** tab. The *Build Smart Cast APK* workflow runs automatically (or press **Run workflow**).
3. When it finishes (~5 min), open the run and download **SmartCast-apk** → `app-debug.apk`.
4. Install it on your phone (allow "Install unknown apps"). For Android TV, copy the same APK with a USB stick or "Send Files to TV" and install it.

With Android Studio instead: open the folder and press Run.

## Project layout

```
app/src/main/java/com/vikaranet/smartcast/
  MainActivity.kt      phone screen (sender)
  TvActivity.kt        TV / tablet receiver, auto-discovers phones
  CastService.kt       background service: server, mirroring, network announce
  ScreenCapturer.kt    screen capture + 16:9 fitting (Smart / Fit / Fill)
  Ui.kt                small UI helpers (no third-party libraries)
  core/HttpServer.kt   tiny web server: receiver page, MJPEG stream, media with seeking
  core/Dlna.kt         DLNA/UPnP discovery and "Play To" for smart TVs
  core/FrameHub.kt     latest-frame buffer shared with all TVs
  core/CastState.kt    what is being cast, fit mode, pause
app/src/main/assets/receiver.html   full-screen TV player (ES5, runs on older TV browsers)
```

## Known limits (v1)

- **Sound while mirroring** stays on the phone (use Bluetooth speakers or cast the media file instead, which plays sound on the TV).
- Mirroring is a JPEG stream: smooth for apps, photos, slides and most video; fast action games may show some lag. A future H.264/WebRTC mode can lower latency on newer TVs.
- **Roku and Apple TV** have no web browser and don't accept DLNA "Play To"; reaching them needs their own channel/app (Roku) or AirPlay. A Roku channel is a possible later add-on.
- Anyone on the same Wi-Fi can open the address while you're casting. A PIN/approve-TV step is a planned next feature.

## Publishing to Google Play

Needs a signed release bundle: create an upload key, add it as GitHub secrets, and build `bundleRelease`. Play requires a declaration for the media-projection foreground service (screen sharing to a TV is an accepted use).
