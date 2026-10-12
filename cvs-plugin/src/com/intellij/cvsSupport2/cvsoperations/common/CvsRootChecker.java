package com.intellij.cvsSupport2.cvsoperations.common;

import com.intellij.cvsSupport2.CvsVcs2;
import com.intellij.openapi.vcs.VcsKey;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;

/** 使用磁盘标记，兼容尚未将 CVS 管理目录加载到 VFS 的 IDEA 232。 */
public final class CvsRootChecker extends com.intellij.openapi.vcs.VcsRootChecker {
  @Override
  public @NotNull VcsKey getSupportedVcs() {
    return CvsVcs2.getKey();
  }

  @Override
  public boolean isRoot(@NotNull VirtualFile file) {
    Path directory = Path.of(file.getPath());
    if (!file.isDirectory() || !FindAllRootsHelper.isCvsWorkingCopyRoot(directory)) return false;
    try {
      return !FindAllRootsHelper.isTrackedChild(directory);
    }
    catch (IOException | SecurityException e) {
      // 无法证明归属父根时保留根资格，避免暂时读失败使工作副本失去 VCS 管理。
      return true;
    }
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
