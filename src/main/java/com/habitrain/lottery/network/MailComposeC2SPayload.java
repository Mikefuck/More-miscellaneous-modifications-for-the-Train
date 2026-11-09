package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.mail.MailComposeLimits;
import com.habitrain.lottery.mail.MailDraft;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.mail.MailService;
import com.habitrain.lottery.mail.MailTargetParse;
import com.habitrain.lottery.mixin.StoredUserEntryAccessor;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

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
        // Retired wire kinds 0 and 1 stay reserved for protocol compatibility.
        public static final int FACTION_CARD = 2;
        public static final int SELF_SELECT_CARD = 3;
        public static final int LIMIT_BREAK_CARD = 4;
        public static final int SKIN = 5;
        public static final int GREEN_APPLES = 6;
        public static final int CRATE = 7;
        public static final int KEY = 8;
    }

    /** Summary delivered back to the server thread once a background mail batch finishes. */
    record DispatchSummary(List<UUID> succeededRecipients, int okCount, int failCount) {
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
                case RewardEntry.SKIN -> list.add(MailReward.skinEntry(r.factionType));
                case RewardEntry.GREEN_APPLES -> list.add(MailReward.greenApples(r.amount));
                case RewardEntry.FACTION_CARD -> list.add(MailReward.factionCard(
                        r.factionType == null ? "" : r.factionType.toLowerCase(Locale.ROOT), r.amount));
                case RewardEntry.SELF_SELECT_CARD -> list.add(MailReward.selfSelectCard(r.amount));
                case RewardEntry.LIMIT_BREAK_CARD -> list.add(MailReward.limitBreakCard(r.amount));
                case RewardEntry.CRATE -> list.add(MailReward.crate(r.factionType, r.amount));
                case RewardEntry.KEY -> list.add(MailReward.key(r.factionType, r.amount));
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

    /**
     * Deduplicates target names case-insensitively while preserving first-seen casing and order.
     */
    public static List<String> deduplicateTargets(List<String> rawTargets) {
        if (rawTargets == null || rawTargets.isEmpty()) {
            return List.of();
        }
        Set<String> seenLower = new LinkedHashSet<>();
        List<String> unique = new ArrayList<>(rawTargets.size());
        for (String raw : rawTargets) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (seenLower.add(trimmed.toLowerCase(Locale.ROOT))) {
                unique.add(trimmed);
            }
        }
        return Collections.unmodifiableList(unique);
    }

    public static void handle(ServerPlayer player, MailComposeC2SPayload payload) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        if (!AdminAuth.isOpOrSingleplayerOwner(player)) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.compose.need_op"));
            return;
        }
        if (!AdminAuth.isAuthorizedOp(player)) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.admin.unauthorized_config"));
            return;
        }
        if (!WorldLotteryPaths.ready()) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.compose.storage_not_ready"));
            return;
        }
        if (payload == null || payload.title == null || payload.title.isBlank()) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.compose.title_blank"));
            return;
        }
        MailDraft draft;
        try {
            draft = payload.toDraft();
            for (MailReward reward : draft.rewards()) {
                if (reward.kind() == MailReward.Kind.SKIN
                        && com.habitrain.lottery.api.skin.HabiSkinApi.fromEntry(reward.factionType()).isEmpty()) {
                    player.sendSystemMessage(Component.translatable(
                            "message.habitrain_lottery.mail.compose.unknown_skin", reward.factionType()));
                    return;
                }
                if ((reward.kind() == MailReward.Kind.CRATE || reward.kind() == MailReward.Kind.KEY)
                        && (CrateCatalog.find(reward.factionType()) == null
                        || CrateCatalog.find(reward.factionType()).archived())) {
                    player.sendSystemMessage(Component.translatable(
                            "message.habitrain_lottery.mail.compose.unknown_crate", reward.factionType()));
                    return;
                }
            }
        } catch (IllegalArgumentException invalid) {
            player.sendSystemMessage(Component.translatable(
                    "message.habitrain_lottery.mail.compose.invalid_attachment", String.valueOf(invalid.getMessage())));
            return;
        }
        // 全员发放模式默认带 30 天限时：未填写（0）时兜底为 30 天，填写了其它正数则尊重管理员设置。
        if (payload.targetMode == MODE_ALL_PLAYERS && payload.expiresDays() <= 0) {
            draft = new MailDraft(draft.sender(), draft.title(), draft.content(),
                    System.currentTimeMillis()
                            + DEFAULT_ALL_PLAYER_EXPIRY_DAYS * 24L * 60L * 60L * 1000L,
                    draft.rewards());
        }
        if (MailDispatchExecutor.isBusy()) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.compose.busy"));
            return;
        }

        MinecraftServer server = player.server;
        UUID senderUuid = player.getUUID();
        MailDraft finalDraft = draft;

        switch (payload.targetMode) {
            case MODE_ALL_PLAYERS -> {
                Map<UUID, String> seedRecipients;
                try {
                    seedRecipients = snapshotOnlineAndWhitelistedPlayers(server);
                } catch (RuntimeException failure) {
                    HabiLotteryMod.LOGGER.error("Unable to snapshot mail whitelist/online recipients", failure);
                    player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.compose.recipients_failed"));
                    return;
                }
                Path playerDataDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
                Path lotteryPlayersDir = WorldLotteryPaths.playersDir();
                Path mailboxDir = com.habitrain.lottery.storage.MetaFeaturePaths.mailDir();
                Object profileCache = server.getProfileCache();
                boolean submitted = MailDispatchExecutor.submit(() -> {
                    Map<UUID, String> allRecipients = new LinkedHashMap<>(seedRecipients);
                    try {
                        collectDiskKnownPlayers(playerDataDir, lotteryPlayersDir, profileCache, allRecipients);
                    } catch (RuntimeException failure) {
                        HabiLotteryMod.LOGGER.error("Unable to collect all mail recipients on background thread", failure);
                        server.execute(() -> {
                            ServerPlayer senderPlayer = server.getPlayerList().getPlayer(senderUuid);
                            if (senderPlayer != null && !senderPlayer.hasDisconnected()) {
                                senderPlayer.sendSystemMessage(
                                        Component.translatable("message.habitrain_lottery.mail.compose.recipients_failed"));
                            }
                        });
                        return;
                    }
                    DispatchSummary summary = writeMailBatch(allRecipients, 0, finalDraft, mailboxDir);
                    server.execute(() -> notifyBatchFinished(server, senderUuid, finalDraft, summary));
                });
                if (!submitted) {
                    player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.compose.busy"));
                }
            }
            case MODE_OFFLINE_NAME -> {
                boolean onlineMode = server.usesAuthentication();
                List<String> uniqueNames = deduplicateTargets(payload.targets);
                Map<UUID, String> resolved = new LinkedHashMap<>();
                int preFail = 0;
                for (String name : uniqueNames) {
                    ServerPlayer online = server.getPlayerList().getPlayerByName(name);
                    if (online != null) {
                        resolved.putIfAbsent(online.getUUID(), online.getGameProfile().getName());
                        continue;
                    }
                    // Pure in-memory lookup: NEVER invoke GameProfileCache#get(String) on the Server Thread!
                    Optional<UUID> cacheHit = ProfileCacheLookup.lookupCachedUuid(server, name);
                    Optional<UUID> uuid = resolveOfflineUuid(onlineMode, cacheHit, name);
                    if (uuid.isEmpty()) {
                        if (onlineMode) {
                            player.sendSystemMessage(Component.translatable(
                                    "message.habitrain_lottery.mail.compose.unresolved_online_mode", name));
                        } else {
                            player.sendSystemMessage(Component.translatable(
                                    "message.habitrain_lottery.mail.compose.unresolved_player", name));
                        }
                        preFail++;
                        continue;
                    }
                    resolved.putIfAbsent(uuid.get(), name);
                }
                if (resolved.isEmpty()) {
                    player.sendSystemMessage(Component.translatable(
                            "message.habitrain_lottery.mail.compose.sent_summary", 0, preFail));
                    return;
                }
                int initialFailures = preFail;
                if (!dispatchResolvedBatch(resolved, initialFailures, finalDraft, server,
                        summary -> notifyBatchFinished(server, senderUuid, finalDraft, summary))) {
                    player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.compose.busy"));
                }
            }
            default -> {
                List<String> uniqueNames = deduplicateTargets(payload.targets);
                Map<UUID, String> resolved = new LinkedHashMap<>();
                int preFail = 0;
                for (String name : uniqueNames) {
                    ServerPlayer online = server.getPlayerList().getPlayerByName(name);
                    if (online != null) {
                        resolved.putIfAbsent(online.getUUID(), online.getGameProfile().getName());
                    } else {
                        preFail++;
                    }
                }
                if (resolved.isEmpty()) {
                    player.sendSystemMessage(Component.translatable(
                            "message.habitrain_lottery.mail.compose.sent_summary", 0, preFail));
                    return;
                }
                int initialFailures = preFail;
                if (!dispatchResolvedBatch(resolved, initialFailures, finalDraft, server,
                        summary -> notifyBatchFinished(server, senderUuid, finalDraft, summary))) {
                    player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.compose.busy"));
                }
            }
        }
    }

    /**
     * Package-visible & unit-testable helper: dispatches resolved {@code uuid -> nameHint} mailbox
     * writes on {@link MailDispatchExecutor} and posts {@link DispatchSummary} onto {@code callbackExecutor}.
     */
    static boolean dispatchResolvedBatch(
            Map<UUID, String> recipients,
            int initialFailures,
            MailDraft draft,
            Executor callbackExecutor,
            Consumer<DispatchSummary> onComplete
    ) {
        Map<UUID, String> snapshot = new LinkedHashMap<>(recipients);
        Path mailboxDir = com.habitrain.lottery.storage.MetaFeaturePaths.mailDir();
        return MailDispatchExecutor.submit(() -> {
            DispatchSummary summary = writeMailBatch(snapshot, initialFailures, draft, mailboxDir);
            callbackExecutor.execute(() -> onComplete.accept(summary));
        });
    }

    private static DispatchSummary writeMailBatch(
            Map<UUID, String> recipients,
            int initialFailures,
            MailDraft draft,
            Path mailboxDir
    ) {
        List<UUID> succeeded = new ArrayList<>(recipients.size());
        int fail = Math.max(0, initialFailures);
        for (Map.Entry<UUID, String> entry : recipients.entrySet()) {
            if (Thread.currentThread().isInterrupted()) {
                fail = Math.max(0, initialFailures) + recipients.size() - succeeded.size();
                break;
            }
            UUID uuid = entry.getKey();
            if (uuid == null) {
                fail++;
                continue;
            }
            if (mailboxDir != null && MailService.sendOfflineTo(
                    WorldLotteryPaths.resolveChildFile(mailboxDir, uuid + ".json"), uuid, entry.getValue(), draft)) {
                succeeded.add(uuid);
            } else {
                fail++;
            }
        }
        return new DispatchSummary(Collections.unmodifiableList(succeeded), succeeded.size(), fail);
    }

    private static void notifyBatchFinished(
            MinecraftServer server,
            UUID senderUuid,
            MailDraft draft,
            DispatchSummary summary
    ) {
        var playerList = server.getPlayerList();
        if (playerList == null) {
            return;
        }
        Component recipientNotice = Component.translatable(
                "message.habitrain_lottery.mail.received", draft.title());
        for (UUID recipientId : summary.succeededRecipients()) {
            ServerPlayer online = playerList.getPlayer(recipientId);
            if (online != null && !online.hasDisconnected()) {
                online.sendSystemMessage(recipientNotice);
                LotteryNetwork.sendMailboxList(online);
            }
        }
        ServerPlayer sender = playerList.getPlayer(senderUuid);
        if (sender != null && !sender.hasDisconnected()) {
            sender.sendSystemMessage(Component.translatable(
                    "message.habitrain_lottery.mail.compose.sent_summary",
                    summary.okCount(), summary.failCount()));
        }
    }

    /**
     * Snapshots online players and whitelist entries on the Server Thread using purely in-memory
     * structures (never invoking blocking Mojang HTTPS requests).
     */
    private static Map<UUID, String> snapshotOnlineAndWhitelistedPlayers(MinecraftServer server) {
        Map<UUID, String> players = new LinkedHashMap<>();
        boolean onlineMode = server.usesAuthentication();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online != null && online.getUUID() != null) {
                String name = online.getGameProfile().getName();
                players.putIfAbsent(online.getUUID(),
                        name == null || name.isBlank() ? shortUuid(online.getUUID()) : name);
            }
        }
        try {
            for (var entry : server.getPlayerList().getWhiteList().getEntries()) {
                GameProfile profile = (GameProfile) ((StoredUserEntryAccessor) entry).habitrain$getUser();
                if (profile == null) {
                    continue;
                }
                UUID id = profile.getId();
                String name = profile.getName();
                if (id == null && name != null) {
                    id = resolveOfflineUuid(onlineMode,
                            ProfileCacheLookup.lookupCachedUuid(server, name), name).orElse(null);
                }
                if (id == null) {
                    continue;
                }
                players.putIfAbsent(id, name == null || name.isBlank() ? shortUuid(id) : name);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("白名单读取失败，全员发送已取消", t);
        }
        return players;
    }

    /**
     * Scans {@code world/playerdata} (and {@code habitrain_lottery/players}) on the background
     * dispatch thread so directory listing and UUID discovery never stall the Server Thread.
     */
    private static void collectDiskKnownPlayers(
            Path playerDataDir,
            Path lotteryPlayersDir,
            Object profileCache,
            Map<UUID, String> players
    ) {
        try {
            if (playerDataDir != null && Files.isDirectory(playerDataDir)) {
                try (var stream = Files.list(playerDataDir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".dat")).forEach(p -> {
                        String fileName = p.getFileName().toString();
                        try {
                            UUID id = UUID.fromString(fileName.substring(0, fileName.length() - 4));
                            players.putIfAbsent(id, resolveCachedName(profileCache, id));
                        } catch (IllegalArgumentException ignored) {
                        }
                    });
                }
            }
            if (lotteryPlayersDir != null && Files.isDirectory(lotteryPlayersDir)) {
                try (var stream = Files.list(lotteryPlayersDir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                        String fileName = p.getFileName().toString();
                        if (fileName.length() != 41) {
                            return;
                        }
                        try {
                            UUID id = UUID.fromString(fileName.substring(0, 36));
                            players.putIfAbsent(id, resolveCachedName(profileCache, id));
                        } catch (IllegalArgumentException ignored) {
                        }
                    });
                }
            }
        } catch (Throwable t) {
            throw new IllegalStateException("玩家存档目录读取失败，全员发送已取消", t);
        }
    }

    private static String resolveCachedName(Object profileCache, UUID id) {
        return ProfileCacheLookup.lookupCachedNameFromCache(profileCache, id)
                .orElseGet(() -> shortUuid(id));
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
}
