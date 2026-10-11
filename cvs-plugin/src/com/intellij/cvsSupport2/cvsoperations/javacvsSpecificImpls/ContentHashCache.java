/*
 * Copyright 2026 Jieyuexing
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.intellij.cvsSupport2.cvsoperations.javacvsSpecificImpls;

import org.netbeans.lib.cvsclient.admin.Entry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A disposable, bounded cache of content known to match a normal CVS entry. */
public final class ContentHashCache {
  private static final int FORMAT = 1;
  private static final int MAX_ENTRIES = 10000;
  private static final long MAX_DISK_BYTES = 16L * 1024 * 1024;

  private final Path myStorage;
  private final int myCapacity;
  private final Runnable myCheckCanceled;
  private final Map<String, Value> myValues = new LinkedHashMap<>(16, .75f, true);
  private boolean myDirty;

  private record Value(String revision, String timestamp, long size, byte[] hash) { }
  private record Fingerprint(long size, byte[] hash) { }

  public ContentHashCache(Path storage, Runnable checkCanceled) {
    this(storage, MAX_ENTRIES, checkCanceled);
  }

  ContentHashCache(Path storage, int capacity, Runnable checkCanceled) {
    myStorage = storage;
    myCapacity = capacity;
    myCheckCanceled = checkCanceled;
    load();
  }

  private static boolean isEligible(Entry entry) {
    return entry != null && !entry.isDirectory() && !entry.isAddedFile() && !entry.isRemoved() &&
           !entry.isConflict() && !entry.isResultOfMerge() && entry.getLastModified() != null &&
           entry.getRevision() != null && !entry.getRevision().isEmpty();
  }

  private static String key(File file) {
    return file.toPath().toAbsolutePath().normalize().toString();
  }

  private static String timestamp(Entry entry) {
    return entry.getConflictStringWithoutConflict();
  }

  private static boolean sameEntry(Value value, String revision, String timestamp) {
    return value != null && value.revision.equals(revision) && value.timestamp.equals(timestamp);
  }

  /** Seeds an absent key only after an ordinary timestamp comparison succeeds. */
  public synchronized void seed(File file, Entry entry) {
    if (isEligible(entry) && !sameEntry(myValues.get(key(file)), entry.getRevision(), timestamp(entry))) {
      record(file, entry);
    }
  }

  /** Called after a normal Entries write; merge, add, remove, and conflict entries invalidate old data. */
  public synchronized void record(File file, Entry entry) {
    invalidate(file);
    if (!isEligible(entry)) return;
    try {
      Fingerprint fingerprint = fingerprint(file.toPath());
      if (fingerprint == null) return;
      myValues.put(key(file), new Value(entry.getRevision(), timestamp(entry), fingerprint.size, fingerprint.hash));
      while (myValues.size() > myCapacity) {
        Iterator<String> iterator = myValues.keySet().iterator();
        iterator.next();
        iterator.remove();
      }
      myDirty = true;
    }
    catch (IOException | SecurityException ignored) { }
  }

  /** Exact revision, Entries timestamp, length, and SHA-256 match. */
  public synchronized boolean matches(File file, Entry entry) {
    return isEligible(entry) && matches(file, entry.getRevision(), timestamp(entry));
  }

  private boolean matches(File file, String revision, String timestamp) {
    if (revision == null || revision.isEmpty() || timestamp == null) return false;
    Value value = myValues.get(key(file));
    if (!sameEntry(value, revision, timestamp)) return false;
    try {
      if (Files.size(file.toPath()) != value.size) return false;
      Fingerprint fingerprint = fingerprint(file.toPath());
      return fingerprint != null && fingerprint.size == value.size && Arrays.equals(fingerprint.hash, value.hash);
    }
    catch (IOException | SecurityException ignored) {
      return false;
    }
  }

  public synchronized void invalidate(File file) {
    if (myValues.remove(key(file)) != null) myDirty = true;
  }

  private Fingerprint fingerprint(Path file) throws IOException {
    BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile()) return null;
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    }
    catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      int count;
      while ((count = input.read(buffer)) != -1) {
        myCheckCanceled.run();
        digest.update(buffer, 0, count);
      }
    }
    BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) ||
        !Objects.equals(before.fileKey(), after.fileKey())) return null;
    return new Fingerprint(after.size(), digest.digest());
  }

  private void load() {
    try {
      if (Files.size(myStorage) > MAX_DISK_BYTES) return;
      try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(myStorage)))) {
        if (input.readInt() != FORMAT) return;
        int count = input.readInt();
        if (count < 0 || count > myCapacity) return;
        for (int i = 0; i < count; i++) {
          String path = input.readUTF();
          String revision = input.readUTF();
          String timestamp = input.readUTF();
          long size = input.readLong();
          byte[] hash = new byte[32];
          input.readFully(hash);
          if (size < 0) throw new IOException("Invalid length");
          myValues.put(path, new Value(revision, timestamp, size, hash));
        }
        if (input.read() != -1) throw new IOException("Trailing data");
      }
    }
    catch (IOException | SecurityException ignored) {
      myValues.clear();
    }
  }

  /** Called once after a command. Failure is deliberately invisible to CVS. */
  public synchronized void flush() {
    if (!myDirty) return;
    Path temporary = null;
    try {
      Files.createDirectories(myStorage.getParent());
      temporary = Files.createTempFile(myStorage.getParent(), "hash-", ".tmp");
      try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
        output.writeInt(FORMAT);
        output.writeInt(myValues.size());
        for (Map.Entry<String, Value> item : myValues.entrySet()) {
          Value value = item.getValue();
          output.writeUTF(item.getKey());
          output.writeUTF(value.revision);
          output.writeUTF(value.timestamp);
          output.writeLong(value.size);
          output.write(value.hash);
        }
      }
      if (Files.size(temporary) > MAX_DISK_BYTES) return;
      try {
        Files.move(temporary, myStorage, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      }
      catch (AtomicMoveNotSupportedException ignored) {
        // Keeping the previous complete cache is safer than a non-atomic replacement.
        return;
      }
      myDirty = false;
    }
    catch (IOException | SecurityException ignored) { }
    finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        }
        catch (IOException | SecurityException ignored) { }
      }
    }
  }
}
