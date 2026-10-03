package io.github.prefabdeploy.core;

/** Litematic uses a contiguous bit stream; entries can straddle long boundaries. */
public final class PackedStates {
  private PackedStates() {}

  public static int bits(int paletteSize) {
    if (paletteSize < 1) throw new IllegalArgumentException("Empty palette");
    return Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
  }

  public static int get(long[] words, int bits, int index) {
    if (index < 0 || bits < 2 || bits > 31)
      throw new IllegalArgumentException("Invalid packed index");
    long bit = (long) index * bits;
    int word = Math.toIntExact(bit >>> 6), shift = (int) (bit & 63);
    if (word >= words.length) throw new IllegalArgumentException("Truncated block states");
    long value = words[word] >>> shift;
    if (shift + bits > 64) {
      if (word + 1 >= words.length) throw new IllegalArgumentException("Truncated block states");
      value |= words[word + 1] << (64 - shift);
    }
    return (int) (value & ((1L << bits) - 1));
  }

  public static long volume(int x, int y, int z, int limit) {
    long n =
        Math.multiplyExact(
            Math.multiplyExact(Math.abs((long) x), Math.abs((long) y)), Math.abs((long) z));
    if (n == 0 || n > limit)
      throw new IllegalArgumentException("Region exceeds position limit: " + n);
    return n;
  }
}
