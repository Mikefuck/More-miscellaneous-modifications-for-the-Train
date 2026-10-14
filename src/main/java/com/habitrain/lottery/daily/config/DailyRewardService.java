package com.habitrain.lottery.daily.config;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.mail.MailDraft;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.mail.MailService;
import com.habitrain.lottery.storage.GrantKeys;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Configured daily rewards use a stable player/task/day delivery intent and per-store receipts.
 * Mail delivery uses a stable letter ID. Titles remain immediate rewards in either delivery mode.
 * A failed stage retains the intent and does not consume the daily claim.
 */
public final class DailyRewardService {
    private DailyRewardService() { }

    public static String grantKey(String taskId, long epochDay) {
        return "habitrain_lottery:daily_task:" + taskId + ":" + epochDay;
    }

    /** @return true once the reward has been durably delivered (or had already been delivered). */
    public static boolean grant(ServerPlayer player, DailyTaskDefinition task, long epochDay) {
        if (player == null || task == null) return false;
        PlayerLotteryStore store = PlayerLotteryStore.get();
        String key = grantKey(task.id, epochDay);
        if (store.isFlushDeferred() || store.isLoadFailed(player.getUUID())) return false;
        if (GrantKeys.contains(store.getOrLoad(player), key)) return !store.isLoadFailed(player.getUUID());
        boolean delivered = com.habitrain.lottery.mail.RewardDelivery.grant(player, key, () -> {
            List<MailReward> rewards = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            for (DailyRewardEntry reward : task.rewards) {
                if (DailyRewardEntry.TITLE.equals(reward.kind)) { titles.add(reward.id); continue; }
                MailReward converted = toMailReward(player, task, reward, epochDay);
                if (converted == null) return null;
                rewards.add(converted);
            }
            boolean mail = "mail".equals(task.delivery) || MailService.blockedDuringMatch(player, rewards);
            MailDraft letter = mail && !rewards.isEmpty() ? new MailDraft("每日任务", "每日任务奖励：" + task.title,
                    "恭喜完成每日任务「" + task.title + "」，请查收附件奖励。", 0L, rewards) : null;
            return new com.habitrain.lottery.mail.RewardDelivery.Plan(rewards, titles, letter);
        });
        if (!delivered) return false;
        return store.applyToPlayerWithRollback(player.getUUID(), data -> GrantKeys.tryConsume(data, key));
    }

    private static MailReward toMailReward(ServerPlayer player, DailyTaskDefinition task, DailyRewardEntry reward, long day) {
        return toMailReward(player, task.id, reward, day);
    }

    /**
     * {@code null} for titles (handled separately) and for rewards that no longer resolve.
     * {@code ownerId} salts the random-crate draw and names the owner in logs.
     */
    public static MailReward toMailReward(ServerPlayer player, String ownerId, DailyRewardEntry reward, long day) {
        try {
            return switch (reward.kind) {
                case DailyRewardEntry.GREEN_APPLES -> MailReward.greenApples(reward.amount);
                case DailyRewardEntry.CARD -> MailReward.factionCard(reward.id, reward.amount);
                case DailyRewardEntry.SELF_SELECT -> MailReward.selfSelectCard(reward.amount);
                case DailyRewardEntry.LIMIT_BREAK -> MailReward.limitBreakCard(reward.amount);
                case DailyRewardEntry.SKIN -> MailReward.skinEntry(reward.id);
                case DailyRewardEntry.CRATE, DailyRewardEntry.KEY -> {
                    String crate = DailyRewardEntry.RANDOM.equals(reward.id)
                            ? randomCrate(player, ownerId + reward.kind, day) : reward.id;
                    CrateCatalog.Entry entry = crate == null ? null : CrateCatalog.find(crate);
                    if (entry == null || entry.archived()) yield null;
                    yield DailyRewardEntry.CRATE.equals(reward.kind)
                            ? MailReward.crate(entry.id(), reward.amount) : MailReward.key(entry.id(), reward.amount);
                }
                default -> null;
            };
        } catch (RuntimeException invalid) {
            HabiLotteryMod.LOGGER.warn("Daily reward owner {} reward {}:{} is invalid", ownerId, reward.kind, reward.id, invalid);
            return null;
        }
    }

    /**
     * One published crate chosen deterministically per player/task/day, so a retried claim
     * resolves to the same crate. Crates enabled for opening are preferred; when none is enabled
     * yet, any published crate qualifies (the item is still valid inventory). New crates join the
     * draw as soon as they are published.
     */
    static String randomCrate(ServerPlayer player, String salt, long day) {
        List<CrateCatalog.Entry> published = CrateCatalog.publishedEntries();
        List<CrateCatalog.Entry> candidates = published.stream().filter(CrateCatalog.Entry::enabled).toList();
        if (candidates.isEmpty()) candidates = published;
        if (candidates.isEmpty()) return null;
        long seed = player.getUUID().getLeastSignificantBits() ^ (day * 31L) ^ salt.hashCode();
        return candidates.get(Math.floorMod(Long.hashCode(seed * 0x9E3779B97F4A7C15L), candidates.size())).id();
    }
}
