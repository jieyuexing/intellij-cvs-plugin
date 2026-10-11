"""Run UpdateCommand against an isolated GNU CVS server process.

This is intentionally a standalone harness: it uses the compiled javacvs
classes and a ProcessBuilder-backed `cvs server` transport, so it exercises
the same RequestProcessor/ResponseParser/UpdateCommand path without IDEA.
The real translator, FileMessage and UpdatedFilesProcessor run with stub IDE
services/containers; new-entry bookkeeping mirrors AdminWriterOnCache. Logs
are javacvs protocol/control logs (raw file bodies bypass IStreamLogger).
Each run retains a fresh lab under TMPDIR and reports its evidence path.
"""
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import hashlib
import json
import ast
import time

ROOT = Path(__file__).resolve().parents[4]
CVS = os.environ.get("CVS_BINARY") or shutil.which("cvs")
if not CVS: raise RuntimeError("GNU cvs is required")
JBR = Path(os.environ.get("JAVA_HOME") or os.environ["CVS_IDEA_JBR"])
IDE_LIB = Path(os.environ.get("CVS_IDEA_LIB", JBR.parents[2] / "lib"))
CP = str(ROOT / "build/classes/java/main") + ":" + str(ROOT / "lib/trilead-ssh2-build213.jar") + ":" + str(IDE_LIB / "*")

# Share only the fixture source dictionary, without executing the wiring test.
module = ast.parse((Path(__file__).with_name("hash_cache_wiring_test.py")).read_text())
STUBS = ast.literal_eval(next(n.value for n in module.body if isinstance(n, ast.Assign) and
                              any(isinstance(t, ast.Name) and t.id == "STUBS" for t in n.targets)))
STUBS["com/intellij/cvsSupport2/application/CvsEntriesManager.java"] = r'''package com.intellij.cvsSupport2.application;
import java.io.*; import java.nio.file.*; import java.util.*; import com.intellij.openapi.vfs.VirtualFile; import org.netbeans.lib.cvsclient.admin.Entry;
public class CvsEntriesManager { static final CvsEntriesManager INSTANCE=new CvsEntriesManager(); public static CvsEntriesManager getInstance(){return INSTANCE;}
 public Entry getEntryFor(VirtualFile f){return getEntryFor(f.getParent(),f.getName());} public boolean isActive(){return true;} public Entry getEntryFor(VirtualFile p,String name){for(Entry e:getEntriesIn(p))if(e.getFileName().equals(name))return e;return null;}
 public Collection<Entry> getEntriesIn(VirtualFile p){List<Entry> out=new ArrayList<>();try{for(String line:Files.readAllLines(p.file.toPath().resolve("CVS/Entries")))if(!line.equals("D")&&!line.isEmpty())out.add(Entry.createEntryForLine(line));}catch(IOException e){}return out;}
 public String getRepositoryFor(VirtualFile p){try{return Files.readString(p.file.toPath().resolve("CVS/Repository")).trim();}catch(IOException e){throw new RuntimeException(e);}}
 public String getStickyTagFor(VirtualFile p){return null;} }
'''
STUBS["com/intellij/cvsSupport2/util/CvsVfsUtil.java"] = r'''package com.intellij.cvsSupport2.util;
import java.io.*; import com.intellij.openapi.vfs.VirtualFile; public class CvsVfsUtil { public static VirtualFile findFileByIoFile(File f){return new VirtualFile(f);} public static VirtualFile findFileByPath(String p){return new VirtualFile(new File(p));} public static VirtualFile getParentFor(File f){return new VirtualFile(f.getParentFile());} public static long getTimeStamp(VirtualFile f){return f.file.lastModified();} }
'''
STUBS["com/intellij/cvsSupport2/CvsUtil.java"] = "package com.intellij.cvsSupport2; public class CvsUtil { public static boolean isLocallyRemoved(java.io.File f){return false;} }"
STUBS["com/intellij/cvsSupport2/history/CvsRevisionNumber.java"] = "package com.intellij.cvsSupport2.history; public class CvsRevisionNumber { public CvsRevisionNumber(String s){} }"

