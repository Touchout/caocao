"""方法 1：戰場條件指令（0x25、0x26、0x2E、0x36、0x3F、0x40、0x41…）跟原版比對的資料。

在模擬器跑 S_002 scene 0（含開戰初始化）當作基準狀態，之後每一組：隨機改回合（戰場物件 +2）、行動方（+0x10）、
單位的狀態／位置／HP／能力狀態，依單位重建佔位表；再用隨機參數呼叫原版的條件處理函式，記下結果（al）。
TestOpsTest 讀同一份資料：引擎跑同一關到同一點，套用每一組的狀態，逐筆比對。輸出放 out/（不進 git）。

用法：python3 tools/re/gen_test_vectors.py <遊戲目錄> [輸出檔，預設 out/test_vectors.json] [組數] [每組條數]
"""
import json
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trace_original as t  # noqa: E402
from eexemu import SCRIPT  # noqa: E402

BATTLE = 0x4B3D08
OPS = [0x3F, 0x40, 0x25, 0x26, 0x2E, 0x36, 0x41]


def main(argv):
    game = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join(os.path.dirname(__file__), "../../out/test_vectors.json")
    groups = int(argv[3]) if len(argv) > 3 else 150
    per = int(argv[4]) if len(argv) > 4 else 40
    rnd = random.Random(3640)
    d = t.Driver(os.path.join(game, "Ekd5.exe"), os.path.join(game, "RS", "S_002.eex"), [0])
    d.run_scene(0)
    o = d.orig
    mu = o.mu
    sigs = d.sigs
    scratch = len(d.data) + 0x100   # 劇本後面的空間放測試指令（劇本緩衝區多配了 0x10000）
    w, h = mu.mem_read(0x4B422C, 2)
    grid = struct.unpack("<I", mu.mem_read(0x48B140 + 0xC, 4))[0] + 0xC80
    base_units = bytes(mu.mem_read(t.UNITS, 250 * 0x30))
    present = [s for s in range(250) if struct.unpack_from("<H", base_units, s * 0x30)[0] != 0xFFFF]
    chars = [struct.unpack_from("<H", base_units, s * 0x30)[0] for s in present]

    def who():
        r = rnd.random()
        if r < 0.5:
            return rnd.choice(chars)
        if r < 0.7:
            return rnd.choice([0x400, 0x401, 0x402, 0x403])
        if r < 0.85:
            return 0x404 + rnd.choice(present + [rnd.randrange(250)])
        return rnd.choice([0xFFFF, rnd.randrange(1024), 0x4FE])

    def value(op, typ, i):
        if typ == 0x02:
            return who()
        if typ == 0x04:
            if op in (0x25, 0x26, 0x41) or (op == 0x36 and i == 2 and rnd.random() < 0.3):
                return rnd.choice([rnd.randrange(max(w, h) + 2), rnd.randrange(256)])
            return rnd.choice([rnd.randrange(40), rnd.randrange(600), 0])
        if typ == 0x24:
            return rnd.choice([0, 1, 2, 3])
        if typ == 0x48:
            return rnd.choice([0, 1, 2, 3])
        if typ == 0x26:
            return rnd.choice([0, 1, 0xFFFF])
        if typ == 0x23:
            return rnd.choice(list(range(16)) + [7, 8, 15])
        if typ == 0x03:
            return rnd.choice([0, 1, 2, 3, 4, 5, 6, 0xFFFF])
        if typ == 0x3F:
            return rnd.choice([0, 1, 2, 0xFFFF])
        return rnd.randrange(8)

    vectors = []
    for g in range(groups):
        units = bytearray(base_units)
        turn = rnd.randrange(30)
        side = rnd.choice([0, 1, 2, 3])
        for s in present:
            u = s * 0x30
            if rnd.random() < 0.6:
                units[u + 0x0C] = rnd.choice([2, 2, 2, 1, 3])
                units[u + 6] = rnd.randrange(w)
                units[u + 7] = rnd.randrange(h)
                struct.pack_into("<I", units, u + 0x10, rnd.choice([0, rnd.randrange(1, 300)]))
                struct.pack_into("<I", units, u + 0x14, rnd.randrange(100))
                for k in range(6):
                    units[u + 0x18 + k] = rnd.choice([3, 3, 1, 6])
                units[u + 0x1E] = rnd.choice([0, 0, 1, 8, 0x10, 0xC0])
        occ = bytearray([0xFF]) * 0x640
        for s in present:
            u = s * 0x30
            if units[u + 0x0C] == 2 and units[u + 6] < w and units[u + 7] < h:
                occ[units[u + 7] * w + units[u + 6]] = s
        mu.mem_write(t.UNITS, bytes(units))
        mu.mem_write(grid, bytes(occ))
        mu.mem_write(BATTLE + 2, bytes([turn]))
        mu.mem_write(BATTLE + 0x10, bytes([side]))
        tests = []
        for _ in range(per):
            op = rnd.choice(OPS)
            params = [[typ, value(op, typ, i)] for i, typ in enumerate(sigs[op])]
            onmap = [s for s in present if units[s * 0x30 + 0x0C] == 2]
            if onmap and rnd.random() < 0.6 and op in (0x25, 0x26, 0x2E):
                # 讓位置／武將對到真的在地圖上的單位
                s0 = rnd.choice(onmap)
                ux, uy = units[s0 * 0x30 + 6], units[s0 * 0x30 + 7]
                if rnd.random() < 0.5:
                    params[0][1] = chars[present.index(s0)]
                if op == 0x25:
                    params[1][1], params[2][1] = ux, uy
                elif op == 0x26:
                    params[1][1], params[2][1] = max(0, ux - rnd.randrange(3)), max(0, uy - rnd.randrange(3))
                    params[3][1], params[4][1] = ux + rnd.randrange(3), uy + rnd.randrange(3)
                else:
                    near = [x for x in onmap if abs(units[x * 0x30 + 6] - ux) + abs(units[x * 0x30 + 7] - uy) == 1]
                    params[0][1] = chars[present.index(s0)]
                    if near and rnd.random() < 0.7:
                        params[1][1] = chars[present.index(rnd.choice(near))]
            code = struct.pack("<H", op) + b"".join(
                struct.pack("<Hi", typ, v) if typ == 0x04 else struct.pack("<HH", typ, v) for typ, v in params)
            o.mu.mem_write(SCRIPT + scratch, code)
            before = bytes(mu.mem_read(t.UNITS, 250 * 0x30))
            _, ret, ok = o.exec_op(op, scratch)
            changed = bytes(mu.mem_read(t.UNITS, 250 * 0x30)) != before
            tests.append({"op": op, "params": params, "ret": ret & 0xFF, "ok": ok, "changed": changed})
        vectors.append({"turn": turn, "side": side, "units": units.hex(), "occupant": occ.hex(), "tests": tests})

    with open(out, "w") as f:
        json.dump({"width": w, "height": h, "groups": vectors}, f)
    n = sum(len(v["tests"]) for v in vectors)
    bad = sum(1 for v in vectors for x in v["tests"] if not x["ok"])
    print(out, n, f"（沒跑完 {bad} 條）")


if __name__ == "__main__":
    main(sys.argv)
