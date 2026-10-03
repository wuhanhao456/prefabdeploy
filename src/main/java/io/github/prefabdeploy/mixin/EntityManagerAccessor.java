package io.github.prefabdeploy.mixin;

import java.util.function.Consumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.*;

@Mixin(PersistentEntitySectionManager.class)
public interface EntityManagerAccessor<T extends EntityAccess> {
  @Accessor("permanentStorage")
  EntityPersistentStorage<T> prefabStorage();

  @Invoker("storeChunkSections")
  boolean prefabStore(long chunk, Consumer<T> consumer);
}
