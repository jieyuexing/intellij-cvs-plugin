#!/usr/bin/env python3
"""Verify en/zh ResourceBundle key alignment for intellij-cvs-plugin.

Usage (from plugin root):
  python3 scripts/check_i18n_keys.py
Exit 0 if every English key has a Chinese counterpart (and vice versa).
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

PAIRS = [
    (
        ROOT / "cvs-core/resources/messages/CvsBundle.properties",
        ROOT / "cvs-core/resources/messages/CvsBundle_zh.properties",
    ),
    (
        ROOT / "javacvs-src/messages/JavaCvsSrcBundle.properties",
        ROOT / "javacvs-src/messages/JavaCvsSrcBundle_zh.properties",
    ),
    (
        ROOT / "smartcvs-src/messages/SmartCvsSrcBundle.properties",
        ROOT / "smartcvs-src/messages/SmartCvsSrcBundle_zh.properties",
    ),
]


def keys_of(path: Path) -> set[str]:
    keys: set[str] = set()
    if not path.is_file():
        raise FileNotFoundError(path)
    for line in path.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if not s or s.startswith("#") or "=" not in line:
            continue
        keys.add(line.split("=", 1)[0])
    return keys


def main() -> int:
    failed = False
    for en_path, zh_path in PAIRS:
        en = keys_of(en_path)
        zh = keys_of(zh_path)
        missing = sorted(en - zh)
        extra = sorted(zh - en)
        rel_en = en_path.relative_to(ROOT)
        rel_zh = zh_path.relative_to(ROOT)
        if missing or extra:
            failed = True
            print(f"FAIL {rel_en} <-> {rel_zh}")
            print(f"  en={len(en)} zh={len(zh)}")
            if missing:
                print(f"  missing in zh ({len(missing)}): {missing[:10]}")
            if extra:
                print(f"  extra in zh ({len(extra)}): {extra[:10]}")
        else:
            print(f"OK   {rel_zh} ({len(zh)} keys)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
