"""給 tools/re 的工具讀 LS12 封包（包裝 tools/ls12.py）。"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import ls12  # noqa: E402


def entries(path):
    return [d for _, _, _, _, d in ls12.read_archive(path)]
