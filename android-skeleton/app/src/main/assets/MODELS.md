# AI model for SafeView Android

The browsing pipeline can route page images into native TFLite via `SafeViewBridge`:

1. Content script captures a 224×224 JPEG data URL when the canvas is not tainted.
2. For cross-origin (tainted) images, native fetches the `https://` src only (no HTTP, size-capped).
3. `NsfwClassifier.classify` scores the bitmap on a background thread.
4. If blocked, the page is told via `SafeViewOnClassifyResult` to apply blur.

## Install a model

1. Obtain/convert an NSFW MobileNetV2 `.tflite`:
   - Input: 224×224 RGB float32, normalized 0–1
   - Output: 5 floats — Drawing, Hentai, Neutral, Porn, Sexy (NSFWJS order)
2. Place as: `app/src/main/assets/nsfw_mobilenet_v2.tflite`
3. Rebuild. Settings → AI switch enables when the model loads.

Without the file, heuristics still run; AI stays off.
