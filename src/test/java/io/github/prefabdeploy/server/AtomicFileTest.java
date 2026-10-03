package io.github.prefabdeploy.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileTest {
  @TempDir Path temp;

  @Test
  void replacementKeepsExactlyOneValidRecord() throws Exception {
    var p = temp.resolve("job.journal");
    AtomicFile.write(p, new byte[] {1, 2, 3});
    AtomicFile.write(p, new byte[] {4, 5, 6, 7});
    assertArrayEquals(new byte[] {4, 5, 6, 7}, AtomicFile.read(p, 100));
  }

  @Test
  void tornAndCorruptRecordsFailClosed() throws Exception {
    var p = temp.resolve("job.journal");
    AtomicFile.write(p, new byte[] {1, 2, 3});
    var bytes = Files.readAllBytes(p);
    bytes[34] ^= 1;
    Files.write(p, bytes);
    assertThrows(java.io.IOException.class, () -> AtomicFile.read(p, 100));
    Files.write(p, new byte[] {1});
    assertThrows(java.io.IOException.class, () -> AtomicFile.read(p, 100));
  }

  @Test
  void concurrentReadersNeverSeePartialPayload() throws Exception {
    var p = temp.resolve("job.journal");
    byte[] one = new byte[4096], two = new byte[8192];
    Arrays.fill(one, (byte) 17);
    Arrays.fill(two, (byte) 42);
    AtomicFile.write(p, one);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var writer =
          executor.submit(
              () -> {
                try {
                  for (int i = 0; i < 80; i++) AtomicFile.write(p, i % 2 == 0 ? two : one);
                } catch (Exception ex) {
                  throw new CompletionException(ex);
                }
              });
      while (!writer.isDone()) {
        byte[] read = AtomicFile.read(p, 10000);
        assertTrue(Arrays.equals(read, one) || Arrays.equals(read, two));
      }
      writer.get();
    } finally {
      executor.shutdownNow();
    }
  }
}
