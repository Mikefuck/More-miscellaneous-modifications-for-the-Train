package com.habitrain.lottery.daily.config;

import java.util.Locale;

/**
 * Every condition a configurable daily task can count. The id is the persisted form; the flags
 * tell the validator and the editor which filters make sense for the type, so the UI never offers
 * a setting the tracker would silently ignore.
 */
public enum DailyTaskType {
    /** Logging in once during the UTC day. */
    LOGIN("login", Scope.ACCOUNT, 1, false, false),
    /** Finished matches; optionally only wins, losses, survived or eliminated rounds. */
    PLAY_MATCH("play_match", Scope.MATCH_END, 50, true, false),
    /** Confirmed kills (the victim really died), attributed after upstream kill redirection. */
    KILL("kill", Scope.IN_MATCH, 100, true, true),
    /** Minutes spent alive inside an active match. */
    SURVIVE_TIME("survive_time", Scope.IN_MATCH, 1440, true, true),
    /** In-match mood / scene tasks. */
    FINISH_TASK("finish_task", Scope.IN_MATCH, 500, true, true),
    /** Items used during a match (knife, revolver, grenade, lockpick, ...). */
    USE_ITEM("use_item", Scope.IN_MATCH, 500, true, true),
    /** Purchases from the in-match shop. */
    SHOP_BUY("shop_buy", Scope.IN_MATCH, 500, true, true),
    /** Coins spent in the in-match shop. */
    SHOP_SPEND("shop_spend", Scope.IN_MATCH, 100_000, true, true),
    /** Active role skills released. */
    USE_SKILL("use_skill", Scope.IN_MATCH, 500, true, true),
    /** Rare role actions recorded by the match replay (bomb defuse, trap, disguise, ...). */
    SPECIAL_ACTION("special_action", Scope.IN_MATCH, 500, true, true),
    /** Meetings started (body reports / emergency calls). */
    START_MEETING("start_meeting", Scope.IN_MATCH, 100, true, true),
    /** Crates opened with this mod's crate opener. */
    OPEN_CRATE("open_crate", Scope.ACCOUNT, 1000, false, false),
    /** Role / self-select / limit-break cards used successfully. */
    USE_CARD("use_card", Scope.ACCOUNT, 100, false, false),
    /** Other daily tasks claimed today. */
    CLAIM_TASKS("claim_tasks", Scope.ACCOUNT, 64, false, false);

    public enum Scope { ACCOUNT, IN_MATCH, MATCH_END }

    private final String id;
    private final Scope scope;
    private final int maxTarget;
    private final boolean roleFilter;
    private final boolean singleMatch;

    DailyTaskType(String id, Scope scope, int maxTarget, boolean roleFilter, boolean singleMatch) {
        this.id = id;
        this.scope = scope;
        this.maxTarget = maxTarget;
        this.roleFilter = roleFilter;
        this.singleMatch = singleMatch;
    }

    public String id() { return id; }
    public Scope scope() { return scope; }
    /** Largest meaningful target for one UTC day. */
    public int maxTarget() { return maxTarget; }
    /** Whether "as role / faction X" applies to this type. */
    public boolean supportsRoleFilter() { return roleFilter; }
    /** Whether "must be reached inside one match" applies to this type. */
    public boolean supportsSingleMatch() { return singleMatch; }
    public String translationKey() { return "screen.habitrain_lottery.daily_admin.type." + id; }

    public static DailyTaskType fromId(String id) {
        if (id == null) return null;
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (DailyTaskType type : values()) if (type.id.equals(wanted)) return type;
        return null;
    }
}
