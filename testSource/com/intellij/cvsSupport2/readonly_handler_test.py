"""离线运行真实 FileReadOnlyHandler：无 Application、无写动作时只改物理文件权限或 DOS 只读属性（issue #1）；可用 CVS_TEST_ROOT 跑 2ac8903 起的 VFS 版红样本。"""
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(os.environ.get("CVS_TEST_ROOT", Path(__file__).resolve().parents[4]))
JAVA = Path(os.environ["JAVA_HOME"]) / "bin"
IDE_LIB = Path(os.environ.get("CVS_IDEA_LIB", Path.home() / "Applications/IntelliJ IDEA.app/Contents/lib"))

with tempfile.TemporaryDirectory(prefix="cvs-readonly-") as tmp_name:
    tmp = Path(tmp_name)
    harness = tmp / "ReadOnlyHandlerTest.java"
    harness.write_text(r'''
import com.intellij.cvsSupport2.javacvsImpl.FileReadOnlyHandler;
import java.nio.file.*; import java.nio.file.attribute.*;
public class ReadOnlyHandlerTest {
 static void check(boolean b, String m) { if (!b) throw new AssertionError(m); }
 static String mode(Path p) throws Exception { return PosixFilePermissions.toString(Files.getPosixFilePermissions(p)); }
 public static void main(String[] args) throws Exception {
  Path file = Files.createTempFile(Path.of(args[0]), "tracked", ".txt");
  FileReadOnlyHandler handler = new FileReadOnlyHandler();
  if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) {
   Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
   handler.setFileReadOnly(file.toFile(), false);
   check(mode(file).equals("rw-r--r--"), "删除或覆盖前改可写只应加 owner 写位，实际 " + mode(file));
   Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));
   handler.setFileReadOnly(file.toFile(), true);
   check(mode(file).equals("r--r--r--"), "置只读应清掉全部写位，实际 " + mode(file));
   handler.setFileReadOnly(file.toFile(), true);
   check(mode(file).equals("r--r--r--"), "已只读时不得再改权限");
  } else {
   DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
   dos.setReadOnly(true);
   handler.setFileReadOnly(file.toFile(), false);
   check(!dos.readAttributes().isReadOnly(), "删除或覆盖前应清掉 DOS 只读属性");
   handler.setFileReadOnly(file.toFile(), true);
   check(dos.readAttributes().isReadOnly(), "置只读应设上 DOS 只读属性");
   handler.setFileReadOnly(file.toFile(), false);
  }
  check(file.toFile().delete(), "改可写后应能删除");
  System.out.println("FileReadOnlyHandler: OK");
 }
}
''')
    sources = [
        harness,
        ROOT / "javacvs-src/org/netbeans/lib/cvsclient/file/IFileReadOnlyHandler.java",
        ROOT / "cvs-plugin/src/com/intellij/cvsSupport2/javacvsImpl/FileReadOnlyHandler.java",
    ]
    classpath = str(IDE_LIB / "*")
    subprocess.run([str(JAVA / "javac"), "--release", "17", "-encoding", "UTF-8", "-cp", classpath, "-d", str(tmp), *map(str, sources)], check=True)
    subprocess.run([str(JAVA / "java"), "-cp", str(tmp) + os.pathsep + classpath, "ReadOnlyHandlerTest", str(tmp)], check=True, timeout=60)
