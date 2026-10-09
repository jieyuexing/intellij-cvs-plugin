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
import setup_signing
import subprocess


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
            '<idea-plugin><id>io.github.jieyuexing.cvs</id><name>OpenCVS</name>'
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
        self.assertEqual("OpenCVS", plugin.findtext("name"))

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
                    patch.object(release, "signing_environment", return_value={}), \
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
            "schema": 2, "commit": commit, "remote": remote,
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
                ("verify_signature", lambda path: None),
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


class SigningTests(ReleaseFixture):
    def test_00_existing_key_refuses_without_keychain(self):
        key = self.root / "private-key.pem"
        key.write_text("existing")
        with patch.object(setup_signing, "PRIVATE_KEY", key), patch.object(setup_signing, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "拒绝覆盖"):
                setup_signing.preflight()
            run.assert_not_called()
        self.assertEqual("existing", key.read_text())

    def test_01_existing_keychain_refuses(self):
        with patch.object(setup_signing, "PRIVATE_KEY", self.root / "absent"), \
                patch.object(setup_signing, "CERTIFICATE", self.root / "cert"), \
                patch.object(setup_signing, "run", return_value=subprocess.CompletedProcess([], 0)):
            with self.assertRaisesRegex(RuntimeError, "拒绝覆盖"):
                setup_signing.preflight()

    def test_02_prepare_missing_password_stops_before_build(self):
        key = self.root / "signing/private-key.pem"
        key.parent.mkdir(mode=0o700)
        key.write_text("-----BEGIN ENCRYPTED PRIVATE KEY-----")
        key.chmod(0o600)
        (self.root / "docs").mkdir()
        (self.root / "docs/signing-cert.pem").write_text("public fixture")
        with patch.object(release, "ROOT", self.root), patch.object(release, "PRIVATE_KEY", key), \
                patch.object(release, "receipt_path", lambda p: p), \
                patch.object(release, "clean_main", return_value="a" * 40), \
                patch.object(release, "remote_refs", return_value=("a" * 40, {"262.0"})), \
                patch.object(release, "require_fast_forward"), patch.object(release, "git", return_value=""), \
                patch.object(release, "command") as command, \
                patch.object(release.subprocess, "run", return_value=subprocess.CompletedProcess([], 44, "", "")):
            with self.assertRaisesRegex(release.ReleaseError, "钥匙串条目缺失"):
                release.prepare(self.root / "receipt.json")
            command.assert_not_called()
            self.assertFalse((self.root / release.INDEX).exists())

    def test_03_unsigned_or_invalid_signature_fails(self):
        artifact = self.fixture_zip()
        for diagnostic in ("unsigned", "invalid signature"):
            with patch.object(release.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, diagnostic, "")), \
                    contextlib.redirect_stdout(io.StringIO()), self.assertRaises(release.ReleaseError):
                release.verify_signature(artifact)

    def test_04_token_missing(self):
        with patch.object(release.subprocess, "run", return_value=subprocess.CompletedProcess([], 44, "", "")):
            with self.assertRaisesRegex(release.ReleaseError, "jetbrains-marketplace-token"):
                release.keychain_value(release.TOKEN_SERVICE)

    def test_05_first_upload_gate(self):
        with self.assertRaisesRegex(release.ReleaseError, "首次上传"):
            release.marketplace(self.root / "absent")

    def test_marketplace_missing_token_never_uploads(self):
        artifact = self.fixture_zip()
        directory = self.root / "build/distributions"
        directory.mkdir(parents=True)
        artifact = artifact.rename(directory / artifact.name)
        xml = release.render_index(self.properties)
        (self.root / release.INDEX).write_bytes(xml)
        receipt = self.root / "receipt.json"
        receipt.write_text(json.dumps({"schema": 2, "commit": "a" * 40,
            "properties": self.properties, "zip_sha256": release.digest(artifact.read_bytes()),
            "xml_sha256": release.digest(xml)}))
        with patch.object(release, "ROOT", self.root), patch.object(release, "receipt_path", lambda p: p), \
                patch.object(release, "clean_main", return_value="b" * 40), \
                patch.object(release, "git", side_effect=["b" * 40 + " " + "a" * 40, release.INDEX]), \
                patch.object(release, "verify_signature"), \
                patch.object(release.subprocess, "run", return_value=subprocess.CompletedProcess([], 44, "", "")), \
                patch.object(release, "secret_gradle") as upload:
            with self.assertRaisesRegex(release.ReleaseError, "jetbrains-marketplace-token"):
                release.marketplace(receipt, True)
            upload.assert_not_called()

    def test_setup_filesystem_failure_cleans_owned_materials(self):
        key = self.root / "signing/private-key.pem"
        cert = self.root / "missing-parent/cert.pem"
        def fake_run(args, **kwargs):
            if args[:2] == ["security", "find-generic-password"]:
                if "-w" in args:
                    return subprocess.CompletedProcess(args, 0, b"fixture-password\n", b"")
                return subprocess.CompletedProcess(args, 44, b"", b"")
            if args[:2] == ["openssl", "req"]:
                Path(args[args.index("-out") + 1]).write_text("public cert")
            return subprocess.CompletedProcess(args, 0, b"", b"")
        with patch.object(setup_signing, "PRIVATE_KEY", key), patch.object(setup_signing, "CERTIFICATE", cert), \
                patch.object(setup_signing.secrets, "token_hex", return_value="fixture-password"), \
                patch.object(setup_signing, "run", side_effect=fake_run) as run, \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(FileNotFoundError):
                setup_signing.main()
            self.assertFalse(key.exists())
            self.assertFalse(cert.exists())
            self.assertEqual("delete-generic-password", run.call_args.args[0][1])

    def test_secret_only_in_child_environment(self):
        secret = "fixture-secret-never-in-argv"
        with patch.object(release.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, secret, "")) as run, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            release.secret_gradle("signPlugin", secrets={"OPENCVS_SIGNING_PASSWORD": secret})
            self.assertNotIn(secret, repr(run.call_args.args))
            self.assertEqual(secret, run.call_args.kwargs["env"]["OPENCVS_SIGNING_PASSWORD"])
            self.assertNotIn(secret, output.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
