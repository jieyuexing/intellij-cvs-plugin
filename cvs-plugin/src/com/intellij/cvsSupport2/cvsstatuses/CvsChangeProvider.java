// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.cvsSupport2.cvsstatuses;

import com.intellij.CvsBundle;
import com.intellij.cvsSupport2.CvsUtil;
import com.intellij.cvsSupport2.CvsVcs2;
import com.intellij.cvsSupport2.application.CvsEntriesManager;
import com.intellij.cvsSupport2.application.CvsInfo;
import com.intellij.cvsSupport2.checkinProject.DirectoryContent;
import com.intellij.cvsSupport2.checkinProject.VirtualFileEntry;
import com.intellij.cvsSupport2.cvsoperations.cvsContent.GetFileContentOperation;
import com.intellij.cvsSupport2.cvsoperations.dateOrRevision.SimpleRevision;
import com.intellij.cvsSupport2.cvsoperations.common.FindAllRootsHelper;
import com.intellij.cvsSupport2.errorHandling.CannotFindCvsRootException;
import com.intellij.cvsSupport2.history.CvsRevisionNumber;
import com.intellij.cvsSupport2.util.CvsVfsUtil;
import com.intellij.history.FileRevisionTimestampComparator;
import com.intellij.history.LocalHistory;
import com.intellij.openapi.cvsIntegration.CvsResult;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.actions.VcsContextFactory;
import com.intellij.openapi.vcs.changes.BinaryContentRevision;
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ChangeListManagerGate;
import com.intellij.openapi.vcs.changes.ChangeProvider;
import com.intellij.openapi.vcs.changes.ChangelistBuilder;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.changes.VcsDirtyScope;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.cvsSupport2.util.CvsCharsetUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcsUtil.VcsUtil;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.netbeans.lib.cvsclient.admin.Entry;

public class CvsChangeProvider implements ChangeProvider {
  private static final Logger LOG = Logger.getInstance(CvsChangeProvider.class);

  private final CvsVcs2 myVcs;
  private final CvsEntriesManager myEntriesManager;
  private final ProjectLevelVcsManager myVcsManager;
  private final ChangeListManager myChangeListManager;
  private final CvsLocalContentBaseline myLocalContentBaseline;

  public CvsChangeProvider(final CvsVcs2 vcs, CvsEntriesManager entriesManager) {
    myVcs = vcs;
    myEntriesManager = entriesManager;
    myVcsManager = ProjectLevelVcsManager.getInstance(vcs.getProject());
    myChangeListManager = ChangeListManager.getInstance(vcs.getProject());
    myLocalContentBaseline = CvsLocalContentBaseline.getInstance(vcs.getProject());
  }

