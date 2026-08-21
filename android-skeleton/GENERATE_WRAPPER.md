# Gradle wrapper

This archive ships **only** `gradle/wrapper/gradle-wrapper.properties`.

It does **not** include a working `gradle-wrapper.jar` or a functional `gradlew` binary.
The `gradlew` script in this tree is an instructional stub and will exit with an error
if run. Do not treat it as a reproducible CLI build entry point until the full wrapper
is generated.

## Generate a real wrapper

### Android Studio (recommended)
1. File → Open → `android-skeleton`
2. Trust / sync the project
3. Studio will download dependencies and can create wrapper files as needed

### Command line (machine with Gradle installed)
```bash
cd android-skeleton
gradle wrapper --gradle-version 8.2
chmod +x gradlew
./gradlew clean lint assembleDebug
```

Commit `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and the properties file
together before CI use.
