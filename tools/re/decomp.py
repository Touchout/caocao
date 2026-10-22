"""用 angr 把 Ekd5.exe 的函式反編譯成 C 虛擬碼。

需要獨立的 venv（系統 setuptools 裝不起來）：
  python3 -m venv ~/venv-re && ~/venv-re/bin/pip install angr
用法：~/venv-re/bin/python tools/re/decomp.py <Ekd5.exe> <VA>...
      ~/venv-re/bin/python tools/re/decomp.py <Ekd5.exe> -o <目錄> @<位址清單檔>   每個函式一個 .c
"""
import logging
import sys

import angr

logging.disable(logging.WARNING)
import os

exe = sys.argv[1]
args = sys.argv[2:]
out_dir = None
if args[:1] == ["-o"]:
    out_dir, args = args[1], args[2:]
    os.makedirs(out_dir, exist_ok=True)
targets = []
for a in args:
    if a.startswith("@"):
        targets += [int(x, 16) for x in open(a[1:]).read().split()]
    else:
        targets.append(int(a, 16))
proj = angr.Project(exe, auto_load_libs=False, load_options={"main_opts": {"base_addr": 0x400000}})
cfg = proj.analyses.CFGFast(function_starts=targets, force_complete_scan=False,
                            normalize=True, data_references=False,
                            start_at_entry=False, symbols=False, function_prologues=False)
for n, va in enumerate(targets):
    func = cfg.kb.functions.get(va)
    if func is None:
        text = f"// {va:#x}: 找不到函式\n"
    else:
        try:
            dec = proj.analyses.Decompiler(func, cfg=cfg.model)
            text = f"// ===== {va:#x} =====\n" + (dec.codegen.text if dec.codegen else "// 反編譯失敗") + "\n"
        except Exception as e:  # noqa: BLE001
            text = f"// {va:#x}: {e}\n"
    if out_dir:
        with open(os.path.join(out_dir, f"{va:08x}.c"), "w") as f:
            f.write(text)
        print(f"[{n + 1}/{len(targets)}] {va:#x}", flush=True)
    else:
        print(text)
