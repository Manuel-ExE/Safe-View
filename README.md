# SafeView for Android v1.4.4

Private, on-device media filter for Android.

## Changes in 1.2.3 (audit residuals)

| Item | Change |
|------|--------|
| Global JavascriptInterface exposure | **Detach on navigation**; **attach only after** `onPageFinished` when origin is on the AI allowlist (`removeJavascriptInterface` / `addJavascriptInterface`) |
| Empty fetch-host policy | **Default CDN allowlist** for Pinterest / Google / Bing image hosts |
| User-Agent | `SafeView/1.2.3` |

Prior 1.2.2 controls retained: origin allowlist, page generation + nonce callbacks, UUID request IDs, streaming body cap, bounded decode, private-host rejection, manual redirects.

## AI policy (defaults)

**Page origins (AI enabled):**  
`www.pinterest.com`, `pinterest.com`, `www.google.com`, `google.com`, `www.bing.com`, `bing.com`

**Fetch hosts (native image download):**  
Pinterest `*.pinimg.com`, Google `encrypted-tbn*.gstatic.com` / `lh3.googleusercontent.com`, Bing `tse*.mm.bing.net` / `th.bing.com`

Heuristics still run on all HTTPS pages. Visual AI only when origin is allowlisted **and** the bridge is attached **and** a TFLite model is present.

```kotlin
bridge.originAllowlist = setOf("www.pinterest.com")
bridge.fetchHostAllowlist = setOf("i.pinimg.com", "s.pinimg.com")
```

## Background protection mode

Version 1.3 adds an optional Android `VpnService` that runs with a persistent notification and blocks a built-in list of adult domains through a local DNS filter. Enable it from **Settings → Background protection** and approve Android's VPN permission dialog.

This mode does not decrypt HTTPS traffic or inspect raw pixels from other applications. It can block configured domains and complements the stronger image filtering inside the SafeView browser. It cannot guarantee that every nude image in every third-party app will be blocked; apps using encrypted DNS, cached content, direct IPs, or their own networking may bypass DNS filtering.

## Build

Open `android-skeleton` in **Android Studio**, or run `./gradlew assembleDebug` from that directory. A complete Gradle wrapper, including `gradle-wrapper.jar`, is included. The GitHub Actions workflow also builds with Gradle 8.2.

## Enable visual AI

The release package includes `nsfw_mobilenet_v2.tflite` in `app/src/main/assets/`. Rebuild after changing the model.

## License
MIT

## Optional Screen AI protection

Version 1.4.4 includes an optional, parent-consented Screen AI mode. If `nsfw_mobilenet_v2.tflite` is present in `android-skeleton/app/src/main/assets/`, open SafeView Settings and enable **Screen AI protection**. SafeView requests Android screen-capture permission, runs a foreground service with a visible notification, samples the visible display, and runs inference locally with TensorFlow Lite. Frames are discarded after processing and are not uploaded or saved. **Calm warning mode** shows supportive intervention text and a Go to safety action; disabling it keeps strict blocking.

Android may exclude protected windows, another app may stop capture, and brief video frames may be missed. Screen AI therefore provides best-effort visible-screen analysis, not a guarantee for every image or video in every application. Background VPN protection continues to block configured adult domains, and the SafeView browser remains the strongest image-level filter.

Screen AI requires both Android screen-capture approval and the **Display over other apps** permission so SafeView can show a blocking cover. If capture is revoked or the service stops, SafeView removes the cover, marks protection paused, and sends a parent-facing notification. The parent can disable it from Settings at any time.

### Protected-app rules

Version 1.4.4 includes a parent-facing **Protected apps** selector. With no selected packages, Screen AI analyzes all visible apps whenever capture is available. If the parent selects packages, SafeView uses Android Usage Access only to identify the current foreground package and applies Screen AI to the selected apps. If Usage Access is not granted, SafeView keeps the safer all-app behavior rather than silently weakening protection. The feature does not read messages, URLs, contacts, or app content.
