package com.habitrain.lottery.daily.config;

/**
 * Server-side fallback wording, used only when an OP cleared a task's description. The Mod Menu
 * editor writes a fully localised description (with role and item names) whenever
 * {@link DailyTaskDefinition#autoDescription} is on, so this text is rarely seen.
 */
public final class DailyTaskText {
    private DailyTaskText() { }

    public static String fallbackDescription(DailyTaskDefinition task) {
        DailyTaskType type = task.taskType();
        int n = task.target;
        String body = switch (type == null ? DailyTaskType.LOGIN : type) {
            case LOGIN -> "登录游戏后即可领取";
            case PLAY_MATCH -> switch (task.matchResult) {
                case "win" -> "获胜 " + n + " 场对局";
                case "lose" -> "参与并落败 " + n + " 场对局";
                case "survived" -> "存活到对局结束 " + n + " 场";
                case "eliminated" -> "参与对局并阵亡 " + n + " 场";
                default -> "参与 " + n + " 场对局";
            };
            case KILL -> "击杀 " + n + " 名" + ("enemy".equals(task.victimMode) ? "敌对" : "") + "玩家";
            case SURVIVE_TIME -> "在对局中累计存活 " + n + " 分钟";
            case FINISH_TASK -> "完成 " + n + " 个对局任务";
            case USE_ITEM -> "在对局中使用道具 " + n + " 次";
            case SHOP_BUY -> "在商店购买 " + n + " 次";
            case SHOP_SPEND -> "在商店累计花费 " + n + " 金币";
            case USE_SKILL -> "释放职业技能 " + n + " 次";
            case SPECIAL_ACTION -> "完成特殊行动 " + n + " 次";
            case START_MEETING -> "发起会议 " + n + " 次";
            case OPEN_CRATE -> "开启箱子 " + n + " 次";
            case USE_CARD -> "使用角色卡 " + n + " 次";
            case CLAIM_TASKS -> "领取 " + n + " 个其他每日任务";
        };
        String prefix = "faction".equals(task.roleMode) || "role".equals(task.roleMode) ? "以指定身份" : "";
        String suffix = task.singleMatch ? "（单局内完成）" : "";
        String text = prefix + body + suffix;
        return text.length() <= 120 ? text : text.substring(0, 119) + "…";
    }
}
