# BT Android Agent 2

[中文版本 / Chinese version](./README.zh-TW.md)

**Author:** Sam Chang (TYMPHANY SQA)  
**Organization:** TYMPHANY Taipei  
**Website:** [www.tymphany.com](https://www.tymphany.com)

## Overview
BT Android Agent 2 is an Android 12+ Bluetooth validation tool for speaker testing.

The current app is organized around a side navigation drawer with several test pages:
- **Dashboard**: Device discovery, pairing, and status overview.
- **Stress Test**: Automated A2DP connect/disconnect/play loops with connection KPI (latency, success rate) tracking and **CSV log persistence**.
- **Media Control**: AVRCP media-key automation, rapid stress loops, volume cycling, and stereo channel check playback.
- **HFP / SCO Stress**: Manual and automated A2DP to HFP (call mode) switching.
- **Stability Monitor**: Background-capable battery, RSSI, route, and glitch monitoring with **structured CSV export**.
- **Acoustic**: Stereo loopback diagnostics with Normal / SWAP / single-channel tone modes, microphone analysis, and automated channel checks.
- **Audio Clock Drift**: Acoustic clock offset analysis using a 1kHz reference signal, with SNR-based environment quality monitoring.
- **Volume Linearity**: Automatic 16-step (0-15) volume gain consistency check.
- **Audio Latency**: End-to-end latency measurement using frequency switching (1kHz to 2kHz).
- **Log Explorer**: View and share persistent test logs directly within the app, with **quick preview** support for CSV content.
- **About**: Dedicated page for version info and project description.

It uses a single-activity, fragment-based structure:
- `MainActivity`
- `DashboardFragment`
- `StressTestFragment`
- `MediaControlStressFragment`
- `HfpStressFragment`
- `BatteryMonitorFragment` (Communicates with `BatteryLoggingService`)
- `AcousticLoopbackFragment` (Stereo Tone Generator + Real-time Goertzel Analysis)
- `VolumeLinearityFragment` (Automated Volume Step Analysis)
- `AudioLatencyFragment` (Frequency-switching Latency Measurement)
- `AudioClockDriftFragment` (Acoustic Clock Drift Monitor)
- `LogViewerFragment` (Log Explorer)
- `AboutFragment` (Project Information)

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
  - connection status summary
  - raw diagnostic text
  - app version display in the page header
- **About card**

Behavior:
- Scans nearby devices using **Classic + BLE** discovery.
- Lets the user pair from the discovery list.
- Shows bonded devices in a separate list.
- Auto-selects the only bonded device when there is only one candidate.
- Enables quick connect / disconnect requests for the selected device.
- Plays a generated 10-second test tone.
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
- **Connection KPI** card
  - Average
  - Success rate
  - Min / Max
  - P90
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
  5. connect (and measure connection time)
  6. play audio again
  7. wait before next loop
- **Log Persistence**: Automated logging to CSV files in the phone's `Downloads/BT_Android_Agent_Logs/` directory for Stress Test and Battery monitoring.
- **Connection KPI Tracking**:
  - Measures the time from `connect()` command to `STATE_CONNECTED`.
  - Calculates real-time statistics: **Average**, **Min/Max**, and **90th Percentile (P90)**.
  - Tracks connection success rate.
  - Logs a final KPI summary at the end of the test.
- Supports different generated audio types.
- Copies logs to clipboard.
- Clears logs in-app.

### 3. Media Control
The Media Control page focuses on AVRCP/media-key checks, stereo playback validation, and long-running rapid stress automation.

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
- **Stereo Channel Check** card
  - `Left Only`
  - `Right Only`
  - `L-R Alternating`
  - `Stop Test`
- **Automation Stress Settings** card
  - **Volume Cycle** controls
    - interval
    - min %
    - max %
    - `Start Volume Cycle`
  - **Rapid Commands** controls
    - loop count with `Non-stop`
    - base interval
    - random range
    - checkboxes for Play/Pause, Next, Prev, Stop
    - `Start Rapid Commands`
    - `Stop Automation`
- log card

Behavior:
- Sends standard media key events.
- Adjusts system media volume directly.
- Supports a file-based stereo channel check with pre-generated voice resources.
- Executes high-frequency AVRCP commands with randomized timing for rapid stress testing.
- Shows real-time progress for long-running automation tasks.
- Requires a compatible background media player session (for example, Spotify).

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

Entry point:
- This page is accessed via the side navigation drawer.

### 5. Stability Monitor
The Stability Monitor page focuses on long-term battery polling, RSSI tracking, route visibility, and phone-side audio glitch monitoring.

UI sections:
- **Current status** card
  - current battery percentage
  - current RSSI (dBm) strength
  - current glitch count
  - current target device
- **Logger settings** card
  - logging interval input
  - `Enable Audio Quality Monitor`
  - `Silent Mode (for Spotify/Music)`
  - buffer strategy selector
  - `Start Monitoring` (Starts a Foreground Service)
  - `Stop Monitoring`
- **Stability Log** card
  - battery, RSSI, route, and glitch log output with timestamps
  - `Clear`

Behavior:
- **Automatic CSV Logging**: All battery and RSSI data is automatically saved to `Downloads/BT_Android_Agent_Logs/StabilityLog_YYYYMMDD.csv`.
- **Glitches Detection**: Monitors internal Android AudioTrack underruns (Phone Buffer) to identify if the phone's CPU or system is causing audio drops.
- **Foreground Service**: Uses `BatteryLoggingService` to continue polling even when the app is in the background or the screen is off.
- **Audio Route Tracking**: Captures whether playback is currently on BT A2DP, BT SCO, or the internal speaker.
- **RSSI Tracking**: Tries GATT RSSI first and falls back to short discovery scans when needed.
- **Event Monitoring**: Logs ACL connection/disconnection events in real-time.
- **Battery Fallback Paths**: Tries the platform battery API first, then falls back to BLE GATT Battery Service reads when the device exposes them.
- Keeps a timestamped on-screen history while the logger is active.

### 6. Acoustic
The Acoustic page provides a stereo speaker-to-microphone loopback check using generated left/right reference tones and real-time microphone analysis.

UI sections:
- **Tone Generator Control** card
  - mode selector:
    - `Normal`
    - `Swap`
    - `L-Only`
    - `R-Only`
  - `Start Manual`
  - `Auto Diag`
- **Microphone Analysis** card
  - input level progress bar
  - left-channel status and magnitude readout
  - right-channel status and magnitude readout
- **Event Log** card
  - timestamped detection log

Behavior:
- Generates stereo test tones with `AudioTrack`.
- Uses 1kHz for the left-channel reference and 2kHz for the right-channel reference in Normal mode.
- Supports **SWAP** mode, where the left/right reference frequencies are intentionally exchanged to validate swapped-channel behavior.
- Supports `L-Only` and `R-Only` modes for single-channel isolation.
- The automated diagnostic sequence runs left-only, right-only, normal, swapped-left-only, swapped-right-only, and full swap checks.
- Samples microphone input with `AudioRecord`.
- Uses Goertzel-based detectors to estimate 1kHz and 2kHz energy in real time.
- Shows `OK`, `SWAP OK`, or `LOST` state changes and records transitions in the event log.
- Long-pressing the acoustic log toggles advanced metrics display, including cached A2DP codec information when available.
- Stops playback and monitoring automatically when the page is closed.

### 7. Volume Linearity
The Volume Linearity page automates the process of checking gain consistency across the standard 16 Android media volume steps (0-15).

UI sections:
- **Test Controls** card
  - `Start Linearity Test`
  - `Stop`
- **Progress** card
  - current step (0-15)
  - progress bar
  - last measured magnitude
- **Test Results & Log** card
  - timestamped step results

Behavior:
- Automatically cycles through volume levels 0 to 15.
- Plays a 1kHz tone at each step for a fixed duration (2 seconds).
- Measures the received magnitude using the microphone.
- Logs results for each step to help identify "dead steps" (where volume doesn't change) or non-linear gain jumps.

### 8. Audio Latency
The Audio Latency page measures the end-to-end delay from the moment the phone's `AudioTrack` changes frequency to the moment the `AudioRecord` detects that change through the microphone.

UI sections:
- **Test Controls** card
  - `Start Latency Test`
  - `Stop`
- **Result Display**
  - Average latency value after 5 rounds
- **Event Log**
  - Detailed round-by-round results and timestamps

Behavior:
1. Runs **5 consecutive rounds** of latency measurement to ensure statistical accuracy.
2. Each round:
    - Plays a steady **1kHz** reference tone to stabilize the Bluetooth A2DP link.
    - Switches the frequency to **2kHz** and records the precise system timestamp.
    - Uses a Goertzel filter to detect the 2kHz arrival via the microphone.
    - Calculates the latency for that round.
3. After 5 rounds, it calculates and displays the **average latency**.
4. This multi-round approach helps filter out transient system jitters and provides a more reliable assessment of the Bluetooth audio path.

### 9. Audio Clock Drift
The Audio Clock Drift page measures acoustic clock offset between the phone and the Bluetooth device using a 1kHz loopback reference.

UI sections:
- **Main Display**
  - current ppm value
  - detected frequency / acoustic method label
  - accumulated offset and duration summary
  - recent trend history
- **Analysis Window** controls
  - `0.1s`
  - `0.5s`
  - `1.0s`
  - `2.0s`
- **Test Controls**
  - `Start Monitor`
  - `Stop`
- **Event Log**
  - timestamped drift-monitor log output

Behavior:
- Plays a precise 1kHz reference tone and records it via the microphone.
- Uses a Hanning-windowed frequency search around 1kHz to estimate drift in PPM.
- Tracks accumulated offset in milliseconds over the full run.
- Warns the user when SNR falls below 10 dB.
- Useful for validating long-term playback drift or TWS sync stability.

### 10. Log Explorer
The Log Explorer provides a central location to manage the structured test logs.

UI sections:
- Log file list (CSV files from internal storage)
- `Refresh List` button
- `Open Folder` info button

Behavior:
- Scans `Downloads/BT_Android_Agent_Logs/` for CSV files.
- **Quick Preview**: Tapping a log file opens a preview dialog showing the latest 200 lines of data in a readable code-style view.
- **Share**: Long-press or use the share icon to export logs via standard Android share sheets.
- Automatically highlights error/crash snapshots with alert icons.

## Navigation Safety
If a stress-related page is running a test, the app intercepts side-drawer navigation changes and asks whether the user wants to stop the running test before switching pages.

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
.github/
  workflows/
    build-test-apk.yml
app/
  src/main/java/com/sam/btagent/
    MainActivity.kt
    DashboardFragment.kt
    StressTestFragment.kt
    MediaControlStressFragment.kt
    HfpStressFragment.kt
    BatteryMonitorFragment.kt
    BatteryLoggingService.kt
    LogPersistenceManager.kt
    BluetoothHelper.kt
    StressTestService.kt
    DeviceAdapter.kt
    AcousticLoopbackFragment.kt
    VolumeLinearityFragment.kt
    AudioLatencyFragment.kt
    AudioClockDriftFragment.kt
    LogViewerFragment.kt
    AboutFragment.kt
  src/main/res/
    layout/
      fragment_dashboard.xml
      fragment_stress_test.xml
      fragment_media_control_stress.xml
      fragment_hfp_stress.xml
      fragment_battery_monitor.xml
      fragment_acoustic_loopback.xml
      fragment_volume_linearity.xml
      fragment_audio_latency.xml
      fragment_audio_clock_drift.xml
      fragment_log_viewer.xml
      fragment_about.xml
      dialog_log_preview.xml
      item_log_file.xml
      nav_header.xml
    values/
    menu/
      nav_drawer_menu.xml
    raw/
      left.wav
      right.wav
    xml/
      file_paths.xml
tools/
  adb_bt_summary.py
  bt_summary_gui.py
```

## GitHub Actions
The repository includes a GitHub Actions workflow for test APK generation:
- Workflow file: `.github/workflows/build-test-apk.yml`
- Triggers:
  - manual run via `workflow_dispatch`
  - automatic run on pushes to `main`
  - automatic run on pushed tags matching `v*`
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
- `ACCESS_COARSE_LOCATION`
- `MODIFY_AUDIO_SETTINGS`
- `RECORD_AUDIO`
- `BLUETOOTH_ADVERTISE`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `POST_NOTIFICATIONS`

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
cd /Users/sam/code/BT_Android_Agent2
./gradlew :app:assembleDebug
```

## Desktop Tool Usage
### ADB Summary Script
```bash
cd /Users/sam/code/BT_Android_Agent2

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
cd /Users/sam/code/BT_Android_Agent2
python3 tools/bt_summary_gui.py
```

## Known Limitations
- The Android Bluetooth Settings page can sometimes see Classic devices that a regular third-party app cannot discover through `startDiscovery()`.
- Connect/disconnect behavior is best-effort and may be blocked by platform restrictions.
- Codec reporting depends on device support and may return incomplete information.
- Media key automation depends on an active background player session; without a compatible media app, AVRCP-style commands may appear to do nothing.
- HFP / SCO behavior can vary significantly across Android devices and may be affected by OEM audio routing policies.
- Bluetooth battery reporting is device-dependent; some speakers do not expose usable battery values to third-party apps.
- Acoustic loopback detection depends on speaker volume, microphone gain, device acoustics, and environmental noise; threshold tuning may be needed across devices.

## Version
- App version: `0.00.09`
