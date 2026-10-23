"""方法 1：特效判定（原版 0x4101D9(特效, 編號2, 合併方式, 看相鄰)，ecx = 單位）跟引擎比對的資料。

每關先在模擬器跑 scene 0（含開戰初始化）。每一組：隨機改幾個在地圖上單位的武將裝備（武器／防具／輔助與等級）
和位置，挑一個單位，用原版呼叫點出現過的參數組合加上隨機參數呼叫 0x4101D9，記下回傳的 eax、edx。
EffectTest 用同樣的狀態比對。輸出放 out/（不進 git）。

用法：python3 tools/re/gen_effect_vectors.py <遊戲目錄> [輸出檔，預設 out/effect_vectors.json] [每關組數] [關卡,…]
"""
import json
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trace_original as t  # noqa: E402
from gen_range_vectors import call  # noqa: E402
from unicorn.x86_const import UC_X86_REG_EDX  # noqa: E402

EFFECT = 0x4101D9
STAGES = [0, 2, 8, 14, 22, 40, 52, 70, 99, 118]
# 原版呼叫點的 (特效, 編號2, 合併方式, 看相鄰)
SITES = [(0x32, 0x44, 1, 1), (0x36, 0x5D, 1, 1), (0x3B, 0x1F, 1, 0), (0x71, 0x58, 1, 0), (0x34, 0x42, 1, 1),
         (0x27, 0x20, 1, 0), (0x30, 0x41, 1, 1), (0x14, 0x65, 1, 0), (0x15, 0x5B, 1, 1), (0x2A, 0x3A, 1, 1),
         (0x6F, 0x48, 3, 0), (0x62, 0x35, 0, 0), (0x65, 0x52, 0, 0), (0, 0x25, 0, 0), (1, 0x1B, 0, 0),
         (54, 93, 1, 1), (62, 42, 1, 1), (63, 42, 1, 1), (55, 41, 1, 1), (88, 116, 1, 1)]


