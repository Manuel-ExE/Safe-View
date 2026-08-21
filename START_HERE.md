# SafeView v1.4.4 — Start Here

Open the `android-skeleton` folder in **Android Studio** and build the debug APK with **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

A full Gradle wrapper JAR is **not** shipped. Android Studio will download the distribution and generate the wrapper on first open. See `android-skeleton/GENERATE_WRAPPER.md` if you need a command-line wrapper.

After installing the APK:

1. Open SafeView.
2. Open **Settings**.
3. Turn on **Background protection** and approve Android's VPN permission dialog.
4. Keep the SafeView protection notification visible while protection is active.
5. Tap the Background protection status text to edit the **blocked domain list**.
6. If the TFLite model is installed, turn on **Screen AI protection**.
7. Read the privacy notice and approve screen capture.
8. If Android asks, allow SafeView to display over other apps.
9. Approve Android's screen-capture dialog.
10. Open **Protected apps** to choose whether Screen AI monitors everything or only selected apps.

## Screen AI notes

Screen AI uses Android MediaProjection to sample visible screen frames and run the existing TFLite classifier locally. Frames are not saved or uploaded. A foreground notification remains visible while capture is active. SafeView can show a neutral cover when multiple sampled frames exceed the configured classifier threshold. In Settings, **Calm warning mode** shows a supportive message and a **Go to safety** button; turn it off for strict blocking. If Android stops capture, SafeView removes the cover, marks Screen AI as paused, and sends a parent notification.

In Settings, choose **Protected apps**. The default is **Protect all apps**. Turn that off and select specific packages to limit analysis. Applying selected-app rules requires Android Usage Access; if that permission is not granted, SafeView keeps the safer all-app analysis behavior. SafeView uses package activity only to apply the rule and does not read messages, contacts, URLs, or app content.

Screen AI is not a guarantee that every nude, explicit, or revealing image or video frame will be blocked. Protected windows may not be capturable, brief frames may be missed, and Android or another app may stop the capture session. The background VPN blocks configured adult domains but does not inspect image pixels. For strongest image filtering, use the SafeView browser.

## Model

The optional TFLite model is not included. Add `nsfw_mobilenet_v2.tflite` to `android-skeleton/app/src/main/assets/` before building if you want visual classification. Without it, Screen AI stays unavailable and the browser uses heuristics only.

## What's new in 1.4.4

- Foreground-app lookups are cached to reduce repeated Usage Access work.
- Screen AI samples directly into a 224×224 bitmap to reduce memory pressure.
- Blocked-domain entries are validated as hostnames and empty-list saves require confirmation.

## Earlier 1.4.3 changes

- More reliable foreground-app detection for protected-app rules.
- Screen AI respects live threshold settings.
- Lower memory use on Screen AI (720 px capture + early downsample).
- Clearer Protected-apps UI with an explicit "Protect all apps" switch.
- Editable blocked-domain list for the background VPN.

See `CHANGELOG-1.4.3.md` for earlier details. The v1.4.4 audit fixes are summarized in the project README.
