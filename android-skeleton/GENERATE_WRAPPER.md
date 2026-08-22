# Gradle wrapper

This archive includes a complete Gradle wrapper: `gradlew`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle-wrapper.properties`. The wrapper uses Gradle 8.2 and downloads the distribution automatically when needed.

## Command-line build

From the `android-skeleton` directory:

```bash
chmod +x gradlew
./gradlew clean lint assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Android Studio build

Open `android-skeleton` in Android Studio, allow the project to sync, and choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

The GitHub Actions workflow uses Gradle 8.2 directly and builds `assembleDebug` from this directory.
