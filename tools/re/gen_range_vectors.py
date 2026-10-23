"""方法 1：移動力（0x43F74F）與移動範圍（0x438484 模式 0）跟原版比對的資料。

每關先在模擬器跑 scene 0（含開戰初始化）當基準狀態。每一組隨機打散在地圖上的單位位置（照單位重建佔位表）、
改移動狀態（+0x1D）與健康狀態（+0x1E），挑一個在地圖上的單位，照原版玩家選單位時的呼叫
（0x43D5FD）：移動力 = 0x43F74F(單位)，0x438484(&單位+6, 移動力, 0, 0xFF, 0xFF, 1)，
記下花費表（戰場緩衝區 +0x12C0，w×h bytes，0xFF = 到不了）。RangeTest 用同樣的狀態比對。

用法：python3 tools/re/gen_range_vectors.py <遊戲目錄> [輸出檔，預設 out/range_vectors.json] [每關組數] [關卡,…]
"""
import json
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trace_original as t  # noqa: E402
from eexemu import RET_MAGIC, STACK  # noqa: E402
from unicorn import UcError  # noqa: E402
from unicorn.x86_const import UC_X86_REG_EAX, UC_X86_REG_EBP, UC_X86_REG_ECX, UC_X86_REG_EIP, UC_X86_REG_ESP  # noqa: E402

MOVE_POINTS = 0x43F74F
RANGE = 0x438484
BATTLE_BUF = 0x48B140
STAGES = [0, 2, 4, 6, 8, 11, 12, 14, 15, 16, 19, 22, 38, 39, 40, 42, 47, 52, 53, 54, 58, 70, 85, 93, 99, 107, 110, 112, 113, 114, 117, 118]


def call(mu, va, ecx, *args):
    esp = STACK + 0x80000
    for a in reversed(args):
        esp -= 4
        mu.mem_write(esp, struct.pack("<I", a & 0xFFFFFFFF))
    esp -= 4
    mu.mem_write(esp, struct.pack("<I", RET_MAGIC))
    mu.reg_write(UC_X86_REG_ESP, esp)
    mu.reg_write(UC_X86_REG_EBP, esp + 0x100)
    mu.reg_write(UC_X86_REG_ECX, ecx)
    try:
        mu.emu_start(va, RET_MAGIC, count=100_000_000)
    except UcError:
        return None
    if mu.reg_read(UC_X86_REG_EIP) != RET_MAGIC:
        return None
    return mu.reg_read(UC_X86_REG_EAX)


def main(argv):
    game = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join(os.path.dirname(__file__), "../../out/range_vectors.json")
    per = int(argv[3]) if len(argv) > 3 else 30
    stages = [int(x) for x in argv[4].split(",")] if len(argv) > 4 else STAGES
    rnd = random.Random(438484)
    result = []
    for stage in stages:
        d = t.Driver(os.path.join(game, "Ekd5.exe"), os.path.join(game, "RS", "S_%03d.eex" % stage), [0])
        d.run_scene(0)
        mu = d.orig.mu
        w, h = mu.mem_read(0x4B422C, 2)
        buf = struct.unpack("<I", mu.mem_read(BATTLE_BUF + 0xC, 4))[0]
        grid = buf + 0xC80
        cost = buf + (0x12C0 if mu.mem_read(0x500ACE, 1)[0] == 0 else 0)
        base_units = bytes(mu.mem_read(t.UNITS, 250 * 0x30))
        base_grid = bytes(mu.mem_read(grid, 0x640))
        onmap = [s for s in range(250) if base_units[s * 0x30 + 0x0C] == 2 and struct.unpack_from("<H", base_units, s * 0x30)[0] != 0xFFFF]
        groups = []
        for g in range(per):
            units = bytearray(base_units)
            occ = bytearray(base_grid)
            if g % 3:
                # 打散位置：隨機挑一些單位搬到隨機空格
                for s in onmap:
                    if rnd.random() < 0.5:
                        u = s * 0x30
                        for _ in range(20):
                            x, y = rnd.randrange(w), rnd.randrange(h)
                            if occ[y * w + x] == 0xFF:
                                occ[units[u + 7] * w + units[u + 6]] = 0xFF
                                units[u + 6], units[u + 7] = x, y
                                occ[y * w + x] = s
                                break
            for s in onmap:
                u = s * 0x30
                if rnd.random() < 0.2:
                    units[u + 0x1D] = rnd.choice([1, 3, 3, 5, 6])
                if rnd.random() < 0.1:
                    units[u + 0x1E] = rnd.choice([0, 1, 2, 4, 0x40])
            mu.mem_write(t.UNITS, bytes(units))
            mu.mem_write(grid, bytes(occ))
            s = rnd.choice(onmap)
            unit = t.UNITS + s * 0x30
            mp = call(mu, MOVE_POINTS, unit)
            ok = mp is not None
            costs = None
            if ok:
                mp &= 0xFF
                r = call(mu, RANGE, unit, unit + 6, mp, 0, 0xFF, 0xFF, 1)
                ok = r is not None
                costs = bytes(mu.mem_read(cost, w * h)).hex()
            groups.append({"slot": s, "units": units.hex(), "occupant": occ.hex(), "ok": ok,
                           "move": mp if ok else None, "cost": costs})
        result.append({"stage": stage, "width": w, "height": h, "groups": groups})
        print(f"S_{stage:03d}：{per} 組，沒跑完 {sum(1 for x in groups if not x['ok'])}", file=sys.stderr)
    with open(out, "w") as f:
        json.dump(result, f)
    print(out)


if __name__ == "__main__":
    main(sys.argv)
