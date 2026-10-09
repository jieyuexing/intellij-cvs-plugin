// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.cvsSupport2.cvsoperations.cvsContent;

import com.intellij.cvsSupport2.CvsUtil;
import com.intellij.cvsSupport2.application.CvsEntriesManager;
import com.intellij.cvsSupport2.connections.CvsEnvironment;
import com.intellij.cvsSupport2.connections.CvsRootProvider;
import com.intellij.cvsSupport2.cvsoperations.common.CvsExecutionEnvironment;
import com.intellij.cvsSupport2.cvsoperations.common.LocalPathIndifferentOperation;
import com.intellij.cvsSupport2.cvsoperations.dateOrRevision.RevisionOrDate;
import com.intellij.cvsSupport2.cvsoperations.dateOrRevision.RevisionOrDateImpl;
import com.intellij.cvsSupport2.errorHandling.CannotFindCvsRootException;
import com.intellij.cvsSupport2.history.CvsRevisionNumber;
import com.intellij.cvsSupport2.util.CvsVfsUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtilRt;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.netbeans.lib.cvsclient.admin.Entry;
import org.netbeans.lib.cvsclient.command.Command;
import org.netbeans.lib.cvsclient.command.CommandAbortedException;
import org.netbeans.lib.cvsclient.command.checkout.CheckoutCommand;
import org.netbeans.lib.cvsclient.file.FileObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Collection;
import java.util.Collections;

@SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
public class GetFileContentOperation extends LocalPathIndifferentOperation {

  @NonNls private static final String VERS_PREFIX = "VERS:";

  public static class FileContentReader {
    private ByteArrayOutputStream myContent = null;
    private byte[] myBinaryContent = null;
    @NonNls private static final String TEXT_MESSAGE_TAG = "text";
    private boolean myLastTagIsText = false;

    public boolean isEmpty() {
      return myContent == null && myBinaryContent == null;
    }

    public byte @NotNull [] getReadContent() {
      if (myBinaryContent != null) {
        return myBinaryContent;
      } else {
        if (!myLastTagIsText && myContent.size() > 0) {
          myContent.write('\n');
        }
        return myContent.toByteArray();
      }
    }

    public void messageSent(final byte[] byteMessage, final boolean tagged) {
      myLastTagIsText = false;
      if (tagged) {
        String tagType = readTagTypeFrom(byteMessage);
        if (tagType != null) {
          if (TEXT_MESSAGE_TAG.equals(tagType)) {
            if (myContent == null) myContent = new ByteArrayOutputStream();
            final int textStartPosition = tagType.length();
            if (myContent.size() > 0) {
              myContent.write('\n');
            }
            myContent.write(byteMessage, textStartPosition + 1, byteMessage.length - textStartPosition - 1);
            myLastTagIsText = true;
          }
        }
      } else {
        if (myContent == null) myContent = new ByteArrayOutputStream();
        if (myContent.size() > 0) {
          myContent.write('\n');
        }
        myContent.write(byteMessage, 0, byteMessage.length);
      }
    }

    private static String readTagTypeFrom(final byte[] byteMessage) {
      final StringBuilder result = new StringBuilder();
      for (byte b : byteMessage) {
        if (b == ' ') return result.toString();
        result.append((char)b);
      }
      return null;
    }

    public void binaryMessageSent(final byte[] bytes) {
      myBinaryContent = bytes;
    }
  }

  private static final byte NOT_LOADED = 0;
  private static final byte FILE_NOT_FOUND = 1;
  private static final byte DELETED = 2;
  private static final byte SUCCESSFULLY_LOADED = 3;
  private static final byte LOADING = 4;

  private byte myState = NOT_LOADED;

  private FileContentReader myReader = new FileContentReader();
  private boolean myCommandSucceeded;
  private boolean myRemovedEntry;

  private byte[] myFileBytes = null;
  private String myRevision;
  private final String myModuleName;
  private final CvsRootProvider myRoot;
  private CvsRevisionNumber myCvsRevisionNumber;
  private final RevisionOrDate myRevisionOrDate;

  public static GetFileContentOperation createForFile(VirtualFile file, RevisionOrDate revisionOrDate)
    throws CannotFindCvsRootException {
    File ioFile = CvsVfsUtil.getFileFor(file);
    return new GetFileContentOperation(new File(getPathInRepository(file)),
                                       CvsRootProvider.createOn(ioFile),
                                       revisionOrDate
    );
  }

  public static GetFileContentOperation createForFile(@NotNull VirtualFile file) throws CannotFindCvsRootException {
    return createForFile(file, RevisionOrDateImpl.createOn(file));
  }

