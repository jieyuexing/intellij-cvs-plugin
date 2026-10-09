// Copyright 2000-2026 JetBrains s.r.o. and contributors. Apache-2.0.
package com.intellij.cvsSupport2.util;

import com.intellij.openapi.application.PathMacros;

import java.util.HashMap;
import java.util.Map;

/** 通过公开宏服务保留文件选择器的 $NAME$ 映射语义。 */
public final class CvsPathMacros {
  private CvsPathMacros() { }

  public static Map<String, String> getMacroMap() {
    PathMacros macros = PathMacros.getInstance();
    Map<String, String> result = new HashMap<>();
    for (String name : macros.getAllMacroNames()) {
      result.put('$' + name + '$', macros.getValue(name));
    }
    return result;
  }
}
