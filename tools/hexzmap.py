"""戰場地形（Hexzmap.e5）匯出。

每筆：u8 寬, u8 高（單位 16 px，格數 = 值 / 3），後面每格 1 byte 地形編號，逐列排列。
第 N 筆對應 MAP/mNNN.jpg，一格 48 × 48 px。
地形名稱表在 Ekd5.exe 裡（GBK 字串，\\0 分隔），見 TERRAIN_NAMES。

用法：
  python3 tools/hexzmap.py <遊戲目錄> <out 目錄>               匯出全部 JSON
  python3 tools/hexzmap.py <遊戲目錄> <out 目錄> --overlay N   另外把第 N 張地圖疊上地形標示存成 PNG
"""
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ls12  # noqa: E402

TILE = 48
TERRAIN_NAMES = [
    "平原", "草原", "树林", "荒地", "山地", "岩山", "山崖", "雪原", "桥梁", "浅滩",
    "沼泽", "池塘", "小河", "大河", "栅栏", "城墙", "城内", "城门", "城池", "关隘",
    "鹿砦", "村庄", "兵营", "民居", "宝物库", "水池", "火", "船", "祭坛", "地下",
    "地雷", "战壕", "掩体", "工事",
]


def read_maps(game):
    maps = []
    for i, _, _, _, d in ls12.read_archive(os.path.join(game, "Hexzmap.e5")):
        w, h = d[0] // 3, d[1] // 3
        cells = d[2:2 + w * h]
        maps.append({"id": i, "width": w, "height": h,
                     "rows": [list(cells[y * w:(y + 1) * w]) for y in range(h)]})
    return maps


def map_image(game, n):
    for path in glob.glob(os.path.join(game, "MAP", "*")):
        if os.path.basename(path).lower() == f"m{n:03d}.jpg":
            return path
    return None


def overlay(game, m, path):
    from PIL import Image, ImageDraw, ImageFont
    img = Image.open(map_image(game, m["id"])).convert("RGB")
    draw = ImageDraw.Draw(img, "RGBA")
    font = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 14)
    for y, row in enumerate(m["rows"]):
        for x, t in enumerate(row):
            x0, y0 = x * TILE, y * TILE
            draw.rectangle([x0, y0, x0 + TILE - 1, y0 + TILE - 1], outline=(255, 255, 255, 70))
            name = TERRAIN_NAMES[t] if t < len(TERRAIN_NAMES) else str(t)
            draw.rectangle([x0 + 2, y0 + 2, x0 + 2 + 15 * len(name), y0 + 19], fill=(0, 0, 0, 140))
            draw.text((x0 + 3, y0 + 2), name, font=font, fill=(255, 255, 255, 230))
    img.save(path)


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 1
    game, out = argv[1], argv[2]
    os.makedirs(out, exist_ok=True)
    maps = read_maps(game)
    with open(os.path.join(out, "hexzmap.json"), "w", encoding="utf-8") as f:
        json.dump({"terrain_names": TERRAIN_NAMES, "maps": maps}, f, ensure_ascii=False)
    print(f"{len(maps)} 張地形 → {out}/hexzmap.json")
    if "--overlay" in argv:
        n = int(argv[argv.index("--overlay") + 1])
        path = os.path.join(out, f"terrain_m{n:03d}.png")
        overlay(game, maps[n], path)
        print(f"疊圖 → {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
