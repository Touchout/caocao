"""把遊戲目錄裡的 LS 封包全部拆到 out/，並匯出 Data.e5 的武將/道具名稱。

用法：python3 tools/export_all.py <遊戲目錄> [out 目錄]
"""
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ls12  # noqa: E402

SIGNATURES = [
    (b"BM", ".bmp"),
    (b"\xff\xd8\xff", ".jpg"),
    (b"\x89PNG", ".png"),
]

# Data.e5 各區段：(項目索引, 名稱, 每筆大小)
DATA_TABLES = [
    (0, "characters", 32),
    (1, "items", 25),
]


def guess_ext(data):
    for sig, ext in SIGNATURES:
        if data.startswith(sig):
            return ext
    return ".bin"


def export_archives(game, out):
    summary = {}
    for path in sorted(glob.glob(os.path.join(game, "*.e5")) + glob.glob(os.path.join(game, "E5", "*.e5"))):
        with open(path, "rb") as f:
            if not ls12.is_ls(f.read(4)):
                continue
        rel = os.path.relpath(path, game)
        dst = os.path.join(out, "archives", os.path.splitext(rel)[0])
        os.makedirs(dst, exist_ok=True)
        counts = {}
        items = ls12.read_archive(path)
        for i, _, _, _, data in items:
            ext = guess_ext(data)
            counts[ext] = counts.get(ext, 0) + 1
            with open(os.path.join(dst, f"{i:04d}{ext}"), "wb") as f:
                f.write(data)
        summary[rel] = {"entries": len(items), "types": counts}
        print(f"{rel:20} {len(items):5d} 項 {counts}")
    return summary


def read_name(rec):
    return rec[:rec.index(b"\0") if b"\0" in rec else len(rec)].decode("gbk", "replace")


def export_data(game, out):
    items = ls12.read_archive(os.path.join(game, "Data.e5"))
    for idx, name, stride in DATA_TABLES:
        blob = items[idx][4]
        rows = []
        for n in range(len(blob) // stride):
            rec = blob[n * stride:(n + 1) * stride]
            rows.append({"id": n, "name": read_name(rec[:16]), "raw": rec[16:].hex()})
        with open(os.path.join(out, f"data_{name}.json"), "w", encoding="utf-8") as f:
            json.dump(rows, f, ensure_ascii=False, indent=1)
        print(f"Data.e5[{idx}] → data_{name}.json（{len(rows)} 筆）")


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 1
    game = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "out")
    os.makedirs(out, exist_ok=True)
    summary = export_archives(game, out)
    export_data(game, out)
    with open(os.path.join(out, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
