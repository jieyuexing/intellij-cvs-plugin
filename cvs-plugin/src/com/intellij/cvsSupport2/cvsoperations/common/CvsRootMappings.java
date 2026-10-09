package com.intellij.cvsSupport2.cvsoperations.common;

import com.intellij.openapi.vcs.VcsDirectoryMapping;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 完整扫描后的纯映射计划；显式的其他 VCS / none 边界始终优先。 */
public final class CvsRootMappings {
  private CvsRootMappings() { }

  public static List<VcsDirectoryMapping> expand(List<VcsDirectoryMapping> original,
                                                 Collection<Path> containers, Collection<Path> roots) {
    Map<VcsDirectoryMapping, List<Path>> owned = new LinkedHashMap<>();
    for (Path root : roots.stream().sorted(Comparator.comparing(Path::toString)).toList()) {
      if (!FindAllRootsHelper.isAtOrUnderAnyRoot(root, containers)) continue;
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