  public static GetFileContentOperation createForFile(FilePath filePath) throws CannotFindCvsRootException {
    String pathInRepository = CvsEntriesManager.getInstance().getRepositoryFor(filePath.getVirtualFileParent()) + "/" + filePath.getName();
    return new GetFileContentOperation(new File(pathInRepository),
                                       CvsRootProvider.createOn(filePath.getIOFile()),
                                       RevisionOrDateImpl.createOn(filePath.getVirtualFileParent(), filePath.getName()));
  }

  public GetFileContentOperation(File cvsFile, CvsEnvironment environment, @NotNull RevisionOrDate revisionOrDate) {
    super(environment);
    myRevisionOrDate = revisionOrDate;
    myRoot = CvsRootProvider.createOn(null, environment);
    myModuleName = cvsFile.getPath().replace(File.separatorChar, '/');
    myCvsRevisionNumber = myRevisionOrDate.getCvsRevisionNumber();
  }

  private static String getPathInRepository(VirtualFile file) {
    return CvsUtil.getModuleName(file);
  }

  @Override
  protected Collection<CvsRootProvider> getAllCvsRoots() {
    return Collections.singleton(myRoot);
  }

  public CvsRootProvider getRoot() {
    return myRoot;
  }

  @Override
  protected Command createCommand(CvsRootProvider root, CvsExecutionEnvironment cvsExecutionEnvironment) {
    myState = LOADING;
    myRoot.changeAdminRootTo(new File("."));
    myRoot.changeLocalRootTo(new File("."));
    CheckoutCommand command = new CheckoutCommand(null);
    command.setRecursive(false);
    command.addModule(myModuleName);
    command.setPrintToOutput(true);

    myRevisionOrDate.setForCommand(command);

    return command;
  }

  public String getRevision() {
    if (!isLoaded()) {
      return myRevisionOrDate.getRevision();
    }
    else {
      return myRevision;
    }

  }

  @Override
  public void execute(CvsExecutionEnvironment environment, boolean underReadAction)
    throws VcsException, CommandAbortedException {
    myState = NOT_LOADED;
    myFileBytes = null;
    myReader = new FileContentReader();
    myCommandSucceeded = false;
    myRemovedEntry = false;
    super.execute(environment, underReadAction);
    // 无异常返回仍可能是协议 error/EOF，或取消、已报告错误；均不能接受部分内容。
    if (!myCommandSucceeded || environment.getCvsCommandStopper().isAborted() ||
        !environment.getErrorProcessor().getErrors().isEmpty()) return;
    if (myRemovedEntry) {
      myState = DELETED;
    }
    else if (!myReader.isEmpty()) {
      myFileBytes = myReader.getReadContent();
      myState = SUCCESSFULLY_LOADED;
    }
    else {
      // checkout -p 无内容不能证明文件已删除，保守地留给调用方报错或重试。
      myState = FILE_NOT_FOUND;
    }
  }

  @Override
  protected void commandCompleted(boolean successfully) {
    myCommandSucceeded = successfully;
  }

  public synchronized byte @Nullable [] getFileBytes() {
    return myState == DELETED ? ArrayUtilRt.EMPTY_BYTE_ARRAY : tryGetFileBytes();
  }

  public synchronized byte @Nullable [] tryGetFileBytes() {
    return myState == SUCCESSFULLY_LOADED ? myFileBytes : null;
  }

  public boolean isDeleted() {
    return myState == DELETED;
  }

  @Override
  public void gotEntry(FileObject abstractFileObject, Entry entry) {
    super.gotEntry(abstractFileObject, entry);
    if (entry == null) {
      myRemovedEntry = true;
    }
    else {
      myRevision = entry.getRevision();
      myCvsRevisionNumber = new CvsRevisionNumber(myRevision);
    }
  }

  public boolean fileNotFound() {
    return myState == FILE_NOT_FOUND;
  }

  public boolean isLoaded() {
    return myState == SUCCESSFULLY_LOADED || myState == DELETED;
  }

  public CvsRevisionNumber getRevisionNumber() {
    LOG.assertTrue(myCvsRevisionNumber != null);
    return myCvsRevisionNumber;
  }

  @Override
  protected String getOperationName() {
    return "checkout";
  }

  @Override
  public void messageSent(String message, final byte[] byteMessage, boolean error, boolean tagged) {
    super.messageSent(message, byteMessage, error, tagged);
    if (!error) {
      myReader.messageSent(byteMessage, tagged);
    } else if (message.startsWith(VERS_PREFIX)) {
      final String version = message.substring(5).trim();
      myRevision = version;
      myCvsRevisionNumber = new CvsRevisionNumber(version);
    }
  }

  @Override
  public void binaryMessageSent(final byte[] bytes) {
    super.binaryMessageSent(bytes);
    myReader.binaryMessageSent(bytes);
  }

  @Override public boolean runInReadThread() {
    return false;
  }

  @Override
  protected boolean runInExclusiveLock() {
    return false;
  }
}
