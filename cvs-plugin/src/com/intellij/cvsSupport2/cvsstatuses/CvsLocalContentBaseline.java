// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.cvsSupport2.cvsstatuses;

import com.intellij.CvsBundle;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.netbeans.lib.cvsclient.admin.Entry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A local, explicitly accepted content index for copied CVS working copies whose file timestamps no longer
 * match {@code CVS/Entries}. CVS normally has no pristine local blob, so an index must never be created
 * implicitly: doing that could hide changes which already existed before the working copy was copied.
 *
 * <p>The index lives under the IDE system directory rather than in the project or CVS administrative area.
 * Repository-backed {@code CVS/BaseRevisions} always takes precedence in {@link CvsChangeProvider}.</p>
 */
public final class CvsLocalContentBaseline {
  private static final Logger LOG = Logger.getInstance(CvsLocalContentBaseline.class);
  private static final int MAGIC = 0x43565342; // CVSB
  private static final int FORMAT_VERSION = 1;
  private static final int MAX_ENTRY_COUNT = 2_000_000;
  private static final int BUFFER_SIZE = 1024 * 1024;
  private static final LinkOption[] NO_FOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

  private final String myProjectBasePath;
  private final Path myStorageFile;
  private final Object myLoadLock = new Object();
  private final Object myMutationLock = new Object();
  private final ConcurrentHashMap<String, ObservedFingerprint> myObservations = new ConcurrentHashMap<>();
  private volatile Map<String, BaselineEntry> myEntries;
  private Object myActiveMutation;

  public CvsLocalContentBaseline(@NotNull Project project) {
    final String basePath = project.getBasePath();
    myProjectBasePath = basePath == null ? "" : normalize(Path.of(basePath));
    myStorageFile = myProjectBasePath.isEmpty() ? null : Path.of(PathManager.getSystemPath(),
                                                                 "cvs-local-content-baselines",
                                                                 sha256Hex(myProjectBasePath) + ".bin");
  }

  public static @NotNull CvsLocalContentBaseline getInstance(@NotNull Project project) {
    return project.getService(CvsLocalContentBaseline.class);
  }

  public boolean hasBaseline() {
    return myStorageFile != null && Files.isRegularFile(myStorageFile, NO_FOLLOW_LINKS);
  }

