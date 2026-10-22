# 進度

- [x] 取得並解壓遊戲檔：`tools/fetch_game.sh 1r1xyIhxh8UlyAatUOKFVJOamnJMxnw_6`
- [x] 階段 1：盤點檔案（`docs/formats.md`）
  - [x] LS12 封包解析／解壓（`tools/ls12.py`）
  - [x] 封包全部匯出到 `out/archives/`，Data.e5 武將／道具名稱輸出成 JSON（`tools/export_all.py`）
- [ ] 階段 2：逆向格式
  - [ ] Data.e5 武將／道具數值欄位、項 2–5
  - [ ] EEX 腳本 opcode 表 → 反組譯器
  - [ ] Hexzmap 地形編號對照
  - [ ] DT/Meff 策略動畫、Mcall、Pmap、Unit_* 裡的非 BMP 項目
