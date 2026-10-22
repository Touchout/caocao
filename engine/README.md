# Android 引擎

| 模組 | 內容 |
|---|---|
| `core` | 純 Kotlin（JVM）：LS12 封包、Data.e5、Hexzmap、EEX 劇本解析、戰場初始配置、簡轉繁 |
| `app` | Android App（Canvas 繪製）。不附任何原版素材，執行時讀玩家自己的遊戲檔 |
| `desktop` | 開發用：在沒有 Android 裝置的環境把戰場畫成 PNG，驗證 core |

## 建置

需要 JDK 17 以上、Android SDK（platform 35、build-tools 35），`local.properties` 寫 `sdk.dir=`。

```sh
./gradlew :app:assembleDebug                        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test -Pgame=<遊戲目錄>                # 有遊戲檔時會跑整合測試（249 個劇本全部解析等）
./gradlew :desktop:run --args="<遊戲目錄> 2 out.png 119"   # S_002 戰場預覽，顯示 119 號武將的資料
```

遊戲目錄路徑或檔名有中文時，JVM 要用 UTF-8 locale（`LC_ALL=C.UTF-8`）；路徑本身最好用 ASCII（可以建符號連結）。
Maven Central 常回 429，`settings.gradle.kts` 先走 Google 的 Maven Central 鏡像。

## 安裝到手機

1. 安裝 APK，打開一次（建立資料夾）。
2. 把遊戲目錄的內容整個複製到手機的 `Android/data/ccz.android/files/game/`（Ekd5.exe、Data.e5、E5/、MAP/、RS/… 放在這一層）。
   電腦用 USB 連手機（檔案傳輸模式）複製最方便。
3. 再打開 App，會列出所有戰場關卡。

## 目前進度（v0.1 戰場檢視器）

- 關卡清單（名稱來自 Imsg.e5）
- 戰場地圖、地形、敵軍／友軍／我軍出場格與強制出場武將、伏兵（半透明）
- 拖曳、雙指縮放；點武將看兵種、等級、五維、地形、AI 方針；全部繁體
- 簡化：只做靜態掃描，不執行劇本、不判斷條件；武將等級還是 Data.e5 的原始值；人物圖固定正面站立
