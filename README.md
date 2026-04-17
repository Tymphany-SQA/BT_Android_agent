# BT Android Agent

[中文版本 / Chinese version](./README.zh-TW.md)

**Author:** Sam Chang (TYMPHANY SQA)  
**Organization:** TYMPHANY Taipei  
**Website:** [www.tymphany.com](https://www.tymphany.com)

## Overview
BT Android Agent is an Android 12+ Bluetooth validation tool for speaker testing.

The current app is organized around five bottom-navigation pages:
- **Dashboard**
- **Stress Test**
- **Media Stress**
- **HFP Stress**
- **Battery**

It uses a single-activity, fragment-based structure:
- `MainActivity`
- `DashboardFragment`
- `StressTestFragment`
- `MediaControlStressFragment`
- `HfpStressFragment`
- `BatteryMonitorFragment`

## Current UI and Features
### 1. Dashboard
The Dashboard is the main entry point for device discovery and quick checks.

UI sections:
- **Discovery card**
  - `BLE+Classic` scan button
  - `Stop Scan` button
  - discovery list for nearby devices
- **Paired Devices card**
  - bonded device list
- **Device Details card**
  - selected device name and address
  - `Connect`
  - `Disconnect`
  - `Play Audio`
  - `Raw Info`
  - `Unpair`
  - `Stress Test`
  - connection status summary
  - raw diagnostic text
- **About card**

Behavior:
- Scans nearby devices using **Classic + BLE** discovery.
- Lets the user pair from the discovery list.
- Shows bonded devices in a separate list.
- Auto-selects the only bonded device when there is only one candidate.
- Enables quick connect / disconnect requests for the selected device.
- Plays a generated 10-second test tone.
- Opens the selected device directly in the Stress Test page.
- Displays A2DP / HFP connection hints and codec summary.

Important note:
- Android does **not** provide stable public APIs for all Bluetooth profile connect/disconnect operations.
- This app uses best-effort profile proxy calls for `A2DP` and `HEADSET/HFP`.
- Behavior can vary by Android version, OEM build, and device policy.

### 2. Stress Test
The Stress Test page is designed for repeated playback and reconnect validation.

UI sections:
- target device summary
- play duration input
- pause interval input
- repeat count input
- generated audio selector
- `Start Stress Test`
- `Stop Test`
- progress section with:
  - current status
  - loop counter
  - progress bar
  - timestamped test log
  - `Copy`
  - `Clear`

Behavior:
- Runs an automated loop:
  1. play audio
  2. pause
  3. disconnect
  4. pause
  5. connect
  6. play audio again
  7. wait before next loop
- Supports different generated audio types.
- Copies logs to clipboard.
- Clears logs in-app.

### 3. Media Stress
The Media Stress page focuses on AVRCP/media-key and volume automation checks.

UI sections:
- page status header
- hint banner for using a background media player
- **Manual AVRCP Controls** card
  - `Open Spotify`
  - `Prev`
  - `Play/Pause`
  - `Next`
  - `Stop`
  - `Vol -`
  - `Vol +`
- **Automation Stress Settings** card
  - **Volume Cycle** controls
    - interval
    - min %
    - max %
    - `Start Volume Cycle`
  - **Rapid Commands** controls
    - base interval
    - random range
    - checkboxes for Play/Pause, Next, Prev, Stop
    - `Start Rapid Commands`
    - `Stop Automation`
- log card

Behavior:
- Sends standard media key events.
- Adjusts system media volume directly.
- Can rapidly repeat selected AVRCP-style commands.
- Can cycle volume between defined limits.
- Requires a compatible background media player session for media-key effects to be observable.

### 4. HFP Stress
The HFP Stress page focuses on SCO / call-path switching.

UI sections:
- status header
- help banner
- **Manual SCO Control** card
  - `Start SCO (HFP)`
  - `Stop SCO (A2DP)`
- **Automation Settings** card
  - A2DP duration input
  - HFP duration input
  - `Start HFP Stress Loop`
  - `Stop Loop`
- log card

Behavior:
- Manually requests SCO on/off.
- Repeatedly alternates between A2DP and HFP timing windows.
- Helps validate whether audio routing returns correctly after simulated call-mode transitions.

### 5. Battery
The Battery page focuses on battery polling and history logging.

UI sections:
- current battery card
  - current battery percentage
  - current target device
- logger settings card
  - logging interval input
  - `Start Logging`
  - `Stop Logging`
- history log card
  - battery log output
  - `Clear`

Behavior:
- Detects a connected Bluetooth device, prioritizing A2DP-connected devices.
- Reads battery level when available.
- Logs battery history at a user-defined interval.
- Supports clipboard-friendly timestamped logs for later analysis.

## Navigation Safety
If a stress-related page is running a test, the app intercepts bottom-navigation changes and asks whether the user wants to stop the running test before switching pages.

## Desktop Diagnostics Tools
The repository also includes Python helper tools under `tools/`:
- `adb_bt_summary.py`
  - parses `adb shell dumpsys bluetooth_manager`
  - extracts bonded-device metadata, codec info, and A2DP state
  - supports text and JSON output
- `bt_summary_gui.py`
  - desktop Tkinter viewer for the parsed Bluetooth summary
  - supports refresh and watch mode

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
      fragment_dashboard.xml
      fragment_stress_test.xml
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

This workflow is intended for internal testing distribution. It currently builds an unsigned debug APK rather than a store-ready signed release APK.

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
- The project currently mixes production UI code with some legacy or experimental code paths, such as `StressTestActivity`, which is not the main in-app flow.

## Version
- App version: `0.00.03`
