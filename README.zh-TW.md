# BT Android Agent

[English version / 英文版本](./README.md)

**作者：** Sam Chang (TYMPHANY SQA)  
**單位：** TYMPHANY Taipei  
**網站：** [www.tymphany.com](https://www.tymphany.com)

## 專案概述
BT Android Agent 是一個用於 Android 12+ 裝置的藍牙喇叭驗證工具，主要用來檢查掃描、配對、連線穩定性、音訊播放與壓力測試流程。

目前專案主要分成五個工作流：
- **Dashboard**：負責掃描、配對、基本連線控制與快速音訊驗證
- **Stress Test**：負責反覆 connect / disconnect / playback 的壓力測試
- **Media Control Stress**：負責 AVRCP / 媒體按鍵 / 音量自動化壓力測試
- **HFP / SCO Stress**：負責通話路徑切換與 HFP 穩定性測試
- **Battery Monitor**：負責電量讀取與長時間記錄

App 採用單一 Activity、Fragment 導向架構：
- `MainActivity`
- `DashboardFragment`
- `StressTestFragment`
- `MediaControlStressFragment`
- `HfpStressFragment`
- `BatteryMonitorFragment`

## 目前功能
### 1. Dashboard
- 支援 **Classic + BLE** 掃描附近裝置
- 可手動停止掃描
- 以清單方式顯示已發現裝置
- 可直接從發現清單發起配對
- 以獨立清單顯示已配對裝置
- 若只有一個已配對裝置，會自動選取
- 顯示選定裝置的詳細資訊卡片

### 2. 裝置控制
- 對選定的已配對裝置執行 **Connect**
- 對選定的已配對裝置執行 **Disconnect**
- 對選定的已配對裝置執行 **Unpair**
- 直接把目前選定裝置帶入內建 Stress Test 畫面

注意：
- Android 並沒有對一般 app 提供穩定公開的藍牙 profile connect/disconnect API。
- 本 app 目前對 `A2DP` 與 `HEADSET/HFP` 使用的是 **best-effort** 方式。
- 實際行為可能受 Android 版本、手機廠商與系統政策影響。

### 3. 音訊 / Codec 檢查
- 可從 Dashboard 播放 **10 秒測試音**
- 可讀取目前 A2DP profile 連線狀態
- 可讀取 HFP / HEADSET profile 連線狀態
- 可嘗試讀取目前啟用中的 A2DP codec 摘要

注意：
- A2DP codec 資訊也是 best-effort。
- 有些 codec 資訊仰賴 hidden API 或裝置特定行為，可能只能拿到部分結果。

### 4. Stress Test 模組
- 可從 Dashboard 選定目標裝置後切換到 Stress Test 畫面
- 可設定：
  - 播放秒數
  - 暫停間隔
  - 重複次數
  - 產生音訊類型
- 可執行自動化測試循環：
  1. 播放音訊
  2. 暫停
  3. 斷線
  4. 暫停
  5. 重新連線
  6. 再次播放音訊
  7. 等待下一輪
- 可顯示即時測試資訊：
  - 目前狀態
  - loop 計數
  - progress bar
  - 含時間戳的 log
- 支援把 log 複製到剪貼簿
- 支援 app 內清除 log

### 5. Media Control Stress 模組
- 支援手動送出標準媒體鍵：
  - Play / Pause
  - Next
  - Previous
  - Stop
- 支援手動調整系統媒體音量（Vol+ / Vol-）
- 支援一鍵開啟 Spotify，方便快速建立可測試的背景播放器
- 支援兩種自動化壓力模式：
  - **Volume Cycle**：在自訂最小/最大百分比之間循環調整音量
  - **Rapid Commands**：以高頻率反覆送出 Play/Pause、Next、Prev、Stop 等媒體鍵
- 若目前有測試正在執行，切換頁面時會先跳出確認視窗，避免誤切頁造成測試中斷
- 此模組需要背景有支援媒體控制的 app（例如 Spotify），媒體鍵才會有明顯效果

### 6. HFP / SCO Stress 模組
- 支援手動呼叫 `startBluetoothSco()` / `stopBluetoothSco()`
- 可在 A2DP 與 HFP 之間反覆切換，驗證音訊路徑切換穩定性
- 可自訂 A2DP / HFP 停留時間，持續跑切換循環
- 適合檢查喇叭在模擬來電 / 通話結束後是否能正確回到 A2DP 高品質播放

### 7. Battery Monitor 模組
- 讀取目前連線藍牙裝置的電量百分比
- 可設定固定間隔進行電量輪詢與記錄
- 適合做長時間放電 / 充電曲線觀察
- 可把帶時間戳的紀錄複製出來做進一步分析

### 8. 桌面診斷工具
專案也包含 Python 工具，位於 `tools/`：
- `adb_bt_summary.py`
  - 解析 `adb shell dumpsys bluetooth_manager`
  - 提取已配對裝置 metadata、codec 資訊與 A2DP 狀態
  - 支援文字與 JSON 輸出
- `bt_summary_gui.py`
  - 提供桌面 Tkinter GUI 檢視藍牙摘要
  - 支援 refresh 與 watch mode

## 專案結構
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
此專案已加入可產生測試 APK 的 GitHub Actions workflow：
- workflow 檔案：`.github/workflows/build-test-apk.yml`
- 觸發方式：
  - 透過 `workflow_dispatch` 手動執行
  - 在 GitHub Release 發佈時自動執行
- 輸出內容：
  - 建立 **debug APK** 作為測試安裝包
  - 上傳到 Actions artifact
  - 若是由 release 事件觸發，也會自動附加到 GitHub Release asset

這個流程是給內部測試分發使用，目前產出的是未簽署商店版流程之外的 debug APK，不是可直接上架的 signed release APK。

## 環境需求
### Android App
- Android Studio
- Android SDK 35
- Java 17
- Android 裝置需為 **Android 12+**（`minSdk = 31`）

### 使用到的權限
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION`
- `MODIFY_AUDIO_SETTINGS`
- `RECORD_AUDIO`
- `BLUETOOTH_ADVERTISE`

Manifest 也宣告了：
- Classic Bluetooth 支援
- BLE 支援

## 建置與執行
### Android Studio
1. 用 Android Studio 開啟此專案資料夾。
2. 完成 Gradle sync。
3. 接上 Android 裝置。
4. 執行 `app` module。
5. 依提示授權藍牙與位置相關權限。

### 指令列
```bash
cd /Users/sam/code/BT_Android_agent
./gradlew :app:assembleDebug
```

## 桌面工具用法
### ADB Summary Script
```bash
cd /Users/sam/code/BT_Android_agent

# 基本摘要
python3 tools/adb_bt_summary.py

# 指定裝置
python3 tools/adb_bt_summary.py --device "ACTON III"

# JSON 輸出
python3 tools/adb_bt_summary.py --json

# 僅查看 codec
python3 tools/adb_bt_summary.py --codec-only

# 僅查看 metadata
python3 tools/adb_bt_summary.py --metadata-only
```

### GUI Summary Panel
```bash
cd /Users/sam/code/BT_Android_agent
python3 tools/bt_summary_gui.py
```

## 已知限制
- Android 藍牙設定頁有時能看到某些 Classic 裝置，但一般第三方 app 透過 `startDiscovery()` 不一定能看到。
- Connect / disconnect 屬於 best-effort，可能受到平台限制。
- Codec 資訊會依裝置支援度不同而不完整。
- 媒體鍵與 AVRCP 自動化依賴背景播放器狀態；若沒有活躍的媒體 session，看起來可能像是沒有作用。
- HFP / SCO 行為在不同 Android 裝置上的差異很大，常受手機廠商音訊路由策略影響。
- 藍牙電量資訊是否可讀，取決於裝置是否有對第三方 app 正確暴露 battery level。
- 專案目前仍混有部分舊版或實驗性路徑，例如 `StressTestActivity`，它不是目前 app 內主要流程。

## 版本
- App 版本：`0.00.03`
