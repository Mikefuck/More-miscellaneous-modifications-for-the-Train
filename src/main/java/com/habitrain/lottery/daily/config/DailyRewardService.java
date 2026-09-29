package com.habitrain.lottery.daily.config;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.player.HabiAssetResult;
import com.habitrain.lottery.api.player.HabiTitleApi;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.mail.MailDraft;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.mail.MailService;
import com.habitrain.lottery.storage.GrantKeys;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Pays out a configured daily task. Rewards go through the mailbox reward pipeline, so every
 * asset type (apples, cards, skins, crates, keys) is credited all-or-nothing, and crates/keys
 * resolve against the live crate catalogue at claim time.
 *
 * <p>A durable grant key ({@code daily:<task>:<day>}) is recorded after a successful payout, so
 * the retry of a claim that crashed after paying out never pays a second time.</p>
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
        if (GrantKeys.contains(store.getOrLoad(player), key)) return true;

        List<MailReward> mailRewards = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (DailyRewardEntry reward : task.rewards) {
            MailReward converted = toMailReward(player, task, reward, epochDay);
            if (converted != null) mailRewards.add(converted);
            else if (DailyRewardEntry.TITLE.equals(reward.kind)) titles.add(reward.id);
            else {
                player.sendSystemMessage(Component.literal("§c[每日任务] 奖励「" + reward.kind + ":" + reward.id
                        + "」已失效（箱子被归档或皮肤已移除），请联系管理员"));
                return false;
            }
        }

        boolean mail = "mail".equals(task.delivery);
        if (!mail && MailService.blockedDuringMatch(player, mailRewards)) {
            // Cards cannot be credited mid-match; a letter keeps the claim instead of refusing it.
            mail = true;
            player.sendSystemMessage(Component.literal("§e[每日任务] 对局中不能直接领取角色卡，奖励已改为邮件发送"));
        }
        boolean delivered;
        if (mailRewards.isEmpty()) {
            delivered = true;
        } else if (mail) {
            delivered = MailService.send(player, new MailDraft("每日任务", "每日任务奖励：" + task.title,
                    "恭喜完成每日任务「" + task.title + "」，请查收附件奖励。", 0L, mailRewards));
        } else {
            delivered = MailService.grantTransactional(player, mailRewards);
        }
        if (!delivered) return false;
        for (String title : titles) {
            HabiAssetResult result = HabiTitleApi.grant(player, title);
            if (result.ok()) player.sendSystemMessage(Component.literal("§a[每日任务] 获得称号「" + title + "」"));
            else HabiLotteryMod.LOGGER.warn("Daily task {} title reward '{}' failed: {}", task.id, title, result.failure());
        }
        store.update(player.getUUID(), data -> GrantKeys.tryConsume(data, key));
        if (!store.flush(player.getUUID())) {
            // Rewards are already delivered; the key stays dirty in memory and the periodic flush retries it.
            HabiLotteryMod.LOGGER.error("Daily task {} for {} paid out but the grant key is not yet on disk",
                    task.id, player.getUUID());
        }
        return true;
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
