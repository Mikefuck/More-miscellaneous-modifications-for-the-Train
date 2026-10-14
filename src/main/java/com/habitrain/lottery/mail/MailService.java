package com.habitrain.lottery.mail;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.mail.LocalMailboxStore.MailJson;
import com.habitrain.lottery.mail.LocalMailboxStore.MailLoad;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.backpack.BackpackManager;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * World-local mailbox service. Each operation holds one reentrant recipient monitor.
 * New claims use RewardDelivery intents and per-store receipts. Legacy in-flight markers
 * lack those receipts and remain locked for manual reconciliation.
 */
public final class MailService {
    /**
     * Reentrant recipient monitor; one entry per player UUID for the server lifetime.
     */
    private static final ConcurrentHashMap<UUID, Object> MAILBOX_LOCKS = new ConcurrentHashMap<>();

    /** In-flight claim marker token prefix. Deliberately not a {@code hltmail:v1:} token. */
    static final String CLAIMING_PREFIX = "hltclaim:v1:";

    /** Marker for a claim whose compensation failed: the mail stays locked on purpose. */
    static final String CLAIM_FAILED_PREFIX = "hltclaimfailed:v1:";

    /** Deduplicates the "unresolved claim" ERROR log, which is re-checked on every load. */
    private static final java.util.Set<String> LOGGED_UNRESOLVED_CLAIMS = ConcurrentHashMap.newKeySet();

    private MailService() {
    }

    private static Object mailboxLock(UUID uuid) {
        return MAILBOX_LOCKS.computeIfAbsent(uuid, id -> new Object());
    }

    /**
     * Loads the mailbox and reconciles interrupted claims before anybody uses it.
     *
     * <p>The {@link MailLoad} returned by the store exposes the live list it parsed, so
     * reconciling {@code loaded.mails()} in place also changes what the caller sees.
     *
     * @return the loaded mailbox; {@code corrupt} loads are returned untouched
     */
    private static MailLoad loadReconciled(UUID uuid) {
        MailLoad loaded = LocalMailboxStore.loadWithStatus(uuid);
        List<MailJson> mails = loaded.mails();
        if (loaded.corrupt() || mails == null) {
            return loaded;
        }
        if (reconcileClaimMarkers(uuid, mails) && !LocalMailboxStore.save(uuid, mails)) {
            HabiLotteryMod.LOGGER.error(
                    "Failed persisting the reconciled mailbox of {}; the interrupted claim will be "
                            + "reconciled again on the next load", uuid);
        }
        return loaded;
    }

    /** Old markers cannot prove which stores committed; never automatically reopen them. */
    static boolean reconcileClaimMarkers(UUID uuid, List<MailJson> list) {
        if (list == null) return false;
        boolean changed = false;
        for (MailJson mail : list) {
            if (mail == null || !hasPrefix(mail, CLAIM_FAILED_PREFIX) && !hasPrefix(mail, CLAIMING_PREFIX)) continue;
            if (!mail.claimed) { mail.claimed = true; changed = true; }
            if (LOGGED_UNRESOLVED_CLAIMS.add(uuid + "/" + mail.id))
                HabiLotteryMod.LOGGER.error("Legacy interrupted mail {} for {} remains locked; check assets before manual recovery",
                        mail.id, uuid);
        }
        return changed;
    }

    /** Adds the durable in-flight marker. {@code claimed} is deliberately left untouched. */
    static void markClaiming(MailJson mail) {
        setClaimMarker(mail, CLAIMING_PREFIX);
    }

    /** Marks a claim whose compensation failed, so a restart keeps it locked. */
    static void markClaimFailed(MailJson mail) {
        setClaimMarker(mail, CLAIM_FAILED_PREFIX);
    }

    private static void setClaimMarker(MailJson mail, String prefix) {
        if (mail == null || mail.commands == null) {
            return;
        }
        clearClaimMarkers(mail);
        mail.commands.add(prefix + (mail.id == null ? "" : mail.id));
    }

