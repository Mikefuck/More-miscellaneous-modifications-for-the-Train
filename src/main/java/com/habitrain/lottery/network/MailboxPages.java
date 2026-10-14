package com.habitrain.lottery.network;

import com.google.gson.Gson;
import com.habitrain.lottery.mail.LocalMailboxStore.MailJson;
import com.habitrain.lottery.mail.MailService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded pages; both row count and encoded JSON size constrain every reply. */
public final class MailboxPages {
    public static final int MAX_CHARS = 180_000;
    public static final int MAX_ROWS = 50;
    private static final Gson GSON = new Gson();
    private static final Map<UUID, Integer> OFFSETS = new ConcurrentHashMap<>();
    private MailboxPages() { }

    public record Request(int offset) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "mailbox_page_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (b, p) -> b.writeVarInt(p.offset), b -> new Request(b.readVarInt()));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Snapshot(int offset, int next, int total, int unclaimed, String json) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "mailbox_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
                (b, p) -> { b.writeVarInt(p.offset); b.writeVarInt(p.next); b.writeVarInt(p.total); b.writeVarInt(p.unclaimed); b.writeUtf(p.json, MAX_CHARS); },
                b -> new Snapshot(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(MAX_CHARS)));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Snapshot.TYPE, Snapshot.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (p, ctx) -> ctx.server().execute(() -> {
            if (LotteryNetwork.rateLimited(ctx.player(), "mailbox_request", 300)) return;
            OFFSETS.put(ctx.player().getUUID(), Math.max(0, p.offset));
            send(ctx.player());
        }));
    }

    public static void forget(UUID uuid) { if (uuid != null) OFFSETS.remove(uuid); }

    public static void send(ServerPlayer player) {
        List<MailJson> mails = MailService.list(player);
        Snapshot page = page(mails, OFFSETS.getOrDefault(player.getUUID(), 0));
        OFFSETS.put(player.getUUID(), page.offset());
        if (ServerPlayNetworking.canSend(player, Snapshot.TYPE)) {
            ServerPlayNetworking.send(player, page);
        } else if (ServerPlayNetworking.canSend(player, MailboxListS2C.TYPE)) {
            // Legacy clients receive a bounded first page and an explicit upgrade notice.
            page = page(mails, 0);
            ServerPlayNetworking.send(player, new MailboxListS2C(page.json()));
            if (page.next() >= 0) player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§e[邮箱] 当前客户端仅显示第一页，请更新抽奖补齐以查看全部邮件"));
        }
    }

    public static Snapshot page(List<MailJson> mails, int requestedOffset) {
        List<MailJson> entries = mails.stream().filter(java.util.Objects::nonNull).toList();
        int offset = Math.max(0, Math.min(requestedOffset, Math.max(0, entries.size() - 1)));
        StringBuilder json = new StringBuilder("[");
        int end = offset;
        while (end < entries.size() && end - offset < MAX_ROWS) {
            String row = GSON.toJson(entries.get(end));
            if (row.length() + 2 > MAX_CHARS) {
                // Keep the ID and claim state accessible even for oversized external API content.
                MailJson summary = GSON.fromJson(row, MailJson.class);
                summary.content = "邮件内容过长，请联系管理员查看完整内容。";
                summary.title = summary.title == null ? "" : summary.title.substring(0, Math.min(256, summary.title.length()));
                summary.sender = summary.sender == null ? "" : summary.sender.substring(0, Math.min(128, summary.sender.length()));
                summary.commands = List.of();
                row = GSON.toJson(summary);
                if (row.length() + 2 > MAX_CHARS) throw new IllegalArgumentException("Invalid mailbox metadata");
            }
            if (json.length() + row.length() + 2 > MAX_CHARS) break;
            if (end > offset) json.append(',');
            json.append(row);
            end++;
        }
        json.append(']');
        return new Snapshot(offset, end < entries.size() ? end : -1, entries.size(),
                (int) entries.stream().filter(m -> !m.claimed).count(), json.toString());
    }
}
