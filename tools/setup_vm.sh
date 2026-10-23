#!/usr/bin/env bash
# 新的雲端 session（或新機器）重建開發環境。可以重複執行，已經有的會跳過。
#   1. 下載並解壓遊戲檔到 game/，建 ASCII 符號連結 game/ccz66
#   2. Python 套件：py7zr（解壓）、capstone / unicorn（反組譯、模擬）、pillow（出圖）
#   3. angr 反編譯器（獨立 venv ~/venv-re，系統 setuptools 裝不起來）
#   4. Android SDK（~/android-sdk）與 engine/local.properties
#   5. 產生 out/ 的匯出結果（封包、Data、EEX 反組譯、地形）
#   6. 產生方法 1 的標準答案（模擬器跑原版，含遊戲資料所以不進 git；沒有的話對應測試會略過）
# 用法：tools/setup_vm.sh [google_drive_file_id] [密碼]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DRIVE_ID="${1:-1r1xyIhxh8UlyAatUOKFVJOamnJMxnw_6}"
PW="${2:-123456}"
GAME_DIR_NAME="新改曹操傳6.6修正版"

echo "== Python 套件"
python3 -m pip install -q py7zr capstone unicorn pillow 2>&1 | grep -v WARNING || true

echo "== 遊戲檔"
if [ ! -d "$ROOT/game/$GAME_DIR_NAME" ]; then
  "$ROOT/tools/fetch_game.sh" "$DRIVE_ID" "$PW"
fi
ln -sfn "$GAME_DIR_NAME" "$ROOT/game/ccz66"
GAME="$ROOT/game/ccz66"

echo "== angr（~/venv-re）"
if [ ! -x "$HOME/venv-re/bin/python" ] || ! "$HOME/venv-re/bin/python" -c "import angr" 2>/dev/null; then
  python3 -m venv "$HOME/venv-re"
  "$HOME/venv-re/bin/pip" install -q --upgrade pip setuptools wheel
  "$HOME/venv-re/bin/pip" install -q angr capstone unicorn
fi

echo "== Android SDK（~/android-sdk）"
SDK="$HOME/android-sdk"
if [ ! -d "$SDK/platforms/android-35" ]; then
  mkdir -p "$SDK/cmdline-tools"
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    curl -sS -L -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-9862592_latest.zip
    rm -rf "$SDK/cmdline-tools/latest" "$SDK/cmdline-tools/cmdline-tools"
    unzip -q /tmp/clt.zip -d "$SDK/cmdline-tools" && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm /tmp/clt.zip
  fi
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0" "platform-tools" >/dev/null
fi
echo "sdk.dir=$SDK" > "$ROOT/engine/local.properties"

echo "== 匯出 out/"
python3 "$ROOT/tools/export_all.py" "$GAME" "$ROOT/out" >/dev/null
python3 "$ROOT/tools/hexzmap.py" "$GAME" "$ROOT/out" >/dev/null
python3 "$ROOT/tools/eex.py" "$GAME/Ekd5.exe" -o "$ROOT/out/eex" "$GAME"/RS/[RS]_*.eex

echo "== 方法 1 標準答案（out/expected_memory.bin、attr_vectors.json、stats_vectors.json）"
python3 "$ROOT/tools/re/dump_memory.py" "$GAME" "$ROOT/out/expected_memory.bin" >/dev/null
python3 "$ROOT/tools/re/gen_attr_vectors.py" "$GAME" "$ROOT/out/attr_vectors.json" >/dev/null
python3 "$ROOT/tools/re/gen_stats_vectors.py" "$GAME" "$ROOT/out/stats_vectors.json" >/dev/null

echo "== 完成。建置：cd engine && ./gradlew :core:test :app:assembleDebug -Pgame=$GAME"
