package com.habitrain.lottery.daily.config;

/**
 * One observed gameplay fact, already reduced to plain strings by the tracker. {@link #matches}
 * is pure so the whole condition matrix is unit-testable without a running match.
 *
 * @param type        what happened
 * @param amount      units contributed (kills, coins, seconds for survival)
 * @param roleId      actor's role id at the time of the event, or {@code null} outside a match
 * @param faction     actor's {@link DailyFactions} faction, or {@code null}
 * @param detail      type-specific id: death reason, quest, item, action, crate or card kind
 * @param victimFaction victim's faction (KILL)
 * @param won         the actor won the match (PLAY_MATCH)
 * @param survived    the actor was alive at the end of the match (PLAY_MATCH)
 */
public record DailyTaskEvent(DailyTaskType type, int amount, String roleId, String faction, String detail,
                             String victimFaction, boolean won, boolean survived) {

    public static DailyTaskEvent simple(DailyTaskType type, int amount, String detail) {
        return new DailyTaskEvent(type, amount, null, null, detail, null, false, false);
    }

    public static DailyTaskEvent inMatch(DailyTaskType type, int amount, String roleId, String faction, String detail) {
        return new DailyTaskEvent(type, amount, roleId, faction, detail, null, false, false);
    }

    /** Whether {@code task} counts this event. Disabled tasks never match. */
    public boolean matches(DailyTaskDefinition task) {
        if (task == null || !task.enabled || task.taskType() != type || amount <= 0) return false;
        if (type.supportsRoleFilter()) {
            switch (task.roleMode == null ? "any" : task.roleMode) {
                case "faction" -> { if (faction == null || !task.factions.contains(faction)) return false; }
                case "role" -> { if (roleId == null || !task.roles.contains(roleId)) return false; }
                default -> { }
            }
        }
        return switch (type) {
            case PLAY_MATCH -> switch (task.matchResult == null ? "any" : task.matchResult) {
                case "win" -> won;
                case "lose" -> !won;
                case "survived" -> survived;
                case "eliminated" -> !survived;
                default -> true;
            };
            case KILL -> anyOf(task.deathReasons, detail) && switch (task.victimMode == null ? "any" : task.victimMode) {
                case "enemy" -> DailyFactions.hostile(faction, victimFaction);
                case "faction" -> victimFaction != null && task.victimFactions.contains(victimFaction);
                default -> true;
            };
            case FINISH_TASK -> anyOf(task.quests, detail);
            case USE_ITEM, SHOP_BUY -> anyOf(task.items, detail);
            case SPECIAL_ACTION -> anyOf(task.actions, detail);
            case OPEN_CRATE -> anyOf(task.crates, detail);
            case USE_CARD -> anyOf(task.cards, detail);
            default -> true;
        };
    }

    /** An empty filter accepts everything, including ids added by future content. */
    private static boolean anyOf(java.util.List<String> filter, String value) {
        return filter == null || filter.isEmpty() || value != null && filter.contains(value);
    }
}
