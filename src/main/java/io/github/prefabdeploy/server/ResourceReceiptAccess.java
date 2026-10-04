package io.github.prefabdeploy.server;

import net.minecraft.nbt.CompoundTag;

/** Attached to SavedData, so a resource debit and its receipt share one durable file. */
public interface ResourceReceiptAccess {
  CompoundTag prefabResourceReceipts();
  void prefabResourceReceipts(CompoundTag receipts);
}
