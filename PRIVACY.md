# SafeView Privacy Statement

SafeView is designed as a local, user-controlled content-protection tool. Image classification is performed on the device with TensorFlow Lite. Screen frames used by Screen AI are processed in memory and are not intentionally saved, uploaded, or sent to a third-party classification service.

## Data handled locally

SafeView may process the following data locally on the device:

| Data | Purpose | Remote upload |
|---|---|---|
| Visible screen frames | Local Screen AI classification when the user enables MediaProjection | None by SafeView |
| Browser page metadata and media elements | Browser filtering and local classification | None by SafeView |
| Blocked-domain preferences | DNS filtering configuration | None by SafeView |
| Browser history and bookmarks | User-requested browser features | Stored locally by the app |
| Installed-app package names | Protected-app selection and foreground-app rules | None by SafeView |

## Permissions and special access

The VPN permission allows SafeView to create a local Android VPN interface for configured DNS-domain filtering. MediaProjection allows Screen AI to receive visible display frames after the user confirms Android’s screen-capture dialog. Display-over-other-apps access allows SafeView to show a blocking cover above another app. Usage Access is used only when the user selects individual protected apps and SafeView needs to identify the foreground package. Notification permission may be required so Android can show the ongoing protection notification.

These permissions are controlled by Android and can be revoked by the user at any time. SafeView should show protection as unavailable, paused, or incomplete when a required permission or service is not active.

## Network and filtering limits

The background VPN is DNS-only. It does not decrypt HTTPS traffic or inspect arbitrary pixels from other apps. Encrypted DNS, cached content, direct IP connections, app-specific networking, protected windows, and other Android restrictions may bypass domain filtering or prevent screen capture. The SafeView browser provides the strongest in-app media filtering, but no ordinary Android application can guarantee perfect blocking in every third-party app.

## Model attribution

The bundled model and its source attribution are documented in `android-skeleton/app/src/main/assets/MODEL_ATTRIBUTION.txt`. The model operates locally and is not a cloud service.

## User control

SafeView does not provide covert monitoring. The user must explicitly enable protection modes and approve Android’s required system dialogs. The user can disable VPN, Screen AI, overlay access, and Usage Access through Android settings or SafeView settings.
