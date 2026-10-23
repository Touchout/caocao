"""原版劇本執行軌跡（docs/methodology.md 方法 2）。

用 Unicorn 執行原版 Ekd5.exe 的劇本指令處理函式，記錄每條指令造成的「邏輯事件」：
  display  呼叫 0x42D5B0（彈出訊息／對話框）的文字
  guide    寫進歷史情報緩衝區（0x509000 起，長度在 0x508994）的條目
  var      整數變數（0x502000 起）、指標變數（0x506000 起）的變化
  flag     旗標（VM 物件 0x492F90 + 0x38 起的位元陣列）的變化
  test     條件指令的結果

跟 tools/re/eexemu.py 不同：這裡用**真實的** VM 物件位址，記憶體狀態跨指令保留，跟原版一樣一路執行下去。
區塊流程（section 條件、opcode 0/1、else）由這裡的 Python 驅動，規則同引擎 ScriptVM（docs/script.md）；
原版負責區塊流程的上層迴圈還沒找到，這是目前的已知限制。

用法：python3 tools/re/trace_original.py <Ekd5.exe> <S_nnn.eex> <scene> [輸出.jsonl] [出陣武將，逗號分隔，預設 0]
出陣（出戰名單）是玩家輸入，兩邊要給一樣的（引擎 :desktop:trace 的第 4 個參數）。
選擇題一律答 1（是／第一個選項），跟引擎端的軌跡輸出一致。
"""
import json
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from eexemu import RET_MAGIC, SCRIPT, STACK, EexEmu  # noqa: E402
from unicorn import UC_HOOK_CODE, UcError  # noqa: E402
from unicorn.x86_const import UC_X86_REG_EAX, UC_X86_REG_EBP, UC_X86_REG_ECX, UC_X86_REG_EIP, UC_X86_REG_ESP  # noqa: E402

VM = 0x492F90
FLAGS = VM + 0x38
INT_VARS = 0x502000
PTR_VARS = 0x506000
GUIDE = 0x509000
GUIDE_LEN = 0x508994
DISPLAY = 0x42D5B0          # 顯示訊息／對話框（stdcall，3 個參數：文字, 說話者, 樣式）
BATTLE_TALK = 0x45965D      # 戰場上的對話（stdcall，2 個參數：文字, 說話者）；0x14 在戰場模式且說話者確定時用這個
MESSAGE = 0x42F699          # 訊息視窗（cdecl：種類, 文字）；0x69 旁白用種類 2
CHOICE_MENU = 0x42DE7D      # 選擇框（stdcall，3 個參數：選項, 說話者, ?），回傳選到第幾個（從 0 起）
VICTORY = 0x44BE80          # 顯示勝利條件（thiscall ecx = 0x4B3D08，1 個參數：文字）
SORTIE_MENU = 0x45E6B3      # 出陣選擇畫面（stdcall，3 個參數），回傳選好的武將資料指標陣列（0 結尾）
ROSTER = 0x48B350           # 我軍出戰名單（u16 × 最多 20，0xFFFF 結尾）
BATTLE_LOOP = 0x44B328      # 0x5A 战场操作开始：進入戰場主迴圈（玩家操作），模擬器裡不執行
LOAD_ENTRY = 0x41B533       # 讀 Data.e5 某一項到緩衝區（stdcall：檔名, 項目, 目的位址），成功回傳 1
LOAD_STAR = 0x451313        # 讀 Star.e5（6.6 的道具 104 起）
LOAD_ALL_DATA = 0x41B639    # 載入全部 Data.e5（武將、道具、兵種、地形、第 2 項、策略）
CHARS_PTR = 0x4CEA00        # 武將執行期資料陣列的指標（1024 × 0x48）
ITEMS2_PTR = 0x4CEA04       # Data.e5 第 2 項載入後的陣列指標（127 × 0x28）
LOAD_BUF_PTR = 0x496F60     # 讀檔用的緩衝區指標
UNITS = 0x4A7B20            # 戰場單位表（250 × 0x30，[0] = 武將編號，空格 0xFFFF）
UNIT_SIZE = 0x30
UNIT_SLOTS = 250
TEMP_BUF = 0x48B100         # 暫存緩衝區物件
HEAP = 0x40000000           # 模擬器裡自己配置的區域（取代原版 new 出來的陣列）
TABLE_VA = 0x410A38         # 指令跳轉表，索引 = opcode − 2

