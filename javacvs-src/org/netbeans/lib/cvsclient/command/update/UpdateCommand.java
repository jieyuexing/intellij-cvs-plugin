/*****************************************************************************
 * Sun Public License Notice
 *
 * The contents of this file are subject to the Sun Public License Version
 * 1.0 (the "License"). You may not use this file except in compliance with
 * the License. A copy of the License is available at http://www.sun.com/
 *
 * The Original Code is the CVS Client Library.
 * The Initial Developer of the Original Code is Robert Greig.
 * Portions created by Robert Greig are Copyright (C) 2000.
 * All Rights Reserved.
 *
 * Contributor(s): Robert Greig.
 *****************************************************************************/
package org.netbeans.lib.cvsclient.command.update;

import org.jetbrains.annotations.NonNls;
import org.netbeans.lib.cvsclient.IClientEnvironment;
import org.netbeans.lib.cvsclient.IRequestProcessor;
import org.netbeans.lib.cvsclient.admin.Entry;
import org.netbeans.lib.cvsclient.command.*;
import org.netbeans.lib.cvsclient.connection.AuthenticationException;
import org.netbeans.lib.cvsclient.event.DualListener;
import org.netbeans.lib.cvsclient.event.ICvsListener;
import org.netbeans.lib.cvsclient.event.ICvsListenerRegistry;
import org.netbeans.lib.cvsclient.event.IEventSender;
import org.netbeans.lib.cvsclient.file.FileObject;
import org.netbeans.lib.cvsclient.progress.IProgressViewer;
import org.netbeans.lib.cvsclient.progress.RangeProgressViewer;
import org.netbeans.lib.cvsclient.progress.receiving.FileInfoAndDirectoryResponseProgressHandler;
import org.netbeans.lib.cvsclient.progress.sending.FileStateRequestsProgressHandler;
import org.netbeans.lib.cvsclient.progress.sending.IRequestsProgressHandler;
import org.netbeans.lib.cvsclient.request.CommandRequest;
import org.netbeans.lib.cvsclient.request.Requests;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.netbeans.lib.cvsclient.file.DirectoryObject;
import org.netbeans.lib.cvsclient.event.IMessageListener;
import org.netbeans.lib.cvsclient.event.TaggedMessageParser;

/**
 * The Update command. Updates files that have previously been checked out
 * from the repository with the checkout command. Modified files are not
 * overwritten.
 * @author  Robert Greig
 */
