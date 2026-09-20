package com.habitrain.lottery.mail;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.bridge.EconomyMirror;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Self-contained mailbox service. New SRE removed the
 * {@code Mail}/{@code MailboxComponent} system, so the lottery owns the whole
 * inbox: local JSON persistence, claim and reward application.
 *
 * <p>Claims acquire a persisted lock before granting rewards. Failed grants
 * reopen the mail only after every reward store has been restored.
 *
 * <h2>Concurrency (audit B-15)</h2>
 * <p>Every read-modify-write of a mailbox file runs under a per-recipient monitor
 * ({@link #mailboxLock(UUID)}), the same pattern {@code LootRollServer} uses for rolls:
 * <ul>
 *   <li>the monitor is a plain {@code synchronized} object, so it is <b>reentrant</b> —
 *       {@link #claimAll} holds it for the player and calls {@link #claimInternal}, which
 *       takes it again for the same player;</li>
 *   <li>it is always taken for <b>exactly one</b> UUID per operation. {@code send},
 *       {@code sendOffline}, {@code claim}, {@code claimAll} and {@code list} each touch a
 *       single mailbox, so two mailbox locks are never held at the same time and no
 *       lock-order inversion is possible. An operation that had to touch two mailboxes
 *       would have to establish a fixed order first; none does today.</li>
 *   <li>{@link #list} takes the same lock even though it is a read for the caller: it
 *       persists read flags and the claim reconciliation below, so a concurrent claim
 *       briefly blocks a list instead of racing it. Both still complete; neither deadlocks.</li>
 * </ul>
 * <p>{@link #MAILBOX_LOCKS} is never evicted (one small monitor per player UUID ever seen),
 * mirroring {@code LootRollServer.ROLL_LOCKS}.
 *
 * <h2>Recoverable claims (audit B-14)</h2>
 * <p>The durable "claim in progress" marker is a {@code hltclaim:v1:<mailId>} token inside
 * the existing {@link MailJson#commands} list. The mailbox JSON schema is unchanged and no
 * new file is created; {@link MailCommandsCodec} ignores the token because it only
 * understands the {@code hltmail:v1:} prefix, and so does the client mailbox screen.
 * The order of durable writes is:
 * <ol>
 *   <li>before the grant: marker added, persisted <b>while {@code claimed} stays
 *       {@code false}</b> — so an {@link Error} or a hard kill during the grant can never
 *       leave a durably "claimed" mail whose rewards were never delivered;</li>
 *   <li>after a successful grant: {@code claimed=true} persisted and the marker removed;</li>
 *   <li>after a successful rollback: marker removed, mail claimable again;</li>
 *   <li>when the rollback itself failed: the marker is replaced by
 *       {@code hltclaimfailed:v1:<mailId>} and the mail is persisted as claimed, so a
 *       restart does <b>not</b> silently re-open a mail whose compensation is unknown.</li>
 * </ol>
 * <p>{@link #reconcileClaimMarkers} runs on every mailbox load through
 * {@link #loadReconciled(UUID)}: an interrupted in-flight marker logs a WARN naming the
 * mail id and returns that mail to the claimable state; an unresolved marker is left locked
 * and logged at ERROR once per mail.
 */
public final class MailService {
    /**
     * Per-recipient mailbox monitor (audit B-15), mirroring
     * {@code LootRollServer.ROLL_LOCKS}. Reentrant, one entry per player UUID, never evicted.
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

    /**
     * Repairs the durable state left behind by an interrupted claim (audit B-14).
     *
     * <ul>
     *   <li>{@code hltclaim:v1:} (in flight, so the process died between the pre-grant write
     *       and the post-grant write): logs a WARN naming the mail id and returns the mail to
     *       the claimable state. {@code claimed} is normally already {@code false} — it is
     *       only persisted after a delivered reward — but a mailbox written by an older build
     *       can carry {@code claimed=true}, and that case is reopened explicitly.</li>
     *   <li>{@code hltclaimfailed:v1:} (compensation failed while the server was up): left
     *       locked on purpose and logged at ERROR, because its rewards cannot be proven absent
     *       and re-opening it could double-grant.</li>
     * </ul>
     *
     * @return {@code true} when {@code list} changed and must be persisted
     */
    static boolean reconcileClaimMarkers(UUID uuid, List<MailJson> list) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (MailJson mail : list) {
            if (mail == null) {
                continue;
            }
            if (hasPrefix(mail, CLAIM_FAILED_PREFIX)) {
                if (LOGGED_UNRESOLVED_CLAIMS.add(uuid + "/" + mail.id)) {
                    HabiLotteryMod.LOGGER.error(
                            "Mail {} for {} still carries an unresolved claim marker (a reward rollback "
                                    + "failed earlier); leaving it locked for manual recovery",
                            mail.id, uuid);
                }
                continue;
            }
            if (!hasPrefix(mail, CLAIMING_PREFIX)) {
                continue;
            }
            clearClaimMarkers(mail);
            if (mail.claimed) {
                // Legacy/hybrid state: `claimed` had been persisted before the grant and the
                // in-flight marker was never cleared, so the reward is absent -> reopen.
                mail.claimed = false;
                mail.read = false;
            }
            changed = true;
            HabiLotteryMod.LOGGER.warn(
                    "Interrupted mail claim: mail {} for {} was left in progress; it is claimable "
                            + "again — verify manually whether any partial reward was granted",
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
        if (target == null || draft == null || !WorldLotteryPaths.ready()) {
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

    public static boolean sendOffline(UUID uuid, String nameHint, MailDraft draft) {
        if (uuid == null || draft == null || !WorldLotteryPaths.ready()) {
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

    /**
     * Claims a single mail and applies its rewards exactly once. Idempotent:
     * the claimed flag is re-read from disk before every claim.
     */
    public static boolean claim(ServerPlayer player, String mailId) {
        return claimInternal(player, mailId, true);
    }

    /**
     * Claims all unclaimed, unexpired mails using the same per-mail transaction
     * as {@link #claim}. Not wired to a C2S packet.
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
                    changed = true;
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
        if (loaded.corrupt()) {
            if (announce) {
                player.sendSystemMessage(Component.literal("§c[邮箱] 邮箱数据损坏，无法领取"));
            }
            return false;
        }
        List<MailJson> list = loaded.mails() != null ? loaded.mails() : new ArrayList<>();
        MailJson target = findClaimable(list, mailId);
        if (target == null) {
            if (announce) {
                player.sendSystemMessage(Component.literal("§e[邮箱] 该邮件已领取或已过期"));
            }
            return false;
        }
        List<MailReward> rewards = MailCommandsCodec.decode(target.commands);
        if (refuseFactionCardInGame(rewards, isSreGameActive(player))) {
            player.sendSystemMessage(Component.literal("§c[邮箱] 局中不能领取阵营卡、自选卡或突破上限卡"));
            return false;
        }
        RewardSnapshot snap;
        try {
            snap = snapshotRewards(player);
        } catch (RuntimeException failure) {
            HabiLotteryMod.LOGGER.error("Cannot snapshot mail rewards for {}", uuid, failure);
            player.sendSystemMessage(Component.literal("§c[邮箱] 玩家存档读取失败，未领取"));
            return false;
        }
        boolean wasRead = target.read;
        // B-14: the durable pre-grant lock is the in-flight marker, NOT `claimed`. The
        // claimed flag must never reach disk before the rewards did, otherwise a crash
        // leaves a mail that is claimed forever and never delivered.
        markClaiming(target);
        var result = MailClaimTransaction.run(
                () -> LocalMailboxStore.save(uuid, list),
                () -> applyRewardsStrict(player, rewards),
                () -> restoreRewards(player, snap),
                () -> {
                    clearClaimMarkers(target);
                    unmarkClaimed(target, wasRead);
                    return LocalMailboxStore.save(uuid, list);
                });
        if (result == MailClaimTransaction.Result.SUCCESS) {
            clearClaimMarkers(target);
            markClaimed(target);
            if (!LocalMailboxStore.save(uuid, list)) {
                HabiLotteryMod.LOGGER.error(
                        "Mail {} for {} was delivered but the claimed flag could not be persisted; the "
                                + "interrupted-claim reconciler may re-open it after a restart — check for "
                                + "duplicate rewards",
                        mailId, uuid);
            }
            if (announce) {
                player.sendSystemMessage(Component.literal("§a[邮箱] 已领取：" + target.title));
            }
            return true;
        }
        if (result == MailClaimTransaction.Result.RECOVERY_REQUIRED) {
            // Compensation failed: keep the mail locked across restarts, but say so with a
            // marker the reconciler understands, so it is not mistaken for an in-flight claim.
            markClaimFailed(target);
            markClaimed(target);
            if (!LocalMailboxStore.save(uuid, list)) {
                HabiLotteryMod.LOGGER.error(
                        "Failed persisting the unresolved-claim marker of mail {} for {}; a restart may "
                                + "re-open a claim whose compensation failed",
                        mailId, uuid);
            }
            HabiLotteryMod.LOGGER.error("Mail {} for {} needs manual recovery; claim remains locked", mailId, uuid);
            player.sendSystemMessage(Component.literal("§c[邮箱] 领取失败且回滚未完成，请联系管理员核对；邮件已锁定以避免重复发放"));
        } else {
            player.sendSystemMessage(Component.literal(result == MailClaimTransaction.Result.ROLLED_BACK
                    ? "§c[邮箱] 领取失败，奖励已回滚，可稍后重试" : "§c[邮箱] 领取存档失败，未发放奖励"));
        }
        return false;
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
     * Claimed-flag policy: never mark claimed before rewards; do not persist
     * claimed=true unless rewards succeeded; roll back if persist fails.
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

    private static void applyRewardsStrict(ServerPlayer player, List<MailReward> rewards) {
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
        int drawDelta = 0;
        int coinDelta = 0;
        List<CardDelta> cards = new ArrayList<>();
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
                    if (!PlayerLotteryStore.get().commitSkinAccess(uuid, skin.type(), skin.id(), true))
                        throw new IllegalStateException("Skin reward could not be persisted");
                    messages.add(Component.literal("§a[邮箱] 已解锁皮肤 " + reward.factionType()));
                }
                case DRAWS -> {
                    drawDelta += r.amount();
                    messages.add(Component.literal(
                            (r.amount() >= 0 ? "§a" : "§e") + "[邮箱] 抽数 " + (r.amount() >= 0 ? "+" : "") + r.amount()));
                }
                case COINS -> {
                    coinDelta += r.amount();
                    messages.add(Component.literal(
                            (r.amount() >= 0 ? "§a" : "§e") + "[邮箱] 金币 " + (r.amount() >= 0 ? "+" : "") + r.amount()));
                }
                case FACTION_CARD -> {
                    FactionCardType type = FactionCardType.fromString(r.factionType());
                    if (type != FactionCardType.NONE) {
                        cards.add(new CardDelta(type, r.amount()));
                        messages.add(Component.literal(
                                "§a[邮箱] 获得阵营卡 " + type.questKey + " x" + r.amount()));
                    }
                }
                case SELF_SELECT_CARD -> {
                    // Atomic delta add: read-modify-write inside one file write, so a
                    // self-select reward never overwrites a concurrently changed balance.
                    if (!LocalBackpackStore.addSelfSelectCards(uuid, r.amount())) {
                        throw new IllegalStateException("Self-select reward could not be persisted");
                    }
                    messages.add(Component.literal("§a[邮箱] 获得自选卡 x" + r.amount()));
                }
                case LIMIT_BREAK_CARD -> {
                    if (!LocalBackpackStore.addLimitBreakCards(uuid, r.amount())) {
                        throw new IllegalStateException("Limit-break reward could not be persisted");
                    }
                    messages.add(Component.translatable("message.habitrain_lottery.mail.limit_break", r.amount()));
                }
            }
        }
        if (drawDelta != 0) {
            int d = drawDelta;
            PlayerLotteryStore.get().update(uuid, data -> data.lootChance = Math.max(0, data.lootChance + d));
        }
        if (coinDelta != 0) {
            int c = coinDelta;
            PlayerLotteryStore.get().update(uuid, data -> data.coinNum = Math.max(0, data.coinNum + c));
        }
        if (drawDelta != 0 || coinDelta != 0) {
            if (PlayerLotteryStore.get().isLoadFailed(uuid) || !PlayerLotteryStore.get().flush(uuid)) {
                throw new IllegalStateException("Mail economy reward could not be persisted");
            }
            EconomyMirror.syncChanceAndCoins(player, PlayerLotteryStore.get().getOrLoad(player));
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
        return new RewardSnapshot(data.lootChance, data.coinNum, cards,
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
                data.lootChance = Math.max(0, snap.lootChance);
                data.coinNum = Math.max(0, snap.coinNum);
                data.unlocked = snap.skins.copy().unlocked;
                data.equipped = snap.skins.copy().equipped;
            });
            if (PlayerLotteryStore.get().isLoadFailed(uuid) || !PlayerLotteryStore.get().flush(uuid)) {
                throw new IllegalStateException("Mail economy rollback could not be persisted");
            }
            EconomyMirror.syncChanceAndCoins(player, PlayerLotteryStore.get().getOrLoad(player));
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

    private record RewardSnapshot(int lootChance, int coinNum, Map<FactionCardType, Integer> cards,
                                  int selfSelectCards, int limitBreakCards, PlayerLotteryData skins) {
    }
}
