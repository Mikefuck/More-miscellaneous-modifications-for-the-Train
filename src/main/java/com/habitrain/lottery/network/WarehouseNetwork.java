package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.player.HabiSystemItemApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.backpack.PlayerCardAdminModels.CardStoreStatus;
import com.habitrain.lottery.backpack.PlayerCardAdminService;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.SkinTypeKeys;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.title.LocalTitleStore;
import com.habitrain.lottery.warehouse.WarehouseEntry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Bounded chunks from one authoritative snapshot. Requests can only query their sender. */
public final class WarehouseNetwork {
    public static final int CHUNK_SIZE = 48, MAX_ENTRIES = 16384;
    private WarehouseNetwork() {}
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("habitrain_lottery", path); }
    public record Request(int requestId) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(id("warehouse_request_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (b, p) -> b.writeVarInt(p.requestId), b -> new Request(b.readVarInt()));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** Error is a translation suffix, never a success-shaped empty inventory. */
    public record Snapshot(int requestId, int offset, int total, String error, List<WarehouseEntry> entries)
            implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(id("warehouse_snapshot_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.requestId); b.writeVarInt(p.offset); b.writeVarInt(p.total); b.writeUtf(p.error, 32);
            b.writeVarInt(p.entries.size());
            for (var e : p.entries) {
                b.writeUtf(e.kind(), 24); b.writeUtf(e.id(), 128); b.writeUtf(e.name(), 256);
                b.writeUtf(e.description(), 1024); b.writeUtf(e.icon(), 128);
                b.writeVarInt(e.count()); b.writeInt(e.color()); b.writeBoolean(e.equipped());
                b.writeUtf(e.quality().id(), 16);
            }
        }, b -> {
            int request = b.readVarInt(), offset = b.readVarInt(), total = b.readVarInt();
            String error = b.readUtf(32); int size = b.readVarInt();
            if (total < 0 || total > MAX_ENTRIES || offset < 0 || size < 0 || size > CHUNK_SIZE || offset + size > total)
                throw new io.netty.handler.codec.DecoderException("Invalid warehouse chunk");
            var entries = new ArrayList<WarehouseEntry>(size);
            for (int i = 0; i < size; i++) entries.add(new WarehouseEntry(b.readUtf(24), b.readUtf(128),
                    b.readUtf(256), b.readUtf(1024), b.readUtf(128), b.readVarInt(), b.readInt(), b.readBoolean(), SkinQuality.fromId(b.readUtf(16))));
            return new Snapshot(request, offset, total, error, List.copyOf(entries));
        });
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.playC2S().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Snapshot.TYPE, Snapshot.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (p, ctx) -> ctx.server().execute(() -> {
            if (LotteryNetwork.rateLimited(ctx.player(), "warehouse", 750)) return;
            send(ctx.player(), p.requestId());
        }));
    }
    private static void fail(ServerPlayer player, int request, String reason) {
        ServerPlayNetworking.send(player, new Snapshot(request, 0, 0, reason, List.of()));
    }
    private static void send(ServerPlayer player, int request) {
        var store = PlayerLotteryStore.get();
        if (!WorldLotteryPaths.ready() || !store.isTakeoverActive()) { fail(player, request, "unavailable"); return; }
        try {
            var data = store.getOrLoad(player.getUUID());
            var cards = PlayerCardAdminService.snapshotOnline(player);
            var titleLoad = LocalTitleStore.get().loadPlayerDetailed(player.getUUID());
            if (store.isLoadFailed(player.getUUID()) || cards.status() == CardStoreStatus.CORRUPT || titleLoad.corrupt()) {
                fail(player, request, "storage_error"); return;
            }
            List<WarehouseEntry> rows = new ArrayList<>();
            rows.add(new WarehouseEntry("currency", "coins", key("coins"), key("coins_hint"), "minecraft:gold_ingot", data.coinNum, 0xFFD5C98D, false));
            rows.add(new WarehouseEntry("currency", "draws", key("draws"), key("draws_hint"), "minecraft:paper", data.lootChance, 0xFF85B4C8, false));
            cards.cards().forEach((id, count) -> rows.add(new WarehouseEntry("card", id,
                    "screen.habitrain_lottery.config.cards." + id, key("card_hint." + id), "minecraft:paper", count, 0, false)));
            data.systemItems.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(e -> {
                var d = HabiSystemItemApi.definition(e.getKey());
                rows.add(new WarehouseEntry("special", e.getKey(), d == null ? e.getKey() : d.name(),
                        d == null ? key("special_hint") : d.description(), d == null ? "minecraft:chest" : d.icon().toString(),
                        e.getValue(), d == null ? 0xFF91B7B3 : d.color(), false));
            });
            var seen = new HashSet<String>();
            data.unlocked.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(group -> {
                String type = SkinTypeKeys.canonical(group.getKey());
                group.getValue().entrySet().stream().filter(e -> Boolean.TRUE.equals(e.getValue()))
                        .sorted(java.util.Map.Entry.comparingByKey()).forEach(e -> {
                    String skin = e.getKey(), entry = type + "/" + skin;
                    if ("default".equals(skin) || !seen.add(entry)) return;
                    int color = HabiSkinApi.find(type, skin).map(d -> d.color()).orElse(0xFFA3ACBC);
                    rows.add(new WarehouseEntry("skin", entry, "skin.habitrain_lottery." + type + "." + skin,
                            key("skin_hint"), "minecraft:leather_chestplate", 1, color,
                            skin.equals(store.getEquipped(player.getUUID(), type)),
                            HabiSkinApi.quality(type, skin).orElse(SkinQuality.WHITE)));
                });
            });
            var titles = titleLoad.data();
            if (titles != null) {
                var catalog = LocalTitleStore.get().loadCatalog();
                titles.owned.stream().distinct().sorted().forEach(id -> rows.add(new WarehouseEntry("title", id,
                        catalog.titles.stream().filter(e -> e.id.equals(id)).map(e -> e.display).findFirst().orElse(id),
                        key("title_hint"), "minecraft:name_tag", 1, 0xFFC9B784, id.equals(titles.current))));
            }
            if (rows.size() > MAX_ENTRIES) { fail(player, request, "too_many"); return; }
            for (int offset = 0; offset < rows.size(); offset += CHUNK_SIZE)
                ServerPlayNetworking.send(player, new Snapshot(request, offset, rows.size(), "",
                        List.copyOf(rows.subList(offset, Math.min(rows.size(), offset + CHUNK_SIZE)))));
        } catch (RuntimeException ex) {
            HabiLotteryMod.LOGGER.error("Unable to read warehouse for {}", player.getUUID(), ex);
            fail(player, request, "storage_error");
        }
    }
    private static String key(String suffix) { return "screen.habitrain_lottery.warehouse." + suffix; }
}
