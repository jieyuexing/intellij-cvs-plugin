#!/usr/bin/env python3
"""Verify en/zh ResourceBundle key alignment for intellij-cvs-plugin.

Usage (from plugin root):
  python3 scripts/check_i18n_keys.py
Exit 0 if every English key has a Chinese counterpart (and vice versa),
and cvs-plugin/src plus cvs-core/src do not reference IDE-internal
ActionsBundle or IdeBundle.
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

FORBIDDEN_BUNDLES = (
    "com.intellij.idea.ActionsBundle",
    "com.intellij.ide.IdeBundle",
)

SCAN_ROOTS = (
    ROOT / "cvs-plugin/src",
    ROOT / "cvs-core/src",
)


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


def check_forbidden_ide_bundles() -> bool:
    hits: list[tuple[Path, str]] = []
    for scan_root in SCAN_ROOTS:
        for path in sorted(scan_root.rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            for needle in FORBIDDEN_BUNDLES:
                if needle in text:
                    hits.append((path.relative_to(ROOT), needle))
    if hits:
        print("FAIL IDE internal bundle references")
        for rel, needle in hits:
            print(f"  {rel}: {needle}")
        return True
    print("OK   no ActionsBundle/IdeBundle references in cvs-plugin/src or cvs-core/src")
    return False


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
    if check_forbidden_ide_bundles():
        failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
