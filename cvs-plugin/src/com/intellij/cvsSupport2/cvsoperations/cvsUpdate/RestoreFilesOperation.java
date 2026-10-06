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

import com.intellij.cvsSupport2.connections.CvsRootProvider;
import com.intellij.cvsSupport2.cvsoperations.common.CvsExecutionEnvironment;
import com.intellij.cvsSupport2.cvsoperations.common.CvsOperationOnFiles;
import com.intellij.cvsSupport2.cvsoperations.common.UpdatedFilesManager;
import org.netbeans.lib.cvsclient.command.Command;
import org.netbeans.lib.cvsclient.command.GlobalOptions;
import org.netbeans.lib.cvsclient.command.update.UpdateCommand;
import org.netbeans.lib.cvsclient.file.FileObject;
import org.netbeans.lib.cvsclient.file.ICvsFileSystem;
import org.netbeans.lib.cvsclient.file.IFileReadOnlyHandler;
import org.netbeans.lib.cvsclient.file.IFileSystem;
import org.netbeans.lib.cvsclient.file.ILocalFileWriter;
import org.netbeans.lib.cvsclient.file.IReaderFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * Restores a group of working-copy files that share the same exact CVS revision.
 *
 * <p>The old rollback fallback issued a checkout for every file. A checkout performs module expansion and
 * checkout as separate protocol conversations, so a directory rollback paid that connection cost twice per
 * file. A clean update can send all selected file objects in one protocol conversation while still requesting
 * the exact revision recorded in CVS/Entries.</p>
 */
public final class RestoreFilesOperation extends CvsOperationOnFiles {
  private final String myRevision;
  private final boolean myMakeNewFilesReadOnly;
  private final Set<File> myRestoredFiles = new HashSet<>();

  public RestoreFilesOperation(String revision, boolean makeNewFilesReadOnly) {
    myRevision = revision;
    myMakeNewFilesReadOnly = makeNewFilesReadOnly;
  }

  @Override
  protected Command createCommand(CvsRootProvider root, CvsExecutionEnvironment cvsExecutionEnvironment) {
    final UpdateCommand command = new UpdateCommand();
    addFilesToCommand(root, command);
    command.setCleanCopy(true);
    command.setUpdateByRevisionOrTag(myRevision);
    return command;
  }

  @Override
  public void modifyOptions(GlobalOptions options) {
    super.modifyOptions(options);
    options.setCheckedOutFilesReadOnly(myMakeNewFilesReadOnly);
  }

  @Override
  protected ILocalFileWriter createLocalFileWriter(String cvsRoot,
                                                   UpdatedFilesManager mergedFilesCollector,
                                                   CvsExecutionEnvironment cvsExecutionEnvironment) {
    return new NoBackupLocalFileWriter(super.createLocalFileWriter(cvsRoot, mergedFilesCollector, cvsExecutionEnvironment),
                                       myRestoredFiles);
  }

  public Set<File> getRestoredFiles() {
    return Set.copyOf(myRestoredFiles);
  }

  @Override
  protected String getOperationName() {
    return "restore";
  }

  @Override
  public boolean runInReadThread() {
    return false;
  }

  /**
   * {@code cvs update -C} normally creates a {@code .#name.revision} sibling before overwriting a modified
   * file. Rollback already has Local History and must not leave thousands of backup files in the project.
   */
  private static final class NoBackupLocalFileWriter implements ILocalFileWriter {
    private final ILocalFileWriter myDelegate;
    private final Set<File> myRestoredFiles;

    private NoBackupLocalFileWriter(ILocalFileWriter delegate, Set<File> restoredFiles) {
      myDelegate = delegate;
      myRestoredFiles = restoredFiles;
    }

    @Override
    public void writeTextFile(FileObject fileObject,
                              int length,
                              InputStream inputStream,
                              boolean readOnly,
                              IReaderFactory readerFactory,
                              IFileReadOnlyHandler fileReadOnlyHandler,
                              IFileSystem fileSystem,
                              Charset charSet) throws IOException {
      myDelegate.writeTextFile(fileObject, length, inputStream, readOnly, readerFactory, fileReadOnlyHandler, fileSystem, charSet);
      myRestoredFiles.add(fileSystem.getFile(fileObject).getAbsoluteFile());
    }

    @Override
    public void writeBinaryFile(FileObject fileObject,
                                int length,
                                InputStream inputStream,
                                boolean readOnly,
                                IFileReadOnlyHandler fileReadOnlyHandler,
                                ICvsFileSystem cvsFileSystem) throws IOException {
      myDelegate.writeBinaryFile(fileObject, length, inputStream, readOnly, fileReadOnlyHandler, cvsFileSystem);
      myRestoredFiles.add(cvsFileSystem.getLocalFileSystem().getFile(fileObject).getAbsoluteFile());
    }

    @Override
    public void removeLocalFile(FileObject fileObject,
                                ICvsFileSystem cvsFileSystem,
                                IFileReadOnlyHandler fileReadOnlyHandler) throws IOException {
      myDelegate.removeLocalFile(fileObject, cvsFileSystem, fileReadOnlyHandler);
    }

    @Override
    public void renameLocalFile(FileObject fileObject, ICvsFileSystem cvsFileSystem, String newFileName) {
      // Intentionally keep the original file in place until the server response overwrites it.
    }

    @Override
    public void setNextFileDate(Date modifiedDate) {
      myDelegate.setNextFileDate(modifiedDate);
    }

    @Override
    public void setNextFileMode(String nextFileMode) {
      myDelegate.setNextFileMode(nextFileMode);
    }
  }
}
