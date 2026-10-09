# Lumi Signal 1.8.0 — Build Verification

## Status

Source implementation and static review are complete. A new APK is **not claimed as built or runtime-tested** in this environment.

## Build attempt

Command attempted:

```text
./gradlew testDebugUnitTest --no-daemon
```

The Gradle wrapper attempted to download `gradle-8.9-bin.zip`, but the execution environment could not reach `services.gradle.org`. No Gradle 8.9 distribution or Kotlin compiler was preinstalled locally. Therefore compilation, unit-test execution, lint, release signing, and Android runtime testing could not be completed here.

## Safety decision

- The existing 1.7.0 APK was not renamed or presented as 1.8.0.
- No unverified APK was produced.
- `versionName` and `versionCode` in source are 1.8.0 and 10800.
- The source archive is intended for Android Studio/JDK 17/SDK 35 or another environment with the Gradle 8.9 distribution available.

## Required release verification

1. Run `./gradlew testDebugUnitTest lintDebug assembleRelease`.
2. Confirm every unit test passes, including Stockbit historical parsing and strategy hard gates.
3. Install the signed APK as an update over 1.7.0.
4. Validate WebView login, one ticker analysis, a full market screen, chart rendering, and one-minute foreground audit on a physical Android device.
5. Confirm the private Stockbit response shapes for historical summary, orderbook, and single-day broker summary have not changed.
