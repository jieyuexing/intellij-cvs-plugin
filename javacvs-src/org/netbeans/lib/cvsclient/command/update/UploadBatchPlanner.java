package org.netbeans.lib.cvsclient.command.update;

import java.util.ArrayList;
import java.util.List;

/** Stable, bounded explicit-file batches; a single oversized file stands alone. */
public final class UploadBatchPlanner {
  private UploadBatchPlanner() {}

  public record Upload<T>(T file, long bytes) {
    public Upload {
      if (bytes < 0) throw new IllegalArgumentException("Negative upload size");
    }
  }

  public static <T> List<List<Upload<T>>> plan(List<Upload<T>> uploads, long limit) {
    if (limit <= 0) return List.of();
    long total = 0;
    for (Upload<T> upload : uploads) {
      total = upload.bytes() > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + upload.bytes();
    }
    if (total <= limit) return List.of();
    List<List<Upload<T>>> batches = new ArrayList<>();
    List<Upload<T>> current = new ArrayList<>();
    long bytes = 0;
    for (Upload<T> upload : uploads) {
      if (!current.isEmpty() && (bytes >= limit || upload.bytes() > limit - bytes)) {
        batches.add(List.copyOf(current));
        current.clear();
        bytes = 0;
      }
      current.add(upload);
      bytes += upload.bytes();
    }
    if (!current.isEmpty()) batches.add(List.copyOf(current));
    return List.copyOf(batches);
  }
}
