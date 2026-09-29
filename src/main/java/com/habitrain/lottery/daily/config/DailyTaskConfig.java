package com.habitrain.lottery.daily.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * World-level daily task configuration ({@code config/daily_tasks.json}).
 *
 * <p>{@link #normalize} is the single validation authority: the server runs it on load and on
 * every save from the Mod Menu editor, so a hand-edited or stale file can never register a task
 * the board or the tracker cannot honour.</p>
 */
public final class DailyTaskConfig {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_TASKS = 48;
    public static final int MAX_REWARDS = 8;
    public static final int MAX_FILTER_ENTRIES = 64;
    public static final int MAX_REWARD_AMOUNT = 100_000;
    public static final String LOGIN_TASK_ID = "daily_login";
    public static final int DEFAULT_RANDOM_PICK = 3;

    public int schemaVersion = SCHEMA_VERSION;
    /** Bumped by every accepted save; a stale editor draft is rejected instead of overwriting. */
    public long revision;
    /**
     * Master switch of the per-player daily draw. Off: every enabled task is on every board.
     * {@code null} only in files written before the switch existed (resolved by {@link #normalize}).
     */
    public Boolean randomEnabled;
    /** With the draw on: how many random-pool tasks each player receives per day (pinned tasks come on top). */
    public int randomPick;
    public List<DailyTaskDefinition> tasks = new ArrayList<>();

    /** Lookups the validator needs from live registries (skins, crates). */
    public interface Checks {
        boolean skinExists(String entry);
        boolean crateExists(String crateId);

        Checks LENIENT = new Checks() {
            public boolean skinExists(String entry) { return true; }
            public boolean crateExists(String crateId) { return true; }
        };
    }

    public DailyTaskConfig copy() {
        DailyTaskConfig c = new DailyTaskConfig();
        c.schemaVersion = schemaVersion;
        c.revision = revision;
        c.randomEnabled = randomEnabled;
        c.randomPick = randomPick;
        if (tasks != null) for (DailyTaskDefinition task : tasks) if (task != null) c.tasks.add(task.copy());
        return c;
    }

    public DailyTaskDefinition find(String id) {
        if (tasks == null || id == null) return null;
        for (DailyTaskDefinition task : tasks) if (task != null && id.equals(task.id)) return task;
        return null;
    }

    public boolean randomOn() {
        return Boolean.TRUE.equals(randomEnabled);
    }

    /** Whether {@code task} is currently drawn per player rather than shown to everyone. */
    public boolean isRandomPool(DailyTaskDefinition task) {
        return randomOn() && task != null && !task.pinned;
    }

    public List<DailyTaskDefinition> activeFor(UUID player, long epochDay) {
        return activeFor(player, epochDay, id -> false);
    }

    /**
     * Enabled tasks shown to one player on one UTC day. With the draw off that is every enabled
     * task; with it on, every pinned task plus {@link #randomPick} tasks drawn from the random pool.
     *
     * <p>The draw is a pure function of player, day and task id (rendezvous ranking), so the board,
     * the tracker and a claim all agree without storing it, and each player gets a different set.
     * {@code kept} marks tasks the player already progressed or claimed today: they rank first, so an
     * OP editing the pool mid-day never takes away a task someone is working on.</p>
     */
    public List<DailyTaskDefinition> activeFor(UUID player, long epochDay, Predicate<String> kept) {
        List<DailyTaskDefinition> pinned = new ArrayList<>();
        List<DailyTaskDefinition> pool = new ArrayList<>();
        if (tasks != null) for (DailyTaskDefinition task : tasks) {
            if (task == null || !task.enabled) continue;
            (isRandomPool(task) ? pool : pinned).add(task);
        }
        if (randomOn() && randomPick < pool.size()) {
            long seed = (player == null ? 0L : player.getMostSignificantBits() ^ player.getLeastSignificantBits())
                    ^ (epochDay * 0x9E3779B97F4A7C15L);
            Predicate<String> keep = kept == null ? id -> false : kept;
            pool.sort(java.util.Comparator.<DailyTaskDefinition>comparingInt(t -> keep.test(t.id) ? 0 : 1)
                    .thenComparingLong(t -> mix(seed ^ t.id.hashCode())));
            Set<DailyTaskDefinition> chosen = new HashSet<>(pool.subList(0, randomPick));
            pool.removeIf(task -> !chosen.contains(task));
        }
        List<DailyTaskDefinition> result = new ArrayList<>();
        // Keep configured order on the board even for random picks.
        if (tasks != null) for (DailyTaskDefinition task : tasks) {
            if (task != null && (pinned.contains(task) || pool.contains(task))) result.add(task);
        }
        return result;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    /**
     * Validates and canonicalises the whole config in place.
     *
     * @throws IllegalArgumentException with a translation-key-friendly message on the first defect
     */
    public void normalize(Checks checks) {
        if (checks == null) checks = Checks.LENIENT;
        if (tasks == null) tasks = new ArrayList<>();
        tasks.removeIf(java.util.Objects::isNull);
        if (tasks.size() > MAX_TASKS) throw invalid("too_many_tasks", String.valueOf(tasks.size()));
        // Files from before the switch used randomPick = 0 for "show everything".
        if (randomEnabled == null) randomEnabled = randomPick > 0;
        if (!randomEnabled && randomPick <= 0) randomPick = DEFAULT_RANDOM_PICK;
        randomPick = Math.max(0, Math.min(MAX_TASKS, randomPick));
        Set<String> ids = new HashSet<>();
        for (DailyTaskDefinition task : tasks) {
            normalizeTask(task, checks);
            if (!ids.add(task.id)) throw invalid("duplicate_id", task.id);
        }
    }

    private static void normalizeTask(DailyTaskDefinition task, Checks checks) {
        task.id = trim(task.id).toLowerCase(Locale.ROOT);
        if (!task.id.matches("[a-z0-9_]{1,40}")) throw invalid("bad_id", task.id);
        DailyTaskType type = task.taskType();
        if (type == null) throw invalid("bad_type", task.id);
        task.type = type.id();
        task.title = trim(task.title);
        if (task.title.isEmpty() || task.title.length() > 32) throw invalid("bad_title", task.id);
        task.description = trim(task.description);
        if (task.description.length() > 120) throw invalid("description_too_long", task.id);
        if (type == DailyTaskType.LOGIN) task.target = 1;
        if (task.target < 1 || task.target > type.maxTarget()) throw invalid("bad_target", task.id);
        if (!type.supportsSingleMatch()) task.singleMatch = false;

        task.roleMode = oneOf(task.roleMode, "any", "any", "faction", "role");
        task.factions = cleanList(task.factions, task.id);
        task.roles = cleanList(task.roles, task.id);
        if (!type.supportsRoleFilter()) {
            task.roleMode = "any";
            task.factions.clear();
            task.roles.clear();
        }
        for (String faction : task.factions) if (!DailyFactions.isFaction(faction)) throw invalid("bad_faction", faction);
        for (String role : task.roles) if (!isResourceId(role)) throw invalid("bad_role", role);
        if ("faction".equals(task.roleMode) && task.factions.isEmpty()) throw invalid("empty_factions", task.id);
        if ("role".equals(task.roleMode) && task.roles.isEmpty()) throw invalid("empty_roles", task.id);

        task.matchResult = oneOf(task.matchResult, "any", "any", "win", "lose", "survived", "eliminated");
        task.victimMode = oneOf(task.victimMode, "any", "any", "enemy", "faction");
        task.deathReasons = cleanList(task.deathReasons, task.id);
        task.victimFactions = cleanList(task.victimFactions, task.id);
        task.quests = cleanList(task.quests, task.id);
        task.items = cleanList(task.items, task.id);
        task.actions = cleanList(task.actions, task.id);
        task.crates = cleanList(task.crates, task.id);
        task.cards = cleanList(task.cards, task.id);
        for (String faction : task.victimFactions) if (!DailyFactions.isFaction(faction)) throw invalid("bad_faction", faction);
        if ("faction".equals(task.victimMode) && task.victimFactions.isEmpty()) throw invalid("empty_victims", task.id);
        for (String reason : task.deathReasons) if (!isResourceId(reason)) throw invalid("bad_death_reason", reason);
        for (String item : task.items) if (!isResourceId(item)) throw invalid("bad_item", item);
        for (String action : task.actions) if (!DailyTaskCatalog.ACTIONS.contains(action)) throw invalid("bad_action", action);
        for (String card : task.cards) if (!DailyTaskCatalog.CARD_USES.contains(card)) throw invalid("bad_card", card);
        // Only keep the filters the type actually reads, so the saved file mirrors the behaviour.
        if (type != DailyTaskType.PLAY_MATCH) task.matchResult = "any";
        if (type != DailyTaskType.KILL) { task.deathReasons.clear(); task.victimMode = "any"; task.victimFactions.clear(); }
        if (type != DailyTaskType.FINISH_TASK) task.quests.clear();
        if (type != DailyTaskType.USE_ITEM && type != DailyTaskType.SHOP_BUY) task.items.clear();
        if (type != DailyTaskType.SPECIAL_ACTION) task.actions.clear();
        if (type != DailyTaskType.OPEN_CRATE) task.crates.clear();
        if (type != DailyTaskType.USE_CARD) task.cards.clear();

        task.delivery = oneOf(task.delivery, "direct", "direct", "mail");
        if (task.rewards == null) task.rewards = new ArrayList<>();
        task.rewards.removeIf(java.util.Objects::isNull);
        if (task.rewards.isEmpty()) throw invalid("no_rewards", task.id);
        if (task.rewards.size() > MAX_REWARDS) throw invalid("too_many_rewards", task.id);
        for (DailyRewardEntry reward : task.rewards) normalizeReward(task.id, reward, checks);
        task.rewardLabel = trim(task.rewardLabel);
        if (task.rewardLabel.length() > 64) throw invalid("reward_label_too_long", task.id);
        if (task.rewardLabel.isEmpty()) task.rewardLabel = fallbackRewardLabel(task.rewards);
    }

    /** Shared with the daily shop, whose goods are the same reward lines. */
    public static void normalizeReward(String taskId, DailyRewardEntry reward, Checks checks) {
        reward.kind = trim(reward.kind).toLowerCase(Locale.ROOT);
        if (!DailyRewardEntry.isKind(reward.kind)) throw invalid("bad_reward", taskId);
        reward.id = trim(reward.id);
        if (DailyRewardEntry.isUnique(reward.kind)) reward.amount = 1;
        if (reward.amount < 1 || reward.amount > MAX_REWARD_AMOUNT) throw invalid("bad_reward_amount", taskId);
        if (!DailyRewardEntry.needsId(reward.kind)) {
            reward.id = "";
            return;
        }
        if (reward.id.isEmpty()) throw invalid("reward_missing_id", taskId);
        switch (reward.kind) {
            case DailyRewardEntry.CARD -> {
                reward.id = reward.id.toLowerCase(Locale.ROOT);
                if (!DailyRewardEntry.isCardType(reward.id)) throw invalid("bad_reward", taskId);
            }
            case DailyRewardEntry.SKIN -> {
                if (!reward.id.contains("/") || !checks.skinExists(reward.id)) throw invalid("unknown_skin", reward.id);
            }
            case DailyRewardEntry.CRATE, DailyRewardEntry.KEY -> {
                reward.id = reward.id.toLowerCase(Locale.ROOT);
                if (!DailyRewardEntry.RANDOM.equals(reward.id) && !checks.crateExists(reward.id))
                    throw invalid("unknown_crate", reward.id);
            }
            case DailyRewardEntry.TITLE -> {
                if (reward.id.length() > 32) throw invalid("bad_title_reward", taskId);
            }
            default -> { }
        }
    }

    /** Server-side label used when the editor left the label empty (no client language available). */
    public static String fallbackRewardLabel(List<DailyRewardEntry> rewards) {
        StringBuilder out = new StringBuilder();
        for (DailyRewardEntry reward : rewards) {
            if (!out.isEmpty()) out.append("、");
            String name = switch (reward.kind) {
                case DailyRewardEntry.GREEN_APPLES -> "绿苹果";
                case DailyRewardEntry.CARD -> "角色卡";
                case DailyRewardEntry.SELF_SELECT -> "自选卡";
                case DailyRewardEntry.LIMIT_BREAK -> "突破上限卡";
                case DailyRewardEntry.SKIN -> "皮肤";
                case DailyRewardEntry.CRATE -> "箱子";
                case DailyRewardEntry.KEY -> "钥匙";
                case DailyRewardEntry.TITLE -> "称号「" + reward.id + "」";
                default -> reward.kind;
            };
            out.append(name);
            if (!DailyRewardEntry.isUnique(reward.kind)) out.append(" ×").append(reward.amount);
        }
        String label = out.toString();
        return label.length() <= 64 ? label : label.substring(0, 63) + "…";
    }

    private static List<String> cleanList(List<String> values, String taskId) {
        List<String> out = new ArrayList<>();
        if (values != null) for (String value : values) {
            String v = trim(value);
            if (v.isEmpty() || out.contains(v)) continue;
            if (v.length() > 128) throw invalid("filter_too_long", taskId);
            out.add(v);
        }
        if (out.size() > MAX_FILTER_ENTRIES) throw invalid("filter_too_long", taskId);
        return out;
    }

    static boolean isResourceId(String value) {
        return value != null && value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }

    private static String oneOf(String value, String fallback, String... allowed) {
        String v = trim(value).toLowerCase(Locale.ROOT);
        for (String a : allowed) if (a.equals(v)) return a;
        return fallback;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String code, String detail) {
        return new IllegalArgumentException(code + (detail == null || detail.isEmpty() ? "" : ":" + detail));
    }

    /**
     * First-run configuration: the classic login reward stays active; the other examples are
     * disabled templates showing what the editor can do, so an update never changes the economy.
     */
    public static DailyTaskConfig defaults() {
        DailyTaskConfig config = new DailyTaskConfig();
        config.randomEnabled = false;
        DailyTaskDefinition login = task(LOGIN_TASK_ID, "每日登录", "登录游戏后即可领取", DailyTaskType.LOGIN, 1);
        login.rewards.add(new DailyRewardEntry(DailyRewardEntry.GREEN_APPLES, "", 160));
        login.rewardLabel = "绿苹果 ×160";
        login.autoDescription = false;
        config.tasks.add(login);

        DailyTaskDefinition play = task("play_matches", "列车常客", "参与 3 场对局", DailyTaskType.PLAY_MATCH, 3);
        play.rewards.add(new DailyRewardEntry(DailyRewardEntry.GREEN_APPLES, "", 60));
        config.tasks.add(disabled(play));

        DailyTaskDefinition killer = task("killer_kills", "暗夜猎手", "以杀手阵营身份击杀 2 名敌对玩家", DailyTaskType.KILL, 2);
        killer.roleMode = "faction";
        killer.factions.add(DailyFactions.KILLER);
        killer.victimMode = "enemy";
        killer.rewards.add(new DailyRewardEntry(DailyRewardEntry.KEY, DailyRewardEntry.RANDOM, 1));
        config.tasks.add(disabled(killer));

        DailyTaskDefinition survive = task("survive_minutes", "顽强乘客", "在对局中累计存活 15 分钟", DailyTaskType.SURVIVE_TIME, 15);
        survive.rewards.add(new DailyRewardEntry(DailyRewardEntry.GREEN_APPLES, "", 40));
        config.tasks.add(disabled(survive));

        DailyTaskDefinition chores = task("finish_tasks", "尽职尽责", "完成 5 个对局任务", DailyTaskType.FINISH_TASK, 5);
        chores.rewards.add(new DailyRewardEntry(DailyRewardEntry.CARD, "civilian", 1));
        config.tasks.add(disabled(chores));

        DailyTaskDefinition winner = task("win_civilian", "正义必胜", "以平民阵营身份获胜 1 场", DailyTaskType.PLAY_MATCH, 1);
        winner.matchResult = "win";
        winner.roleMode = "faction";
        winner.factions.add(DailyFactions.CIVILIAN);
        winner.rewards.add(new DailyRewardEntry(DailyRewardEntry.CRATE, DailyRewardEntry.RANDOM, 1));
        config.tasks.add(disabled(winner));

        DailyTaskDefinition all = task("daily_all", "全勤奖励", "领取 3 个其他每日任务", DailyTaskType.CLAIM_TASKS, 3);
        all.rewards.add(new DailyRewardEntry(DailyRewardEntry.SELF_SELECT, "", 1));
        config.tasks.add(disabled(all));
        return config;
    }

    private static DailyTaskDefinition task(String id, String title, String description, DailyTaskType type, int target) {
        DailyTaskDefinition task = new DailyTaskDefinition();
        task.id = id;
        task.title = title;
        task.description = description;
        task.type = type.id();
        task.target = target;
        return task;
    }

    private static DailyTaskDefinition disabled(DailyTaskDefinition task) {
        task.enabled = false;
        return task;
    }
}
