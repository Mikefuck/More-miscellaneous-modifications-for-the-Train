package com.habitrain.lottery.skin;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;

/** Vanilla persistence and item-stack networking; no upstream data component. */
public final class SkinComponents {
    public static final DataComponentType<String> SKIN = DataComponentType.<String>builder()
            .persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.stringUtf8(128)).build();
    private SkinComponents() {}
    public static void register() {
        Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
                ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "skin"), SKIN);
    }
}
