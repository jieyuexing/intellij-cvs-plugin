// Copyright 2000-2026 JetBrains s.r.o. and contributors. Apache-2.0.
package com.intellij.cvsSupport2.util;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * checkout 单线程使用的两段有界 LRU，保留原 SLRU 的晋升、降级与淘汰回调。
 * 新值先入试用段，再次命中晋升保护段；保护段溢出时降回试用段。
 */
public abstract class CvsSegmentedLruCache<K, V> {
  private static final int FACTOR = Integer.getInteger("idea.slru.factor", 1);
  private final LinkedHashMap<K, V> protectedQueue;
  private final LinkedHashMap<K, V> probationQueue;

  protected CvsSegmentedLruCache(int protectedSize, int probationSize) {
    probationQueue = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        if (size() <= probationSize * FACTOR) return false;
        // 先写回再移除：异常时保留条目，与原实现一致。
        onDropFromCache(eldest.getKey(), eldest.getValue());
        return true;
      }
    };
    protectedQueue = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        if (size() <= protectedSize * FACTOR) return false;
        probationQueue.put(eldest.getKey(), eldest.getValue());
        return true;
      }
    };
  }

  public V get(K key) {
    V value = protectedQueue.get(key);
    if (value != null) return value;
    value = probationQueue.remove(key);
    if (value != null) {
      protectedQueue.put(key, value);
      return value;
    }
    value = createValue(key);
    probationQueue.put(key, value);
    return value;
  }

  public abstract V createValue(K key);

  protected void onDropFromCache(K key, V value) { }

  public Set<Map.Entry<K, V>> entrySet() {
    Set<Map.Entry<K, V>> result = new HashSet<>(protectedQueue.entrySet());
    result.addAll(probationQueue.entrySet());
    return result;
  }
}
