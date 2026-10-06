// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.cvsSupport2.cvsoperations.common;

import com.intellij.CvsBundle;
import com.intellij.cvsSupport2.CvsVcs2;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps disk-backed CVS root discovery outside the hot ChangeProvider path.
 *
 * <p>The IntelliJ roots converter is synchronous and may be called often. On activation we therefore run one
 * cancellable background scan, publish only its complete result, and make later conversions a small cache lookup.
 * This also allows IDEA 232 to discover working copies whose ignored {@code CVS} directories are not in VFS yet.</p>
 */
public final class CvsRootDiscovery {
  private static final Logger LOG = Logger.getInstance(CvsRootDiscovery.class);

  private final Project myProject;
  private final CvsVcs2 myVcs;
  private final AtomicBoolean myScanScheduled = new AtomicBoolean();
  private final AtomicLong myGeneration = new AtomicLong();

  private volatile boolean myActive;
  private volatile boolean myHasCompletedScan;
  private volatile Set<Path> myScannedMappings = Collections.emptySet();
  private volatile Set<Path> mySuppressedMappings = Collections.emptySet();
  private volatile List<Path> myDiscoveredRoots = Collections.emptyList();

  public CvsRootDiscovery(@NotNull Project project, @NotNull CvsVcs2 vcs) {
    myProject = project;
    myVcs = vcs;
  }

  public void activate() {
    if (myActive) return;
    myActive = true;
    mySuppressedMappings = Collections.emptySet();
    final long generation = myGeneration.incrementAndGet();
    final ProjectLevelVcsManager manager = ProjectLevelVcsManager.getInstance(myProject);
    manager.runAfterInitialization(() -> {
      if (!isCurrent(generation)) return;
      scheduleScan(manager.getRootsUnderVcsWithoutFiltering(myVcs));
    });
  }

  public void deactivate() {
    if (!myActive) return;
    myActive = false;
    myGeneration.incrementAndGet();
  }

  public @NotNull List<VirtualFile> convertRoots(@NotNull List<VirtualFile> mappedRoots) {
    final Set<Path> mappingPaths = toPaths(mappedRoots);
    final boolean completeForCurrentMappings = myHasCompletedScan && mappingPaths.equals(myScannedMappings);
    final boolean suppressedForCurrentMappings = mappingPaths.equals(mySuppressedMappings);
    final Map<String, VirtualFile> result = new LinkedHashMap<>();

    // Preserve the old VFS path while the first disk scan is pending. Once the complete disk result is
    // available, conversions stay O(number of roots) instead of recursively walking the mapped container.
    if (!completeForCurrentMappings && !suppressedForCurrentMappings) {
      if (!myScanScheduled.get()) {
        for (VirtualFile root : FindAllRootsHelper.findVersionedUnder(mappedRoots)) {
          addRoot(result, root);
        }
      }
      scheduleScan(mappedRoots);
    }

    for (Path rootPath : myDiscoveredRoots) {
      if (!FindAllRootsHelper.isAtOrUnderAnyRoot(rootPath, mappingPaths)) continue;
      final VirtualFile root = LocalFileSystem.getInstance().findFileByPath(toVfsPath(rootPath));
      if (root != null && root.isValid() && root.isDirectory()) {
        addRoot(result, root);
      }
    }

    final List<VirtualFile> roots = new ArrayList<>(result.values());
    roots.sort(Comparator.comparing(VirtualFile::getPath));
    return roots;
  }

