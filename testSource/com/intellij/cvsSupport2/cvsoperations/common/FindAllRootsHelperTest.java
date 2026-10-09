package com.intellij.cvsSupport2.cvsoperations.common;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Focused standalone regression test; run with assertions enabled. */
public final class FindAllRootsHelperTest {
  public static void main(String[] args) throws Exception {
    if (args.length == 2 && "--scan".equals(args[0])) {
      scanRealContainer(Path.of(args[1]));
      return;
    }
    final Path container = Files.createTempDirectory("cvs-root-discovery-");
    try {
      final Path applicationRoot = createCvsRoot(container.resolve("project/application"));
      createCvsRoot(applicationRoot.resolve("src"));
      Files.writeString(applicationRoot.resolve("src/CVS/Repository"), "module/src\n");
      final Path dictionaryRoot = createCvsRoot(container.resolve("docs/dictionary"));
      final Path runtime = Files.createDirectories(container.resolve("docker/weblogic12c/runtime/logs"));
      Files.writeString(runtime.resolve("server.log"), "not versioned");
      Files.createDirectories(container.resolve("broken/CVS"));
      Files.writeString(container.resolve("broken/CVS/Entries"), "");

      final int[] lastProgress = new int[2];
      final FindAllRootsHelper.ScanResult result = FindAllRootsHelper.findVersionedPathsUnder(
        List.of(container), new FindAllRootsHelper.ScanProgress() {
          @Override
          public void checkCanceled() { }

          @Override
          public void onDirectory(@NotNull Path directory, int scannedDirectories, int rootsFound) {
            lastProgress[0] = scannedDirectories;
            lastProgress[1] = rootsFound;
          }
        });

      assertEquals(List.of(dictionaryRoot, applicationRoot).stream().sorted().toList(), result.getRoots());
      assertTrue(result.getScannedDirectories() > result.getRoots().size(), "container directories were not scanned");
      assertTrue(lastProgress[0] > 0, "scan progress was not reported");
      assertTrue(FindAllRootsHelper.isAncestorOfAnyRoot(container, result.getRoots()), "container must remain a discovery ancestor");
      assertTrue(FindAllRootsHelper.isAtOrUnderAnyRoot(applicationRoot.resolve("src/Main.java"), result.getRoots()),
                 "tracked descendants must belong to their CVS root");
      assertFalse(FindAllRootsHelper.isAtOrUnderAnyRoot(runtime, result.getRoots()),
                  "non-root runtime tree must not belong to CVS status");
      assertFalse(result.getRoots().contains(applicationRoot.resolve("src")),
                  "CVS descendants must not be emitted as peer roots");

      final RuntimeException cancellation = new RuntimeException("expected cancellation");
      final int[] checks = new int[1];
      try {
        FindAllRootsHelper.findVersionedPathsUnder(List.of(container), new FindAllRootsHelper.ScanProgress() {
          @Override
          public void checkCanceled() {
            if (++checks[0] == 3) throw cancellation;
          }

          @Override
          public void onDirectory(@NotNull Path directory, int scannedDirectories, int rootsFound) { }
        });
        throw new AssertionError("cancellation did not stop the disk scan");
      }
      catch (RuntimeException e) {
        if (e != cancellation) throw e;
      }

      System.out.println("FindAllRootsHelperTest: PASSED");
    }
    finally {
      deleteRecursively(container);
    }
  }

  private static void scanRealContainer(@NotNull Path container) {
    final long started = System.nanoTime();
    final FindAllRootsHelper.ScanResult result = FindAllRootsHelper.findVersionedPathsUnder(
      List.of(container), new FindAllRootsHelper.ScanProgress() {
        @Override
        public void checkCanceled() { }

        @Override
        public void onDirectory(@NotNull Path directory, int scannedDirectories, int rootsFound) { }
      });
    final long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
    System.out.println("FindAllRootsHelperTest: roots=" + result.getRoots().size() +
                       ", directories=" + result.getScannedDirectories() +
                       ", errors=" + result.getErrors() + ", elapsedMs=" + elapsedMs);
    result.getRoots().forEach(System.out::println);
  }

  private static @NotNull Path createCvsRoot(@NotNull Path directory) throws IOException {
    final Path admin = Files.createDirectories(directory.resolve("CVS"));
    Files.writeString(admin.resolve("Entries"), "D/src////\n");
    Files.writeString(admin.resolve("Root"), ":local:/repository\n");
    Files.writeString(admin.resolve("Repository"), "module\n");
    return directory.toAbsolutePath().normalize();
  }

  private static void deleteRecursively(@NotNull Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static void assertEquals(Object expected, Object actual) {
    if (!expected.equals(actual)) {
      throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }
  }

  private static void assertTrue(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void assertFalse(boolean condition, String message) {
    assertTrue(!condition, message);
  }
}
