"""在 Ekd5.exe 找引用某個絕對位址的指令，印出所在函式的起點（往回找 push ebp; mov ebp,esp）。

用法：python3 tools/re/xref.py <Ekd5.exe> <VA> [VA...]
      python3 tools/re/xref.py <Ekd5.exe> --callers <VA> [VA...]
"""
import struct, sys
from capstone import Cs, CS_ARCH_X86, CS_MODE_32

exe = open(sys.argv[1], 'rb').read()
SECS = [(0x1000, 0x400, 0x84e00), (0x86000, 0x85200, 0x4800), (0x8b000, 0x89a00, 0x6800),
        (0xce000, 0x9f800, 0x64000)]
md = Cs(CS_ARCH_X86, CS_MODE_32)


def f2v(o):
    for va, ra, rs in SECS:
        if ra <= o < ra + rs:
            return 0x400000 + va + o - ra


def v2f(v):
    for va, ra, rs in SECS:
        if 0x400000 + va <= v < 0x400000 + va + rs:
            return v - 0x400000 - va + ra


def func_start(va):
    f = v2f(va)
    for back in range(0, 0x3000):
        if exe[f - back:f - back + 3] == b'\x55\x8b\xec':
            return va - back
    return None




def callers(target):
    """直接 call rel32 到 target 的位置。"""
    out = []
    for va0, ra, rs in SECS:
        seg = exe[ra:ra + rs]
        i = seg.find(b'\xe8')
        while i != -1 and i + 5 <= len(seg):
            rel = struct.unpack_from('<i', seg, i + 1)[0]
            src = 0x400000 + va0 + i
            if src + 5 + rel == target:
                out.append(src)
            i = seg.find(b'\xe8', i + 1)
    return out


if __name__ == "__main__":
    if sys.argv[2] == "--callers":
        for target in sys.argv[3:]:
            t = int(target, 16)
            print(f"== callers of {t:#x}")
            for c in callers(t):
                fs = func_start(c)
                print(f"  {c:#x}  func {fs:#x}" if fs else f"  {c:#x}")
        sys.exit(0)
    for target in sys.argv[2:]:
        t = int(target, 16)
        pat = struct.pack('<I', t)
        print(f'== {t:#x}')
        i = exe.find(pat)
        while i != -1:
            va = f2v(i)
            if va:
                # 找出包含這 4 bytes 的指令
                for back in range(1, 8):
                    ins = next(md.disasm(exe[i - back:i - back + 16], va - back), None)
                    if ins and ins.address + ins.size >= va + 4 and pat in ins.bytes:
                        fs = func_start(ins.address)
                        print(f'  {ins.address:#x}  {ins.mnemonic} {ins.op_str:40}  func {fs:#x}' if fs else f'  {ins.address:#x} {ins.mnemonic} {ins.op_str}')
                        break
            i = exe.find(pat, i + 1)

