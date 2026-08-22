# SafeView Privacy Notice

This notice describes what SafeView for Android does on the device. It covers
the three protection features and the data each one touches. SafeView has no
backend server; nothing described below is transmitted off the device.

## Background protection (VPN service)

SafeView can run a local `VpnService` that acts as a DNS filter.

- It inspects only outgoing DNS queries (UDP port 53) to check the requested
  domain against the blocked-domain list stored in the app's local settings.
- It does not decrypt HTTPS traffic and cannot see URLs, page content, or
  images inside other apps.
- It counts how many queries were blocked, for display in Settings. That
  count is stored locally and is not transmitted anywhere.
- Apps using encrypted DNS, cached content, direct IP connections, or their
  own networking may not be affected by this filter.

## Screen AI protection (optional, parent-consented)

Screen AI uses Android's `MediaProjection` API to sample visible screen
frames and classify them on-device with a local TensorFlow Lite model.

- Frames are held in memory only for the duration of classification and are
  discarded immediately after. SafeView does not save, log, or upload
  captured frames.
- Classification happens entirely on-device; frames and classification
  results never leave the device.
- A foreground notification is shown by Android any time capture is active,
  and a persistent visual cover may be shown over content pending or
  following classification.
- If the parent enables **Protected apps** with specific packages selected,
  SafeView uses Android Usage Access only to identify which app is currently
  in the foreground, so it can decide whether to apply Screen AI to it.
  SafeView does not read messages, contacts, URLs, browsing content, or any
  other app data through this permission.
- Screen capture can be turned off at any time from Settings. If Android
  stops the capture session (for example, because the user revokes
  permission or switches to a protected system window), SafeView marks the
  service paused and notifies the parent rather than silently continuing.

## SafeView browser

- The built-in browser can classify images using the same on-device model,
  limited to an allowlist of trusted image-hosting origins (for example,
  Pinterest, Google Images, Bing Images).
- Images sent to the classifier are processed in memory and are not saved or
  uploaded.
- Browsing history, bookmarks, and downloads recorded by the browser are
  stored locally in the app's private storage and are not transmitted
  anywhere.

## What SafeView does not do

- SafeView does not send captured frames, classified images, browsing
  history, or app-usage data to any server. There is no backend to send it
  to.
- SafeView does not guarantee that every explicit image or video frame in
  every third-party app will be detected or blocked. See `README.md` for
  the specific limitations of each protection layer.

## Model

The bundled classifier (`nsfw_mobilenet_v2.tflite`) runs entirely on-device
using TensorFlow Lite. See `MODEL_ATTRIBUTION.txt` for its source and
license.