# 參數表（引擎用的同一份）
SIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "engine", "core", "src", "main",
                        "resources", "ccz", "eex", "signatures.txt")
TESTS = {0x04, 0x05, 0x07, 0x13, 0x25, 0x26, 0x2D, 0x2E, 0x36, 0x37, 0x3C, 0x3F, 0x40, 0x41, 0x42, 0x43,
         0x54, 0x5E, 0x6E, 0x73, 0x79, 0x7B}


def load_signatures():
    sigs = {}
    for line in open(SIG_PATH, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        k, v = line.split(":", 1)
        v = v.strip()
        sigs[int(k, 16)] = None if v == "VAR" else [int(t, 16) for t in v.split()] if v else []
    return sigs


class Original(EexEmu):
    def __init__(self, exe, script, budget=3_000_000):
        super().__init__(exe, budget=budget)
        self.data = script
        self.reads = []  # eexemu 的讀參數 hook 會寫這裡（看的是假 VM，這裡不用）
        self.load_script(script)
        self.events = []
        self.mu.hook_add(UC_HOOK_CODE, self._on_display, begin=DISPLAY, end=DISPLAY)
        self.mu.hook_add(UC_HOOK_CODE, self._on_battle_talk, begin=BATTLE_TALK, end=BATTLE_TALK)
        self._stub(MESSAGE, 0, lambda a: {"display": self.cstr(a[1]), "kind": a[0]})
        self._stub(CHOICE_MENU, 3, lambda a: {"choice": a[1]})  # 一律選第一個（引擎軌跡也答 1）
        self._stub(VICTORY, 1, lambda a: {"display": self.cstr(a[0]), "victory": True})
        self._stub(BATTLE_LOOP, 1, lambda a: {"battle_start": True})
        self.sortie = [0]
        self.mu.hook_add(UC_HOOK_CODE, self._on_sortie, begin=SORTIE_MENU, end=SORTIE_MENU)

    def init_game_data(self, data_e5_path):
        """照原版開新遊戲時的方式載入 Data.e5：陣列放在 HEAP，攔截讀檔函式直接給資料，再執行原版的載入函式。"""
        import ls12_reader
        self.data_entries = ls12_reader.entries(data_e5_path)
        mu = self.mu
        mu.mem_map(HEAP, 0x400000)
        buf, chars, items2 = HEAP, HEAP + 0x100000, HEAP + 0x200000
        mu.mem_write(LOAD_BUF_PTR, struct.pack("<I", buf))
        mu.mem_write(CHARS_PTR, struct.pack("<I", chars))
        mu.mem_write(ITEMS2_PTR, struct.pack("<I", items2))
        # 暫存緩衝區物件（0x40159C 取得）：[0] 型別 1 = 記憶體、+4 大小、+0xC 起點。原版啟動時建立，這裡直接給一塊
        mu.mem_write(TEMP_BUF, struct.pack("<BxxxII", 1, 0x100000, 0) + struct.pack("<I", HEAP + 0x300000))
        self.mu.hook_add(UC_HOOK_CODE, self._on_load_entry, begin=LOAD_ENTRY, end=LOAD_ENTRY)
        self.star = open(os.path.join(os.path.dirname(data_e5_path), "Star.e5"), "rb").read()
        self.mu.hook_add(UC_HOOK_CODE, self._on_load_star, begin=LOAD_STAR, end=LOAD_STAR)
        return self.call(LOAD_ALL_DATA)

    def _on_load_star(self, mu, addr, size, user):
        # 0x451313：用 Windows 檔案 API 把 Star.e5 開頭最多 0x27D8 bytes 讀到 ecx（道具 104 起）。模擬器沒有檔案 API，直接給
        dest = mu.reg_read(UC_X86_REG_ECX)
        mu.mem_write(dest, self.star[:0x27D8])
        esp = mu.reg_read(UC_X86_REG_ESP)
        ret = struct.unpack("<I", mu.mem_read(esp, 4))[0]
        mu.reg_write(UC_X86_REG_ESP, esp + 4)
        mu.reg_write(UC_X86_REG_EIP, ret)

    def init_statics(self):
        """程式啟動時的靜態建構：戰場單位表 250 格的 [0] = 0xFFFF（0x408E69 → 建構函式 0x4012AF）。"""
        for slot in range(UNIT_SLOTS):
            self.mu.mem_write(UNITS + UNIT_SIZE * slot, struct.pack("<I", 0xFFFF))

    def _on_load_entry(self, mu, addr, size, user):
        esp = mu.reg_read(UC_X86_REG_ESP)
        ret, name, index, dest = struct.unpack("<IIII", mu.mem_read(esp, 16))
        data = self.data_entries[index]
        mu.mem_write(dest, data)
        mu.reg_write(UC_X86_REG_EAX, 1)
        mu.reg_write(UC_X86_REG_ESP, esp + 4 + 12)
        mu.reg_write(UC_X86_REG_EIP, ret)

    def call(self, va, *args):
        """呼叫原版函式（cdecl／stdcall 都可以；回傳 eax）。"""
        mu = self.mu
        esp = STACK + 0x80000
        for a in reversed(args):
            esp -= 4
            mu.mem_write(esp, struct.pack("<I", a))
        esp -= 4
        mu.mem_write(esp, struct.pack("<I", RET_MAGIC))
        mu.reg_write(UC_X86_REG_ESP, esp)
        mu.reg_write(UC_X86_REG_EBP, esp + 0x100)
        mu.emu_start(va, RET_MAGIC, count=50_000_000)
        if mu.reg_read(UC_X86_REG_EIP) != RET_MAGIC:
            raise RuntimeError(f"{va:#x} 沒有跑完，停在 {mu.reg_read(UC_X86_REG_EIP):#x}")
        return mu.reg_read(UC_X86_REG_EAX)

    def character(self, n):
        """武將 n 的執行期資料（0x48 bytes）。"""
        base = struct.unpack("<I", self.mu.mem_read(CHARS_PTR, 4))[0]
        return bytes(self.mu.mem_read(base + 0x48 * n, 0x48))

    def _on_display(self, mu, addr, size, user):
        esp = mu.reg_read(UC_X86_REG_ESP)
        ret, text, a2, a3 = struct.unpack("<IIII", mu.mem_read(esp, 16))
        self.events.append({"display": self.cstr(text), "arg2": a2 if a2 < 0x80000000 else a2 - (1 << 32), "style": a3})
        mu.reg_write(UC_X86_REG_EAX, 0)
        mu.reg_write(UC_X86_REG_ESP, esp + 4 + 12)
        mu.reg_write(UC_X86_REG_EIP, ret)

    def set_roster(self, chars):
        """出陣是玩家輸入：出陣選擇畫面一律回傳這些武將，名單也先寫好（沒有 0x4A 的關卡沿用上一場名單）。"""
        self.sortie = list(chars)
        data = b"".join(struct.pack("<H", c) for c in self.sortie) + b"\xff\xff"
        self.mu.mem_write(ROSTER, data)

    def _on_sortie(self, mu, addr, size, user):
        esp = mu.reg_read(UC_X86_REG_ESP)
        ret = struct.unpack("<I", mu.mem_read(esp, 4))[0]
        base = struct.unpack("<I", mu.mem_read(CHARS_PTR, 4))[0]
        lst = HEAP + 0x380000
        mu.mem_write(lst, b"".join(struct.pack("<I", base + 0x48 * c) for c in self.sortie) + b"\0" * 4)
        self.events.append({"sortie": self.sortie})
        mu.reg_write(UC_X86_REG_EAX, lst)
        mu.reg_write(UC_X86_REG_ESP, esp + 4 + 12)
        mu.reg_write(UC_X86_REG_EIP, ret)

    def _stub(self, addr, pops, record, ret=0):
        """攔截函式：記錄 record(參數) 回傳的事件，不執行原函式，直接回傳 ret（pops = stdcall 要彈掉的參數個數）。"""
        def hook(mu, a, size, user):
            esp = mu.reg_read(UC_X86_REG_ESP)
            words = struct.unpack("<5I", mu.mem_read(esp, 20))
            self.events.append(record(words[1:]))
            mu.reg_write(UC_X86_REG_EAX, ret)
            mu.reg_write(UC_X86_REG_ESP, esp + 4 + 4 * pops)
            mu.reg_write(UC_X86_REG_EIP, words[0])
        self.mu.hook_add(UC_HOOK_CODE, hook, begin=addr, end=addr)

    def _on_battle_talk(self, mu, addr, size, user):
        esp = mu.reg_read(UC_X86_REG_ESP)
        ret, text, speaker = struct.unpack("<III", mu.mem_read(esp, 12))
        self.events.append({"display": self.cstr(text), "arg2": speaker, "battle": True})
        mu.reg_write(UC_X86_REG_EAX, 0)
        mu.reg_write(UC_X86_REG_ESP, esp + 4 + 8)
        mu.reg_write(UC_X86_REG_EIP, ret)

    def cstr(self, addr, limit=4096):
        b = bytes(self.mu.mem_read(addr, limit))
        return b[:b.index(0)].decode("gbk", "replace") if 0 in b else b.decode("gbk", "replace")

    def snap(self):
        mu = self.mu
        return {
            "int": bytes(mu.mem_read(INT_VARS, 0x4000)),
            "ptr": bytes(mu.mem_read(PTR_VARS, 0x1000)),
            "flag": bytes(mu.mem_read(FLAGS, 0x2000)),
            "glen": struct.unpack("<I", mu.mem_read(GUIDE_LEN, 4))[0],
            "mem": [bytes(mu.mem_read(a, n)) for a, n in MEM_REGIONS],
        }

    def exec_op(self, op, pos):
        """在真實 VM 物件上執行一條指令（記憶體不還原）。回傳 (下一個位置, 回傳值, 完成與否)。"""
        mu = self.mu
        mu.mem_write(VM + 0xC, struct.pack("<I", SCRIPT + pos + 2))
        mu.mem_write(VM + 0x2E, bytes([op]))
        handler = struct.unpack("<I", mu.mem_read(TABLE_VA + 4 * (op - 2), 4))[0]
        esp = STACK + 0x80000
        esp -= 4
        mu.mem_write(esp, struct.pack("<I", VM))
        esp -= 4
        mu.mem_write(esp, struct.pack("<I", RET_MAGIC))
        mu.reg_write(UC_X86_REG_ESP, esp)
        mu.reg_write(UC_X86_REG_EBP, esp + 0x100)
        mu.reg_write(UC_X86_REG_ECX, VM)
        ok = False
        try:
            mu.emu_start(handler, RET_MAGIC, count=self.budget)
            ok = mu.reg_read(UC_X86_REG_EIP) == RET_MAGIC
        except UcError:
            pass
        end = struct.unpack("<I", mu.mem_read(VM + 0xC, 4))[0] - SCRIPT
        return end, mu.reg_read(UC_X86_REG_EAX), ok


# 逐 byte 比對的記憶體區域（引擎 Trace.kt 用同一份清單）
MEM_REGIONS = [
    (0x492F90 + 0x2044, 250 * 16),   # 出場配置表
    (0x500AD4, 2),                   # 0x46/0x47 執行次數
    (ROSTER, 42),                    # 出戰名單
    (HEAP + 0x100000, 1024 * 0x48),  # 武將資料
    (0x4A7B20, 250 * 0x30),          # 戰場單位表
    (0x508400, 0x66 * 14),           # 能力修正紀錄
    (0x50F000, 0x1000),              # 頭像表、R 形象表
]


def diff(before, after):
    out = []
    for (base, n), b, a in zip(MEM_REGIONS, before["mem"], after["mem"]):
        for i in range(n):
            if b[i] != a[i]:
                out.append({"mem": base + i, "value": a[i]})
    for i in range(0, len(before["int"]), 4):
        if before["int"][i:i + 4] != after["int"][i:i + 4]:
            out.append({"var": i // 4, "value": struct.unpack_from("<I", after["int"], i)[0]})
    for i in range(0, len(before["ptr"]), 4):
        if before["ptr"][i:i + 4] != after["ptr"][i:i + 4]:
            out.append({"ptr": i // 4, "value": struct.unpack_from("<I", after["ptr"], i)[0]})
    for i in range(len(before["flag"])):
        x = before["flag"][i] ^ after["flag"][i]
        for bit in range(8):
            if x & (0x80 >> bit):
                out.append({"flag": i * 8 + bit, "value": (after["flag"][i] >> (7 - bit)) & 1})
    return out


class Driver:
    """區塊流程（同 ScriptVM），逐條呼叫原版處理函式。"""

    def __init__(self, exe, script_path, roster=(0,)):
        self.data = open(script_path, "rb").read()
        self.orig = Original(exe, self.data)
        self.orig.init_statics()
        self.orig.init_game_data(os.path.join(os.path.dirname(exe), "Data.e5"))
        self.orig.set_roster(roster)
        self.sigs = load_signatures()
        self.trace = []

    def u16(self, p):
        return struct.unpack_from("<H", self.data, p)[0]

    def instr_len(self, pos):
        """指令長度（用參數表解碼；0x05 依數量）。"""
        op = self.u16(pos)
        p = pos + 2

        def read(t):
            nonlocal p
            tt = self.u16(p)
            if tt != t:
                raise ValueError(f"{pos:#x} op {op:#x} 參數型別 {tt:#x} != {t:#x}")
            if t == 4:
                p += 6
            elif t == 5:
                z = self.data.index(b"\0", p + 2)
                p = z + 1
            else:
                p += 4
            return struct.unpack_from("<H", self.data, p - 2)[0] if t not in (4, 5) else None

        if op == 0x05:
            for _ in range(2):
                n = self.u16(p + 2)
                read(0x35)
                p += 2 * n
        else:
            for t in self.sigs[op]:
                read(t)
        return p

    def sections(self, scene):
        first = struct.unpack_from("<I", self.data, 0x0A)[0]
        offsets = [struct.unpack_from("<I", self.data, q)[0] for q in range(0x0A, first, 4)]
        off = offsets[scene]
        p = off + 2
        out = []
        for _ in range(self.u16(off)):
            n = self.u16(p)
            out.append((p + 2, p + 2 + n))
            p += 2 + n
        return out

    def run_instr(self, pos):
        op = self.u16(pos)
        before = self.orig.snap()
        self.orig.events = []
        end, ret, ok = self.orig.exec_op(op, pos)
        after = self.orig.snap()
        rec = {"pos": pos, "op": op, "ret": ret & 0xFFFFFFFF, "ok": ok}
        if self.orig.events:
            rec["display"] = self.orig.events
        changes = diff(before, after)
        if after["glen"] != before["glen"]:
            raw = bytes(self.orig.mu.mem_read(GUIDE + before["glen"], after["glen"] - before["glen"]))
            rec["guide"] = {"mode": raw[1] if len(raw) > 1 else None, "text": raw[2:].split(b"\0")[0].decode("gbk", "replace")}
        if changes:
            rec["writes"] = changes
        self.trace.append(rec)
        expect = self.instr_len(pos)
        if op != 0x76 and ok and end != expect:
            rec["warn"] = f"結束位置 {end:#x} != {expect:#x}"
        return rec, (end if op == 0x76 else expect)

    def run_scene(self, scene, limit=200000):
        for start, end in self.sections(scene):
            self.run_block(start, end, limit)

    def run_block(self, start, end, limit):
        stack = [[start, end, True, True]]   # [起點, 結束, cond, lastSub]
        pc = start
        while stack and len(self.trace) < limit:
            top = stack[-1]
            if pc >= top[1]:
                stack.pop()
                continue
            op = self.u16(pc)
            if op == 0:
                if pc + 2 >= top[1]:
                    pc += 2
                    stack.pop()
                    continue
                n = self.u16(pc + 2)
                body = pc + 4
                if top[2]:
                    stack.append([body, body + n, True, True])
                    pc = body
                else:
                    pc = body + n
                top[2] = True
            elif op == 1:
                head = pc + 2
                hop = self.u16(head)
                after = self.instr_len(head)
                if hop == 0x03:
                    c = not top[3]
                elif hop in TESTS:
                    rec, _ = self.run_instr(head)
                    c = rec["ret"] & 0xFF != 0
                else:
                    self.run_instr(head)
                    c = True
                top[3] = c
                n = self.u16(after)
                body = after + 2
                if c:
                    stack.append([body, body + n, True, True])
                    pc = body
                else:
                    pc = body + n
            else:
                rec, nxt = self.run_instr(pc)
                if op in TESTS:
                    top[2] = top[2] and (rec["ret"] & 0xFF != 0)
                if op == 0x0C:
                    return
                if op == 0x0D:
                    stack.clear()
                    return
                pc = nxt


def main(argv):
    exe, script, scene = argv[1], argv[2], int(argv[3])
    out = argv[4] if len(argv) > 4 else None
    roster = [int(x) for x in argv[5].split(",")] if len(argv) > 5 else [0]
    d = Driver(exe, script, roster)
    d.run_scene(scene)
    lines = [json.dumps(r, ensure_ascii=False) for r in d.trace]
    if out:
        with open(out, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    else:
        print("\n".join(lines))
    print(f"{len(d.trace)} 條指令", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
