# SafeView for Android v1.2.3

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

## Build

Open `android-skeleton` in **Android Studio** (recommended).  
A full Gradle wrapper JAR is **not** shipped — see `GENERATE_WRAPPER.md`.

## Enable visual AI

Place `nsfw_mobilenet_v2.tflite` in `app/src/main/assets/` and rebuild.

## License
MIT
