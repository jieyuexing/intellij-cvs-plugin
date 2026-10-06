// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.cvsSupport2.cvsstatuses;

import com.intellij.CvsBundle;
import com.intellij.cvsSupport2.actions.update.UpdateSettings;
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor;
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutorCallback;
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler;
import com.intellij.cvsSupport2.cvshandlers.CvsUpdatePolicy;
import com.intellij.cvsSupport2.cvshandlers.FileSetToBeUpdated;
import com.intellij.cvsSupport2.cvsoperations.cvsUpdate.UpdateOperation;
import com.intellij.cvsSupport2.cvsoperations.cvsMessages.CvsMessagesAdapter;
import com.intellij.cvsSupport2.updateinfo.UpdatedFilesProcessor;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.cvsIntegration.CvsResult;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.update.FileGroup;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies ambiguous timestamp-only changes with a read-only CVS update and turns only silent,
 * stable candidates into local content-baseline entries.
 */
public final class CvsRepositoryContentVerifier {
  private CvsRepositoryContentVerifier() {
  }

  public static @NotNull CvsLocalContentBaseline.RepositoryVerificationResult verify(
    @NotNull Project project,
    @NotNull Collection<VirtualFile> roots,
    @NotNull ProgressIndicator indicator) throws IOException {
    final CvsLocalContentBaseline baseline = CvsLocalContentBaseline.getInstance(project);
    final CvsLocalContentBaseline.RepositoryVerificationSnapshot snapshot =
      baseline.prepareRepositoryVerification(roots, indicator);
    try {
      indicator.checkCanceled();
      indicator.setIndeterminate(true);
      indicator.setText(CvsBundle.message("cvs.repository.verification.progress.repository"));
      indicator.setText2("");

      final FilePath[] filePaths = new FilePath[roots.size()];
      int index = 0;
      for (VirtualFile root : roots) {
        filePaths[index++] = VcsUtil.getFilePath(root);
      }

      final UpdatedFiles updatedFiles = CvsUpdatePolicy.createUpdatedFiles();
      final CommandCvsHandler handler = new CommandCvsHandler(
        CvsBundle.message("cvs.repository.verification.operation"),
        new UpdateOperation(filePaths, UpdateSettings.DONT_MAKE_ANY_CHANGES, project),
        FileSetToBeUpdated.selectedFiles(filePaths));
      handler.addCvsListener(new UpdatedFilesProcessor(updatedFiles));
      final AtomicBoolean commandStarted = new AtomicBoolean();
      handler.addCvsListener(new CvsMessagesAdapter() {
        @Override
        public void commandStarted(String command) {
          commandStarted.set(true);
        }
      });

      final CvsOperationExecutor executor =
        new CvsOperationExecutor(false, project, ModalityState.defaultModalityState());
      executor.setShowErrors(false);
      executor.setIsQuietOperation(true);
      executor.performActionSync(handler, CvsOperationExecutorCallback.EMPTY);
      final CvsResult result = executor.getResult();
      if (result.isCanceled()) {
        throw new ProcessCanceledException();
      }
      if (!result.getErrorsAndWarnings().isEmpty()) {
        throw repositoryFailure(result.getErrorsAndWarnings());
      }
      if (!commandStarted.get()) {
        throw new IOException(CvsBundle.message("cvs.repository.verification.error.not.started"));
      }

      final Set<String> reportedPaths = collectReportedPaths(updatedFiles);
      final Set<String> silentCandidates = selectSilentCandidates(snapshot.getCandidatePaths(), reportedPaths);
      indicator.checkCanceled();
      return baseline.completeRepositoryVerification(snapshot, silentCandidates, indicator);
    }
    finally {
      baseline.abortRepositoryVerification(snapshot);
    }
  }

  private static @NotNull IOException repositoryFailure(@NotNull List<VcsException> failures) {
    String detail = null;
    for (VcsException failure : failures) {
      if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
        detail = failure.getMessage();
        break;
      }
    }
    if (detail == null) {
      detail = CvsBundle.message("cvs.repository.verification.error.unknown");
    }
    return new IOException(CvsBundle.message("cvs.repository.verification.error.repository", detail));
  }

  static @NotNull Set<String> collectReportedPaths(@NotNull UpdatedFiles updatedFiles) {
    final Set<String> result = new HashSet<>();
    for (FileGroup group : updatedFiles.getTopLevelGroups()) {
      collectReportedPaths(group, result);
    }
    return result;
  }

  private static void collectReportedPaths(@NotNull FileGroup group, @NotNull Set<String> result) {
    for (String path : group.getFiles()) {
      result.add(normalize(path));
    }
    for (FileGroup child : group.getChildren()) {
      collectReportedPaths(child, result);
    }
  }

  /** Pure selection helper kept visible for a focused compatibility test. */
  public static @NotNull Set<String> selectSilentCandidates(@NotNull Collection<String> candidates,
                                                            @NotNull Collection<String> reportedPaths) {
    final Set<Path> reported = new HashSet<>(reportedPaths.size());
    for (String path : reportedPaths) {
      try {
        reported.add(Path.of(path).toAbsolutePath().normalize());
      }
      catch (RuntimeException ignored) {
        // An unparseable server path cannot match a valid local candidate; other server output still stays conservative.
      }
    }

    final Set<String> result = new HashSet<>();
    for (String candidateText : candidates) {
      final Path candidate;
      try {
        candidate = Path.of(candidateText).toAbsolutePath().normalize();
      }
      catch (RuntimeException ignored) {
        continue;
      }
      boolean wasReported = false;
      for (Path current = candidate; current != null; current = current.getParent()) {
        if (reported.contains(current)) {
          wasReported = true;
          break;
        }
      }
      if (!wasReported) {
        result.add(candidate.toString());
      }
    }
    return result;
  }

  private static @NotNull String normalize(@NotNull String path) {
    try {
      return Path.of(path).toAbsolutePath().normalize().toString();
    }
    catch (RuntimeException ignored) {
      return path;
    }
  }
}
