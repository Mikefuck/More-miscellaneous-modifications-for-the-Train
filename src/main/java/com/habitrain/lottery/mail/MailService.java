package com.habitrain.lottery.mail;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.card.ServerMatchGate;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.mail.LocalMailboxStore.MailJson;
import com.habitrain.lottery.mail.LocalMailboxStore.MailLoad;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.backpack.BackpackManager;
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

    static Object mailboxLock(UUID uuid) {
        return MAILBOX_LOCKS.computeIfAbsent(uuid, id -> new Object());
    }

    /** Clears per-player monitors and log dedupe state on server shutdown. */
    public static void onServerStopping() {
        MAILBOX_LOCKS.clear();
        LOGGED_UNRESOLVED_CLAIMS.clear();
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
        if (reconcileClaimMarkers(uuid, mails) && !LocalMailboxStore.save(uuid, mails, !loaded.usedBackup())) {
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

    static boolean hasClaimFailedMarker(MailJson mail) {
        return hasPrefix(mail, CLAIM_FAILED_PREFIX);
    }

    /**
     * Marks a recovered {@code mail:<id>} delivery as {@code claimed = true, read = true} on disk
     * under the recipient's mailbox monitor so mailbox state never split-brains after recovery.
     */
    static boolean markClaimedFromRecovery(UUID uuid, String mailId) {
        if (uuid == null || mailId == null || mailId.isBlank() || !WorldLotteryPaths.ready()) {
            return false;
        }
        synchronized (mailboxLock(uuid)) {
            MailLoad loaded = loadReconciled(uuid);
            if (loaded.corrupt()) {
                return false;
            }
            List<MailJson> list = loaded.mails() != null ? loaded.mails() : new ArrayList<>();
            MailJson found = null;
            for (MailJson m : list) {
                if (m != null && mailId.equals(m.id)) {
                    found = m;
                    break;
                }
            }
            if (found == null) {
                return true;
            }
            if (found.claimed && found.read && !hasClaimingMarker(found)) {
                return true;
            }
            found.claimed = true;
            found.read = true;
            clearClaimMarkers(found);
            return LocalMailboxStore.save(uuid, list, !loaded.usedBackup());
        }
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
                queued = LocalMailboxStore.save(uuid, list, !loaded.usedBackup());
            } catch (Exception e) {
                HabiLotteryMod.LOGGER.error("send mail to {} failed", target.getGameProfile().getName(), e);
                return false;
            }
        }
        if (!queued) {
            return false;
        }
        target.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.received", draft.title()));
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
            return LocalMailboxStore.save(uuid, list, !loaded.usedBackup());
        }
    }

    public static boolean sendOffline(UUID uuid, String nameHint, MailDraft draft) {
        if (uuid == null || draft == null || !WorldLotteryPaths.ready() || !publishedRewards(draft)) {
            return false;
        }
        return sendOfflineTo(com.habitrain.lottery.storage.MetaFeaturePaths.mailPlayer(uuid), uuid, nameHint, draft);
    }

    /** Append-only background delivery bound to the originating world's immutable path. */
    public static boolean sendOfflineTo(java.nio.file.Path mailbox, UUID uuid, String nameHint, MailDraft draft) {
        if (mailbox == null || uuid == null || draft == null) return false;
        synchronized (mailboxLock(uuid)) {
            try {
                // Appending must not reconcile player asset receipts on this I/O thread.
                MailLoad loaded = LocalMailboxStore.loadFrom(mailbox);
                if (loaded.corrupt()) {
                    HabiLotteryMod.LOGGER.error("Refusing to send offline mail over corrupt mailbox {}", uuid);
                    return false;
                }
                List<MailJson> list = loaded.mails() != null ? loaded.mails() : new ArrayList<>();
                list.add(LocalMailboxStore.fromDraft(draft));
                if (!LocalMailboxStore.saveTo(mailbox, list, !loaded.usedBackup())) {
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
            player.sendSystemMessage(PlayerStateGate.mailBlockedComponent());
            return 0;
        }
        UUID uuid = player.getUUID();
        synchronized (mailboxLock(uuid)) {
            MailLoad loaded = loadReconciled(uuid);
            if (loaded.corrupt()) {
                HabiLotteryMod.LOGGER.error("Refusing claimAll on corrupt mailbox {}", uuid);
                player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.corrupt"));
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
                player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.none_claimable"));
                return 0;
            }
            int count = 0;
            for (String id : ids) {
                if (claimInternal(player, id, false)) {
                    count++;
                }
            }
            if (count > 0) {
                player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.claimed_all", count));
            } else {
                player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.none_claimable"));
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
            if (changed && !LocalMailboxStore.save(uuid, list, !loaded.usedBackup())) {
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
                player.sendSystemMessage(PlayerStateGate.mailBlockedComponent());
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
            if (announce) player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.already_or_expired"));
            return false;
        }
        List<MailReward> rewards;
        try {
            rewards = MailCommandsCodec.decodeStrict(target.commands);
        } catch (IllegalArgumentException invalid) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.invalid_attachment"));
            return false;
        }
        if (refuseFactionCardInGame(rewards, isSreGameActive(player))) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.cards_blocked_in_match"));
            return false;
        }
        String deliveryKey = "mail:" + mailId;
        boolean delivered = RewardDelivery.grant(player, deliveryKey,
                () -> new RewardDelivery.Plan(rewards, List.of(), null));
        if (!delivered || !finishClaim(target, true, () -> LocalMailboxStore.save(uuid, list, !loaded.usedBackup()))) {
            player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.claim_incomplete"));
            return false;
        }
        RewardDelivery.cleanupCompleted(uuid, deliveryKey);
        if (announce) player.sendSystemMessage(Component.translatable("message.habitrain_lottery.mail.claimed_one", target.title));
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
        if (player == null || rewards == null || !WorldLotteryPaths.ready()
                || PlayerLotteryStore.get().isFlushDeferred()) return false;
        if (containsFactionCard(rewards) && !LocalBackpackStore.prepareOnline(player)) {
            return false;
        }
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
        } catch (Throwable failure) {
            HabiLotteryMod.LOGGER.error("Direct reward grant failed for {}; rolling back", player.getUUID(), failure);
            if (!restoreRewards(player, snap)) {
                HabiLotteryMod.LOGGER.error("Direct reward rollback failed for {}; manual review required", player.getUUID());
            }
            if (failure instanceof Error err) {
                throw err;
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
        if (PlayerLotteryStore.get().isFlushDeferred()) {
            throw new IllegalStateException("Player lottery store flush is deferred");
        }
        if (rewards == null || rewards.isEmpty()) {
            return;
        }
        boolean hasFactionCards = false;
        for (MailReward reward : rewards) {
            if (reward == null || reward.amount() == 0) {
                continue;
            }
            if (reward.kind() == MailReward.Kind.SKIN
                    && com.habitrain.lottery.api.skin.HabiSkinApi.fromEntry(reward.factionType()).isEmpty()) {
                throw new IllegalArgumentException("Skin provider unavailable: " + reward.factionType());
            }
            if ((reward.kind() == MailReward.Kind.CRATE || reward.kind() == MailReward.Kind.KEY)
                    && CrateCatalog.find(reward.factionType()) == null) {
                throw new IllegalArgumentException("Unknown crate attachment: " + reward.factionType());
            }
            if (reward.kind() == MailReward.Kind.FACTION_CARD
                    && FactionCardType.fromString(reward.factionType()) != FactionCardType.NONE) {
                hasFactionCards = true;
            }
        }
        if (hasFactionCards && !LocalBackpackStore.prepareOnline(player)) {
            throw new IllegalStateException("Backpack not ready");
        }
        UUID uuid = player.getUUID();
        long greenAppleDelta = 0L;
        for (MailReward r : rewards) {
            if (r != null && r.kind() == MailReward.Kind.GREEN_APPLES && r.amount() != 0) {
                greenAppleDelta = Math.addExact(greenAppleDelta, (long) r.amount());
            }
        }
        if (greenAppleDelta != 0L) {
            PlayerLotteryData current = PlayerLotteryStore.get().getOrLoad(player);
            if (PlayerLotteryStore.get().isLoadFailed(uuid)) {
                throw new IllegalStateException("Player lottery store unreadable");
            }
            long preview = Math.addExact((long) current.greenApples, greenAppleDelta);
            if (preview < 0L) {
                throw new IllegalStateException("Insufficient green apples");
            }
            if (preview > Integer.MAX_VALUE) {
                throw new IllegalStateException("Green apple balance overflow");
            }
        }
        List<CardDelta> cards = new ArrayList<>();
        Map<String, Integer> systemItemDeltas = new LinkedHashMap<>();
        Component tagComp = "[邮箱]".equals(tag)
                ? Component.translatable("message.habitrain_lottery.tag.mail")
                : "[每日任务]".equals(tag)
                        ? Component.translatable("message.habitrain_lottery.tag.daily_task")
                        : Component.literal(tag == null ? "" : tag);
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
                    messages.add(Component.translatable(
                            "message.habitrain_lottery.reward.skin_unlocked", tagComp, reward.factionType()));
                }
                case GREEN_APPLES -> {
                    messages.add(r.amount() >= 0
                            ? Component.translatable("message.habitrain_lottery.reward.green_apples_gained", tagComp, r.amount())
                            : Component.translatable("message.habitrain_lottery.reward.green_apples_spent", tagComp, r.amount()));
                }
                case FACTION_CARD -> {
                    FactionCardType type = FactionCardType.fromString(r.factionType());
                    if (type != FactionCardType.NONE) {
                        cards.add(new CardDelta(type, r.amount()));
                        messages.add(Component.translatable(
                                "message.habitrain_lottery.reward.faction_card",
                                tagComp, Component.translatable(type.displayName), r.amount()));
                    }
                }
                case SELF_SELECT_CARD -> {
                    // Atomic delta add: read-modify-write inside one file write, so a
                    // self-select reward never overwrites a concurrently changed balance.
                    if (!LocalBackpackStore.addSelfSelectCards(uuid, r.amount())) {
                        throw new IllegalStateException("Self-select reward could not be persisted");
                    }
                    messages.add(Component.translatable(
                            "message.habitrain_lottery.reward.self_select_card", tagComp, r.amount()));
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
                    systemItemDeltas.merge(itemId, r.amount(), Math::addExact);
                    messages.add(Component.translatable(
                            r.kind() == MailReward.Kind.CRATE
                                    ? "message.habitrain_lottery.reward.crate"
                                    : "message.habitrain_lottery.reward.key",
                            tagComp, entry.id(), r.amount()));
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
        if (greenAppleDelta != 0L) {
            long delta = greenAppleDelta;
            if (!PlayerLotteryStore.get().applyToPlayerWithRollback(uuid, data -> {
                long next = Math.addExact((long) data.greenApples, delta);
                if (next < 0L) {
                    throw new IllegalStateException("Insufficient green apples");
                }
                if (next > Integer.MAX_VALUE) {
                    throw new IllegalStateException("Green apple balance overflow");
                }
                data.greenApples = (int) next;
            })) {
                throw new IllegalStateException("Mail green-apple reward could not be persisted");
            }
        }
        if (!cards.isEmpty()) {
            LocalBackpackStore.runWithApplyingCommitted(() -> {
                for (CardDelta card : cards) {
                    BackpackManager.addCard(player, card.type, card.amount);
                }
            });
            if (!LocalBackpackStore.saveFromEnumMap(uuid, BackpackManager.getCards(player))) {
                throw new IllegalStateException("Faction-card reward could not be persisted");
            }
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
            LocalBackpackStore.runWithApplyingCommitted(() -> {
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
            });
            if (!LocalBackpackStore.saveFromEnumMap(uuid, BackpackManager.getCards(player))) {
                throw new IllegalStateException("Faction-card rollback could not be persisted");
            }
        });
    }

    static boolean isSreGameActive(ServerPlayer player) {
        return ServerMatchGate.hasActiveMatch(player);
    }

    private record CardDelta(FactionCardType type, int amount) {
    }

    private record RewardSnapshot(int greenApples, Map<FactionCardType, Integer> cards,
                                  int selfSelectCards, int limitBreakCards, PlayerLotteryData skins) {
    }
}
