"""依控制流反組譯整個函式（跟著跳躍走到所有 ret，略過 nop），依位址排序輸出。
用法：python3 tools/re/fn.py <Ekd5.exe> <VA>...
"""
import sys
from capstone import Cs, CS_ARCH_X86, CS_MODE_32
exe = open(sys.argv[1], 'rb').read()
SECS = [(0x1000, 0x400, 0x84e00), (0x86000, 0x85200, 0x4800), (0x8b000, 0x89a00, 0x6800),
        (0xce000, 0x9f800, 0x64000)]
def v2f(v):
    for va, ra, rs in SECS:
        if 0x400000 + va <= v < 0x400000 + va + rs:
            return v - 0x400000 - va + ra
md = Cs(CS_ARCH_X86, CS_MODE_32)
def one(addr):
    seen = {}
    todo = [addr]
    while todo:
        a = todo.pop()
        while a not in seen:
            f = v2f(a)
            if f is None:
                break
            ins = next(md.disasm(exe[f:f + 16], a), None)
            if ins is None:
                break
            seen[a] = ins
            m = ins.mnemonic
            if m.startswith('j'):
                try:
                    todo.append(int(ins.op_str, 16))
                except ValueError:
                    pass
                if m == 'jmp':
                    break
            if m in ('ret', 'retn') or (m == 'jmp' and not ins.op_str.startswith('0x')):
                break
            a += ins.size
    for a in sorted(seen):
        ins = seen[a]
        if ins.mnemonic == 'nop':
            continue
        print(f'{a:x}  {ins.mnemonic} {ins.op_str}')
for x in sys.argv[2:]:
    print(f'===== {x}')
    one(int(x, 16))