  @Override
  public void getChanges(@NotNull final VcsDirtyScope dirtyScope, @NotNull final ChangelistBuilder builder, @NotNull final ProgressIndicator progress,
                         @NotNull final ChangeListManagerGate addGate) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Processing changes for scope " + dirtyScope);
    }
    final HashSet<VirtualFile> cvsRoots = ContainerUtil.newHashSet(myVcsManager.getRootsUnderVcs(myVcs));
    final List<Path> cvsRootPaths = toPaths(cvsRoots);
    showBranchImOn(builder, dirtyScope, cvsRoots);

    final HashMap<String, FilePath> recursivePaths = new HashMap<>();
    for (FilePath path : dirtyScope.getRecursivelyDirtyDirectories()) {
      recursivePaths.putIfAbsent(normalizeDirtyPath(path), path);
    }
    final List<Path> processedRecursivePaths = new ArrayList<>();
    for (String normalizedPath : collapseNestedPaths(recursivePaths.keySet())) {
      final FilePath path = recursivePaths.get(normalizedPath);
      if (path == null) {
        continue;
      }
      final VirtualFile dir = path.getVirtualFile();
      if (dir != null) {
        processEntriesIn(dir, dirtyScope, builder, true, cvsRoots, cvsRootPaths, progress);
        addNormalizedPath(path, processedRecursivePaths);
      }
      else {
        processFile(path, builder, cvsRootPaths, progress);
      }
    }

    final HashSet<String> processedExplicitPaths = new HashSet<>();
    for (FilePath path : dirtyScope.getDirtyFiles()) {
      final String normalizedPath = normalizeDirtyPath(path);
      if (!processedExplicitPaths.add(normalizedPath) || isCoveredByRecursivePath(normalizedPath, processedRecursivePaths)) {
        continue;
      }
      if (path.isDirectory()) {
        final VirtualFile dir = path.getVirtualFile();
        if (dir != null) {
          processEntriesIn(dir, dirtyScope, builder, false, cvsRoots, cvsRootPaths, progress);
        }
        else {
          processFile(path, builder, cvsRootPaths, progress);
        }
      }
      else {
        processFile(path, builder, cvsRootPaths, progress);
      }
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Done processing changes");
    }
  }

  /**
   * IDEA may include both a mapped container and converted CVS roots in one recursive dirty scope.
   * Keep only the shallowest path so each working-copy subtree is visited once.
   */
  static @NotNull List<String> collapseNestedPaths(@NotNull Collection<String> paths) {
    final List<Path> normalized = new ArrayList<>();
    final List<String> unparseable = new ArrayList<>();
    for (String path : paths) {
      try {
        normalized.add(Path.of(path).toAbsolutePath().normalize());
      }
      catch (RuntimeException e) {
        unparseable.add(path);
      }
    }
    normalized.sort(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString));

    final List<Path> topLevel = new ArrayList<>();
    for (Path candidate : normalized) {
      boolean covered = false;
      for (Path ancestor : topLevel) {
        if (candidate.startsWith(ancestor)) {
          covered = true;
          break;
        }
      }
      if (!covered) {
        topLevel.add(candidate);
      }
    }

    final List<String> result = new ArrayList<>(topLevel.size() + unparseable.size());
    for (Path path : topLevel) {
      result.add(path.toString());
    }
    result.addAll(unparseable);
    return result;
  }

  private static @NotNull String normalizeDirtyPath(@NotNull FilePath path) {
    try {
      return Path.of(path.getPath()).toAbsolutePath().normalize().toString();
    }
    catch (RuntimeException e) {
      return path.getPath();
    }
  }

  private static void addNormalizedPath(@NotNull FilePath path, @NotNull Collection<Path> result) {
    try {
      result.add(Path.of(path.getPath()).toAbsolutePath().normalize());
    }
    catch (RuntimeException e) {
      LOG.warn("Cannot normalize recursive CVS dirty path " + path.getPath(), e);
    }
  }

  private static boolean isCoveredByRecursivePath(@NotNull String path, @NotNull Collection<Path> recursivePaths) {
    try {
      return FindAllRootsHelper.isAtOrUnderAnyRoot(Path.of(path), recursivePaths);
    }
    catch (RuntimeException e) {
      LOG.warn("Cannot normalize explicit CVS dirty path " + path, e);
      return false;
    }
  }

  @Override
  public boolean isModifiedDocumentTrackingRequired() {
    return true;
  }

  private void processEntriesIn(@NotNull VirtualFile dir, VcsDirtyScope scope, ChangelistBuilder builder, boolean recursively,
                                Collection<VirtualFile> cvsRoots, Collection<Path> cvsRootPaths,
                                final ProgressIndicator progress) throws VcsException {
    final FilePath path = VcsContextFactory.SERVICE.getInstance().createFilePathOn(dir);
    // A recursive dirty directory already authorizes its complete subtree. IDEA 232 does not
    // reliably report scope membership for descendants of roots produced by a custom roots
    // converter, so checking every child would truncate the scan at the first directory level.
    if (!recursively && !belongsToScope(path, scope)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Skipping out of scope path " + path);
      }
      return;
    }
    if (!isVersionedDirectory(dir, cvsRoots)) {
      final List<VirtualFile> nestedRoots = getNestedCvsRoots(dir, cvsRoots);
      if (!nestedRoots.isEmpty()) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Routing CVS container " + dir.getPath() + " to " + nestedRoots.size() + " working-copy roots");
        }
        if (recursively) {
          for (VirtualFile root : nestedRoots) {
            progress.checkCanceled();
            processEntriesIn(root, scope, builder, true, cvsRoots, cvsRootPaths, progress);
          }
        }
        return;
      }
      if (!isAtOrUnderCvsRoot(path, cvsRootPaths)) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Skipping mapped container path outside confirmed CVS roots: " + path);
        }
        return;
      }
      processUnknownDirectory(dir, builder);
      return;
    }
    final DirectoryContent dirContent = getDirectoryContent(dir, progress);

    for (VirtualFile file : dirContent.getUnknownFiles()) {
      builder.processUnversionedFile(VcsUtil.getFilePath(file));
    }
    for (VirtualFile file : dirContent.getIgnoredFiles()) {
      builder.processIgnoredFile(VcsUtil.getFilePath(file));
    }

    for (Entry entry : dirContent.getDeletedDirectories()) {
      builder.processLocallyDeletedFile(VcsUtil.getFilePath(CvsVfsUtil.getFileFor(dir, entry.getFileName()), true));
    }

    for (Entry entry : dirContent.getDeletedFiles()) {
      builder.processLocallyDeletedFile(VcsUtil.getFilePath(CvsVfsUtil.getFileFor(dir, entry.getFileName()), false));
    }

    for (VirtualFile file : dirContent.getUnknownDirectories()) {
      if (dirContent.getCvsInfo().getIgnoreFilter().shouldBeIgnored(file) || myVcsManager.isIgnored(file)) {
        builder.processIgnoredFile(VcsUtil.getFilePath(file));
      }
      else {
        builder.processUnversionedFile(VcsUtil.getFilePath(file));
      }
    }

    progress.checkCanceled();
    checkSwitchedDir(dir, builder, scope, cvsRoots);

    if (CvsUtil.fileIsUnderCvs(dir) && dir.getChildren().length == 1 /* admin dir */ &&
        dirContent.getDeletedFiles().isEmpty() && hasRemovedFiles(dirContent.getFiles())) {
      // directory is going to be deleted
      builder.processChange(new Change(CurrentContentRevision.create(path), CurrentContentRevision.create(path), FileStatus.DELETED), CvsVcs2.getKey());
    }
    for (VirtualFileEntry fileEntry : dirContent.getFiles()) {
      processFile(dir, fileEntry.getVirtualFile(), fileEntry.getEntry(), builder, progress);
    }

    if (recursively) {
      for (VirtualFileEntry directoryEntry : dirContent.getDirectories()) {
        progress.checkCanceled();
        final VirtualFile file = directoryEntry.getVirtualFile();
        if (!myVcsManager.isIgnored(file)) {
          processEntriesIn(file, scope, builder, true, cvsRoots, cvsRootPaths, progress);
        }
        else {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Skipping ignored path " + file.getPath());
          }
        }
      }
    }
  }

  /**
   * IDEA 232 may not consider a directory produced by a custom roots converter to belong to its
   * own dirty scope. Accept explicit non-recursive directory entries as well as normal members.
   * Recursive trees are authorized by their caller and do not use this check.
   */
  private static boolean belongsToScope(@NotNull FilePath path, @NotNull VcsDirtyScope scope) {
    if (scope.belongsTo(path)) {
      return true;
    }
    if (containsSamePath(scope.getRecursivelyDirtyDirectories(), path)) {
      return true;
    }
    return path.isDirectory() && containsSamePath(scope.getDirtyFilesNoExpand(), path);
  }

  private static boolean containsSamePath(@NotNull Collection<? extends FilePath> paths, @NotNull FilePath candidate) {
    for (FilePath path : paths) {
      if (FileUtil.pathsEqual(path.getPath(), candidate.getPath())) {
        return true;
      }
    }
    return false;
  }

  private boolean isVersionedDirectory(@NotNull VirtualFile dir, Collection<VirtualFile> cvsRoots) {
    if (cvsRoots.contains(dir)) {
      return true;
    }
    final VirtualFile parent = dir.getParent();
    if (parent == null) {
      return false;
    }
    final Entry entry = myEntriesManager.getEntryFor(parent, dir.getName());
    return entry != null && entry.isDirectory();
  }

  /**
   * The CVS roots converter exposes nested working copies as peer VCS roots, but a dirty scope may still
   * start at their common mapped container. Route directly to those roots instead of reporting the
   * container as one unversioned directory or recursively walking every generated directory below it.
   */
  private static @NotNull List<VirtualFile> getNestedCvsRoots(@NotNull VirtualFile dir,
                                                              @NotNull Collection<VirtualFile> cvsRoots) {
    final List<VirtualFile> result = new ArrayList<>();
    for (VirtualFile root : cvsRoots) {
      if (!dir.equals(root) && VfsUtilCore.isAncestor(dir, root, true)) {
        result.add(root);
      }
    }
    result.sort(Comparator.comparing(VirtualFile::getPath));
    return result;
  }

  private void processUnknownDirectory(@NotNull VirtualFile dir, @NotNull ChangelistBuilder builder) {
    final VirtualFile parent = dir.getParent();
    final boolean cvsIgnored = parent != null && myEntriesManager.getCvsInfoFor(parent).getIgnoreFilter().shouldBeIgnored(dir);
    if (cvsIgnored || myVcsManager.isIgnored(dir)) {
      builder.processIgnoredFile(VcsUtil.getFilePath(dir));
    }
    else {
      builder.processUnversionedFile(VcsUtil.getFilePath(dir));
    }
  }

  private static boolean hasRemovedFiles(final Collection<VirtualFileEntry> files) {
    for(VirtualFileEntry e: files) {
      if (e.getEntry().isRemoved()) {
        return true;
      }
    }
    return false;
  }

  private void processFile(final FilePath filePath,
                           final ChangelistBuilder builder,
                           final Collection<Path> cvsRootPaths,
                           final ProgressIndicator progress) throws VcsException {
    if (!isAtOrUnderCvsRoot(filePath, cvsRootPaths)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Skipping dirty file outside confirmed CVS roots: " + filePath);
      }
      return;
    }
    final VirtualFile dir = filePath.getVirtualFileParent();
    if (dir == null) return;

    final Entry entry = myEntriesManager.getEntryFor(dir, filePath.getName());
    final FileStatus status = CvsStatusProvider.getStatus(filePath.getVirtualFile(), entry);
    final VcsRevisionNumber number = entry != null ? createRevisionNumber(entry.getRevision(), status) : VcsRevisionNumber.NULL;
    processStatus(filePath, dir.findChild(filePath.getName()), status, number, builder);
    progress.checkCanceled();
    checkSwitchedFile(filePath, builder, dir, entry);
  }

  private static @NotNull List<Path> toPaths(@NotNull Collection<? extends VirtualFile> roots) {
    final List<Path> result = new ArrayList<>(roots.size());
    for (VirtualFile root : roots) {
      try {
        result.add(Path.of(root.getPath()).toAbsolutePath().normalize());
      }
      catch (RuntimeException e) {
        LOG.warn("Cannot normalize CVS root path " + root.getPath(), e);
      }
    }
    return result;
  }

  private static boolean isAtOrUnderCvsRoot(@NotNull FilePath path, @NotNull Collection<Path> roots) {
    try {
      return FindAllRootsHelper.isAtOrUnderAnyRoot(Path.of(path.getPath()), roots);
    }
    catch (RuntimeException e) {
      LOG.warn("Cannot normalize CVS dirty path " + path.getPath(), e);
      return false;
    }
  }

  private void processFile(final VirtualFile dir, @Nullable VirtualFile file, Entry entry, final ChangelistBuilder builder,
                           final ProgressIndicator progress) throws VcsException {
    final FilePath filePath = VcsUtil.getFilePath(dir, entry.getFileName());
    final FileStatus status = CvsStatusProvider.getStatus(file, entry);
    final VcsRevisionNumber number = createRevisionNumber(entry.getRevision(), status);
    processStatus(filePath, file, status, number, builder);
    progress.checkCanceled();
    checkSwitchedFile(filePath, builder, dir, entry);
  }

  private static CvsRevisionNumber createRevisionNumber(final String revision, final FileStatus status) {
    final String correctedRevision;
    if (FileStatus.DELETED.equals(status)) {
      final int idx = revision.indexOf('-');
      correctedRevision = (idx != -1) ? revision.substring(idx + 1) : revision;
    } else {
      correctedRevision = revision;
    }
    return new CvsRevisionNumber(correctedRevision);
  }

  private void showBranchImOn(final ChangelistBuilder builder, final VcsDirtyScope scope, HashSet<VirtualFile> cvsRoots) {
    for (VirtualFile root : cvsRoots) {
      if (scope.belongsTo(VcsUtil.getFilePath(root))) {
        checkTopLevelForBeingSwitched(root, builder);
      }
    }
  }

  private void checkTopLevelForBeingSwitched(final VirtualFile dir, final ChangelistBuilder builder) {
    final CvsInfo info = myEntriesManager.getCvsInfoFor(dir);
    if (info.getRepository() == null) return;
    final String dirTag = info.getStickyTag();
    if (dirTag != null) {
      final String caption = getSwitchedTagCaption(dirTag, null, false);
      if (caption != null) {
        builder.processRootSwitch(dir, caption);
      }
    } else {
      builder.processRootSwitch(dir, CvsUtil.HEAD);
    }
  }

  @Nullable
  private static String getSwitchedTagCaption(final String tag, @Nullable final String parentTag, final boolean checkParentTag) {
    if (tag == null) return CvsUtil.HEAD;
    final String tagOnly = tag.substring(1);
    if (CvsUtil.isNonDateTag(tag)) {
      // a switch between a branch tag and a non-branch tag is not a switch
      if (checkParentTag && parentTag != null && CvsUtil.isNonDateTag(parentTag)) {
        final String parentTagOnly = parentTag.substring(1);
        if (tagOnly.equals(parentTagOnly)) {
          return null;
        }
      }
      return CvsBundle.message("switched.tag.format", tagOnly);
    }
    else if (tag.startsWith(CvsUtil.STICKY_DATE_PREFIX)) {
      try {
        final Date date = Entry.STICKY_DATE_FORMAT.parse(tagOnly);
        return CvsBundle.message("switched.date.format", date);
      }
      catch (ParseException e) {
        return CvsBundle.message("switched.date.format", tagOnly);
      }
    }
    return null;
  }

  private void checkSwitchedDir(final VirtualFile dir,
                                final ChangelistBuilder builder,
                                final VcsDirtyScope scope,
                                Collection<VirtualFile> cvsRoots) {
    final VirtualFile parentDir = dir.getParent();
    if (parentDir == null || cvsRoots.contains(dir) || !myVcsManager.isFileInContent(parentDir)) {
      return;
    }
    final CvsInfo info = myEntriesManager.getCvsInfoFor(dir);
    if (info.getRepository() == null) {
      if (info.getIgnoreFilter().shouldBeIgnored(dir)) {
        builder.processIgnoredFile(VcsUtil.getFilePath(dir));
      }
      else {
        builder.processUnversionedFile(VcsUtil.getFilePath(dir));
      }
      return;
    }
    final String dirTag = info.getStickyTag();
    final CvsInfo parentInfo = myEntriesManager.getCvsInfoFor(parentDir);
    final String parentDirTag = parentInfo.getStickyTag();
    if (!Objects.equals(dirTag, parentDirTag)) {
      final String caption = getSwitchedTagCaption(dirTag, parentDirTag, true);
      if (caption != null) {
        builder.processSwitchedFile(dir, caption, true);
      }
    }
    else if (!scope.belongsTo(VcsContextFactory.SERVICE.getInstance().createFilePathOn(parentDir))) {
      // check if we're doing a partial refresh below a switched dir (IDEADEV-16611)
      final String parentBranch = myChangeListManager.getSwitchedBranch(parentDir);
      if (parentBranch != null) {
        builder.processSwitchedFile(dir, parentBranch, true);
      }
    }
  }

  private void checkSwitchedFile(final FilePath filePath, final ChangelistBuilder builder, final VirtualFile dir, final Entry entry) {
    // if content root itself is switched, ignore
    if (!myVcsManager.isFileInContent(dir)) {
      return;
    }
    final String dirTag = myEntriesManager.getCvsInfoFor(dir).getStickyTag();
    final String dirStickyInfo = getStickyInfo(dirTag);
    if (entry != null && !Objects.equals(entry.getStickyInformation(), dirStickyInfo)) {
      final VirtualFile file = filePath.getVirtualFile();
      if (file != null) {
        if (entry.getStickyTag() != null) {
          builder.processSwitchedFile(file, CvsBundle.message("switched.tag.format", entry.getStickyTag()), false);
        }
        else if (entry.getStickyDate() != null) {
          builder.processSwitchedFile(file, CvsBundle.message("switched.date.format", entry.getStickyDate()), false);
        }
        else if (entry.getStickyRevision() != null) {
          builder.processSwitchedFile(file, CvsBundle.message("switched.revision.format", entry.getStickyRevision()), false);
        }
        else {
          builder.processSwitchedFile(file, CvsUtil.HEAD, false);
        }
      }
    }
  }

  @Nullable
  private static String getStickyInfo(final String dirTag) {
    return (dirTag != null && dirTag.length() > 1) ? dirTag.substring(1) : null;
  }

  private void processStatus(final FilePath filePath,
                             final VirtualFile file,
                             final FileStatus status,
                             final VcsRevisionNumber number,
                             final ChangelistBuilder builder) throws VcsException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("processStatus: filePath=" + filePath + " status=" + status);
    }
    if (status == FileStatus.NOT_CHANGED) {
      if (file != null && FileDocumentManager.getInstance().isFileModified(file)) {
        builder.processChange(
          new Change(createCvsRevision(filePath, number), CurrentContentRevision.create(filePath), FileStatus.MODIFIED), CvsVcs2.getKey());
      }
      return;
    }
    if (status == FileStatus.MODIFIED || status == FileStatus.MERGE || status == FileStatus.MERGED_WITH_CONFLICTS) {
      final CvsUpToDateRevision beforeRevision = createCvsRevision(filePath, number);
      final ContentRevision afterRevision = CurrentContentRevision.create(filePath);
      final byte[] cachedContent = file == null ? null : getCachedUpToDateContentFor(file);
      if (cachedContent != null) {
        beforeRevision.setContent(cachedContent);
        if (beforeRevision instanceof BinaryContentRevision) {
          if (Arrays.equals(cachedContent, ((BinaryContentRevision)afterRevision).getBinaryContent())) {
            return;
          }
        }
        else if (CvsCharsetUtil.bytesToString(cachedContent, filePath.getCharset()).equals(afterRevision.getContent())) {
          return;
        }
      }
      else if (file != null && !FileDocumentManager.getInstance().isFileModified(file) &&
               myLocalContentBaseline.isCurrentContentAccepted(file, number.asString())) {
        return;
      }
      builder.processChange(new Change(beforeRevision, afterRevision, status), CvsVcs2.getKey());
    }
    else if (status == FileStatus.ADDED) {
      builder.processChange(new Change(null, CurrentContentRevision.create(filePath), status), CvsVcs2.getKey());
    }
    else if (status == FileStatus.DELETED) {
      // not sure about deleted content
      builder.processChange(new Change(createCvsRevision(filePath, number), null, status), CvsVcs2.getKey());
    }
    else if (status == FileStatus.DELETED_FROM_FS) {
      builder.processLocallyDeletedFile(filePath);
    }
    else if (status == FileStatus.UNKNOWN) {
      builder.processUnversionedFile(filePath);
    }
    else if (status == FileStatus.IGNORED) {
      builder.processIgnoredFile(filePath);
    }
  }

  private byte @Nullable [] getCachedUpToDateContentFor(@NotNull VirtualFile file) {
    final VirtualFile parent = file.getParent();
    if (parent == null) {
      return null;
    }
    final Entry entry = myEntriesManager.getEntryFor(parent, file.getName());
    if (entry == null) {
      return null;
    }
    if (entry.isResultOfMerge()) {
      final byte[] content = CvsUtil.getStoredContentForFile(file, entry.getRevision());
      if (content != null) {
        return content;
      }
    }
    return CvsUtil.getCachedStoredContent(parent, file.getName(), entry.getRevision());
  }

  public byte @Nullable [] getLastUpToDateContentFor(@NotNull final VirtualFile f) {
    final VirtualFile parent = f.getParent();
    final String name = f.getName();
    final Entry entry = myEntriesManager.getEntryFor(parent, name);
    if (entry != null && entry.isResultOfMerge()) {
      // try created by VCS during merge
      final byte[] content = CvsUtil.getStoredContentForFile(f, entry.getRevision());
      if (content != null) {
        return content;
      }
      // try cached by IDEA in CVS dir
      return CvsUtil.getCachedStoredContent(parent, name, entry.getRevision());
    }
    final long upToDateTimestamp = getUpToDateTimeForFile(f);
    final FileRevisionTimestampComparator c = new FileRevisionTimestampComparator() {
      @Override
      public boolean isSuitable(long revisionTimestamp) {
        return CvsStatusProvider.timeStampsAreEqual(upToDateTimestamp, revisionTimestamp);
      }
    };
    final byte[] localHistoryContent = LocalHistory.getInstance().getByteContent(f, c);
    if (localHistoryContent == null) {
      if (entry != null && CvsUtil.haveCachedContent(f, entry.getRevision())) {
        return CvsUtil.getCachedStoredContent(parent, name, entry.getRevision());
      }
    }
    return localHistoryContent;
  }

  public long getUpToDateTimeForFile(@NotNull VirtualFile vFile) {
    final Entry entry = myEntriesManager.getEntryFor(vFile.getParent(), vFile.getName());
    if (entry == null) return -1;
    // retrieve of any file version in time is not correct since update/merge was applie3d to already modified file
    /*if (entry.isResultOfMerge()) {
      long resultForMerge = CvsUtil.getUpToDateDateForFile(vFile);
      if (resultForMerge > 0) {
        return resultForMerge;
      }
    }*/

    final Date lastModified = entry.getLastModified();
    if (lastModified == null) return -1;
    return lastModified.getTime();
  }

  private CvsUpToDateRevision createCvsRevision(FilePath filePath, VcsRevisionNumber revisionNumber) {
    if (filePath.getFileType().isBinary()) {
      return new CvsUpToDateBinaryRevision(filePath, revisionNumber);
    }
    return new CvsUpToDateRevision(filePath, revisionNumber);
  }

  private static boolean isInContent(VirtualFile file) {
    return file == null || !FileTypeManager.getInstance().isFileIgnored(file);
  }

  private static DirectoryContent getDirectoryContent(VirtualFile directory, final ProgressIndicator progress) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Retrieving directory content for " + directory);
    }
    final CvsInfo cvsInfo = CvsEntriesManager.getInstance().getCvsInfoFor(directory);
    final DirectoryContent result = new DirectoryContent(cvsInfo);

    final HashMap<String, VirtualFile> nameToFileMap = new HashMap<>();
    for (VirtualFile child : CvsVfsUtil.getChildrenOf(directory)) {
      nameToFileMap.put(child.getName(), child);
    }

    for (final Entry entry : cvsInfo.getEntries()) {
      progress.checkCanceled();
      final String fileName = entry.getFileName();
      if (entry.isDirectory()) {
        if (nameToFileMap.containsKey(fileName)) {
          final VirtualFile virtualFile = nameToFileMap.get(fileName);
          if (isInContent(virtualFile)) {
            result.addDirectory(new VirtualFileEntry(virtualFile, entry));
          }
        }
        else if (!entry.isRemoved() && !FileTypeManager.getInstance().isFileIgnored(fileName)) {
          result.addDeletedDirectory(entry);
        }
      }
      else {
        if (nameToFileMap.containsKey(fileName) || entry.isRemoved()) {
          final VirtualFile virtualFile = nameToFileMap.get(fileName);
          if (isInContent(virtualFile)) {
            result.addFile(new VirtualFileEntry(virtualFile, entry));
          }
        }
        else if (!entry.isAddedFile()) {
          result.addDeletedFile(entry);
        }
      }
      nameToFileMap.remove(fileName);
    }

    for (final String name : nameToFileMap.keySet()) {
      progress.checkCanceled();
      final VirtualFile unknown = nameToFileMap.get(name);
      if (unknown.isDirectory()) {
        if (isInContent(unknown)) {
          result.addUnknownDirectory(unknown);
        }
      }
      else {
        if (isInContent(unknown)) {
          final boolean isIgnored = result.getCvsInfo().getIgnoreFilter().shouldBeIgnored(unknown);
          if (isIgnored) {
            result.addIgnoredFile(unknown);
          }
          else {
            result.addUnknownFile(unknown);
          }
        }
      }
    }

    return result;
  }

  private class CvsUpToDateRevision implements ByteBackedContentRevision {
    protected final FilePath myPath;
    private final VcsRevisionNumber myRevisionNumber;

    private byte[] myContent;

    protected CvsUpToDateRevision(final FilePath path, final VcsRevisionNumber revisionNumber) {
      myRevisionNumber = revisionNumber;
      myPath = path;
    }

    private void setContent(byte @NotNull [] content) {
      myContent = content;
    }

    @Override
    @Nullable
    public String getContent() throws VcsException {
      final byte[] fileBytes = getContentAsBytes();
      return fileBytes == null ? null : CvsCharsetUtil.bytesToString(fileBytes, myPath.getCharset());
    }

    @Override
    public byte @Nullable [] getContentAsBytes() throws VcsException {
      if (myContent == null) {
        try {
          myContent = getUpToDateBinaryContent();
        }
        catch (CannotFindCvsRootException e) {
          throw new VcsException(e);
        }
      }
      return myContent;
    }

    private byte @Nullable [] getUpToDateBinaryContent() throws CannotFindCvsRootException {
      final VirtualFile virtualFile = myPath.getVirtualFile();
      byte[] result = null;
      if (virtualFile != null) {
        result = getLastUpToDateContentFor(virtualFile);
      }
      if (result == null) {
        String revision = null;
        final GetFileContentOperation operation;
        if (virtualFile != null) {
          // todo maybe refactor where data lives
          final Entry entry = myEntriesManager.getEntryFor(virtualFile.getParent(), virtualFile.getName());
          if (entry != null) {
            revision = entry.getRevision();
            operation = GetFileContentOperation.createForFile(virtualFile, new SimpleRevision(revision));
          } else {
            operation = GetFileContentOperation.createForFile(myPath);
          }
        }
        else {
          operation = GetFileContentOperation.createForFile(myPath);
        }
        if (operation.getRoot().isOffline()) return null;
        final CvsResult executionResult =
          CvsVcs2.executeQuietOperation(CvsBundle.message("operation.name.get.file.content"), operation, myVcs.getProject()).getResult();
        if (executionResult.isCanceled() || executionResult.hasErrors()) return null;
        result = operation.tryGetFileBytes();

        if (result != null && revision != null) {
          // cache in CVS area to reduce remote requests number (old revisions are deleted)
          CvsUtil.storeContentForRevision(virtualFile, revision, result);
        }
      }
      return result;
    }

    @Override
    @NotNull
    public FilePath getFile() {
      return myPath;
    }

    @Override
    @NotNull
    public VcsRevisionNumber getRevisionNumber() {
      return myRevisionNumber;
    }

    @NonNls
    public String toString() {
      return "CvsUpToDateRevision:" + myPath;
    }
  }

  private class CvsUpToDateBinaryRevision extends CvsUpToDateRevision implements BinaryContentRevision {
    CvsUpToDateBinaryRevision(final FilePath path, final VcsRevisionNumber revisionNumber) {
      super(path, revisionNumber);
    }

    @Override
    public byte @Nullable [] getBinaryContent() throws VcsException {
      return getContentAsBytes();
    }

    @NonNls
    public String toString() {
      return "CvsUpToDateBinaryRevision:" + myPath;
    }
  }
}
