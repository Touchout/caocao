"""全部戰場劇本的軌跡比對摘要（docs/methodology.md 方法 2）。

先產生兩邊的軌跡：
  原版：每個 S_nnn 跑 tools/re/trace_original.py … out/sweep/orig_nnn.jsonl
  引擎：./gradlew :desktop:trace --args="<遊戲目錄> all <絕對路徑>/out/sweep"
再執行：python3 tools/re/sweep_compare.py [out/sweep] [--init]

每關列出：指令數、第一處不同（路徑分岔或寫入不同）；--init 只比開戰初始化那一條（0x27，標 battle_init），
並把差異依區域（單位表格號 + 偏移、武將 + 偏移）統計。
"""
import json
import os
import sys
from collections import Counter

UNITS = 0x4A7B20
CHARS = 0x40100000


def load(path):
    return [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]


def writes(rec):
    return {(k, w[k]): w["value"] for w in rec.get("writes", []) for k in ("mem", "var", "ptr", "flag") if k in w}


def region(key):
    kind, addr = key
    if kind != "mem":
        return kind
    if UNITS <= addr < UNITS + 250 * 0x30:
        return "單位+%02x" % ((addr - UNITS) % 0x30)
    if CHARS <= addr < CHARS + 1024 * 0x48:
        return "武將+%02x" % ((addr - CHARS) % 0x48)
    return "%#x" % addr


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    only_init = "--init" in argv
    d = args[0] if args else os.path.join(os.path.dirname(__file__), "../../out/sweep")
    stages = sorted(f[5:8] for f in os.listdir(d) if f.startswith("orig_") and f.endswith(".jsonl"))
    same = 0
    total = Counter()
    for n in stages:
        ep = os.path.join(d, f"engine_{n}.jsonl")
        if not os.path.exists(ep):
            print(f"S_{n}：沒有引擎軌跡")
            continue
        orig, eng = load(os.path.join(d, f"orig_{n}.jsonl")), load(ep)
        if only_init:
            oi = next((i for i, r in enumerate(orig) if r.get("battle_init")), None)
            if oi is None:
                print(f"S_{n}：原版沒有開戰初始化")
                continue
            if oi >= len(eng) or eng[oi]["pos"] != orig[oi]["pos"]:
                print(f"S_{n}：開戰初始化之前路徑就不同")
                continue
            a, b = writes(orig[oi]), writes(eng[oi])
            diff = [k for k in set(a) | set(b) if a.get(k) != b.get(k)]
            if not diff:
                same += 1
                continue
            c = Counter(region(k) for k in diff)
            total.update(c)
            print(f"S_{n}：{len(diff)} 處不同  " + ", ".join(f"{k}×{v}" for k, v in c.most_common(8)))
            continue
        first = None
        for i, (o, e) in enumerate(zip(orig, eng)):
            if o["pos"] != e["pos"] or o["op"] != e["op"]:
                first = f"#{i} 路徑分岔（原版 {o['pos']:#x} op {o['op']:#x}，引擎 {e['pos']:#x} op {e['op']:#x}）"
                break
            if writes(o) != writes(e) or (o["op"] in (0x04, 0x05) and (o["ret"] & 0xFF != 0) != (e.get("ret", 0) != 0)):
                first = f"#{i} pos {o['pos']:#x} op {o['op']:#x} 寫入不同"
                break
        if first is None and len(orig) != len(eng):
            first = f"指令數不同（原版 {len(orig)}，引擎 {len(eng)}）"
        if first is None:
            same += 1
        else:
            print(f"S_{n}：{first}")
    print(f"{len(stages)} 關，{same} 關一致")
    if total:
        print("差異區域合計：" + ", ".join(f"{k}×{v}" for k, v in total.most_common(20)))


if __name__ == "__main__":
    main(sys.argv)
