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
import java.util.function.BooleanSupplier;

/**
 * Self-contained mailbox service. New SRE removed the
 * {@code Mail}/{@code MailboxComponent} system, so the lottery owns the whole
 * inbox: local JSON persistence, claim and reward application.
 *
 * <p>Claims acquire a persisted lock before granting rewards. Failed grants
 * reopen the mail only after every reward store has been restored.
 */
public final class MailService {
    private MailService() {
    }

    public static boolean send(ServerPlayer target, MailDraft draft) {
        if (target == null || draft == null || !WorldLotteryPaths.ready()) {
            return false;
        }
        try {
            MailLoad loaded = LocalMailboxStore.loadWithStatus(target.getUUID());
            if (loaded.corrupt()) {
                HabiLotteryMod.LOGGER.error("Refusing to send mail over corrupt mailbox {}", target.getUUID());
                return false;
            }
            List<MailJson> list = loaded.mails() != null ? loaded.mails() : new ArrayList<>();
            list.add(LocalMailboxStore.fromDraft(draft));
            if (!LocalMailboxStore.save(target.getUUID(), list)) {
                return false;
            }
            target.sendSystemMessage(Component.literal("§a[邮箱] 你收到一封新邮件：" + draft.title()));
            return true;
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("send mail to {} failed", target.getGameProfile().getName(), e);
            return false;
        }
    }

    public static boolean sendOffline(UUID uuid, String nameHint, MailDraft draft) {
        if (uuid == null || draft == null || !WorldLotteryPaths.ready()) {
            return false;
        }
        try {
            MailLoad loaded = LocalMailboxStore.loadWithStatus(uuid);
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
     */
    public static int claimAll(ServerPlayer player) {
        if (player == null || !WorldLotteryPaths.ready()) {
            return 0;
        }
        if (PlayerStateGate.spectatorRestOrDead(player)) {
            player.sendSystemMessage(Component.literal(PlayerStateGate.MAIL_BLOCKED));
            return 0;
        }
        MailLoad loaded = LocalMailboxStore.loadWithStatus(player.getUUID());
        if (loaded.corrupt()) {
            HabiLotteryMod.LOGGER.error("Refusing claimAll on corrupt mailbox {}", player.getUUID());
            player.sendSystemMessage(Component.literal("§c[邮箱] 邮箱数据损坏，无法领取"));
            return 0;
        }
        List<String> ids = new ArrayList<>();
        List<MailJson> list = loaded.mails() != null ? loaded.mails() : List.of();
        for (MailJson m : list) {
            if (m == null || m.claimed || LocalMailboxStore.isExpired(m) || m.id == null || m.id.isBlank()) {
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

    /**
     * Returns the non-expired inbox, marking everything read (unread badge
     * source). Persists when the read flags changed. Corrupt files are not
     * overwritten.
     */
    public static List<MailJson> list(ServerPlayer player) {
        if (player == null || !WorldLotteryPaths.ready()) {
            return new ArrayList<>();
        }
        MailLoad loaded = LocalMailboxStore.loadWithStatus(player.getUUID());
        if (loaded.corrupt()) {
            HabiLotteryMod.LOGGER.error("Refusing list/save on corrupt mailbox {}", player.getUUID());
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
        if (changed && !LocalMailboxStore.save(player.getUUID(), list)) {
            HabiLotteryMod.LOGGER.error("Failed persisting mailbox read flags for {}", player.getUUID());
        }
        return out;
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
        MailLoad loaded = LocalMailboxStore.loadWithStatus(player.getUUID());
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
            HabiLotteryMod.LOGGER.error("Cannot snapshot mail rewards for {}", player.getUUID(), failure);
            player.sendSystemMessage(Component.literal("§c[邮箱] 玩家存档读取失败，未领取"));
            return false;
        }
        boolean wasRead = target.read;
        markClaimed(target);
        var result = MailClaimTransaction.run(
                () -> LocalMailboxStore.save(player.getUUID(), list),
                () -> applyRewardsStrict(player, rewards),
                () -> restoreRewards(player, snap),
                () -> {
                    unmarkClaimed(target, wasRead);
                    return LocalMailboxStore.save(player.getUUID(), list);
                });
        if (result != MailClaimTransaction.Result.SUCCESS) {
            if (result == MailClaimTransaction.Result.RECOVERY_REQUIRED) {
                HabiLotteryMod.LOGGER.error("Mail {} for {} needs manual recovery; claim remains locked", mailId, player.getUUID());
                player.sendSystemMessage(Component.literal("§c[邮箱] 领取失败且回滚未完成，请联系管理员核对；邮件已锁定以避免重复发放"));
            } else {
                player.sendSystemMessage(Component.literal(result == MailClaimTransaction.Result.ROLLED_BACK
                        ? "§c[邮箱] 领取失败，奖励已回滚，可稍后重试" : "§c[邮箱] 领取存档失败，未发放奖励"));
            }
            return false;
        }
        if (announce) {
            player.sendSystemMessage(Component.literal("§a[邮箱] 已领取：" + target.title));
        }
        return true;
    }

    static MailJson findClaimable(List<MailJson> list, String mailId) {
        if (list == null || mailId == null) {
            return null;
        }
        for (MailJson m : list) {
            if (m == null || m.claimed || LocalMailboxStore.isExpired(m)) {
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
                backpack.selfSelectCards(), backpack.limitBreakCards());
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
            });
            if (PlayerLotteryStore.get().isLoadFailed(uuid) || !PlayerLotteryStore.get().flush(uuid)) {
                throw new IllegalStateException("Mail economy rollback could not be persisted");
            }
            EconomyMirror.syncChanceAndCoins(player, PlayerLotteryStore.get().getOrLoad(player));
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
                                  int selfSelectCards, int limitBreakCards) {
    }
}
