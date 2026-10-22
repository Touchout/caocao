"""從根函式往下 BFS 直接呼叫（call rel32），列出可達函式。

用法：python3 tools/re/callgraph.py <Ekd5.exe> <深度> <VA>...
"""
import struct, sys
from capstone import Cs, CS_ARCH_X86, CS_MODE_32
from capstone.x86 import X86_OP_IMM

exe = open(sys.argv[1], 'rb').read()
SECS = [(0x1000, 0x400, 0x84e00), (0x86000, 0x85200, 0x4800), (0x8b000, 0x89a00, 0x6800),
        (0xce000, 0x9f800, 0x64000)]
md = Cs(CS_ARCH_X86, CS_MODE_32); md.detail = True


def v2f(v):
    for va, ra, rs in SECS:
        if 0x400000 + va <= v < 0x400000 + va + rs:
            return v - 0x400000 - va + ra


def calls_of(va):
    """線性掃到 ret 為止（跟進條件跳轉目標），回傳呼叫目標。"""
    seen, out, todo = set(), set(), [va]
    while todo:
        a = todo.pop()
        if a in seen or v2f(a) is None:
            continue
        f = v2f(a)
        for ins in md.disasm(exe[f:f + 0x2000], a):
            if ins.address in seen:
                break
            seen.add(ins.address)
            if ins.mnemonic == 'call' and ins.operands[0].type == X86_OP_IMM:
                out.add(ins.operands[0].imm)
            elif ins.mnemonic.startswith('j') and ins.operands[0].type == X86_OP_IMM:
                todo.append(ins.operands[0].imm)
                if ins.mnemonic == 'jmp':
                    break
            elif ins.mnemonic == 'ret':
                break
    return {c for c in out if v2f(c) is not None}


def reach(roots, depth):
    level = {r: 0 for r in roots}
    frontier = list(roots)
    for d in range(1, depth + 1):
        nxt = []
        for f in frontier:
            for c in calls_of(f):
                if c not in level:
                    level[c] = d
                    nxt.append(c)
        frontier = nxt
    return level


if __name__ == '__main__':
    depth = int(sys.argv[2])
    lv = reach([int(a, 16) for a in sys.argv[3:]], depth)
    for d in range(depth + 1):
        fs = sorted(f for f, l in lv.items() if l == d)
        print(d, len(fs), ' '.join(hex(f) for f in fs[:40]))
