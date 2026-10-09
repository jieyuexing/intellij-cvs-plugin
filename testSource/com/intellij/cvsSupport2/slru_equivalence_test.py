"""用两代 IDE 原 SLRUCache 对照晋升/降级/淘汰/回调异常；不访问 CVS。"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[4]
SRC=ROOT/'cvs-plugin/src/com/intellij/cvsSupport2'
caller=(SRC/'cvsoperations/cvsCheckOut/CheckoutAdminWriter.java').read_text()
assert 'import com.intellij.util.containers.SLRUCache;' not in caller, '仍使用内部缓存'
assert 'extends CvsSegmentedLruCache<String, EntriesHandler>' in caller
HARNESS=r'''
import java.util.*;
import com.intellij.util.containers.SLRUCache;
import com.intellij.cvsSupport2.util.CvsSegmentedLruCache;
public class SlruEquivalence {
 static class State {
   int creates; String fail; boolean failCreate; List<String> drops=new ArrayList<>();
   Integer create(String key) {if(failCreate)throw new IllegalStateException("create");return ++creates;}
   void drop(String key,Integer value) {drops.add(key+":"+value);if(key.equals(fail))throw new IllegalStateException("drop");}
 }
 static class Old extends SLRUCache<String,Integer> {
   State s; Old(int p,int q,State s){super(p,q);this.s=s;}
   public Integer createValue(String k){return s.create(k);}
   protected void onDropFromCache(String k,Integer v){s.drop(k,v);}
 }
 static class New extends CvsSegmentedLruCache<String,Integer> {
   State s; New(int p,int q,State s){super(p,q);this.s=s;}
   public Integer createValue(String k){return s.create(k);}
   protected void onDropFromCache(String k,Integer v){s.drop(k,v);}
 }
 static Map<String,Integer> entries(Set<Map.Entry<String,Integer>> set) {
   Map<String,Integer> map=new TreeMap<>();for(var e:set)map.put(e.getKey(),e.getValue());return map;
 }
 static void compare(Old a,New b,State x,State y,String key) {
   Object oldResult,newResult;
   try{oldResult=a.get(key);}catch(IllegalStateException e){oldResult=e.getMessage();}
   try{newResult=b.get(key);}catch(IllegalStateException e){newResult=e.getMessage();}
   if(!Objects.equals(oldResult,newResult)||x.creates!=y.creates||!x.drops.equals(y.drops)||!entries(a.entrySet()).equals(entries(b.entrySet())))
     throw new AssertionError(key+" old="+oldResult+" new="+newResult+" drops="+x.drops+" / "+y.drops);
 }
 public static void main(String[] args) {
   int count=0;
   for(int[] size:List.of(new int[]{2,1},new int[]{0,1},new int[]{1,0},new int[]{1600,224})) {
     State x=new State(),y=new State();Old a=new Old(size[0],size[1],x);New b=new New(size[0],size[1],y);
     for(String key:List.of("a","a","b","b","c","c","a","d","b","e","f","a")){compare(a,b,x,y,key);count++;}
     Random r=new Random(232262);
     for(int i=0;i<10000;i++){compare(a,b,x,y,"k"+r.nextInt(size[0]+size[1]+10));count++;}
     x.failCreate=y.failCreate=true;compare(a,b,x,y,"create-failure");
   }
   State x=new State(),y=new State();Old a=new Old(2,1,x);New b=new New(2,1,y);
   compare(a,b,x,y,"a");x.fail=y.fail="a";compare(a,b,x,y,"b");
   x.fail=y.fail=null;
   for(String k:List.of("a","b","c","c","d","d","e","e"))compare(a,b,x,y,k);
   System.out.println("SLRU 状态/值/淘汰写回顺序等价："+count+" 次访问，含加载失败与淘汰失败");
 }
}'''
if len(sys.argv)<2:raise SystemExit('需提供 IDE 发行包根')
java=Path(os.environ['JAVA_HOME'])/'bin'
for ide in sys.argv[1:]:
 with tempfile.TemporaryDirectory(prefix='cvs-slru-') as temp:
  base=Path(temp);harness=base/'SlruEquivalence.java';harness.write_text(HARNESS)
  cp=str(Path(ide)/'lib/*')
  # 生产工具单独验证 Java 17；oracle harness 由 JBR25 编译以读 262 平台 class。
  subprocess.run([str(java/'javac'),'--release','17','-d',temp,str(SRC/'util/CvsSegmentedLruCache.java')],check=True)
  subprocess.run([str(java/'javac'),'-encoding','UTF-8','-cp',temp+os.pathsep+cp,'-d',temp,str(harness)],check=True)
  for factor in ['1','2']:
   print('对照目标：',ide,'factor='+factor,flush=True)
   subprocess.run([str(java/'java'),'-Didea.slru.factor='+factor,'-cp',temp+os.pathsep+cp,'SlruEquivalence'],check=True)
