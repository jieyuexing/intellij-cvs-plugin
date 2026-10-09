// Copyright 2000-2026 JetBrains s.r.o. and contributors. Apache-2.0.
package com.intellij.cvsSupport2.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

/** 保留 ChangesUtil 的文件优先、锁外刷新、锁内逐级回退行为。 */
public final class CvsValidParent {
  private CvsValidParent() { }

  public static VirtualFile find(FilePath path) {
    VirtualFile result = path.getVirtualFile();
    if (result == null && !ApplicationManager.getApplication().isReadAccessAllowed()) {
      result = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.getPath());
    }
    if (result != null) return result;
    return ReadAction.compute(() -> {
      FilePath parent = path;
      LocalFileSystem fileSystem = LocalFileSystem.getInstance();
      while (parent != null) {
        VirtualFile file = fileSystem.findFileByPath(parent.getPath());
        if (file != null) return file;
        parent = parent.getParentPath();
      }
      return null;
    });
  }
}
