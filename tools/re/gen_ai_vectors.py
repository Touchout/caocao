"""方法 1：AI 行動評估跟引擎比對的資料。

每關先在模擬器跑 scene 0（含開戰初始化）。每一組隨機改地圖上單位的武將資料與單位狀態（同 gen_damage_vectors.py），
挑一個在場單位，隨機設定每格的攻擊標記（緩衝區 0x48B0E0 +0）、加權（+0x640）與 [0x4B2C38]，然後：
  1. 移動範圍搜尋 0x438484 模式 0（建花費表），在幾個到得了的格呼叫物理攻擊得分 0x438914、地形價值 0x452E18，
     記下回傳值、目標 [0x4B2C30]、路徑長度 [0x500C48] 與路徑、擴散記錄 0x500E49…、亂數狀態；
  2. 還原標記與加權後跑整個模式 4 搜尋（每格評估 0x43A55E），記下最佳結果 0x4B2C20…、每格得分、策略清單 0x4AC808。
AiTest 用同樣的狀態比對。輸出放 out/（不進 git）。

用法：python3 tools/re/gen_ai_vectors.py <遊戲目錄> [輸出檔，預設 out/ai_vectors.json] [每關組數] [關卡,…]
"""
import json
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trace_original as t  # noqa: E402
from gen_range_vectors import call  # noqa: E402

SEARCH = 0x438484
MOVE_POINTS = 0x43F74F
PHYSICAL = 0x438914
TERRAIN_VALUE = 0x452E18
SEED = 0x48ECD4
ARG = t.HEAP + 0x3E0000     # 放指標參數（位置）的地方
STAGES = [0, 2, 8, 14, 22, 40, 52, 70, 99, 118]


def randomize(rnd, units, occ, cdata, onmap, classes, w, h, a):
    changed = set()
    for s in onmap:
        u = s * 0x30
        ch = struct.unpack_from("<H", units, u)[0] & 0xFFF
        c = ch * 0x48
        if rnd.random() < 0.5:
            cdata[c + 0x2C] = rnd.randrange(1, 60)
            for k in range(5):
                struct.pack_into("<H", cdata, c + 0x11 + 2 * k, rnd.randrange(20, 250))
                cdata[c + 0x21 + k] = rnd.randrange(20, 120)
            if rnd.random() < 0.3:
                cdata[c + 0x2B] = rnd.choice(classes)
            for k in range(3):
                if rnd.random() < 0.4:
                    cdata[c + 0x2E + 3 * k] = rnd.choice([0xFF, rnd.randrange(256)])
                    cdata[c + 0x2F + 3 * k] = rnd.randrange(12)
            changed.add(ch)
        if rnd.random() < 0.4:
            for k in range(6):
                units[u + 0x18 + k] = rnd.choice([3, 3, 1, 5, 6])
            units[u + 0x1E] = rnd.choice([0, 0, 1, 4, 0x10, 0x40])
            struct.pack_into("<I", units, u + 0x10, rnd.randrange(1, 400))
        if rnd.random() < 0.3:
            units[u + 0x0E] = rnd.choice([0, 1, 2, 3, 4])
    # 把幾個單位搬到 a 附近的空格，讓敵我靠近
    ax, ay = units[a * 0x30 + 6], units[a * 0x30 + 7]
    for s in rnd.sample([s for s in onmap if s != a], min(len(onmap) - 1, 6)):
        u = s * 0x30
        for _ in range(10):
            x, y = ax + rnd.randrange(-5, 6), ay + rnd.randrange(-5, 6)
            if not (0 <= x < w and 0 <= y < h):
                continue
            if occ[y * w + x] == 0xFF:
                occ[units[u + 7] * w + units[u + 6]] = 0xFF
                units[u + 6], units[u + 7] = x, y
                occ[y * w + x] = s
                break
    return changed


