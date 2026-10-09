"""真实父路径查找工具的离线编译替身：验证刷新、读锁和逐级回退。"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[4]
SRC=ROOT/'cvs-plugin/src/com/intellij/cvsSupport2'
caller=(SRC/'changeBrowser/CvsCommittedChangesProvider.java').read_text()
assert 'ChangesUtil.findValidParentAccurately' not in caller, '仍调用内部 API'
assert 'CvsValidParent.find(filePath)' in caller
STUBS={
 'com.intellij.openapi.application.ApplicationManager': '''public class ApplicationManager {
 public static boolean read; public static ApplicationManager getApplication(){return new ApplicationManager();}
 public boolean isReadAccessAllowed(){return read;}
 }''',
 'com.intellij.openapi.application.ReadAction': '''public class ReadAction {
 public static <T> T compute(java.util.concurrent.Callable<T> action) {
 boolean before=ApplicationManager.read; ApplicationManager.read=true;
 try{return action.call();}catch(RuntimeException e){throw e;}catch(Exception e){throw new RuntimeException(e);}
 finally{ApplicationManager.read=before;}
 }}''',
 'com.intellij.openapi.vfs.VirtualFile': 'public class VirtualFile {}',
 'com.intellij.openapi.vfs.LocalFileSystem': '''public class LocalFileSystem {
 public static final java.util.Map<String,VirtualFile> files=new java.util.HashMap<>();
 public static final java.util.List<String> calls=new java.util.ArrayList<>();
 public static VirtualFile refreshed; public static boolean fail;
 public static LocalFileSystem getInstance(){return new LocalFileSystem();}
 public VirtualFile refreshAndFindFileByPath(String p){
 if(com.intellij.openapi.application.ApplicationManager.read)throw new AssertionError("锁内刷新");
 calls.add("refresh:"+p); if(fail)throw new IllegalStateException("refresh"); return refreshed;}
 public VirtualFile findFileByPath(String p){
 if(!com.intellij.openapi.application.ApplicationManager.read)throw new AssertionError("锁外查找");
 calls.add("find:"+p); return files.get(p);}
 }''',
 'com.intellij.openapi.vcs.FilePath': '''public class FilePath {
 public final String path; public com.intellij.openapi.vfs.VirtualFile file;
 public FilePath(String p){path=p;} public String getPath(){return path;}
 public com.intellij.openapi.vfs.VirtualFile getVirtualFile(){return file;}
 public FilePath getParentPath(){int n=path.lastIndexOf('/');return n<0?null:new FilePath(path.substring(0,n));}
 }'''
}
HARNESS='''import java.util.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vfs.*;
import com.intellij.cvsSupport2.util.CvsValidParent;
public class ParentTest {
 static void check(boolean v){if(!v)throw new AssertionError(LocalFileSystem.calls);}
 static void reset(){LocalFileSystem.calls.clear();LocalFileSystem.files.clear();LocalFileSystem.refreshed=null;LocalFileSystem.fail=false;ApplicationManager.read=false;}
 public static void main(String[] args){
 VirtualFile f=new VirtualFile();FilePath p=new FilePath("a/b/c");
 reset();p.file=f;check(CvsValidParent.find(p)==f);check(LocalFileSystem.calls.isEmpty());p.file=null;
 reset();LocalFileSystem.refreshed=f;check(CvsValidParent.find(p)==f);check(LocalFileSystem.calls.equals(List.of("refresh:a/b/c")));
 reset();LocalFileSystem.files.put("a/b",f);check(CvsValidParent.find(p)==f);
 check(LocalFileSystem.calls.equals(List.of("refresh:a/b/c","find:a/b/c","find:a/b")));check(!ApplicationManager.read);
 reset();ApplicationManager.read=true;LocalFileSystem.files.put("a",f);check(CvsValidParent.find(p)==f);
 check(LocalFileSystem.calls.equals(List.of("find:a/b/c","find:a/b","find:a")));check(ApplicationManager.read);
 reset();LocalFileSystem.files.put("a/b/c",f);check(CvsValidParent.find(p)==f);
 check(LocalFileSystem.calls.equals(List.of("refresh:a/b/c","find:a/b/c")));
 reset();check(CvsValidParent.find(p)==null);check(LocalFileSystem.calls.size()==4);
 reset();LocalFileSystem.fail=true;try{CvsValidParent.find(p);throw new AssertionError("异常被吞掉");}catch(IllegalStateException expected){}
 System.out.println("有效文件/刷新命中/锁内外父路径/无结果/异常传播：通过");
 }}'''
with tempfile.TemporaryDirectory(prefix='cvs-parent-') as temp:
 base=Path(temp); sources=[]
 for name,body in STUBS.items():
  p=base/(name.replace('.','/')+'.java');p.parent.mkdir(parents=True,exist_ok=True)
  p.write_text('package '+name.rsplit('.',1)[0]+';\n'+body);sources.append(str(p))
 harness=base/'ParentTest.java';harness.write_text(HARNESS)
 java=Path(os.environ['JAVA_HOME'])/'bin'
 subprocess.run([str(java/'javac'),'--release','17','-encoding','UTF-8','-d',temp,*sources,
                 str(SRC/'util/CvsValidParent.java'),str(harness)],check=True)
 subprocess.run([str(java/'java'),'-cp',temp,'ParentTest'],check=True)
