# SafeView 1.4.3 — Changes

## Fixed

### Screen AI – protected apps & thresholds
- Foreground package detection now uses `UsageEvents` (MOVE_TO_FOREGROUND / ACTIVITY_RESUMED) instead of the unreliable `queryUsageStats` max-by-lastTimeUsed approach.
- Screen AI classification now uses the **live** parent-configured explicit / revealing thresholds from Settings (previously hard-coded defaults).
- Missing Usage Access or unknown foreground package still falls back to “analyze everything” (safer).

### Screen AI – performance & memory
- Capture resolution capped at 720 px (was 1280).
- Frames are downsampled to 224×224 *before* classification so the full-resolution bitmap is discarded immediately.
- Overlay always consumes touches (strict mode no longer uses FLAG_NOT_TOUCHABLE).

## Improved

### AppRulesActivity UX
- Clear **“Protect all apps”** master switch. When on, the per-app list is hidden and an empty set is stored (documented safer default).
- Explicit Usage Access status text (granted / needed) that updates on resume.
- “Open Usage Access settings” button always visible.
- SafeView itself is excluded from the selectable app list.

### Configurable blocked domains
- Domain blocklist moved from a hard-coded constant into `SettingsPrefs`.
- New `BlockedDomainsActivity` (edit list, one domain per line, reset to defaults).
- Tap the Background protection status text in Settings to open the domain editor.
- VPN service reads the live preference on every DNS query.

## Build
- versionCode 11, versionName 1.4.3

## How to build
See `START_HERE.md` and `android-skeleton/GENERATE_WRAPPER.md`.
Open `android-skeleton` in Android Studio → Build → Build APK(s).
Optional: place `nsfw_mobilenet_v2.tflite` in `app/src/main/assets/` before building to enable AI features.
