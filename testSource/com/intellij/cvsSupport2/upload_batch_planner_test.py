"""Compile the production planner; CVS_TEST_ROOT can point to the baseline export."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(os.environ.get('CVS_TEST_ROOT', Path(__file__).resolve().parents[4]))
with tempfile.TemporaryDirectory(prefix='cvs-upload-planner-') as tmp:
    base = Path(tmp)
    source = base / 'PlannerTest.java'
    source.write_text('''
import java.util.*;
import org.netbeans.lib.cvsclient.command.update.UploadBatchPlanner;
import org.netbeans.lib.cvsclient.command.update.UploadBatchPlanner.Upload;
public class PlannerTest {
 static void check(boolean b) { if (!b) throw new AssertionError(); }
 public static void main(String[] args) {
  check(UploadBatchPlanner.plan(List.of(new Upload<>("a", 20)), 20).isEmpty());
  check(UploadBatchPlanner.plan(List.of(new Upload<>("a", 21)), 0).isEmpty());
  var p = UploadBatchPlanner.plan(List.of(new Upload<>("a", 10), new Upload<>("b", 10),
    new Upload<>("c", 21), new Upload<>("d", 0), new Upload<>("e", 2)), 20);
  check(p.size() == 3 && p.get(0).size() == 2 && p.get(1).get(0).file().equals("c"));
  check(p.get(2).size() == 2);
  check(UploadBatchPlanner.plan(List.of(new Upload<>("a", Long.MAX_VALUE),
    new Upload<>("b", 1)), 20).size() == 2);
  check(UploadBatchPlanner.plan(List.<Upload<String>>of(), 20).isEmpty());
  try { new Upload<>("bad", -1); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
  Random r = new Random(7);
  for (int n = 0; n < 200; n++) {
    List<Upload<Integer>> input = new ArrayList<>();
    long total = 0;
    for (int i = 0; i < n; i++) { long size = r.nextInt(50); input.add(new Upload<>(i, size)); total += size; }
    var batches = UploadBatchPlanner.plan(input, 20);
    if (total <= 20) { check(batches.isEmpty()); continue; }
    List<Upload<Integer>> flattened = new ArrayList<>();
    for (var batch : batches) {
      check(batch.size() == 1 || batch.stream().mapToLong(Upload::bytes).sum() <= 20);
      flattened.addAll(batch);
    }
    check(flattened.equals(input));
  }
  System.out.println("Upload batch planner: PASS");
 }
}''')
    java = Path(os.environ['JAVA_HOME']) / 'bin'
    subprocess.run([str(java/'javac'), '--release', '17', '-d', tmp, str(source),
                    str(ROOT/'javacvs-src/org/netbeans/lib/cvsclient/command/update/UploadBatchPlanner.java')], check=True)
    subprocess.run([str(java/'java'), '-cp', tmp, 'PlannerTest'], check=True)
