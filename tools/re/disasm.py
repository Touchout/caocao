"""反組譯 Ekd5.exe 的一段：python3 tools/re/disasm.py <Ekd5.exe> <VA> [指令數]"""
import sys
from capstone import Cs, CS_ARCH_X86, CS_MODE_32
exe = open(sys.argv[1], 'rb').read()
SECS = [(0x1000, 0x400, 0x84e00), (0x86000, 0x85200, 0x4800), (0x8b000, 0x89a00, 0x6800),
        (0xce000, 0x9f800, 0x64000)]
def v2f(v):
    for va, ra, rs in SECS:
        if 0x400000 + va <= v < 0x400000 + va + rs:
            return v - 0x400000 - va + ra
va = int(sys.argv[2], 16); n = int(sys.argv[3]) if len(sys.argv) > 3 else 80
f = v2f(va)
md = Cs(CS_ARCH_X86, CS_MODE_32)
for i, ins in enumerate(md.disasm(exe[f:f + n * 16], va)):
    if i >= n:
        break
    print(f'{ins.address:x}  {ins.bytes.hex():22} {ins.mnemonic} {ins.op_str}')
