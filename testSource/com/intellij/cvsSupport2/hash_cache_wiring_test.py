"""Execute the real AdminReaderOnCache against disk and a stub IDE entry service.

Requires buildPlugin/compileJava first. Only platform services are stubbed; the
production reader, cache, Entry and CVS filesystem are used. CVS_TEST_ROOT
selects the reader source, so the baseline reaches a behavioral red assertion.
"""
import os
from pathlib import Path
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[4]
ROOT = Path(os.environ.get('CVS_TEST_ROOT', PROJECT))
JAVA_HOME = Path(os.environ['JAVA_HOME'])
IDE = JAVA_HOME.parents[2]
CP = os.pathsep.join([str(PROJECT/'build/classes/java/main'), str(IDE/'lib/*')])
STUBS = {
 'com/intellij/CvsBundle.java': 'package com.intellij; public class CvsBundle { public static String message(String s,Object... a){return s;} }',
 'com/intellij/openapi/diagnostic/Logger.java': 'package com.intellij.openapi.diagnostic; public class Logger { public static Logger getInstance(Class<?> c){return new Logger();} public void debug(String s){} public void error(Object x){throw new AssertionError(x);} }',
 'com/intellij/openapi/progress/ProgressManager.java': 'package com.intellij.openapi.progress; public class ProgressManager { public static void checkCanceled(){} public static ProgressManager getInstance(){return new ProgressManager();} public ProgressIndicator getProgressIndicator(){return null;} }',
 'com/intellij/openapi/progress/ProgressIndicator.java': 'package com.intellij.openapi.progress; public interface ProgressIndicator { void setText2(String s); }',
 'com/intellij/openapi/application/PathManager.java': 'package com.intellij.openapi.application; public class PathManager { public static String getSystemPath(){return System.getProperty("java.io.tmpdir");} }',
 'com/intellij/openapi/vfs/VirtualFile.java': 'package com.intellij.openapi.vfs; public class VirtualFile { public final java.io.File file; public VirtualFile(java.io.File f){file=f;} public VirtualFile getParent(){return new VirtualFile(file.getParentFile());} public String getName(){return file.getName();} }',
 'com/intellij/cvsSupport2/util/CvsVfsUtil.java': 'package com.intellij.cvsSupport2.util; import java.io.*; import com.intellij.openapi.vfs.*; public class CvsVfsUtil { public static VirtualFile findFileByIoFile(File f){return new VirtualFile(f);} public static long getTimeStamp(VirtualFile f){return f.file.lastModified();} }',
 'com/intellij/cvsSupport2/config/CvsApplicationLevelConfiguration.java': 'package com.intellij.cvsSupport2.config; public class CvsApplicationLevelConfiguration { public static String getCharset(){return "UTF-8";} }',
 'com/intellij/cvsSupport2/application/CvsEntriesManager.java': '''package com.intellij.cvsSupport2.application;
import java.util.*; import com.intellij.openapi.vfs.VirtualFile; import org.netbeans.lib.cvsclient.admin.Entry;
public class CvsEntriesManager { static final CvsEntriesManager INSTANCE=new CvsEntriesManager(); public static CvsEntriesManager getInstance(){return INSTANCE;}
 public Entry entry; public Entry getEntryFor(VirtualFile p,String n){return entry;} public Collection<Entry> getEntriesIn(VirtualFile p){return List.of(entry);}
 public String getRepositoryFor(VirtualFile p){return "/repo";} public String getStickyTagFor(VirtualFile p){return null;} }
''',
}
HARNESS = r'''
import java.io.*; import java.nio.file.*; import java.nio.file.attribute.*; import java.util.*;
import org.netbeans.lib.cvsclient.file.CvsFileSystem; import org.netbeans.lib.cvsclient.admin.Entry;
import org.netbeans.lib.cvsclient.file.FileObject;
import com.intellij.cvsSupport2.application.CvsEntriesManager;
import com.intellij.cvsSupport2.cvsoperations.javacvsSpecificImpls.*;
public class HashWiringTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception {
  Path root=Path.of(System.getProperty("java.io.tmpdir")); Path file=root.resolve("tracked.txt");
  Files.writeString(file,"original"); Entry entry=Entry.createEntryForLine("/tracked.txt/1.2/Mon Jan 01 00:00:00 2024//");
  CvsEntriesManager.getInstance().entry=entry;
  CvsFileSystem fs=new CvsFileSystem(root.toFile(),root.toFile(),"/repo"); FileObject object=FileObject.createInstance("/tracked.txt");
  AdminReaderOnCache reader=new AdminReaderOnCache(); Date date=entry.getLastModified();
  Files.setLastModifiedTime(file,FileTime.fromMillis(date.getTime()));
  check(!reader.isModified(object,date,fs),"initial timestamp seed");
  Files.setLastModifiedTime(file,FileTime.fromMillis(date.getTime()+123000));
  check(!reader.isModified(object,date,fs),"mtime-only drift must send Unchanged");
  check(file.toFile().lastModified()==date.getTime(),"restore working mtime");
  check(entry.getLastModified().equals(date),"Entries service unchanged");
  Files.writeString(file,"modified"); Files.setLastModifiedTime(file,FileTime.fromMillis(date.getTime()+456000));
  check(reader.isModified(object,date,fs),"same-size true edit must send Modified");
  CvsContentHashes.getInstance().invalidate(file.toFile());
  Files.writeString(file,"original"); Files.setLastModifiedTime(file,FileTime.fromMillis(date.getTime()+456000));
  check(reader.isModified(object,date,fs),"unseeded drift must send Modified");
  System.out.println("AdminReader hash wiring: PASS");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='cvs-hash-wiring-') as tmp:
    base=Path(tmp)
    for name,content in STUBS.items():
        p=base/name; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(content)
    (base/'HashWiringTest.java').write_text(HARNESS)
    src=ROOT/'cvs-plugin/src/com/intellij/cvsSupport2/cvsoperations/javacvsSpecificImpls/AdminReaderOnCache.java'
    # Compile the selected reader while retaining the real cache and timestamp predicate.
    subprocess.run([str(JAVA_HOME/'bin/javac'),'--release','17','-cp',CP,'-d',tmp,
                    *map(str,base.rglob('*.java')),str(src)],check=True)
    subprocess.run([str(JAVA_HOME/'bin/java'),'-Djava.io.tmpdir='+tmp,'-cp',tmp+os.pathsep+CP,'HashWiringTest'],check=True,timeout=30)