    /** Removes every claim marker; reward tokens are untouched. */
    static void clearClaimMarkers(MailJson mail) {
        if (mail == null || mail.commands == null) {
            return;
        }
        mail.commands.removeIf(MailService::isClaimMarker);
    }

    /** True for both marker kinds; {@code hltmail:v1:} reward tokens are never markers. */
    static boolean isClaimMarker(String token) {
        return token != null && (token.startsWith(CLAIMING_PREFIX) || token.startsWith(CLAIM_FAILED_PREFIX));
    }

    static boolean hasClaimingMarker(MailJson mail) {
        return hasPrefix(mail, CLAIMING_PREFIX);
    }

    private static boolean hasPrefix(MailJson mail, String prefix) {
        if (mail == null || mail.commands == null || prefix == null) {
            return false;
        }
        for (String token : mail.commands) {
            if (token != null && token.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean send(ServerPlayer target, MailDraft draft) {
        if (target == null || draft == null || !WorldLotteryPaths.ready() || !publishedRewards(draft)) {
            return false;
        }
        UUID uuid = target.getUUID();
        boolean queued;
        synchronized (mailboxLock(uuid)) {
            try {
                MailLoad loaded = loadReconciled(uuid);
                if (loaded.corrupt()) {
                    HabiLotteryMod.LOGGER.error("Refusing to send mail over corrupt mailbox {}", uuid);
                    return false;
                }
                List<MailJson> list = loaded.mails() != null ? loaded.mails() : new ArrayList<>();
                list.add(LocalMailboxStore.fromDraft(draft));
                queued = LocalMailboxStore.save(uuid, list);
            } catch (Exception e) {
                HabiLotteryMod.LOGGER.error("send mail to {} failed", target.getGameProfile().getName(), e);
                return false;
            }
        }
        if (!queued) {
            return false;
        }
        target.sendSystemMessage(Component.literal("§a[邮箱] 你收到一封新邮件：" + draft.title()));
        return true;
    }

    /** A stable ID deduplicates retries even after the letter has been claimed. */
    public static boolean sendOnce(UUID uuid, String key, MailDraft draft) {
        if (uuid == null || draft == null || !WorldLotteryPaths.ready()) return false;
        synchronized (mailboxLock(uuid)) {
            MailLoad loaded = loadReconciled(uuid);
            if (loaded.corrupt()) return false;
            List<MailJson> list = loaded.mails() != null ? loaded.mails() : new ArrayList<>();
            String id = RewardDelivery.id(key);
            for (MailJson mail : list) if (mail != null && id.equals(mail.id)) return true;
            if (!publishedRewards(draft)) return false;
            MailJson mail = LocalMailboxStore.fromDraft(draft);
            mail.id = id;
            list.add(mail);
            return LocalMailboxStore.save(uuid, list);
        }
    }

    public static boolean sendOffline(UUID uuid, String nameHint, MailDraft draft) {
        if (uuid == null || draft == null || !WorldLotteryPaths.ready() || !publishedRewards(draft)) {
            return false;
        }
        synchronized (mailboxLock(uuid)) {
            try {
                MailLoad loaded = loadReconciled(uuid);
                if (loaded.corrupt()) {
                    HabiLotteryMod.LOGGER.error("Refusing to send offline mail over corrupt mailbox {}", uuid);
                    return false;
                }
                List<MailJson> list = loaded.mails() != null ? loaded.mails() : new ArrayList<>();
                list.add(LocalMailboxStore.fromDraft(draft));
                if (!LocalMailboxStore.save(uuid, list)) {
                    return false;
                }
                HabiLotteryMod.LOGGER.info("Queued offline mail for {} ({})", nameHint, uuid);
                return true;
            } catch (Exception e) {
                HabiLotteryMod.LOGGER.error("offline mail to {} failed", uuid, e);
                return false;
            }
        }
    }

    private static boolean publishedRewards(MailDraft draft) {
        for (MailReward reward : draft.rewards()) {
            if (reward == null || reward.kind() != MailReward.Kind.CRATE && reward.kind() != MailReward.Kind.KEY)
                continue;
            CrateCatalog.Entry entry = CrateCatalog.find(reward.factionType());
            if (entry == null || entry.archived()) return false;
        }
        return true;
    }

    /**
     * Claims a single mail and applies its rewards exactly once. Idempotent:
     * the claimed flag is re-read from disk before every claim.
     */
    public static boolean claim(ServerPlayer player, String mailId) {
        return claimInternal(player, mailId, true);
    }

    /**
     * Claims all unclaimed, unexpired mails using the same per-mail transaction
     * as {@link #claim}. The C2S handler uses an empty ID or * for this operation.
     *
     * <p>Holds the player's mailbox monitor for the whole sweep (the lock is reentrant, so
     * the nested {@link #claimInternal} calls re-enter it); the id snapshot therefore cannot
     * go stale against a concurrent send.
     */
    public static int claimAll(ServerPlayer player) {
        if (player == null || !WorldLotteryPaths.ready()) {
            return 0;
        }
        if (PlayerStateGate.spectatorRestOrDead(player)) {
            player.sendSystemMessage(Component.literal(PlayerStateGate.MAIL_BLOCKED));
            return 0;
        }
        UUID uuid = player.getUUID();
        synchronized (mailboxLock(uuid)) {
            MailLoad loaded = loadReconciled(uuid);
            if (loaded.corrupt()) {
                HabiLotteryMod.LOGGER.error("Refusing claimAll on corrupt mailbox {}", uuid);
                player.sendSystemMessage(Component.literal("§c[邮箱] 邮箱数据损坏，无法领取"));
                return 0;
            }
            List<String> ids = new ArrayList<>();
            List<MailJson> list = loaded.mails() != null ? loaded.mails() : List.of();
            for (MailJson m : list) {
                if (m == null || m.claimed || hasClaimingMarker(m) || LocalMailboxStore.isExpired(m)
                        || m.id == null || m.id.isBlank()) {
                    continue;
                }
                ids.add(m.id);
            }
            if (ids.isEmpty()) {
                player.sendSystemMessage(Component.literal("§e[邮箱] 没有可领取的邮件"));
                return 0;
            }
            int count = 0;
            for (String id : ids) {
                if (claimInternal(player, id, false)) {
                    count++;
                }
            }
            if (count > 0) {
                player.sendSystemMessage(Component.literal("§a[邮箱] 已领取 " + count + " 封邮件"));
            } else {
                player.sendSystemMessage(Component.literal("§e[邮箱] 没有可领取的邮件"));
            }
            return count;
        }
    }

    /**
     * Returns the non-expired inbox, marking everything read (unread badge
     * source). Persists when the read flags changed. Corrupt files are not
     * overwritten.
     *
     * <p>Takes the caller's mailbox monitor (see the class javadoc): it can persist read
     * flags, so it must not race a concurrent claim of the same player.
     */
    public static List<MailJson> list(ServerPlayer player) {
        if (player == null || !WorldLotteryPaths.ready()) {
            return new ArrayList<>();
        }
        UUID uuid = player.getUUID();
        synchronized (mailboxLock(uuid)) {
            MailLoad loaded = loadReconciled(uuid);
            if (loaded.corrupt()) {
                HabiLotteryMod.LOGGER.error("Refusing list/save on corrupt mailbox {}", uuid);
                return new ArrayList<>();
            }
            List<MailJson> list = loaded.mails() != null ? loaded.mails() : new ArrayList<>();
            List<MailJson> out = new ArrayList<>();
            boolean changed = false;
            for (MailJson m : list) {
                if (m == null) {
                    continue;
                }
                if (LocalMailboxStore.isExpired(m)) {
                    continue;
                }
                if (!m.read) {
                    m.read = true;
                    changed = true;
                }
                out.add(m);
            }
            if (changed && !LocalMailboxStore.save(uuid, list)) {
                HabiLotteryMod.LOGGER.error("Failed persisting mailbox read flags for {}", uuid);
            }
            return out;
        }
    }

    static boolean claimInternal(ServerPlayer player, String mailId, boolean announce) {
        if (player == null || mailId == null || mailId.isBlank() || !WorldLotteryPaths.ready()) {
            return false;
        }
        if (PlayerStateGate.spectatorRestOrDead(player)) {
            if (announce) {
                player.sendSystemMessage(Component.literal(PlayerStateGate.MAIL_BLOCKED));
            }
            return false;
        }
        synchronized (mailboxLock(player.getUUID())) {
            return claimLocked(player, mailId, announce);
        }
    }

    /** The claim transaction; always called with the player's mailbox monitor held. */
    private static boolean claimLocked(ServerPlayer player, String mailId, boolean announce) {
        UUID uuid = player.getUUID();
        MailLoad loaded = loadReconciled(uuid);
        if (loaded.corrupt()) return false;
        List<MailJson> list = loaded.mails() != null ? loaded.mails() : new ArrayList<>();
        MailJson target = findClaimable(list, mailId);
        if (target == null) {
            if (announce) player.sendSystemMessage(Component.literal("§e[邮箱] 该邮件已领取、已过期或等待核对"));
            return false;
        }
        List<MailReward> rewards;
        try {
            rewards = MailCommandsCodec.decodeStrict(target.commands);
        } catch (IllegalArgumentException invalid) {
            player.sendSystemMessage(Component.literal("§c[邮箱] 附件暂时无法解析，请联系管理员；邮件已保留"));
            return false;
        }
        if (refuseFactionCardInGame(rewards, isSreGameActive(player))) {
            player.sendSystemMessage(Component.literal("§c[邮箱] 局中不能领取阵营卡、自选卡或突破上限卡"));
            return false;
        }
        boolean delivered = RewardDelivery.grant(player, "mail:" + mailId,
                () -> new RewardDelivery.Plan(rewards, List.of(), null));
        if (!delivered || !finishClaim(target, true, () -> LocalMailboxStore.save(uuid, list))) {
            player.sendSystemMessage(Component.literal("§e[邮箱] 领取尚未完成，请稍后重试；已到账附件不会重复发放"));
            return false;
        }
        if (announce) player.sendSystemMessage(Component.literal("§a[邮箱] 已领取：" + target.title));
        return true;
    }

    static MailJson findClaimable(List<MailJson> list, String mailId) {
        if (list == null || mailId == null) {
            return null;
        }
        for (MailJson m : list) {
            // A mail carrying an in-flight marker is mid-claim: never hand it out twice.
            // (Mailboxes are reconciled on load, so this is defence in depth for a list
            // that was obtained without going through loadReconciled.)
            if (m == null || m.claimed || hasClaimingMarker(m) || LocalMailboxStore.isExpired(m)) {
                continue;
            }
            if (mailId.equals(m.id)) {
                return m;
            }
        }
        return null;
    }

    static void markClaimed(MailJson mail) {
        if (mail == null) {
            return;
        }
        mail.claimed = true;
        mail.read = true;
    }

    static void unmarkClaimed(MailJson mail, boolean previousRead) {
        if (mail == null) {
            return;
        }
        mail.claimed = false;
        mail.read = previousRead;
    }

    /**
     * Publish claimed only after delivery. A failed final write is retried through delivery receipts.
     */
    static boolean finishClaim(MailJson mail, boolean rewardsOk, BooleanSupplier persist) {
        if (mail == null || !rewardsOk) {
            if (mail != null) {
                mail.claimed = false;
            }
            return false;
        }
        mail.claimed = true;
        mail.read = true;
        if (persist == null || !persist.getAsBoolean()) {
            mail.claimed = false;
            return false;
        }
        return true;
    }

    static boolean containsFactionCard(List<MailReward> rewards) {
        if (rewards == null) {
            return false;
        }
        for (MailReward r : rewards) {
            if (r != null && (r.kind() == MailReward.Kind.SELF_SELECT_CARD
                    || r.kind() == MailReward.Kind.LIMIT_BREAK_CARD) && r.amount() != 0) return true;
            if (r == null || r.kind() != MailReward.Kind.FACTION_CARD || r.amount() == 0) {
                continue;
            }
            FactionCardType type = FactionCardType.fromString(r.factionType());
            if (type != FactionCardType.NONE) {
                return true;
            }
        }
        return false;
    }

    static boolean refuseFactionCardInGame(List<MailReward> rewards, boolean gameActive) {
        return gameActive && containsFactionCard(rewards);
    }

    public static boolean applyRewards(ServerPlayer player, List<MailReward> rewards) {
        try {
            applyRewardsStrict(player, rewards);
            return true;
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("apply rewards failed for {}",
                    player == null ? "?" : player.getGameProfile().getName(), e);
            return false;
        }
    }

    /**
     * Credits rewards directly (no mailbox) with the same all-or-nothing guarantee a mail claim
     * has: every store is snapshotted first and restored if any reward cannot be persisted.
     * Used by daily-task claims; the caller decides whether cards are allowed right now.
     */
    public static boolean grantTransactional(ServerPlayer player, List<MailReward> rewards) {
        return grantTransactional(player, rewards, "[每日任务]");
    }

    /** As {@link #grantTransactional(ServerPlayer, List)}, with {@code tag} prefixing the chat receipts. */
    public static boolean grantTransactional(ServerPlayer player, List<MailReward> rewards, String tag) {
        if (player == null || rewards == null || !WorldLotteryPaths.ready()) return false;
        RewardSnapshot snap;
        try {
            snap = snapshotRewards(player);
        } catch (RuntimeException failure) {
            HabiLotteryMod.LOGGER.error("Cannot snapshot rewards for {}", player.getUUID(), failure);
            return false;
        }
        try {
            applyRewardsStrict(player, rewards, tag);
            return true;
        } catch (RuntimeException failure) {
            HabiLotteryMod.LOGGER.error("Direct reward grant failed for {}; rolling back", player.getUUID(), failure);
            if (!restoreRewards(player, snap)) {
                HabiLotteryMod.LOGGER.error("Direct reward rollback failed for {}; manual review required", player.getUUID());
            }
            return false;
        }
    }

    /** True when {@code rewards} contain cards that cannot be claimed while a match is running. */
    public static boolean blockedDuringMatch(ServerPlayer player, List<MailReward> rewards) {
        return refuseFactionCardInGame(rewards, isSreGameActive(player));
    }

    private static void applyRewardsStrict(ServerPlayer player, List<MailReward> rewards) {
        applyRewardsStrict(player, rewards, "[邮箱]");
    }

    private static void applyRewardsStrict(ServerPlayer player, List<MailReward> rewards, String tag) {
        if (player == null) {
            throw new IllegalArgumentException("player");
        }
        if (rewards == null || rewards.isEmpty()) {
            return;
        }
        for (MailReward reward : rewards) {
            if (reward != null && reward.kind() == MailReward.Kind.SKIN
                    && com.habitrain.lottery.api.skin.HabiSkinApi.fromEntry(reward.factionType()).isEmpty())
                throw new IllegalArgumentException("Skin provider unavailable: " + reward.factionType());
        }
        UUID uuid = player.getUUID();
        long greenAppleDelta = 0;
        List<CardDelta> cards = new ArrayList<>();
        Map<String, Integer> systemItemDeltas = new LinkedHashMap<>();
        List<Component> messages = new ArrayList<>();
        for (MailReward r : rewards) {
            if (r == null || r.amount() == 0) {
                continue;
            }
            switch (r.kind()) {
                case SKIN -> {
                    var reward = MailReward.skinEntry(r.factionType());
                    var skin = com.habitrain.lottery.api.skin.HabiSkinApi.fromEntry(reward.factionType())
                            .orElseThrow(() -> new IllegalArgumentException("Skin provider unavailable: " + reward.factionType()));
                    if (!PlayerLotteryStore.get().awardSkin(uuid, skin.type(), skin.id(), Math.max(1, r.amount())))
                        throw new IllegalStateException("Skin reward could not be persisted");
                    messages.add(Component.literal("§a" + tag + " 已解锁皮肤 " + reward.factionType()));
                }
                case GREEN_APPLES -> {
                    greenAppleDelta += r.amount();
                    messages.add(Component.literal(
                            (r.amount() >= 0 ? "§a" : "§e") + tag + " 绿苹果 "
                                    + (r.amount() >= 0 ? "+" : "") + r.amount()));
                }
                case FACTION_CARD -> {
                    FactionCardType type = FactionCardType.fromString(r.factionType());
                    if (type != FactionCardType.NONE) {
                        cards.add(new CardDelta(type, r.amount()));
                        messages.add(Component.literal(
                                "§a" + tag + " 获得阵营卡 " + type.questKey + " x" + r.amount()));
                    }
                }
                case SELF_SELECT_CARD -> {
                    // Atomic delta add: read-modify-write inside one file write, so a
                    // self-select reward never overwrites a concurrently changed balance.
                    if (!LocalBackpackStore.addSelfSelectCards(uuid, r.amount())) {
                        throw new IllegalStateException("Self-select reward could not be persisted");
                    }
                    messages.add(Component.literal("§a" + tag + " 获得自选卡 x" + r.amount()));
                }
                case LIMIT_BREAK_CARD -> {
                    if (!LocalBackpackStore.addLimitBreakCards(uuid, r.amount())) {
                        throw new IllegalStateException("Limit-break reward could not be persisted");
                    }
                    messages.add(Component.translatable("message.habitrain_lottery.mail.limit_break", r.amount()));
                }
                case CRATE, KEY -> {
                    CrateCatalog.Entry entry = CrateCatalog.find(r.factionType());
                    if (entry == null) {
                        throw new IllegalArgumentException("Unknown crate attachment: " + r.factionType());
                    }
                    String itemId = r.kind() == MailReward.Kind.CRATE
                            ? entry.crateItemId() : entry.keyItemId();
                    systemItemDeltas.merge(itemId, r.amount(), Integer::sum);
                    messages.add(Component.literal("§a" + tag + " 获得 "
                            + (r.kind() == MailReward.Kind.CRATE ? "箱子 " : "钥匙 ")
                            + entry.id() + " x" + r.amount()));
                }
            }
        }
        if (!systemItemDeltas.isEmpty()) {
            Map<String, Integer> deltas = Map.copyOf(systemItemDeltas);
            if (!PlayerLotteryStore.get().applyToPlayerWithRollback(uuid, data -> {
                if (data.systemItems == null) data.systemItems = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> delta : deltas.entrySet()) {
                    if (!com.habitrain.lottery.warehouse.SystemItemBalances.change(
                            data.systemItems, delta.getKey(), delta.getValue())) {
                        throw new IllegalStateException("System item reward could not be persisted: " + delta.getKey());
                    }
                }
            })) {
                throw new IllegalStateException("Mail crate/key reward could not be persisted");
            }
        }
        if (greenAppleDelta != 0) {
            long delta = greenAppleDelta;
            PlayerLotteryStore.get().update(uuid, data -> data.greenApples = (int) Math.max(0L,
                    Math.min(Integer.MAX_VALUE, (long) data.greenApples + delta)));
            if (PlayerLotteryStore.get().isLoadFailed(uuid) || !PlayerLotteryStore.get().flush(uuid)) {
                throw new IllegalStateException("Mail green-apple reward could not be persisted");
            }
        }
        for (CardDelta card : cards) {
            BackpackManager.addCard(player, card.type, card.amount);
        }
        if (!cards.isEmpty() && !LocalBackpackStore.saveFromEnumMap(uuid, BackpackManager.getCards(player))) {
            throw new IllegalStateException("Faction-card reward could not be persisted");
        }
        com.habitrain.lottery.skin.SkinNetwork.sync(player);
        for (Component msg : messages) {
            player.sendSystemMessage(msg);
        }
    }

    private static RewardSnapshot snapshotRewards(ServerPlayer player) {
        PlayerLotteryData data = PlayerLotteryStore.get().getOrLoad(player);
        var backpack = LocalBackpackStore.loadResult(player.getUUID());
        if (PlayerLotteryStore.get().isLoadFailed(player.getUUID()) || backpack.corrupt()) {
            throw new IllegalStateException("Cannot claim mail over unreadable player storage");
        }
        Map<FactionCardType, Integer> cards = new EnumMap<>(FactionCardType.class);
        cards.putAll(BackpackManager.getCards(player));
        return new RewardSnapshot(data.greenApples, cards,
                backpack.selfSelectCards(), backpack.limitBreakCards(), data.copy());
    }

    private static boolean restoreRewards(ServerPlayer player, RewardSnapshot snap) {
        if (player == null || snap == null) {
            return false;
        }
        UUID uuid = player.getUUID();
        return MailClaimTransaction.restoreAll(() -> {
            if (!LocalBackpackStore.setSelfSelectCards(uuid, snap.selfSelectCards)) {
                throw new IllegalStateException("Self-select reward rollback could not be persisted");
            }
        }, () -> {
            if (!LocalBackpackStore.setLimitBreakCards(uuid, snap.limitBreakCards)) {
                throw new IllegalStateException("Limit-break reward rollback could not be persisted");
            }
        }, () -> {
            PlayerLotteryStore.get().update(uuid, data -> {
                data.greenApples = Math.max(0, snap.greenApples);
                data.systemItems = snap.skins.copy().systemItems;
                data.unlocked = snap.skins.copy().unlocked;
                data.ownedSkinCounts = snap.skins.copy().ownedSkinCounts;
                data.equipped = snap.skins.copy().equipped;
            });
            if (PlayerLotteryStore.get().isLoadFailed(uuid) || !PlayerLotteryStore.get().flush(uuid)) {
                throw new IllegalStateException("Mail asset rollback could not be persisted");
            }
            com.habitrain.lottery.bridge.SkinStateCoordinator.reassertPlayer(player, "mail_rollback");
        }, () -> {
            Map<FactionCardType, Integer> now = BackpackManager.getCards(player);
            for (FactionCardType type : FactionCardType.values()) {
                if (type == FactionCardType.NONE) {
                    continue;
                }
                int want = snap.cards.getOrDefault(type, 0);
                int have = now == null ? 0 : now.getOrDefault(type, 0);
                int delta = want - have;
                if (delta != 0) {
                    BackpackManager.addCard(player, type, delta);
                }
            }
            if (!LocalBackpackStore.saveFromEnumMap(uuid, BackpackManager.getCards(player))) {
                throw new IllegalStateException("Faction-card rollback could not be persisted");
            }
        });
    }

    static boolean isSreGameActive(ServerPlayer player) {
        try {
            if (player == null || player.level() == null) {
                return false;
            }
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(player.level());
            if (game == null) {
                return false;
            }
            SREGameWorldComponent.GameStatus status = game.getGameStatus();
            return status != null && status != SREGameWorldComponent.GameStatus.INACTIVE;
        } catch (Exception t) {
            return false;
        }
    }

    private record CardDelta(FactionCardType type, int amount) {
    }

    private record RewardSnapshot(int greenApples, Map<FactionCardType, Integer> cards,
                                  int selfSelectCards, int limitBreakCards, PlayerLotteryData skins) {
    }
}
