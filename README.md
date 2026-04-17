# BT Android Agent

[中文版本 / Chinese version](./README.zh-TW.md)

**Author:** Sam Chang (TYMPHANY SQA)  
**Organization:** TYMPHANY Taipei  
**Website:** [www.tymphany.com](https://www.tymphany.com)

## Overview
BT Android Agent is an Android test app for Bluetooth speaker validation on Android 12+ devices.

The project currently focuses on five practical workflows:
- A **Dashboard** for discovery, pairing, basic connection control, and quick audio checks.
- A **Stress Test** module for repeated connect/disconnect and playback verification.
- A **Media Control Stress** module for AVRCP/media-key and volume automation checks.
- An **HFP / SCO Stress** module for call-path handover verification.
- A **Battery Monitor** module for battery-level polling and logging.

The app uses a single-activity, fragment-based structure:
- `MainActivity`
- `DashboardFragment`
- `StressTestFragment`
- `MediaControlStressFragment`
- `HfpStressFragment`
- `BatteryMonitorFragment`

## Current App Features
### 1. Dashboard
- Scan nearby devices with **Classic + BLE** discovery.
- Stop an active scan manually.
- Show discovered devices in a tappable list.
- Start pairing from the discovery list.
- Show bonded / paired devices in a separate list.
- Auto-select the only bonded device when there is just one candidate.
- Display a device details card for the selected bonded device.

### 2. Device Control
- **Connect** the selected bonded device with best-effort profile requests.
- **Disconnect** the selected bonded device with best-effort profile requests.
- **Unpair** the selected bonded device.
- Launch the selected device directly into the built-in stress test page.

Important note:
- Android does **not** provide stable public APIs for all Bluetooth profile connect/disconnect operations.
- This app uses best-effort profile proxy calls for `A2DP` and `HEADSET/HFP`.
- Behavior can vary by Android version, OEM build, and device policy.

### 3. Audio / Codec Checks
- Play a generated **10-second test tone** from the Dashboard.
- Read current A2DP profile connection state.
- Read HFP/HEADSET profile connection state.
- **Current A2DP Codec Summary**: Displays the active codec (SBC, AAC, LDAC, etc.).
  - **Note**: Codec reporting is **Read-Only**. Android does not allow third-party apps to change codecs programmatically due to security restrictions (requires system-level permissions).

Important note:
- A2DP codec reporting is best-effort.
- Some codec information depends on hidden or device-specific APIs and may return only partial data.

### 4. Stress Test Module
- Select a target device from the Dashboard and switch to the stress test screen.
- Configure:
  - play duration
  - pause interval
  - repeat count
  - generated audio type
- Run an automated loop:
  1. play audio
  2. pause
  3. disconnect
  4. pause
  5. connect
  6. play audio again
  7. wait before next loop
- View live progress:
  - current status
  - loop counter
  - progress bar
  - timestamped log output
- Copy the log to clipboard.
- Clear the log in-app.

### 5. Media Control Stress (AVRCP)
- **Manual Controls**: Send standard AVRCP commands (Play, Pause, Next, Previous, Stop).
- **Volume Control**: Adjust system media volume (Vol+ / Vol-) directly.
- **One-Click Tools**: Quick launch button for **Spotify** to facilitate testing.
- **Automation Stress**:
  - **Volume Cycle**: Ramps volume between custom limits (e.g., 20% to 70%) to test gain stability.
  - **Rapid Commands**: Select multiple commands (Play/Pause, Next, Prev, Stop) to loop through at high frequency.
- **Navigation Safety**: Intercepts navigation attempts if a test is running, prompting the user to stop the test before switching pages.
- **Requirement**: Requires a background media app (like Spotify) for media key commands to take effect.

### 6. HFP / SCO Stress Test
- **Manual SCO Control**: Manually trigger `startBluetoothSco()` and `stopBluetoothSco()` to toggle between Music (A2DP) and Call (HFP) modes.
- **Automation Loop**: 
  - Define custom durations for A2DP and HFP states.
  - Repeatedly toggle profiles to verify the speaker's firmware stability during profile handovers.
  - Monitor if the audio path correctly restores to high-quality A2DP after the "call" ends.

### 7. Battery Monitor
- **Real-time Level**: Read current battery percentage (%) of the connected Bluetooth device.
- **Battery History Logger**: Automatically poll battery levels at custom intervals (e.g., every 1 or 5 minutes).
- **Discharge/Charge Tracking**: Useful for long-term battery life verification or charging curve analysis.
- **Log Export**: Copy time-stamped battery data to clipboard for Excel analysis.

### 8. Desktop Diagnostics Tools
The repository also includes Python helper tools under `tools/`:
- `adb_bt_summary.py`
  - parses `adb shell dumpsys bluetooth_manager`
  - extracts bonded-device metadata, codec info, and A2DP state
  - supports text and JSON output
- `bt_summary_gui.py`
  - desktop Tkinter viewer for the parsed Bluetooth summary
  - supports refresh and watch mode

## Future Roadmap (SQA Ideas)
- **Battery Graph**: Integration of a real-time graph view for battery discharging/charging.
- **AVRCP Metadata**: Verification of track information (title/artist) synchronization.
- **BLE Battery**: Reading precise battery levels via GATT Battery Service if available.
- **Absolute Volume**: Automated testing of volume synchronization between phone and speaker.
- **Multi-point**: Automated audio source preemptive testing between two connected agents.

## Repository Structure
```text
app/
  src/main/java/com/sam/btagent/
    MainActivity.kt
    DashboardFragment.kt
    StressTestFragment.kt
    MediaControlStressFragment.kt
    HfpStressFragment.kt
    BatteryMonitorFragment.kt
    StressTestActivity.kt
  src/main/res/
    layout/
      ...
      fragment_media_control_stress.xml
      fragment_hfp_stress.xml
      fragment_battery_monitor.xml
    values/
    menu/
tools/
  adb_bt_summary.py
  bt_summary_gui.py
```

## GitHub Actions
The repository includes a GitHub Actions workflow for test APK generation:
- Workflow file: `.github/workflows/build-test-apk.yml`
- Triggers:
  - manual run via `workflow_dispatch`
  - automatic run when a GitHub Release is published
- Output:
  - builds a **debug APK** for testing
  - uploads the APK as a workflow artifact
  - attaches the APK to the GitHub Release asset when triggered from a release event

This is intended for internal testing distribution. It currently builds an unsigned debug APK rather than a store-ready signed release APK.

## Requirements
### Android App
- Android Studio
- Android SDK 35
- Java 17
- Android device running **Android 12+** (`minSdk = 31`)

### Permissions Used
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION`
- `MODIFY_AUDIO_SETTINGS`
- `RECORD_AUDIO`
- `BLUETOOTH_ADVERTISE`

Manifest also declares:
- Classic Bluetooth support
- BLE support

## Build and Run
### Android Studio
1. Open this folder in Android Studio.
2. Sync Gradle.
3. Connect an Android device.
4. Run the `app` module.
5. Grant Bluetooth and location-related permissions when prompted.

### Command Line
```bash
cd /Users/sam/code/BT_Android_agent
./gradlew :app:assembleDebug
```

## Desktop Tool Usage
### ADB Summary Script
```bash
cd /Users/sam/code/BT_Android_agent

# basic summary
python3 tools/adb_bt_summary.py

# filter by device
python3 tools/adb_bt_summary.py --device "ACTON III"

# JSON output
python3 tools/adb_bt_summary.py --json

# codec-only view
python3 tools/adb_bt_summary.py --codec-only

# metadata-only view
python3 tools/adb_bt_summary.py --metadata-only
```

### GUI Summary Panel
```bash
cd /Users/sam/code/BT_Android_agent
python3 tools/bt_summary_gui.py
```

## Known Limitations
- The Android Bluetooth Settings page can sometimes see Classic devices that a regular third-party app cannot discover through `startDiscovery()`.
- Connect/disconnect behavior is best-effort and may be blocked by platform restrictions.
- Codec reporting depends on device support and may return incomplete information.
- Media key automation depends on an active background player session; without a compatible media app, AVRCP-style commands may appear to do nothing.
- HFP / SCO behavior can vary significantly across Android devices and may be affected by OEM audio routing policies.
- Bluetooth battery reporting is device-dependent; some speakers do not expose usable battery values to third-party apps.
- The project currently mixes production UI code with some legacy/experimental code paths, such as `StressTestActivity`, which is not the main in-app flow.

## Version
- App version: `0.00.03`
