package com.habitrain.lottery.api.player;

/**
 * Reason a {@link HabiAssetResult} reports failure.
 *
 * <p>Values are stable API: compare with {@code ==}. Use {@link #message()} for a
 * ready-to-display Chinese description.</p>
 */
public enum HabiFailure {
    /** No failure. */
    NONE(""),
    /** The world lottery root is not initialised yet (server not finished starting, or the world is unloading). */
    NOT_READY("抽奖存档尚未就绪（服务器未完成启动或世界正在卸载）"),
    /** The mutation operation was null or unknown. */
    INVALID_OPERATION("未知操作（仅支持 ADD / SET）"),
    /** The supplied value is outside the allowed range for this asset. */
    INVALID_VALUE("数值不合法或超出允许范围"),
    /** The supplied card kind is unknown or not a faction card where one is required. */
    UNKNOWN_CARD_TYPE("未知角色卡类型"),
    /** The supplied skin type is not one of knife / revolver / bat / grenade / hat. */
    UNKNOWN_SKIN_TYPE("未知皮肤类型"),
    /** The skin id is not registered with SRE. */
    UNKNOWN_SKIN("未注册的皮肤"),
    /** The player does not own the skin, so it cannot be equipped or revoked. */
    SKIN_NOT_UNLOCKED("玩家尚未解锁该皮肤"),
    /** The player JSON / backpack JSON is unreadable; refusing to overwrite it. */
    CORRUPT_STORAGE("玩家存档损坏，已拒绝覆盖"),
    /** The authoritative store rejected the write; the previous value was restored. */
    WRITE_FAILED("存档写入失败"),
    /** A deduplicated grant reused a reason key that was already consumed. */
    DUPLICATE_GRANT("该发放原因已被使用过（去重命中）"),
    /** The target player could not be resolved. */
    NOT_FOUND("未找到目标玩家"),
    /** The player must be online for this operation. */
    PLAYER_OFFLINE("该操作需要玩家在线"),
    /** The player does not own the title, so it cannot be set as current. */
    TITLE_NOT_OWNED("玩家未拥有该称号"),
    /** The mailbox JSON is unreadable; refusing to overwrite it. */
    MAILBOX_CORRUPT("邮箱存档损坏"),
    /** An unexpected exception was caught at the API boundary. */
    INTERNAL_ERROR("内部错误");

    private final String message;

    HabiFailure(String message) {
        this.message = message;
    }

    /** Human readable Chinese description; empty for {@link #NONE}. */
    public String message() {
        return message;
    }
}
