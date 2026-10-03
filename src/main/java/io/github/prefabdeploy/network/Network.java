package io.github.prefabdeploy.network;

import io.github.prefabdeploy.server.Sessions;
import java.util.function.Consumer;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class Network {
  public static Consumer<CompoundTag> CLIENT = n -> {};
  public static Consumer<String> TOOL = a -> {};
  public static java.util.function.BooleanSupplier PLACEMENT = () -> false;

  public record Message(CompoundTag data) implements CustomPacketPayload {
    public static final Type<Message> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("prefabdeploy", "message"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Message> CODEC =
        StreamCodec.of(
            (b, m) -> b.writeNbt(m.data),
            (b) -> {
              var n = b.readNbt(NbtAccounter.create(1024 * 1024));
              if (!(n instanceof CompoundTag compound))
                throw new IllegalArgumentException("Invalid prefab message");
              return new Message(compound);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  public static void register(RegisterPayloadHandlersEvent e) {
    e.registrar("1")
        .playBidirectional(
            Message.TYPE,
            Message.CODEC,
            (packet, context) -> {
              if (context.player() instanceof ServerPlayer p) Sessions.receive(p, packet.data);
              else CLIENT.accept(packet.data);
            });
  }

  public static CompoundTag message(String op) {
    var n = new CompoundTag();
    n.putString("op", op);
    return n;
  }

  public static void send(ServerPlayer p, CompoundTag data) {
    if (data.sizeInBytes() > 512 * 1024)
      throw new IllegalArgumentException("Prefab network message exceeds its safety limit");
    PacketDistributor.sendToPlayer(p, new Message(data));
  }

  public static void request(CompoundTag data) {
    PacketDistributor.sendToServer(new Message(data));
  }

  private Network() {}
}
