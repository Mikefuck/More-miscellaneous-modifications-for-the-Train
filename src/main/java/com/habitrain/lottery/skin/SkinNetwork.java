package com.habitrain.lottery.skin;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.bridge.InventorySkinApplier;
import com.habitrain.lottery.bridge.SkinStateCoordinator;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;

/** Server validates ownership and persists before acknowledging an equip request. */
public final class SkinNetwork {
    public static final int MAX_ENTRIES = 4096;
    private static final java.util.Map<java.util.UUID, Integer> LAST_REQUEST = new java.util.HashMap<>();
    private SkinNetwork() {}
    public record Entry(String type, String id, boolean owned, boolean equipped) {}
    public record Request(String typeName, String skin) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "skin_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeUtf(p.typeName, 32); buf.writeUtf(p.skin, 64); },
                buf -> new Request(buf.readUtf(32), buf.readUtf(64)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Snapshot(boolean open, List<Entry> entries) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "skin_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeBoolean(p.open); buf.writeVarInt(p.entries.size());
            for (Entry e : p.entries) { buf.writeUtf(e.type, 32); buf.writeUtf(e.id, 64); buf.writeBoolean(e.owned); buf.writeBoolean(e.equipped); }
        }, buf -> {
            boolean open = buf.readBoolean(); int count = buf.readVarInt();
            if (count < 0 || count > MAX_ENTRIES) throw new io.netty.handler.codec.DecoderException("Skin catalog exceeds limit");
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) entries.add(new Entry(buf.readUtf(32), buf.readUtf(64), buf.readBoolean(), buf.readBoolean()));
            return new Snapshot(open, List.copyOf(entries));
        });
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.playC2S().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Snapshot.TYPE, Snapshot.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                LAST_REQUEST.remove(handler.player.getUUID()));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_REQUEST.clear());
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (p, ctx) -> ctx.server().execute(() -> {
            var player = ctx.player();
            int tick = ctx.server().getTickCount();
            Integer last = LAST_REQUEST.get(player.getUUID());
            if (last != null && tick - last >= 0 && tick - last < 3) return;
            LAST_REQUEST.put(player.getUUID(), tick);
            if (p.typeName.isEmpty()) {
                // An in-screen refresh must never reopen a screen the player has closed.
                if ("refresh".equals(p.skin)) sync(player); else open(player);
                return;
            }
            var result = SkinStateCoordinator.commitEquipped(player, p.typeName, p.skin);
            if (!result.committed()) {
                player.sendSystemMessage(Component.literal("[皮肤] 切换失败：" + result.failure()));
                sync(player);
            }
        }));
    }
    public static void open(ServerPlayer player) { send(player, true); }
    public static void sync(ServerPlayer player) { send(player, false); }
    public static boolean syncAccess(ServerPlayer p, PlayerLotteryData data, String type, String skin, boolean unlocked) {
        if (p == null) return false;
        InventorySkinApplier.applyAllEquipped(p, data.equipped); sync(p); return true;
    }
    private static void send(ServerPlayer player, boolean open) {
        if (player == null || !ServerPlayNetworking.canSend(player, Snapshot.TYPE)) return;
        var store = PlayerLotteryStore.get();
        if (!store.isTakeoverActive() || store.isLoadFailed(player.getUUID())) return;
        List<Entry> entries = new ArrayList<>();
        for (var skin : HabiSkinApi.registrations()) {
            entries.add(new Entry(skin.type(), skin.id(), store.isSkinUnlocked(player.getUUID(), skin.type(), skin.id()),
                    skin.id().equals(store.getEquipped(player.getUUID(), skin.type()))));
        }
        ServerPlayNetworking.send(player, new Snapshot(open, List.copyOf(entries)));
    }
}
