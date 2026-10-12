package com.intellij.cvsSupport2.cvsoperations.common;

import com.intellij.openapi.vcs.VcsDirectoryMapping;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 后台扫描的映射收敛与展开计划；显式的其他 VCS / none 边界始终优先。 */
public final class CvsRootMappings {
  private CvsRootMappings() { }

  public static List<VcsDirectoryMapping> expand(List<VcsDirectoryMapping> original,
                                                 Collection<Path> containers, Collection<Path> roots) {
    List<VcsDirectoryMapping> retained = collapse(original);
    Collection<Path> retainedContainers = retainContainers(original, retained, containers);
    Collection<Path> retainedRoots = retainContainers(original, retained, roots);
    original = retained;
    Map<VcsDirectoryMapping, List<Path>> owned = new LinkedHashMap<>();
    for (Path root : retainedRoots.stream().sorted(Comparator.comparing(Path::toString)).toList()) {
      if (!FindAllRootsHelper.isAtOrUnderAnyRoot(root, retainedContainers) || isTrackedUnderCvs(retained, root)) continue;
      VcsDirectoryMapping owner = owner(original, root);
      if (owner != null && "CVS".equals(owner.getVcs())) {
        owned.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(root);
      }
    }
    List<VcsDirectoryMapping> result = new ArrayList<>();
    for (VcsDirectoryMapping mapping : original) {
      List<Path> found = owned.get(mapping);
      // 空结果保留配置，允许以后重新扫描；默认映射保留平台自动发现语义。
      if (found == null || mapping.isDefaultMapping()) result.add(mapping);
      if (found != null) {
        for (Path root : found) {
          VcsDirectoryMapping expanded = new VcsDirectoryMapping(root.toString().replace('\\', '/'),
                                                                  mapping.getVcs(), mapping.getRootSettings());
          if (!result.contains(expanded)) result.add(expanded);
        }
      }
    }
    return List.copyOf(result);
  }

  /** 只删除仍由父目录的 CVS owner 接管的受管子目录；独立 checkout 和显式边界保留。 */
  static List<VcsDirectoryMapping> collapse(List<VcsDirectoryMapping> mappings) {
    List<VcsDirectoryMapping> result = new ArrayList<>();
    for (VcsDirectoryMapping mapping : mappings) {
      boolean redundant = false;
      if (!mapping.isDefaultMapping() && "CVS".equals(mapping.getVcs())) {
        Path directory = Path.of(mapping.getDirectory()).toAbsolutePath().normalize();
        redundant = isTrackedUnderCvs(mappings, directory);
      }
      if (!redundant) result.add(mapping);
    }
    return List.copyOf(result);
  }

  private static boolean isTrackedUnderCvs(List<VcsDirectoryMapping> mappings, Path directory) {
    VcsDirectoryMapping parentOwner = directory.getParent() == null ? null : owner(mappings, directory.getParent());
    if (parentOwner != null && "CVS".equals(parentOwner.getVcs())) {
      try {
        return FindAllRootsHelper.isCvsWorkingCopyRoot(directory) && FindAllRootsHelper.isTrackedChild(directory);
      }
      catch (IOException | SecurityException e) {
        // 未能证明可由父映射接管，保留配置；完整扫描仍负责阻止发布不完整结果。
      }
    }
    return false;
  }

  /** 被收敛的显式路径不再享有扫描 container 的强制根资格。 */
  static List<Path> retainContainers(List<VcsDirectoryMapping> original, List<VcsDirectoryMapping> retained,
                                     Collection<Path> paths) {
    List<Path> removed = original.stream()
      .filter(mapping -> !mapping.isDefaultMapping() && !retained.contains(mapping))
      .map(mapping -> Path.of(mapping.getDirectory()).toAbsolutePath().normalize()).toList();
    return paths.stream().filter(path -> !removed.contains(path.toAbsolutePath().normalize())).toList();
  }

  public static VcsDirectoryMapping owner(List<VcsDirectoryMapping> mappings, Path path) {
    VcsDirectoryMapping result = null;
    int depth = -1;
    for (VcsDirectoryMapping mapping : mappings) {
      if (mapping.isDefaultMapping()) {
        if (result == null) result = mapping;
      }
      else {
        Path directory = Path.of(mapping.getDirectory()).toAbsolutePath().normalize();
        if (path.startsWith(directory) && directory.getNameCount() > depth) {
          result = mapping;
          depth = directory.getNameCount();
        }
      }
    }
    return result;
  }
}