  private void scheduleScan(@NotNull Collection<? extends VirtualFile> mappedRoots) {
    if (!myActive || myProject.isDisposed() || ApplicationManager.getApplication().isUnitTestMode()) return;
    final Set<Path> mappingPaths = toPaths(mappedRoots);
    if (mappingPaths.isEmpty() || myHasCompletedScan && mappingPaths.equals(myScannedMappings)) return;
    if (mappingPaths.equals(mySuppressedMappings)) return;
    if (!myScanScheduled.compareAndSet(false, true)) return;

    final long generation = myGeneration.get();
    ProgressManager.getInstance().run(new Task.Backgroundable(
      myProject, CvsBundle.message("progress.title.discovering.cvs.roots"), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        boolean completed = false;
        try {
          indicator.setIndeterminate(true);
          final FindAllRootsHelper.ScanResult scan = FindAllRootsHelper.findVersionedPathsUnder(
            mappingPaths, new FindAllRootsHelper.ScanProgress() {
              @Override
              public void checkCanceled() {
                indicator.checkCanceled();
              }

              @Override
              public void onDirectory(@NotNull Path directory, int scannedDirectories, int rootsFound) {
                indicator.setText(CvsBundle.message("progress.text.discovering.cvs.roots", scannedDirectories, rootsFound));
                indicator.setText2(directory.toString());
              }
            });

          refreshDiscoveredRoots(scan.getRoots(), indicator);
          indicator.checkCanceled();
          if (!isCurrent(generation)) return;

          // Publish only after the complete scan and targeted VFS refresh. Cancellation keeps the previous
          // complete snapshot instead of exposing a partially traversed root set.
          myDiscoveredRoots = scan.getRoots();
          myScannedMappings = Set.copyOf(mappingPaths);
          mySuppressedMappings = Collections.emptySet();
          myHasCompletedScan = true;
          completed = true;
          LOG.info("CVS root discovery completed: roots=" + scan.getRoots().size() +
                   ", directories=" + scan.getScannedDirectories() + ", errors=" + scan.getErrors());

          ApplicationManager.getApplication().invokeLater(() -> {
            if (isCurrent(generation)) {
              markMappedContainersDirty(mappingPaths);
            }
          });
        }
        finally {
          if (!completed && isCurrent(generation)) {
            // Respect cancellation/failure until activation or mapping changes instead of immediately
            // spawning the same expensive scan from the next roots-converter call.
            mySuppressedMappings = Set.copyOf(mappingPaths);
          }
          myScanScheduled.set(false);
        }
      }
    });
  }

  private static void refreshDiscoveredRoots(@NotNull List<Path> roots, @NotNull ProgressIndicator indicator) {
    indicator.setIndeterminate(false);
    final int total = roots.size();
    if (total == 0) {
      indicator.setFraction(1.0);
      return;
    }
    for (int i = 0; i < total; i++) {
      indicator.checkCanceled();
      final Path root = roots.get(i);
      indicator.setText(CvsBundle.message("progress.text.refreshing.cvs.roots", i + 1, total));
      indicator.setText2(root.toString());
      LocalFileSystem.getInstance().refreshAndFindFileByPath(toVfsPath(root));
      indicator.setFraction((i + 1.0) / total);
    }
  }

  /**
   * Keep IDEA's dirty-scope anchor on the configured mapping rather than on a converted CVS root.
   *
   * <p>IDEA 232 builds {@code VcsDirtyScope.belongsTo(...)} from the configured mapping root, while
   * {@code markEverythingDirty()} seeds the scope with the roots returned by our custom converter.
   * The resulting mixed scope lets the provider traverse files but makes the platform
   * {@code ChangelistBuilder} reject every reported change as out of scope. Marking each original
   * mapping recursively gives the platform a coherent scope; {@link
   * com.intellij.cvsSupport2.cvsstatuses.CvsChangeProvider} then routes that container to the
   * discovered sibling working-copy roots.</p>
   */
  private void markMappedContainersDirty(@NotNull Set<Path> mappingPaths) {
    final VcsDirtyScopeManager dirtyScopeManager = VcsDirtyScopeManager.getInstance(myProject);
    for (Path mappingPath : mappingPaths) {
      final VirtualFile mapping = LocalFileSystem.getInstance().findFileByPath(toVfsPath(mappingPath));
      if (mapping != null && mapping.isValid() && mapping.isDirectory()) {
        dirtyScopeManager.dirDirtyRecursively(mapping);
      }
    }
  }

  private boolean isCurrent(long generation) {
    return myActive && !myProject.isDisposed() && generation == myGeneration.get();
  }

  private static @NotNull Set<Path> toPaths(@NotNull Collection<? extends VirtualFile> files) {
    final Set<Path> result = new HashSet<>();
    for (VirtualFile file : files) {
      if (file != null && file.isValid() && file.isDirectory()) {
        result.add(Path.of(file.getPath()).toAbsolutePath().normalize());
      }
    }
    return result;
  }

  private static void addRoot(@NotNull Map<String, VirtualFile> roots, @NotNull VirtualFile root) {
    roots.putIfAbsent(root.getPath(), root);
  }

  private static @NotNull String toVfsPath(@NotNull Path path) {
    return FileUtil.toSystemIndependentName(path.toString());
  }
}
