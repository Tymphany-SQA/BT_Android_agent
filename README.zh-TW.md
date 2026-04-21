# BT Android Agent 2

[English version / 英文版本](./README.md)

**作者：** Sam Chang (TYMPHANY SQA)  
**單位：** TYMPHANY Taipei  
**網站：** [www.tymphany.com](https://www.tymphany.com)

## 專案概述
BT Android Agent 2 是一個面向 Android 12+ 裝置的藍牙驗證工具，主要用於藍牙喇叭的掃描、配對、連線穩定性、媒體控制、HFP/SCO 路徑切換與電量記錄測試。

目前 app 以側邊導覽列 (Side Drawer) 提供多個主要測試頁面：
- **Dashboard**：裝置搜尋、配對與狀態概覽。
- **Stress Test**：自動化 A2DP 連線/斷線/播放循環測試，具備連線 KPI 統計與 **CSV 日誌持久化**功能。
- **Media Control**：AVRCP 媒體鍵自動化、Rapid Stress、音量循環與左右聲道檢查。
- **HFP / SCO Stress**：手動與自動 A2DP 至 HFP (通話模式) 切換測試。
- **Stability Monitor**：支援背景執行的電量、RSSI、音訊路由、聲學連續性與品質分數監測，並自動**匯出為結構化 CSV**。
- **Acoustic**：立體聲 loopback 診斷，支援 Normal / SWAP / 單聲道測試模式、麥克風分析與自動聲道檢查。
- **Audio Clock Drift**：透過 1kHz 聲學 loopback 做時鐘漂移與 PPM 偏移分析，並提供 SNR 環境品質監看。
- **Volume Linearity**：自動化音量線性度與 16 階步階分析。
- **Audio Latency**：透過頻率切換 (1kHz 到 2kHz) 量測端到端音訊延遲。
- **Log Explorer**：在 app 內直接檢視與分享持久化測試日誌，支援 **快速預覽** CSV 內容。
- **About**：專屬的關於頁面，顯示版本資訊與專案描述。

整體採用單一 Activity、Fragment 導向架構：
- `MainActivity`
- `DashboardFragment`
- `StressTestFragment`
- `MediaControlStressFragment`
- `HfpStressFragment`
- `BatteryMonitorFragment`
- `AcousticLoopbackFragment` (立體聲 Tone Generator + 即時 Goertzel 分析)
- `VolumeLinearityFragment` (自動音量步階分析)
- `AudioLatencyFragment` (頻率切換延遲量測)
- `AudioClockDriftFragment` (Acoustic Clock Drift Monitor)
- `LogViewerFragment` (Log Explorer)
- `AboutFragment` (專案資訊)

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
  - 連線狀態摘要
  - 原始診斷資訊
  - 頁首版本號顯示
- **About card**

功能：
- 以 **Classic + BLE** 搜尋附近裝置
- 可手動停止掃描
- 可從 discovery 清單直接發起配對
- 以獨立清單顯示已配對裝置
- 當只有一個已配對裝置時會自動選取
- 可對選定裝置發送 connect / disconnect 請求
- 可播放 10 秒測試音
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
- **Connection KPI** 區塊
  - Average
  - Success rate
  - Min / Max
  - P90
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
  5. 重新連線（並量測連線耗時）
  6. 再次播放音訊
  7. 等待下一輪
- **日誌持久化 (Log Persistence)**：自動將 Stress Test 與 Battery Log 寫入手機 `Downloads/BT_Android_Agent_Logs/` 資料夾。
- **連線 KPI 統計**：
  - 精確量測從發出 `connect()` 到系統回報 `STATE_CONNECTED` 的時間。
  - 即時計算 **平均值 (Avg)**、**最大/最小值 (Min/Max)** 與 **90th Percentile (P90)**。
  - 統計連線成功率。
  - 測試結束後輸出完整 KPI 摘要。
- 支援不同測試音色
- 可複製 log
- 可清空 log

### 3. Media Control
Media Control 頁面用來測試 AVRCP / 媒體鍵、自動化壓力測試、音量循環，以及左右聲道播放驗證。

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
- **Stereo Channel Check** card
  - `Left Only`
  - `Right Only`
  - `L-R Alternating`
  - `Stop Test`
- **Automation Stress Settings** card
  - **Volume Cycle**
    - interval
    - min %
    - max %
    - `Start Volume Cycle`
  - **Rapid Commands**
    - loop count 與 `Non-stop`
    - base interval
    - random range
    - Play/Pause / Next / Prev / Stop 勾選項
    - `Start Rapid Commands`
    - `Stop Automation`
- log card

功能：
- 發送標準媒體鍵事件
- 直接調整系統媒體音量
- 支援以語音檔做左右聲道與交替播放驗證
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
- 這個頁面可透過側邊導覽列 (Side Drawer) 進入。

### 5. Stability Monitor
Stability Monitor 頁面用來做背景式電量、RSSI、音訊路由與 phone-side glitch 長時間記錄。

畫面區塊：
- **目前狀態** 卡片
  - 目前電量百分比
  - 目前 RSSI（dBm）
  - 目前 glitches 計數
  - 基於 sliding RSSI window 的 RF quality score
  - 非 silent 監測模式下的 acoustic continuity uptime
  - 非預期 audio route change 計數
  - 目前目標裝置
- **Logger Settings** 卡片
  - interval input
  - `Enable Audio Quality Monitor`
  - `Silent Mode (for Spotify/Music)`
  - buffer strategy selector
  - `Start Monitoring`（啟動 Foreground Service）
  - `Stop Monitoring`
- **Stability Log** 卡片
  - 含時間戳的電量、RSSI、route 與 glitch 記錄輸出
  - `Clear`

功能：
- **自動 CSV 記錄**：所有的電量與 RSSI 數據都會自動存入 `Downloads/BT_Android_Agent_Logs/StabilityLog_YYYYMMDD.csv`。
- **Glitches Detection**：透過 Android `AudioTrack` underrun count 觀察 phone buffer 端是否發生掉音風險。
- **Foreground Service**：透過 `BatteryLoggingService` 在 app 退到背景或螢幕關閉後仍持續記錄。
- **Audio Route Tracking**：可記錄目前是 `BT_A2DP`、`BT_SCO` 或 `INTERNAL_SPEAKER`。
- **RF Quality Score**：以最近 RSSI 平均值、最低值與波動量計算 sliding-window 分數，用來觀察不一定會造成手機端 underrun 的連線不穩。
- **Acoustic Continuity Uptime**：在非 silent audio monitor 模式下，透過麥克風回錄 440Hz monitor tone，統計實際聲學輸出 uptime 與 lost events。
- **Unexpected Route Change Counter**：統計監測期間 audio route 轉換次數，用來標記播放路徑不穩定。
- **RSSI 追蹤**：會先嘗試以 GATT 讀取 RSSI，必要時再退回短暫 discovery 方式補抓。
- **事件監控**：會即時記錄 ACL connected / disconnected 事件。
- **電量備援路徑**：會先嘗試平台 battery API，若裝置有提供 BLE GATT Battery Service，也會用它做補讀。
- 適合做長時間放電 / 充電曲線與訊號穩定度追蹤

### 6. Acoustic
Acoustic 頁面提供立體聲喇叭對麥克風 loopback 檢查，透過左右聲道參考音與即時麥克風分析來判斷聲道輸出是否正常。

畫面區塊：
- **Tone Generator Control** 卡片
  - 模式選擇：
    - `Normal`
    - `Swap`
    - `L-Only`
    - `R-Only`
  - `Start Manual`
  - `Auto Diag`
- **Microphone Analysis** 卡片
  - 輸入電平 progress bar
  - 左聲道狀態與 magnitude 顯示
  - 右聲道狀態與 magnitude 顯示
- **Event Log** 卡片
  - 含時間戳的偵測事件紀錄

功能：
- 透過 `AudioTrack` 連續輸出立體聲測試音。
- Normal 模式使用 1kHz 作為左聲道參考、2kHz 作為右聲道參考。
- 支援 **SWAP** 模式，刻意對調左右參考頻率，用來驗證聲道互換情境。
- 支援 `L-Only` 與 `R-Only` 單聲道隔離測試。
- `Auto Diag` 會自動輪跑 left-only、right-only、normal、swapped-left-only、swapped-right-only 與 full swap 檢查。
- 透過 `AudioRecord` 擷取麥克風訊號。
- 使用 Goertzel 演算法即時估算 1kHz 與 2kHz 成分強度。
- 顯示 `OK`、`SWAP OK` 或 `LOST` 狀態切換，並把變化寫入 log。
- 長按 acoustic log 可切換 advanced metrics 顯示，包含可取得時的 A2DP codec 快取資訊。
- 離開頁面時會自動停止播放與監聽。

### 7. Volume Linearity
音量線性度頁面用來自動化檢查 Android 標準 16 階媒體音量（0-15）的增益一致性。

畫面區塊：
- **Test Controls** 卡片
  - `Start Linearity Test`
  - `Stop`
- **Progress** 卡片
  - 目前步階（0-15）
  - 進度條
  - 最後量測到的 Magnitude
- **Test Results & Log** 卡片
  - 各階量測結果紀錄

功能：
- 自動從音量 0 增加到 15。
- 每一階播放 2 秒 1kHz 測試音。
- 利用麥克風即時紀錄每一階的 Magnitude。
- 測試結束後輸出摘要，方便檢查是否有「某兩階音量一樣大」或「增益跳躍不平滑」的問題。

### 8. Audio Latency
音訊延遲頁面用來量測從手機 `AudioTrack` 改變頻率的那一刻，到麥克風 `AudioRecord` 偵測到頻率改變之間的端到端延遲。

畫面區塊：
- **Test Controls** 卡片
  - `Start Latency Test`
  - `Stop`
- **量測結果**
  - 5 輪測試後的平均延遲數值
- **Event Log** 卡片
  - 每一輪的詳細量測結果與時間戳

功能：
1. 自動執行 **連續 5 輪** 量測，以確保統計上的準確性。
2. 每一輪流程：
    - 先播放穩定的 **1kHz** 參考音，確保藍牙 A2DP 鏈路處於活躍狀態。
    - 無縫切換頻率至 **2kHz** 並記錄精確的系統時間戳。
    - 利用 Goertzel 演算法偵測麥克風收到的 2kHz 訊號。
    - 計算該輪的延遲時間。
3. 5 輪結束後，自動計算並顯示 **平均延遲 (Average Latency)**。
4. 這種多輪量測法有助於過濾掉系統瞬時的抖動 (Jitter)，提供更可靠的藍牙音訊路徑評估數據。

### 9. Audio Clock Drift
Audio Clock Drift 頁面用來透過 1kHz 聲學 loopback 量測手機與藍牙裝置之間的時鐘漂移。

畫面區塊：
- **Main Display**
  - 即時 ppm 數值
  - 偵測頻率與 acoustic method 資訊
  - 累積 offset 與 duration 摘要
  - 最近趨勢列表
- **Analysis Window**
  - `0.1s`
  - `0.5s`
  - `1.0s`
  - `2.0s`
- **Test Controls**
  - `Start Monitor`
  - `Stop`
- **Event Log**
  - 含時間戳的 drift monitor log

功能：
- 播放精準 1kHz 測試音並用麥克風回錄。
- 在 1kHz 附近做高解析搜尋，估算頻率偏移並換算成 PPM。
- 累積整段測試過程中的 offset（ms）。
- 當 SNR 低於 10 dB 時，會提示環境噪音或擺位不佳。

### 10. Log Explorer
Log Explorer 提供 app 內集中管理結構化測試日誌的入口。

畫面區塊：
- log 檔案列表（顯示內部持久化輸出的 CSV）
- `Refresh List`
- `Open Folder`

功能：
- 掃描 `Downloads/BT_Android_Agent_Logs/` 內的 CSV 檔案。
- **Quick Preview**：點擊檔案後會跳出預覽視窗，以易讀的 code-style 方式顯示最新 200 行內容。
- **Share**：可透過分享按鈕把 log 檔送到 Android share sheet。
- 針對錯誤快照或 crash log 會以警示 icon 強調顯示。

## 導頁保護機制
如果目前頁面正在執行測試，切換側邊導覽列頁面時，app 會先詢問是否停止測試，再切換頁面，避免誤切頁造成測試中斷。

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
此專案已加入可產生測試 APK 的 GitHub Actions workflow：
- workflow 檔案：`.github/workflows/build-test-apk.yml`
- 觸發方式：
  - 透過 `workflow_dispatch` 手動執行
  - push 到 `main` 時自動執行
  - push 符合 `v*` 的 tag 時自動執行
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
- `ACCESS_COARSE_LOCATION`
- `MODIFY_AUDIO_SETTINGS`
- `RECORD_AUDIO`
- `BLUETOOTH_ADVERTISE`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `FOREGROUND_SERVICE_MICROPHONE`
- `POST_NOTIFICATIONS`

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
cd /Users/sam/code/BT_Android_Agent2
./gradlew :app:assembleDebug
```

## 桌面工具用法
### ADB Summary Script
```bash
cd /Users/sam/code/BT_Android_Agent2

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
cd /Users/sam/code/BT_Android_Agent2
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
- App 版本：`0.00.09`
