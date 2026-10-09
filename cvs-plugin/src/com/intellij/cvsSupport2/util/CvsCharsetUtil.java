/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors.
 * Licensed under the Apache License, Version 2.0.
 * 编码判定保留上游 CharsetToolkit 的历史语义，不依赖平台内部 API。
 */
package com.intellij.cvsSupport2.util;

import com.intellij.openapi.application.ApplicationInfo;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class CvsCharsetUtil {
  // 官方稳定版 252（含补丁版本）仍使用旧判定，253 起容忍截断序列。
  private static final boolean TOLERATE_TRUNCATED =
    ApplicationInfo.getInstance().getBuild().getBaselineVersion() >= 253;

  private CvsCharsetUtil() { }

  public static String bytesToString(byte[] bytes, Charset fallback) {
    if (bytes.length == 0) return "";
    Charset charset;
    int offset;
    if (startsWith(bytes, 0xef, 0xbb, 0xbf)) {
      charset = StandardCharsets.UTF_8;
      offset = 3;
    }
    else if (startsWith(bytes, 0, 0, 0xfe, 0xff)) {
      charset = Charset.forName("UTF-32BE");
      offset = 4;
    }
    else if (startsWith(bytes, 0xff, 0xfe, 0, 0)) {
      charset = Charset.forName("UTF-32LE");
      offset = 4;
    }
    else if (startsWith(bytes, 0xff, 0xfe)) {
      charset = StandardCharsets.UTF_16LE;
      offset = 2;
    }
    else if (startsWith(bytes, 0xfe, 0xff)) {
      charset = StandardCharsets.UTF_16BE;
      offset = 2;
    }
    else {
      charset = guessWithoutBom(bytes, fallback);
      offset = 0;
    }
    return charset.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
  }

  private static boolean startsWith(byte[] bytes, int... prefix) {
    if (bytes.length < prefix.length) return false;
    for (int i = 0; i < prefix.length; i++) {
      if ((bytes[i] & 0xff) != prefix[i]) return false;
    }
    return true;
  }

  private static Charset guessWithoutBom(byte[] bytes, Charset fallback) {
    boolean high = false;
    boolean valid = true;
    for (int i = 0; i < bytes.length; i++) {
      int b = bytes[i] & 0xff;
      if (b < 9) return fallback;
      if (b < 128) continue;
      high = true;
      int length = b >= 0xc0 && b <= 0xdf ? 2 : b >= 0xe0 && b <= 0xef ? 3 :
                   b >= 0xf0 && b <= 0xf7 ? 4 : b >= 0xf8 && b <= 0xfb ? 5 :
                   b >= 0xfc && b <= 0xfd ? 6 : 0;
      if (length == 0) {
        valid = false;
        continue;
      }
      // 上游对截断序列保留 UTF-8 猜测；解码器随后产生替代字符。
      if (i + length > bytes.length) {
        if (TOLERATE_TRUNCATED) break;
        return fallback;
      }
      boolean continuation = true;
      for (int j = 1; j < length; j++) {
        if ((bytes[i + j] & 0xc0) != 0x80) continuation = false;
      }
      if (continuation) i += length - 1;
      else valid = false;
    }
    return !high ? StandardCharsets.US_ASCII : valid ? StandardCharsets.UTF_8 : fallback;
  }
}
