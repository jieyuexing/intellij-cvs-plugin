package com.intellij.cvsSupport2.actions;

import com.intellij.CvsBundle;
import com.intellij.cvsSupport2.CvsVcs2;
import com.intellij.cvsSupport2.cvsstatuses.CvsLocalContentBaseline;
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

/** Creates an explicit local content index for a copied CVS working copy. */
public final class CreateLocalContentBaselineAction extends CvsGlobalAction {
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
    final List<VirtualFile> roots = Arrays.asList(ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(cvs));
    if (roots.isEmpty()) {
      Messages.showErrorDialog(project, CvsBundle.message("cvs.local.baseline.error.no.roots"),
                               CvsBundle.message("cvs.local.baseline.title"));
      return;
    }

    if (Messages.showOkCancelDialog(project,
                                    CvsBundle.message("cvs.local.baseline.confirmation"),
                                    CvsBundle.message("cvs.local.baseline.title"),
                                    Messages.getWarningIcon()) != Messages.OK) {
      return;
    }

    final CvsLocalContentBaseline baseline = CvsLocalContentBaseline.getInstance(project);
    ProgressManager.getInstance().run(new Task.Backgroundable(project,
                                                               CvsBundle.message("cvs.local.baseline.progress.title"), true) {
      private CvsLocalContentBaseline.BuildResult myResult;
      private IOException myError;

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          myResult = baseline.rebuild(roots, indicator);
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
                                   CvsBundle.message("cvs.local.baseline.title"));
          return;
        }
        if (myResult == null) {
          return;
        }
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
        Messages.showInfoMessage(project,
                                 CvsBundle.message("cvs.local.baseline.success",
                                                   myResult.getAcceptedFiles(),
                                                   myResult.getRepositoryVerifiedCleanFiles(),
                                                   myResult.getProtectedChanges(),
                                                   myResult.getSpecialEntries(),
                                                   myResult.getErrors()),
                                 CvsBundle.message("cvs.local.baseline.title"));
      }
    });
  }
}
