"""离线编译生产根扫描/映射计划；可用 CVS_TEST_ROOT 在旧源码快照上跑红样本。"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(os.environ.get('CVS_TEST_ROOT', Path(__file__).resolve().parents[4]))
SRC = ROOT / 'cvs-plugin/src/com/intellij/cvsSupport2'
vcs = (SRC / 'CvsVcs2.java').read_text()
assert all(name not in vcs for name in ['getCustomConvertor(', 'filterUniqueRoots(', 'isVersionedDirectory(']), '仍覆写内部根 API'
assert 'boolean allowsNestedRoots()' in vcs
with tempfile.TemporaryDirectory(prefix='cvs-root-mappings-') as tmp:
    base = Path(tmp)
    stubs = {
      'org/jetbrains/annotations/NotNull.java': 'package org.jetbrains.annotations; import java.lang.annotation.*; @Target({ElementType.TYPE_USE,ElementType.PARAMETER,ElementType.METHOD}) public @interface NotNull {}',
      'com/intellij/cvsSupport2/CvsUtil.java': 'package com.intellij.cvsSupport2; import com.intellij.openapi.vfs.*; public class CvsUtil { public static final String CVS="CVS", ENTRIES="Entries", CVS_ROOT_FILE="Root"; public static boolean fileIsUnderCvsMaybeWithVfs(VirtualFile f){return false;} }',
      'com/intellij/openapi/vfs/VirtualFile.java': 'package com.intellij.openapi.vfs; public record VirtualFile(String path) {public String getPath(){return path;}public boolean isDirectory(){return java.nio.file.Files.isDirectory(java.nio.file.Path.of(path));}}',
      'com/intellij/openapi/vcs/VcsKey.java': 'package com.intellij.openapi.vcs; public class VcsKey {}',
      'com/intellij/cvsSupport2/CvsVcs2.java': 'package com.intellij.cvsSupport2; public class CvsVcs2 {public static com.intellij.openapi.vcs.VcsKey getKey(){return new com.intellij.openapi.vcs.VcsKey();}}',
      'com/intellij/openapi/vcs/VcsRootChecker.java': 'package com.intellij.openapi.vcs; public abstract class VcsRootChecker {public abstract VcsKey getSupportedVcs();public boolean isRoot(com.intellij.openapi.vfs.VirtualFile f){return false;}public boolean isVcsDir(String n){return false;}public boolean areChildrenValidMappings(){return false;}}',
      'com/intellij/openapi/vfs/VirtualFileVisitor.java': 'package com.intellij.openapi.vfs; public class VirtualFileVisitor<T> { public static class Result {} public static final Result CONTINUE=new Result(), SKIP_CHILDREN=new Result(); public Result visitFileEx(VirtualFile f){return CONTINUE;} }',
      'com/intellij/openapi/vfs/VfsUtilCore.java': 'package com.intellij.openapi.vfs; public class VfsUtilCore {public static void visitChildrenRecursively(VirtualFile f, VirtualFileVisitor<?> v){}}',
      'com/intellij/openapi/vcs/FilePath.java': 'package com.intellij.openapi.vcs; import com.intellij.openapi.vfs.*; public class FilePath {public VirtualFile getVirtualFile(){return null;}}',
      'com/intellij/vcsUtil/VcsUtil.java': 'package com.intellij.vcsUtil; import com.intellij.openapi.vfs.*; import com.intellij.openapi.vcs.*; public class VcsUtil {public static FilePath getFilePath(VirtualFile f){return null;}}',
      'com/intellij/util/containers/ContainerUtil.java': 'package com.intellij.util.containers; import java.util.*; import java.util.function.*; public class ContainerUtil {public static <T,R> List<R> map(Collection<T> c, Function<T,R> f){return c.stream().map(f).toList();}}',
      'com/intellij/openapi/vcs/VcsDirectoryMapping.java': '''package com.intellij.openapi.vcs; import java.util.*;
public record VcsDirectoryMapping(String directory,String vcs,Object rootSettings) {
 public VcsDirectoryMapping(String d,String v){this(d,v,null);} public String getDirectory(){return directory;}
 public String getVcs(){return vcs;} public Object getRootSettings(){return rootSettings;} public boolean isDefaultMapping(){return directory.isEmpty();}
}''',
    }
    for name, content in stubs.items():
        p=base/name; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(content)
    harness = base/'RootsTest.java'
    harness.write_text(r'''
import java.nio.file.*; import java.util.*;
import com.intellij.cvsSupport2.cvsoperations.common.*;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
public class RootsTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static Path wc(Path p,String entries)throws Exception{Files.createDirectories(p.resolve("CVS")); Files.writeString(p.resolve("CVS/Entries"),entries); Files.writeString(p.resolve("CVS/Root"),"local");Files.writeString(p.resolve("CVS/Repository"),"module");return p;}
 static VcsDirectoryMapping m(Path p,String vcs){return new VcsDirectoryMapping(p.toString(),vcs);}
 public static void main(String[] args)throws Exception{
  Path c=Files.createTempDirectory("roots-").toAbsolutePath();
  Path a=wc(c.resolve("a"),"D/src////\n"), src=wc(a.resolve("src"),""), b=wc(c.resolve("b"),"");
  Files.writeString(src.resolve("CVS/Repository"),"module/src");
  Path nested=wc(a.resolve("vendor/deep"),""), git=wc(c.resolve("git/wc"),"");
  Files.createDirectories(c.resolve("broken/CVS")); Files.writeString(c.resolve("broken/CVS/Entries"),"");
  Files.createDirectories(c.resolve("generated/logs")); Files.createSymbolicLink(c.resolve("loop"),c);
  var progress=new FindAllRootsHelper.ScanProgress(){public void checkCanceled(){} public void onDirectory(Path p,int n,int r){}};
  var scan=FindAllRootsHelper.findVersionedPathsUnder(List.of(c),progress);
  check(FindAllRootsHelper.findVersionedPathsUnder(List.of(c,a,nested),progress).getRoots().equals(scan.getRoots()),"重叠扫描输入");
  check(scan.getRoots().equals(List.of(a,nested,b,git).stream().sorted().toList()),"根发现或嵌套根遗漏: "+scan.getRoots());
  check(!scan.getRoots().contains(src),"普通 CVS 子目录不可变成独立根");
  var checker=new CvsRootChecker();
  check(checker.isRoot(new com.intellij.openapi.vfs.VirtualFile(a.toString())),"磁盘 checker 不依赖 VFS children");
  check(!checker.isRoot(new com.intellij.openapi.vfs.VirtualFile(c.toString())) && checker.isVcsDir("CVS") && !checker.isVcsDir("cvs") && checker.areChildrenValidMappings(),"公开 checker 合同");
  Files.writeString(a.resolve("CVS/Entries.Log"),"R D/src////\n");
  check(FindAllRootsHelper.findVersionedPathsUnder(List.of(c),progress).getRoots().contains(src),"Entries.Log 删除");
  Files.writeString(a.resolve("CVS/Entries.Log"),"R D/src////\nA D/src////\n");
  check(!FindAllRootsHelper.findVersionedPathsUnder(List.of(c),progress).getRoots().contains(src),"Entries.Log 后条覆盖");
  Files.writeString(src.resolve("CVS/Repository"),"different-module");
  check(FindAllRootsHelper.findVersionedPathsUnder(List.of(c),progress).getRoots().contains(src),"同服务器不同模块的嵌套根");
  Files.writeString(src.resolve("CVS/Repository"),"module/src");
  Object settings=new Object(); var gitMap=m(c.resolve("git"),"Git");
  var original=List.of(new VcsDirectoryMapping(c.toString(),"CVS",settings),gitMap,m(c.resolve("excluded"),""));
  var result=CvsRootMappings.expand(original,List.of(c),scan.getRoots());
  check(result.contains(gitMap) && result.contains(original.get(2)),"其他 VCS/none 映射被删除");
  check(result.stream().noneMatch(x->x.getDirectory().equals(git.toString())),"侵入 Git 子树");
  for(Path p:List.of(a,b,nested))check(result.contains(new VcsDirectoryMapping(p.toString(),"CVS",settings)),"根或根设置丢失 "+p);
  check(!result.contains(original.get(0)),"容器未展开");
  check(CvsRootMappings.expand(result,List.of(c),scan.getRoots()).equals(result),"重复改写不幂等");
  check(CvsRootMappings.expand(original,List.of(c),List.of()).equals(original),"空扫描删除原映射");
  var explicit=List.of(m(c,"CVS"),m(a,"CVS"),m(nested,"CVS"));
  check(CvsRootMappings.expand(explicit,List.of(c,a,nested),scan.getRoots()).contains(m(nested,"CVS")),"嵌套显式映射被折叠");
  var def=List.of(new VcsDirectoryMapping("","CVS"),gitMap);
  var expandedDefault=CvsRootMappings.expand(def,List.of(c),scan.getRoots());
  check(expandedDefault.contains(def.get(0)) && expandedDefault.contains(m(a,"CVS")),"默认映射失效");
  RuntimeException cancel=new RuntimeException("cancel");int[] checks={0};
  try {FindAllRootsHelper.findVersionedPathsUnder(List.of(c),new FindAllRootsHelper.ScanProgress(){public void checkCanceled(){if(++checks[0]==4)throw cancel;} public void onDirectory(Path p,int n,int r){}});throw new AssertionError("取消未传播");} catch(RuntimeException e){if(e!=cancel)throw e;}
  System.out.println("根发现/磁盘 CVS/兄弟/嵌套/普通子目录/符号链接/取消/映射展开/其他 VCS/none/设置/幂等/空集/默认映射：通过");
 }
}''')
    java=Path(os.environ['JAVA_HOME'])/'bin'
    sources=list(base.rglob('*.java'))+[SRC/'cvsoperations/common/FindAllRootsHelper.java',SRC/'cvsoperations/common/CvsRootMappings.java',SRC/'cvsoperations/common/CvsRootChecker.java',ROOT/'testSource/com/intellij/cvsSupport2/cvsoperations/common/FindAllRootsHelperTest.java']
    subprocess.run([str(java/'javac'),'--release','17','-encoding','UTF-8','-d',tmp,*map(str,sources)],check=True)
    subprocess.run([str(java/'java'),'-Djava.io.tmpdir='+tmp,'-cp',tmp,'RootsTest'],check=True)

    subprocess.run([str(java/'java'),'-ea','-Djava.io.tmpdir='+tmp,'-cp',tmp,'com.intellij.cvsSupport2.cvsoperations.common.FindAllRootsHelperTest'],check=True)
