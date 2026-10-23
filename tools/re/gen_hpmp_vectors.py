"""方法 1：HP/MP 上限（0x407263／0x40728F，含裝備加成 0x407C29）跟原版比對的資料。

在模擬器跑原版載入函式後，每筆隨機產生一名武將的狀態（裝備、裝備等級、HP/MP 基礎值、+0x35、整數變數 4046），
直接呼叫原版 0x407263、0x40728F（ecx = 武將資料），記錄回傳值。HpMpTest 讀同一份資料比對。
輸出含遊戲資料衍生的數值，放 out/（不進 git）。

用法：python3 tools/re/gen_hpmp_vectors.py <遊戲目錄> [輸出檔，預設 out/hpmp_vectors.json] [筆數]
"""
import json
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trace_original as t  # noqa: E402
from unicorn.x86_const import UC_X86_REG_ECX  # noqa: E402

MAX_HP = 0x407263
MAX_MP = 0x40728F
VAR_4046 = 0x502000 + 4 * 4046


def main(argv):
    game = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join(os.path.dirname(__file__), "../../out/hpmp_vectors.json")
    count = int(argv[3]) if len(argv) > 3 else 3000
    rnd = random.Random(7263)
    o = t.Original(os.path.join(game, "Ekd5.exe"), b"\0" * 16)
    o.init_statics()
    o.init_game_data(os.path.join(game, "Data.e5"))
    mu = o.mu
    chars_base = struct.unpack("<I", mu.mem_read(t.CHARS_PTR, 4))[0]

    def item():
        r = rnd.random()
        return 0xFF if r < 0.15 else rnd.randrange(256)

    def level():
        return rnd.choice([0, 1, 1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 15, rnd.randrange(256)])

    cases = []
    for _ in range(count):
        c = rnd.randrange(1024)
        r = chars_base + 0x48 * c
        data = bytearray(mu.mem_read(r, 0x48))
        for k in range(3):
            data[0x2E + 3 * k] = item()
            data[0x2F + 3 * k] = level()
        data[0x1B:0x1F] = struct.pack("<I", rnd.choice([rnd.randrange(1, 600), rnd.randrange(10000), rnd.randrange(0x10000)]))
        data[0x1F:0x21] = struct.pack("<H", rnd.choice([rnd.randrange(1, 300), rnd.randrange(10000), rnd.randrange(0x10000)]))
        data[0x35:0x37] = struct.pack("<H", rnd.choice([0, rnd.randrange(200), rnd.randrange(0x10000)]))
        var = rnd.choice([0, 0, 1, 0x100, rnd.getrandbits(32)])
        mu.mem_write(r, bytes(data))
        mu.mem_write(VAR_4046, struct.pack("<I", var))
        mu.reg_write(UC_X86_REG_ECX, r)
        hp = o.call(MAX_HP)
        mu.reg_write(UC_X86_REG_ECX, r)
        mp = o.call(MAX_MP)
        cases.append({"char": c, "data": data.hex(), "var4046": var, "hp": hp, "mp": mp})

    with open(out, "w") as f:
        json.dump(cases, f)
    print(out, len(cases))


if __name__ == "__main__":
    main(sys.argv)
