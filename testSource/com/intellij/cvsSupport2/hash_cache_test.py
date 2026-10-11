"""离线编译内容哈希缓存：时间漂移命中、真修改失效、特殊 Entries 不可播种；可用 CVS_TEST_ROOT 跑 649f094 红样本。"""
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(os.environ.get("CVS_TEST_ROOT", Path(__file__).resolve().parents[4]))
with tempfile.TemporaryDirectory(prefix="cvs-content-hash-") as tmp_name:
    tmp = Path(tmp_name)
    stubs = {
        "org/jetbrains/annotations/NonNls.java": "package org.jetbrains.annotations; public @interface NonNls {}",
        "com/intellij/util/text/SyncDateFormat.java": r'''
package com.intellij.util.text;
import java.text.DateFormat; import java.text.ParseException; import java.util.Date;
public class SyncDateFormat { private final DateFormat delegate; public SyncDateFormat(DateFormat d){delegate=d;}
 public synchronized String format(Date d){return delegate.format(d);} public synchronized Date parse(String s)throws ParseException{return delegate.parse(s);} }
''',
    }
    for relative, source in stubs.items():
        target = tmp / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source)
    harness = tmp / "ContentHashCacheTest.java"
    harness.write_text(r'''
package com.intellij.cvsSupport2.cvsoperations.javacvsSpecificImpls;
import java.io.*; import java.nio.file.*; import java.nio.file.attribute.FileTime; import java.util.*;
import org.netbeans.lib.cvsclient.admin.Entry;
public class ContentHashCacheTest {
 static void check(boolean b, String m) { if (!b) throw new AssertionError(m); }
 static Entry entry(String line) { return Entry.createEntryForLine(line); }
 public static void main(String[] args) throws Exception {
  Path root=Files.createTempDirectory("content-hash-data-"); Path store=root.resolve("cache.bin"); Path file=root.resolve("tracked.txt");
  Files.writeString(file,"unchanged", java.nio.charset.StandardCharsets.UTF_8);
  Entry normal=entry("/tracked.txt/1.2/Mon Jan 01 00:00:00 2024//");
  ContentHashCache cache=new ContentHashCache(store, () -> {});
  check(!cache.matches(file.toFile(),normal),"空缓存不得命中");
  cache.seed(file.toFile(),normal); check(cache.matches(file.toFile(),normal),"正常 Entries 应播种并命中");
  cache.flush();
  ContentHashCache restored=new ContentHashCache(store, () -> {});
  check(restored.matches(file.toFile(),normal),"落盘缓存应跨实例命中");
  Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
  check(restored.matches(file.toFile(),normal),"只改 mtime 不得令内容哈希失效");
  Files.writeString(file,"changed", java.nio.charset.StandardCharsets.UTF_8);
  check(!restored.matches(file.toFile(),normal),"真内容修改必须 miss");
  restored.record(file.toFile(),normal); check(restored.matches(file.toFile(),normal),"正常响应可更新缓存");
  Entry merged=entry("/tracked.txt/1.2/Result of merge//"); restored.record(file.toFile(),merged);
  check(!restored.matches(file.toFile(),normal),"合并结果必须删除旧缓存");

  Files.writeString(file,"revision two", java.nio.charset.StandardCharsets.UTF_8);
  Entry revisionTwo=entry("/tracked.txt/1.3/Tue Jan 02 00:00:00 2024//");
  restored.seed(file.toFile(),revisionTwo);
  check(restored.matches(file.toFile(),revisionTwo),"同一路径的新 revision 或 timestamp 必须重新播种");

  Path special=root.resolve("special.txt"); Files.writeString(special,"special");
  String[] specialLines={"/special.txt/0/Mon Jan 01 00:00:00 2024//", "/special.txt/-1.2/Mon Jan 01 00:00:00 2024//", "/special.txt/1.2/+Mon Jan 01 00:00:00 2024//", "/special.txt/1.2/Result of merge//"};
  for (String line:specialLines) { Entry e=entry(line); ContentHashCache specialCache=new ContentHashCache(root.resolve("special-"+Math.abs(line.hashCode())),()->{}); specialCache.seed(special.toFile(),e); check(!specialCache.matches(special.toFile(),e),"特殊 Entries 不得播种: "+line); }

  Path evictionStore=root.resolve("eviction.bin"); ContentHashCache tiny=new ContentHashCache(evictionStore,2,()->{});
  Path one=root.resolve("one.txt"), two=root.resolve("two.txt"), three=root.resolve("three.txt"); Files.writeString(one,"1"); Files.writeString(two,"2"); Files.writeString(three,"3");
  Entry oneEntry=entry("/one.txt/1.2/Mon Jan 01 00:00:00 2024//"), twoEntry=entry("/two.txt/1.2/Mon Jan 01 00:00:00 2024//"), threeEntry=entry("/three.txt/1.2/Mon Jan 01 00:00:00 2024//");
  tiny.seed(one.toFile(),oneEntry); tiny.seed(two.toFile(),twoEntry); tiny.seed(three.toFile(),threeEntry);
  check(!tiny.matches(one.toFile(),oneEntry) && tiny.matches(two.toFile(),twoEntry) && tiny.matches(three.toFile(),threeEntry),"容量满时须淘汰最早条目");

  boolean canceled=false; try { new ContentHashCache(root.resolve("cancel.bin"),()->{throw new RuntimeException("cancel");}).seed(file.toFile(),revisionTwo); } catch (RuntimeException expected) { canceled=true; }
  check(canceled,"取消必须传播，不能把半哈希当成命中");
  check(!restored.matches(root.resolve("missing.txt").toFile(),normal),"缺失文件必须 miss");
  Path parentFile=root.resolve("not-a-directory"); Files.writeString(parentFile,"x"); ContentHashCache unwritable=new ContentHashCache(parentFile.resolve("cache.bin"),()->{}); unwritable.seed(file.toFile(),revisionTwo); unwritable.flush(); check(unwritable.matches(file.toFile(),revisionTwo),"不可写存储不得影响内存缓存或 CVS 操作");
  Files.write(store,new byte[]{1,2,3});
  ContentHashCache corrupt=new ContentHashCache(store, () -> {});
  check(!corrupt.matches(file.toFile(),normal),"损坏缓存必须整体视为空");
  System.out.println("ContentHashCache: OK");
 }
}
''')
    sources = list(tmp.rglob("*.java")) + [
        ROOT / "javacvs-src/org/netbeans/lib/cvsclient/admin/Entry.java",
        ROOT / "javacvs-src/org/netbeans/lib/cvsclient/admin/InvalidEntryFormatException.java",
        ROOT / "cvs-plugin/src/com/intellij/cvsSupport2/cvsoperations/javacvsSpecificImpls/ContentHashCache.java",
    ]
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    subprocess.run([str(java / "javac"), "--release", "17", "-encoding", "UTF-8", "-d", str(tmp), *map(str, sources)], check=True)
    subprocess.run([str(java / "java"), "-Djava.io.tmpdir=" + str(tmp), "-cp", str(tmp), "com.intellij.cvsSupport2.cvsoperations.javacvsSpecificImpls.ContentHashCacheTest"], check=True, timeout=60)
