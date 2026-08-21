# SafeView APK — Start Here

## What this is

This folder is the SafeView Android project. It is not the APK itself. Android Studio turns this project into an APK.

## The only build steps

1. Install **Android Studio** from https://developer.android.com/studio.
2. Unzip this package.
3. Open the folder named **android-skeleton** in Android Studio.
4. Wait for Android Studio to finish loading and syncing the project.
5. In the top menu, click **Build**.
6. Click **Build Bundle(s) / APK(s)**.
7. Click **Build APK(s)**.
8. Android Studio will show a message with a link named **locate**. Click it to find the APK.

The APK is normally located at:

```text
android-skeleton/app/build/outputs/apk/debug/app-debug.apk
```

You can copy that `.apk` file to an Android phone and install it.

## Important

The optional visual AI model is not included. The app can still build and run using its heuristic filter. To enable visual AI, add this file before building:

```text
android-skeleton/app/src/main/assets/nsfw_mobilenet_v2.tflite
```

The archive does not include the Gradle wrapper JAR. If Android Studio asks to generate or download Gradle files, allow it to do so. If Android Studio shows an error, use **File → Sync Project with Gradle Files**.

For a public release, use **Build → Generate Signed Bundle / APK → APK** instead of the ordinary debug APK. Keep the signing key safe.
