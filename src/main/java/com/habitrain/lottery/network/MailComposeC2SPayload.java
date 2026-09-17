package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.mail.MailComposeLimits;
import com.habitrain.lottery.mail.MailDraft;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.mail.MailService;
import com.habitrain.lottery.mail.MailTargetParse;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * OP mail compose packet: target mode + rewards.
 */
public record MailComposeC2SPayload(
        int targetMode,
        List<String> targets,
        String sender,
        String title,
        String content,
        int expiresDays,
        List<RewardEntry> rewards
) implements CustomPacketPayload {

    /** 0 = ONLINE_LIST, 1 = ALL_PLAYERS, 2 = OFFLINE_NAME */
    public static final int MODE_ONLINE_LIST = 0;
    public static final int MODE_ALL_PLAYERS = 1;
    public static final int MODE_OFFLINE_NAME = 2;

    /** 对所有玩家发放时未填写有效期默认的限时天数。 */
    public static final int DEFAULT_ALL_PLAYER_EXPIRY_DAYS = 30;

    public static final Type<MailComposeC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "mail_compose"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MailComposeC2SPayload> CODEC = StreamCodec.of(
            MailComposeC2SPayload::write, MailComposeC2SPayload::read);

    public record RewardEntry(int kind, int amount, String factionType) {
        public static final int DRAWS = 0;
        public static final int COINS = 1;
        public static final int FACTION_CARD = 2;
        public static final int SELF_SELECT_CARD = 3;
        public static final int LIMIT_BREAK_CARD = 4;
    }

    private static void write(RegistryFriendlyByteBuf buf, MailComposeC2SPayload value) {
        buf.writeVarInt(value.targetMode);
        List<String> targets = value.targets == null ? List.of() : value.targets;
        int n = Math.min(MailTargetParse.MAX_TARGETS, Math.max(0, targets.size()));
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            String t = targets.get(i);
            buf.writeUtf(t == null ? "" : t, 64);
        }
        buf.writeUtf(value.sender == null ? "系统" : value.sender, 64);
        buf.writeUtf(value.title == null ? "" : value.title, 256);
        buf.writeUtf(value.content == null ? "" : value.content, 8000);
        buf.writeVarInt(MailComposeLimits.clampExpiresDays(value.expiresDays));
        List<RewardEntry> rewards = value.rewards == null ? List.of() : value.rewards;
        int rn = Math.min(MailComposeLimits.MAX_REWARDS, Math.max(0, rewards.size()));
        buf.writeVarInt(rn);
        for (int i = 0; i < rn; i++) {
            RewardEntry r = rewards.get(i);
            buf.writeVarInt(r.kind);
            buf.writeVarInt(MailComposeLimits.clampRewardAmount(r.amount));
            buf.writeUtf(r.factionType == null ? "" : r.factionType, 64);
        }
    }

    private static MailComposeC2SPayload read(RegistryFriendlyByteBuf buf) {
        int mode = buf.readVarInt();
        int n = MailComposeLimits.requireCount(buf.readVarInt(), MailTargetParse.MAX_TARGETS, "targets");
        List<String> targets = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            targets.add(buf.readUtf(64));
        }
        String sender = buf.readUtf(64);
        String title = buf.readUtf(256);
        String content = buf.readUtf(8000);
        int expiresDays = MailComposeLimits.clampExpiresDays(buf.readVarInt());
        int rn = MailComposeLimits.requireCount(buf.readVarInt(), MailComposeLimits.MAX_REWARDS, "rewards");
        List<RewardEntry> rewards = new ArrayList<>(rn);
        for (int i = 0; i < rn; i++) {
            int kind = buf.readVarInt();
            int amount = MailComposeLimits.clampRewardAmount(buf.readVarInt());
            rewards.add(new RewardEntry(kind, amount, buf.readUtf(64)));
        }
        return new MailComposeC2SPayload(mode, targets, sender, title, content, expiresDays, rewards);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public MailDraft toDraft() {
        List<MailReward> list = new ArrayList<>();
        for (RewardEntry r : rewards) {
            if (r == null || r.amount == 0) {
                continue;
            }
            switch (r.kind) {
                case RewardEntry.DRAWS -> list.add(MailReward.draws(r.amount));
                case RewardEntry.COINS -> list.add(MailReward.coins(r.amount));
                case RewardEntry.FACTION_CARD -> list.add(MailReward.factionCard(
                        r.factionType == null ? "" : r.factionType.toLowerCase(Locale.ROOT), r.amount));
                case RewardEntry.SELF_SELECT_CARD -> list.add(MailReward.selfSelectCard(r.amount));
                case RewardEntry.LIMIT_BREAK_CARD -> list.add(MailReward.limitBreakCard(r.amount));
                default -> {
                }
            }
        }
        long expiresAt = 0;
        if (expiresDays > 0) {
            expiresAt = System.currentTimeMillis() + expiresDays * 24L * 60L * 60L * 1000L;
        }
        return new MailDraft(sender, title, content, expiresAt, list);
    }

    public static void handle(ServerPlayer player, MailComposeC2SPayload payload) {
        if (player == null) {
            return;
        }
        if (!LotteryNetwork.isOp(player)) {
            player.sendSystemMessage(Component.literal("§c[邮箱] 需要 OP 才能发信"));
            return;
        }
        if (LotteryNetwork.gateBlocked(player)) {
            player.sendSystemMessage(Component.literal("§c当前为未授权的访问：未获得服务器授权修改配置"));
            return;
        }
        if (payload.title == null || payload.title.isBlank()) {
            player.sendSystemMessage(Component.literal("§c[邮箱] 标题不能为空"));
            return;
        }
        MailDraft draft = payload.toDraft();
        // 全员发放模式默认带 30 天限时：未填写（0）时兜底为 30 天，填写了其它正数则尊重管理员设置。
        if (payload.targetMode == MODE_ALL_PLAYERS && payload.expiresDays() <= 0) {
            draft = new MailDraft(draft.sender(), draft.title(), draft.content(),
                    System.currentTimeMillis()
                            + DEFAULT_ALL_PLAYER_EXPIRY_DAYS * 24L * 60L * 60L * 1000L,
                    draft.rewards());
        }
        MinecraftServer server = player.server;
        int ok = 0;
        int fail = 0;
        switch (payload.targetMode) {
            case MODE_ALL_PLAYERS -> {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (MailService.send(p, draft)) {
                        ok++;
                    } else {
                        fail++;
                    }
                }
                // 离线部分：白名单（含从未进服）+ 曾进服玩家（playerdata），按 UUID 去重。
                // 在线玩家已在上面发过，这里跳过，避免重复发放。
                for (Map.Entry<UUID, String> entry : collectAllKnownPlayers(server).entrySet()) {
                    UUID uuid = entry.getKey();
                    if (server.getPlayerList().getPlayer(uuid) != null) {
                        continue;
                    }
                    if (MailService.sendOffline(uuid, entry.getValue(), draft)) {
                        ok++;
                    } else {
                        fail++;
                    }
                }
            }
            case MODE_OFFLINE_NAME -> {
                boolean onlineMode = server.usesAuthentication();
                for (String name : payload.targets) {
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    ServerPlayer online = server.getPlayerList().getPlayerByName(name);
                    if (online != null) {
                        if (MailService.send(online, draft)) {
                            ok++;
                        } else {
                            fail++;
                        }
                        continue;
                    }
                    Optional<UUID> cacheHit = lookupProfileUuid(server, name);
                    Optional<UUID> uuid = resolveOfflineUuid(onlineMode, cacheHit, name);
                    if (uuid.isEmpty()) {
                        if (onlineMode) {
                            player.sendSystemMessage(Component.literal(
                                    "§c[邮箱] 正版服无法解析离线玩家 UUID：" + name + "（档案缓存未命中）"));
                        } else {
                            player.sendSystemMessage(Component.literal("§c[邮箱] 无法解析玩家：" + name));
                        }
                        fail++;
                        continue;
                    }
                    if (MailService.sendOffline(uuid.get(), name, draft)) {
                        ok++;
                    } else {
                        fail++;
                    }
                }
            }
            default -> {
                for (String name : payload.targets) {
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    ServerPlayer online = server.getPlayerList().getPlayerByName(name);
                    if (online != null && MailService.send(online, draft)) {
                        ok++;
                    } else {
                        fail++;
                    }
                }
            }
        }
        player.sendSystemMessage(Component.literal("§a[邮箱] 发送完成：成功 " + ok + "，失败 " + fail));
    }

    /**
     * 收集服务器已知的全部玩家（uuid → 名字提示），用于"所有玩家"模式：
     * <ol>
     *   <li>白名单条目 —— 覆盖从未进服但已被登记的玩家；</li>
     *   <li>{@code world/playerdata} 目录 —— 覆盖所有曾进服过的玩家。</li>
     * </ol>
     * 在线玩家不在此集合中处理（调用方单独发送并跳过）。
     */
    private static Map<UUID, String> collectAllKnownPlayers(MinecraftServer server) {
        Map<UUID, String> players = new LinkedHashMap<>();
        try {
            // 白名单条目携带 GameProfile（名字 + UUID，含从未进服玩家的登记 UUID）。
            // getUser() 在映射中是包私有方法，这里通过反射读取（与 resolveOfflineUuid 的防御式风格一致）。
            var getUserMethod = net.minecraft.server.players.StoredUserEntry.class
                    .getDeclaredMethod("getUser");
            getUserMethod.setAccessible(true);
            for (var entry : server.getPlayerList().getWhiteList().getEntries()) {
                GameProfile profile;
                try {
                    profile = (GameProfile) getUserMethod.invoke(entry);
                } catch (Throwable ignored) {
                    profile = null;
                }
                if (profile == null) {
                    continue;
                }
                UUID id = profile.getId();
                String name = profile.getName();
                if (id == null && name != null) {
                    id = resolveOfflineUuid(server.usesAuthentication(),
                            lookupProfileUuid(server, name), name).orElse(null);
                }
                if (id == null) {
                    continue;
                }
                players.putIfAbsent(id, name == null || name.isBlank() ? shortUuid(id) : name);
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("Mail all-player whitelist scan failed: {}", t.toString());
        }
        try {
            Path dataDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            if (Files.isDirectory(dataDir)) {
                try (var stream = Files.list(dataDir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".dat")).forEach(p -> {
                        String fileName = p.getFileName().toString();
                        try {
                            UUID id = UUID.fromString(fileName.substring(0, fileName.length() - 4));
                            players.putIfAbsent(id, resolveKnownName(server, id));
                        } catch (IllegalArgumentException ignored) {
                        }
                    });
                }
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("Mail all-player playerdata scan failed: {}", t.toString());
        }
        return players;
    }

    private static String resolveKnownName(MinecraftServer server, UUID id) {
        try {
            var cache = server.getProfileCache();
            if (cache != null) {
                var opt = cache.get(id);
                if (opt != null && opt.isPresent() && opt.get().getName() != null
                        && !opt.get().getName().isBlank()) {
                    return opt.get().getName();
                }
            }
        } catch (Throwable ignored) {
        }
        return shortUuid(id);
    }

    private static String shortUuid(UUID id) {
        String s = id.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    /**
     * Resolve an offline mail target UUID.
     * Online-mode cache misses must not fall back to {@code OfflinePlayer:} UUIDs.
     */
    public static Optional<UUID> resolveOfflineUuid(boolean onlineMode, Optional<UUID> cacheHit, String name) {
        if (cacheHit != null && cacheHit.isPresent()) {
            return cacheHit;
        }
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        if (onlineMode) {
            return Optional.empty();
        }
        return Optional.of(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
    }

    private static Optional<UUID> lookupProfileUuid(MinecraftServer server, String name) {
        if (server == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            var cache = server.getProfileCache();
            if (cache == null) {
                return Optional.empty();
            }
            var opt = cache.get(name);
            if (opt == null || opt.isEmpty()) {
                return Optional.empty();
            }
            Object profile = opt.get();
            try {
                var m = profile.getClass().getMethod("getId");
                Object id = m.invoke(profile);
                if (id instanceof UUID u) {
                    return Optional.of(u);
                }
            } catch (NoSuchMethodException ignored) {
            }
            try {
                var m = profile.getClass().getMethod("id");
                Object id = m.invoke(profile);
                if (id instanceof UUID u) {
                    return Optional.of(u);
                }
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }
}
