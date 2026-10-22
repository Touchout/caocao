# 曹操傳 Android 移植研究

## 目標
在本機/VM 環境研究《三國志曹操傳》（目前手上的版本是社群改版「新改曹操傳 6.6 修正版」）的檔案格式與遊戲邏輯，
再寫一個 Android 版引擎，執行時讀取玩家自己的遊戲資料檔。

## 規則
- **成品（Android App）的所有文字一律用繁體中文。** 遊戲原始資料是 GBK、簡繁混用，顯示前要轉成繁體（台灣用字）。
- 遊戲原始檔（exe、*.e5、*.eex、圖片、音樂）只放在 `game/`，**不進 git**（見 `.gitignore`）。repo 只放工具、文件和引擎程式碼。
- 匯出結果放 `out/`，同樣不進 git。
- 每分析完一種格式，就更新 `docs/formats.md`：檔名、用途、header、record 結構、已知/未知欄位、驗證方式。
- 跨 Session 的進度記在 `docs/progress.md`。

## 取得遊戲檔
`tools/fetch_game.sh <google_drive_file_id> [密碼]`：Drive 檔案需先開「知道連結的人都能檢視」。

## 引擎
`engine/`（Kotlin）：`core` 純 JVM 邏輯、`app` Android、`desktop` 預覽工具。建置方式見 `engine/README.md`。
VM 上的遊戲目錄有 ASCII 符號連結 `game/ccz66`，跑 Gradle 時用它。

## 階段
1. 盤點檔案：分類圖像、地圖、角色資料、事件腳本、音樂，各自用什麼格式。
2. 逆向格式：寫解析/匯出工具（`tools/`，Python），圖片轉 PNG、資料轉 JSON、腳本轉可讀指令序列。
3. 拆解遊戲邏輯：從 exe 和腳本整理出戰鬥公式、AI、事件系統、存檔格式。
4. 建引擎：Godot 或 Kotlin 寫 Android 版，執行時讀原始資料檔，UI 改成觸控操作。
5. 驗證：逐關比對原版行為。

每完成一個階段，先把成果交給使用者確認再往下做。
