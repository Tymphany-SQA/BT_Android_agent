# BT Android Agent

Minimal Android Studio project for evaluating Bluetooth speaker control on a Pixel 4.

Current MVP:
- shows Bluetooth adapter status
- requests runtime Bluetooth permissions
- lists bonded devices
- includes a Mac-side `adb` summary helper for codec / metadata diagnostics

Suggested next steps:
1. Open this folder in Android Studio
2. Let Android Studio sync Gradle and install missing SDK components
3. Run the app on the connected Pixel 4
4. Grant Bluetooth permissions in the app
5. Extend the app with scan / connected-state / logcat export commands

Useful command-line helper:

```bash
python3 /Users/sam/code/BT_Android_agent/tools/adb_bt_summary.py
python3 /Users/sam/code/BT_Android_agent/tools/adb_bt_summary.py --device "ACTON III"
python3 /Users/sam/code/BT_Android_agent/tools/adb_bt_summary.py --json
python3 /Users/sam/code/BT_Android_agent/tools/adb_bt_summary.py --codec-only
python3 /Users/sam/code/BT_Android_agent/tools/adb_bt_summary.py --metadata-only --device "ACTON III"
python3 /Users/sam/code/BT_Android_agent/tools/adb_bt_summary.py --a2dp-only --device "ACTON III"
```

Desktop diagnostics panel:

```bash
python3 /Users/sam/code/BT_Android_agent/tools/bt_summary_gui.py
```

Or double-click:

```bash
/Users/sam/code/BT_Android_agent/launch_bt_summary_gui.command
```
