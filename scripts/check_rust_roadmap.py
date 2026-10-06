#!/usr/bin/env python3
"""Keep the Rust roadmap planning-only until its explicit experiment gate opens."""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path


ALLOWED_STATUSES = {"planned", "experiment", "adopted", "rejected", "retired"}
INACTIVE_STATUSES = {"planned", "rejected", "retired"}
SKIPPED_DIRECTORIES = {".git", ".gradle", ".intellijPlatform", "build", "out"}
RUST_MANIFESTS = {"Cargo.toml", "Cargo.lock", "rust-toolchain", "rust-toolchain.toml"}
RUST_DIRECTORIES = {"rust", "rust-src", "rust-native"}
GRADLE_RUST_PATTERNS = ("cargo", "rustc", "rustup", "corrosion", "org.mozilla.rust")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    return parser.parse_args()


def read_text(path: Path, errors: list[str]) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        errors.append(f"cannot read {path}: {exc}")
        return ""


def find_active_rust_files(root: Path) -> list[str]:
    findings: list[str] = []
    for directory, child_directories, files in os.walk(root):
        child_directories[:] = [
            name for name in child_directories
            if name not in SKIPPED_DIRECTORIES
        ]
        relative_directory = Path(directory).relative_to(root)
        for name in child_directories:
            if name.lower() in RUST_DIRECTORIES:
                findings.append(str(relative_directory / name))
        for name in files:
            path = Path(directory) / name
            if name in RUST_MANIFESTS or path.suffix == ".rs":
                findings.append(str(path.relative_to(root)))
    return sorted(findings)


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    errors: list[str] = []

    roadmap = root / "docs" / "rust-performance-roadmap.md"
    roadmap_text = read_text(roadmap, errors)
    match = re.search(r"^roadmap_status:\s*([a-z-]+)\s*$", roadmap_text, re.MULTILINE)
    status = match.group(1) if match else None
    if status not in ALLOWED_STATUSES:
        errors.append(f"roadmap_status must be one of {sorted(ALLOWED_STATUSES)}; got {status!r}")

    link = "(docs/rust-performance-roadmap.md)"
    for readme_name in ("README.md", "README_ZH.md"):
        if link not in read_text(root / readme_name, errors):
            errors.append(f"{readme_name} must link to docs/rust-performance-roadmap.md")

    if status in INACTIVE_STATUSES:
        for finding in find_active_rust_files(root):
            errors.append(f"roadmap_status={status} forbids active Rust artifact: {finding}")
        for build_name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties"):
            build_text = read_text(root / build_name, errors).lower()
            for pattern in GRADLE_RUST_PATTERNS:
                if pattern in build_text:
                    errors.append(f"roadmap_status={status} forbids Rust build wiring in {build_name}: {pattern}")

    if errors:
        for error in errors:
            print(f"ERROR {error}", file=sys.stderr)
        return 1

    print(f"OK   Rust roadmap status={status}; boundary is consistent")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
