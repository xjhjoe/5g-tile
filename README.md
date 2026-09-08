# 5G Tile (custom build)

Purpose-built for a Xiaomi Android 17 device using Shizuku.

## Exact verified masks

- 4G/auto without NR: `01001111101111111111`
- 5G/auto with NR: `11001111101111111111`

The tile queries `cmd phone get-allowed-network-types-for-users -s <slot>` on every use and toggles only the NR bit using the masks above.

## Why this avoids the aShell You issue

- No terminal session is kept alive.
- Each tap checks the current Shizuku binder and creates a fresh shell process.
- The setup Activity has `android:excludeFromRecents="true"`, so it does not remain in Recent Apps.
- No background polling, foreground service, boot receiver, analytics, or internet permission.

## Build

The repository includes a GitHub Actions workflow that builds an installable debug APK with JDK 17, Android SDK 35 and Gradle 8.7.

Local build requirements: JDK 17 and Android SDK 35.

```bash
gradle :app:assembleDebug
```

Dependencies:

- `dev.rikka.shizuku:api:13.1.5`
- `dev.rikka.shizuku:provider:13.1.5`
