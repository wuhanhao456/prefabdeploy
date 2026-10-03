package io.github.prefabdeploy.server;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;

/** Checksum + force + atomic rename: a torn journal is detected, never interpreted as success. */
public final class AtomicFile {
  public static void write(Path target, byte[] payload) throws IOException {
    byte[] hash = digest(payload);
    var bytes = new byte[hash.length + payload.length];
    System.arraycopy(hash, 0, bytes, 0, hash.length);
    System.arraycopy(payload, 0, bytes, hash.length, payload.length);
    writeRaw(target, bytes);
  }

  public static void writeRaw(Path target, byte[] bytes) throws IOException {
    Files.createDirectories(target.getParent());
    Path tmp = target.resolveSibling(target.getFileName() + ".prefab.tmp");
    try (var c =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      writeAll(c, ByteBuffer.wrap(bytes));
      c.force(true);
    }
    for (int attempt = 0; ; attempt++)
      try {
        Files.move(
            tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        break;
      } catch (AccessDeniedException ex) {
        if (attempt >= 40) throw ex;
        try {
          Thread.sleep(5);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("Atomic replacement interrupted", interrupted);
        }
      } catch (AtomicMoveNotSupportedException ex) {
        throw new IOException("Atomic journal replacement is required on this filesystem", ex);
      }
  }

  private static void writeAll(FileChannel c, ByteBuffer b) throws IOException {
    while (b.hasRemaining()) c.write(b);
  }

  public static byte[] read(Path path, int maxBytes) throws IOException {
    long size = Files.size(path);
    if (size < 32 || size > (long) maxBytes + 32) throw new IOException("Invalid journal size");
    byte[] all = Files.readAllBytes(path), payload = Arrays.copyOfRange(all, 32, all.length);
    if (all.length < 32 || all.length > (long) maxBytes + 32)
      throw new IOException("Invalid journal size");
    if (!MessageDigest.isEqual(Arrays.copyOf(all, 32), digest(payload)))
      throw new IOException("Journal checksum mismatch");
    return payload;
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private AtomicFile() {}
}
