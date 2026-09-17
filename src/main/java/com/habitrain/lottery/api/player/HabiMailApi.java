package com.habitrain.lottery.api.player;

import com.habitrain.lottery.mail.MailComposeLimits;
import com.habitrain.lottery.mail.MailDraft;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.mail.MailService;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Public API for delivering rewards through this mod's mailbox.
 *
 * <p>Mail is the recommended way for another mod to grant assets to a player
 * who may be offline: the reward is stored in the mailbox JSON and applied
 * exactly once when the player claims it. Online players also receive an
 * in-game notification.</p>
 *
 * <p>Reward amounts are clamped to
 * {@link MailComposeLimits#MIN_REWARD_AMOUNT}..{@link MailComposeLimits#MAX_REWARD_AMOUNT}
 * and at most {@link MailComposeLimits#MAX_REWARDS} rewards per mail, matching
 * the compose screen.</p>
 *
 * <p>Server thread only.</p>
 */
public final class HabiMailApi {
    /** {@code expiresAt} value meaning "never expires". */
    public static final long NEVER_EXPIRES = 0L;

    private HabiMailApi() {
    }

    /** Sends a mail to one player, online or offline. */
    public static boolean send(UUID target, MailDraft draft) {
        return send(target, "", draft);
    }

    /**
     * Sends a mail to one player.
     *
     * @param nameHint log hint when the player is offline; may be blank
     * @return true when the mailbox JSON was written
     */
    public static boolean send(UUID target, String nameHint, MailDraft draft) {
        if (target == null || draft == null || !WorldLotteryPaths.ready()) {
            return false;
        }
        ServerPlayer online = HabiLotteryApi.online(target);
        if (online != null) {
            return MailService.send(online, draft);
        }
        return MailService.sendOffline(target, nameHint == null ? "" : nameHint, draft);
    }

    /** Sends a mail to an online player by entity. */
    public static boolean send(ServerPlayer target, MailDraft draft) {
        if (target == null) {
            return false;
        }
        return send(target.getUUID(), target.getGameProfile().getName(), draft);
    }

    /**
     * Builds a draft that never expires from up to
     * {@link MailComposeLimits#MAX_REWARDS} clamped rewards.
     */
    public static MailDraft draft(String sender, String title, String content, List<MailReward> rewards) {
        return draft(sender, title, content, NEVER_EXPIRES, rewards);
    }

    /** Builds a draft from a list of rewards. */
    public static MailDraft draft(String sender, String title, String content, long expiresAt,
                                  List<MailReward> rewards) {
        List<MailReward> safe = new ArrayList<>();
        if (rewards != null) {
            for (MailReward reward : rewards) {
                if (reward == null || reward.amount() == 0) {
                    continue;
                }
                if (safe.size() >= MailComposeLimits.MAX_REWARDS) {
                    break;
                }
                safe.add(clamp(reward));
            }
        }
        return new MailDraft(sender, title, content, expiresAt, safe);
    }

    /** One draft that grants 抽数. */
    public static MailDraft draws(String sender, String title, String content, int amount) {
        return draft(sender, title, content, List.of(draws(amount)));
    }

    /** One draft that grants 金币. */
    public static MailDraft coins(String sender, String title, String content, int amount) {
        return draft(sender, title, content, List.of(coins(amount)));
    }

    /** {@code DRAWS} reward. */
    public static MailReward draws(int amount) {
        return MailReward.draws(MailComposeLimits.clampRewardAmount(amount));
    }

    /** {@code COINS} reward. */
    public static MailReward coins(int amount) {
        return MailReward.coins(MailComposeLimits.clampRewardAmount(amount));
    }

    /**
     * {@code FACTION_CARD} reward. Only the four real faction kinds are valid;
     * virtual kinds return {@code null}.
     */
    public static MailReward factionCard(HabiCardKind kind, int amount) {
        if (kind == null || !kind.isFactionCard()) {
            return null;
        }
        return MailReward.factionCard(kind.id(), MailComposeLimits.clampRewardAmount(amount));
    }

    /** {@code SELF_SELECT_CARD} reward. */
    public static MailReward selfSelectCard(int amount) {
        return MailReward.selfSelectCard(MailComposeLimits.clampRewardAmount(amount));
    }

    /** {@code LIMIT_BREAK_CARD} reward. */
    public static MailReward limitBreakCard(int amount) {
        return MailReward.limitBreakCard(MailComposeLimits.clampRewardAmount(amount));
    }

    private static MailReward clamp(MailReward reward) {
        int amount = MailComposeLimits.clampRewardAmount(reward.amount());
        if (amount == reward.amount()) {
            return reward;
        }
        return new MailReward(reward.kind(), amount, reward.factionType());
    }
}
