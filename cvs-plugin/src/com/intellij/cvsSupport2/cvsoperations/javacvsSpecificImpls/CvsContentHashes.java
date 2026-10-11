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

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.progress.ProgressManager;

import java.nio.file.Path;

/** Owns the cache location while keeping the cache itself independent from the IDE runtime. */
public final class CvsContentHashes {
  private CvsContentHashes() { }

  private static final class Holder {
    private static final ContentHashCache CACHE = new ContentHashCache(
      Path.of(PathManager.getSystemPath(), "opencvs", "content-hashes-v1"), ProgressManager::checkCanceled);
  }

  public static ContentHashCache getInstance() {
    return Holder.CACHE;
  }
}
