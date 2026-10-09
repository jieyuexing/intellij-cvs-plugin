#!/usr/bin/env python3
"""校验本地发版产物，并按先资产、后更新索引的顺序发布。"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from itertools import zip_longest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "jieyuexing/intellij-cvs-plugin"
ORIGIN = f"git@github.com:{REPOSITORY}.git"
PLUGIN_ID = "io.github.jieyuexing.cvs"
PLUGIN_NAME = "CVS (Community)"
RAW_URL = f"https://raw.githubusercontent.com/{REPOSITORY}/main/updatePlugins.xml"
INDEX = "updatePlugins.xml"


class ReleaseError(Exception):
    """前置条件或校验失败；调用方必须停止。"""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReleaseError(message)


def version_parts(version: str) -> tuple[int, ...]:
    # 本仓只发布无前导零的数字稳定版；这一子集与 IntelliJ 比较规则一致。
    require(bool(re.fullmatch(r"(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*))+", version)),
            f"不支持的版本/tag 格式：{version!r}；只允许数字稳定版，如 262.0.1")
    return tuple(int(part) for part in version.split("."))


def compare_versions(left: str, right: str) -> int:
    for a, b in zip_longest(version_parts(left), version_parts(right), fillvalue=0):
        if a != b:
            return 1 if a > b else -1
    return 0


def check_version(version: str, tags: set[str]) -> None:
    version_parts(version)
    require(version not in tags, f"tag 已存在：{version}")
    for tag in sorted(tags):
        require(compare_versions(version, tag) > 0,
                f"版本 {version} 必须高于已有 tag {tag}")


def read_properties(path: Path) -> dict[str, str]:
    properties = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            require(key.strip() not in properties, f"重复属性：{key}")
            properties[key.strip()] = value.strip()
    for key in ("pluginVersion", "pluginSinceBuild", "pluginUntilBuild", "pluginName", "pluginGroup"):
        require(bool(properties.get(key)), f"缺少属性：{key}")
    require(properties["pluginName"] == "intellij-cvs-plugin", "pluginName 不符合本仓身份")
    require(properties["pluginGroup"] == "io.github.jieyuexing", "pluginGroup 不符合本仓身份")
    version_parts(properties["pluginVersion"])
    return properties


def verify_zip(path: Path, properties: dict[str, str]) -> None:
    descriptors = []
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            if entry.filename.endswith(".jar") and "/lib/" in entry.filename:
                with zipfile.ZipFile(io.BytesIO(archive.read(entry))) as jar:
                    for item in jar.infolist():
                        if item.filename == "META-INF/plugin.xml":
                            descriptors.append(ET.fromstring(jar.read(item)))
    require(len(descriptors) == 1, "zip 必须含唯一插件描述符")
    descriptor = descriptors[0]
    require(descriptor.tag == "idea-plugin", "plugin.xml 根元素错误")
    for name, expected in (("id", PLUGIN_ID), ("name", PLUGIN_NAME),
                           ("version", properties["pluginVersion"])):
        require(descriptor.findtext(name) == expected, f"plugin.xml 的 {name} 不匹配：应为 {expected}")
    compatibility = descriptor.find("idea-version")
    require(compatibility is not None, "plugin.xml 缺少 idea-version")
    for field, key in (("since-build", "pluginSinceBuild"), ("until-build", "pluginUntilBuild")):
        require(compatibility.get(field) == properties[key], f"plugin.xml 的 {field} 不匹配")


def asset_url(version: str) -> str:
    version_parts(version)
    return f"https://github.com/{REPOSITORY}/releases/download/{version}/intellij-cvs-plugin-{version}.zip"


def render_index(properties: dict[str, str]) -> bytes:
    root = ET.Element("plugins")
    plugin = ET.SubElement(root, "plugin", id=PLUGIN_ID,
                           version=properties["pluginVersion"], url=asset_url(properties["pluginVersion"]))
    ET.SubElement(plugin, "idea-version", {"since-build": properties["pluginSinceBuild"],
                                          "until-build": properties["pluginUntilBuild"]})
    ET.SubElement(plugin, "name").text = PLUGIN_NAME
    ET.indent(root, space="  ")
    return ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def command(*args: str, capture: bool = True) -> str:
    result = subprocess.run(args, cwd=ROOT, text=True,
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None)
    if result.returncode:
        raise ReleaseError(f"命令失败（退出码 {result.returncode}）：{' '.join(args)}\n"
                           + (result.stderr or ""))
    return (result.stdout or "").strip()


def git(*args: str) -> str:
    return command("git", *args)


def clean_main() -> str:
    require(git("branch", "--show-current") == "main", "必须在 main 分支")
    require(not git("status", "--porcelain=v1", "--untracked-files=all"), "工作树必须干净（含暂存和未跟踪文件）")
    require(git("remote", "get-url", "--all", "origin") == ORIGIN, "origin 必须为本仓唯一预期地址")
    require(git("remote", "get-url", "--push", "--all", "origin") == ORIGIN, "origin 推送地址不匹配")
    return git("rev-parse", "HEAD")


def remote_refs() -> tuple[str, set[str]]:
    refs = dict(line.split()[::-1] for line in git("ls-remote", "--refs", "origin", "refs/heads/main", "refs/tags/*").splitlines())
    require("refs/heads/main" in refs, "远端缺少 main")
    return refs["refs/heads/main"], {ref.removeprefix("refs/tags/") for ref in refs if ref.startswith("refs/tags/")}


def require_fast_forward(remote: str, target: str) -> None:
    require(git("rev-parse", "origin/main") == remote,
            "origin/main 缓存过期；人工核对并 fetch 后重新准备")
    require(git("merge-base", remote, target) == remote,
            "本地提交落后于远端 main 或已分叉；只允许相等或可快进的领先提交")


def receipt_path(path: Path) -> Path:
    path = path.expanduser().resolve()
    require(not path.is_relative_to(ROOT), "校验回执必须放在仓外调用方的任务临时目录")
    require(path.parent.is_dir(), "回执父目录必须已存在")
    return path


def prepare(path: Path) -> None:
    path = receipt_path(path)
    require(not path.exists(), "回执已存在；先审阅旧回执，使用新的回执文件名")
    commit = clean_main()
    remote, tags = remote_refs()
    require_fast_forward(remote, commit)
    properties = read_properties(ROOT / "gradle.properties")
    version = properties["pluginVersion"]
    check_version(version, tags | set(git("tag", "--list").splitlines()))
    xml = render_index(properties)
    require(not (ROOT / INDEX).exists() or (ROOT / INDEX).read_bytes() != xml,
            "当前版本 XML 已在版本提交内；必须保持 XML 为单独的后续提交")
    for args in ((sys.executable, "-B", "scripts/check_i18n_keys.py"),
                 (sys.executable, "-B", "scripts/check_rust_roadmap.py"),
                 ("./gradlew", "compileJava"), ("./gradlew", "buildPlugin")):
        command(*args, capture=False)
    artifact = ROOT / "build/distributions" / f"intellij-cvs-plugin-{version}.zip"
    verify_zip(artifact, properties)
    require(clean_main() == commit, "构建期间 HEAD 发生变化")
    require(remote_refs() == (remote, tags), "构建期间远端发生变化；重新核对")
    receipt = {"schema": 1, "commit": commit, "remote": remote,
               "properties": properties, "zip_sha256": digest(artifact.read_bytes()),
               "xml_sha256": digest(xml)}
    (ROOT / INDEX).write_bytes(xml)
    with path.open("x", encoding="utf-8") as stream:
        json.dump(receipt, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(f"准备完成：{version}\n产物：{artifact}\n回执：{path}\n请只提交 {INDEX}，不要手工推送 main。")


def fetch_url(url: str, method: str = "GET") -> bytes:
    request = urllib.request.Request(url, method=method, headers={"User-Agent": "intellij-cvs-release"})
    with urllib.request.urlopen(request, timeout=60) as response:
        require(response.status == 200, f"下载检查失败：HTTP {response.status}")
        require(response.url.startswith("https://"), "下载重定向必须使用 HTTPS")
        return response.read() if method == "GET" else b""


def publish(path: Path) -> None:
    completed = []
    attempted = "本地及远端只读校验"
    try:
        receipt = json.loads(receipt_path(path).read_text(encoding="utf-8"))
        require(receipt["schema"] == 1, "不支持的回执版本")
        commit, remote = receipt["commit"], receipt["remote"]
        require(all(re.fullmatch(r"[0-9a-f]{40}", ref) for ref in (commit, remote)), "回执提交格式错误")
        head = clean_main()
        require(git("rev-list", "--parents", "-n", "1", head).split() == [head, commit],
                "HEAD 必须是 prepare 版本提交的唯一直属 XML 提交（不允许合并提交）")
        require(git("diff", "--name-only", commit, head) == INDEX, "后续提交只能修改 updatePlugins.xml")
        properties = read_properties(ROOT / "gradle.properties")
        require(properties == receipt["properties"], "properties 与 prepare 回执不一致")
        version = properties["pluginVersion"]
        artifact = ROOT / "build/distributions" / f"intellij-cvs-plugin-{version}.zip"
        verify_zip(artifact, properties)
        require(digest(artifact.read_bytes()) == receipt["zip_sha256"], "zip 已变化；拒绝发布")
        xml = render_index(properties)
        require((ROOT / INDEX).read_bytes() == xml and digest(xml) == receipt["xml_sha256"], "XML 与校验回执不一致")
        current_remote, tags = remote_refs()
        require(current_remote == remote, "远端 main 已变化；停止并人工核对，不能自动重跑")
        require_fast_forward(current_remote, commit)
        check_version(version, tags | set(git("tag", "--list").splitlines()))
        command("gh", "--version")
        completed.append("本地回执、提交、zip、XML 与远端校验")

        attempted = "推送版本提交及同版本 tag（原子推送）"
        print(attempted, flush=True)
        git("push", "--atomic", "origin", f"{commit}:refs/heads/main", f"{commit}:refs/tags/{version}")
        completed.append(attempted)
        attempted = "创建 GitHub Release 并上传 zip"
        print(attempted, flush=True)
        command("gh", "release", "create", version, str(artifact), "--repo", REPOSITORY,
                "--verify-tag", "--title", version, "--notes", f"CVS (Community) {version}")
        completed.append(attempted)
        attempted = "验证公开资产 HEAD 与下载 SHA-256"
        print(attempted, flush=True)
        fetch_url(asset_url(version), "HEAD")
        require(digest(fetch_url(asset_url(version))) == receipt["zip_sha256"], "远端资产 SHA-256 不一致")
        completed.append(attempted)
        attempted = "推送更新 XML 提交"
        print(attempted, flush=True)
        require(clean_main() == head, "发布期间本地提交变化")
        require(remote_refs()[0] == commit, "发布期间远端 main 变化；不推送 XML")
        git("push", "origin", f"{head}:refs/heads/main")
        completed.append(attempted)
        attempted = "取回 raw XML 验证"
        print(attempted, flush=True)
        try:
            require(fetch_url(RAW_URL) == xml, "raw XML 尚未匹配已校验版本（可能是 CDN 缓存）")
        except (ReleaseError, OSError) as exc:
            print(f"远端发布操作已全部成功：{version}；raw XML 待人工复查：{exc}")
            for step in completed:
                print(f"已完成：{step}")
            print("请等待 CDN 刷新后，在本仓根执行以下只读命令（退出码 0 表示一致）：")
            print(f"curl --fail --silent --show-error --location '{RAW_URL}' | "
                  "python3 -c 'import pathlib, sys; "
                  'sys.exit(0 if sys.stdin.buffer.read() == pathlib.Path("updatePlugins.xml").read_bytes() else 1)' + "'")
            print("不要重跑 publish、重建 Release 或重传资产。")
            return
        completed.append(attempted)
        print(f"发布完成：{version}\n" + "\n".join(f"已完成：{step}" for step in completed))
    except (Exception, KeyboardInterrupt) as exc:
        print(f"发布停止：{exc}\n失败或状态不确定的步骤：{attempted}", file=sys.stderr)
        for step in completed:
            print(f"已完成：{step}", file=sys.stderr)
        print("不自动重试或回滚。先只读核对远端 main/tag、Release 资产与 raw XML；"
              "按 docs/release.md 的对应步骤人工恢复，不能直接重跑 publish。", file=sys.stderr)
        raise ReleaseError("发布未完成；请保留本次输出及 prepare 回执") from exc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "publish"))
    parser.add_argument("--receipt", type=Path, required=True, help="仓外任务目录中的校验回执 JSON")
    args = parser.parse_args()
    try:
        (prepare if args.action == "prepare" else publish)(args.receipt)
        return 0
    except (ReleaseError, OSError, ValueError, KeyError, zipfile.BadZipFile, ET.ParseError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("操作中断；停止执行。", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
