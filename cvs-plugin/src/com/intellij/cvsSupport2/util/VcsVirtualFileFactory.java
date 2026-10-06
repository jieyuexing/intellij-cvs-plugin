/*
 * Copyright 2000-2026 JetBrains s.r.o.
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
package com.intellij.cvsSupport2.util;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.vfs.VcsVirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * Creates VCS-backed virtual files across the constructor change between IDE 232 and 262.
 */
public final class VcsVirtualFileFactory {
  private interface Factory {
    VcsVirtualFile create(FilePath path, VcsFileRevision revision);
  }

  private static final Factory FACTORY = createFactory();

  private VcsVirtualFileFactory() {
  }

  public static VcsVirtualFile create(FilePath path, VcsFileRevision revision) {
    return FACTORY.create(path, revision);
  }

  private static Factory createFactory() {
    try {
      Constructor<VcsVirtualFile> constructor =
        VcsVirtualFile.class.getConstructor(FilePath.class, VcsFileRevision.class);
      return (path, revision) -> newInstance(constructor, path, revision);
    }
    catch (NoSuchMethodException ignored) {
      try {
        Constructor<VcsVirtualFile> constructor =
          VcsVirtualFile.class.getConstructor(String.class, VcsFileRevision.class, VirtualFileSystem.class);
        return (path, revision) -> newInstance(constructor, path.getPath(), revision, getLegacyFileSystem());
      }
      catch (NoSuchMethodException exception) {
        throw new ExceptionInInitializerError(exception);
      }
    }
  }

  private static VirtualFileSystem getLegacyFileSystem() {
    try {
      return (VirtualFileSystem)Class.forName("com.intellij.openapi.vcs.vfs.VcsFileSystem")
        .getMethod("getInstance")
        .invoke(null);
    }
    catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
      throw new IllegalStateException("Cannot access the legacy CVS virtual file system", exception);
    }
    catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("Cannot access the legacy CVS virtual file system", cause);
    }
  }

  private static VcsVirtualFile newInstance(Constructor<VcsVirtualFile> constructor, Object... arguments) {
    try {
      return constructor.newInstance(arguments);
    }
    catch (InstantiationException | IllegalAccessException exception) {
      throw new IllegalStateException("Cannot create a CVS virtual file", exception);
    }
    catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("Cannot create a CVS virtual file", cause);
    }
  }
}
