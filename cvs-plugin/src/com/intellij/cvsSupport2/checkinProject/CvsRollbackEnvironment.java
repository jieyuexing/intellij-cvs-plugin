/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package com.intellij.cvsSupport2.checkinProject;

import com.intellij.CvsBundle;
import com.intellij.cvsSupport2.CvsUtil;
import com.intellij.cvsSupport2.application.CvsEntriesManager;
import com.intellij.cvsSupport2.config.CvsConfiguration;
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor;
import com.intellij.cvsSupport2.cvsExecution.DefaultCvsOperationExecutorCallback;
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler;
import com.intellij.cvsSupport2.cvshandlers.CvsHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.rollback.DefaultRollbackEnvironment;
import com.intellij.openapi.vcs.rollback.RollbackProgressListener;
import com.intellij.openapi.vcs.ui.VcsBalloonProblemNotifier;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.netbeans.lib.cvsclient.admin.Entry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * @author yole
 */
public class CvsRollbackEnvironment extends DefaultRollbackEnvironment {

  private static final Logger LOG = Logger.getInstance(CvsRollbackEnvironment.class);
  private final Project myProject;

  public CvsRollbackEnvironment(final Project project) {
    myProject = project;
  }

  @Override
  public void rollbackChanges(List<? extends Change> changes, final List<VcsException> vcsExceptions, @NotNull final RollbackProgressListener listener) {
    listener.determinate();
    final List<FilePath> repositoryRestores = new ArrayList<>();
    for (Change change : changes) {
      listener.checkCanceled();
      final FilePath filePath = ChangesUtil.getFilePath(change);
      listener.accept(change);
      final VirtualFile parent = filePath.getVirtualFileParent();
      final String name = filePath.getName();

      switch (change.getType()) {
        case DELETED:
          if (!restoreFileFromLocalBase(filePath, parent, name, vcsExceptions)) {
            repositoryRestores.add(filePath);
          }
          break;

        case MODIFICATION:
          if (!restoreFileFromLocalBase(filePath, parent, name, vcsExceptions)) {
            repositoryRestores.add(filePath);
          }
          break;

        case MOVED:
        case NEW:
          removeEntry(filePath, vcsExceptions);
          break;
      }
    }
    scheduleRepositoryRestore(repositoryRestores);
  }

  @Override
  public void rollbackMissingFileDeletion(List<? extends FilePath> filePaths, List<? super VcsException> exceptions, RollbackProgressListener listener) {
    listener.determinate();
    listener.accept(filePaths);
    listener.checkCanceled();
    final List<FilePath> repositoryRestores = new ArrayList<>();
    for (FilePath filePath : filePaths) {
      final VirtualFile parent = filePath.getVirtualFileParent();
      if (parent == null) {
        addRollbackError(filePath, CvsBundle.message("message.error.rollback.parent.unavailable"), null, exceptions);
        continue;
      }
      final Entry entry = CvsEntriesManager.getInstance().getEntryFor(parent, filePath.getName());
      if (entry == null || entry.getRevision() == null || entry.getRevision().isEmpty()) {
        addRollbackError(filePath, CvsBundle.message("message.error.rollback.entry.missing"), null, exceptions);
        continue;
      }
      repositoryRestores.add(filePath);
    }
    scheduleRepositoryRestore(repositoryRestores);
  }

  /**
   * @return {@code true} when the file was restored locally or cannot be restored; {@code false} when a
   * repository-backed restore must be scheduled.
   */
  private boolean restoreFileFromLocalBase(@NotNull FilePath filePath,
                                           VirtualFile parent,
                                           @NotNull String name,
                                           @NotNull List<? super VcsException> exceptions) {
    if (parent == null) {
      addRollbackError(filePath, CvsBundle.message("message.error.rollback.parent.unavailable"), null, exceptions);
      return true;
    }

    final Entry entry = CvsEntriesManager.getInstance().getEntryFor(parent, name);
    if (entry == null || entry.getRevision() == null || entry.getRevision().isEmpty()) {
      addRollbackError(filePath, CvsBundle.message("message.error.rollback.entry.missing"), null, exceptions);
      return true;
    }

    try {
      final boolean makeReadOnly = CvsConfiguration.getInstance(myProject).MAKE_NEW_FILES_READONLY;
      if (CvsUtil.restoreFileFromCachedContentOrThrow(parent, name, entry.getRevision(), makeReadOnly)) {
        return true;
      }
    }
    catch (IOException e) {
      addRollbackError(filePath, CvsBundle.message("message.error.rollback.cache.restore", e.getMessage()), e, exceptions);
      return true;
    }
    return false;
  }