def main(argv):
    game = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join(os.path.dirname(__file__), "../../out/effect_vectors.json")
    per = int(argv[3]) if len(argv) > 3 else 20
    stages = [int(x) for x in argv[4].split(",")] if len(argv) > 4 else STAGES
    rnd = random.Random(4101)
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
        excl0 = struct.unpack("<I", mu.mem_read(0x500C3B, 4))[0]
        base_excl = bytes(mu.mem_read(excl0, 0x1000))
        base_combo = bytes(mu.mem_read(0x50E000, 0x1000))
        base_limit = bytes(mu.mem_read(0x505F78, 4))
        onmap = [s for s in range(250) if base_units[s * 0x30 + 0x0C] == 2 and struct.unpack_from("<H", base_units, s * 0x30)[0] != 0xFFFF]
        groups = []
        for g in range(per):
            units = bytearray(base_units)
            occ = bytearray(base_grid)
            cdata = bytearray(base_chars)
            # 讓一些單位擠在一起（相鄰的特效）
            s0 = rnd.choice(onmap)
            for s in onmap:
                if s != s0 and rnd.random() < 0.3:
                    u = s * 0x30
                    x0, y0 = units[s0 * 0x30 + 6], units[s0 * 0x30 + 7]
                    x, y = x0 + rnd.randint(-1, 1), y0 + rnd.randint(-1, 1)
                    if 0 <= x < w and 0 <= y < h and occ[y * w + x] == 0xFF:
                        occ[units[u + 7] * w + units[u + 6]] = 0xFF
                        units[u + 6], units[u + 7] = x, y
                        occ[y * w + x] = s
            changed = []
            for s in onmap:
                if rnd.random() < 0.4:
                    ch = struct.unpack_from("<H", units, s * 0x30)[0] & 0xFFF
                    c = ch * 0x48
                    for k in range(3):
                        if rnd.random() < 0.6:
                            cdata[c + 0x2E + 3 * k] = rnd.choice([0xFF, rnd.randrange(256)])
                            cdata[c + 0x2F + 3 * k] = rnd.randrange(12)
                    changed.append(ch)
            mu.mem_write(t.UNITS, bytes(units))
            mu.mem_write(grid, bytes(occ))
            mu.mem_write(chars, bytes(cdata))
            # 一半的組隨機填特效表，讓封印（0x59）、相鄰傳遞（88）、組合表、兵種、相鄰門檻都跑得到
            mu.mem_write(excl0, base_excl)
            mu.mem_write(0x50E000, base_combo)
            mu.mem_write(0x505F78, base_limit)
            regions = None
            sampled = []
            if g % 2:
                ex = bytearray(base_excl)
                co = bytearray(base_combo)
                chs = [struct.unpack_from("<H", units, x * 0x30)[0] & 0xFFF for x in onmap]
                sampled = rnd.sample(range(256), 8)
                for e in sampled + [88, 0x59, 0x59, 88]:
                    for i in range(4):
                        if rnd.random() < 0.5:
                            struct.pack_into("<HB", ex, 16 * e + 3 * i, rnd.choice(chs), rnd.randrange(1, 120))
                    for j in range(2):
                        if rnd.random() < 0.3:
                            ex[16 * e + 12 + 2 * j] = rnd.randrange(0x55)
                            ex[16 * e + 13 + 2 * j] = rnd.randrange(1, 100)
                    for i in range(2):
                        if rnd.random() < 0.4:
                            c = rnd.choice(chs)
                            its = [cdata[c * 0x48 + 0x2E + 3 * k] for k in range(3)]
                            struct.pack_into("<HBB", co, 16 * e + 4 * i, c, rnd.choice(its), rnd.randrange(1, 100))
                        if rnd.random() < 0.4:
                            c = rnd.choice(chs)
                            its = [cdata[c * 0x48 + 0x2E + 3 * k] if rnd.random() < 0.7 else 0xFF for k in range(3)]
                            co[16 * e + 8 + 4 * i:16 * e + 12 + 4 * i] = bytes(its + [rnd.randrange(1, 100)])
                mu.mem_write(excl0, bytes(ex))
                mu.mem_write(0x50E000, bytes(co))
                mu.mem_write(0x505F78, struct.pack("<I", rnd.choice([0x400, 0x200, 0x50])))
                regions = {"excl": ex.hex(), "combo": co.hex(), "limit": struct.unpack("<I", mu.mem_read(0x505F78, 4))[0]}
            excl = struct.unpack("<I", mu.mem_read(0x500C3B, 4))[0]
            table = bytes(mu.mem_read(excl, 256 * 16))
            effs_extra = ([88, 0x59] + sampled) if regions else []
            tests = []
            for _ in range(40):
                s = rnd.choice(onmap)
                ch = struct.unpack_from("<H", units, s * 0x30)[0] & 0xFFF
                # 有意義的參數：武將專屬表裡有這個武將的特效、他裝備上的特效碼
                effs = [e for e in range(256) for i in range(4) if struct.unpack_from("<H", table, 16 * e + 3 * i)[0] == ch] + effs_extra
                codes = []
                for k in range(3):
                    it = cdata[ch * 0x48 + 0x2E + 3 * k]
                    if it != 0xFF:
                        rec = bytes(mu.mem_read(0x49F820 + 25 * it, 25))
                        codes.append(rec[0x11] if k == 2 else rec[0x14])
                r0 = rnd.random()
                if r0 < 0.3:
                    a = rnd.choice(SITES)
                elif r0 < 0.7:
                    a = (rnd.choice(effs or [rnd.randrange(0x80)]), rnd.choice(codes or [rnd.randrange(0x80)]),
                         rnd.choice([0, 1, 2, 3]), rnd.choice([0, 0, 1]))
                else:
                    a = (rnd.randrange(0x100), rnd.randrange(0x80), rnd.choice([0, 1, 2, 3]), rnd.choice([0, 1]))
                r = call(mu, EFFECT, t.UNITS + s * 0x30, *a)
                tests.append({"slot": s, "args": list(a), "ok": r is not None,
                              "eax": r, "edx": mu.reg_read(UC_X86_REG_EDX) if r is not None else None})
            groups.append({"units": units.hex(), "occupant": occ.hex(), "regions": regions,
                           "chars": {str(ch): cdata[ch * 0x48:ch * 0x48 + 0x48].hex() for ch in changed}, "tests": tests})
        result.append({"stage": stage, "groups": groups})
        print(f"S_{stage:03d}：{per} 組，非 0 結果 {sum(1 for gr in groups for x in gr['tests'] if x['eax'])}", file=sys.stderr)
    with open(out, "w") as f:
        json.dump(result, f)
    print(out)


if __name__ == "__main__":
    main(sys.argv)
