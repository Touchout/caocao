"""方法 1：能力值（0x38 武将能力设定、0x78 寫能力、基礎值公式 0x408A60）跟原版比對的資料。

在模擬器跑原版載入函式後，先隨機改一些武將的兵種、等級、五維、我軍標識，並在成長修正表 0x507EC0 放幾筆資料
（這些初始修改記在 "init"，引擎照做），然後隨機執行：
  38    原版 0x38 處理函式（0x412007），參數 (武將, 項目, 運算, 值)
  78    原版 0x4202D8（0x78 屬性讀寫），只取能力相關屬性
  base  直接呼叫 0x408A60（ecx = 武將資料，參數 = 項目），記錄回傳值
每筆記錄寫到的所有記憶體（堆疊除外）。StatsTest 依序重播比對。輸出含遊戲資料衍生的數值，放 out/（不進 git）。

用法：python3 tools/re/gen_stats_vectors.py <遊戲目錄> [輸出檔，預設 out/stats_vectors.json] [次數]
"""
import json
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trace_original as t  # noqa: E402
from eexemu import RET_MAGIC, SCRIPT, STACK  # noqa: E402
from unicorn import UC_HOOK_MEM_WRITE  # noqa: E402
from unicorn.x86_const import UC_X86_REG_ECX  # noqa: E402

ATTR = 0x4202D8
BASE = 0x408A60
GROWTH_ADJUST = 0x507EC0
INT_VARS = 0x502000
STAT_ATTRS = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13]


def main(argv):
    game = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join(os.path.dirname(__file__), "../../out/stats_vectors.json")
    count = int(argv[3]) if len(argv) > 3 else 3000
    rnd = random.Random(38)
    o = t.Original(os.path.join(game, "Ekd5.exe"), b"\0" * 16)
    o.init_statics()
    o.init_game_data(os.path.join(game, "Data.e5"))
    mu = o.mu
    chars_base = struct.unpack("<I", mu.mem_read(t.CHARS_PTR, 4))[0]

    init = []

    def poke(addr, data):
        mu.mem_write(addr, bytes(data))
        init.extend([addr + i, b] for i, b in enumerate(data))

    # 隨機的武將狀態：0x66 號以前大多設成我軍（我軍標識 0 或其他值），兵種、等級、五維隨機
    pool = list(range(0x70)) + rnd.sample(range(0x70, 1024), 30)
    for c in pool:
        r = chars_base + 0x48 * c
        if rnd.random() < 0.8:
            poke(r + 0x2A, [rnd.choice([0, 0, 1, 2, 0xFF])])
        poke(r + 0x2B, [rnd.randrange(80)])
        poke(r + 0x2C, [rnd.choice([1, 5, 14, 15, 16, 29, 30, 31, 45, 60, 99, rnd.randrange(256)])])
        poke(r + 0x21, [rnd.randrange(256) if rnd.random() < 0.1 else rnd.randrange(30, 101) for _ in range(5)])
    # 成長修正表：0xFF 項目 {值 u16武將}* … 0xFF 0xFF
    table = bytearray()
    for stat in range(7):
        table += bytes([0xFF, stat])
        for c in rnd.sample(pool, 4):
            table += struct.pack("<bH", rnd.randrange(-5, 6), c)
    table += b"\xFF\xFF"
    poke(GROWTH_ADJUST, table)

    # 0x38 指令都放在同一段劇本裡，逐條執行
    script = bytearray()
    ops = []
    for _ in range(count):
        kind = rnd.choices(["38", "78", "base"], [5, 2, 2])[0]
        ch = rnd.choice(pool) if rnd.random() < 0.85 else rnd.choice([0xFFFF, 0x401, 0x3FF, rnd.randrange(1024)])
        if kind == "38":
            stat = rnd.choice(list(range(16)) + [0, 1, 2, 3, 4, 5, 6] * 2)
            op = rnd.choice([0, 0, 1, 2, 3])
            r = rnd.random()
            value = rnd.randrange(200) if r < 0.6 else rnd.randrange(20000) if r < 0.9 else rnd.getrandbits(32)
            if ch >= 0x400:
                ch = rnd.choice(pool)
            ops.append({"kind": "38", "char": ch, "stat": stat, "op": op, "value": value, "pos": len(script)})
            script += struct.pack("<HHHHHHHHi", 0x38, 0x02, ch, 0x23, stat, 0x34, op, 0x04, value - (1 << 32) if value >= 1 << 31 else value)
        elif kind == "78":
            if ch >= 0x400:
                ch = rnd.choice(pool)
            r = rnd.random()
            value = rnd.randrange(200) if r < 0.6 else rnd.randrange(20000) if r < 0.9 else rnd.getrandbits(32)
            ops.append({"kind": "78", "attr": rnd.choice(STAT_ATTRS), "char": ch, "mode": rnd.choice([0, 1, 1]),
                        "var": rnd.randrange(100, 200), "value": value})
        else:
            if ch >= 0x400:
                ch = rnd.choice(pool)
            ops.append({"kind": "base", "char": ch, "stat": rnd.randrange(5)})
    o.load_script(bytes(script) + b"\0" * 16)

    writes = []

    def on_write(mu, access, addr, size, value, user):
        if not (STACK <= addr < STACK + 0x100000) and not (SCRIPT <= addr < SCRIPT + 0x100000):
            writes.append((addr, size))

    hook = None
    for op in ops:
        if hook is None:
            hook = mu.hook_add(UC_HOOK_MEM_WRITE, on_write)
        writes.clear()
        if op["kind"] == "38":
            end, ret, ok = o.exec_op(0x38, op["pos"])
            op["ok"] = ok
            op["ret"] = ret & 0xFF
        elif op["kind"] == "78":
            mu.mem_write(INT_VARS + 4 * op["var"], struct.pack("<I", op["value"]))
            writes.clear()
            o.call(ATTR, op["attr"], op["char"], op["mode"], op["var"])
        else:
            mu.reg_write(UC_X86_REG_ECX, chars_base + 0x48 * op["char"])
            op["expect"] = o.call(BASE, op["stat"])
        touched = sorted({a + i for a, s in writes for i in range(s)})
        op["writes"] = [[a, mu.mem_read(a, 1)[0]] for a in touched]
        op.pop("pos", None)

    with open(out, "w") as f:
        json.dump({"init": init, "ops": ops}, f)
    bad = sum(1 for x in ops if x["kind"] == "38" and not x["ok"])
    print(out, len(ops), f"（0x38 沒跑完 {bad} 筆）")


if __name__ == "__main__":
    main(sys.argv)
