# 進度與規劃

> 新 session 先讀這份和 `CLAUDE.md`，再跑 `tools/setup_vm.sh` 重建環境。
> 每完成一個步驟就更新這份文件並 commit + push：session 可能隨時換新，沒寫進來的進度等於沒做。

## 目前狀態（最後更新：2026-10-22）

- 重現對象：**新改曹操傳 6.6 修正版**（使用者決定，不是原版）。
- 成品：Android App（Kotlin，`engine/`），執行時讀玩家自己的遊戲檔，所有文字顯示繁體（台灣用字）。
- 使用者已在手機上測過 **v0.2**：選遊戲資料夾匯入、關卡清單、戰場檢視都正常。
- **進行中：v0.3 移動**（見下方「進行中」）。

## 路線圖

| 版本 | 內容 | 狀態 |
|---|---|---|
| v0.1 | 戰場檢視器：關卡清單、地圖、開戰配置、點選查看資料 | 完成 |
| v0.2 | 選遊戲資料夾自動匯入（SAF） | 完成，使用者實機測過 |
| v0.3 | 移動：兵種移動力、地形移動消耗、移動範圍、敵軍阻擋、移動動畫與朝向 | **進行中** |
| v0.4 | 劇本直譯器：變數、條件、子事件、對話（頭像、逐句點擊）、敵軍等級調整、伏兵、勝敗條件 | 未開始 |
| v0.5+ | 戰鬥：先接已解的命中／物理傷害／反擊，邊逆向邊補（能力值、策略、暴擊連擊、相剋、255 種特效、AI、經驗） | 未開始 |
| 之後 | 回合流程、R 場景（城內劇情）、商店、存檔、音樂音效、關卡串接 | 未開始 |

做法：原型和逆向同步進行（使用者同意）。每版做完出 APK 給使用者實機測試。

## 進行中：v0.3 移動

1. [ ] 解 Data.e5 第 3 項（兵種資料，2800 bytes）：找出每個兵種的移動力。
   線索：社群 Data 修改器設定 `Game_Force_Length 0x1B`；MOD 文件 `6.5数据转移.txt` 說「部队 9DB4 2800」。
   80 兵種 × 35 bytes = 2800。
2. [ ] 解 Data.e5 第 4 項（地形，2400 bytes）：80 兵種 × 30 地形，推測是移動消耗或地形適性（%）。
   跟 exe 的移動範圍計算對照驗證（找讀取這張表的函式）。
3. [ ] core：移動範圍（BFS／Dijkstra，敵軍格不能穿過、友軍可穿過但不能停；確切規則要看 exe）。
4. [ ] core 測試 + desktop 預覽畫出移動範圍。
5. [ ] app：點我軍 → 顯示範圍 → 點目標格移動；走路動畫；依朝向換圖（mov 圖 11 格的對應要確認）。
6. [ ] 出 v0.3 APK，更新這份文件與 `engine/README.md`。

## 決策紀錄

- 戰場地圖編號由劇本 0x27 背景显示（模式 3）的型別 0x15 參數決定，不一定等於關卡編號。
- 座標順序 (x, y)。人物圖：0–239 兵種通用圖（兵種×3 + 陣營 0 我/1 友/2 敵），240 起武將專屬圖（240 + S形象 − 1），
  劇本 0x75 可以改形象。
- EEX 每個 opcode 的參數型別表是用 Unicorn 模擬 exe 量出來的（`tools/re/eexemu.py`），結果在 `tools/eex_signatures.json`，
  引擎用的是 `engine/core/src/main/resources/ccz/eex/signatures.txt`。
- 簡轉繁用 OpenCC s2twp 字典（Apache 2.0），放在 core resources。
- Maven Central 常回 429：Gradle 先走 Google 的 Maven Central 鏡像。VM 沒有 KVM，不能跑 Android 模擬器，
  用 `engine/desktop` 把畫面畫成 PNG 驗證。

## 已完成

- [x] 階段 1：盤點檔案（`docs/formats.md`）
  - [x] LS12 封包解析／解壓（`tools/ls12.py`）
  - [x] 封包全部匯出到 `out/archives/`（`tools/export_all.py`）
- [~] 階段 2：逆向格式
  - [x] EEX 劇本：結構、參數編碼、opcode 參數型別表，249 個劇本全部反組譯（`tools/eex.py` → `out/eex/`）
  - [x] Hexzmap 地形與名稱對照，疊圖驗證（`tools/hexzmap.py`）
  - [x] 關卡名稱（Imsg.e5 0xC350，每筆 200 bytes，前 100 關）
  - [~] Data.e5 武將／道具欄位：五維、等級、兵種、R 形象已確認，其餘是推測（`out/data_*.json`）
  - [ ] Data.e5 項 2–5（商店？兵種表、地形表、策略）
  - [~] 參數型別碼：武將、物品、地形、能力、比較／運算子、AI 方針、背景模式／地圖編號已確認（`tools/eex_types.json`）
  - [ ] DT/Meff 策略動畫、Mcall、Pmap、Unit_* 裡的非 BMP 項目
- [~] 階段 3：遊戲邏輯（`docs/battle.md`）
  - [x] 工具：xref、callgraph、disasm、angr 反編譯（`tools/re/`）
  - [x] 特效名稱表、兵種名稱表（`out/exe_tables.json`）
  - [x] 命中判定、基礎命中率
  - [~] 物理傷害：主流程已讀，後段 3 個修正函式待讀
  - [ ] 能力值計算、特效判定、策略、暴擊／連擊／反擊、相剋、地形、經驗、AI、存檔
- [~] 階段 4：Android 引擎（`engine/`，見 `engine/README.md`）
  - [x] core：LS12（隨用隨讀）、Data.e5、Hexzmap、EEX 解析（249 個劇本測試通過）、戰場初始配置、簡轉繁
  - [x] v0.1 戰場檢視器、v0.2 匯入
