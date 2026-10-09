"""离线回归：完整编译 GetFileContentOperation；从实际源码提取缓存/消费方法。

IDE 服务和命令执行使用替身，不连接 CVS、不加载 IDE。提取的方法保持原文，
覆盖真实分支与磁盘缓存；公共执行层接线仍需 compileJava 和人工 IDE 验证。
运行：JAVA_HOME=<IDEA JBR> python3 -B testSource/com/intellij/cvsSupport2/empty_base_test.py
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
SRC = ROOT / "cvs-plugin/src/com/intellij/cvsSupport2"


def method(text, name):
    match = re.search(r"^  (?:public|private|protected) [^\n]*\b" + name + r"\(", text, re.M)
    if not match:
        raise AssertionError(name)
    start = text.index("{", match.start())
    depth = 1
    end = start + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[match.start():end]


STUBS = {
"com.intellij.CvsBundle": 'public class CvsBundle { public static String message(String s) { return s; } }',
"com.intellij.openapi.vcs.VcsException": 'public class VcsException extends Exception {}',
"com.intellij.openapi.diagnostic.Logger": 'public class Logger { public static Logger getInstance(Class<?> c) { return new Logger(); } public void error(Object o) { throw new AssertionError(o); } public void info(Object o) { throw new AssertionError(o); } public void assertTrue(boolean b) { if (!b) throw new AssertionError(); } }',
"com.intellij.openapi.vfs.VirtualFile": """public class VirtualFile {
  private final java.io.File f; public VirtualFile(java.io.File f) { this.f=f; }
  public String getPath() { return f.getPath(); } public String getName() { return f.getName(); }
  public VirtualFile getParent() { return new VirtualFile(f.getParentFile()); }
  public long getTimeStamp() { return f.lastModified(); }
}""",
"com.intellij.openapi.vcs.FilePath": """public class FilePath {
  private final com.intellij.openapi.vfs.VirtualFile f;
  public FilePath(com.intellij.openapi.vfs.VirtualFile f) { this.f=f; }
  public com.intellij.openapi.vfs.VirtualFile getVirtualFile() { return f; }
  public com.intellij.openapi.vfs.VirtualFile getVirtualFileParent() { return f.getParent(); }
  public java.io.File getIOFile() { return new java.io.File(f.getPath()); }
  public String getName() { return f.getName(); }
}""",
"com.intellij.openapi.util.io.FileUtil": """public class FileUtil {
  public static byte[] loadFileBytes(java.io.File f) throws java.io.IOException { return java.nio.file.Files.readAllBytes(f.toPath()); }
  public static void writeToFile(java.io.File f, byte[] b) throws java.io.IOException { java.nio.file.Files.write(f.toPath(), b); }
}""",
"com.intellij.util.ArrayUtilRt": 'public class ArrayUtilRt { public static final byte[] EMPTY_BYTE_ARRAY = new byte[0]; }',
"com.intellij.cvsSupport2.util.CvsVfsUtil": 'public class CvsVfsUtil { public static java.io.File getFileFor(com.intellij.openapi.vfs.VirtualFile f) { return new java.io.File(f.getPath()); } }',
"com.intellij.cvsSupport2.connections.CvsEnvironment": 'public class CvsEnvironment {}',
"com.intellij.cvsSupport2.connections.CvsRootProvider": """public class CvsRootProvider extends CvsEnvironment {
  public static CvsRootProvider createOn(java.io.File f) { return new CvsRootProvider(); }
  public static CvsRootProvider createOn(java.io.File f, CvsEnvironment e) { return new CvsRootProvider(); }
  public void changeAdminRootTo(java.io.File f) {} public void changeLocalRootTo(java.io.File f) {}
  public boolean isOffline() { return false; }
}""",
"com.intellij.cvsSupport2.errorHandling.CannotFindCvsRootException": 'public class CannotFindCvsRootException extends Exception {}',
"com.intellij.cvsSupport2.history.CvsRevisionNumber": 'public class CvsRevisionNumber { public CvsRevisionNumber(String s) {} }',
"com.intellij.cvsSupport2.cvsoperations.dateOrRevision.RevisionOrDate": """public interface RevisionOrDate {
  default com.intellij.cvsSupport2.history.CvsRevisionNumber getCvsRevisionNumber() { return new com.intellij.cvsSupport2.history.CvsRevisionNumber("1.2"); }
  default String getRevision() { return "1.2"; }
  default void setForCommand(org.netbeans.lib.cvsclient.command.checkout.CheckoutCommand c) {}
}""",
"com.intellij.cvsSupport2.cvsoperations.dateOrRevision.RevisionOrDateImpl": """public class RevisionOrDateImpl implements RevisionOrDate {
  public static RevisionOrDate createOn(com.intellij.openapi.vfs.VirtualFile f) { return new RevisionOrDateImpl(); }
  public static RevisionOrDate createOn(com.intellij.openapi.vfs.VirtualFile f, String s) { return new RevisionOrDateImpl(); }
}""",
"com.intellij.cvsSupport2.cvsoperations.dateOrRevision.SimpleRevision": 'public class SimpleRevision implements RevisionOrDate { public SimpleRevision(String s) {} }',
"org.netbeans.lib.cvsclient.admin.Entry": 'public class Entry { public String getRevision() { return "1.2"; } }',
"org.netbeans.lib.cvsclient.file.FileObject": 'public class FileObject {}',
"org.netbeans.lib.cvsclient.command.Command": 'public class Command {}',
"org.netbeans.lib.cvsclient.command.CommandAbortedException": 'public class CommandAbortedException extends Exception {}',
"org.netbeans.lib.cvsclient.command.checkout.CheckoutCommand": """public class CheckoutCommand extends org.netbeans.lib.cvsclient.command.Command {
  public CheckoutCommand(Object o) {} public void setRecursive(boolean b) {} public void addModule(String s) {} public void setPrintToOutput(boolean b) {}
}""",
"com.intellij.cvsSupport2.application.CvsEntriesManager": """public class CvsEntriesManager {
  public static CvsEntriesManager getInstance() { return new CvsEntriesManager(); }
  public String getRepositoryFor(com.intellij.openapi.vfs.VirtualFile f) { return "module"; }
  public org.netbeans.lib.cvsclient.admin.Entry getEntryFor(com.intellij.openapi.vfs.VirtualFile f, String s) { return new org.netbeans.lib.cvsclient.admin.Entry(); }
}""",
"com.intellij.cvsSupport2.cvsoperations.common.CvsExecutionEnvironment": """public class CvsExecutionEnvironment {
  public boolean aborted; public final java.util.List<com.intellij.openapi.vcs.VcsException> errors = new java.util.ArrayList<>();
  public CvsExecutionEnvironment getCvsCommandStopper() { return this; } public boolean isAborted() { return aborted; }
  public CvsExecutionEnvironment getErrorProcessor() { return this; }
  public java.util.List<com.intellij.openapi.vcs.VcsException> getErrors() { return errors; }
}""",
"com.intellij.cvsSupport2.cvsoperations.common.LocalPathIndifferentOperation": """public abstract class LocalPathIndifferentOperation {
  protected static final com.intellij.openapi.diagnostic.Logger LOG = com.intellij.openapi.diagnostic.Logger.getInstance(LocalPathIndifferentOperation.class);
  public static String scenario = "success"; public static int executions;
  public LocalPathIndifferentOperation(com.intellij.cvsSupport2.connections.CvsEnvironment e) {}
  protected abstract java.util.Collection<com.intellij.cvsSupport2.connections.CvsRootProvider> getAllCvsRoots();
  protected abstract org.netbeans.lib.cvsclient.command.Command createCommand(com.intellij.cvsSupport2.connections.CvsRootProvider r, CvsExecutionEnvironment e);
  protected abstract String getOperationName();
  public abstract boolean runInReadThread(); protected abstract boolean runInExclusiveLock();
  protected void commandCompleted(boolean success) {}
  public void gotEntry(org.netbeans.lib.cvsclient.file.FileObject f, org.netbeans.lib.cvsclient.admin.Entry e) {}
  public void messageSent(String m, byte[] b, boolean e, boolean t) {}
  public void binaryMessageSent(byte[] b) {}
  public void execute(CvsExecutionEnvironment env, boolean read) throws com.intellij.openapi.vcs.VcsException, org.netbeans.lib.cvsclient.command.CommandAbortedException {
    executions++; createCommand(getAllCvsRoots().iterator().next(), env);
    if (scenario.startsWith("deleted")) gotEntry(null, null);
    else if (scenario.equals("tag-only")) messageSent("fname file", "fname file".getBytes(), false, true);
    else if (scenario.equals("empty")) binaryMessageSent(new byte[0]);
    else if (!scenario.equals("no-content") && !scenario.equals("abort")) {
      messageSent("VERS: 1.2", "VERS: 1.2".getBytes(), true, false);
      messageSent("base", "base".getBytes(), false, false);
    }
    if (scenario.contains("abort")) throw new org.netbeans.lib.cvsclient.command.CommandAbortedException();
    if (scenario.equals("exception")) throw new com.intellij.openapi.vcs.VcsException();
    if (scenario.equals("cancel-flag")) env.aborted = true;
    if (scenario.equals("reported-error")) env.errors.add(new com.intellij.openapi.vcs.VcsException());
    commandCompleted(!scenario.equals("failure"));
  }
}""",
"com.intellij.openapi.cvsIntegration.CvsResult": """public class CvsResult {
 public boolean isCanceled() { return com.intellij.cvsSupport2.cvsoperations.common.LocalPathIndifferentOperation.scenario.equals("executor-cancel"); } public boolean hasErrors() { return com.intellij.cvsSupport2.cvsoperations.common.LocalPathIndifferentOperation.scenario.equals("executor-error"); }
}""",
"com.intellij.cvsSupport2.CvsVcs2": """public class CvsVcs2 {
  public Object getProject() { return null; }
  public static CvsVcs2 executeQuietOperation(String title, com.intellij.cvsSupport2.cvsoperations.cvsContent.GetFileContentOperation op, Object project) {
    try { op.execute(new com.intellij.cvsSupport2.cvsoperations.common.CvsExecutionEnvironment(), false); }
    catch (Exception expected) {}
    return new CvsVcs2();
  }
  public com.intellij.openapi.cvsIntegration.CvsResult getResult() { return new com.intellij.openapi.cvsIntegration.CvsResult(); }
}""",
}
for annotation in ("NotNull", "Nullable", "NonNls"):
    STUBS["org.jetbrains.annotations." + annotation] = '@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.PARAMETER}) public @interface ' + annotation + ' {}'

HARNESS = r'''
import java.nio.file.*;
import java.util.*;
import com.intellij.cvsSupport2.*;
import com.intellij.cvsSupport2.cvsoperations.common.*;
import com.intellij.cvsSupport2.cvsoperations.cvsContent.*;
import com.intellij.cvsSupport2.cvsoperations.dateOrRevision.*;
import com.intellij.cvsSupport2.connections.*;
import com.intellij.openapi.vfs.VirtualFile;

public class EmptyBaseTest {
  static int failures;
  public static void main(String[] args) throws Exception {
    for (String scenario : List.of("abort", "partial-abort", "exception", "failure", "reported-error", "cancel-flag", "no-content", "tag-only", "deleted-abort", "deleted", "success", "empty", "zero-cache", "nonempty-cache", "merge-empty", "retry", "executor-cancel", "executor-error")) {
      try { check(Path.of(args[0]).resolve(scenario), scenario); System.out.println(scenario + ": 通过"); }
      catch (AssertionError e) { failures++; System.err.println(scenario + ": " + e.getMessage()); }
    }
    if (failures != 0) throw new AssertionError("失败场景数：" + failures);
  }
  static void require(boolean b, String s) { if (!b) throw new AssertionError(s); }
  static void check(Path dir, String scenario) throws Exception {
    Files.createDirectories(dir.resolve("CVS/BaseRevisions"));
    Path working = dir.resolve("file"); Files.writeString(working, "local");
    Path cache = dir.resolve("CVS/BaseRevisions/.#file.1.2");
    VirtualFile file = new VirtualFile(working.toFile());
    LocalPathIndifferentOperation.scenario = scenario;
    int before = LocalPathIndifferentOperation.executions;
    if (scenario.equals("merge-empty")) {
      Files.write(dir.resolve(".#file.1.2"), new byte[0]);
      require(CvsUtil.getStoredContentForFile(file, "1.2").length == 0, "merge 空文件语义变更"); return;
    }
    if (scenario.equals("zero-cache") || scenario.equals("nonempty-cache")) {
      Files.write(cache, scenario.equals("zero-cache") ? new byte[0] : "cached".getBytes());
      LocalPathIndifferentOperation.scenario = "success";
    }
    if (scenario.equals("retry")) {
      GetFileContentOperation op = new GetFileContentOperation(new java.io.File("file"), new CvsEnvironment(), new SimpleRevision("1.2"));
      LocalPathIndifferentOperation.scenario = "partial-abort";
      try { op.execute(new CvsExecutionEnvironment(), false); } catch (Exception expected) {}
      LocalPathIndifferentOperation.scenario = "success";
      op.execute(new CvsExecutionEnvironment(), false);
      require(Arrays.equals(op.tryGetFileBytes(), "base\n".getBytes()), "重试混入上次残留内容"); return;
    }
    byte[] result = new ProviderHarness(file).get();
    if (Set.of("success", "zero-cache").contains(scenario)) {
      require(Arrays.equals(result, "base\n".getBytes()), "成功内容不正确");
      require(Arrays.equals(Files.readAllBytes(cache), result), "未写入成功缓存/未覆盖坏缓存");
      require(LocalPathIndifferentOperation.executions == before + 1, "未重新取内容");
    } else if (scenario.equals("nonempty-cache")) {
      require(Arrays.equals(result, "cached".getBytes()), "非空缓存没有复用");
      require(LocalPathIndifferentOperation.executions == before, "不应联网重取");
    } else if (scenario.equals("empty")) {
      require(result != null && result.length == 0, "确实收到的空内容应允许返回");
      require(!CvsUtil.haveCachedContent(file, "1.2"), "空缓存不应当作有效缓存");
    } else {
      require(result == null && !Files.exists(cache), "失败/未读取成功时返回基准=" + (result != null) + "，写入缓存=" + Files.exists(cache));
      GetFileContentOperation op = new GetFileContentOperation(new java.io.File("file"), new CvsEnvironment(), new SimpleRevision("1.2"));
      try { op.execute(new CvsExecutionEnvironment(), false); } catch (Exception expected) {}
      require(op.isDeleted() == scenario.equals("deleted"), "误判删除状态");
    }
  }
}
'''


def main():
    util = (SRC / "CvsUtil.java").read_text()
    cache_methods = ["getStoredContentForFile", "haveCachedContent", "createFromRevisionAndPath", "getCachedContentFile", "getCachedStoredContent", "storeContentForRevision", "deleteAllOtherRevisions"]
    STUBS["com.intellij.cvsSupport2.CvsUtil"] = """import java.io.*; import java.util.regex.Pattern; import org.jetbrains.annotations.*;
