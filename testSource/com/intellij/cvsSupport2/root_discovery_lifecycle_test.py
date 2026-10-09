"""编译完整生产 CvsRootDiscovery；用可控 EDT/后台队列检验发布和取消边界。"""
import os
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(os.environ.get('CVS_TEST_ROOT',Path(__file__).resolve().parents[4]))
SRC=ROOT/'cvs-plugin/src/com/intellij/cvsSupport2/cvsoperations/common'
STUBS={
'org.jetbrains.annotations.NotNull': 'import java.lang.annotation.*; @Target({ElementType.TYPE_USE,ElementType.PARAMETER,ElementType.METHOD}) public @interface NotNull {}',
'com.intellij.CvsBundle': 'public class CvsBundle {public static String message(String key,Object... args){return key;}}',
'com.intellij.cvsSupport2.CvsVcs2': 'public class CvsVcs2 {}',
'com.intellij.openapi.application.ApplicationManager': '''public class ApplicationManager {
 public static final ApplicationManager INSTANCE=new ApplicationManager(); public static ApplicationManager getApplication(){return INSTANCE;}
 public final java.util.Queue<Runnable> queue=new java.util.ArrayDeque<>(); public void invokeLater(Runnable r){queue.add(r);}
 public void flush(){int n=0;while(!queue.isEmpty()){if(++n>100)throw new AssertionError("EDT loop");queue.remove().run();}}
}''',
'com.intellij.openapi.diagnostic.Logger': 'public class Logger {public static Logger getInstance(Class<?> c){return new Logger();} public void info(String s){} public void warn(String s,Throwable t){}}',
'com.intellij.openapi.progress.ProgressIndicator': 'public class ProgressIndicator {public boolean cancel;public void checkCanceled(){if(cancel)throw new RuntimeException("cancel");} public void setIndeterminate(boolean b){} public void setText(String s){} public void setText2(String s){} public void setFraction(double d){}}',
'com.intellij.openapi.progress.Task': '''public class Task {public abstract static class Backgroundable {
 public Backgroundable(Object p,String t,boolean c){} public abstract void run(ProgressIndicator i);
 public void onSuccess(){} public void onCancel(){} public void onThrowable(Throwable t){}
}}''',
'com.intellij.openapi.progress.ProgressManager': '''public class ProgressManager {public static final ProgressManager INSTANCE=new ProgressManager();public static ProgressManager getInstance(){return INSTANCE;}
 public java.util.Queue<Task.Backgroundable> tasks=new java.util.ArrayDeque<>(); public void run(Task.Backgroundable t){tasks.add(t);}}''',
'com.intellij.openapi.project.Project': '''public class Project {public boolean disposed; public String getBasePath(){return "/project";} public boolean isDisposed(){return disposed;}
 public com.intellij.util.messages.MessageBusConnection bus=new com.intellij.util.messages.MessageBusConnection(); public com.intellij.util.messages.MessageBusConnection getMessageBus(){return bus;}
 public final com.intellij.cvsSupport2.cvsoperations.common.CvsRootMappingHistory history=new com.intellij.cvsSupport2.cvsoperations.common.CvsRootMappingHistory();
 public <T>T getService(Class<T> c){return c.cast(history);}}''',
'com.intellij.openapi.roots.ProjectRootManager': 'public class ProjectRootManager {public static ProjectRootManager getInstance(Object p){return new ProjectRootManager();}public com.intellij.openapi.vfs.VirtualFile[] getContentRoots(){return new com.intellij.openapi.vfs.VirtualFile[0];}}',
'com.intellij.openapi.util.io.FileUtil': 'public class FileUtil {public static String toSystemIndependentName(String s){return s.replace((char)92,\'/\');}}',
'com.intellij.openapi.vcs.VcsDirectoryMapping': '''public record VcsDirectoryMapping(String directory,String vcs,Object rootSettings){public VcsDirectoryMapping(String d,String v){this(d,v,null);}public String getDirectory(){return directory;}public String getVcs(){return vcs;}public Object getRootSettings(){return rootSettings;}public boolean isDefaultMapping(){return directory.isEmpty();}}''',
'com.intellij.openapi.vcs.VcsMappingListener': 'public interface VcsMappingListener {void directoryMappingChanged();}',
'com.intellij.openapi.vcs.ProjectLevelVcsManager': '''import java.util.*; public class ProjectLevelVcsManager {
 public static final Object VCS_CONFIGURATION_CHANGED=new Object();public static final ProjectLevelVcsManager INSTANCE=new ProjectLevelVcsManager();public static ProjectLevelVcsManager getInstance(Object p){return INSTANCE;}
 public List<VcsDirectoryMapping> mappings=List.of(); public int writes; public void runAfterInitialization(Runnable r){r.run();}
 public List<VcsDirectoryMapping> getDirectoryMappings(){return mappings;}public void setDirectoryMappings(List<VcsDirectoryMapping> m){mappings=m;writes++;}
}''',
'com.intellij.openapi.vcs.changes.VcsDirtyScopeManager': '''public class VcsDirtyScopeManager {public static final VcsDirtyScopeManager INSTANCE=new VcsDirtyScopeManager();public static VcsDirtyScopeManager getInstance(Object p){return INSTANCE;}
 public final java.util.Set<String> dirty=new java.util.HashSet<>();public void dirDirtyRecursively(com.intellij.openapi.vfs.VirtualFile f){dirty.add(f.getPath());}}''',
'com.intellij.openapi.vfs.VirtualFile': 'public record VirtualFile(String path){public String getPath(){return path;}public boolean isValid(){return true;}public boolean isDirectory(){return true;}}',
'com.intellij.openapi.vfs.LocalFileSystem': '''public class LocalFileSystem {public static final LocalFileSystem INSTANCE=new LocalFileSystem();public static LocalFileSystem getInstance(){return INSTANCE;}
 public boolean missing;public VirtualFile findFileByPath(String p){return new VirtualFile(p);}public VirtualFile refreshAndFindFileByPath(String p){return missing?null:new VirtualFile(p);}}''',
'com.intellij.util.messages.MessageBusConnection': '''public class MessageBusConnection {public com.intellij.openapi.vcs.VcsMappingListener listener;
 public MessageBusConnection connect(Object p){return this;}public void subscribe(Object topic,com.intellij.openapi.vcs.VcsMappingListener l){listener=l;}public void disconnect(){listener=null;}public void event(){if(listener!=null)listener.directoryMappingChanged();}}''',
'com.intellij.cvsSupport2.cvsoperations.common.CvsRootMappingHistory': 'public class CvsRootMappingHistory {public int records; public void record(Object a,Object b){records++;}}',
'com.intellij.cvsSupport2.cvsoperations.common.FindAllRootsHelper': '''import java.nio.file.*;import java.util.*;
public class FindAllRootsHelper {public interface ScanProgress {void checkCanceled();void onDirectory(Path p,int n,int r);}
 public static List<Path> roots=List.of(Path.of("/project/a"),Path.of("/project/a/nested"),Path.of("/project/b"));public static int errors;
 public static class ScanResult {public List<Path> getRoots(){return roots;}public int getErrors(){return errors;}public int getScannedDirectories(){return 10;}}
 public static ScanResult findVersionedPathsUnder(Collection<Path> p,ScanProgress progress,java.util.function.Predicate<Path> accept){progress.checkCanceled();return new ScanResult();}
 public static boolean isAtOrUnderAnyRoot(Path p,Collection<Path> roots){return roots.stream().anyMatch(p::startsWith);}
}''',
}
HARNESS=r'''
import java.util.*;import java.nio.file.*;
import com.intellij.cvsSupport2.cvsoperations.common.*;import com.intellij.cvsSupport2.CvsVcs2;
import com.intellij.openapi.application.*;import com.intellij.openapi.progress.*;import com.intellij.openapi.project.*;import com.intellij.openapi.vcs.*;import com.intellij.openapi.vfs.*;import com.intellij.openapi.vcs.changes.*;
public class LifecycleTest {
 static ApplicationManager app=ApplicationManager.getApplication();static ProjectLevelVcsManager manager=ProjectLevelVcsManager.INSTANCE;static ProgressManager pm=ProgressManager.INSTANCE;
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static VcsDirectoryMapping map(String s){return new VcsDirectoryMapping(s,"CVS");}
 static Task.Backgroundable take(){app.flush();check(pm.tasks.size()==1,"任务数 "+pm.tasks.size());return pm.tasks.remove();}
 static void success(Task.Backgroundable t){t.run(new ProgressIndicator());t.onSuccess();app.flush();}
 public static void main(String[] args){
  Project project=new Project();manager.mappings=List.of(map("/project"));var original=manager.mappings;
  CvsRootDiscovery discovery=new CvsRootDiscovery(project,new CvsVcs2());discovery.activate();var task=take();
  task.run(new ProgressIndicator());check(manager.mappings.equals(original)&&project.history.records==0,"后台提前发布");
  task.onSuccess();app.flush();check(manager.writes==1 && project.history.records==1,"映射迁移缺失");
  check(VcsDirtyScopeManager.INSTANCE.dirty.equals(Set.of("/project/a","/project/a/nested","/project/b")),"232 显式根标脏缺失");
  project.bus.event();app.flush();check(pm.tasks.isEmpty()&&manager.writes==1,"事件重入不幂等");
  // 用户在后台扫描完成前改配置，旧结果不可覆盖；新输入会再次排队。
  FindAllRootsHelper.roots=List.of(Path.of("/other/wc"));manager.mappings=List.of(map("/other"));project.bus.event();task=take();task.run(new ProgressIndicator());
  manager.mappings=List.of(map("/new"));task.onSuccess();check(manager.writes==1,"覆盖并发映射");task=take();
  task.onCancel();FindAllRootsHelper.roots=List.of(Path.of("/project/a"),Path.of("/project/a/nested"),Path.of("/project/b"));project.bus.event();app.flush();check(pm.tasks.isEmpty()&&manager.writes==1,"取消后自动重试或发布");
  manager.mappings=List.of(map("/failure"));project.bus.event();task=take();FindAllRootsHelper.errors=1;
  try {task.run(new ProgressIndicator());throw new AssertionError("扫描错误未拒绝");}catch(IllegalStateException e){task.onThrowable(e);}FindAllRootsHelper.errors=0;
  project.bus.event();app.flush();check(pm.tasks.isEmpty()&&manager.writes==1,"失败发布/重试");
  manager.mappings=original;project.bus.event();task=take();LocalFileSystem.INSTANCE.missing=true;
  try {task.run(new ProgressIndicator());throw new AssertionError("消失根未拒绝");}catch(IllegalStateException e){task.onThrowable(e);}LocalFileSystem.INSTANCE.missing=false;
  // 停用后旧任务不得发布；再次启用必须重新扫描，不能复用陈旧缓存。
  discovery.deactivate();app.flush();discovery.activate();task=take();task.run(new ProgressIndicator());discovery.deactivate();app.flush();task.onSuccess();app.flush();check(manager.writes==1,"停用后发布");
  discovery.activate();task=take();success(task);check(manager.writes==2,"重新启用未扫描");
  System.out.println("完整快照/EDT 发布/232 根标脏/幂等/并发改映射/取消抑制/IO 错误/根消失/停用/重新启用：通过");
 }
}'''
with tempfile.TemporaryDirectory(prefix='cvs-lifecycle-') as tmp:
 base=Path(tmp)
 for name,body in STUBS.items():
  p=base/(name.replace('.','/')+'.java');p.parent.mkdir(parents=True,exist_ok=True);p.write_text('package '+name.rsplit('.',1)[0]+';\n'+body)
 (base/'LifecycleTest.java').write_text(HARNESS)
 java=Path(os.environ['JAVA_HOME'])/'bin'
 subprocess.run([str(java/'javac'),'--release','17','-encoding','UTF-8','-d',tmp,*map(str,base.rglob('*.java')),str(SRC/'CvsRootDiscovery.java'),str(SRC/'CvsRootMappings.java')],check=True)
 subprocess.run([str(java/'java'),'-cp',tmp,'LifecycleTest'],check=True)
