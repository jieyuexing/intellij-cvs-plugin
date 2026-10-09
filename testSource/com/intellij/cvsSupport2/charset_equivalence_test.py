"""以发行包内原 CharsetToolkit 为 oracle；不启动 IDE、不连接 CVS。

运行：JAVA_HOME=<JBR25> python3 -B 此文件 <IDE Contents 或发行包根> [更多 IDE]
行为保持型重构采用等价性对照：旧代码本应通过行为断言，无意制造行为失败。
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[4]
HARNESS = r'''
import java.nio.charset.*;
import java.util.*;
import com.intellij.cvsSupport2.util.CvsCharsetUtil;
public class CharsetEquivalence {
  static java.lang.reflect.Method oracle;
  static int count;
  static void check(byte[] b, Charset fallback) throws Exception {
    String expected = (String)oracle.invoke(null, b, fallback);
    String actual = CvsCharsetUtil.bytesToString(b, fallback);
    if (!actual.equals(expected)) throw new AssertionError(fallback + " " + Arrays.toString(b));
    count++;
  }
  public static void main(String[] args) throws Exception {
    oracle = Class.forName("com.intellij.openapi.vfs.CharsetToolkit").getMethod("bytesToString", byte[].class, Charset.class);
    List<Charset> defaults = List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE,
      StandardCharsets.ISO_8859_1, Charset.forName("windows-1251"), Charset.forName("Shift_JIS"));
    byte[][] fixtures = { {}, {65,66}, {0,65}, {8,65}, {9,65}, {(byte)0xc3},
      {(byte)0xc0,(byte)0x80}, {(byte)0xe2,65}, {(byte)0xe2,65,66},
      {(byte)0xf8,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80},
      {(byte)0xfc,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80},
      {(byte)0xef,(byte)0xbb,(byte)0xbf}, {(byte)0xff,(byte)0xfe},
      {(byte)0xfe,(byte)0xff}, {0,0,(byte)0xfe,(byte)0xff}, {(byte)0xff,(byte)0xfe,0,0} };
    for (Charset fallback : defaults) {
      for (byte[] b : fixtures) check(b, fallback);
      for (String name : List.of("UTF-8","UTF-16LE","UTF-16BE","UTF-32LE","UTF-32BE")) {
        byte[] bom = switch(name) {
          case "UTF-8" -> new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf};
          case "UTF-16LE" -> new byte[]{(byte)0xff,(byte)0xfe};
          case "UTF-16BE" -> new byte[]{(byte)0xfe,(byte)0xff};
          case "UTF-32LE" -> new byte[]{(byte)0xff,(byte)0xfe,0,0};
          default -> new byte[]{0,0,(byte)0xfe,(byte)0xff};
        };
        byte[] content = "中文-é-𝄞".getBytes(Charset.forName(name));
        byte[] b = Arrays.copyOf(bom, bom.length+content.length);
        System.arraycopy(content,0,b,bom.length,content.length);
        check(b,fallback); check(content,fallback);
      }
      for (int a=0;a<256;a++) {
        check(new byte[]{(byte)a},fallback);
        for (int b=0;b<256;b++) check(new byte[]{(byte)a,(byte)b},fallback);
      }
      Random random = new Random(262232);
      for(int n=0;n<20000;n++) {
        byte[] b = new byte[random.nextInt(64)]; random.nextBytes(b); check(b,fallback);
      }
    }
    System.out.println("编码等价对照通过：" + count);
  }
}
'''

def main():
    if len(sys.argv) < 2:
        raise SystemExit("需提供 IDEA 发行包根目录")
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    with tempfile.TemporaryDirectory(prefix="cvs-charset-") as temp:
        harness = Path(temp) / "CharsetEquivalence.java"
        harness.write_text(HARNESS)
        stub = Path(temp) / "com/intellij/openapi/application/ApplicationInfo.java"
        stub.parent.mkdir(parents=True)
        stub.write_text('package com.intellij.openapi.application;\npublic class ApplicationInfo {\n  public static ApplicationInfo getInstance(){return new ApplicationInfo();}\n  public Build getBuild(){return new Build();}\n  public static class Build {public int getBaselineVersion(){return Integer.getInteger("test.baseline");}}\n}')
        subprocess.run([str(java / "javac"), "--release", "17", "-d", temp,
                        str(ROOT / "cvs-plugin/src/com/intellij/cvsSupport2/util/CvsCharsetUtil.java"), str(harness), str(stub)], check=True)
        for ide in sys.argv[1:]:
            print("对照目标：", ide, flush=True)
            product = Path(ide) / "product-info.json"
            if not product.exists(): product = Path(ide) / "Resources/product-info.json"
            baseline = json.loads(product.read_text())["buildNumber"].split(".")[0]
            subprocess.run([str(java / "java"), "-Dtest.baseline=" + baseline, "-cp", temp + os.pathsep + str(Path(ide) / "lib/*"),
                            "CharsetEquivalence"], check=True)

if __name__ == "__main__":
    main()
