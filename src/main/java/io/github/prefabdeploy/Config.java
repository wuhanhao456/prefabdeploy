package io.github.prefabdeploy;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
  public static final ModConfigSpec SPEC;
  public static final ModConfigSpec.IntValue MAX_POSITIONS,
      MAX_CHUNKS,
      MAX_OPERATIONS,
      RAY_DISTANCE,
      MAX_BYTES;
  public static final ModConfigSpec.DoubleValue TICK_BUDGET_MS;
  public static final ModConfigSpec.ConfigValue<List<? extends String>> PROTECTED_BLOCKS,
      FORBIDDEN_CONTENT;

  static {
    var b = new ModConfigSpec.Builder();
    b.push("limits");
    MAX_POSITIONS = b.defineInRange("maxPositions", 100_000, 1, 5_000_000);
    MAX_CHUNKS = b.defineInRange("maxChunks", 256, 1, 4096);
    MAX_OPERATIONS = b.defineInRange("operationsPerTick", 512, 1, 16384);
    TICK_BUDGET_MS = b.defineInRange("tickBudgetMs", 2.0, 0.1, 40.0);
    RAY_DISTANCE = b.defineInRange("placementDistance", 32, 1, 128);
    MAX_BYTES = b.defineInRange("maxNbtBytes", 64 * 1024 * 1024, 1024, 256 * 1024 * 1024);
    b.pop().push("security");
    PROTECTED_BLOCKS =
        b.defineListAllowEmpty(
            "protectedBlocks",
            List.of("minecraft:bedrock", "minecraft:end_portal", "minecraft:end_portal_frame"),
            () -> "minecraft:bedrock",
            o -> o instanceof String);
    FORBIDDEN_CONTENT =
        b.defineListAllowEmpty(
            "forbiddenContent",
            List.of(
                "minecraft:command_block",
                "minecraft:repeating_command_block",
                "minecraft:chain_command_block",
                "minecraft:structure_block",
                "minecraft:jigsaw"),
            () -> "minecraft:command_block",
            o -> o instanceof String);
    b.pop();
    SPEC = b.build();
  }

  private Config() {}
}
