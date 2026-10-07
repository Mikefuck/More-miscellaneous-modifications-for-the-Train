package com.habitrain.lottery.skin;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinQuality;
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
    private static final java.util.Map<java.util.UUID, Integer> LAST_SOUND_REQUEST = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, SoundRequest> PENDING_SOUNDS = new java.util.HashMap<>();
    private SkinNetwork() {}
    public record Entry(String type, String id, boolean owned, boolean equipped, SkinQuality quality) {
        public Entry {
            quality = java.util.Objects.requireNonNull(quality, "quality");
        }
        public Entry(String type, String id, boolean owned, boolean equipped) {
            this(type, id, owned, equipped, SkinQuality.WHITE);
        }
    }
    public record Request(String typeName, String skin) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "skin_request_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeUtf(p.typeName, 32); buf.writeUtf(p.skin, 64); },
                buf -> new Request(buf.readUtf(32), buf.readUtf(64)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Snapshot(boolean open, List<Entry> entries) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "skin_snapshot_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeBoolean(p.open); buf.writeVarInt(p.entries.size());
            for (Entry e : p.entries) { buf.writeUtf(e.type, 32); buf.writeUtf(e.id, 64); buf.writeBoolean(e.owned); buf.writeBoolean(e.equipped); buf.writeUtf(e.quality.id(), 16); }
        }, buf -> {
            boolean open = buf.readBoolean(); int count = buf.readVarInt();
            if (count < 0 || count > MAX_ENTRIES) throw new io.netty.handler.codec.DecoderException("Skin catalog exceeds limit");
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) entries.add(new Entry(buf.readUtf(32), buf.readUtf(64), buf.readBoolean(), buf.readBoolean(), SkinQuality.fromId(buf.readUtf(16))));
            return new Snapshot(open, List.copyOf(entries));
        });
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record SoundRequest(String typeName, String skin, boolean enabled) implements CustomPacketPayload {
        public static final Type<SoundRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                "habitrain_lottery", "skin_sound_request_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SoundRequest> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeUtf(p.typeName, 32); buf.writeUtf(p.skin, 64); buf.writeBoolean(p.enabled); },
                buf -> new SoundRequest(buf.readUtf(32), buf.readUtf(64), buf.readBoolean()));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record SoundEntry(String type, String id, boolean enabled) { }
    public record SoundSnapshot(List<SoundEntry> entries) implements CustomPacketPayload {
        public static final Type<SoundSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                "habitrain_lottery", "skin_sound_snapshot_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SoundSnapshot> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.entries.size());
            for (var e : p.entries) { buf.writeUtf(e.type, 32); buf.writeUtf(e.id, 64); buf.writeBoolean(e.enabled); }
        }, buf -> {
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_ENTRIES)
                throw new io.netty.handler.codec.DecoderException("Skin sound preferences exceed limit");
            List<SoundEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) entries.add(new SoundEntry(buf.readUtf(32), buf.readUtf(64), buf.readBoolean()));
            return new SoundSnapshot(List.copyOf(entries));
        });
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.playC2S().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Snapshot.TYPE, Snapshot.CODEC);
        PayloadTypeRegistry.playC2S().register(SoundRequest.TYPE, SoundRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(SoundSnapshot.TYPE, SoundSnapshot.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LAST_REQUEST.remove(handler.player.getUUID()); LAST_SOUND_REQUEST.remove(handler.player.getUUID());
            PENDING_SOUNDS.remove(handler.player.getUUID());
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LAST_REQUEST.clear(); LAST_SOUND_REQUEST.clear();
            PENDING_SOUNDS.clear();
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            var iterator = PENDING_SOUNDS.entrySet().iterator();
            while (iterator.hasNext()) {
                var pending = iterator.next();
                int tick = server.getTickCount();
                Integer last = LAST_SOUND_REQUEST.get(pending.getKey());
                if (last != null && tick - last >= 0 && tick - last < 3) continue;
                var player = server.getPlayerList().getPlayer(pending.getKey());
                var request = pending.getValue();
                iterator.remove();
                if (player != null) commitSoundRequest(player, request, tick);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(SoundRequest.TYPE, (p, ctx) -> ctx.server().execute(() -> {
            var player = ctx.player();
            int tick = ctx.server().getTickCount();
            Integer last = LAST_SOUND_REQUEST.get(player.getUUID());
            if (last != null && tick - last >= 0 && tick - last < 3) {
                // Keep the newest choice and acknowledge only after it is actually committed.
                PENDING_SOUNDS.put(player.getUUID(), p);
                return;
            }
            PENDING_SOUNDS.remove(player.getUUID());
            commitSoundRequest(player, p, tick);
        }));
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
    private static void commitSoundRequest(ServerPlayer player, SoundRequest request, int tick) {
        LAST_SOUND_REQUEST.put(player.getUUID(), tick);
        var result = com.habitrain.lottery.api.player.HabiSkinPlayerApi.setCustomSoundEnabled(
                player, request.typeName, request.skin, request.enabled);
        if (!result.ok()) {
            player.sendSystemMessage(Component.literal("[皮肤] 枪声设置保存失败：" + result.message()));
            syncSounds(player);
        }
    }
    public static void sync(ServerPlayer player) { send(player, false); }
    public static void syncSounds(ServerPlayer player) {
        if (player == null || !ServerPlayNetworking.canSend(player, SoundSnapshot.TYPE)) return;
        var store = PlayerLotteryStore.get();
        if (!store.isTakeoverActive() || store.isLoadFailed(player.getUUID())) return;
        List<SoundEntry> sounds = new ArrayList<>();
        for (var skin : HabiSkinApi.registrations()) {
            if (com.habitrain.lottery.api.skin.SkinSounds.supports(skin.type(), skin.id()))
                sounds.add(new SoundEntry(skin.type(), skin.id(),
                        store.isCustomSoundEnabled(player.getUUID(), skin.type(), skin.id())));
        }
        ServerPlayNetworking.send(player, new SoundSnapshot(List.copyOf(sounds)));
    }
    public static boolean syncAccess(ServerPlayer p, PlayerLotteryData data, String type, String skin, boolean unlocked) {
        if (p == null) return false;
        InventorySkinApplier.applyAllEquipped(p, data.equipped); sync(p); return true;
    }
    private static void send(ServerPlayer player, boolean open) {
        if (player == null || !ServerPlayNetworking.canSend(player, Snapshot.TYPE)) return;
        var store = PlayerLotteryStore.get();
        if (!store.isTakeoverActive() || store.isLoadFailed(player.getUUID())) return;
        syncSounds(player);
        List<Entry> entries = new ArrayList<>();
        for (var skin : HabiSkinApi.registrations()) {
            entries.add(new Entry(skin.type(), skin.id(), store.isSkinUnlocked(player.getUUID(), skin.type(), skin.id()),
                    skin.id().equals(store.getEquipped(player.getUUID(), skin.type())), skin.quality()));
        }
        ServerPlayNetworking.send(player, new Snapshot(open, List.copyOf(entries)));
    }
}
