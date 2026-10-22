"""光榮 LS11/LS12 封包（*.e5）解析。

格式：
  0x000  16 bytes  magic，"Ls12" / "LS11" 加空白或 \\0 填充
  0x010  256 bytes 字典（壓縮時 code < 256 查這張表）
  0x110  目錄：每項 12 bytes big-endian (壓縮後大小, 原始大小, 偏移)，
         一直到第一筆資料的偏移為止（不一定有全 0 結尾）
  之後   各項資料。壓縮後大小 == 原始大小時是未壓縮直接存放。

用法：
  python3 tools/ls12.py list  <file.e5>
  python3 tools/ls12.py extract <file.e5> <out_dir>
"""
import os
import struct
import sys

MAGICS = (b"Ls12", b"LS12", b"LS11", b"Ls11")


class BitReader:
    def __init__(self, data):
        self.data = data
        self.pos = 0
        self.bit = 0

    def getbit(self):
        if self.pos >= len(self.data):
            raise EOFError
        b = (self.data[self.pos] >> (7 - self.bit)) & 1
        self.bit += 1
        if self.bit == 8:
            self.bit = 0
            self.pos += 1
        return b

    def getbits(self, n):
        v = 0
        for _ in range(n):
            v = (v << 1) | self.getbit()
        return v

    def getcode(self):
        # 前綴是一串 1 以 0 結束，共 n bits；值 = (2^n - 2) + 後面 n bits
        n = 0
        while True:
            n += 1
            if not self.getbit():
                break
        return (1 << n) - 2 + self.getbits(n)


def decompress(data, size, dictionary):
    out = bytearray()
    br = BitReader(data)
    while len(out) < size:
        code = br.getcode()
        if code < 256:
            out.append(dictionary[code])
        else:
            dist = code - 256
            length = br.getcode() + 3
            start = len(out) - dist
            for i in range(length):
                out.append(out[start + i])
    return bytes(out[:size])


def is_ls(buf):
    return buf[:4] in MAGICS


def read_archive(path):
    """回傳 [(index, raw_size, stored_size, offset, bytes)]。"""
    with open(path, "rb") as f:
        buf = f.read()
    if not is_ls(buf):
        raise ValueError(f"{path}: 不是 LS 封包")
    dictionary = buf[16:272]
    entries = []
    p = 272
    end = len(buf)
    while p + 12 <= end:
        csize, usize, off = struct.unpack_from(">3I", buf, p)
        p += 12
        if csize == 0 and usize == 0 and off == 0:
            break
        entries.append((csize, usize, off))
        end = min(end, off)
    result = []
    for i, (csize, usize, off) in enumerate(entries):
        chunk = buf[off:off + csize]
        data = chunk if csize == usize else decompress(chunk, usize, dictionary)
        result.append((i, usize, csize, off, data))
    return result


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 1
    cmd, path = argv[1], argv[2]
    items = read_archive(path)
    if cmd == "list":
        packed = sum(1 for it in items if it[1] != it[2])
        print(f"{path}: {len(items)} 項，其中 {packed} 項有壓縮")
        for i, usize, csize, off, _ in items:
            print(f"  {i:4d}  off=0x{off:08x}  size={usize:8d}  stored={csize:8d}")
    elif cmd == "extract":
        out = argv[3]
        os.makedirs(out, exist_ok=True)
        for i, _, _, _, data in items:
            with open(os.path.join(out, f"{i:04d}.bin"), "wb") as f:
                f.write(data)
        print(f"{len(items)} 項 → {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
