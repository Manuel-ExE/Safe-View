# SafeView Android v1.4.3 Audit

## Executive conclusion

SafeView v1.4.3 contains the claimed feature changes in source: UsageEvents-based foreground-app detection, live classifier thresholds, reduced capture dimensions, an improved protected-app selector, configurable DNS domains, and updated release metadata in the Android module. Static validation passed for the Android XML resources, JavaScript syntax, Gradle metadata, and feature markers.

The archive is suitable for **controlled device testing**, but I do not recommend treating it as production-ready until a real Android build and device test are completed. The archive still does not contain `gradle-wrapper.jar`, and the top-level README remains version-stale. The most important runtime risk is performance: foreground-package lookup is performed on every sampled frame, so UsageEvents queries may occur roughly twice per second while Screen AI is active.

## Verified changes

| Area | Evidence | Assessment |
|---|---|---|
| Foreground-app detection | `SafeViewScreenAiService.kt:153-199` uses `UsageStatsManager.queryEvents()` and consumes `MOVE_TO_FOREGROUND` / `ACTIVITY_RESUMED` events. | Implemented. Unknown foreground app and missing Usage Access fall back to analyzing everything. |
| Live thresholds | `SafeViewScreenAiService.kt:207-213` reads `SettingsPrefs` for every classification and passes `explicitThreshold` and `revealingThreshold` to the classifier. | Implemented. Slider changes are persisted by `SettingsActivity.kt:147-152`. |
| Capture performance | `SafeViewScreenAiService.kt:121-127` caps dimensions at 720; `:225-276` downsamples to 224 before classification and recycles intermediate bitmaps. | Implemented, though a full capture bitmap is still temporarily allocated before scaling. |
| Overlay behavior | `SafeViewScreenAiService.kt:316-319` uses a touch-consuming overlay without `FLAG_NOT_TOUCHABLE`. | Implemented. Real-device testing is required for touch and accessibility behavior. |
| App-rules UX | `AppRulesActivity.kt:54-171` provides Usage Access status, an always-visible settings button, a Protect all apps switch, and excludes SafeView itself. | Implemented. Empty package set means all-app protection. |
| Configurable domains | `BlockedDomainsActivity.kt:43-77` edits and resets the list; `SafeViewVpnService.kt:112-115` reads `SettingsPrefs.blockedDomains` on each DNS query. | Implemented. The VPN remains DNS-only and does not inspect image pixels. |
| Release metadata | `app/build.gradle:10-16` reports `versionCode 11` and `versionName "1.4.3"`. | Android metadata aligned. |

## Findings and recommendations

### P2 — Foreground lookup is repeated for every sampled frame

`shouldAnalyzeCurrentApp()` is called from the image listener at `SafeViewScreenAiService.kt:58-70`, and it calls `queryEvents()` at `:180-196` for each frame that passes the 500 ms sample interval. This can create repeated UsageStats work and battery consumption while Screen AI is active. Cache the foreground package for approximately 1–2 seconds on the worker thread, refresh it when the cache expires, and avoid querying UsageStats from the ImageReader callback.

### P2 — Full-size bitmap allocation remains before downsampling

Although the classifier receives a 224×224-scale bitmap, `imageToBitmap()` allocates a full `width × height` ARGB bitmap at `:240-244` and fills it before creating the scaled bitmap. With a 720-pixel cap this is bounded, but it still creates avoidable allocation and copy pressure. A production optimization would write directly into a 224×224 target using nearest-neighbor sampling from the `Image` buffer, avoiding the full intermediate bitmap.

### P2 — README version metadata is stale

The Android build and changelog identify v1.4.3, but the top-level `README.md` begins with `SafeView for Android v1.4.2`, and its Screen AI section still contains historical headings referring to 1.4.1 and 1.4.2. Update the headings and release summary before publishing so users do not receive contradictory version information.

### P2 — Domain editor accepts weakly validated input

`BlockedDomainsActivity.kt:61-66` accepts any non-empty line containing a dot. It normalizes case and removes `www.`, but it does not reject paths, ports, wildcards, malformed labels, or IP addresses. Add strict hostname validation and show invalid entries to the parent rather than silently storing them.

### P3 — Empty custom blocklist can disable DNS blocking

Saving an empty domain list produces an empty preference set, which means the VPN blocks no configured domains. This may be intentional, but the UI should warn the parent that saving an empty list disables domain blocking, or provide an explicit “disable custom list” state separate from an empty list.

### P3 — No functional Gradle wrapper is included

The archive contains wrapper properties and a `gradlew` scaffold but no `gradle-wrapper.jar`. This is documented in the build instructions, so it is not a hidden claim, but it prevents fully reproducible command-line and CI builds from the archive alone. Generate and commit the official wrapper before a release build.

## Validation performed

The following checks passed:

| Check | Result |
|---|---|
| AndroidManifest XML parsing | Pass |
| `strings.xml` XML parsing | Pass |
| Settings layout XML parsing | Pass |
| `www/app.js` syntax check | Pass |
| Android `versionCode` / `versionName` markers | Pass |
| UsageEvents, live thresholds, and blocked-domain markers | Pass |
| Gradle wrapper JAR presence | Absent; documented limitation |

A complete Android compile and runtime test was not performed because the archive does not include a usable wrapper JAR and the sandbox does not provide the Android SDK/device environment.

## Release recommendation

Approve v1.4.3 for **controlled QA**, not final public release. Before production packaging, update the README version headings, test the full permission and app-switching lifecycle on Android 13/14, measure battery use with Usage Access enabled, validate malformed domain input, test protected and non-protected apps, and generate a real Gradle wrapper. The core v1.4.3 claims are substantively present, but the runtime behavior still needs device verification.

## References

[1]: https://developer.android.com/reference/android/app/usage/UsageStatsManager "Android UsageStatsManager reference"
[2]: https://developer.android.com/reference/android/media/projection/MediaProjection "Android MediaProjection reference"
[3]: https://developer.android.com/reference/android/net/VpnService "Android VpnService reference"
