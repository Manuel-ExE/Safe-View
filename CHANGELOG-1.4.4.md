# SafeView 1.4.4 — Audit Fixes

## Fixed

- Cached foreground-package lookups for 1.5 seconds to reduce repeated Usage Access queries during Screen AI sampling.
- Replaced the full-resolution intermediate Screen AI bitmap with direct 224×224 sampling from the captured image buffer.
- Added hostname validation for configurable blocked domains.
- Added an explicit warning before saving an empty blocked-domain list, which disables configured DNS-domain blocking.
- Aligned the README and setup guide with the 1.4.4 release.

## Validation

Static XML parsing, JavaScript syntax validation, feature-marker checks, and version metadata checks passed. A complete Android build and runtime test still requires Android Studio/Android SDK and a physical or emulated Android device.

The archive continues to document that a complete Gradle wrapper JAR is not included.
