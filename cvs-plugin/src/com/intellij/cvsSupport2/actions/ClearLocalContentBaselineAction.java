package com.intellij.cvsSupport2.actions;

import com.intellij.CvsBundle;
import com.intellij.cvsSupport2.cvsstatuses.CvsLocalContentBaseline;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/** Removes the optional local content index and restores conservative CVS timestamp-only status. */
public final class ClearLocalContentBaselineAction extends CvsGlobalAction {
  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    final Project project = event.getProject();
    if (project == null) {
      return;
    }
    final CvsLocalContentBaseline baseline = CvsLocalContentBaseline.getInstance(project);
    if (!baseline.hasBaseline()) {
      Messages.showInfoMessage(project, CvsBundle.message("cvs.local.baseline.clear.none"),
                               CvsBundle.message("cvs.local.baseline.title"));
      return;
    }
    if (Messages.showOkCancelDialog(project,
                                    CvsBundle.message("cvs.local.baseline.clear.confirmation"),
                                    CvsBundle.message("cvs.local.baseline.title"),
                                    Messages.getWarningIcon()) != Messages.OK) {
      return;
    }
    try {
      baseline.clear();
      VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
      Messages.showInfoMessage(project, CvsBundle.message("cvs.local.baseline.clear.success"),
                               CvsBundle.message("cvs.local.baseline.title"));
    }
    catch (IOException e) {
      Messages.showErrorDialog(project, e.getLocalizedMessage(), CvsBundle.message("cvs.local.baseline.title"));
    }
  }
}
