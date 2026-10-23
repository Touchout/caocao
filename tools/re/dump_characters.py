"""在模擬器跑原版載入函式，輸出 1024 名武將的執行期資料（各 0x48 bytes），給 CharactersTest 當標準答案。

用法：python3 tools/re/dump_characters.py <遊戲目錄> [輸出檔，預設 out/expected_characters.bin]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trace_original as t  # noqa: E402


def main(argv):
    game = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join(os.path.dirname(__file__), "../../out/expected_characters.bin")
    o = t.Original(os.path.join(game, "Ekd5.exe"), open(os.path.join(game, "RS/S_000.eex"), "rb").read())
    o.init_game_data(os.path.join(game, "Data.e5"))
    with open(out, "wb") as f:
        for n in range(1024):
            f.write(o.character(n))
    print(out)


if __name__ == "__main__":
    main(sys.argv)
