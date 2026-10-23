"""方法 1：戰場事件的選擇（原版 0x4100A1）跟引擎 ScriptVM.selectEvent 比對的資料。

每關先在模擬器跑 scene 0（含開戰初始化）。之後每一組隨機改回合（戰場物件 +2）、行動方（+0x10）、
scene 1 條件用到的旗標，把劇本 VM 設成「目前在 scene 1、沒有事件在執行」，呼叫原版 0x4100A1，
記下它選中的位置（VM +0xC，選中 section 的 opcode 0 之後）或沒選中。EventTest 用同樣的狀態比對。

用法：python3 tools/re/gen_event_vectors.py <遊戲目錄> [輸出檔，預設 out/event_vectors.json] [每關組數] [關卡,…]
"""
import json
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trace_original as t  # noqa: E402
from eexemu import RET_MAGIC, SCRIPT, STACK  # noqa: E402
from unicorn import UcError  # noqa: E402
from unicorn.x86_const import UC_X86_REG_EAX, UC_X86_REG_EBP, UC_X86_REG_ECX, UC_X86_REG_EIP, UC_X86_REG_ESP  # noqa: E402

VM = t.VM
BATTLE = 0x4B3D08
SELECT = 0x4100A1
STAGES = [0, 2, 4, 6, 8, 11, 12, 14, 15, 16, 19, 22, 38, 39, 40, 42, 47, 52, 53, 54, 58, 70, 85, 93, 99, 107, 110, 112, 113, 114, 117, 118]


def scene1_flags(d):
    """scene 1 的 0x05 变量测试 用到的旗標編號。"""
    out = set()
    offs = [struct.unpack_from("<I", d.data, q)[0] for q in range(0x0A, struct.unpack_from("<I", d.data, 0x0A)[0], 4)]
    if len(offs) < 2:
        return []
    p, end = offs[1], (offs[2] if len(offs) > 2 else len(d.data))
    while p < end - 8:
        if d.u16(p) == 0x05 and d.u16(p + 2) == 0x35:
            try:
                q = p + 2
                for _ in range(2):
                    n = d.u16(q + 2)
                    out.update(d.u16(q + 4 + 2 * i) for i in range(n))
                    q += 4 + 2 * n
            except struct.error:
                pass
        p += 2
    return sorted(f for f in out if f < 0x10000)


def main(argv):
    game = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join(os.path.dirname(__file__), "../../out/event_vectors.json")
    per = int(argv[3]) if len(argv) > 3 else 40
    stages = [int(x) for x in argv[4].split(",")] if len(argv) > 4 else STAGES
    rnd = random.Random(4100)
    result = []
    for stage in stages:
        d = t.Driver(os.path.join(game, "Ekd5.exe"), os.path.join(game, "RS", "S_%03d.eex" % stage), [0])
        d.run_scene(0)
        mu = d.orig.mu
        flags = scene1_flags(d)
        base_flags = bytes(mu.mem_read(t.FLAGS, 0x2000))
        groups = []
        for _ in range(per):
            mu.mem_write(t.FLAGS, base_flags)
            turn = rnd.randrange(12)
            side = rnd.choice([0, 0, 1, 2])
            on = [f for f in flags if rnd.random() < 0.5]
            for f in on:
                a = t.FLAGS + f // 8
                mu.mem_write(a, bytes([mu.mem_read(a, 1)[0] | (0x80 >> (f % 8))]))
            off = [f for f in flags if f not in on]
            for f in off:
                a = t.FLAGS + f // 8
                mu.mem_write(a, bytes([mu.mem_read(a, 1)[0] & ~(0x80 >> (f % 8)) & 0xFF]))
            for p in d.orig.vm_results:   # 勝敗結果欄位每組歸 0（EventSelectTest 也一樣）
                mu.mem_write(p, b"\0" * 0x10)
            mu.mem_write(BATTLE + 2, bytes([turn]))
            mu.mem_write(BATTLE + 0x10, bytes([side]))
            # VM：目前 scene 1、腳本基址、場景表在檔頭 0x0A、沒有事件在執行
            mu.mem_write(VM + 4, struct.pack("<I", 1))
            mu.mem_write(VM + 0x10, struct.pack("<H", 0xFFFF))
            mu.mem_write(VM + 0x20, struct.pack("<I", SCRIPT))
            mu.mem_write(VM + 0x28, struct.pack("<I", 1))
            mu.mem_write(VM + 0x2C, struct.pack("<I", 0))
            mu.mem_write(VM + 0x30, struct.pack("<I", 0x0A))
            mu.mem_write(VM + 0x34, struct.pack("<I", 0))
            esp = STACK + 0x80000 - 4
            mu.mem_write(esp, struct.pack("<I", RET_MAGIC))
            mu.reg_write(UC_X86_REG_ESP, esp)
            mu.reg_write(UC_X86_REG_EBP, esp + 0x100)
            mu.reg_write(UC_X86_REG_ECX, VM)
            try:
                mu.emu_start(SELECT, RET_MAGIC, count=200_000_000)
                ok = mu.reg_read(UC_X86_REG_EIP) == RET_MAGIC
            except UcError:
                ok = False
            sel = mu.reg_read(UC_X86_REG_EAX) & 0xFF
            pos = struct.unpack("<I", mu.mem_read(VM + 0xC, 4))[0] - SCRIPT
            groups.append({"turn": turn, "side": side, "on": on, "off": off, "ok": ok,
                           "selected": sel != 0, "pos": pos if sel else None})
        result.append({"stage": stage, "groups": groups})
        n = sum(1 for g in groups if g["selected"])
        print(f"S_{stage:03d}：{len(groups)} 組，選到事件 {n} 組，旗標 {len(flags)} 個", file=sys.stderr)
    with open(out, "w") as f:
        json.dump(result, f)
    print(out)


if __name__ == "__main__":
    main(sys.argv)