  /**
   * Returns true only when the accepted revision and current on-disk bytes match the local index.
   * Matching size and timestamp is the fast path; changed metadata is re-hashed once per observation.
   */
  public boolean isCurrentContentAccepted(@NotNull VirtualFile file, @NotNull String revision) {
    final Path path;
    try {
      path = Path.of(file.getPath()).toAbsolutePath().normalize();
    }
    catch (RuntimeException e) {
      return false;
    }
    final String key = normalize(path);
    final BaselineEntry baseline = getEntries().get(key);
    if (baseline == null || !revision.equals(baseline.revision)) {
      return false;
    }

    final BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW_LINKS);
    }
    catch (IOException e) {
      return false;
    }
    if (!attributes.isRegularFile()) {
      return false;
    }
    final long size = attributes.size();
    final long modified = attributes.lastModifiedTime().toMillis();
    if (size == baseline.size && modified == baseline.modified) {
      return true;
    }

    final ObservedFingerprint observed = myObservations.get(key);
    if (observed != null && observed.size == size && observed.modified == modified) {
      return observed.matches;
    }

    try {
      final Fingerprint fingerprint = captureStableFingerprint(path);
      if (fingerprint == null) {
        return false;
      }
      final boolean matches = MessageDigest.isEqual(baseline.digest, fingerprint.digest);
      myObservations.put(key, new ObservedFingerprint(fingerprint.size, fingerprint.modified, matches));
      return matches;
    }
    catch (IOException e) {
      LOG.debug("Cannot verify local CVS content baseline for " + path, e);
      return false;
    }
  }

  /**
   * Replaces the baseline only after the full scan and atomic index write succeed.
   */
  public @NotNull BuildResult rebuild(@NotNull Collection<VirtualFile> roots,
                                      @NotNull ProgressIndicator indicator) throws IOException {
    final Object mutation = new Object();
    beginMutation(mutation);
    try {
      return doRebuild(roots, indicator);
    }
    finally {
      endMutation(mutation);
    }
  }

  /**
   * Captures the timestamp-mismatched files which have no repository-backed local copy. The returned
   * snapshot keeps baseline mutations serialized until it is completed or aborted.
   */
  public @NotNull RepositoryVerificationSnapshot prepareRepositoryVerification(
    @NotNull Collection<VirtualFile> roots,
    @NotNull ProgressIndicator indicator) throws IOException {
    final Object mutation = new Object();
    beginMutation(mutation);
    try {
      if (myStorageFile == null) {
        throw new IOException(CvsBundle.message("cvs.local.baseline.error.project.path"));
      }
      indicator.setIndeterminate(true);
      indicator.setText(CvsBundle.message("cvs.repository.verification.progress.scan"));
      final ScanResult scan = collectTrackedFiles(roots, indicator);
      final List<TrackedFile> candidates = new ArrayList<>();
      for (TrackedFile file : scan.files) {
        if (file.cachedRevision == null) {
          candidates.add(file);
        }
      }
      if (candidates.isEmpty()) {
        throw new IOException(CvsBundle.message("cvs.repository.verification.error.no.candidates"));
      }
      return new RepositoryVerificationSnapshot(mutation, candidates, scan.specialEntries, scan.errors);
    }
    catch (IOException | RuntimeException e) {
      endMutation(mutation);
      throw e;
    }
  }

  /**
   * Atomically replaces the optional baseline with only the candidates that a complete repository
   * dry-run left silent. Any working-file or CVS administrative change during verification aborts
   * the complete replacement.
   */
  public @NotNull RepositoryVerificationResult completeRepositoryVerification(
    @NotNull RepositoryVerificationSnapshot snapshot,
    @NotNull Set<String> silentCandidatePaths,
    @NotNull ProgressIndicator indicator) throws IOException {
    try {
      requireActive(snapshot.myMutation);
      final Set<String> expected = new HashSet<>(snapshot.myCandidatePaths);
      if (!expected.containsAll(silentCandidatePaths)) {
        throw new IOException(CvsBundle.message("cvs.repository.verification.error.invalid.result"));
      }

      indicator.setIndeterminate(false);
      indicator.setText(CvsBundle.message("cvs.repository.verification.progress.hash"));
      final Map<Path, Fingerprint> currentEntriesFingerprints = new HashMap<>();
      final Map<String, BaselineEntry> replacement = new HashMap<>();
      int verifiedClean = 0;

      for (int i = 0; i < snapshot.myCandidates.size(); i++) {
        indicator.checkCanceled();
        final TrackedFile candidate = snapshot.myCandidates.get(i);
        final String path = normalize(candidate.path);
        indicator.setText2(candidate.path.toString());
        indicator.setFraction(i / (double)snapshot.myCandidates.size());
        if (!silentCandidatePaths.contains(path)) {
          continue;
        }

        Fingerprint currentEntries = currentEntriesFingerprints.get(candidate.entriesPath);
        if (currentEntries == null) {
          currentEntries = captureStableFingerprint(candidate.entriesPath);
          if (currentEntries == null) {
            throw changedDuringRepositoryVerification(candidate.entriesPath);
          }
          currentEntriesFingerprints.put(candidate.entriesPath, currentEntries);
        }
        if (!fingerprintsEqual(candidate.entriesFingerprint, currentEntries)) {
          throw changedDuringRepositoryVerification(candidate.entriesPath);
        }

        final Fingerprint fingerprint = captureStableFingerprint(candidate.path);
        if (fingerprint == null || fingerprint.size != candidate.size || fingerprint.modified != candidate.modified) {
          throw changedDuringRepositoryVerification(candidate.path);
        }
        replacement.put(path,
                        new BaselineEntry(candidate.revision, fingerprint.size, fingerprint.modified, fingerprint.digest));
        verifiedClean++;
      }

      indicator.checkCanceled();
      indicator.setFraction(1.0);
      indicator.setText2("");
      writeEntries(replacement);
      myEntries = immutableCopy(replacement);
      myObservations.clear();
      return new RepositoryVerificationResult(snapshot.myCandidates.size(), verifiedClean,
                                              snapshot.myCandidates.size() - verifiedClean,
                                              snapshot.mySpecialEntries, snapshot.myScanErrors);
    }
    finally {
      endMutation(snapshot.myMutation);
    }
  }

  public void abortRepositoryVerification(@NotNull RepositoryVerificationSnapshot snapshot) {
    endMutation(snapshot.myMutation);
  }

  private static @NotNull IOException changedDuringRepositoryVerification(@NotNull Path path) {
    return new IOException(CvsBundle.message("cvs.repository.verification.error.changed", path));
  }

  private void beginMutation(@NotNull Object mutation) throws IOException {
    synchronized (myMutationLock) {
      if (myActiveMutation != null) {
        throw new IOException(CvsBundle.message("cvs.local.baseline.error.busy"));
      }
      myActiveMutation = mutation;
    }
  }

  private void requireActive(@NotNull Object mutation) throws IOException {
    synchronized (myMutationLock) {
      if (myActiveMutation != mutation) {
        throw new IOException(CvsBundle.message("cvs.repository.verification.error.invalid.result"));
      }
    }
  }

  private void endMutation(@NotNull Object mutation) {
    synchronized (myMutationLock) {
      if (myActiveMutation == mutation) {
        myActiveMutation = null;
      }
    }
  }

  private @NotNull BuildResult doRebuild(@NotNull Collection<VirtualFile> roots,
                                         @NotNull ProgressIndicator indicator) throws IOException {
    if (myStorageFile == null) {
      throw new IOException(CvsBundle.message("cvs.local.baseline.error.project.path"));
    }

    indicator.setIndeterminate(true);
    indicator.setText(CvsBundle.message("cvs.local.baseline.progress.scan"));
    final ScanResult scan = collectTrackedFiles(roots, indicator);
    if (scan.files.isEmpty()) {
      throw new IOException(CvsBundle.message("cvs.local.baseline.error.no.files"));
    }

    final Map<String, BaselineEntry> replacement = new HashMap<>();
    int accepted = 0;
    int repositoryVerifiedClean = 0;
    int protectedChanges = 0;
    int errors = scan.errors;

    indicator.setIndeterminate(false);
    indicator.setText(CvsBundle.message("cvs.local.baseline.progress.hash"));
    for (int i = 0; i < scan.files.size(); i++) {
      indicator.checkCanceled();
      final TrackedFile tracked = scan.files.get(i);
      indicator.setText2(tracked.path.toString());
      indicator.setFraction(i / (double)scan.files.size());
      try {
        if (tracked.cachedRevision != null) {
          if (contentsEqual(tracked.path, tracked.cachedRevision)) {
            repositoryVerifiedClean++;
          }
          else {
            // Never let a user-accepted snapshot override stronger repository-backed local evidence.
            protectedChanges++;
          }
          continue;
        }

        final Fingerprint fingerprint = captureStableFingerprint(tracked.path);
        if (fingerprint == null) {
          errors++;
          continue;
        }
        replacement.put(normalize(tracked.path),
                        new BaselineEntry(tracked.revision, fingerprint.size, fingerprint.modified, fingerprint.digest));
        accepted++;
      }
      catch (IOException e) {
        errors++;
        LOG.debug("Cannot index CVS file " + tracked.path, e);
      }
    }
    indicator.setFraction(1.0);
    indicator.setText2("");

    indicator.checkCanceled();
    writeEntries(replacement);
    myEntries = immutableCopy(replacement);
    myObservations.clear();
    return new BuildResult(scan.files.size(), accepted, repositoryVerifiedClean, protectedChanges,
                           scan.specialEntries, errors);
  }

  public void clear() throws IOException {
    synchronized (myMutationLock) {
      if (myActiveMutation != null) {
        throw new IOException(CvsBundle.message("cvs.local.baseline.error.busy"));
      }
      if (myStorageFile != null) {
        Files.deleteIfExists(myStorageFile);
      }
      myEntries = Collections.emptyMap();
      myObservations.clear();
    }
  }

  private @NotNull Map<String, BaselineEntry> getEntries() {
    Map<String, BaselineEntry> entries = myEntries;
    if (entries != null) {
      return entries;
    }
    synchronized (myLoadLock) {
      entries = myEntries;
      if (entries == null) {
        entries = loadEntries();
        myEntries = entries;
      }
    }
    return entries;
  }

  private @NotNull Map<String, BaselineEntry> loadEntries() {
    if (myStorageFile == null || !Files.isRegularFile(myStorageFile, NO_FOLLOW_LINKS)) {
      return Collections.emptyMap();
    }
    try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(myStorageFile)))) {
      if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
        throw new IOException("Unsupported local CVS baseline format");
      }
      if (!myProjectBasePath.equals(input.readUTF())) {
        throw new IOException("Local CVS baseline belongs to another project");
      }
      final int count = input.readInt();
      if (count < 0 || count > MAX_ENTRY_COUNT) {
        throw new IOException("Invalid local CVS baseline entry count: " + count);
      }
      final Map<String, BaselineEntry> entries = new HashMap<>(Math.max(16, count * 4 / 3));
      for (int i = 0; i < count; i++) {
        final String path = input.readUTF();
        final String revision = input.readUTF();
        final long size = input.readLong();
        final long modified = input.readLong();
        final byte[] digest = new byte[32];
        input.readFully(digest);
        if (size < 0 || revision.isEmpty() || !isAbsolutePath(path)) {
          throw new IOException("Invalid local CVS baseline entry");
        }
        entries.put(path, new BaselineEntry(revision, size, modified, digest));
      }
      return immutableCopy(entries);
    }
    catch (IOException | RuntimeException e) {
      LOG.warn("Ignoring unreadable local CVS content baseline " + myStorageFile, e);
      return Collections.emptyMap();
    }
  }

  private static boolean isAbsolutePath(@NotNull String path) {
    try {
      return Path.of(path).isAbsolute();
    }
    catch (RuntimeException e) {
      return false;
    }
  }

  private void writeEntries(@NotNull Map<String, BaselineEntry> entries) throws IOException {
    final Path parent = Objects.requireNonNull(myStorageFile).getParent();
    Files.createDirectories(parent);
    final Path temporary = Files.createTempFile(parent, myStorageFile.getFileName().toString(), ".tmp");
    boolean moved = false;
    try {
      final List<Map.Entry<String, BaselineEntry>> sorted = new ArrayList<>(entries.entrySet());
      sorted.sort(Comparator.comparing(Map.Entry::getKey));
      try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
        output.writeInt(MAGIC);
        output.writeInt(FORMAT_VERSION);
        output.writeUTF(myProjectBasePath);
        output.writeInt(sorted.size());
        for (Map.Entry<String, BaselineEntry> item : sorted) {
          final BaselineEntry entry = item.getValue();
          output.writeUTF(item.getKey());
          output.writeUTF(entry.revision);
          output.writeLong(entry.size);
          output.writeLong(entry.modified);
          output.write(entry.digest);
        }
      }
      try {
        Files.move(temporary, myStorageFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      }
      catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, myStorageFile, StandardCopyOption.REPLACE_EXISTING);
      }
      moved = true;
    }
    finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private static @NotNull ScanResult collectTrackedFiles(@NotNull Collection<VirtualFile> roots,
                                                          @NotNull ProgressIndicator indicator) {
    final List<TrackedFile> result = new ArrayList<>();
    final Set<Path> visited = new HashSet<>();
    final Deque<Path> pending = new ArrayDeque<>();
    int errors = 0;
    int specialEntries = 0;
    for (VirtualFile root : roots) {
      try {
        final Path path = Path.of(root.getPath()).toAbsolutePath().normalize();
        if (hasCvsAdmin(path)) {
          pending.add(path);
        }
      }
      catch (RuntimeException ignored) {
        errors++;
      }
    }

    while (!pending.isEmpty()) {
      indicator.checkCanceled();
      final Path directory = pending.removeFirst();
      if (!visited.add(directory)) {
        continue;
      }
      indicator.setText2(directory.toString());
      final Path entriesPath = directory.resolve("CVS").resolve("Entries");
      final List<String> lines;
      final Fingerprint entriesFingerprint;
      try {
        final Fingerprint before = captureStableFingerprint(entriesPath);
        lines = Files.readAllLines(entriesPath, StandardCharsets.ISO_8859_1);
        entriesFingerprint = captureStableFingerprint(entriesPath);
        if (before == null || entriesFingerprint == null || !fingerprintsEqual(before, entriesFingerprint)) {
          errors++;
          continue;
        }
      }
      catch (IOException e) {
        errors++;
        continue;
      }

      for (String line : lines) {
        indicator.checkCanceled();
        final Entry entry;
        try {
          entry = Entry.createEntryForLine(line);
        }
        catch (RuntimeException e) {
          errors++;
          continue;
        }
        final String name = entry.getFileName();
        if (name == null || name.isEmpty()) {
          continue;
        }
        final Path child = directory.resolve(name).normalize();
        if (!directory.equals(child.getParent())) {
          errors++;
          continue;
        }
        if (entry.isDirectory()) {
          if (hasCvsAdmin(child)) {
            pending.addLast(child);
          }
          continue;
        }
        if (entry.isAddedFile() || entry.isRemoved() || entry.isResultOfMerge() || entry.getLastModified() == null ||
            entry.getRevision() == null || entry.getRevision().isEmpty()) {
          specialEntries++;
          continue;
        }
        final BasicFileAttributes attributes;
        try {
          attributes = Files.readAttributes(child, BasicFileAttributes.class, NO_FOLLOW_LINKS);
        }
        catch (IOException e) {
          errors++;
          continue;
        }
        if (!attributes.isRegularFile()) {
          errors++;
          continue;
        }
        if (CvsStatusProvider.timeStampsAreEqual(entry.getLastModified().getTime(),
                                                attributes.lastModifiedTime().toMillis())) {
          continue;
        }
        final Path cachedRevision = getCachedRevision(directory, name, entry.getRevision());
        result.add(new TrackedFile(child, entry.getRevision(), cachedRevision,
                                   attributes.size(), attributes.lastModifiedTime().toMillis(),
                                   entriesPath, entriesFingerprint));
      }
    }
    return new ScanResult(result, specialEntries, errors);
  }

  private static Path getCachedRevision(@NotNull Path directory, @NotNull String name, @NotNull String revision) {
    final String normalizedRevision = revision.startsWith("-") ? revision.substring(1) : revision;
    final Path candidate = directory.resolve("CVS").resolve("BaseRevisions")
      .resolve(".#" + name + '.' + normalizedRevision);
    return Files.isRegularFile(candidate, NO_FOLLOW_LINKS) ? candidate : null;
  }

  private static boolean hasCvsAdmin(@NotNull Path directory) {
    final Path admin = directory.resolve("CVS");
    return Files.isRegularFile(admin.resolve("Entries"), NO_FOLLOW_LINKS) &&
           Files.isRegularFile(admin.resolve("Root"), NO_FOLLOW_LINKS) &&
           Files.isRegularFile(admin.resolve("Repository"), NO_FOLLOW_LINKS);
  }

  private static boolean contentsEqual(@NotNull Path first, @NotNull Path second) throws IOException {
    if (Files.size(first) != Files.size(second)) {
      return false;
    }
    try (InputStream left = new BufferedInputStream(Files.newInputStream(first), BUFFER_SIZE);
         InputStream right = new BufferedInputStream(Files.newInputStream(second), BUFFER_SIZE)) {
      final byte[] leftBuffer = new byte[BUFFER_SIZE];
      final byte[] rightBuffer = new byte[BUFFER_SIZE];
      while (true) {
        ProgressManager.checkCanceled();
        final int leftRead = left.read(leftBuffer);
        final int rightRead = right.read(rightBuffer);
        if (leftRead != rightRead) {
          return false;
        }
        if (leftRead < 0) {
          return true;
        }
        if (!Arrays.equals(leftBuffer, 0, leftRead, rightBuffer, 0, rightRead)) {
          return false;
        }
      }
    }
  }

  private static boolean fingerprintsEqual(@NotNull Fingerprint first, @NotNull Fingerprint second) {
    return first.size == second.size && first.modified == second.modified &&
           MessageDigest.isEqual(first.digest, second.digest);
  }

  private static Fingerprint captureStableFingerprint(@NotNull Path path) throws IOException {
    for (int attempt = 0; attempt < 2; attempt++) {
      final BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW_LINKS);
      if (!before.isRegularFile()) {
        return null;
      }
      final MessageDigest digest = newSha256();
      try (InputStream input = new BufferedInputStream(Files.newInputStream(path), BUFFER_SIZE)) {
        final byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          ProgressManager.checkCanceled();
          if (read > 0) {
            digest.update(buffer, 0, read);
          }
        }
      }
      final BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW_LINKS);
      if (before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())) {
        return new Fingerprint(after.size(), after.lastModifiedTime().toMillis(), digest.digest());
      }
    }
    return null;
  }

  private static @NotNull Map<String, BaselineEntry> immutableCopy(@NotNull Map<String, BaselineEntry> entries) {
    return Collections.unmodifiableMap(new HashMap<>(entries));
  }

  private static @NotNull String normalize(@NotNull Path path) {
    return path.toAbsolutePath().normalize().toString();
  }

  private static @NotNull String sha256Hex(@NotNull String text) {
    final byte[] digest = newSha256().digest(text.getBytes(StandardCharsets.UTF_8));
    final StringBuilder result = new StringBuilder(digest.length * 2);
    for (byte value : digest) {
      result.append(Character.forDigit((value >>> 4) & 0xf, 16));
      result.append(Character.forDigit(value & 0xf, 16));
    }
    return result.toString();
  }

  private static @NotNull MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    }
    catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static final class ScanResult {
    private final List<TrackedFile> files;
    private final int specialEntries;
    private final int errors;

    private ScanResult(List<TrackedFile> files, int specialEntries, int errors) {
      this.files = files;
      this.specialEntries = specialEntries;
      this.errors = errors;
    }
  }

  private static final class TrackedFile {
    private final Path path;
    private final String revision;
    private final Path cachedRevision;
    private final long size;
    private final long modified;
    private final Path entriesPath;
    private final Fingerprint entriesFingerprint;

    private TrackedFile(Path path, String revision, Path cachedRevision, long size, long modified,
                        Path entriesPath, Fingerprint entriesFingerprint) {
      this.path = path;
      this.revision = revision;
      this.cachedRevision = cachedRevision;
      this.size = size;
      this.modified = modified;
      this.entriesPath = entriesPath;
      this.entriesFingerprint = entriesFingerprint;
    }
  }

  private static final class BaselineEntry {
    private final String revision;
    private final long size;
    private final long modified;
    private final byte[] digest;

    private BaselineEntry(String revision, long size, long modified, byte[] digest) {
      this.revision = revision;
      this.size = size;
      this.modified = modified;
      this.digest = digest;
    }
  }

  private static final class Fingerprint {
    private final long size;
    private final long modified;
    private final byte[] digest;

    private Fingerprint(long size, long modified, byte[] digest) {
      this.size = size;
      this.modified = modified;
      this.digest = digest;
    }
  }

  private static final class ObservedFingerprint {
    private final long size;
    private final long modified;
    private final boolean matches;

    private ObservedFingerprint(long size, long modified, boolean matches) {
      this.size = size;
      this.modified = modified;
      this.matches = matches;
    }
  }

  public static final class BuildResult {
    private final int myTrackedFiles;
    private final int myAcceptedFiles;
    private final int myRepositoryVerifiedCleanFiles;
    private final int myProtectedChanges;
    private final int mySpecialEntries;
    private final int myErrors;

    private BuildResult(int trackedFiles, int acceptedFiles, int repositoryVerifiedCleanFiles,
                        int protectedChanges, int specialEntries, int errors) {
      myTrackedFiles = trackedFiles;
      myAcceptedFiles = acceptedFiles;
      myRepositoryVerifiedCleanFiles = repositoryVerifiedCleanFiles;
      myProtectedChanges = protectedChanges;
      mySpecialEntries = specialEntries;
      myErrors = errors;
    }

    public int getTrackedFiles() {
      return myTrackedFiles;
    }

    public int getAcceptedFiles() {
      return myAcceptedFiles;
    }

    public int getRepositoryVerifiedCleanFiles() {
      return myRepositoryVerifiedCleanFiles;
    }

    public int getProtectedChanges() {
      return myProtectedChanges;
    }

    public int getSpecialEntries() {
      return mySpecialEntries;
    }

    public int getErrors() {
      return myErrors;
    }
  }

  public static final class RepositoryVerificationSnapshot {
    private final Object myMutation;
    private final List<TrackedFile> myCandidates;
    private final List<String> myCandidatePaths;
    private final int mySpecialEntries;
    private final int myScanErrors;

    private RepositoryVerificationSnapshot(Object mutation, List<TrackedFile> candidates,
                                           int specialEntries, int scanErrors) {
      myMutation = mutation;
      myCandidates = List.copyOf(candidates);
      final List<String> paths = new ArrayList<>(candidates.size());
      for (TrackedFile candidate : candidates) {
        paths.add(normalize(candidate.path));
      }
      myCandidatePaths = List.copyOf(paths);
      mySpecialEntries = specialEntries;
      myScanErrors = scanErrors;
    }

    public @NotNull List<String> getCandidatePaths() {
      return myCandidatePaths;
    }
  }

  public static final class RepositoryVerificationResult {
    private final int myCandidates;
    private final int myVerifiedClean;
    private final int myReportedOrUnverified;
    private final int mySpecialEntries;
    private final int myScanErrors;

    private RepositoryVerificationResult(int candidates, int verifiedClean, int reportedOrUnverified,
                                         int specialEntries, int scanErrors) {
      myCandidates = candidates;
      myVerifiedClean = verifiedClean;
      myReportedOrUnverified = reportedOrUnverified;
      mySpecialEntries = specialEntries;
      myScanErrors = scanErrors;
    }

    public int getCandidates() {
      return myCandidates;
    }

    public int getVerifiedClean() {
      return myVerifiedClean;
    }

    public int getReportedOrUnverified() {
      return myReportedOrUnverified;
    }

    public int getSpecialEntries() {
      return mySpecialEntries;
    }

    public int getScanErrors() {
      return myScanErrors;
    }
  }
}
