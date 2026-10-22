"""EEX 劇本解析與反組譯。

結構（詳見 docs/formats.md）：
  "EEX\\0" + 6 bytes + u32 scene 偏移表（第一個偏移 = 表的結尾）
  scene   = u16 section 數 + section*
  section = u16 長度 + block
  block   = 指令*，以 opcode 0 結尾
  opcode 0 後面如果還在區塊內，接 u16 長度 + 子區塊（條件成立時才執行）
  opcode 1 = 子事件：內部信息(名稱) + u16 長度 + 子區塊
  其他指令 = u16 opcode + 參數*；參數 = u16 型別 + 值（型別 4 是 u32，5 是 GBK 字串，其餘是 u16）

指令要讀幾個參數由 opcode 的處理函式決定，這裡用 tools/re/eexemu.py 模擬原版 exe 來量。

用法：
  python3 tools/eex.py <Ekd5.exe> <file.eex>...          反組譯到 stdout
  python3 tools/eex.py <Ekd5.exe> -o <目錄> <file.eex>... 每個檔案輸出成 <目錄>/<檔名>.txt
"""
import collections
import os
import struct
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "re"))


def load_names():
    """opcode 名稱，取自社群劇本編輯器的 CczString.ini 第一行。"""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eex_opcodes.txt")
    names = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                code, name = line.split(None, 1)
                names[int(code, 16)] = name
    return names


# 處理函式會直接改串流指標的指令，結束位置以讀到的參數為準
JUMP_OPS = {0x76}

# 同一個 opcode 先用模擬器量幾次，型別序列都一樣才改成直接解碼
LEARN_RUNS = 3


class ParseError(Exception):
    pass


class EexParser:
    def __init__(self, emu):
        self.emu = emu
        # opcode -> 參數型別序列；None 表示會隨資料變動（一律用模擬器量）
        self.sigs = {}
        self.seen = collections.Counter()

    def parse(self, data):
        if data[:4] != b"EEX\0":
            raise ParseError("magic")
        self.data = data
        self.emu.load_script(data)
        first = struct.unpack_from("<I", data, 0x0A)[0]
        offsets = [struct.unpack_from("<I", data, p)[0] for p in range(0x0A, first, 4)]
        scenes = []
        for n, off in enumerate(offsets):
            end = offsets[n + 1] if n + 1 < len(offsets) else len(data)
            scenes.append(self.parse_scene(off, end))
        return scenes

    def u16(self, p):
        return struct.unpack_from("<H", self.data, p)[0]

    def parse_scene(self, pos, end):
        count = self.u16(pos)
        pos += 2
        sections = []
        for _ in range(count):
            length = self.u16(pos)
            sections.append(self.parse_block(pos + 2, pos + 2 + length))
            pos += 2 + length
        if pos != end:
            raise ParseError(f"scene 結束位置 {pos:#x} != {end:#x}")
        return sections

    def parse_block(self, pos, end):
        out = []
        while pos < end:
            op = self.u16(pos)
            if op == 0:
                pos += 2
                if pos == end:
                    out.append((pos - 2, 0, []))
                    return out
                length = self.u16(pos)
                out.append((pos - 2, 0, [("len", length)]))
                out.append(("block", self.parse_block(pos + 2, pos + 2 + length)))
                pos += 2 + length
                if pos == end:
                    # section 本體：條件 + 0 + 長度 + 內容，內容延伸到 section 結尾
                    return out
            elif op == 1:
                start = pos
                head, pos = self.parse_ins(pos + 2)
                length = self.u16(pos)
                out.append((start, 1, [("len", length)]))
                out.append(("head", head))
                out.append(("block", self.parse_block(pos + 2, pos + 2 + length)))
                pos += 2 + length
            else:
                ins, pos = self.parse_ins(pos)
                out.append(ins)
        raise ParseError(f"區塊在 {end:#x} 沒有以 opcode 0 結尾（停在 {pos:#x}）")

    def parse_ins(self, pos):
        op = self.u16(pos)
        if op > 0x7B:
            raise ParseError(f"{pos:#x}: 不合法的 opcode {op:#x}")
        sig = self.sigs.get(op)
        if sig is not None and self.seen[op] >= LEARN_RUNS:
            try:
                params, p = self.decode(pos, op, sig)
                return (pos, op, params), p
            except ParseError:
                self.sigs[op] = None
        end, reads, _, _ = self.emu.run(op, pos + 2)
        p = pos + 2
        for at, _ in reads:
            if at < p:
                raise ParseError(f"{pos:#x} op {op:#x}: 參數位置不連續")
            p = at
        wants = tuple(w for _, w in reads)
        params, p = self.decode(pos, op, wants)
        if p != end and op not in JUMP_OPS:
            raise ParseError(f"{pos:#x} op {op:#x}: 參數結束 {p:#x}，引擎停在 {end:#x}")
        if op not in self.sigs:
            self.sigs[op] = wants
        elif self.sigs[op] != wants:
            self.sigs[op] = None
        self.seen[op] += 1
        return (pos, op, params), p

    def decode(self, pos, op, wants):
        """依型別序列解出參數；型別碼對不上就丟 ParseError。"""
        params = []
        p = pos + 2
        for want in wants:
            t = self.u16(p)
            if want == 1:
                params.append(("raw", t))
                p += 2
                continue
            if t != want:
                raise ParseError(f"{pos:#x} op {op:#x}: 參數型別 {t:#x}，引擎要 {want:#x}")
            if t == 4:
                v = struct.unpack_from("<i", self.data, p + 2)[0]
                p += 6
            elif t == 5:
                z = self.data.index(b"\0", p + 2)
                v = self.data[p + 2:z].decode("gbk", "replace")
                p = z + 1
            else:
                v = self.u16(p + 2)
                p += 4
            params.append((t, v))
        return params, p


def fmt_param(p):
    t, v = p
    if t == "len":
        return f"len={v:#x}"
    if t == "raw":
        return f"#{v}"
    if t == 5:
        return '"' + v.replace("\n", "\\n") + '"'
    if t == 4:
        return f"{t:02x}:{v}"
    return f"{t:02x}:{v:#x}" if v >= 0xEFFF else f"{t:02x}:{v}"


def dump(scenes, names, out):
    def walk(items, depth):
        for it in items:
            if it[0] == "block":
                walk(it[1], depth + 1)
                continue
            if it[0] == "head":
                it = it[1]
            pos, op, params = it
            name = names.get(op, "?")
            out.write(f"{pos:06x} {'  ' * depth}{op:02x} {name}  " + " ".join(fmt_param(p) for p in params) + "\n")
    for s, sections in enumerate(scenes):
        out.write(f"=== scene {s}\n")
        for n, block in enumerate(sections):
            out.write(f"--- section {n}\n")
            walk(block, 0)


def main(argv):
    from eexemu import EexEmu
    if len(argv) < 3:
        print(__doc__)
        return 1
    parser = EexParser(EexEmu(argv[1]))
    names = load_names()
    paths = argv[2:]
    out_dir = None
    if paths[:1] == ["-o"]:
        out_dir, paths = paths[1], paths[2:]
        os.makedirs(out_dir, exist_ok=True)
    failed = 0
    for path in paths:
        try:
            scenes = parser.parse(open(path, "rb").read())
        except ParseError as e:
            print(f"{path}: {e}", file=sys.stderr)
            failed += 1
            continue
        if out_dir:
            name = os.path.splitext(os.path.basename(path))[0] + ".txt"
            with open(os.path.join(out_dir, name), "w", encoding="utf-8") as f:
                dump(scenes, names, f)
        else:
            dump(scenes, names, sys.stdout)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
