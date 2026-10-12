package com.intellij.cvsSupport2.cvsoperations.common;

import com.intellij.openapi.vcs.VcsDirectoryMapping;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Disk fixtures; the standalone harness supplies the platform VirtualFile adapter. */
public final class CvsRootConvergenceTest {
  public static void run(Predicate<Path> isRoot) throws Exception {
    Path top = Files.createTempDirectory("GOFCatsWeb-");
    Path src = top.resolve("src"), deep = src.resolve("deep");
    wc(top, "GOFCatsWeb", "D/src////\n");
    wc(src, "GOFCatsWeb/src", "D/deep////\n");
    wc(deep, "GOFCatsWeb/src/deep", "");
    List<String> failures = new ArrayList<>();
    check(isRoot.test(top) && !isRoot.test(src) && !isRoot.test(deep), "checker: tracked children are roots", failures);
    var polluted = List.of(mapping(deep, "CVS"), mapping(top, "CVS"), mapping(src, "CVS"));
    var containers = List.of(top, src, deep);
    var retained = CvsRootMappings.collapse(polluted);
    var scanContainers = CvsRootMappings.retainContainers(polluted, retained, containers);
    var scan = FindAllRootsHelper.findVersionedPathsUnder(scanContainers, new FindAllRootsHelper.ScanProgress() {
      public void checkCanceled() { }
      public void onDirectory(Path path, int count, int roots) { }
    });
    check(scan.getErrors() == 0 && scan.getRoots().equals(List.of(top)), "converged containers must not rediscover children", failures);
    var clean = CvsRootMappings.expand(polluted, containers, containers);
    check(clean.equals(List.of(mapping(top, "CVS"))), "expand: polluted mappings retained: " + clean, failures);
    check(CvsRootMappings.expand(clean, List.of(top), List.of(top)).equals(clean), "idempotence", failures);
    for (String owner : List.of("Git", "")) {
      var boundary = List.of(mapping(top, owner), mapping(src, "CVS"), mapping(deep, "CVS"));
      check(CvsRootMappings.expand(boundary, List.of(src, deep), List.of(src, deep))
        .equals(List.of(mapping(top, owner), mapping(src, "CVS"))), "preserve " + owner + " boundary", failures);
    }
    var onlyChild = List.of(mapping(src, "CVS"));
    check(CvsRootMappings.expand(onlyChild, List.of(src), List.of(src)).equals(onlyChild), "child-only mapping", failures);
    var defaults = List.of(new VcsDirectoryMapping("", "CVS"), mapping(top, "CVS"), mapping(src, "CVS"));
    check(CvsRootMappings.expand(defaults, containers, containers)
      .equals(List.of(defaults.get(0), mapping(top, "CVS"))), "default mapping retained", failures);
    Files.writeString(src.resolve("CVS/Repository"), "independent\n");
    check(isRoot.test(src), "independent repository root", failures);
    var independent = List.of(mapping(top, "CVS"), mapping(src, "CVS"));
    check(CvsRootMappings.expand(independent, List.of(top, src), List.of(top, src)).equals(independent), "independent mapping", failures);
    Files.writeString(src.resolve("CVS/Repository"), "GOFCatsWeb/src\n");
    Files.writeString(src.resolve("CVS/Root"), ":local:/other\n");
    check(isRoot.test(src), "different server root", failures);
    check(CvsRootMappings.expand(independent, List.of(top, src), List.of(top, src)).equals(independent), "different server mapping", failures);
    if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    System.out.println("CvsRootConvergenceTest: PASSED");
  }

  private static void wc(Path path, String repository, String entries) throws Exception {
    Files.createDirectories(path.resolve("CVS"));
    Files.writeString(path.resolve("CVS/Entries"), entries);
    Files.writeString(path.resolve("CVS/Root"), ":local:/repository\n");
    Files.writeString(path.resolve("CVS/Repository"), repository + "\n");
  }
  private static VcsDirectoryMapping mapping(Path path, String vcs) {
    return new VcsDirectoryMapping(path.toString(), vcs);
  }
  private static void check(boolean value, String message, List<String> failures) {
    if (!value) failures.add(message);
  }
}
