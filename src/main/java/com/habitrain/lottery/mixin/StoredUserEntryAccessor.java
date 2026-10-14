package com.habitrain.lottery.mixin;

import net.minecraft.server.players.StoredUserEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Direct mapped invocation, including in the remapped production jar. */
@Mixin(StoredUserEntry.class)
public interface StoredUserEntryAccessor {
    @Invoker("getUser") Object habitrain$getUser();
}