import com.intellij.openapi.vfs.VirtualFile; import com.intellij.openapi.util.io.FileUtil;
import com.intellij.cvsSupport2.util.CvsVfsUtil;
public class CvsUtil {
  static final String BASE_REVISIONS_DIR = "BaseRevisions", REVISION_PATTERN = "[0-9]+([.][0-9]+)*";
  static final com.intellij.openapi.diagnostic.Logger LOG = com.intellij.openapi.diagnostic.Logger.getInstance(CvsUtil.class);
  static File getAdminDir(File parent) { return new File(parent, "CVS"); }
  public static String getModuleName(VirtualFile f) { return "module/file"; }
""" + "\n".join(method(util, name) for name in cache_methods) + "\n}"
    provider = (SRC / "cvsstatuses/CvsChangeProvider.java").read_text()
    # 内部类方法统一缩进后原样提取；周边 IDE 本地历史服务替换为缓存读取。
    provider = "\n".join(line[2:] if line.startswith("  ") else line for line in provider.splitlines())
    STUBS["com.intellij.cvsSupport2.ProviderHarness"] = """import org.jetbrains.annotations.*;
import com.intellij.openapi.vfs.VirtualFile; import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.cvsIntegration.CvsResult;
import com.intellij.cvsSupport2.errorHandling.CannotFindCvsRootException;
import com.intellij.cvsSupport2.cvsoperations.cvsContent.GetFileContentOperation;
import com.intellij.cvsSupport2.cvsoperations.dateOrRevision.SimpleRevision;
import org.netbeans.lib.cvsclient.admin.Entry; import com.intellij.CvsBundle;
public class ProviderHarness {
  final FilePath myPath; final CvsVcs2 myVcs = new CvsVcs2();
  final com.intellij.cvsSupport2.application.CvsEntriesManager myEntriesManager = com.intellij.cvsSupport2.application.CvsEntriesManager.getInstance();
  public ProviderHarness(VirtualFile f) { myPath = new FilePath(f); }
  public byte[] get() throws Exception { return getUpToDateBinaryContent(); }
  byte[] getLastUpToDateContentFor(VirtualFile f) { return CvsUtil.getCachedStoredContent(f.getParent(), f.getName(), "1.2"); }
""" + method(provider, "getUpToDateBinaryContent") + "\n}"
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    with tempfile.TemporaryDirectory(prefix="empty-base-") as temp:
        directory = Path(temp)
        sources = []
        for name, body in STUBS.items():
            source = directory / (name.replace(".", "/") + ".java")
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body)
            sources.append(str(source))
        harness = directory / "EmptyBaseTest.java"
        harness.write_text(HARNESS)
        sources += [str(harness), str(SRC / "cvsoperations/cvsContent/GetFileContentOperation.java")]
        classes = directory / "classes"
        subprocess.run([str(java / "javac"), "-encoding", "UTF-8", "--release", "17", "-d", str(classes), *sources], check=True)
        return subprocess.run([str(java / "java"), "-cp", str(classes), "EmptyBaseTest", temp]).returncode


if __name__ == "__main__":
    raise SystemExit(main())
