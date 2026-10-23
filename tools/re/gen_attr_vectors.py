"""方法 1：0x78 整型变量赋值 的武將屬性讀寫（原版 0x4202D8）比對資料。

在模擬器跑原版載入函式建好武將資料，在戰場單位表（0x4A7B20，250 × 0x30）隨機放幾個單位，
然後連續呼叫 0x4202D8(屬性, 武將, 模式, 變數) 數百次，記錄每次呼叫寫到的所有記憶體（堆疊除外）。
引擎（AttrTest）從同樣的初始狀態依序重播，寫入的位址與值都要一樣。

輸出含遊戲資料衍生的數值，放 out/（不進 git）。
用法：python3 tools/re/gen_attr_vectors.py <遊戲目錄> [輸出檔，預設 out/attr_vectors.json] [次數]
"""
import json
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trace_original as t  # noqa: E402
from eexemu import STACK  # noqa: E402
from unicorn import UC_HOOK_MEM_WRITE  # noqa: E402

ATTR = 0x4202D8
UNITS = 0x4A7B20
UNIT_SIZE = 0x30
SLOTS = 250
INT_VARS = 0x502000


def main(argv):
    game = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join(os.path.dirname(__file__), "../../out/attr_vectors.json")
    count = int(argv[3]) if len(argv) > 3 else 2000
    rnd = random.Random(78)
    o = t.Original(os.path.join(game, "Ekd5.exe"), open(os.path.join(game, "RS/S_000.eex"), "rb").read())
    o.init_game_data(os.path.join(game, "Data.e5"))
    mu = o.mu

    # 戰場單位：空的格子 [0] = 0xFFFF（建構函式 0x4012AF），其他放隨機內容
    deployed = rnd.sample(range(1024), 40) + [0, 1]
    units = bytearray()
    for slot in range(SLOTS):
        u = bytearray(UNIT_SIZE)
        struct.pack_into("<I", u, 0, 0xFFFF)
        units += u
    for i, c in enumerate(deployed):
        slot = rnd.randrange(SLOTS) if i else 0
        u = bytearray(rnd.getrandbits(8) for _ in range(UNIT_SIZE))
        struct.pack_into("<I", u, 0, c)
        units[slot * UNIT_SIZE:(slot + 1) * UNIT_SIZE] = u
    mu.mem_write(UNITS, bytes(units))

    writes = []

    def on_write(mu, access, addr, size, value, user):
        if not (STACK <= addr < STACK + 0x100000):
            writes.append((addr, size))

    mu.hook_add(UC_HOOK_MEM_WRITE, on_write)

    ops = []
    for _ in range(count):
        attr = rnd.randrange(42)
        char = rnd.choice(deployed) if rnd.random() < 0.6 else rnd.randrange(1024)
        mode = rnd.randrange(2)
        var = rnd.randrange(100, 200)
        r = rnd.random()
        value = rnd.randrange(256) if r < 0.5 else rnd.randrange(0x10000) if r < 0.8 else rnd.getrandbits(32)
        mu.mem_write(INT_VARS + 4 * var, struct.pack("<I", value))
        writes.clear()
        o.call(ATTR, attr, char, mode, var)
        touched = sorted({a + i for a, s in writes for i in range(s)})
        ops.append({"attr": attr, "char": char, "mode": mode, "var": var, "value": value,
                    "writes": [[a, mu.mem_read(a, 1)[0]] for a in touched]})

    with open(out, "w") as f:
        json.dump({"units": units.hex(), "ops": ops}, f)
    print(out, len(ops))


if __name__ == "__main__":
    main(sys.argv)
