package com.intellij.cvsSupport2.cvsoperations.common;

import com.intellij.cvsSupport2.CvsVcs2;
import com.intellij.openapi.vcs.VcsKey;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/** 使用磁盘标记，兼容尚未将 CVS 管理目录加载到 VFS 的 IDEA 232。 */
public final class CvsRootChecker extends com.intellij.openapi.vcs.VcsRootChecker {
  @Override
  public @NotNull VcsKey getSupportedVcs() {
    return CvsVcs2.getKey();
  }

  @Override
  public boolean isRoot(@NotNull VirtualFile file) {
    return file.isDirectory() && FindAllRootsHelper.isCvsWorkingCopyRoot(Path.of(file.getPath()));
  }

  @Override
  public boolean isVcsDir(@NotNull String name) {
    return "CVS".equals(name);
  }

  @Override
  public boolean areChildrenValidMappings() {
    return true;
  }
}
