"""Compile the javacvs response path and replay CVS's conflict-only terminator.

The ordinary-message fixture is a message ``M C conflict.txt`` followed
by ``error`` with an empty payload.  The latter is a protocol failure signal, but
it must not create a second user-visible error when the conflict was already
reported by the update parser. The MT + single-space error fixture was captured
from the local GNU CVS 1.12.13 integration run; it also covers tagged responses.
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


with tempfile.TemporaryDirectory(prefix="cvs-update-error-response-") as temporary:
    base = Path(temporary)
    # ResponseParser only needs these types as signatures for this fixture path.
    write(base, "org/jetbrains/annotations/NonNls.java",
          "package org.jetbrains.annotations; public @interface NonNls {}")
    write(base, "org/netbeans/lib/cvsclient/IClientEnvironment.java",
          "package org.netbeans.lib.cvsclient; public interface IClientEnvironment {}")
    write(base, "org/netbeans/lib/cvsclient/IConnectionStreams.java", r'''package org.netbeans.lib.cvsclient;
import java.io.*; public interface IConnectionStreams { InputStream getLoggedInputStream(); InputStream getInputStream(); }
''')
    write(base, "org/netbeans/lib/cvsclient/file/FileObject.java",
          "package org.netbeans.lib.cvsclient.file; public class FileObject {}")
    write(base, "org/netbeans/lib/cvsclient/file/DirectoryObject.java",
          "package org.netbeans.lib.cvsclient.file; public class DirectoryObject {}")
    write(base, "org/netbeans/lib/cvsclient/admin/Entry.java",
          "package org.netbeans.lib.cvsclient.admin; public class Entry {}")
    write(base, "org/netbeans/lib/cvsclient/response/ResponseUtils.java", r'''package org.netbeans.lib.cvsclient.response;
import java.util.Date; final class ResponseUtils { static Date parseDateString(String ignored) { return new Date(); } }
''')
    write(base, "org/netbeans/lib/cvsclient/util/BugLog.java", r'''package org.netbeans.lib.cvsclient.util;
public class BugLog { private static final BugLog INSTANCE=new BugLog(); public static BugLog getInstance(){return INSTANCE;}
 public void assertNotNull(Object value){if(value==null)throw new AssertionError("null");} public void showException(Exception ignored){} }
''')
    write(base, "org/netbeans/lib/cvsclient/response/RecordedConflictResponseTest.java", r'''package org.netbeans.lib.cvsclient.response;
import java.io.*; import java.nio.charset.StandardCharsets; import java.util.*;
import org.netbeans.lib.cvsclient.*; import org.netbeans.lib.cvsclient.event.*;
import org.netbeans.lib.cvsclient.file.*; import org.netbeans.lib.cvsclient.admin.*;
public class RecordedConflictResponseTest {
 static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
 static final class Streams implements IConnectionStreams { final InputStream in; Streams(String text){in=new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));}
  public InputStream getLoggedInputStream(){return in;} public InputStream getInputStream(){return in;} }
 static final class Events implements IEventSender { final List<String> messages=new ArrayList<>(); Boolean terminated;
  public void notifyMessageListeners(byte[] b,boolean error,boolean tagged){messages.add(new String(b,StandardCharsets.UTF_8)+"|"+error);}
  public void notifyTerminationListeners(boolean error){terminated=error;} public void notifyFileInfoListeners(Object x){} public void notifyFileInfoListeners(byte[] x){}
  public void notifyModuleExpansionListeners(String x){} public void notifyEntryListeners(FileObject x,Entry y){} public void notifyDirectoryListeners(DirectoryObject x,boolean y){} }
 static final class Services implements IResponseServices { final Events events=new Events(); public IEventSender getEventSender(){return events;}
  public void setNextFileDate(Date x){} public Date getNextFileDate(){return null;} public void setValidRequests(String x){} public String getNextFileMode(){return null;} public void setNextFileMode(String x){} }
 static final class Handler extends AbstractResponseHandler {
  public void processErrorMessageResponse(byte[] x,IResponseServices s){} public void processMessageResponse(byte[] x,IResponseServices s){s.getEventSender().notifyMessageListeners(x,false,false);} public void processMessageTaggedResponse(byte[] x,IResponseServices s){s.getEventSender().notifyMessageListeners(x,false,true);}
  public void processCheckedInResponse(String a,String b,String c,IResponseServices d,IClientEnvironment e){} public void processNewEntryResponse(String a,String b,IResponseServices c,String d,IClientEnvironment e){} public void processSetStaticDirectoryResponse(String a,String b,IResponseServices c,IClientEnvironment d){} public void processClearStaticDirectoryResponse(String a,String b,IResponseServices c,IClientEnvironment d){} public void processSetStickyResponse(String a,String b,String c,IClientEnvironment d){} public void processClearStickyResponse(String a,String b,IClientEnvironment c){} public void processNotifiedResponse(String a,String b,IClientEnvironment c){} public void processRemovedResponse(String a,String b,IResponseServices c,IClientEnvironment d){} public void processRemoveEntryResponse(String a,String b,IResponseServices c,IClientEnvironment d){} public void processCopyFileResponse(String a,String b,String c,IClientEnvironment d){} public void processModTimeResponse(Date a,IResponseServices b){} public void processModeResponse(String a,IResponseServices b){} public void processTemplateResponse(String a,String b,int c,IClientEnvironment d,IConnectionStreams e){} public void processModuleExpansionResponse(String a,IResponseServices b){} public void processUpdatedResponse(String a,String b,String c,String d,int e,IClientEnvironment f,IResponseServices g,IConnectionStreams h){} public void processMergedResponse(String a,String b,String c,String d,int e,IClientEnvironment f,IResponseServices g,IConnectionStreams h){} public void processValidRequestsResponse(String a,IResponseServices b){} public void processBinaryMessageResponse(int a,byte[] b,IResponseServices c){}
 }
 public static void main(String[] args)throws Exception { Services services=new Services(); ResponseParser parser=new ResponseParser(new Handler(),"UTF-8"); Streams streams=new Streams("C conflict.txt\n\n");
  check(parser.processResponse("M",streams,services,null)==null,"M continues"); check(Boolean.FALSE.equals(parser.processResponse("error",streams,services,null)),"error ends false");
  check(services.events.messages.equals(Arrays.asList("C conflict.txt|false","|true")),"recorded messages="+services.events.messages); check(Boolean.TRUE.equals(services.events.terminated),"error termination"); Services recorded=new Services(); Streams tags=new Streams("text C \nfname conflict.txt\nnewline\n \n");
  parser.processResponse("MT",tags,recorded,null);parser.processResponse("MT",tags,recorded,null);parser.processResponse("MT",tags,recorded,null);
  check(Boolean.FALSE.equals(parser.processResponse("error",tags,recorded,null)),"recorded error ends false");
  check(recorded.events.messages.equals(Arrays.asList("text C |false","fname conflict.txt|false","newline|false"," |true")),"GNU CVS recorded MT/error sequence");
  System.out.println("conflict-only error response: OK"); }
}
''')
    sources = [
        *base.rglob("*.java"),
        ROOT / "javacvs-src/org/netbeans/lib/cvsclient/response/IResponseHandler.java",
        ROOT / "javacvs-src/org/netbeans/lib/cvsclient/response/IResponseServices.java",
        ROOT / "javacvs-src/org/netbeans/lib/cvsclient/response/AbstractResponseHandler.java",
        ROOT / "javacvs-src/org/netbeans/lib/cvsclient/response/ResponseParser.java",
        ROOT / "javacvs-src/org/netbeans/lib/cvsclient/io/StreamUtilities.java",
        ROOT / "javacvs-src/org/netbeans/lib/cvsclient/event/IEventSender.java",
    ]
    javac = str(Path(os.environ["JAVA_HOME"]) / "bin" / "javac") if os.environ.get("JAVA_HOME") else shutil.which("javac")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java") if os.environ.get("JAVA_HOME") else shutil.which("java")
    if not javac or not java:
        raise RuntimeError("javac/java is required for this offline response test")
    subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-d", temporary,
                    *map(str, sources)], check=True)
    subprocess.run([java, "-Djava.io.tmpdir=" + temporary, "-cp", temporary,
                    "org.netbeans.lib.cvsclient.response.RecordedConflictResponseTest"], check=True, timeout=60)
