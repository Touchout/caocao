"""用 Unicorn 執行原版 Ekd5.exe 的變數指令處理函式，產生 Kotlin 直譯器的測試資料。

輸出：engine/core/src/test/resources/ccz/vm_vectors.json
每筆：{op, code(指令 bytes 的 hex，不含 opcode), init:{位址:u32}, expect:{位址:u32}, ret}
只含數字，不含遊戲內容。

用法：python3 tools/re/gen_vectors.py <Ekd5.exe> [每種指令的筆數]
"""
import json
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from eexemu import THIS, EexEmu  # noqa: E402

INT_VARS = 0x502000
PTR_VARS = 0x506000
WIDTH_VAR = INT_VARS + 4 * 4076
FLAGS = 0x492FC8
# 模擬器裡的 VM 物件是假的（THIS），旗標實際在 THIS + 0x38；測試資料一律記真實位址 FLAGS
FLAGS_EMU = THIS + 0x38


def to_emu(a):
    return a - FLAGS + FLAGS_EMU if FLAGS <= a < FLAGS + 0x2000 else a
# 指標會指到這一塊（MOD 資料區，在映像內）
POOL = 0x511000


def p16(t, v):
    return struct.pack("<HH", t, v & 0xFFFF)


def p32(t, v):
    return struct.pack("<HI", t, v & 0xFFFFFFFF)


class Gen:
    def __init__(self, exe):
        self.emu = EexEmu(exe)
        self.rng = random.Random(1234)

    def mem_r(self, a):
        return struct.unpack("<I", self.emu.mu.mem_read(a, 4))[0]

    def mem_w(self, a, v):
        self.emu.mu.mem_write(a, struct.pack("<I", v & 0xFFFFFFFF))

    def run(self, op, code, init, watch):
        """init 寫進記憶體後執行；回傳 (watch 位址的值, 回傳值)。"""
        self.emu.load_script(code + b"\0" * 16)
        # run() 會先還原映像，所以把 init 包進 hook：在 handler 開始前寫入
        orig = self.emu.mu.emu_start

        def start(begin, until, **kw):
            for a, v in init.items():
                self.mem_w(to_emu(a), v)
            return orig(begin, until, **kw)

        self.emu.mu.emu_start = start
        try:
            end, _, ret, ok = self.emu.run(op, 0)
        finally:
            self.emu.mu.emu_start = orig
        if not ok or end != len(code):
            return None
        return {a: self.mem_r(to_emu(a)) for a in watch}, ret & 0xFFFFFFFF

    def rnd_val(self):
        return self.rng.choice([0, 1, 2, 3, 7, 100, 255, 256, 1000, 65535, 65536, 0x12345678, 0xFFFFFFFF,
                                self.rng.randrange(0, 1 << 32)])

    def state(self, ints, ptrs):
        """隨機的整數變數、指標變數（指到 POOL 裡）、寬度、POOL 內容。"""
        init = {WIDTH_VAR: self.rng.choice([0, 1, 2, 4])}
        for n in ints:
            init[INT_VARS + 4 * n] = self.rnd_val()
        for n in ptrs:
            init[PTR_VARS + 4 * n] = POOL + 4 * self.rng.randrange(0, 16) + self.rng.randrange(0, 4)
        for i in range(0, 80, 4):
            init[POOL + i] = self.rnd_val()
        return init

    def op77(self):
        kind = self.rng.choice([0, 1, 2])
        idx = self.rng.randrange(0, 8) if kind < 2 else self.rng.choice([0, 5, 100, 4076 - 1, 3000])
        oper = self.rng.randrange(0, 7)
        okind = self.rng.randrange(0, 6)
        oval = self.rnd_val() if okind == 0 else self.rng.randrange(0, 8)
        if oper in (4, 6) and okind == 0 and oval == 0:
            oval = 3  # 避免除以零（原版也會當掉）
        init = self.state(ints=[idx, oval] if okind >= 4 else [idx], ptrs=list(range(8)))
        if oper in (4, 6) and okind != 0:
            # 運算元可能是 0；先跑跑看，除以零的組合丟掉
            pass
        code = p16(0x51, kind) + p32(4, idx) + p16(0x53, oper) + p16(0x52, okind) + p32(4, oval)
        watch = sorted(set(init) | {POOL + i for i in range(0, 80, 4)})
        r = self.run(0x77, code, init, watch)
        return code, init, r

    def op79(self):
        ka, kb = self.rng.randrange(0, 6), self.rng.randrange(0, 6)
        va = self.rnd_val() if ka == 0 else self.rng.randrange(0, 8)
        vb = self.rnd_val() if kb == 0 else self.rng.randrange(0, 8)
        cmp = self.rng.randrange(0, 6)
        init = self.state(ints=list(range(8)), ptrs=list(range(8)))
        if self.rng.random() < 0.3:
            init[INT_VARS + 4 * (va if ka >= 4 else 0)] = init.get(INT_VARS + 4 * (vb if kb >= 4 else 1), 0)
        code = p16(0x52, ka) + p32(4, va) + p16(0x56, cmp) + p16(0x52, kb) + p32(4, vb)
        r = self.run(0x79, code, init, [])
        return code, init, r

    def op05(self):
        init = {}
        ids = [self.rng.randrange(0, 4096) for _ in range(4)]
        for i in ids:
            a = FLAGS + i // 8
            init[a - a % 4] = self.rng.randrange(0, 1 << 32)
        n1, n2 = self.rng.randrange(0, 3), self.rng.randrange(0, 3)
        code = p16(0x35, n1) + b"".join(struct.pack("<H", i) for i in ids[:n1]) + \
            p16(0x35, n2) + b"".join(struct.pack("<H", i) for i in ids[2:2 + n2])
        r = self.run(0x05, code, init, [])
        return code, init, r

    def op0b(self):
        i = self.rng.randrange(0, 4096)
        a = FLAGS + i // 8
        init = {a - a % 4: self.rng.randrange(0, 1 << 32)}
        code = p32(4, i) + p16(0x27, self.rng.choice([0, 1, 2]))
        r = self.run(0x0B, code, init, sorted(init))
        return code, init, r


def main(argv):
    exe = argv[1]
    count = int(argv[2]) if len(argv) > 2 else 300
    g = Gen(exe)
    out = []
    for op, fn in [(0x77, g.op77), (0x79, g.op79), (0x05, g.op05), (0x0B, g.op0b)]:
        made = 0
        tries = 0
        while made < count and tries < count * 5:
            tries += 1
            code, init, r = fn()
            if r is None:
                continue
            expect, ret = r
            out.append({"op": op, "code": code.hex(),
                        "init": {f"{a:x}": v for a, v in init.items()},
                        "expect": {f"{a:x}": v for a, v in expect.items()},
                        "ret": ret})
            made += 1
        print(f"op {op:#04x}: {made} 筆（嘗試 {tries}）")
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "engine", "core", "src", "test",
                        "resources", "ccz", "vm_vectors.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(out, f)
    print(f"{len(out)} 筆 → {path}")


if __name__ == "__main__":
    sys.exit(main(sys.argv))
