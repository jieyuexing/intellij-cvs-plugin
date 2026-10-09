#!/usr/bin/env python3
"""显式授权后初始化加密私钥和钥匙串口令；拒绝覆盖。"""
import os
from pathlib import Path
import secrets
import stat
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PRIVATE_KEY = Path.home() / 'Library/Application Support/intellij-cvs-plugin/signing/private-key.pem'
CERTIFICATE = ROOT / 'docs/signing-cert.pem'
SERVICE = 'intellij-cvs-plugin-signing-password'
KEYCHAIN = Path.home() / 'Library/Keychains/login.keychain-db'


def run(args, **kwargs):
    return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, **kwargs)


def check_parents(path):
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise RuntimeError('签名路径不得含 symlink')


def preflight():
    check_parents(PRIVATE_KEY)
    check_parents(CERTIFICATE)
    if PRIVATE_KEY.exists() or CERTIFICATE.exists():
        raise RuntimeError('私钥或证书已存在；拒绝覆盖')
    for suffix in ('private-key', 'certificate', 'password'):
        service = 'intellij-cvs-plugin-signing-' + suffix
        probe = run(['security', 'find-generic-password', '-s', service, str(KEYCHAIN)])
        if probe.returncode == 0:
            raise RuntimeError('钥匙串条目已存在；拒绝覆盖：' + service)
        if probe.returncode != 44:
            raise RuntimeError(f'钥匙串检查失败（退出码 {probe.returncode}）；停止，不重试')
    if PRIVATE_KEY.parent.exists():
        info = PRIVATE_KEY.parent.stat()
        if stat.S_IMODE(info.st_mode) != 0o700 or info.st_uid != os.getuid():
            raise RuntimeError('私钥目录必须由本人拥有且为 0700；不自动修改既有目录')


def main():
    scratch = Path(os.environ['TMPDIR']).resolve()
    if not scratch.is_dir() or scratch.stat().st_mode & 0o077:
        raise RuntimeError('TMPDIR 必须为调用方已建立的 0700 任务目录')
    preflight()
    password = secrets.token_hex(32)
    key_created = cert_created = password_created = False
    password_attempted = False
    password_verified = False
    old_umask = os.umask(0o077)
    try:
        PRIVATE_KEY.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        # 排他占位保证不覆盖并发产生的材料；openssl 只写本次拥有的文件。
        with PRIVATE_KEY.open('xb'):
            pass
        key_created = True
        result = run(['openssl', 'genpkey', '-aes-256-cbc', '-algorithm', 'RSA',
                      '-pkeyopt', 'rsa_keygen_bits:4096', '-out', str(PRIVATE_KEY), '-pass', 'stdin'],
                     input=(password + '\n').encode())
        if result.returncode:
            raise RuntimeError('加密私钥生成失败；秘密输出已抑制')
        with tempfile.TemporaryDirectory(prefix='signing-public-', dir=scratch) as directory:
            cert = Path(directory) / 'certificate.pem'
            result = run(['openssl', 'req', '-new', '-x509', '-sha256', '-days', '3650',
                          '-subj', '/CN=OpenCVS/O=jieyuexing', '-key', str(PRIVATE_KEY),
                          '-out', str(cert), '-passin', 'stdin'], input=(password + '\n').encode())
            if result.returncode:
                raise RuntimeError('证书生成失败；秘密输出已抑制')
            instruction = f'add-generic-password -a intellij-cvs-plugin -s {SERVICE} -w {password} "{KEYCHAIN}"\n'
            password_attempted = True
            result = run(['security', '-i'], input=instruction.encode())
            if result.returncode or b'SecKeychain' in result.stderr or b'Error' in result.stderr:
                raise RuntimeError(f'钥匙串写入失败（退出码 {result.returncode}）；停止')
            password_created = True
            check = run(['security', 'find-generic-password', '-s', SERVICE, '-w', str(KEYCHAIN)])
            if check.returncode or check.stdout.strip() != password.encode():
                raise RuntimeError(f'钥匙串回读校验失败（退出码 {check.returncode}）；停止')
            password_verified = True
            with CERTIFICATE.open('xb') as stream:
                cert_created = True
                stream.write(cert.read_bytes())
        print('签名初始化成功：RSA 4096 加密私钥 0600、目录 0700；钥匙串口令已回读验证。')
        print('口令条目：' + SERVICE)
    except BaseException:
        if key_created:
            PRIVATE_KEY.unlink(missing_ok=True)
        if cert_created:
            CERTIFICATE.unlink(missing_ok=True)
        # 授权拒绝/弹窗后不再调用钥匙串；明确交接本次可能产生的条目。
        if password_verified:
            # 回读已成功，后续仅为本地文件失败；删除本次新建的口令半成品。
            try:
                cleanup = run(['security', 'delete-generic-password', '-s', SERVICE, str(KEYCHAIN)])
                if cleanup.returncode == 0:
                    password_attempted = False
                    print('本次钥匙串口令半成品已删除。')
                else:
                    print(f'清理钥匙串失败（退出码 {cleanup.returncode}）；停止，不重试。')
            except (OSError, subprocess.TimeoutExpired):
                print('清理钥匙串失败或超时；停止，不重试。')
        if password_attempted:
            print('本次私钥/证书已清理；钥匙串口令' + ('已写入' if password_created else '状态不确定') +
                  '，停止访问。请协调者核对并删除本次半成品条目：' + SERVICE)
        raise
    finally:
        os.umask(old_umask)


if __name__ == '__main__':
    try:
        main()
    except subprocess.TimeoutExpired:
        raise SystemExit('命令超时：如有钥匙串授权弹窗，请用户处理；停止，不自动重试。')
    except (OSError, RuntimeError) as exc:
        raise SystemExit(str(exc))
