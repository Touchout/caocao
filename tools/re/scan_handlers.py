"""從 Ekd5.exe 抽出每個 EEX opcode 處理函式讀取的參數型別（靜態分析）。"""
import struct, sys
from capstone import Cs, CS_ARCH_X86, CS_MODE_32
from capstone.x86 import X86_OP_IMM

EXE = sys.argv[1]
exe = open(EXE, 'rb').read()
md = Cs(CS_ARCH_X86, CS_MODE_32); md.detail = True
TABLE = 0xfe38          # 跳轉表檔案偏移，索引 = opcode - 2
READ = 0x4183f7         # 讀參數函式


def v2f(v):
    return v - 0x400000 - 0x1000 + 0x400


def scan(va, depth=0, seen=None):
    """線性掃描一個函式（跟進 jmp），回傳讀參數呼叫的型別序列。"""
    seen = seen if seen is not None else set()
    out = []
    todo = [va]
    while todo:
        start = todo.pop()
        if start in seen:
            continue
        last_push = None
        f = v2f(start)
        for ins in md.disasm(exe[f:f + 0x1000], start):
            if ins.address in seen:
                break
            seen.add(ins.address)
            if ins.mnemonic == 'push' and ins.operands[0].type == X86_OP_IMM:
                last_push = ins.operands[0].imm & 0xffff
            elif ins.mnemonic == 'call':
                if ins.op_str == hex(READ):
                    out.append((ins.address, last_push))
                last_push = None if ins.op_str != hex(READ) else last_push
            elif ins.mnemonic.startswith('j') and ins.operands[0].type == X86_OP_IMM:
                todo.append(ins.operands[0].imm)
                if ins.mnemonic == 'jmp':
                    break
            elif ins.mnemonic == 'ret':
                break
    return sorted(out)


for k in range(122):
    va = struct.unpack_from('<I', exe, TABLE + 4 * k)[0]
    reads = scan(va)
    print(f'{k + 2:#04x} {va:#x} ' + ' '.join('??' if t is None else f'{t:02x}' for _, t in reads))
