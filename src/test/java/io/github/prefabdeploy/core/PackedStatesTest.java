package io.github.prefabdeploy.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PackedStatesTest {
  @Test
  void decodesRandomEntriesIncludingCrossLongBoundaries() {
    var random = new Random(1211);
    for (int palette : new int[] {1, 3, 17, 257, 4097}) {
      int bits = PackedStates.bits(palette), count = 10001;
      var words = new long[(count * bits + 63) / 64];
      var expected = new int[count];
      for (int i = 0; i < count; i++) {
        int value = expected[i] = random.nextInt(palette);
        long bit = (long) i * bits;
        int word = (int) (bit >>> 6), shift = (int) (bit & 63);
        words[word] |= (long) value << shift;
        if (shift + bits > 64) words[word + 1] |= (long) value >>> (64 - shift);
      }
      for (int i = 0; i < count; i++) assertEquals(expected[i], PackedStates.get(words, bits, i));
    }
  }

  @Test
  void negativeDimensionsHaveTheSameVolume() {
    assertEquals(392, PackedStates.volume(-7, 8, -7, 100000));
  }

  @Test
  void rejectsTruncatedStreamsAndOversizedOrEmptyRegions() {
    assertThrows(IllegalArgumentException.class, () -> PackedStates.get(new long[1], 5, 12));
    assertThrows(IllegalArgumentException.class, () -> PackedStates.volume(128, 64, 128, 100000));
    assertThrows(IllegalArgumentException.class, () -> PackedStates.volume(0, 8, 8, 100000));
  }
}