public final class UpdateCommand extends AbstractCommand
        implements IUpdatingCommand {

	// Fields =================================================================

  private long uploadBatchLimit;
  private Runnable checkCanceled = () -> {};
  private UploadBatchProgress uploadBatchProgress = (index, count, bytes) -> {};

  @FunctionalInterface
  public interface UploadBatchProgress {
    void started(int index, int count, long bytes);
    default void finished() {}
  }

  public void setUploadBatchLimit(long bytes) { uploadBatchLimit = bytes; }
  public void setUploadBatchProgress(UploadBatchProgress progress) { uploadBatchProgress = progress; }
  public void setCheckCanceled(Runnable check) { checkCanceled = check; }

  private boolean canBatch() {
    return uploadBatchLimit > 0 && !getGlobalOptions().isDoNoChanges()
           && !cleanCopy && !resetStickyOnes && updateByRevision == null && updateByDate == null
           && keywordSubst == null && mergeRevision1 == null && mergeRevision2 == null;
  }

  private List<List<UploadBatchPlanner.Upload<FileObject>>> planUploads(IClientEnvironment environment)
    throws IOException {
    List<UploadBatchPlanner.Upload<FileObject>> uploads = new ArrayList<>();
    Set<FileObject> seen = new HashSet<>();
    scanFileSystem(environment).visit(new ICvsFilesVisitor() {
      @Override public void handleDirectory(DirectoryObject directory) { checkCanceled.run(); }
      @Override public void handleFile(FileObject file, Entry entry, boolean exists) {
        checkCanceled.run();
        if (!exists || entry == null || entry.isRemoved() || !seen.add(file)) return;
        if (entry.isConflict() || entry.getLastModified() == null
            || isModified(file, entry.getLastModified(), environment)) {
          uploads.add(new UploadBatchPlanner.Upload<>(file,
            environment.getCvsFileSystem().getLocalFileSystem().getFile(file).length()));
        }
      }
    });
    return UploadBatchPlanner.plan(uploads, uploadBatchLimit);
  }

  // An error terminator alone is not a plugin error. Only a conflict-only round
  // may continue after false; all other failures stop before the next request.
  private static final class BatchMessages implements IMessageListener {
    boolean conflict;
    boolean otherError;
    private final TaggedMessageParser taggedMessages = new TaggedMessageParser();
    @Override public void messageSent(String message, byte[] bytes, boolean error, boolean tagged) {
      if (tagged) {
        if (!message.isEmpty()) accept(taggedMessages.parseTaggedMessage(message), false);
      }
      else {
        accept(taggedMessages.getString(), false);
        accept(message, error);
      }
    }
    private void accept(String message, boolean error) {
      if (message == null) return;
      if (message.startsWith("C ")) conflict = true;
      else if (error && !message.trim().isEmpty() && !message.matches("cvs.*: Updating.*")) otherError = true;
    }
    @Override public void binaryMessageSent(byte[] bytes) {}
  }

  private boolean executeBatches(IRequestProcessor processor, IEventSender sender,
                                ICvsListenerRegistry registry, IClientEnvironment environment,
                                IProgressViewer progress) throws CommandException, AuthenticationException {
    final List<List<UploadBatchPlanner.Upload<FileObject>>> batches;
    try { batches = planUploads(environment); }
    catch (IOException e) { throw new IOCommandException(e); }
    for (int i = 0; i < batches.size(); i++) {
      checkCanceled.run();
      UpdateCommand batch = new UpdateCommand();
      batch.setUseHeadIfNotFound(useHeadIfNotFound);
      batch.getGlobalOptions().setCheckedOutFilesReadOnly(getGlobalOptions().isCheckedOutFilesReadOnly());
      long bytes = 0;
      for (UploadBatchPlanner.Upload<FileObject> upload : batches.get(i)) {
        batch.addFileObject(upload.file());
        bytes += upload.bytes();
      }
      uploadBatchProgress.started(i + 1, batches.size(), bytes);
      BatchMessages messages = new BatchMessages();
      registry.addMessageListener(messages);
      boolean success;
      try { success = batch.execute(processor, sender, registry, environment, progress); }
      finally { registry.removeMessageListener(messages); }
      if (!success && (!messages.conflict || messages.otherError)) return false;
    }
    checkCanceled.run();
    if (!batches.isEmpty()) uploadBatchProgress.finished();
    return true;
  }

	private boolean buildDirectories;
	private boolean cleanCopy;
	private boolean pruneDirectories;
	private boolean resetStickyOnes;
	private boolean useHeadIfNotFound;
	private String updateByDate;
	private String updateByRevision;
	private KeywordSubstitution keywordSubst;
	private String mergeRevision1;
	private String mergeRevision2;

	// Setup ==================================================================

	public UpdateCommand() {
	}

	// Implemented ============================================================

	/**
	 * Execute the command.
	 * @param requestProcessor the client services object that provides any necessary
	 *               services to this command, including the ability to actually
	 *               process all the requests
	 */
	@Override
        public boolean execute(IRequestProcessor requestProcessor, IEventSender eventSender, ICvsListenerRegistry listenerRegistry, IClientEnvironment clientEnvironment, IProgressViewer progressViewer)
          throws CommandException, AuthenticationException {
    if (canBatch() && !executeBatches(requestProcessor, eventSender, listenerRegistry, clientEnvironment, progressViewer)) {
      return false;
    }
		final ICvsFiles cvsFiles;
		try {
			cvsFiles = scanFileSystem(clientEnvironment);
		}
		catch (IOException ex) {
			throw new IOCommandException(ex);
		}

		final Requests requests = new Requests(CommandRequest.UPDATE, clientEnvironment);
		requests.addArgumentRequest(isBuildDirectories(), "-d");
		requests.addArgumentRequest(isCleanCopy(), "-C");
		requests.addArgumentRequest(isResetStickyOnes(), "-A");
		requests.addArgumentRequest(isUseHeadIfNotFound(), "-f");
		requests.addArgumentRequest(getUpdateByDate(), "-D");
		requests.addArgumentRequest(getUpdateByRevision(), "-r");
		requests.addArgumentRequests(getMergeRevision1(), "-j");
		requests.addArgumentRequests(getMergeRevision2(), "-j");
		requests.addArgumentRequest(getKeywordSubst(), "-k");
		addFileRequests(cvsFiles, requests, clientEnvironment);
		requests.addLocalPathDirectoryRequest();
		addArgumentRequests(requests);

		DirectoryPruner directoryPruner = null;
		if (isPruneDirectories()) {
			directoryPruner = new DirectoryPruner(clientEnvironment);
			directoryPruner.registerListeners(listenerRegistry);
		}

		final IRequestsProgressHandler requestsProgressHandler = new FileStateRequestsProgressHandler(new RangeProgressViewer(progressViewer, 0.0, 0.5), cvsFiles);
		final ICvsListener responseProgressViewer = new FileInfoAndDirectoryResponseProgressHandler(new RangeProgressViewer(progressViewer, 0.5, 1.0), cvsFiles);

		final ICvsListener updateMessageParser = new UpdateMessageParser(eventSender, clientEnvironment.getCvsFileSystem());
		final ICvsListener listener = new DualListener(updateMessageParser, responseProgressViewer);
		listener.registerListeners(listenerRegistry);
		try {
			return requestProcessor.processRequests(requests, requestsProgressHandler);
		}
		finally {
			listener.unregisterListeners(listenerRegistry);

			if (directoryPruner != null) {
				directoryPruner.unregisterListeners(listenerRegistry);

				try {
					directoryPruner.pruneEmptyDirectories();
				}
				catch (IOException ex) {
					throw new IOCommandException(ex);
				}
			}
		}
	}

	/**
	 * This method returns how the command would looklike when typed on the
	 * command line.
	 * Each command is responsible for constructing this information.
	 */
	@Override
        public String getCvsCommandLine() {
		@NonNls final StringBuffer cvsCommandLine = new StringBuffer("update ");
		cvsCommandLine.append(getCvsArguments());
		appendFileArguments(cvsCommandLine);
		return cvsCommandLine.toString();
	}

	/**
	 * Resets all switches in the command.
	 * After calling this method, the command should have no switches defined
	 * and should behave defaultly.
	 */
	@Override
        public void resetCvsCommand() {
		super.resetCvsCommand();
		setRecursive(true);
		setCleanCopy(false);
		setBuildDirectories(false);
		setPruneDirectories(false);
		setResetStickyOnes(false);
		setUseHeadIfNotFound(false);
		setUpdateByDate(null);
		setUpdateByRevisionOrTag(null);
		setKeywordSubst(null);
		setMergeRevision1(null);
		setMergeRevision2(null);
	}

	@Override
        protected void addModifiedRequest(FileObject fileObject, Entry entry, Requests requests, IClientEnvironment clientEnvironment) {
		if (isCleanCopy()) {
			if (!getGlobalOptions().isDoNoChanges()) {
				final String newFileName = ".#" + fileObject.getName() + '.' + entry.getRevision();

				try {
					clientEnvironment.getLocalFileWriter().renameLocalFile(fileObject, clientEnvironment.getCvsFileSystem(), newFileName);
				}
				catch (IOException ex) {
					ex.printStackTrace();
				}
			}
			return;
		}

		super.addModifiedRequest(fileObject, entry, requests, clientEnvironment);
	}

	// Accessing ==============================================================

	public void setBuildDirectories(boolean buildDirectories) {
		this.buildDirectories = buildDirectories;
	}

	private boolean isBuildDirectories() {
		return buildDirectories;
	}

	public void setCleanCopy(boolean cleanCopy) {
		this.cleanCopy = cleanCopy;
	}

	private boolean isCleanCopy() {
		return cleanCopy;
	}

	public void setPruneDirectories(boolean pruneDirectories) {
		this.pruneDirectories = pruneDirectories;
	}

	private boolean isPruneDirectories() {
		return pruneDirectories;
	}

	private boolean isResetStickyOnes() {
		return resetStickyOnes;
	}

	@Override
        public void setResetStickyOnes(boolean resetStickyOnes) {
		this.resetStickyOnes = resetStickyOnes;
	}

	private boolean isUseHeadIfNotFound() {
		return useHeadIfNotFound;
	}

	@Override
        public void setUseHeadIfNotFound(boolean useHeadIfNotFound) {
		this.useHeadIfNotFound = useHeadIfNotFound;
	}

	private String getUpdateByDate() {
		return updateByDate;
	}

	@Override
        public void setUpdateByDate(String updateByDate) {
		this.updateByDate = getTrimmedString(updateByDate);
	}

	public String getUpdateByRevision() {
		return updateByRevision;
	}

	@Override
        public void setUpdateByRevisionOrTag(String updateByRevision) {
		this.updateByRevision = getTrimmedString(updateByRevision);
	}

	private KeywordSubstitution getKeywordSubst() {
		return keywordSubst;
	}

	public void setKeywordSubst(KeywordSubstitution keywordSubst) {
		this.keywordSubst = keywordSubst;
	}

	private String getMergeRevision1() {
		return mergeRevision1;
	}

	public void setMergeRevision1(String mergeRevision1) {
		this.mergeRevision1 = getTrimmedString(mergeRevision1);
	}

	private String getMergeRevision2() {
		return mergeRevision2;
	}

	public void setMergeRevision2(String mergeRevision2) {
		this.mergeRevision2 = getTrimmedString(mergeRevision2);
	}

	// Utils ==================================================================

	/**
	 * Returns the arguments of the command in the command-line style.
	 * Similar to getCVSCommand() however without the files and command's name
	 */
	private String getCvsArguments() {
		@NonNls final StringBuilder cvsArguments = new StringBuilder();
		if (isCleanCopy()) {
			cvsArguments.append("-C ");
		}
		if (!isRecursive()) {
			cvsArguments.append("-l ");
		}
		if (isBuildDirectories()) {
			cvsArguments.append("-d ");
		}
		if (isPruneDirectories()) {
			cvsArguments.append("-P ");
		}
		if (isResetStickyOnes()) {
			cvsArguments.append("-A ");
		}
		if (isUseHeadIfNotFound()) {
			cvsArguments.append("-f ");
		}
		if (getKeywordSubst() != null) {
			cvsArguments.append("-k");
			cvsArguments.append(getKeywordSubst());
			cvsArguments.append(' ');
		}
		if (getUpdateByRevision() != null) {
			cvsArguments.append("-r ");
			cvsArguments.append(getUpdateByRevision());
			cvsArguments.append(' ');
		}
		if (getUpdateByDate() != null) {
			cvsArguments.append("-D ");
			cvsArguments.append(getUpdateByDate());
			cvsArguments.append(' ');
		}
		if (getMergeRevision1() != null) {
			cvsArguments.append("-j ");
			cvsArguments.append(getMergeRevision1());
			cvsArguments.append(' ');

			if (getMergeRevision2() != null) {
				cvsArguments.append("-j ");
				cvsArguments.append(getMergeRevision2());
				cvsArguments.append(' ');
			}
		}
		return cvsArguments.toString();
	}

	@Override
        public void setUpdateByRevisionOrDate(String revision, final String date) {
		setUpdateByRevisionOrTag(revision);
		setUpdateByDate(date);
	}
}
