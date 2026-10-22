#!/usr/bin/env bash
# 從 Google Drive 下載遊戲壓縮檔並解壓到 game/。
# 檔案必須先設成「知道連結的人都能檢視」。
# 用法：tools/fetch_game.sh <drive_file_id> [password]
set -euo pipefail
ID="${1:?需要 Google Drive file id}"
PW="${2:-123456}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/dl" "$ROOT/game"
ARCHIVE="$ROOT/dl/game.7z"

if [ ! -s "$ARCHIVE" ]; then
  # 大檔案會先出現「無法掃描病毒」確認頁，usercontent 端點加 confirm=t 可直接略過
  curl -fL --retry 4 -o "$ARCHIVE" \
    "https://drive.usercontent.google.com/download?id=${ID}&export=download&confirm=t"
fi

if head -c 6 "$ARCHIVE" | grep -q '<'; then
  echo "下載到的是 HTML，不是 7z：檔案可能還沒開放連結分享" >&2
  rm -f "$ARCHIVE"
  exit 1
fi

python3 -m pip install -q py7zr
python3 - "$ARCHIVE" "$ROOT/game" "$PW" <<'PY'
import sys, py7zr
src, dst, pw = sys.argv[1:4]
with py7zr.SevenZipFile(src, mode="r", password=pw) as z:
    z.extractall(path=dst)
PY
echo "解壓完成：$ROOT/game"
