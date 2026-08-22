# SafeView v1.4.4 — Start Here

Open the `android-skeleton` folder in Android Studio and build the debug APK with **Build → Build Bundle(s) / APK(s) → Build APK(s)**. A complete Gradle wrapper is included, so from a terminal you can also run `./gradlew assembleDebug` inside `android-skeleton`.

After installing the APK, open SafeView and tap **Settings**. Use the **Protection status** card as the setup guide. Enable Background protection and approve Android’s VPN dialog. Enable Screen AI protection, read the privacy notice, allow Display over other apps, and approve Android’s screen-capture dialog. The status card must show the VPN filter as active and Screen capture as running before those services are treated as ready.

Open **Choose protected apps** to keep the default Protect all apps behavior or select individual apps. Selecting individual apps requires Android Usage Access so SafeView can identify the foreground package; SafeView does not read messages, contacts, URLs, or app content.

Screen AI samples visible display frames and runs the bundled TFLite classifier locally. Frames are discarded and are not saved or uploaded. If Android stops capture, SafeView marks the service paused and shows a warning. In Strict mode, unavailable or uncertain browser media is covered rather than silently treated as safe.

The SafeView browser includes a custom home page, tabs, a three-dot menu, reload, history, downloads, bookmarks, and add-bookmark actions. The tabs UI uses one shared WebView, so cookies and storage are not isolated between tabs.

## Model

The package includes `nsfw_mobilenet_v2.tflite` in `android-skeleton/app/src/main/assets/`. It is a local two-label `nonnude`/`nude` model. Its accuracy is not a guarantee for every image or video frame.

## Platform limitations

The background VPN is DNS-only and cannot inspect image pixels in other apps. Encrypted DNS, cached content, direct IP connections, protected windows, and app-specific networking may bypass parts of the protection stack. The SafeView browser provides the strongest image-level filtering, but no ordinary Android app can guarantee perfect blocking inside every third-party app.

See `README.md`, `PRIVACY.md`, and `MODEL_ATTRIBUTION.txt` for additional details.