STUBS["com/intellij/openapi/diagnostic/Logger.java"] = "package com.intellij.openapi.diagnostic; public class Logger { public static Logger getInstance(Class<?> c){return new Logger();} public void debug(String s){} public void error(Object x){throw new AssertionError(x);} public boolean assertTrue(boolean b,String m){if(!b)throw new AssertionError(m);return b;} }"
STUBS["com/intellij/cvsSupport2/CvsVcs2.java"] = "package com.intellij.cvsSupport2; public class CvsVcs2 { public static Object getKey(){return null;} }"
STUBS["com/intellij/openapi/fileTypes/FileTypeManager.java"] = "package com.intellij.openapi.fileTypes; public class FileTypeManager { public static FileTypeManager getInstance(){return new FileTypeManager();} public boolean isFileIgnored(com.intellij.openapi.vfs.VirtualFile f){return false;} }"
GROUP_IDS = ["MODIFIED","MERGED","MERGED_WITH_CONFLICT","UNKNOWN","LOCALLY_ADDED","LOCALLY_REMOVED","REMOVED_FROM_REPOSITORY","CREATED","RESTORED","UPDATED"]
STUBS["com/intellij/openapi/vcs/update/FileGroup.java"] = "package com.intellij.openapi.vcs.update; import java.util.*; public class FileGroup { " + " ".join(f'public static final String {g}_ID="{g}";' for g in GROUP_IDS) + " public final Set<String> files=new TreeSet<>(); public void add(String p,Object key,Object revision){files.add(new java.io.File(p).getName());} }"
STUBS["com/intellij/openapi/vcs/update/UpdatedFiles.java"] = "package com.intellij.openapi.vcs.update; import java.util.*; public class UpdatedFiles { public final Map<String,FileGroup> groups=new TreeMap<>(); public FileGroup getGroupById(String s){return groups.computeIfAbsent(s,k->new FileGroup());} public String toString(){Map<String,Set<String>> m=new TreeMap<>();groups.forEach((k,v)->m.put(k,v.files));return m.toString();} }"

