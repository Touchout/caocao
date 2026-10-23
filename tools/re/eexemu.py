"""用 Unicorn 模擬 Ekd5.exe 的 EEX opcode 處理函式，量出每個指令實際讀了哪些參數。

只拿來決定「指令佔幾個 bytes、讀了哪些型別」，遊戲狀態一律是假的（全 0）。
"""
import os
import struct
import sys

from capstone import CS_ARCH_X86, CS_MODE_32, Cs
from unicorn import (UC_ARCH_X86, UC_HOOK_CODE, UC_HOOK_INTR, UC_HOOK_MEM_FETCH_UNMAPPED,
                     UC_HOOK_MEM_READ_UNMAPPED, UC_HOOK_MEM_WRITE_UNMAPPED,
                     UC_MODE_32, Uc, UcError)
from unicorn.x86_const import (UC_X86_REG_EAX, UC_X86_REG_EBP, UC_X86_REG_ECX,
                               UC_X86_REG_EDX, UC_X86_REG_EIP, UC_X86_REG_ESP)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from winapi_args import ARGS  # noqa: E402

IMAGE_BASE = 0x400000
TABLE_VA = 0x410A38      # 跳轉表，索引 = opcode - 2
READ_PARAM = 0x4183F7    # thiscall read_param(type)
THIS = 0x10000000
SCRIPT = 0x20000000
STACK = 0x30000000
RET_MAGIC = 0x0DEAD000
PAGE = 0x1000


