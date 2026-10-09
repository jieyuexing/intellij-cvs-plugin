"""离线守护扩展 verifier 矩阵；加载/API 结果由真实 verifyPlugin 提供。"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]

def check(text):
    assert 'create("IU", "2023.2.8")' in text, "缺少 IDEA 下界"
    assert 'local(file(' in text and 'Applications/IntelliJ IDEA.app' in text, "缺少本机 IDEA 上界"
    assert 'create("IU", "2024.3.6")' in text, "缺少 IDEA 中间版本"
    assert 'create("WS", "2026.2")' in text, "缺少非 Java IDE"
    assert 'verifyCrossProduct' in text, "跨产品调查应可显式运行"

check((ROOT / "build.gradle.kts").read_text())
print("verifier 四目标矩阵：通过")
