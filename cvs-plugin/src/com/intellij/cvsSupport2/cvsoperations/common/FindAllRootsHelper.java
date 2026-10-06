/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.cvsSupport2.cvsoperations.common;

import com.intellij.cvsSupport2.CvsUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.intellij.util.containers.ContainerUtil.map;

public class FindAllRootsHelper {
  private FindAllRootsHelper() { }

  public interface ScanProgress {
    void checkCanceled();

    void onDirectory(@NotNull Path directory, int scannedDirectories, int rootsFound);
  }

  public static final class ScanResult {
    private final List<Path> myRoots;
    private final int myScannedDirectories;
    private final int myErrors;

    private ScanResult(@NotNull List<Path> roots, int scannedDirectories, int errors) {
      myRoots = List.copyOf(roots);
      myScannedDirectories = scannedDirectories;
      myErrors = errors;
    }

    public @NotNull List<Path> getRoots() {
      return myRoots;
    }

    public int getScannedDirectories() {
      return myScannedDirectories;
    }

    public int getErrors() {
      return myErrors;
    }
  }

  public static List<VirtualFile> findVersionedUnder(final List<? extends VirtualFile> coll) {
    final List<FilePath> pathList = map(coll, VcsUtil::getFilePath);
    final MyVisitor visitor = new MyVisitor();

    for (FilePath root : pathList) {
      final VirtualFile vf = root.getVirtualFile();
      if (vf == null) continue;
      VfsUtilCore.visitChildrenRecursively(vf, visitor);
    }

    return visitor.found;
  }

  /**
   * Discovers top-level CVS working copies from disk without depending on the current VFS snapshot.
   * A discovered working copy is a scan boundary: its versioned descendants belong to that root and
   * must not be returned as thousands of separate roots.
   */
  public static @NotNull ScanResult findVersionedPathsUnder(@NotNull Collection<? extends Path> containers,
                                                             @NotNull ScanProgress progress) {
    final Deque<Path> pending = new ArrayDeque<>();
    for (Path container : containers) {
      if (container == null) continue;
      final Path normalized = normalize(container);
      if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
        pending.addLast(normalized);
      }
    }

    final Set<Path> visited = new HashSet<>();
    final List<Path> found = new ArrayList<>();
    int scannedDirectories = 0;
    int errors = 0;

    while (!pending.isEmpty()) {
      progress.checkCanceled();
      final Path directory = pending.removeFirst();
      if (!visited.add(directory)) continue;

      scannedDirectories++;
      progress.onDirectory(directory, scannedDirectories, found.size());
      if (isCvsWorkingCopyRoot(directory)) {
        found.add(directory);
        continue;
      }

      final List<Path> children = new ArrayList<>();
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
        for (Path child : stream) {
          progress.checkCanceled();
          if (CvsUtil.CVS.equals(child.getFileName().toString())) continue;
          if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            children.add(normalize(child));
          }
        }
      }
      catch (IOException | SecurityException e) {
        errors++;
        continue;
      }
      children.sort(Comparator.comparing(Path::toString));
      pending.addAll(children);
    }

    found.sort(Comparator.comparing(Path::toString));
    return new ScanResult(found, scannedDirectories, errors);
  }

  public static boolean isAtOrUnderAnyRoot(@NotNull Path candidate, @NotNull Collection<? extends Path> roots) {
    final Path normalizedCandidate = normalize(candidate);
    for (Path root : roots) {
      if (normalizedCandidate.startsWith(normalize(root))) {
        return true;
      }
    }
    return false;
  }

  public static boolean isAncestorOfAnyRoot(@NotNull Path candidate, @NotNull Collection<? extends Path> roots) {
    final Path normalizedCandidate = normalize(candidate);
    for (Path root : roots) {
      final Path normalizedRoot = normalize(root);
      if (!normalizedCandidate.equals(normalizedRoot) && normalizedRoot.startsWith(normalizedCandidate)) {
        return true;
      }
    }
    return false;
  }

  static boolean isCvsWorkingCopyRoot(@NotNull Path directory) {
    final Path admin = directory.resolve(CvsUtil.CVS);
    return Files.isRegularFile(admin.resolve(CvsUtil.ENTRIES), LinkOption.NOFOLLOW_LINKS) &&
           Files.isRegularFile(admin.resolve(CvsUtil.CVS_ROOT_FILE), LinkOption.NOFOLLOW_LINKS) &&
           Files.isRegularFile(admin.resolve("Repository"), LinkOption.NOFOLLOW_LINKS);
  }

  private static @NotNull Path normalize(@NotNull Path path) {
    return path.toAbsolutePath().normalize();
  }

  private static class MyVisitor extends VirtualFileVisitor<Void> {
    private final List<VirtualFile> found = new ArrayList<>();

    @NotNull
    @Override
    public Result visitFileEx(@NotNull VirtualFile file) {
      if (!file.isDirectory()) {
        return CONTINUE;
      }
      if (CvsUtil.fileIsUnderCvsMaybeWithVfs(file)) {
        found.add(file);
        return SKIP_CHILDREN;
      }
      return CONTINUE;
    }
  }
}
