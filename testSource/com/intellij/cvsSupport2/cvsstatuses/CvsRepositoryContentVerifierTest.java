package com.intellij.cvsSupport2.cvsstatuses;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Focused standalone regression test for fail-closed repository result selection. */
public final class CvsRepositoryContentVerifierTest {
  public static void main(String[] args) {
    final Path root = Path.of(System.getProperty("java.io.tmpdir"), "cvs-verification-test").toAbsolutePath().normalize();
    final String clean = root.resolve("clean.txt").toString();
    final String modified = root.resolve("changed/modified.txt").toString();
    final String remoteChange = root.resolve("remote/update.txt").toString();
    final String sibling = root.resolve("changed-sibling/clean.txt").toString();

    assertEquals(Set.of(clean, sibling), CvsRepositoryContentVerifier.selectSilentCandidates(
      List.of(clean, modified, remoteChange, sibling),
      List.of(modified, root.resolve("remote").toString())));

    assertEquals(Set.of(), CvsRepositoryContentVerifier.selectSilentCandidates(
      List.of(modified), List.of(root.toString())));

    assertEquals(Set.of(clean), CvsRepositoryContentVerifier.selectSilentCandidates(
      List.of(clean), List.of(root.resolve("other.txt").toString())));

    assertEquals(List.of(root.resolve("gof").toString(), root.resolve("osn").toString()),
                 CvsChangeProvider.collapseNestedPaths(List.of(
                   root.resolve("gof/project/module-a").toString(),
                   root.resolve("osn").toString(),
                   root.resolve("gof").toString(),
                   root.resolve("gof/project/module-b").toString(),
                   root.resolve("gof").toString())));

    assertEquals(List.of(root.resolve("gof").toString(), root.resolve("gof-copy").toString()),
                 CvsChangeProvider.collapseNestedPaths(List.of(
                   root.resolve("gof").toString(),
                   root.resolve("gof-copy").toString())));

    final List<String> bulkCandidates = new ArrayList<>(20_000);
    final List<String> bulkReported = new ArrayList<>(4_000);
    for (int i = 0; i < 20_000; i++) {
      final String path = root.resolve("bulk/file-" + i + ".txt").toString();
      bulkCandidates.add(path);
      if (i < 4_000) {
        bulkReported.add(path);
      }
    }
    final Set<String> bulkSilent = CvsRepositoryContentVerifier.selectSilentCandidates(bulkCandidates, bulkReported);
    assertEquals(16_000, bulkSilent.size());
    assertEquals(false, bulkSilent.contains(root.resolve("bulk/file-3999.txt").toString()));
    assertEquals(true, bulkSilent.contains(root.resolve("bulk/file-19999.txt").toString()));

    System.out.println("CvsRepositoryContentVerifierTest: PASSED");
  }

  private static void assertEquals(Object expected, Object actual) {
    if (!expected.equals(actual)) {
      throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }
  }
}
