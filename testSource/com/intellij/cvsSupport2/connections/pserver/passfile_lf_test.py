"""离线回归：编译实际写入类，仅替换 IDE 服务；不启动 IDE、不访问用户 passfile。

运行：JAVA_HOME=<JDK> python3 testSource/com/intellij/cvsSupport2/connections/pserver/passfile_lf_test.py
临时目录由 TMPDIR/TMP/TEMP 控制。测试替身不证明 IDE 服务自身的运行时行为。
"""
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[6]
STUBS = {
    "com.intellij.CvsBundle": 'public class CvsBundle { public static String message(String s, Object... a) { return s; } }',
    "com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration": '''
public class CvsApplicationLevelConfiguration {
  public static java.io.File passFile;
  public static CvsApplicationLevelConfiguration getInstance() { return new CvsApplicationLevelConfiguration(); }
  public java.io.File getPassFile() { return passFile; }
  public static String getCharset() { return "UTF-8"; }
}''',
    "com.intellij.application.options.CodeStyle": '''
public class CodeStyle {
  public static CodeStyle getDefaultSettings() { return new CodeStyle(); }
  public String getLineSeparator() { return "\\r\\n"; }
}''',
    "com.intellij.cvsSupport2.util.CvsVfsUtil": '''
public class CvsVfsUtil {
  public static com.intellij.openapi.vfs.VirtualFile findFileByIoFile(java.io.File f) { return null; }
}''',
    "com.intellij.openapi.vfs.VirtualFile": 'public class VirtualFile {}',
    "com.intellij.openapi.fileEditor.FileDocumentManager": '''
public class FileDocumentManager {
  public static FileDocumentManager getInstance() { throw new AssertionError("不应使用 VFS"); }
  public String getLineSeparator(com.intellij.openapi.vfs.VirtualFile f, Object p) { throw new AssertionError(); }
}''',
    "com.intellij.openapi.util.io.FileUtil": '''
public class FileUtil {
  public static boolean createIfDoesntExist(java.io.File f) {
    try { return f.exists() || f.createNewFile(); }
    catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
  }
}''',
    "com.intellij.cvsSupport2.javacvsImpl.FileReadOnlyHandler": '''
public class FileReadOnlyHandler {
  public void setFileReadOnly(java.io.File f, boolean b) { throw new AssertionError("不应更改可写文件权限"); }
}''',
    "com.intellij.openapi.diagnostic.Logger": '''
public class Logger {
  public static Logger getInstance(Class<?> c) { return new Logger(); }
  public void info(Exception e) { throw new AssertionError(e); }
  public void error(Exception e) { throw new AssertionError(e); }
}''',
    "com.intellij.openapi.project.Project": 'public interface Project {}',
    "com.intellij.openapi.ui.Messages": '''
public class Messages {
  public static String showPasswordDialog(String a, String b) { throw new AssertionError("不应显示登录界面"); }
}''',
    "org.jetbrains.annotations.Nullable": 'public @interface Nullable {}',
    "com.intellij.cvsSupport2.connections.login.CvsLoginWorker": 'public interface CvsLoginWorker {}',
    "com.intellij.cvsSupport2.connections.login.CvsLoginWorkerImpl": '''
public abstract class CvsLoginWorkerImpl<T> implements CvsLoginWorker {
  protected T mySettings;
  protected com.intellij.openapi.project.Project myProject;
  protected CvsLoginWorkerImpl(com.intellij.openapi.project.Project p, T s) { myProject = p; mySettings = s; }
  protected abstract void silentLoginImpl(boolean force) throws org.netbeans.lib.cvsclient.connection.AuthenticationException;
  protected abstract void clearOldCredentials();
  public abstract boolean promptForPassword();
  protected void showConnectionErrorMessage(com.intellij.openapi.project.Project p, String s) { throw new AssertionError(s); }
}''',
    "com.intellij.cvsSupport2.connections.pserver.PServerLoginProvider": '''
public abstract class PServerLoginProvider {
  public abstract String getScrambledPasswordForCvsRoot(String root);
  public abstract com.intellij.cvsSupport2.connections.login.CvsLoginWorker getLoginWorker(
    com.intellij.openapi.project.Project p, PServerCvsSettings s);
}''',
    "com.intellij.cvsSupport2.connections.pserver.PServerCvsSettings": '''
public class PServerCvsSettings {
  public String getCvsRootAsString() { throw new AssertionError(); }
  public org.netbeans.lib.cvsclient.connection.IConnection createConnection(
    com.intellij.cvsSupport2.javacvsImpl.io.ReadWriteStatistics s) { throw new AssertionError("禁止连接服务器"); }
  public void setOffline(boolean b) { throw new AssertionError(); }
  public void storePassword(String s) { throw new AssertionError(); }
  public void releasePassword() { throw new AssertionError(); }
}''',
    "org.netbeans.lib.cvsclient.connection.AuthenticationException": 'public class AuthenticationException extends Exception {}',
    "org.netbeans.lib.cvsclient.connection.UnknownUserException": 'public class UnknownUserException extends AuthenticationException {}',
    "com.intellij.cvsSupport2.connections.ssh.SolveableAuthenticationException": '''
public class SolveableAuthenticationException extends org.netbeans.lib.cvsclient.connection.AuthenticationException {
  public SolveableAuthenticationException(String s) {}
  public SolveableAuthenticationException(String s, Exception e) {}
}''',
    "org.netbeans.lib.cvsclient.connection.IConnection": '''
public interface IConnection {
  void open(com.intellij.cvsSupport2.javacvsImpl.io.StreamLogger l) throws AuthenticationException;
  void close() throws java.io.IOException;
}''',
    "org.netbeans.lib.cvsclient.connection.PServerPasswordScrambler": '''
public class PServerPasswordScrambler {
  public static PServerPasswordScrambler getInstance() { throw new AssertionError(); }
  public String scramble(String s) { throw new AssertionError(); }
}''',
    "com.intellij.cvsSupport2.javacvsImpl.io.ReadWriteStatistics": 'public class ReadWriteStatistics {}',
    "com.intellij.cvsSupport2.javacvsImpl.io.StreamLogger": 'public class StreamLogger {}',
}
HARNESS = r'''
import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration;
import com.intellij.cvsSupport2.connections.pserver.PServerLoginProviderImpl;
import com.intellij.cvsSupport2.util.CvsFileUtil;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

public class PassfileLfTest {
  private static final String ROOT = ":pserver:fixture@example.invalid:/repo";
  private static final String OTHER = ":pserver:fixture@example.invalid:/other Afixture";
  private static int failures;

  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    for (String scenario : List.of("store", "remove", "remove-last", "create", "generic")) {
      try {
        check(directory, scenario);
        System.out.println(scenario + ": 通过");
      }
      catch (AssertionError e) {
        failures++;
        System.err.println(scenario + ": " + e.getMessage());
      }
    }
    if (failures != 0) throw new AssertionError("失败场景数：" + failures);
  }

  private static void check(Path directory, String scenario) throws Exception {
    Path pass = directory.resolve(scenario);
    CvsApplicationLevelConfiguration.passFile = pass.toFile();
    boolean existing = !scenario.equals("create");
    boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
    if (existing) {
      String fixture = scenario.equals("remove-last") ? ROOT + " Aold\r\n"
        : ROOT + " Aold\r\n" + OTHER + "\r\n" + ROOT + " Aduplicate\r\n";
      Files.writeString(pass, fixture, StandardCharsets.UTF_8);
      if (posix) Files.setPosixFilePermissions(pass, PosixFilePermissions.fromString("rw-------"));
    }
    String expected;
    if (scenario.equals("generic")) {
      CvsFileUtil.storeLines(List.of("first", "second"), pass.toFile());
      expected = "first\r\nsecond\r\n";
    }
    else if (scenario.startsWith("remove")) {
      Method remove = PServerLoginProviderImpl.class.getDeclaredMethod(
        "removeAllPasswordsForThisCvsRootFromPasswordFile", String.class);
      remove.setAccessible(true);
      remove.invoke(null, ROOT);
      expected = scenario.equals("remove-last") ? "" : OTHER + "\n";
    }
    else {
      Method store = PServerLoginProviderImpl.class.getDeclaredMethod("storePassword", String.class, String.class);
      store.setAccessible(true);
      store.invoke(null, ROOT, "Anew");
      expected = (existing ? ROOT + " Aold\n" + OTHER + "\n" + ROOT + " Aduplicate\n" : "") + ROOT + " Anew\n";
    }
    if (existing && posix && !Files.getPosixFilePermissions(pass).equals(PosixFilePermissions.fromString("rw-------"))) {
      throw new AssertionError("0600 权限发生变化");
    }
    byte[] actual = Files.readAllBytes(pass);
    if (!scenario.equals("generic")) {
      for (byte value : actual) if (value == '\r') throw new AssertionError("输出包含 CR，原生 CVS 会读入多余字节");
    }
    if (!Arrays.equals(expected.getBytes(StandardCharsets.UTF_8), actual)) throw new AssertionError("输出字节不符合预期");
  }
}
'''


def main():
    java_bin = Path(os.environ["JAVA_HOME"]) / "bin"
    with tempfile.TemporaryDirectory(prefix="passfile-lf-") as temporary:
        directory = Path(temporary)
        sources = []
        for name, body in STUBS.items():
            source = directory / (name.replace(".", "/") + ".java")
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body, encoding="utf-8")
            sources.append(str(source))
        harness = directory / "PassfileLfTest.java"
        harness.write_text(HARNESS, encoding="utf-8")
        sources += [str(harness)]
        for relative in ("connections/pserver/PServerLoginProviderImpl.java", "util/CvsFileUtil.java"):
            sources.append(str(ROOT / "cvs-plugin/src/com/intellij/cvsSupport2" / relative))
        classes = directory / "classes"
        subprocess.run([str(java_bin / "javac"), "-encoding", "UTF-8", "--release", "17", "-d", str(classes), *sources], check=True)
        result = subprocess.run([str(java_bin / "java"), "-Djava.io.tmpdir=" + temporary,
                                 "-cp", str(classes), "PassfileLfTest", temporary])
        return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
