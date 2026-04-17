# BT Android Agent

**Author:** Sam Chang (TYMPHANY SQA)  
**Organization:** TYMPHANY Taipei  
**Website:** [WWW.TYMPHANY.COM](https://WWW.TYMPHANY.COM)

## Project Overview
A specialized Android application and toolset for evaluating Bluetooth speaker stability, connection reliability, and audio performance. This project features a modern Fragment-based architecture and robust logging for automated stress testing.

## Key Features
- **Bluetooth Dashboard:**
    - Real-time monitoring of Bluetooth adapter and connected devices.
    - Support for both Classic Bluetooth and BLE scanning.
    - Quick access to profile connection status (A2DP, HFP).
    - Audio test tone generator for quick validation.
- **Stress Test Module:**
    - Automated 7-step test sequence: Play -> Pause -> Disconnect -> Wait -> Connect -> Play Again -> Loop.
    - Configurable play duration, pause intervals, and repeat counts.
    - Built-in audio samples: Soft Piano, Zen Bells, and Standard Beep.
    - Comprehensive test logging with "Copy to Clipboard" and "Clear" functionality.
- **Modern Architecture:** Single-activity pattern using `DashboardFragment` and `StressTestFragment` with stable lifecycle management.

## Getting Started
1. **Android App:**
    - Open this project folder in Android Studio.
    - Sync Gradle and install required SDK components.
    - Run the `app` module on a connected Android device.
    - Grant Bluetooth and Location permissions when prompted.
2. **Desktop Diagnostics (Mac/Linux/Windows):**
    - Ensure Python 3 is installed.
    - Use the command-line helper for deep protocol analysis:
      ```bash
      python3 tools/adb_bt_summary.py
      ```
    - Launch the GUI dashboard for a desktop view of Bluetooth status:
      ```bash
      python3 tools/bt_summary_gui.py
      ```

## Command-Line Usage
```bash
# Basic summary
python3 tools/adb_bt_summary.py

# Filter by device name
python3 tools/adb_bt_summary.py --device "ACTON III"

# Output in JSON format for external parsing
python3 tools/adb_bt_summary.py --json

# Specific diagnostics
python3 tools/adb_bt_summary.py --codec-only
python3 tools/adb_bt_summary.py --metadata-only
```

## Maintenance & Versioning
- **Current Version:** v0.00.01
- **CI/CD:** Supports GitHub Actions for automated APK generation (config required in `.github/workflows`).
