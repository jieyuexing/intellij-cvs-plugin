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
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vcs.VcsMappingListener;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 后台完整发现，再在 EDT 以公开映射 API 一次发布；不再使用 AbstractVcs 内部转换器。 */
public final class CvsRootDiscovery {
  private static final Logger LOG = Logger.getInstance(CvsRootDiscovery.class);
  private final Project myProject;
  // 下列状态只在 EDT 更新；后台任务只拥有不可变输入与本次结果。
  private boolean myActive;
  private boolean myInitialized;
  private boolean myScanScheduled;
  private long myGeneration;
  private List<VcsDirectoryMapping> myCompletedMappings;
  private List<VcsDirectoryMapping> mySuppressedMappings;
  private List<Path> myPublishedRoots = List.of();
  private MessageBusConnection myConnection;

  public CvsRootDiscovery(@NotNull Project project, @NotNull CvsVcs2 vcs) {
    myProject = project;
  }

  public void activate() {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (myActive || myProject.isDisposed()) return;
      myActive = true;
      myInitialized = false;
      myCompletedMappings = null;
      mySuppressedMappings = null;
      final long generation = ++myGeneration;
      myConnection = myProject.getMessageBus().connect(myProject);
      myConnection.subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
                             (VcsMappingListener)this::requestScan);
      ProjectLevelVcsManager.getInstance(myProject).runAfterInitialization(() ->
        ApplicationManager.getApplication().invokeLater(() -> {
          if (!isCurrent(generation)) return;
          myInitialized = true;
          requestScan();
        }));
    });
  }

  public void deactivate() {
    ApplicationManager.getApplication().invokeLater(() -> {
      myActive = false;
      myInitialized = false;
      ++myGeneration;
      if (myConnection != null) {
        myConnection.disconnect();
        myConnection = null;
      }
    });
  }

  private void requestScan() {
    ApplicationManager.getApplication().invokeLater(this::scanIfNeeded);
  }

  private void scanIfNeeded() {
    if (!myActive || !myInitialized || myProject.isDisposed() || myScanScheduled) return;
    ProjectLevelVcsManager manager = ProjectLevelVcsManager.getInstance(myProject);
    List<VcsDirectoryMapping> mappings = List.copyOf(manager.getDirectoryMappings());
    if (mappings.equals(myCompletedMappings)) {
      // setDirectoryMappings 的平台根刷新可能异步完成；收到映射事件后再标脏一次。
      markRootsDirty(myPublishedRoots);
      return;
    }
    if (mappings.equals(mySuppressedMappings)) return;
    Set<Path> containers = mappingPaths(mappings);
    if (containers.isEmpty()) return;
    myScanScheduled = true;
    final long generation = myGeneration;
    ProgressManager.getInstance().run(new Task.Backgroundable(
      myProject, CvsBundle.message("progress.title.discovering.cvs.roots"), true) {
      private FindAllRootsHelper.ScanResult myResult;

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        myResult = FindAllRootsHelper.findVersionedPathsUnder(containers, new FindAllRootsHelper.ScanProgress() {
          @Override
          public void checkCanceled() { indicator.checkCanceled(); }
          @Override
          public void onDirectory(@NotNull Path directory, int count, int roots) {
            indicator.setText(CvsBundle.message("progress.text.discovering.cvs.roots", count, roots));
            indicator.setText2(directory.toString());
          }
        }, path -> {
          VcsDirectoryMapping owner = CvsRootMappings.owner(mappings, path);
          return owner != null && "CVS".equals(owner.getVcs());
        });
        // 不把读不到的子树当作空目录并发布不完整映射。
        if (myResult.getErrors() != 0) throw new IllegalStateException("Incomplete CVS root scan: " + myResult.getErrors());
        refreshDiscoveredRoots(myResult.getRoots(), indicator);
        indicator.checkCanceled();
      }

      @Override
      public void onSuccess() {
        try {
          if (!isCurrent(generation) || !mappings.equals(manager.getDirectoryMappings())) return;
          List<VcsDirectoryMapping> expanded = CvsRootMappings.expand(mappings, containers, myResult.getRoots());
          List<Path> published = new ArrayList<>();
          for (Path root : myResult.getRoots()) {
            VcsDirectoryMapping owner = CvsRootMappings.owner(expanded, root);
            if (owner != null && "CVS".equals(owner.getVcs())) published.add(root);
          }
          if (!expanded.equals(mappings)) {
            myProject.getService(CvsRootMappingHistory.class).record(mappings, expanded);
            manager.setDirectoryMappings(expanded);
          }
          myCompletedMappings = expanded;
          mySuppressedMappings = null;
          myPublishedRoots = List.copyOf(published);
          markRootsDirty(myPublishedRoots);
          LOG.info("CVS root discovery completed: roots=" + published.size() +
                   ", directories=" + myResult.getScannedDirectories());
        }
        finally { finished(); }
      }

      @Override
      public void onCancel() { failed(); }

      @Override
      public void onThrowable(@NotNull Throwable error) {
        LOG.warn("CVS root discovery failed; mappings unchanged", error);
        failed();
      }

      private void failed() {
        if (isCurrent(generation)) mySuppressedMappings = mappings;
        finished();
      }

      private void finished() {
        myScanScheduled = false;
        // 用户在扫描中改映射时，旧结果作废，自动处理最新配置。取消同一输入不自动重试。
        if (myActive && (!isCurrent(generation) || !mappings.equals(manager.getDirectoryMappings()) &&
                         !manager.getDirectoryMappings().equals(myCompletedMappings))) requestScan();
      }
    });
  }

  private Set<Path> mappingPaths(List<VcsDirectoryMapping> mappings) {
    Set<Path> paths = new LinkedHashSet<>();
    for (VcsDirectoryMapping mapping : mappings) {
      if (!"CVS".equals(mapping.getVcs())) continue;
      if (mapping.isDefaultMapping()) {
        if (myProject.getBasePath() != null) paths.add(Path.of(myProject.getBasePath()).toAbsolutePath().normalize());
        for (VirtualFile root : ProjectRootManager.getInstance(myProject).getContentRoots()) {
          paths.add(Path.of(root.getPath()).toAbsolutePath().normalize());
        }
      }
      else paths.add(Path.of(mapping.getDirectory()).toAbsolutePath().normalize());
    }
    return Set.copyOf(paths);
  }

  private static void refreshDiscoveredRoots(List<Path> roots, ProgressIndicator indicator) {
    indicator.setIndeterminate(false);
    for (int i = 0; i < roots.size(); i++) {
      indicator.checkCanceled();
      Path root = roots.get(i);
      indicator.setText(CvsBundle.message("progress.text.refreshing.cvs.roots", i + 1, roots.size()));
      indicator.setText2(root.toString());
      VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(toVfsPath(root));
      if (file == null || !file.isValid() || !file.isDirectory()) {
        throw new IllegalStateException("Discovered CVS root disappeared: " + root);
      }
      indicator.setFraction((i + 1.0) / roots.size());
    }
  }

  private void markRootsDirty(List<Path> roots) {
    VcsDirtyScopeManager dirty = VcsDirtyScopeManager.getInstance(myProject);
    for (Path path : roots) {
      VirtualFile root = LocalFileSystem.getInstance().findFileByPath(toVfsPath(path));
      if (root != null && root.isValid()) dirty.dirDirtyRecursively(root);
    }
  }

  private boolean isCurrent(long generation) {
    return myActive && !myProject.isDisposed() && generation == myGeneration;
  }

  private static String toVfsPath(Path path) {
    return FileUtil.toSystemIndependentName(path.toString());
  }
}
