package com.intellij.cvsSupport2.cvsoperations.common;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 仅保存本项目映射迁移的前后路径，用于人工回退；不保存 CVS 连接或认证材料。 */
@State(name = "CvsRootMappingHistory", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class CvsRootMappingHistory implements PersistentStateComponent<CvsRootMappingHistory.History> {
  public static final class Mapping {
    public String directory = "";
    public String vcs = "";
    public Mapping() { }
    Mapping(VcsDirectoryMapping mapping) {
      directory = mapping.getDirectory();
      vcs = mapping.getVcs();
    }
  }
  public static final class Migration {
    public List<Mapping> before = new ArrayList<>();
    public List<Mapping> after = new ArrayList<>();
  }
  public static final class History {
    public List<Migration> migrations = new ArrayList<>();
  }
  private History myState = new History();

  public void record(List<VcsDirectoryMapping> before, List<VcsDirectoryMapping> after) {
    Migration migration = new Migration();
    before.forEach(mapping -> migration.before.add(new Mapping(mapping)));
    after.forEach(mapping -> migration.after.add(new Mapping(mapping)));
    myState.migrations.add(migration);
  }

  @Override
  public @NotNull History getState() { return myState; }

  @Override
  public void loadState(@NotNull History state) { myState = state; }
}
