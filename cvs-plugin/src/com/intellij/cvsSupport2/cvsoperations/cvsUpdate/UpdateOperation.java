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
package com.intellij.cvsSupport2.cvsoperations.cvsUpdate;

import com.intellij.CvsBundle;
import com.intellij.cvsSupport2.CvsVcs2;
import com.intellij.cvsSupport2.actions.update.UpdateByBranchUpdateSettings;
import com.intellij.cvsSupport2.actions.update.UpdateSettings;
import com.intellij.cvsSupport2.connections.CvsRootProvider;
import com.intellij.cvsSupport2.cvshandlers.CvsHandler;
import com.intellij.cvsSupport2.cvsoperations.common.*;
import com.intellij.cvsSupport2.util.CvsVfsUtil;
import com.intellij.cvsSupport2.javacvsImpl.io.ReadWriteStatistics;
import com.intellij.openapi.vcs.VcsException;
import org.netbeans.lib.cvsclient.command.CommandException;
import org.netbeans.lib.cvsclient.connection.CommandConnection;
import org.netbeans.lib.cvsclient.progress.IProgressViewer;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.actions.VcsContextFactory;
import com.intellij.openapi.vfs.VirtualFile;
import org.netbeans.lib.cvsclient.command.Command;
import org.netbeans.lib.cvsclient.command.GlobalOptions;
import org.netbeans.lib.cvsclient.command.update.UpdateCommand;
import org.netbeans.lib.cvsclient.file.AbstractFileObject;
import org.netbeans.lib.cvsclient.file.ICvsFileSystem;
import org.netbeans.lib.cvsclient.util.IIgnoreFileFilter;

import java.io.File;
import java.util.Locale;

/**
 * author: lesya
 */

public class UpdateOperation extends CvsOperationOnFiles {
  private final UpdateSettings myUpdateSettings;
  private final ProjectLevelVcsManager myVcsManager;
  private final CvsVcs2 myVcs;

  public UpdateOperation(FilePath[] files, UpdateSettings updateSettings, Project project) {
    myUpdateSettings = updateSettings;
    myVcsManager = ProjectLevelVcsManager.getInstance(project);
    myVcs = CvsVcs2.getInstance(project);
    addAllFiles(files);
  }

  public UpdateOperation(FilePath[] files, String branchName,
                         boolean makeNewFilesReadOnly, Project project) {
    this(files, new UpdateByBranchUpdateSettings(branchName, makeNewFilesReadOnly), project);
  }

  public void addAllFiles(FilePath[] files) {
    for (FilePath file : files) {
      addFile(file.getIOFile());
    }
  }

  public void addAllFiles(VirtualFile[] files) {
    for (VirtualFile file : files) {
      addFile(new File(file.getPath()));
    }
  }

  @Override
  protected String getOperationName() {
    return "update";
  }

  @Override
  protected void execute(CvsRootProvider root, CvsExecutionEnvironment environment,
                         ReadWriteStatistics statistics, IProgressViewer progress)
    throws CommandException, VcsException {
    execute(root, environment,
            new CommandConnection(root.getCvsRoot().getRepositoryPath(), () -> root.createConnection(statistics)), progress);
  }

  @Override
  protected Command createCommand(CvsRootProvider root, CvsExecutionEnvironment cvsExecutionEnvironment) {
    final UpdateCommand updateCommand = new UpdateCommand();
    updateCommand.setUploadBatchLimit(20L * 1024 * 1024);
    updateCommand.setCheckCanceled(ProgressManager::checkCanceled);
    updateCommand.setUploadBatchProgress(new UpdateCommand.UploadBatchProgress() {
      @Override public void started(int index, int count, long bytes) {
        final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        if (indicator != null) indicator.setText(CvsBundle.message(
          "progress.text.update.upload.batch", index, count,
          String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024))));
      }
      @Override public void finished() {
        final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        if (indicator != null) indicator.setText(CvsBundle.message(
          "progress.text.command.running.for.file", getOperationName(), root.getCvsRootAsString()));
      }
    });
    addFilesToCommand(root, updateCommand);
    //updateCommand.setPruneDirectories(myUpdateSettings.getPruneEmptyDirectories());
    updateCommand.setCleanCopy(myUpdateSettings.getCleanCopy());
    updateCommand.setResetStickyOnes(myUpdateSettings.getResetAllSticky());
    updateCommand.setBuildDirectories(myUpdateSettings.getCreateDirectories());
    updateCommand.setKeywordSubst(myUpdateSettings.getKeywordSubstitution());

    myUpdateSettings.getRevisionOrDate().setForCommand(updateCommand);
    if (myUpdateSettings.getBranch1ToMergeWith() != null) {
      updateCommand.setMergeRevision1(myUpdateSettings.getBranch1ToMergeWith());
    }
    if (myUpdateSettings.getBranch2ToMergeWith() != null) {
      updateCommand.setMergeRevision2(myUpdateSettings.getBranch2ToMergeWith());
    }

    return updateCommand;
  }

  @Override
  public void modifyOptions(GlobalOptions options) {
    super.modifyOptions(options);
    options.setDoNoChanges(myUpdateSettings.getDontMakeAnyChanges());
    options.setCheckedOutFilesReadOnly(myUpdateSettings.getMakeNewFilesReadOnly());
  }

  @Override
  public int getFilesToProcessCount() {
    return CvsHandler.UNKNOWN_COUNT;
  }

  @Override
  public boolean fileIsUnderProject(final VirtualFile file) {
    return super.fileIsUnderProject(file) && myVcsManager.getVcsFor(file) == myVcs;
  }

  @Override
  public boolean fileIsUnderProject(File file) {
    final FilePath path = VcsContextFactory.SERVICE.getInstance().createFilePathOn(file);
    if (!super.fileIsUnderProject(file)) {
      return false;
    }
    final AbstractVcs vcs = ReadAction.compute(() -> myVcsManager.getVcsFor(path));
    return vcs == myVcs;
  }

  @Override
  protected IIgnoreFileFilter getIgnoreFileFilter() {
    final IIgnoreFileFilter ignoreFileFilterFromSuper = super.getIgnoreFileFilter();
    return new IIgnoreFileFilter() {
      @Override
      public boolean shouldBeIgnored(AbstractFileObject abstractFileObject, ICvsFileSystem cvsFileSystem) {
        if (ignoreFileFilterFromSuper.shouldBeIgnored(abstractFileObject, cvsFileSystem)) {
          return true;
        }
        final VirtualFile fileByIoFile = CvsVfsUtil.findFileByIoFile(cvsFileSystem.getLocalFileSystem().getFile(abstractFileObject));
        return fileByIoFile != null && myVcsManager.getVcsFor(fileByIoFile) != myVcs;
      }
    };
  }

  @Override
  protected ReceivedFileProcessor createReceivedFileProcessor(UpdatedFilesManager mergedFilesCollector, PostCvsActivity postCvsActivity) {
    return new UpdateReceivedFileProcessor(mergedFilesCollector,
                                           postCvsActivity);
  }

  @Override public boolean runInReadThread() {
    return false;
  }
}
