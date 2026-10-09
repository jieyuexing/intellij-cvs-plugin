"""离线编译真实宏映射工具，以 PathMacros 替身覆盖宏名和路径边界。"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
source = ROOT / 'cvs-plugin/src/com/intellij/cvsSupport2'
chooser = (source / 'ui/experts/SelectLocationStep.java').read_text()
assert 'FileChooserFactoryImpl' not in chooser, '仍依赖内部文件选择器工厂'
assert 'CvsPathMacros.getMacroMap()' in chooser
with tempfile.TemporaryDirectory(prefix='cvs-macros-') as temp:
    base = Path(temp)
    stub = base / 'com/intellij/openapi/application/PathMacros.java'
    stub.parent.mkdir(parents=True)
    stub.write_text('''package com.intellij.openapi.application;
import java.util.*;
public class PathMacros {
  public static final Map<String,String> values = new LinkedHashMap<>();
  public static PathMacros getInstance() { return new PathMacros(); }
  public Set<String> getAllMacroNames() { return values.keySet(); }
  public String getValue(String name) { return values.get(name); }
}''')
    harness = base / 'MacrosTest.java'
    harness.write_text(r'''import java.util.*;
import com.intellij.openapi.application.PathMacros;
import com.intellij.cvsSupport2.util.CvsPathMacros;
public class MacrosTest {
  public static void main(String[] args) {
    if (!CvsPathMacros.getMacroMap().isEmpty()) throw new AssertionError("空映射");
    PathMacros.values.put("HOME", "/含 空格/用户");
    PathMacros.values.put("SDK", "C:\\sdk\\x");
    PathMacros.values.put("UNRESOLVED", null);
    Map<String,String> result=CvsPathMacros.getMacroMap();
    if (result.size()!=3 || !"/含 空格/用户".equals(result.get("$HOME$")) ||
        !"C:\\sdk\\x".equals(result.get("$SDK$")) || !result.containsKey("$UNRESOLVED$") ||
        result.get("$UNRESOLVED$")!=null) throw new AssertionError(result);
    PathMacros.values.put("HOME", "/new");
    if (!"/含 空格/用户".equals(result.get("$HOME$"))) throw new AssertionError("快照被改写");
    if (!"/new".equals(CvsPathMacros.getMacroMap().get("$HOME$"))) throw new AssertionError("未重新读宏");
    System.out.println("宏映射空集/全部宏/空值/路径/快照：通过");
  }
}''')
    java=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(java/'javac'),'--release','17','-encoding','UTF-8','-d',temp,str(stub),
                    str(source/'util/CvsPathMacros.java'),str(harness)],check=True)
    subprocess.run([str(java/'java'),'-cp',temp,'MacrosTest'],check=True)
