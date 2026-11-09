package com.habitrain.lottery.api.player;

import com.habitrain.lottery.mail.MailComposeLimits;
import com.habitrain.lottery.mail.MailDraft;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.mail.MailService;
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
 * <p>Public reward amounts must be positive ({@code amount > 0}), clamped at
 * {@link MailComposeLimits#MAX_REWARD_AMOUNT}, and at most
 * {@link MailComposeLimits#MAX_REWARDS} rewards per mail.</p>
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
        if (target == null || draft == null || !ApiGuards.isServerThreadAndReady()) {
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
     * {@link MailComposeLimits#MAX_REWARDS} positive rewards.
     */
    public static MailDraft draft(String sender, String title, String content, List<MailReward> rewards) {
        return draft(sender, title, content, NEVER_EXPIRES, rewards);
    }

    /** Builds a draft from a list of positive rewards (non-positive entries are ignored). */
    public static MailDraft draft(String sender, String title, String content, long expiresAt,
                                  List<MailReward> rewards) {
        List<MailReward> safe = new ArrayList<>();
        if (rewards != null) {
            for (MailReward reward : rewards) {
                if (reward == null || reward.amount() <= 0) {
                    continue;
                }
                if (safe.size() >= MailComposeLimits.MAX_REWARDS) {
                    break;
                }
                safe.add(clampPositive(reward));
            }
        }
        return new MailDraft(sender, title, content, expiresAt, safe);
    }

    /** One draft that grants a positive quantity of green apples. */
    public static MailDraft greenApples(String sender, String title, String content, int amount) {
        return draft(sender, title, content, List.of(greenApples(amount)));
    }

    public static MailReward greenApples(int amount) {
        return MailReward.greenApples(positiveAmount(amount));
    }

    /**
     * {@code FACTION_CARD} reward. Only the four real faction kinds are valid;
     * virtual kinds return {@code null}.
     */
    public static MailReward factionCard(HabiCardKind kind, int amount) {
        if (kind == null || !kind.isFactionCard()) {
            return null;
        }
        return MailReward.factionCard(kind.id(), positiveAmount(amount));
    }

    /** {@code SELF_SELECT_CARD} reward. */
    public static MailReward selfSelectCard(int amount) {
        return MailReward.selfSelectCard(positiveAmount(amount));
    }

    /** {@code SKIN} reward for a registered {@code type/id} skin. */
    public static MailReward skin(String type, String id) {
        return MailReward.skin(type, id);
    }

    /** {@code LIMIT_BREAK_CARD} reward. */
    public static MailReward limitBreakCard(int amount) {
        return MailReward.limitBreakCard(positiveAmount(amount));
    }

    /** A crate attachment from the shared crate catalogue. */
    public static MailReward crate(String crateId, int amount) {
        return MailReward.crate(crateId, positiveAmount(amount));
    }

    /** A matching crate key attachment from the shared crate catalogue. */
    public static MailReward key(String crateId, int amount) {
        return MailReward.key(crateId, positiveAmount(amount));
    }

    private static int positiveAmount(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Mail reward amount must be positive: " + amount);
        }
        return Math.min(MailComposeLimits.MAX_REWARD_AMOUNT, amount);
    }

    private static MailReward clampPositive(MailReward reward) {
        int amount = Math.max(1, Math.min(MailComposeLimits.MAX_REWARD_AMOUNT, reward.amount()));
        if (amount == reward.amount()) {
            return reward;
        }
        return new MailReward(reward.kind(), amount, reward.factionType());
    }
}
