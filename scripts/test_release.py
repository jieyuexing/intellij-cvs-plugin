#!/usr/bin/env python3
"""发版校验的离线夹具；不调用网络、gh 或真实构建。"""

from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from unittest.mock import patch

import release


class ReleaseFixture(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.properties_path = self.root / "gradle.properties"
        self.properties_path.write_text(
            "pluginName=intellij-cvs-plugin\npluginGroup=io.github.jieyuexing\n"
            "pluginVersion=262.0.1\npluginSinceBuild=232\npluginUntilBuild=262.*\n",
            encoding="utf-8")
        self.properties = release.read_properties(self.properties_path)

    def fixture_zip(self, field=None, value=None):
        descriptor = ET.fromstring(
            '<idea-plugin><id>io.github.jieyuexing.cvs</id><name>CVS (Community)</name>'
            '<version>262.0.1</version><idea-version since-build="232" until-build="262.*"/>'
            '</idea-plugin>')
        if field in ("since-build", "until-build"):
            descriptor.find("idea-version").set(field, value)
        elif field:
            descriptor.find(field).text = value
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/plugin.xml", ET.tostring(descriptor))
        target = self.root / "intellij-cvs-plugin-262.0.1.zip"
        with zipfile.ZipFile(target, "w") as archive:
            archive.writestr("intellij-cvs-plugin/lib/intellij-cvs-plugin-262.0.1.jar", jar.getvalue())
        return target



class ReleaseTests(ReleaseFixture):
    def test_00_bad_descriptors_fail(self):
        for field, value in (("id", "CVS"), ("version", "262.0"),
                             ("since-build", "262"), ("until-build", "263.*"),
                             ("name", "CVS")):
            with self.subTest(field=field), self.assertRaises(release.ReleaseError):
                release.verify_zip(self.fixture_zip(field, value), self.properties)

    def test_01_existing_tag_fails(self):
        with self.assertRaisesRegex(release.ReleaseError, "tag 已存在"):
            release.check_version("262.0.1", {"262.0", "262.0.1"})

    def test_good_zip_and_index(self):
        release.verify_zip(self.fixture_zip(), self.properties)
        root = ET.fromstring(release.render_index(self.properties))
        self.assertEqual("plugins", root.tag)
        self.assertEqual(1, len(root))
        plugin = root.find("plugin")
        self.assertEqual("io.github.jieyuexing.cvs", plugin.get("id"))
        self.assertEqual("262.0.1", plugin.get("version"))
        self.assertEqual("https://github.com/jieyuexing/intellij-cvs-plugin/releases/download/262.0.1/intellij-cvs-plugin-262.0.1.zip", plugin.get("url"))
        self.assertEqual({"since-build": "232", "until-build": "262.*"}, plugin.find("idea-version").attrib)
        self.assertEqual("CVS (Community)", plugin.findtext("name"))

    def test_version_order(self):
        for left, right, expected in (("262.0.1", "262.0", 1), ("262.0", "262.1", -1),
                                      ("262.10", "262.9", 1), ("262.0", "262.0.0", 0)):
            with self.subTest(left=left, right=right):
                self.assertEqual(expected, release.compare_versions(left, right))
        release.check_version("262.0.1", {"262.0"})
        for tags in ({"262.1"}, {"262.0.1.0"}, {"262.0-rc1"}):
            with self.subTest(tags=tags), self.assertRaises(release.ReleaseError):
                release.check_version("262.0.1", tags)
        for invalid in ("262.01", "v262.0", "262.0-beta", "../../x"):
            with self.subTest(version=invalid), self.assertRaises(release.ReleaseError):
                release.version_parts(invalid)


class SyncTests(ReleaseFixture):
    """真实本地 Git 提交图；远端读取和构建均隔离。"""

    def check_graph(self, relation):
        with patch.object(release, "ROOT", self.root):
            release.git("init", "-b", "main")
            release.git("config", "user.name", "离线夹具")
            release.git("config", "user.email", "fixture@example.invalid")
            release.git("config", "commit.gpgsign", "false")
            release.git("remote", "add", "origin", release.ORIGIN)
            release.git("add", "gradle.properties")
            release.git("commit", "-m", "基线")
            base = release.git("rev-parse", "HEAD")
            release.git("commit", "--allow-empty", "-m", "领先")
            ahead = release.git("rev-parse", "HEAD")
            if relation == "behind":
                release.git("checkout", "-B", "main", base)
                remote = ahead
            elif relation == "diverged":
                release.git("checkout", "-B", "main", base)
                release.git("commit", "--allow-empty", "-m", "分叉")
                remote = ahead
            else:
                remote = base if relation == "ahead" else ahead
            release.git("update-ref", "refs/remotes/origin/main", remote)
            original_command = release.command

            def command(*args, **kwargs):
                if args[0] != "git":
                    raise RuntimeError("已通过前置检查并进入构建")
                return original_command(*args, **kwargs)

            with patch.object(release, "remote_refs", return_value=(remote, {"262.0"})), \
                    patch.object(release, "receipt_path", lambda path: path), \
                    patch.object(release, "command", command), \
                    patch.object(release.urllib.request, "urlopen", side_effect=AssertionError("禁止网络")):
                if relation in ("equal", "ahead"):
                    with self.assertRaisesRegex(RuntimeError, "进入构建"):
                        release.prepare(self.root / "receipt.json")
                else:
                    with self.assertRaises(release.ReleaseError):
                        release.prepare(self.root / "receipt.json")

    def test_equal_passes(self):
        self.check_graph("equal")

    def test_ahead_passes(self):
        self.check_graph("ahead")

    def test_behind_rejected(self):
        self.check_graph("behind")

    def test_diverged_rejected(self):
        self.check_graph("diverged")


class PublishOrderTests(ReleaseFixture):
    """只在临时 fixture 中调用编排，所有外部入口均替换为离线桩。"""

    def fixture_publish(self, fail_at=None, stale_raw=False):
        artifact = self.fixture_zip()
        distributions = self.root / "build/distributions"
        distributions.mkdir(parents=True)
        artifact = artifact.rename(distributions / artifact.name)
        xml = release.render_index(self.properties)
        (self.root / release.INDEX).write_bytes(xml)
        commit, remote, head = "a" * 40, "b" * 40, "c" * 40
        receipt = self.root / "receipt.json"
        receipt.write_text(json.dumps({
            "schema": 1, "commit": commit, "remote": remote,
            "properties": self.properties, "zip_sha256": release.digest(artifact.read_bytes()),
            "xml_sha256": release.digest(xml),
        }), encoding="utf-8")
        events = []

        def event(name):
            events.append(name)
            if name == fail_at:
                raise release.ReleaseError("离线故障注入")

        def fake_git(*args):
            if args[0] == "rev-parse":
                return remote
            if args[0] == "merge-base":
                self.assertEqual(("merge-base", remote, commit), args)
                return remote
            if args[0] == "rev-list":
                return f"{head} {commit}"
            if args[0] == "diff":
                return release.INDEX
            if args[0] == "tag":
                return "262.0"
            if args[0] == "push":
                if "--atomic" in args:
                    self.assertIn(f"{commit}:refs/heads/main", args)
                    self.assertIn(f"{commit}:refs/tags/262.0.1", args)
                    event("push-version-tag")
                else:
                    self.assertEqual(("push", "origin", f"{head}:refs/heads/main"), args)
                    event("push-xml")
                return ""
            self.fail(f"意外 Git 调用：{args}")

        def fake_command(*args, **kwargs):
            if args == ("gh", "--version"):
                return "离线桩"
            self.assertEqual(("gh", "release", "create", "262.0.1"), args[:4])
            self.assertIn("--verify-tag", args)
            event("release")
            return ""

        def fake_fetch(url, method="GET"):
            if url == release.RAW_URL:
                event("raw")
                return b"old XML" if stale_raw else xml
            self.assertEqual(release.asset_url("262.0.1"), url)
            event(method)
            return artifact.read_bytes() if method == "GET" else b""

        with contextlib.ExitStack() as stack:
            for target, replacement in (
                ("ROOT", self.root), ("receipt_path", lambda path: path),
                ("clean_main", lambda: head), ("git", fake_git),
                ("command", fake_command), ("fetch_url", fake_fetch),
            ):
                stack.enter_context(patch.object(release, target, replacement))
            stack.enter_context(patch.object(release, "remote_refs", side_effect=[
                (remote, {"262.0"}), (commit, {"262.0", "262.0.1"})]))
            stack.enter_context(patch.object(release.subprocess, "run", side_effect=AssertionError("禁止子进程")))
            stack.enter_context(patch.object(release.urllib.request, "urlopen", side_effect=AssertionError("禁止网络")))
            output = io.StringIO()
            stack.enter_context(contextlib.redirect_stdout(output))
            stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
            if fail_at and fail_at != "raw":
                with self.assertRaises(release.ReleaseError):
                    release.publish(receipt)
            else:
                release.publish(receipt)
            if fail_at == "raw" or stale_raw:
                self.assertIn("远端发布操作已全部成功", output.getvalue())
                self.assertIn("curl --fail", output.getvalue())
                self.assertIn("raw XML 待人工复查", output.getvalue())
        return events

    def test_publish_order(self):
        self.assertEqual(["push-version-tag", "release", "HEAD", "GET", "push-xml", "raw"],
                         self.fixture_publish())

    def test_raw_cache_requires_manual_recheck(self):
        self.assertEqual(["push-version-tag", "release", "HEAD", "GET", "push-xml", "raw"],
                         self.fixture_publish(stale_raw=True))

    def test_failure_stops_without_retry(self):
        order = ["push-version-tag", "release", "HEAD", "GET", "push-xml", "raw"]
        for index, step in enumerate(order):
            with self.subTest(step=step):
                # 每个故障样本使用独立临时树。
                case = PublishOrderTests()
                case.setUp()
                try:
                    self.assertEqual(order[:index + 1], case.fixture_publish(step))
                finally:
                    case.doCleanups()


if __name__ == "__main__":
    unittest.main(verbosity=2)