class EexEmu:
    def __init__(self, exe_path, budget=200000):
        self.exe = open(exe_path, "rb").read()
        self.budget = budget
        self.mu = Uc(UC_ARCH_X86, UC_MODE_32)
        self._map_pe()
        self.mu.mem_map(THIS, 0x10000)
        self.mu.mem_map(STACK, 0x100000)
        self.mu.mem_map(RET_MAGIC, PAGE)
        self.script_size = 0
        self.dyn_pages = []
        self.stub_pages = []
        self.mu.hook_add(UC_HOOK_MEM_READ_UNMAPPED | UC_HOOK_MEM_WRITE_UNMAPPED, self._on_unmapped)
        self.mu.hook_add(UC_HOOK_MEM_FETCH_UNMAPPED, self._on_fetch_unmapped)
        self.mu.hook_add(UC_HOOK_CODE, self._on_read, begin=READ_PARAM, end=READ_PARAM)
        self.mu.hook_add(UC_HOOK_INTR, self._on_intr)
        self.cs = Cs(CS_ARCH_X86, CS_MODE_32)
        self.snapshot = self.mu.context_save()

    def _map_pe(self):
        exe = self.exe
        pe = struct.unpack_from("<I", exe, 0x3C)[0]
        nsec = struct.unpack_from("<H", exe, pe + 6)[0]
        opt = struct.unpack_from("<H", exe, pe + 20)[0]
        size = struct.unpack_from("<I", exe, pe + 24 + 56)[0]
        self.mu.mem_map(IMAGE_BASE, (size + PAGE - 1) & ~(PAGE - 1))
        self.mu.mem_write(IMAGE_BASE, exe[:0x400])
        self.image_end = IMAGE_BASE + size
        for i in range(nsec):
            s = pe + 24 + opt + 40 * i
            vs, va, rs, ra = struct.unpack_from("<IIII", exe, s + 8)
            self.mu.mem_write(IMAGE_BASE + va, exe[ra:ra + min(rs, max(vs, rs))])
        self.image_pristine = bytes(self.mu.mem_read(IMAGE_BASE, size))

    def _on_unmapped(self, mu, access, addr, size, value, user):
        base = addr & ~(PAGE - 1)
        try:
            mu.mem_map(base, PAGE)
        except UcError:
            return False
        self.dyn_pages.append(base)
        return True

    def _imports(self):
        """匯入表：IAT 裡的值（沒綁定，是名稱的 RVA）→ API 名稱。`call [IAT]` 會跳到這個值。"""
        exe = self.exe
        pe = struct.unpack_from("<I", exe, 0x3C)[0]
        nsec = struct.unpack_from("<H", exe, pe + 6)[0]
        opt = struct.unpack_from("<H", exe, pe + 20)[0]
        secs = [struct.unpack_from("<IIII", exe, pe + 24 + opt + 40 * i + 8) for i in range(nsec)]

        def off(rva):
            for vs, va, rs, ra in secs:
                if va <= rva < va + rs:
                    return ra + rva - va
            return None
        out = {}
        d = off(struct.unpack_from("<I", exe, pe + 24 + 104)[0])
        while d is not None:
            ft, name = struct.unpack_from("<I", exe, d + 16)[0], struct.unpack_from("<I", exe, d + 12)[0]
            if name == 0:
                break
            i = 0
            while True:
                thunk = struct.unpack_from("<I", exe, off(ft) + 4 * i)[0]
                if thunk == 0:
                    break
                if not thunk & 0x80000000:
                    o = off(thunk)
                    out[thunk] = exe[o + 2:o + 64].split(b"\0")[0].decode()
                i += 1
            d += 20
        return out

    def _on_fetch_unmapped(self, mu, access, addr, size, value, user):
        # 跳進 DLL（Windows API）：映射一頁，執行到時照 stdcall 彈掉參數、回傳 0（參數個數見 winapi_args.py）
        base = addr & ~(PAGE - 1)
        try:
            mu.mem_map(base, PAGE)
        except UcError:
            return False
        mu.mem_write(base, b"\xc3" * PAGE)
        self.stub_pages.append(base)
        if not hasattr(self, "import_names"):
            self.import_names = self._imports()
        mu.hook_add(UC_HOOK_CODE, self._on_api, begin=base, end=base + PAGE - 1)
        return True

    def _on_api(self, mu, addr, size, user):
        name = self.import_names.get(addr)
        n = ARGS.get(name, 0)
        esp = mu.reg_read(UC_X86_REG_ESP)
        ret = struct.unpack("<I", mu.mem_read(esp, 4))[0]
        value = 0
        if name == "GetTickCount":
            # 等待迴圈（0x09 延时 等）靠時間前進才會結束：每次呼叫前進 1 秒
            self.ticks = getattr(self, "ticks", 0) + 1000
            value = self.ticks
        mu.reg_write(UC_X86_REG_EAX, value)
        mu.reg_write(UC_X86_REG_ESP, esp + 4 + 4 * n)
        mu.reg_write(UC_X86_REG_EIP, ret)

    def _on_intr(self, mu, intno, user):
        # 假狀態全是 0，常會除以零：結果當 0，跳過該指令
        if intno != 0:
            mu.emu_stop()
            return
        eip = mu.reg_read(UC_X86_REG_EIP)
        ins = next(self.cs.disasm(bytes(mu.mem_read(eip, 16)), eip))
        mu.reg_write(UC_X86_REG_EAX, 0)
        mu.reg_write(UC_X86_REG_EDX, 0)
        mu.reg_write(UC_X86_REG_EIP, eip + ins.size)

    def _on_read(self, mu, addr, size, user):
        ptr = struct.unpack("<I", mu.mem_read(THIS + 0xC, 4))[0]
        esp = mu.reg_read(UC_X86_REG_ESP)
        want = struct.unpack("<I", mu.mem_read(esp + 4, 4))[0]
        self.reads.append((ptr - SCRIPT, want))

    def load_script(self, data):
        size = (len(data) + 0x10000) & ~(PAGE - 1)
        if size > self.script_size:
            if self.script_size:
                self.mu.mem_unmap(SCRIPT, self.script_size)
            self.mu.mem_map(SCRIPT, size)
            self.script_size = size
        self.mu.mem_write(SCRIPT, data + b"\0" * (self.script_size - len(data)))

    def run(self, opcode, pos):
        """執行 opcode 處理函式；pos 指向 opcode 之後的第一個 byte。

        回傳 (結束時的串流位置, [(參數位置, 期望型別)], 回傳值, 是否完整跑完)。"""
        mu = self.mu
        mu.context_restore(self.snapshot)
        mu.mem_write(IMAGE_BASE, self.image_pristine)
        mu.mem_write(THIS, b"\0" * 0x10000)
        mu.mem_write(STACK, b"\0" * 0x100000)
        for page in self.dyn_pages:
            mu.mem_write(page, b"\0" * PAGE)
        mu.mem_write(THIS + 0xC, struct.pack("<I", SCRIPT + pos))
        mu.mem_write(THIS + 0x2E, bytes([opcode]))
        handler = struct.unpack("<I", mu.mem_read(TABLE_VA + 4 * (opcode - 2), 4))[0]
        esp = STACK + 0x80000
        esp -= 4
        mu.mem_write(esp, struct.pack("<I", THIS))
        esp -= 4
        mu.mem_write(esp, struct.pack("<I", RET_MAGIC))
        mu.reg_write(UC_X86_REG_ESP, esp)
        mu.reg_write(UC_X86_REG_EBP, esp + 0x100)
        mu.reg_write(UC_X86_REG_ECX, THIS)
        self.reads = []
        finished = False
        try:
            mu.emu_start(handler, RET_MAGIC, count=self.budget)
            finished = mu.reg_read(UC_X86_REG_EIP) == RET_MAGIC
        except UcError:
            pass
        end = struct.unpack("<I", mu.mem_read(THIS + 0xC, 4))[0] - SCRIPT
        return end, self.reads, mu.reg_read(UC_X86_REG_EAX), finished