JAVA = r'''import java.io.*; import java.nio.charset.*; import java.nio.file.*; import java.util.*;
import org.netbeans.lib.cvsclient.*; import org.netbeans.lib.cvsclient.admin.*; import org.netbeans.lib.cvsclient.command.update.*; import org.netbeans.lib.cvsclient.connection.*; import org.netbeans.lib.cvsclient.event.*; import org.netbeans.lib.cvsclient.file.*; import org.netbeans.lib.cvsclient.io.*; import org.netbeans.lib.cvsclient.progress.*; import org.netbeans.lib.cvsclient.util.*;
import com.intellij.cvsSupport2.cvsoperations.cvsMessages.*;
import com.intellij.cvsSupport2.cvsoperations.common.UpdatedFilesManager;
import com.intellij.cvsSupport2.cvsoperations.javacvsSpecificImpls.*;
public class UploadIntegration {
 static Path evidence;
 static class Results extends CvsMessagesAdapter {
  final SortedMap<String,Integer> groups=new TreeMap<>(); final List<String> errors=new ArrayList<>();
  final com.intellij.openapi.vcs.update.UpdatedFiles updated=new com.intellij.openapi.vcs.update.UpdatedFiles();
  final com.intellij.cvsSupport2.updateinfo.UpdatedFilesProcessor processor=new com.intellij.cvsSupport2.updateinfo.UpdatedFilesProcessor(updated);
  @Override public void addFileMessage(FileMessage m){if(groups.put(Path.of(m.getFileAbsolutePath()).getFileName().toString(),m.getType())!=null)throw new AssertionError("duplicate file");processor.addFileMessage(m);}
  @Override public void addError(String m,String p,ICvsFileSystem fs,String root,boolean warning){errors.add(warning+"|"+m);}
 }
 static class Conn implements IConnection { final String repo; Process p; Conn(String r){repo=r;} public String getRepository(){return repo;} public InputStream getInputStream(){return p.getInputStream();} public OutputStream getOutputStream(){return p.getOutputStream();} public void verify(IStreamLogger x){} public void open(IStreamLogger x)throws AuthenticationException{try{p=new ProcessBuilder(System.getProperty("cvs.binary"),"-f","server").start();}catch(IOException e){throw new AuthenticationException(e.getMessage(),e);}} public void close()throws IOException{if(p!=null){p.getOutputStream().close();p.destroy();}} }
 static class Log implements IStreamLogger { final ByteArrayOutputStream in=new ByteArrayOutputStream(),out=new ByteArrayOutputStream(); public InputStream createLoggingInputStream(InputStream x){return new InputStream(){public int read()throws IOException{int b=x.read();if(b>=0)in.write(b);return b;} public int read(byte[] b,int off,int len)throws IOException{if(len==0)return 0;int q=read();if(q<0)return -1;b[off]=(byte)q;return 1;}};} public OutputStream createLoggingOutputStream(OutputStream x){return new OutputStream(){public void write(int b)throws IOException{x.write(b);out.write(b);} public void write(byte[] b,int o,int n)throws IOException{x.write(b,o,n);out.write(b,o,n);} public void flush()throws IOException{x.flush();}};} public OutputStream getInputLogStream(){return in;} public OutputStream getOutputLogStream(){return out;} }
 static class Events implements IMessageListener,IFileInfoListener { final List<String> messages=new ArrayList<>(); final List<String> infos=new ArrayList<>(); public void messageSent(String s,byte[] b,boolean e,boolean t){messages.add(s+"|"+e);} public void binaryMessageSent(byte[] b){} public void fileInfoGenerated(Object x){infos.add(String.valueOf(x));} }
 static IClientEnvironment env(File dir,String repo,boolean hashes,UpdatedFilesManager files){ ISendTextFilePreprocessor send=new ISendTextFilePreprocessor(){public File getPreprocessedTextFile(File f,IWriterFactory w){return f;} public void cleanup(File f){}}; IReceiveTextFilePreprocessor recv=(in,n,target,rf,cs)->{try(OutputStream o=new FileOutputStream(target)){byte[] b=new byte[8192];while(n>0){int r=in.read(b,0,Math.min(n,b.length));if(r<0)throw new EOFException();o.write(b,0,r);n-=r;}}}; IAdminWriter diskWriter=new AdminWriter("\n","UTF-8");
 // Model the IDE's cached pre-write entry lookup for new-file classification.
 IAdminWriter writer=(IAdminWriter)java.lang.reflect.Proxy.newProxyInstance(UploadIntegration.class.getClassLoader(),new Class[]{IAdminWriter.class},(proxy,method,args)->{
   Entry entry=method.getName().equals("setEntry")?(Entry)args[1]:null; boolean created=false;
   if(entry!=null)created=new AdminReader("UTF-8").getEntry(FileObject.createInstance((DirectoryObject)args[0],entry.getFileName()),(ICvsFileSystem)args[2])==null;
   try {Object result=method.invoke(diskWriter,args);if(created)files.addNewlyCreatedEntry(entry);return result;}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
 }); return new ClientEnvironment(new CommandConnection(repo,()->new Conn(repo)),dir,dir,new CvsRoot("",repo,":local:"+repo),new LocalFileReader(send),new LocalFileWriter(recv),(hashes ? new AdminReaderOnCache() : new AdminReader("UTF-8")),writer,new DefaultIgnoreFileFilter(),(f,ro)->f.setWritable(!ro),"UTF-8"); }
 static Results run(File dir,String repo,long limit,boolean hashes,String name)throws Exception {
  UpdatedFilesManager files=new UpdatedFilesManager(); IClientEnvironment e=env(dir,repo,hashes,files); EventManager em=new EventManager("UTF-8"); Events events=new Events();
  em.addMessageListener(events);em.addFileInfoListener(events);
  files.setCvsFileSystem(e.getCvsFileSystem());em.addMessageListener(files);
  // Same collector/translator lifetime as CvsCommandOperation: one for every round.
  Results results=new Results(); CvsMessagesTranslator translator=new CvsMessagesTranslator(results,e.getCvsFileSystem(),files,repo);translator.registerTo(em);
  UpdateCommand c=new UpdateCommand();c.setUploadBatchLimit(limit);c.getGlobalOptions().setUseGzip(false);c.getGlobalOptions().setDoNoChanges(name.equals("hash-hit-n"));
  Log log=new Log(); long started=System.nanoTime();
  RequestProcessor p=new RequestProcessor(e,c.getGlobalOptions(),em,log,new ICvsCommandStopper(){public boolean isAborted(){return false;}public boolean isAlive(){return true;}public void resetAlive(){}},-1);
  boolean ok;
  try {ok=c.execute(p,em,em,e,v->{});} finally {translator.operationCompleted();}
  Files.writeString(evidence.resolve(name+"-events.txt"),events.messages.toString()+"\n"+events.infos);
  Files.write(evidence.resolve(name+"-input.bin"),log.in.toByteArray()); Files.write(evidence.resolve(name+"-output.bin"),log.out.toByteArray());
  Files.writeString(evidence.resolve(name+"-groups.txt"),results.updated.toString());Files.writeString(evidence.resolve(name+"-errors.txt"),results.errors.toString());
  System.out.println(name+" result="+ok+" groups="+results.updated+" errors="+results.errors+" ms="+(System.nanoTime()-started)/1000000);
  return results;
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] x)throws Exception {
  String repo=x[2];evidence=Path.of(x[3]);
  Results single=run(new File(x[0]),repo,0,false,"single"),batch=run(new File(x[1]),repo,1,false,"batch");
  check(single.updated.toString().equals(batch.updated.toString()),"UpdatedFiles groups differ");check(single.groups.equals(batch.groups),"file types differ");check(single.errors.equals(batch.errors),"errors differ");
  check(single.groups.get("conflict.txt")==FileMessage.MERGED_WITH_CONFLICTS,"conflict group");
  check(single.groups.get("merge.txt")==FileMessage.MERGED,"merge group");
  check(single.groups.get("local-only.txt")==FileMessage.MODIFIED,"local edit group");
  check(single.groups.get("server-added.txt")==FileMessage.CREATED,"created group");
  Results repeatSingle=run(new File(x[0]),repo,0,false,"repeat-single");
  Results repeatBatch=run(new File(x[1]),repo,1,false,"repeat-batch");
  check(repeatSingle.updated.toString().equals(repeatBatch.updated.toString())&&repeatSingle.errors.equals(repeatBatch.errors),"pre-existing conflict observable results differ");
  check(Files.readString(evidence.resolve("repeat-batch-output.bin")).lines().filter(line->line.equals("update")).count()==4,"pre-existing tagged conflict must not stop later batches/final");
  File work=new File(x[4]); IClientEnvironment env=env(work,repo,true,new UpdatedFilesManager());
  FileObject f=FileObject.createInstance("/mtime.txt"); Entry entry=env.getAdminReader().getEntry(f,env.getCvsFileSystem());
  File disk=new File(work,"mtime.txt");disk.setLastModified(entry.getLastModified().getTime());
  check(!env.getAdminReader().isModified(f,entry.getLastModified(),env.getCvsFileSystem()),"seed production reader");
  disk.setLastModified(entry.getLastModified().getTime()+120000);
  run(work,repo,0,true,"hash-hit");
  String output=Files.readString(evidence.resolve("hash-hit-output.bin"));
  check(output.contains("Unchanged mtime.txt\n")&&!output.contains("Modified mtime.txt\n"),"hash hit must not upload content");
  disk.setLastModified(entry.getLastModified().getTime()+180000);
  run(work,repo,1,true,"hash-hit-n");
  String dry=Files.readString(evidence.resolve("hash-hit-n-output.bin"));
  check(dry.contains("Global_option -n\n")&&dry.contains("Unchanged mtime.txt\n")&&!dry.contains("Modified mtime.txt\n"),"hash cache works independently in -n");
  // A real error and warning still take the original presentation route.
  Results negative=new Results();UpdatedFilesManager manager=new UpdatedFilesManager();manager.setCvsFileSystem(env.getCvsFileSystem());
  CvsMessagesTranslator translator=new CvsMessagesTranslator(negative,env.getCvsFileSystem(),manager,repo);
  translator.messageSent("cvs [server aborted]: denied",new byte[0],true,false);
  translator.messageSent(" ",new byte[0],true,false);
  check(negative.errors.size()==1&&negative.errors.get(0).startsWith("false|"),"nonempty error preserved, blank error ignored");
  translator.messageSent("cvs server: warning: fixture warning",new byte[0],true,false);
  check(negative.errors.size()==2&&negative.errors.get(1).startsWith("true|"),"warning preserved");
  Files.writeString(disk.toPath(),"true edit\n");disk.setLastModified(entry.getLastModified().getTime()+240000);
  run(work,repo,0,true,"hash-edit");
  check(Files.readString(evidence.resolve("hash-edit-output.bin")).contains("Modified mtime.txt\n"),"true edit must upload");
  System.out.println("javacvs five samples, plugin groups/errors, hash wire: PASS");
 }

}'''

