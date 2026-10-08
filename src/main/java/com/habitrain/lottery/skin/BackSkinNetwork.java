package com.habitrain.lottery.skin;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Back cosmetics have no equipment slot; synchronize them to the owner and observers. */
public final class BackSkinNetwork {
    private BackSkinNetwork() { }

    /** Empty skin means no back cosmetic, including when an entity stops being tracked. */
    public record Appearance(UUID player, String skin) implements CustomPacketPayload {
        public static final Type<Appearance> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                "habitrain_lottery", "back_skin_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Appearance> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeUUID(p.player); buf.writeUtf(p.skin, 48); },
                buf -> new Appearance(buf.readUUID(), buf.readUtf(48)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Appearance.TYPE, Appearance.CODEC);
        EntityTrackingEvents.START_TRACKING.register((entity, observer) -> {
            if (entity instanceof ServerPlayer owner) send(observer, appearance(owner));
        });
        EntityTrackingEvents.STOP_TRACKING.register((entity, observer) -> {
            if (entity instanceof ServerPlayer owner) send(observer, new Appearance(owner.getUUID(), ""));
        });
        // Defer until the normal JOIN handlers have loaded the authoritative world account.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            if (server.getPlayerList().getPlayer(handler.player.getUUID()) == handler.player) sync(handler.player);
        }));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> sync(newPlayer));
    }

    /** Called only after a saved equip/access change, or to reassert existing saved state. */
    public static void sync(ServerPlayer owner) {
        if (owner == null) return;
        Appearance appearance = appearance(owner);
        send(owner, appearance);
        for (ServerPlayer observer : PlayerLookup.tracking(owner)) send(observer, appearance);
    }

    private static Appearance appearance(ServerPlayer owner) {
        var store = PlayerLotteryStore.get();
        String skin = "";
        if (store.isTakeoverActive() && !store.isLoadFailed(owner.getUUID())) {
            String equipped = store.getEquipped(owner.getUUID(), "back");
            if (HabiSkinApi.find("back", equipped).isPresent()
                    && store.isSkinUnlocked(owner.getUUID(), "back", equipped)) skin = equipped;
        }
        return new Appearance(owner.getUUID(), skin);
    }

    private static void send(ServerPlayer observer, Appearance appearance) {
        if (ServerPlayNetworking.canSend(observer, Appearance.TYPE)) ServerPlayNetworking.send(observer, appearance);
    }
}
