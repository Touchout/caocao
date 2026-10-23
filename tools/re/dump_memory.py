"""在模擬器跑原版的啟動初始化與 Data 載入（0x41B639），輸出整個映像與 HEAP 陣列，給 NewGameTest 當標準答案。

輸出（out/expected_memory.bin）：映像 0x400000 起 SizeOfImage bytes，接著武將陣列 1024 × 0x48、第 2 項陣列 128 × 0x28。
用法：python3 tools/re/dump_memory.py <遊戲目錄> [輸出檔]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trace_original as t  # noqa: E402


def main(argv):
    game = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join(os.path.dirname(__file__), "../../out/expected_memory.bin")
    o = t.Original(os.path.join(game, "Ekd5.exe"), b"\0" * 16)
    o.init_statics()
    o.init_game_data(os.path.join(game, "Data.e5"))
    mu = o.mu
    with open(out, "wb") as f:
        f.write(bytes(mu.mem_read(0x400000, len(o.image_pristine))))
        f.write(bytes(mu.mem_read(t.HEAP + 0x100000, 1024 * 0x48)))
        f.write(bytes(mu.mem_read(t.HEAP + 0x200000, 128 * 0x28)))
    print(out)


if __name__ == "__main__":
    main(sys.argv)
