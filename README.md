# 5G Tile — 2.2 preview

Custom Quick Settings tile for the Xiaomi / Android 17 / Shizuku setup described by
this project. This revision requires on-device testing; a passing build does not
establish compatibility with every HyperOS or modem version.

## Reliability changes

- Failed or disconnected states remain tappable. Android STATE_UNAVAILABLE
  disables clicks, so it cannot be used for a “tap to retry” state.
- Status refreshes are coalesced and use a separate worker from user clicks.
  Results captured before a tap cannot overwrite the completed switch.
- A missing UserService connection callback expires after 1.5 seconds and permits
  rebinding. Shizuku death clears the pending connection state.
- Only DeadObjectException triggers one transport reconnect. A command timeout,
  permission error, or invalid SIM does not trigger blind command replay.
- Shell output is drained concurrently, retained output is capped, and each
  command has a timeout capped at 3 seconds. The click path shares an 8-second
  budget for connection and commands; this is not a guarantee against a hung
  Android Binder or OS process creation.
- Verification reads immediately, then polls briefly if necessary. No fixed
  delay on successful immediate reads and no repeated writes while the modem
  applies the setting.
- “复制诊断” copies device/Android version, selected SIM, timings, network type
  replies and the last error. It does not read phone numbers, IMEI or IMSI.

## Network mode

Each tap reads the allowed network types for the selected SIM, validates the
returned network names, and changes only the NR bit. Other radio types are
preserved. Empty, unknown or error responses abort the switch.

The earlier device masks are regression fixtures:

- without NR: 01001111101111111111
- with NR: 11001111101111111111

“5G 已允许” means NR is permitted in the user setting, not that the device is
currently registered on 5G. Coverage, carrier policy and modem re-registration
still determine the actual connection and status-bar icon.

## Install and check

1. Install the APK from the PR's **Build APK** workflow artifact.
2. Start Shizuku and grant access to **5G 切换**.
3. Open the app, select the intended SIM and run **完整自检**.
4. Add the tile. Check 5G off/on, repeated control-center openings, app process
   cleanup, and Shizuku stop/start. After a Shizuku restart, retry the tile.
5. If it fails or feels slow, use **复制诊断** and include whether the tile itself
   was slow or only the phone's 5G signal icon changed late.

GitHub's existing workflow uses a generated debug signing key. Separate builds
may have different signatures. If Android rejects an update with a signature
conflict, uninstall the old app first, then reinstall, reselect the SIM and
regrant Shizuku access. That removes this app's settings.

No terminal session, foreground service, boot receiver, analytics or internet
permission is used. Shizuku's independent UserService remains daemonized.
The setup Activity stays excluded from Recent Apps.

## Build

JDK 17, Android SDK 35, build tools 35.0.0 and Gradle 8.7:

    gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

The workflow builds main and pull requests, tests network parsing/bit preservation
and command failure/timeout/large-output behavior, and checks the packaged
Shizuku declarations. Shizuku API and provider remain at 13.1.5.
