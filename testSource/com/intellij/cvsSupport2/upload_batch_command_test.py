"""Offline UpdateCommand batching contract using the production command source.

The harness deliberately supplies a tiny JavaCVS model: it records every request
round through a fake IRequestProcessor while compiling the real UpdateCommand.
CVS_TEST_ROOT may point at the exported 649f094 baseline for the required red run.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(os.environ.get("CVS_TEST_ROOT", Path(__file__).resolve().parents[4]))


def write(base, name, content):
    target = base / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


STUBS = {
 "org/jetbrains/annotations/NonNls.java": "package org.jetbrains.annotations; public @interface NonNls {}",
 "org/netbeans/lib/cvsclient/IClientEnvironment.java": r'''package org.netbeans.lib.cvsclient;
import org.netbeans.lib.cvsclient.file.*; public interface IClientEnvironment { FS getCvsFileSystem();
 default Writer getLocalFileWriter(){return new Writer();} public static class Writer { public void renameLocalFile(FileObject f,FS s,String n)throws java.io.IOException{} } public static class FS { public Local getLocalFileSystem(){return new Local();} } public static class Local { public java.io.File getFile(FileObject f){return f.disk;} } }''',
 "org/netbeans/lib/cvsclient/IRequestProcessor.java": r'''package org.netbeans.lib.cvsclient; import org.netbeans.lib.cvsclient.command.*; import org.netbeans.lib.cvsclient.connection.*; import org.netbeans.lib.cvsclient.progress.sending.*; import org.netbeans.lib.cvsclient.request.*;
 public interface IRequestProcessor { boolean processRequests(Requests r,IRequestsProgressHandler h)throws CommandException,AuthenticationException; }''',
 "org/netbeans/lib/cvsclient/admin/Entry.java": "package org.netbeans.lib.cvsclient.admin; public class Entry { public boolean removed,conflict; public java.util.Date last=new java.util.Date(); public boolean isRemoved(){return removed;} public boolean isConflict(){return conflict;} public java.util.Date getLastModified(){return last;} public String getRevision(){return \"1.1\";} }",
 "org/netbeans/lib/cvsclient/connection/AuthenticationException.java": "package org.netbeans.lib.cvsclient.connection; public class AuthenticationException extends Exception {}",
 "org/netbeans/lib/cvsclient/connection/IConnection.java": "package org.netbeans.lib.cvsclient.connection; import java.io.*; import org.netbeans.lib.cvsclient.io.*; public interface IConnection { String getRepository(); InputStream getInputStream(); OutputStream getOutputStream(); void open(IStreamLogger x)throws AuthenticationException; void verify(IStreamLogger x)throws AuthenticationException; void close()throws IOException; }",
 "org/netbeans/lib/cvsclient/io/IStreamLogger.java": "package org.netbeans.lib.cvsclient.io; public interface IStreamLogger {}",
 "org/netbeans/lib/cvsclient/command/CommandException.java": "package org.netbeans.lib.cvsclient.command; public class CommandException extends Exception { public CommandException(){} public CommandException(Throwable x){super(x);} }",
 "org/netbeans/lib/cvsclient/command/IOCommandException.java": "package org.netbeans.lib.cvsclient.command; public class IOCommandException extends CommandException { public IOCommandException(Throwable x){super(x);} }",
 "org/netbeans/lib/cvsclient/command/KeywordSubstitution.java": "package org.netbeans.lib.cvsclient.command; public class KeywordSubstitution { public String toString(){return \"kv\";} }",
 "org/netbeans/lib/cvsclient/command/IUpdatingCommand.java": "package org.netbeans.lib.cvsclient.command; public interface IUpdatingCommand { void setResetStickyOnes(boolean b); void setUseHeadIfNotFound(boolean b); void setUpdateByDate(String s); void setUpdateByRevisionOrTag(String s); void setUpdateByRevisionOrDate(String a,String b); }",
 "org/netbeans/lib/cvsclient/command/GlobalOptions.java": "package org.netbeans.lib.cvsclient.command; public class GlobalOptions { boolean no; boolean ro; public boolean isDoNoChanges(){return no;} public void setDoNoChanges(boolean b){no=b;} public boolean isCheckedOutFilesReadOnly(){return ro;} public void setCheckedOutFilesReadOnly(boolean b){ro=b;} }",
 "org/netbeans/lib/cvsclient/file/AbstractFileObject.java": "package org.netbeans.lib.cvsclient.file; public class AbstractFileObject { final String p; protected AbstractFileObject(String p){this.p=p;} public String toUnixPath(){return p;} }",
 "org/netbeans/lib/cvsclient/file/FileObject.java": "package org.netbeans.lib.cvsclient.file; public class FileObject extends AbstractFileObject { public final java.io.File disk; public final boolean modified; public FileObject(String p,java.io.File f,boolean m){super(p);disk=f;modified=m;} public String getName(){return p;} }",
 "org/netbeans/lib/cvsclient/file/DirectoryObject.java": "package org.netbeans.lib.cvsclient.file; public class DirectoryObject extends AbstractFileObject { private DirectoryObject(String p){super(p);} private static final DirectoryObject ROOT=new DirectoryObject(\"/\"); public static DirectoryObject getRoot(){return ROOT;} }",
 "org/netbeans/lib/cvsclient/command/ICvsFilesVisitor.java": "package org.netbeans.lib.cvsclient.command; import org.netbeans.lib.cvsclient.file.*; import org.netbeans.lib.cvsclient.admin.*; public interface ICvsFilesVisitor { void handleDirectory(DirectoryObject d); void handleFile(FileObject f,Entry e,boolean x); }",
 "org/netbeans/lib/cvsclient/command/ICvsFiles.java": "package org.netbeans.lib.cvsclient.command; public interface ICvsFiles { void visit(ICvsFilesVisitor v); }",
 "org/netbeans/lib/cvsclient/command/DirectoryPruner.java": "package org.netbeans.lib.cvsclient.command; import org.netbeans.lib.cvsclient.*; import org.netbeans.lib.cvsclient.event.*; public class DirectoryPruner { public DirectoryPruner(IClientEnvironment x){} public void registerListeners(ICvsListenerRegistry x){} public void unregisterListeners(ICvsListenerRegistry x){} public void pruneEmptyDirectories()throws java.io.IOException{} }",
 "org/netbeans/lib/cvsclient/command/AbstractCommand.java": r'''package org.netbeans.lib.cvsclient.command;
import java.util.*; import org.netbeans.lib.cvsclient.*; import org.netbeans.lib.cvsclient.file.*; import org.netbeans.lib.cvsclient.admin.*; import org.netbeans.lib.cvsclient.request.*;
public abstract class AbstractCommand { public static List<FileObject> all=List.of(); protected final List<AbstractFileObject> files=new ArrayList<>(); private final GlobalOptions options=new GlobalOptions(); private boolean recursive=true;
 public void addFileObject(AbstractFileObject f){files.add(f);} public List<AbstractFileObject> getFileObjects(){return files;} public GlobalOptions getGlobalOptions(){return options;} public void setRecursive(boolean b){recursive=b;} protected boolean isRecursive(){return recursive;} public void resetCvsCommand(){} public abstract boolean execute(org.netbeans.lib.cvsclient.IRequestProcessor p,org.netbeans.lib.cvsclient.event.IEventSender s,org.netbeans.lib.cvsclient.event.ICvsListenerRegistry r,org.netbeans.lib.cvsclient.IClientEnvironment e,org.netbeans.lib.cvsclient.progress.IProgressViewer v)throws CommandException,org.netbeans.lib.cvsclient.connection.AuthenticationException; public String getCvsCommandLine(){return "";}
 protected ICvsFiles scanFileSystem(IClientEnvironment e)throws java.io.IOException{return v->{v.handleDirectory(DirectoryObject.getRoot()); List<? extends AbstractFileObject> source=files.isEmpty()?all:files; for(AbstractFileObject x:source){if(x instanceof FileObject){FileObject f=(FileObject)x; v.handleFile(f,new Entry(),true);}}};}
 protected void addFileRequests(ICvsFiles fs,Requests r,IClientEnvironment e){}
 protected boolean isModified(FileObject f,java.util.Date d,IClientEnvironment e){return f.modified;} protected void addModifiedRequest(FileObject f,Entry z,Requests r,IClientEnvironment e){} protected void addArgumentRequests(Requests r){for(AbstractFileObject x:files)r.files.add(x.toUnixPath());} protected void appendFileArguments(StringBuffer b){for(AbstractFileObject x:files)b.append(x.toUnixPath()).append(' ');} protected String getTrimmedString(String s){return s==null?null:s.trim();} }''',
 "org/netbeans/lib/cvsclient/event/IMessageListener.java": "package org.netbeans.lib.cvsclient.event; public interface IMessageListener { void messageSent(String s,byte[] b,boolean error,boolean tagged); void binaryMessageSent(byte[] b); }",
 "org/netbeans/lib/cvsclient/event/ICvsListener.java": "package org.netbeans.lib.cvsclient.event; public interface ICvsListener { default void registerListeners(ICvsListenerRegistry r){} default void unregisterListeners(ICvsListenerRegistry r){} }",
 "org/netbeans/lib/cvsclient/event/ICvsListenerRegistry.java": "package org.netbeans.lib.cvsclient.event; public interface ICvsListenerRegistry { void addMessageListener(IMessageListener l); void removeMessageListener(IMessageListener l); }",
 "org/netbeans/lib/cvsclient/event/IEventSender.java": "package org.netbeans.lib.cvsclient.event; public interface IEventSender {}",
 "org/netbeans/lib/cvsclient/event/DualListener.java": "package org.netbeans.lib.cvsclient.event; public class DualListener implements ICvsListener { public DualListener(ICvsListener a,ICvsListener b){} }",
 "org/netbeans/lib/cvsclient/progress/IProgressViewer.java": "package org.netbeans.lib.cvsclient.progress; public interface IProgressViewer {}",
 "org/netbeans/lib/cvsclient/progress/RangeProgressViewer.java": "package org.netbeans.lib.cvsclient.progress; public class RangeProgressViewer implements IProgressViewer { public RangeProgressViewer(IProgressViewer x,double a,double b){} }",
 "org/netbeans/lib/cvsclient/progress/sending/IRequestsProgressHandler.java": "package org.netbeans.lib.cvsclient.progress.sending; public interface IRequestsProgressHandler {}",
 "org/netbeans/lib/cvsclient/progress/sending/FileStateRequestsProgressHandler.java": "package org.netbeans.lib.cvsclient.progress.sending; import org.netbeans.lib.cvsclient.progress.*; import org.netbeans.lib.cvsclient.command.*; public class FileStateRequestsProgressHandler implements IRequestsProgressHandler { public FileStateRequestsProgressHandler(IProgressViewer p,ICvsFiles f){} }",
 "org/netbeans/lib/cvsclient/progress/receiving/FileInfoAndDirectoryResponseProgressHandler.java": "package org.netbeans.lib.cvsclient.progress.receiving; import org.netbeans.lib.cvsclient.event.*; import org.netbeans.lib.cvsclient.progress.*; import org.netbeans.lib.cvsclient.command.*; public class FileInfoAndDirectoryResponseProgressHandler implements ICvsListener { public FileInfoAndDirectoryResponseProgressHandler(IProgressViewer p,ICvsFiles f){} }",
 "org/netbeans/lib/cvsclient/command/update/UpdateMessageParser.java": "package org.netbeans.lib.cvsclient.command.update; import org.netbeans.lib.cvsclient.event.*; import org.netbeans.lib.cvsclient.IClientEnvironment; public class UpdateMessageParser implements ICvsListener { public UpdateMessageParser(IEventSender e,Object f){} }",
 "org/netbeans/lib/cvsclient/request/CommandRequest.java": "package org.netbeans.lib.cvsclient.request; public class CommandRequest { public static final CommandRequest UPDATE=new CommandRequest(); }",
 "org/netbeans/lib/cvsclient/request/Requests.java": "package org.netbeans.lib.cvsclient.request; import java.util.*; import org.netbeans.lib.cvsclient.*; import org.netbeans.lib.cvsclient.file.*; public class Requests { public final List<String> args=new ArrayList<>(),files=new ArrayList<>(); public Requests(CommandRequest c,IClientEnvironment e){} public void addArgumentRequest(boolean b,String s){if(b)args.add(s);} public void addArgumentRequest(Object v,String s){if(v!=null){args.add(s);args.add(v.toString());}} public void addArgumentRequests(Object v,String s){addArgumentRequest(v,s);} public void addLocalPathDirectoryRequest(){} }",
}


HARNESS = r'''package org.netbeans.lib.cvsclient.command.update;
import java.io.*; import java.util.*; import org.netbeans.lib.cvsclient.*; import org.netbeans.lib.cvsclient.command.*; import org.netbeans.lib.cvsclient.event.*; import org.netbeans.lib.cvsclient.file.*; import org.netbeans.lib.cvsclient.progress.*; import org.netbeans.lib.cvsclient.progress.sending.*; import org.netbeans.lib.cvsclient.request.*;
public class UpdateBatchCommandTest {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);} static final class Env implements IClientEnvironment { public FS getCvsFileSystem(){return new FS();} }
 static final class Registry implements ICvsListenerRegistry { final List<IMessageListener> listeners=new ArrayList<>(); public void addMessageListener(IMessageListener x){listeners.add(x);} public void removeMessageListener(IMessageListener x){listeners.remove(x);} void error(String s){for(IMessageListener x:List.copyOf(listeners))x.messageSent(s,new byte[0],true,false);} void tagged(String s){for(IMessageListener x:List.copyOf(listeners))x.messageSent(s,new byte[0],false,true);} void normal(String s){for(IMessageListener x:List.copyOf(listeners))x.messageSent(s,new byte[0],false,false);} }
 static final class Processor implements IRequestProcessor { final Registry r; final List<List<String>> rounds=new ArrayList<>(); final List<List<String>> args=new ArrayList<>(); int calls; int mode; boolean cancel; Processor(Registry r){this.r=r;} public boolean processRequests(Requests q,IRequestsProgressHandler h){calls++; rounds.add(List.copyOf(q.files)); args.add(List.copyOf(q.args)); if(mode==1 && calls<=2){r.normal("C "+q.files.get(0));r.error("");return false;} if(mode==4 && calls<=2){r.tagged("text C ");r.tagged("fname "+q.files.get(0));r.tagged("newline");r.error(" ");return false;} if(mode==2){r.error("cvs update: transport failed");return false;} if(mode==3 && calls==1)cancel=true; return true;} }
 static FileObject file(File d,String n,int size)throws Exception { File f=new File(d,n); try(FileOutputStream out=new FileOutputStream(f)){out.write(new byte[size]);} return new FileObject(n,f,true); }
 static UpdateCommand command(FileObject... fs){AbstractCommand.all=List.of(fs);UpdateCommand c=new UpdateCommand();c.setUploadBatchLimit(20);return c;}
 static void assertOneRound(UpdateCommand c) throws Exception { Registry r=new Registry();Processor p=new Processor(r); check(c.execute(p,new IEventSender(){},r,new Env(),new IProgressViewer(){}),"one round succeeds");check(p.calls==1,"guard must disable batching: "+p.calls); }
 public static void main(String[] z)throws Exception { File d=java.nio.file.Files.createTempDirectory("batch-command").toFile(); FileObject a=file(d,"a",10),b=file(d,"b",10),c=file(d,"c",10);
  { Registry r=new Registry(); Processor p=new Processor(r); UpdateCommand u=command(a,b,c); List<String> progress=new ArrayList<>(); u.setUploadBatchProgress((i,n,bytes)->progress.add(i+"/"+n+":"+bytes)); check(u.execute(p,new IEventSender(){},r,new Env(),new IProgressViewer(){}),"batched success"); check(p.rounds.equals(List.of(List.of("a","b"),List.of("c"),List.of())),"batches then full scan: "+p.rounds); check(progress.equals(List.of("1/2:20","2/2:10")),"progress: "+progress); }
  { Registry r=new Registry(); Processor p=new Processor(r); UpdateCommand u=command(a,b); u.setUploadBatchLimit(21); u.execute(p,new IEventSender(){},r,new Env(),new IProgressViewer(){}); check(p.calls==1,"below threshold single command"); }
  { UpdateCommand u=command(a,b,c);u.setCleanCopy(true);assertOneRound(u); u=command(a,b,c);u.setResetStickyOnes(true);assertOneRound(u); u=command(a,b,c);u.setUpdateByRevisionOrTag("REL");assertOneRound(u); u=command(a,b,c);u.setUpdateByDate("2026-10-11");assertOneRound(u); u=command(a,b,c);u.setKeywordSubst(new KeywordSubstitution());assertOneRound(u); u=command(a,b,c);u.setMergeRevision1("1.2");assertOneRound(u); u=command(a,b,c);u.getGlobalOptions().setDoNoChanges(true);assertOneRound(u); }
  { Registry r=new Registry();Processor p=new Processor(r);UpdateCommand u=command(a,b,c);u.setBuildDirectories(true);u.execute(p,new IEventSender(){},r,new Env(),new IProgressViewer(){});check(!p.args.get(0).contains("-d")&&!p.args.get(1).contains("-d")&&p.args.get(2).contains("-d"),"-d only final overall round: "+p.args); }
  { Registry r=new Registry();Processor p=new Processor(r);p.mode=1;UpdateCommand u=command(a,b,c);check(u.execute(p,new IEventSender(){},r,new Env(),new IProgressViewer(){}),"conflict-only empty error continues");check(p.calls==3,"conflict-only must reach final: "+p.calls); }
  { Registry r=new Registry();Processor p=new Processor(r);p.mode=4;UpdateCommand u=command(a,b,c);check(u.execute(p,new IEventSender(){},r,new Env(),new IProgressViewer(){}),"tagged conflict-only error continues");check(p.calls==3,"tagged conflict-only must reach final"); }
  { Registry r=new Registry();Processor p=new Processor(r);p.mode=2;UpdateCommand u=command(a,b,c);check(!u.execute(p,new IEventSender(){},r,new Env(),new IProgressViewer(){}),"other error stops");check(p.calls==1,"other error must not continue"); }
  { Registry r=new Registry();Processor p=new Processor(r);p.mode=3;UpdateCommand u=command(a,b,c);u.setCheckCanceled(()->{if(p.cancel)throw new RuntimeException("cancelled");});try{u.execute(p,new IEventSender(){},r,new Env(),new IProgressViewer(){});throw new AssertionError("cancel expected");}catch(RuntimeException expected){check(p.calls==1,"cancel preserves first batch and skips later rounds");} }
  System.out.println("UpdateCommand batching contract: PASS");
 }
}'''

CONNECTION_HARNESS = r'''package org.netbeans.lib.cvsclient.connection;
import java.io.*; import java.util.concurrent.atomic.*; import org.netbeans.lib.cvsclient.io.*;
public class CommandConnectionTest { static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static final class One implements IConnection { final int id; boolean closed; One(int id){this.id=id;} public String getRepository(){return "repo";} public InputStream getInputStream(){return InputStream.nullInputStream();} public OutputStream getOutputStream(){return OutputStream.nullOutputStream();} public void open(IStreamLogger x){} public void verify(IStreamLogger x){} public void close(){closed=true;} }
 public static void main(String[] x)throws Exception { AtomicInteger created=new AtomicInteger(); One[] seen=new One[2]; CommandConnection c=new CommandConnection("repo",()->{One n=new One(created.getAndIncrement());seen[n.id]=n;return n;}); c.open(null);c.close();c.open(null);c.close();check(created.get()==2,"each request round needs a fresh transport");check(seen[0].closed&&seen[1].closed,"each transport closes");System.out.println("CommandConnection fresh transport: PASS");} }'''


with tempfile.TemporaryDirectory(prefix="cvs-upload-batch-command-") as temporary:
    base = Path(temporary)
    for name, content in STUBS.items():
        write(base, name, content)
    write(base, "org/netbeans/lib/cvsclient/command/update/UpdateBatchCommandTest.java", HARNESS)
    write(base, "org/netbeans/lib/cvsclient/connection/CommandConnectionTest.java", CONNECTION_HARNESS)
    bundled_jbr = Path.home() / "Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin"
    javac = str(Path(os.environ["JAVA_HOME"]) / "bin" / "javac") if os.environ.get("JAVA_HOME") else (str(bundled_jbr / "javac") if (bundled_jbr / "javac").exists() else shutil.which("javac"))
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java") if os.environ.get("JAVA_HOME") else (str(bundled_jbr / "java") if (bundled_jbr / "java").exists() else shutil.which("java"))
    if not javac or not java:
        raise RuntimeError("javac/java is required")
    sources = [*base.rglob("*.java"), ROOT / "javacvs-src/org/netbeans/lib/cvsclient/event/TaggedMessageParser.java", ROOT / "javacvs-src/org/netbeans/lib/cvsclient/command/update/UploadBatchPlanner.java", ROOT / "javacvs-src/org/netbeans/lib/cvsclient/command/update/UpdateCommand.java", ROOT / "javacvs-src/org/netbeans/lib/cvsclient/connection/CommandConnection.java"]
    subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-d", temporary, *map(str, sources)], check=True)
    subprocess.run([java, "-Djava.io.tmpdir=" + temporary, "-cp", temporary,
                    "org.netbeans.lib.cvsclient.command.update.UpdateBatchCommandTest"], check=True, timeout=60)
    subprocess.run([java, "-Djava.io.tmpdir=" + temporary, "-cp", temporary,
                    "org.netbeans.lib.cvsclient.connection.CommandConnectionTest"], check=True, timeout=60)
