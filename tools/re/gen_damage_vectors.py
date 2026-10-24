"""方法 1：物理攻擊傷害（原版 0x43BC5A(目標單位, 估算, 旗標)，ecx = 攻擊單位）跟引擎比對的資料。

每關先在模擬器跑 scene 0（含開戰初始化）。每一組隨機改在地圖上單位的武將資料（等級、戰鬥能力、五維、兵種、裝備）、
單位狀態（能力狀態 +0x18..0x1D、健康 +0x1E、HP）與位置，挑不同方的兩個單位（一半讓它們相鄰），用兩種參數
呼叫原版，記下回傳的 eax。DamageTest 用同樣的狀態比對。輸出放 out/（不進 git）。

用法：python3 tools/re/gen_damage_vectors.py <遊戲目錄> [輸出檔，預設 out/damage_vectors.json] [每關組數] [關卡,…]
"""
import json
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trace_original as t  # noqa: E402
from gen_range_vectors import call  # noqa: E402

DAMAGE = 0x43BC5A
STAT_FNS = [0x43F5FF, 0x43F62B, 0x43F657, 0x43F683, 0x43F6AF]
SEED = 0x48ECD4      # 亂數 0x47FAE7 的狀態（LCG：× 0x41C64E6D + 0x3039，取 bit 16–30）
STAGES = [0, 2, 8, 14, 22, 40, 52, 70, 99, 118]


def main(argv):
    game = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join(os.path.dirname(__file__), "../../out/damage_vectors.json")
    per = int(argv[3]) if len(argv) > 3 else 20
    stages = [int(x) for x in argv[4].split(",")] if len(argv) > 4 else STAGES
    rnd = random.Random(43)
    result = []
    for stage in stages:
        d = t.Driver(os.path.join(game, "Ekd5.exe"), os.path.join(game, "RS", "S_%03d.eex" % stage), [0])
        d.run_scene(0)
        mu = d.orig.mu
        w, h = mu.mem_read(0x4B422C, 2)
        buf = struct.unpack("<I", mu.mem_read(0x48B140 + 0xC, 4))[0]
        grid = buf + 0xC80
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
            pairs = []
            for _ in range(20):
                a, b = rnd.sample(onmap, 2)
                if rnd.random() < 0.6:
                    # 搬到相鄰（夾擊、地形等跟位置有關）
                    ax, ay = units[a * 0x30 + 6], units[a * 0x30 + 7]
                    for dx, dy in rnd.sample([(1, 0), (-1, 0), (0, 1), (0, -1)], 4):
                        x, y = ax + dx, ay + dy
                        if 0 <= x < w and 0 <= y < h and occ[y * w + x] == 0xFF:
                            bu = b * 0x30
                            occ[units[bu + 7] * w + units[bu + 6]] = 0xFF
                            units[bu + 6], units[bu + 7] = x, y
                            occ[y * w + x] = b
                            break
                pairs.append((a, b, rnd.choice([0, 1]), rnd.choice([0, 1])))
            mu.mem_write(t.UNITS, bytes(units))
            mu.mem_write(grid, bytes(occ))
            mu.mem_write(chars, bytes(cdata))
            tests = []
            snapshot = bytes(mu.mem_read(t.UNITS, 250 * 0x30))
            for a, b, est, flag in pairs:
                # 分層比對：攻守雙方的 5 項戰鬥能力（0x414CCB 的函式表 0x440151 → 0x43F5FF…）
                stats = [[call(mu, f, t.UNITS + x * 0x30) for f in STAT_FNS] for x in (a, b)]
                seed = rnd.randrange(1 << 32)
                mu.mem_write(SEED, struct.pack("<I", seed))
                r = call(mu, DAMAGE, t.UNITS + a * 0x30, t.UNITS + b * 0x30, est, flag)
                after = bytes(mu.mem_read(t.UNITS, 250 * 0x30))
                tests.append({"attacker": a, "target": b, "args": [est, flag], "ok": r is not None, "eax": r,
                              "stats": stats, "seed": seed, "seed_after": struct.unpack("<I", mu.mem_read(SEED, 4))[0],
                              "changed": after != snapshot})
                mu.mem_write(t.UNITS, snapshot)
            groups.append({"units": units.hex(), "occupant": occ.hex(),
                           "chars": {str(ch): cdata[ch * 0x48:ch * 0x48 + 0x48].hex() for ch in changed}, "tests": tests})
        result.append({"stage": stage, "groups": groups})
        print(f"S_{stage:03d}：{per} 組，沒跑完 {sum(1 for gr in groups for x in gr['tests'] if not x['ok'])}", file=sys.stderr)
    with open(out, "w") as f:
        json.dump(result, f)
    print(out)


if __name__ == "__main__":
    main(sys.argv)
