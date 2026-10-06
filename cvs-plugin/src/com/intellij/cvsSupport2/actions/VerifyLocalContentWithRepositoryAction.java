// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.cvsSupport2.actions;

import com.intellij.CvsBundle;
import com.intellij.cvsSupport2.CvsVcs2;
import com.intellij.cvsSupport2.cvsstatuses.CvsLocalContentBaseline;
import com.intellij.cvsSupport2.cvsstatuses.CvsRepositoryContentVerifier;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Runs an explicit read-only repository check before accepting timestamp-only content as clean. */
public final class VerifyLocalContentWithRepositoryAction extends CvsGlobalAction {
  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    final Project project = event.getProject();
    if (project == null) {
      return;
    }
    final CvsVcs2 cvs = CvsVcs2.getInstance(project);
    if (cvs == null) {
      return;
    }
    final ProjectLevelVcsManager manager = ProjectLevelVcsManager.getInstance(project);
    final List<VirtualFile> roots = Arrays.asList(manager.getRootsUnderVcs(cvs));
    if (roots.isEmpty()) {
      Messages.showErrorDialog(project, CvsBundle.message("cvs.local.baseline.error.no.roots"),
                               CvsBundle.message("cvs.repository.verification.title"));
      return;
    }

    if (Messages.showOkCancelDialog(project,
                                    CvsBundle.message("cvs.repository.verification.confirmation", roots.size()),
                                    CvsBundle.message("cvs.repository.verification.title"),
                                    Messages.getWarningIcon()) != Messages.OK) {
      return;
    }

    ProgressManager.getInstance().run(new Task.Backgroundable(
      project, CvsBundle.message("cvs.repository.verification.progress.title"), true) {
      private CvsLocalContentBaseline.RepositoryVerificationResult myResult;
      private IOException myError;

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          myResult = CvsRepositoryContentVerifier.verify(project, roots, indicator);
        }
        catch (IOException e) {
          myError = e;
        }
      }

      @Override
      public void onSuccess() {
        if (project.isDisposed()) {
          return;
        }
        if (myError != null) {
          Messages.showErrorDialog(project, myError.getLocalizedMessage(),
                                   CvsBundle.message("cvs.repository.verification.title"));
          return;
        }
        if (myResult == null) {
          return;
        }

        final VcsDirtyScopeManager dirtyScopeManager = VcsDirtyScopeManager.getInstance(project);
        final List<VirtualFile> mappedContainers = manager.getRootsUnderVcsWithoutFiltering(cvs);
        if (mappedContainers.isEmpty()) {
          for (VirtualFile root : roots) {
            dirtyScopeManager.dirDirtyRecursively(root);
          }
        }
        else {
          for (VirtualFile mappedContainer : mappedContainers) {
            dirtyScopeManager.dirDirtyRecursively(mappedContainer);
          }
        }

        Messages.showInfoMessage(project,
                                 CvsBundle.message("cvs.repository.verification.success",
                                                   myResult.getVerifiedClean(),
                                                   myResult.getReportedOrUnverified(),
                                                   myResult.getSpecialEntries(),
                                                   myResult.getScanErrors()),
                                 CvsBundle.message("cvs.repository.verification.title"));
      }
    });
  }
}