  private static void removeEntry(@NotNull FilePath filePath, @NotNull List<? super VcsException> exceptions) {
    try {
      CvsUtil.removeEntryForOrThrow(filePath.getIOFile());
    }
    catch (IOException | RuntimeException e) {
      addRollbackError(filePath, CvsBundle.message("message.error.rollback.entry.remove", e.getMessage()), e, exceptions);
    }
  }

  private void scheduleRepositoryRestore(@NotNull List<? extends FilePath> requestedFiles) {
    final FilePath[] files = new LinkedHashSet<>(requestedFiles).toArray(new FilePath[0]);
    if (files.length == 0) return;

    // IDEA 232 executes the generic RollbackEnvironment inside an explicitly non-cancelable progress section.
    // Queue repository I/O after that callback returns so it gets its own cancellable background indicator and
    // cannot trap the user in the stock Rollback dialog.
    ApplicationManager.getApplication().invokeLater(() -> {
      if (myProject.isDisposed()) return;

      final CvsHandler handler = CommandCvsHandler.createRestoreFilesHandler(
        files, CvsConfiguration.getInstance(myProject));
      final CvsOperationExecutor executor = new CvsOperationExecutor(myProject, ModalityState.NON_MODAL);
      executor.setIsQuietOperation(true);
      executor.setShowErrors(false);
      VcsBalloonProblemNotifier.showOverChangesView(
        myProject, CvsBundle.message("message.rollback.remote.queued", files.length), MessageType.INFO);
      executor.performActionSync(handler, new DefaultCvsOperationExecutorCallback() {
        @Override
        public void executionFinished(boolean successfully) {
          refreshAfterRepositoryRestore(files);
          if (myProject.isDisposed()) return;
          if (executor.getResult().isCanceled()) {
            VcsBalloonProblemNotifier.showOverChangesView(
              myProject, CvsBundle.message("message.rollback.remote.canceled"), MessageType.WARNING);
          }
          else if (!handler.getErrorsExceptAborted().isEmpty()) {
            for (VcsException error : handler.getErrorsExceptAborted()) {
              LOG.warn(error);
            }
            VcsBalloonProblemNotifier.showOverChangesView(
              myProject,
              CvsBundle.message("message.rollback.remote.failed", handler.getErrorsExceptAborted().size()),
              MessageType.ERROR);
          }
          else {
            VcsBalloonProblemNotifier.showOverChangesView(
              myProject, CvsBundle.message("message.rollback.remote.completed", files.length), MessageType.INFO);
          }
        }
      });
    }, ModalityState.NON_MODAL);
  }

  private void refreshAfterRepositoryRestore(@NotNull FilePath[] files) {
    final VcsDirtyScopeManager dirtyScopeManager = VcsDirtyScopeManager.getInstance(myProject);
    final LinkedHashSet<VirtualFile> parents = new LinkedHashSet<>();
    for (FilePath file : files) {
      final VirtualFile parent = file.getVirtualFileParent();
      if (parent != null && parent.isValid()) {
        parents.add(parent);
      }
      dirtyScopeManager.fileDirty(file);
    }
    for (VirtualFile parent : parents) {
      parent.refresh(true, false);
    }
  }

  private static void addRollbackError(@NotNull FilePath filePath,
                                       String detail,
                                       Throwable cause,
                                       @NotNull List<? super VcsException> exceptions) {
    final String safeDetail = detail == null || detail.isBlank() ?
                              CvsBundle.message("message.error.rollback.unknown") : detail;
    final String message = CvsBundle.message("message.error.rollback.failed", filePath.getPath(), safeDetail);
    exceptions.add(cause == null ? new VcsException(message) : new VcsException(message, cause));
  }
}