def main(argv):
    game = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join(os.path.dirname(__file__), "../../out/ai_vectors.json")
    per = int(argv[3]) if len(argv) > 3 else 40
    stages = [int(x) for x in argv[4].split(",")] if len(argv) > 4 else STAGES
    rnd = random.Random(43555)
    result = []
    for stage in stages:
        d = t.Driver(os.path.join(game, "Ekd5.exe"), os.path.join(game, "RS", "S_%03d.eex" % stage), [0])
        d.run_scene(0)
        mu = d.orig.mu
        w, h = mu.mem_read(0x4B422C, 2)
        buf = struct.unpack("<I", mu.mem_read(0x48B140 + 0xC, 4))[0]
        grid = buf + 0xC80
        scratch = struct.unpack("<I", mu.mem_read(0x48B0E0 + 0xC, 4))[0]
        chars = struct.unpack("<I", mu.mem_read(t.CHARS_PTR, 4))[0]
        base_units = bytes(mu.mem_read(t.UNITS, 250 * 0x30))
        base_grid = bytes(mu.mem_read(grid, 0x640))
        base_chars = bytes(mu.mem_read(chars, 1024 * 0x48))
        onmap = [s for s in range(250) if base_units[s * 0x30 + 0x0C] == 2 and struct.unpack_from("<H", base_units, s * 0x30)[0] != 0xFFFF]
        if len(onmap) < 2:
            print(f"S_{stage:03d}：地圖上不到兩個單位，略過", file=sys.stderr)
            continue
        classes = sorted({base_chars[(struct.unpack_from("<H", base_units, s * 0x30)[0] & 0xFFF) * 0x48 + 0x2B] for s in onmap})
        groups = []
        for g in range(per):
            units = bytearray(base_units)
            occ = bytearray(base_grid)
            cdata = bytearray(base_chars)
            a = rnd.choice(onmap)
            changed = randomize(rnd, units, occ, cdata, onmap, classes, w, h, a)
            mu.mem_write(t.UNITS, bytes(units))
            mu.mem_write(grid, bytes(occ))
            mu.mem_write(chars, bytes(cdata))
            tests = []
            for _ in range(1):
                ua = t.UNITS + a * 0x30
                n = w * h
                marks = bytes(rnd.choice([0xFF, 0xFF, 0xFF, rnd.choice(onmap)]) for _ in range(n))
                weights = bytes(rnd.choice([0] * 8 + [rnd.randrange(1, 40)]) for _ in range(n))
                avoid = 0xFFFF if rnd.random() < 0.7 else rnd.randrange(w) | (rnd.randrange(h) << 8)
                seed = rnd.randrange(1 << 32)

                def setup():
                    mu.mem_write(t.UNITS, bytes(units))
                    mu.mem_write(scratch, marks)
                    mu.mem_write(scratch + 0x640, weights)
                    mu.mem_write(0x4B2C38, struct.pack("<H", avoid))
                    mu.mem_write(SEED, struct.pack("<I", seed))
                    mu.mem_write(0x500E49, b"\0" * 0x80)   # 擴散記錄（只給動畫用），比對前清 0
                    mu.mem_write(0x500C48, b"\0")          # 路徑長度（沒有目標時不會重算）
                    mu.mem_write(buf + 0x1900, b"\0" * 0x40)
                setup()
                mp = call(mu, MOVE_POINTS, ua) & 0xFF
                call(mu, SEARCH, ua, ua + 6, mp, 0, 0xFF, 0xFF, 1)
                cost = bytes(mu.mem_read(buf + 0x12C0, n))
                reach = [i for i in range(n) if cost[i] != 0xFF]
                tiles = []
                for i in rnd.sample(reach, min(len(reach), 6)):
                    p = (i % w) | ((i // w) << 8)
                    mu.mem_write(ARG, struct.pack("<I", p))
                    r = call(mu, PHYSICAL, ua, ARG)
                    tv = call(mu, TERRAIN_VALUE, ua, ARG)
                    plen = mu.mem_read(0x500C48, 1)[0]
                    tiles.append({"pos": p, "ok": r is not None, "eax": r, "target": mu.mem_read(0x4B2C30, 1)[0],
                                  "plen": plen, "path": bytes(mu.mem_read(buf + 0x1900, 0x40)).hex(),
                                  "spread": bytes(mu.mem_read(0x500E49, 0x80)).hex(), "terrain": tv,
                                  "seed": struct.unpack("<I", mu.mem_read(SEED, 4))[0]})
                # 整個模式 4 搜尋
                setup()
                r = call(mu, SEARCH, ua, ua + 6, mp, 4, 0xFF, 0xFF, 1)
                full = {"ok": r is not None, "eax": r,
                        "best": bytes(mu.mem_read(0x4B2C20, 0x30)).hex(),
                        "scores": bytes(mu.mem_read(scratch + 0x640, n)).hex(),
                        "cost": bytes(mu.mem_read(buf + 0x12C0, n)).hex(),
                        "strategies": bytes(mu.mem_read(0x4AC808, 0x40)).hex(),
                        "seed": struct.unpack("<I", mu.mem_read(SEED, 4))[0]}
                tests.append({"unit": a, "marks": marks.hex(), "weights": weights.hex(), "avoid": avoid, "seed": seed,
                              "mp": mp, "tiles": tiles, "full": full})
            groups.append({"units": units.hex(), "occupant": occ.hex(),
                           "chars": {str(ch): cdata[ch * 0x48:ch * 0x48 + 0x48].hex() for ch in changed}, "tests": tests})
        result.append({"stage": stage, "groups": groups})
        bad = sum(1 for gr in groups for x in gr["tests"] for tl in x["tiles"] if not tl["ok"])
        print(f"S_{stage:03d}：{per} 組，物理得分沒跑完 {bad}，模式 4 沒跑完 "
              f"{sum(1 for gr in groups for x in gr['tests'] if not x['full']['ok'])}", file=sys.stderr)
    with open(out, "w") as f:
        json.dump(result, f)
    print(out)


if __name__ == "__main__":
    main(sys.argv)
