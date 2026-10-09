# Lumi Signal 1.9.0 — Build Verification

- `assembleRelease`: passed
- Release lint vital: passed
- R8 minification/resource shrinking: passed
- Unit tests: 35 passed, 0 failed, 0 skipped
- Package: `com.lumisignal.idxscreener`
- Version: `1.9.0` (`versionCode 10900`)
- Min/target SDK: 28/35
- APK signing: verified with APK Signature Scheme v2
- APK SHA-256: `21ed2884cd8e2101612996d992eb68ef1e350c7216ce20ce595c6c1aa33fa344`

The release was built with Gradle 8.9, Android Gradle Plugin 8.7.3,
Android Platform/Build Tools 35, and Temurin JDK 17.0.17.

## Runtime validation boundary

Stockbit integration uses unofficial endpoints. Authenticated login, portfolio,
orders/history parsing, live market data, and foreground audit behavior must be
validated on a physical Android device with a real Stockbit account. Telegram
delivery likewise requires a real bot token and chat ID. Device/OEM power
management can interrupt a one-minute foreground audit despite the in-app
battery-optimization warning.
