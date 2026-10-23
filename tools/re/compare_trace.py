"""比對原版與引擎的劇本執行軌跡（docs/methodology.md 方法 2）。

用法：python3 tools/re/compare_trace.py <原版.jsonl> <引擎.jsonl> [最多列幾筆]
逐條比對：執行路徑（pos、op）、條件結果、彈出的文字、歷史情報、變數／旗標寫入。
路徑一分岔就停（之後的差異沒有意義）。回傳碼：0 = 完全一致，1 = 有差異。
"""
import json
import sys


def load(path):
    return [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]


def norm_writes(r):
    return sorted((k, w[k], w["value"]) for w in r.get("writes", []) for k in ("var", "ptr", "flag") if k in w)


def texts(r):
    out = []
    for d in r.get("display", []):
        if not isinstance(d, dict):
            out.append(d)
        elif "display" in d:  # 其他事件（choice、battle_start）不是文字
            out.append(d["display"])
    return out


def main(argv):
    orig, eng = load(argv[1]), load(argv[2])
    limit = int(argv[3]) if len(argv) > 3 else 30
    diffs = []
    n = min(len(orig), len(eng))
    for i in range(n):
        a, b = orig[i], eng[i]
        where = f"#{i} pos={a['pos']:#x} op={a['op']:#04x}"
        if (a["pos"], a["op"]) != (b["pos"], b["op"]):
            diffs.append(f"{where}：執行路徑分岔，引擎走到 pos={b['pos']:#x} op={b['op']:#04x}")
            break
        if "ret" in b and (a["ret"] & 0xFF != 0) != (b["ret"] != 0):
            diffs.append(f"{where}：條件結果 原版 {a['ret'] & 0xFF} / 引擎 {b['ret']}")
        if texts(a) != texts(b):
            diffs.append(f"{where}：彈出文字\n    原版 {texts(a)}\n    引擎 {texts(b)}")
        ga, gb = a.get("guide"), b.get("guide")
        if (ga and (ga["mode"], ga["text"])) != (gb and (gb["mode"], gb["text"])):
            diffs.append(f"{where}：歷史情報 原版 {ga} / 引擎 {gb}")
        wa, wb = norm_writes(a), norm_writes(b)
        if wa != wb:
            only_a = [w for w in wa if w not in wb]
            only_b = [w for w in wb if w not in wa]
            diffs.append(f"{where}：記憶體寫入\n    只有原版 {only_a[:8]}\n    只有引擎 {only_b[:8]}")
    else:
        if len(orig) != len(eng):
            diffs.append(f"指令數不同：原版 {len(orig)} / 引擎 {len(eng)}")
    unfinished = [r for r in orig if not r.get("ok", True)]
    print(f"比對 {n} 條指令：{'完全一致' if not diffs else f'{len(diffs)} 處不同'}")
    if unfinished:
        print(f"（原版有 {len(unfinished)} 條指令在模擬器裡沒跑完，例如 op " +
              ", ".join(sorted({f'{r['op']:#04x}' for r in unfinished})) + "，這些指令的比對不可靠）")
    for d in diffs[:limit]:
        print(" ", d)
    return 1 if diffs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
