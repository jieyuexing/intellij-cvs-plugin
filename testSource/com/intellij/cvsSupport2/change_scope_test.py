"""提取生产 getChanges/路径计划/232 scope 方法，验证嵌套扫描调度和边界。
Entries 与 ChangelistBuilder 使用替身；真实 IDE Changes 发布另见人工清单。
"""
import os,re,subprocess,tempfile
from pathlib import Path
ROOT=Path(os.environ.get('CVS_TEST_ROOT',Path(__file__).resolve().parents[4]))
s=(ROOT/'cvs-plugin/src/com/intellij/cvsSupport2/cvsstatuses/CvsChangeProvider.java').read_text()
def method(name):
 m=re.search(r'^  (?:public|private|protected|static) [^\n]*\b'+name+r'\(',s,re.M)
 assert m,name
 start=s.index('{',m.start());end=start+1;depth=1
 while depth:
  depth+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[m.start():end]
methods='\n'.join(method(n) for n in ['getChanges','collapseNestedPaths','normalizeDirtyPath','addNormalizedPath','isCoveredByRecursivePath','getNestedCvsRoots','belongsToScope','containsSamePath'])
assert '!recursively && !belongsToScope(path, scope)' in s,'232 递归授权丢失'
assert 'if (myVcsManager.getVcsFor(path) != myVcs) return;' in s,'跨 VCS 边界未保护'
assert 'visitedDirectories.add(normalizeDirtyPath(path))' in s,'目录缺少去重'
HARNESS=r'''
import java.util.*;import java.nio.file.*;import java.lang.annotation.*;
public class ScopeTest {
 @Target({ElementType.TYPE_USE,ElementType.PARAMETER,ElementType.METHOD}) @interface NotNull {}
 static class Logger {boolean isDebugEnabled(){return false;}void debug(String s){}void warn(String s,Throwable t){}}
 static final Logger LOG=new Logger();
 record VirtualFile(String path){String getPath(){return path;}}
 record FilePath(String path){String getPath(){return path;}boolean isDirectory(){return true;}VirtualFile getVirtualFile(){return new VirtualFile(path);}}
 static class VfsUtilCore {static boolean isAncestor(VirtualFile a,VirtualFile b,boolean strict){return !a.equals(b)&&Path.of(b.path).startsWith(a.path);}}
 static class FileUtil {static boolean pathsEqual(String a,String b){return a.equals(b);}}
 static class ContainerUtil {static <T> HashSet<T> newHashSet(T[] a){return new HashSet<>(Arrays.asList(a));}}
 static class FindAllRootsHelper {static boolean isAtOrUnderAnyRoot(Path p,Collection<Path> roots){return roots.stream().anyMatch(p::startsWith);}}
 static class VcsDirtyScope {List<FilePath> recursive=List.of(new FilePath("/wc"),new FilePath("/wc/vendor/nested"));List<FilePath> files=List.of(new FilePath("/wc/src"));
  Collection<FilePath> getRecursivelyDirtyDirectories(){return recursive;}Collection<FilePath> getDirtyFiles(){return files;}Collection<FilePath> getDirtyFilesNoExpand(){return files;}
  boolean belongsTo(FilePath path){return false;} // 重现 232 明确 scope 根不属于自己的情形
 }
 static class ChangelistBuilder{} static class ProgressIndicator{} static class ChangeListManagerGate{} static class VcsException extends Exception{}
 final Object myVcs=new Object();
 static class Manager {VirtualFile[] getRootsUnderVcs(Object vcs){return new VirtualFile[]{new VirtualFile("/wc"),new VirtualFile("/wc/vendor/nested"),new VirtualFile("/other")};}}
 final Manager myVcsManager=new Manager();final Map<String,Integer> scanned=new LinkedHashMap<>();
 static List<Path> toPaths(Collection<VirtualFile> roots){return roots.stream().map(v->Path.of(v.path)).toList();}
 void showBranchImOn(Object b,Object s,Object r){}void processFile(Object p,Object b,Object r,Object i){}
 void processEntriesIn(VirtualFile dir,VcsDirtyScope scope,ChangelistBuilder b,boolean recursive,Collection<VirtualFile> roots,Collection<Path> paths,ProgressIndicator progress,Set<String> visited){
  if(!visited.add(dir.path))return;scanned.merge(dir.path,1,Integer::sum);
  // 普通 Entries 只列 src；vendor/nested 不在 Entries，必须由真实 getChanges 另行调度。
  if(dir.path.equals("/wc"))processEntriesIn(new VirtualFile("/wc/src"),scope,b,true,roots,paths,progress,visited);
 }
 METHODS
 public static void main(String[] args)throws Exception{
  ScopeTest t=new ScopeTest();VcsDirtyScope scope=new VcsDirtyScope();
  if(!belongsToScope(new FilePath("/wc"),scope)||!belongsToScope(new FilePath("/wc/src"),scope)||belongsToScope(new FilePath("/outside"),scope))throw new AssertionError("232 scope");
  t.getChanges(scope,new ChangelistBuilder(),new ProgressIndicator(),new ChangeListManagerGate());
  if(!t.scanned.equals(Map.of("/wc",1,"/wc/src",1,"/wc/vendor/nested",1)))throw new AssertionError(t.scanned);
  if(!collapseNestedPaths(List.of("/wc","/wc/src","/wc","/wc2")).equals(List.of("/wc","/wc2")))throw new AssertionError("前缀边界");
  System.out.println("Changes 调度：232 scope 根/显式目录/嵌套独立根/Entries 子目录/重叠去重/路径前缀：通过");
 }
}'''.replace('METHODS',methods)
with tempfile.TemporaryDirectory(prefix='cvs-scope-') as tmp:
 p=Path(tmp)/'ScopeTest.java';p.write_text(HARNESS);java=Path(os.environ['JAVA_HOME'])/'bin'
 subprocess.run([str(java/'javac'),'--release','17','-encoding','UTF-8','-d',tmp,str(p)],check=True)
 subprocess.run([str(java/'java'),'-cp',tmp,'ScopeTest'],check=True)
