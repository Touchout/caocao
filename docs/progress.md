# 進度

- [x] 取得並解壓遊戲檔：`tools/fetch_game.sh 1r1xyIhxh8UlyAatUOKFVJOamnJMxnw_6`
- [x] 階段 1：盤點檔案（`docs/formats.md`）
  - [x] LS12 封包解析／解壓（`tools/ls12.py`）
  - [x] 封包全部匯出到 `out/archives/`（`tools/export_all.py`）
- [ ] 階段 2：逆向格式
  - [x] EEX 劇本：結構、參數編碼、opcode 參數型別表，249 個劇本全部反組譯（`tools/eex.py` → `out/eex/`）
  - [x] Hexzmap 地形與名稱對照，疊圖驗證（`tools/hexzmap.py`）
  - [~] Data.e5 武將／道具欄位：五維、等級、兵種已確認，其餘是推測（`out/data_*.json`）
  - [ ] Data.e5 項 2–5（商店？兵種表、地形修正、策略）
  - [~] 各參數型別碼的意義：武將、物品、地形、能力、比較／運算子、AI 方針等已確認（`tools/eex_types.json`），其餘待查
  - [ ] DT/Meff 策略動畫、Mcall、Pmap、Unit_* 裡的非 BMP 項目
- [ ] 階段 3：從 exe 整理戰鬥公式、AI、存檔格式（`docs/battle.md`）
  - [x] 工具：xref、callgraph、angr 反編譯（`tools/re/`）
  - [x] 特效名稱表、兵種名稱表（`out/exe_tables.json`）
  - [x] 命中判定、基礎命中率
  - [~] 物理傷害：主流程已讀，後段 3 個修正函式待讀
  - [ ] 能力值計算、特效判定、策略、暴擊／連擊／反擊、相剋、地形、經驗、AI
