package com.habitrain.lottery.api.player;

import net.minecraft.network.chat.Component;

/**
 * Reason a {@link HabiAssetResult} reports failure.
 *
 * <p>Values are stable API: compare with {@code ==}. Use {@link #message()} for a
 * ready-to-display Chinese description, or {@link #toComponent()} for client-locale
 * translatable text.</p>
 */
public enum HabiFailure {
    /** No failure. */
    NONE("", ""),
    /** The world lottery root is not initialised yet (server not finished starting, or the world is unloading). */
    NOT_READY("玩家存档尚未就绪（服务器未完成启动或世界正在卸载）", "message.habitrain_lottery.failure.not_ready"),
    /** The mutation operation was null, unknown, or invoked off the server thread. */
    INVALID_OPERATION("未知或非法操作（仅支持服务端主线程执行 ADD / SET）", "message.habitrain_lottery.failure.invalid_operation"),
    /** The supplied value is outside the allowed range for this asset. */
    INVALID_VALUE("数值不合法或超出允许范围", "message.habitrain_lottery.failure.invalid_value"),
    /** The supplied card kind is unknown or not a faction card where one is required. */
    UNKNOWN_CARD_TYPE("未知角色卡类型", "message.habitrain_lottery.failure.unknown_card_type"),
    /** The supplied skin type is not one of knife / revolver / bat / grenade / hat / back. */
    UNKNOWN_SKIN_TYPE("未知皮肤类型", "message.habitrain_lottery.failure.unknown_skin_type"),
    /** The skin id is not registered with {@link com.habitrain.lottery.api.skin.HabiSkinApi}. */
    UNKNOWN_SKIN("未注册的皮肤", "message.habitrain_lottery.failure.unknown_skin"),
    /** The player does not own the skin, so it cannot be equipped or revoked. */
    SKIN_NOT_UNLOCKED("玩家尚未解锁该皮肤", "message.habitrain_lottery.failure.skin_not_unlocked"),
    /** The player JSON / backpack JSON is unreadable; refusing to overwrite it. */
    CORRUPT_STORAGE("玩家存档损坏，已拒绝覆盖", "message.habitrain_lottery.failure.corrupt_storage"),
    /** The authoritative store rejected the write; the previous value was restored. */
    WRITE_FAILED("存档写入失败", "message.habitrain_lottery.failure.write_failed"),
    /** A deduplicated grant reused a reason key that was already consumed. */
    DUPLICATE_GRANT("该发放原因已被使用过（去重命中）", "message.habitrain_lottery.failure.duplicate_grant"),
    /** The target player could not be resolved. */
    NOT_FOUND("未找到目标玩家", "message.habitrain_lottery.failure.not_found"),
    /** The player must be online for this operation. */
    PLAYER_OFFLINE("该操作需要玩家在线", "message.habitrain_lottery.failure.player_offline"),
    /** The player does not own the title, so it cannot be set as current. */
    TITLE_NOT_OWNED("玩家未拥有该称号", "message.habitrain_lottery.failure.title_not_owned"),
    /** The mailbox JSON is unreadable; refusing to overwrite it. */
    MAILBOX_CORRUPT("邮箱存档损坏", "message.habitrain_lottery.failure.mailbox_corrupt"),
    /** An unexpected exception was caught at the API boundary. */
    INTERNAL_ERROR("内部错误", "message.habitrain_lottery.failure.internal_error");

    private final String message;
    private final String translationKey;

    HabiFailure(String message, String translationKey) {
        this.message = message;
        this.translationKey = translationKey;
    }

    /** Human readable Chinese description; empty for {@link #NONE}. */
    public String message() {
        return message;
    }

    /** Client localization key; empty for {@link #NONE}. */
    public String translationKey() {
        return translationKey;
    }

    /** Translatable component for player/command feedback. */
    public Component toComponent() {
        if (translationKey == null || translationKey.isEmpty()) {
            return Component.empty();
        }
        return Component.translatable(translationKey);
    }
}
