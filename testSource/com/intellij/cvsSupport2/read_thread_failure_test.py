"""离线编译生产 ReadThread：读线程出错后读取方须拿到异常，不得无限等待；可用 CVS_TEST_ROOT 在旧源码快照上跑红样本。"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(os.environ.get('CVS_TEST_ROOT', Path(__file__).resolve().parents[4]))
with tempfile.TemporaryDirectory(prefix='cvs-read-thread-') as tmp:
    base = Path(tmp)
    stubs = {
      'com/intellij/openapi/diagnostic/Logger.java': 'package com.intellij.openapi.diagnostic; public class Logger { public static Logger getInstance(Class<?> c){return new Logger();} public boolean isDebugEnabled(){return false;} public void info(Object o){} public boolean assertTrue(boolean b){return b;} }',
      'com/intellij/openapi/progress/ProcessCanceledException.java': 'package com.intellij.openapi.progress; public class ProcessCanceledException extends RuntimeException {}',
      'com/intellij/util/concurrency/Semaphore.java': 'package com.intellij.util.concurrency; public class Semaphore { private int n; public synchronized void down(){n++;} public synchronized void up(){n--; notifyAll();} public synchronized void waitFor(){while(n>0){try{wait();}catch(InterruptedException e){throw new RuntimeException(e);}}} }',
    }
    for name, content in stubs.items():
        p = base / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(content)
    harness = base / 'ReadThreadTest.java'
    harness.write_text(r'''
import java.io.*; import java.net.SocketTimeoutException; import java.util.concurrent.*;
import com.intellij.cvsSupport2.javacvsImpl.io.ReadThread;
import org.netbeans.lib.cvsclient.ICvsCommandStopper;
public class ReadThreadTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static final ICvsCommandStopper RUNNING=new ICvsCommandStopper(){public boolean isAborted(){return false;} public boolean isAlive(){return true;} public void resetAlive(){}};
 static ReadThread start(InputStream in){ReadThread t=new ReadThread(in,RUNNING); t.prepareForWait(); Thread th=new Thread(t); th.setDaemon(true); th.start(); t.waitForStart(); return t;}
 static <T> T within(Callable<T> c,String m)throws Exception{
  ExecutorService e=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
  try{return e.submit(c).get(10,TimeUnit.SECONDS);}catch(TimeoutException x){throw new AssertionError(m+"：10 秒内未返回");}finally{e.shutdownNow();}
 }
 public static void main(String[] args)throws Exception{
  ReadThread ok=start(new ByteArrayInputStream("abc".getBytes()));
  byte[] buf=new byte[8];
  check(within(()->ok.read(buf,0,8),"正常读取")==3 && new String(buf,0,3).equals("abc"),"正常数据");
  check(within(()->ok.read(buf,0,8),"流结束")==-1,"流结束返回 -1");

  ReadThread timedOut=start(new InputStream(){public int read()throws IOException{throw new SocketTimeoutException("Read timed out");}
   public int read(byte[] b)throws IOException{return read();}});
  Throwable failure=within(()->{try{timedOut.read(buf,0,8);return null;}catch(Throwable t){return t;}},"读线程超时后读取");
  check(failure instanceof SocketTimeoutException,"读取方应拿到原始超时异常: "+failure);
  check(!ReadThread.READ_THREADS.contains(timedOut),"读线程退出后应注销");
  System.out.println("ReadThread 失败传递：OK");
 }
}''')
    sources = list(base.rglob('*.java')) + [
      ROOT / 'cvs-core/src/com/intellij/cvsSupport2/javacvsImpl/io/ReadThread.java',
      ROOT / 'javacvs-src/org/netbeans/lib/cvsclient/ICvsCommandStopper.java',
    ]
    java = Path(os.environ['JAVA_HOME']) / 'bin'
    subprocess.run([str(java/'javac'), '--release', '17', '-encoding', 'UTF-8', '-d', tmp, *map(str, sources)], check=True)
    subprocess.run([str(java/'java'), '-Djava.io.tmpdir='+tmp, '-cp', tmp, 'ReadThreadTest'], check=True, timeout=60)
