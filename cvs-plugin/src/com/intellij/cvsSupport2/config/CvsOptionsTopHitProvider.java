// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.cvsSupport2.config;

import com.intellij.CvsBundle;
import com.intellij.cvsSupport2.CvsVcs2;
import com.intellij.ide.ui.search.BooleanOptionDescription;
import com.intellij.ide.ui.search.OptionDescription;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.configurable.VcsOptionsTopHitProviderBase;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

public final class CvsOptionsTopHitProvider extends VcsOptionsTopHitProviderBase {
  private static final String CONFIGURABLE_ID = "editor.preferences.tabs";

  @Override
  public @NotNull String getId() {
    return "vcs";
  }

  @Override
  public @NotNull Collection<OptionDescription> getOptions(@NotNull Project project) {
    if (!isEnabled(project, CvsVcs2.getKey())) {
      return List.of();
    }

    final CvsConfiguration configuration = CvsConfiguration.getInstance(project);
    final String groupName = CvsBundle.message("general.cvs.display.name");
    return List.of(
      createOption(
        groupName,
        CvsBundle.message("checkbox.use.read.only.flag.for.not.edited.files"),
        () -> configuration.MAKE_NEW_FILES_READONLY,
        enabled -> configuration.MAKE_NEW_FILES_READONLY = enabled
      ),
      createOption(
        groupName,
        CvsBundle.message("checkbox.show.cvs.server.output"),
        () -> configuration.SHOW_OUTPUT,
        enabled -> configuration.SHOW_OUTPUT = enabled
      )
    );
  }

  private static BooleanOptionDescription createOption(String groupName,
                                                       String optionName,
                                                       BooleanGetter getter,
                                                       BooleanSetter setter) {
    final String option = groupName.trim().replaceFirst(":$", "") + ": " + optionName;
    return new BooleanOptionDescription(option, CONFIGURABLE_ID) {
      @Override
      public boolean isOptionEnabled() {
        return getter.get();
      }

      @Override
      public void setOptionState(boolean enabled) {
        setter.set(enabled);
      }
    };
  }

  @FunctionalInterface
  private interface BooleanGetter {
    boolean get();
  }

  @FunctionalInterface
  private interface BooleanSetter {
    void set(boolean value);
  }
}
