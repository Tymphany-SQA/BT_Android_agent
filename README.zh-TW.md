# BT Android Agent

[English version / 英文版本](./README.md)

**作者：** Sam Chang (TYMPHANY SQA)  
**單位：** TYMPHANY Taipei  
**網站：** [www.tymphany.com](https://www.tymphany.com)

## 專案概述
BT Android Agent 是一個面向 Android 12+ 裝置的藍牙驗證工具，主要用於藍牙喇叭的掃描、配對、連線穩定性、媒體控制、HFP/SCO 路徑切換與電量記錄測試。

目前 app 以底部導覽列分成五個主要頁面：
- **Dashboard**
- **Stress Test**
- **Media**
- **Battery**
- **Acoustic**

另外，`Dashboard` 內也提供直接進入 **HFP Stress** 的捷徑，用來做 SCO / 通話路徑測試。

整體採用單一 Activity、Fragment 導向架構：
- `MainActivity`
- `DashboardFragment`
- `StressTestFragment`
- `MediaControlStressFragment`
- `HfpStressFragment`
- `BatteryMonitorFragment`
- `AcousticLoopbackFragment`

## 目前 UI 與功能
### 1. Dashboard
Dashboard 是主要入口，用來做裝置搜尋、配對與快速驗證。

畫面區塊：
- **Discovery card**
  - `BLE+Classic`
  - `Stop Scan`
  - 附近裝置清單
- **Paired Devices card**
  - 已配對裝置清單
- **Device Details card**
  - 裝置名稱與位址
  - `Connect`
  - `Disconnect`
  - `Play Audio`
  - `Raw Info`
  - `Unpair`
  - `Stress Test`
  - `HFP Stress`
  - 連線狀態摘要
  - 原始診斷資訊
- **About card**

功能：
- 以 **Classic + BLE** 搜尋附近裝置
- 可手動停止掃描
- 可從 discovery 清單直接發起配對
- 以獨立清單顯示已配對裝置
- 當只有一個已配對裝置時會自動選取
- 可對選定裝置發送 connect / disconnect 請求
- 可播放 10 秒測試音
- 可直接把選定裝置帶入 Stress Test 頁面
- 可直接從裝置細節區進入 HFP Stress 頁面
- 可顯示 A2DP / HFP 狀態與 codec 摘要

注意：
- Android 並未對一般 app 提供完整穩定的藍牙 profile connect/disconnect 公開 API。
- 本 app 對 `A2DP` 與 `HEADSET/HFP` 採用 best-effort 方式。
- 實際行為會受到 Android 版本、手機廠商與系統政策影響。

### 2. Stress Test
Stress Test 頁面用來驗證播放、斷線、重連的穩定性。

畫面區塊：
- 目標裝置資訊
- 播放秒數輸入
- 暫停秒數輸入
- 重複次數輸入
- 音訊類型選擇
- `Start Stress Test`
- `Stop Test`
- 測試進度區
  - 目前狀態
  - loop 計數
  - progress bar
  - 含時間戳的 log
  - `Copy`
  - `Clear`

功能：
- 自動執行以下測試循環：
  1. 播放音訊
  2. 暫停
  3. 斷線
  4. 暫停
  5. 重新連線
  6. 再次播放音訊
  7. 等待下一輪
- 支援不同測試音色
- 可複製 log
- 可清空 log

### 3. Media
Media 頁面用來測試 AVRCP / 媒體鍵 / 音量自動化行為。

畫面區塊：
- 頁首狀態列
- 背景播放器提示區
- **Manual AVRCP Controls** card
  - `Open Spotify`
  - `Prev`
  - `Play/Pause`
  - `Next`
  - `Stop`
  - `Vol -`
  - `Vol +`
- **Automation Stress Settings** card
  - **Volume Cycle**
    - interval
    - min %
    - max %
    - `Start Volume Cycle`
  - **Rapid Commands**
    - base interval
    - random range
    - Play/Pause / Next / Prev / Stop 勾選項
    - `Start Rapid Commands`
    - `Stop Automation`
- log card

功能：
- 發送標準媒體鍵事件
- 直接調整系統媒體音量
- 支援快速重複媒體鍵測試
- 支援在自訂音量範圍內做循環調整
- 需要背景有可接收媒體鍵的播放 app，效果才會明顯

### 4. HFP Stress
HFP Stress 頁面用來驗證 SCO / 通話音訊路徑切換。

畫面區塊：
- 狀態列
- 提示說明區
- **Manual SCO Control** card
  - `Start SCO (HFP)`
  - `Stop SCO (A2DP)`
- **Automation Settings** card
  - A2DP duration
  - HFP duration
  - `Start HFP Stress Loop`
  - `Stop Loop`
- log card

功能：
- 可手動切換 SCO on/off
- 可在 A2DP 與 HFP 模式之間做自動輪替
- 適合驗證裝置在模擬來電與通話結束後，是否能正確回到 A2DP 路徑

進入方式：
- 這個頁面不是底部導覽主頁籤，而是由 Dashboard 裡的 `HFP Stress` 按鈕進入。

### 5. Battery
Battery 頁面用來做背景式電量與訊號強度（RSSI）長時間記錄。

畫面區塊：
- **Current Battery & Signal** 卡片
  - 目前電量百分比
  - 目前 RSSI（dBm）
  - 目前目標裝置
- **Logger Settings** 卡片
  - interval input
  - `Start Logging`（啟動 Foreground Service）
  - `Stop Logging`
- **History Log** 卡片
  - 含時間戳的電量與 RSSI 記錄輸出
  - 自動捲動的 log 視窗
  - `Clear`

功能：
- **Foreground Service**：透過 `BatteryLoggingService` 在 app 退到背景或螢幕關閉後仍持續記錄。
- **RSSI 追蹤**：每次輪詢時會短暫觸發 discovery 來補抓目前連線裝置的 RSSI。
- **事件監控**：會即時記錄 ACL connected / disconnected 事件。
- **電量備援路徑**：會先嘗試平台 battery API，若裝置有提供 BLE GATT Battery Service，也會用它做補讀。
- **Auto-Scroll**：log 會自動捲到最新一筆，除非使用者手動停在較前面的內容。
- 適合做長時間放電 / 充電曲線與訊號穩定度追蹤

### 6. Acoustic
Acoustic 頁面提供簡單的喇叭對麥克風 loopback 檢查，透過 1kHz 測試音與即時麥克風分析來判斷聲音是否有成功回授。

畫面區塊：
- **Tone Generator (1kHz)** 卡片
  - `Start 1kHz Tone`
  - `Stop 1kHz Tone`
- **Real-time Monitor** 卡片
  - 輸入電平 progress bar
  - 1kHz 偵測狀態 badge
  - magnitude 顯示
- **Event Log** 卡片
  - 含時間戳的偵測事件紀錄

功能：
- 透過 `AudioTrack` 連續輸出 1kHz 測試音。
- 透過 `AudioRecord` 擷取麥克風訊號。
- 使用 Goertzel 演算法即時估算 1kHz 成分強度。
- 顯示 `DETECTED` / `NO TONE` 狀態切換，並把變化寫入 log。
- 離開頁面時會自動停止播放與監聽。

## 導頁保護機制
如果目前頁面正在執行測試，切換底部導覽頁面時，app 會先詢問是否停止測試，再切換頁面，避免誤切頁造成測試中斷。

## 桌面診斷工具
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
    BatteryLoggingService.kt
    AcousticLoopbackFragment.kt
  src/main/res/
    layout/
      fragment_dashboard.xml
      fragment_stress_test.xml
      fragment_media_control_stress.xml
      fragment_hfp_stress.xml
      fragment_battery_monitor.xml
      fragment_acoustic_loopback.xml
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

此流程主要給內部測試分發使用，目前產出的是 debug APK，不是可直接上架的 signed release APK。

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
- Acoustic loopback 的偵測結果會受喇叭音量、麥克風增益、裝置聲學結構與環境噪音影響，不同手機上可能需要調整門檻值。

## 版本
- App 版本：`0.00.06`