def cvs(*args, cwd=None):
    subprocess.run([CVS, "-f", *args], cwd=cwd, check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


if __name__ == "__main__":
    temp = tempfile.mkdtemp(prefix="cvs-upload-integration-")
    source = Path(temp) / "UploadIntegration.java"
    source.write_text(JAVA, encoding="utf-8")
    stub_sources=[]
    for name,content in STUBS.items():
        target=Path(temp)/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content);stub_sources.append(str(target))
    production=[ROOT/"cvs-plugin/src/com/intellij/cvsSupport2/updateinfo/UpdatedFilesProcessor.java"]
    if os.environ.get("CVS_TEST_ROOT"):
        production.append(Path(os.environ["CVS_TEST_ROOT"])/"javacvs-src/org/netbeans/lib/cvsclient/command/update/UpdateCommand.java")
    subprocess.run([str(JBR / "bin/javac"), "--release", "17", "-cp", CP, "-d", temp, *stub_sources, *map(str,production), str(source)], check=True)
    root = Path(temp) / "fresh-cvs"; repo = root / "repo"; seed = root / "seed"; single = root / "single"; batch = root / "batch"; remote = root / "remote"
    root.mkdir(); seed.mkdir()
    for name in ("mtime.txt", "local-only.txt", "conflict.txt"): (seed / name).write_text("base\n", encoding="utf-8")
    (seed / "merge.txt").write_text("".join(f"line {i}\n" for i in range(30)), encoding="utf-8")
    cvs("-d", f":local:{repo}", "init")
    cvs("-d", f":local:{repo}", "import", "-m", "initial", "module", "VENDOR", "START", cwd=seed)
    cvs("-d", f":local:{repo}", "checkout", "-d", str(single), "module")
    cvs("-d", f":local:{repo}", "checkout", "-d", str(batch), "module")
    cvs("-d", f":local:{repo}", "checkout", "-d", str(remote), "module")
    (remote / "merge.txt").write_text((seed / "merge.txt").read_text().replace("line 0\n","server\n"), encoding="utf-8"); (remote / "conflict.txt").write_text("server\n", encoding="utf-8"); (remote / "server-added.txt").write_text("new\n", encoding="utf-8")
    cvs("add", "server-added.txt", cwd=remote); cvs("commit", "-m", "server", cwd=remote)
    for work in (single, batch):
        os.utime(work / "mtime.txt", (time.time()+120,time.time()+120)); (work / "local-only.txt").write_text("local\n", encoding="utf-8"); (work / "merge.txt").write_text((seed / "merge.txt").read_text().replace("line 29\n","local\n"), encoding="utf-8"); (work / "conflict.txt").write_text("local\n", encoding="utf-8")
        for name in ("local-only.txt","merge.txt","conflict.txt"):
            os.utime(work/name,(time.time()+120,time.time()+120))
    hash_work=root/"hash-work";cvs("-d", f":local:{repo}", "checkout", "-d", str(hash_work), "module")
    subprocess.run([str(JBR / "bin/java"), "-Dcvs.binary="+CVS, "-Djava.io.tmpdir="+temp, "-cp", temp + ":" + CP, "UploadIntegration", str(single), str(batch), str(repo),temp,str(hash_work)], check=True, timeout=60)
    def snapshot(work):
        return {str(p.relative_to(work)):hashlib.sha256(p.read_bytes()).hexdigest()
                for p in work.rglob("*") if p.is_file() and "CVS" not in p.relative_to(work).parts}
    def entries(work):
        result={}
        for p in work.rglob("CVS/Entries"):
            lines=[]
            for line in p.read_text().splitlines():
                fields=line.split("/")
                if len(fields)>3:fields[3]="<timestamp>"
                lines.append("/".join(fields))
            result[str(p.relative_to(work))]=sorted(lines)
        return result
    assert snapshot(single)==snapshot(batch), "final work/backup files differ"
    assert entries(single)==entries(batch), "normalized Entries differ"
    result={"work_and_backups_equal":True,"entries_equal":True,"groups_equal":True,"errors_equal":True,
            "files":snapshot(single),"entries":entries(single),"evidence":temp}
    (Path(temp)/"result.json").write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print("Final work/backup/Entries comparison: PASS; evidence="+temp)
