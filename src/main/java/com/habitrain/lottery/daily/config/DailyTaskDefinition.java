package com.habitrain.lottery.daily.config;

import java.util.ArrayList;
import java.util.List;

/**
 * One OP-configured daily task, persisted in {@code config/daily_tasks.json} and edited by the
 * Mod Menu "每日任务" console. Plain fields so Gson can round-trip the draft between server and
 * editor without an adapter; {@link DailyTaskConfig#normalize} is the only authority on validity.
 */
public final class DailyTaskDefinition {
    /** Local id; the board id is {@code habitrain_lottery:<id>}. */
    public String id = "";
    public boolean enabled = true;
    /** Always shown; {@code false} puts the task into the per-player daily random pool. */
    public boolean pinned = true;
    public String title = "";
    public String description = "";
    /** Editor hint: regenerate {@link #description} from the conditions on every edit. */
    public boolean autoDescription = true;
    public String type = DailyTaskType.LOGIN.id();
    public int target = 1;
    /** Progress must be reached inside one match (best single match counts). */
    public boolean singleMatch;

    /** {@code any}, {@code faction} or {@code role}. */
    public String roleMode = "any";
    public List<String> factions = new ArrayList<>();
    public List<String> roles = new ArrayList<>();

    /** {@code any}, {@code win}, {@code lose}, {@code survived}, {@code eliminated} (PLAY_MATCH). */
    public String matchResult = "any";
    /** Death-reason ids (KILL); empty = any weapon. */
    public List<String> deathReasons = new ArrayList<>();
    /** {@code any}, {@code enemy} or {@code faction} (KILL). */
    public String victimMode = "any";
    public List<String> victimFactions = new ArrayList<>();
    /** Quest names (FINISH_TASK); empty = any. */
    public List<String> quests = new ArrayList<>();
    /** Item ids (USE_ITEM / SHOP_BUY); empty = any. */
    public List<String> items = new ArrayList<>();
    /** Special action ids (SPECIAL_ACTION); empty = any. */
    public List<String> actions = new ArrayList<>();
    /** Crate ids (OPEN_CRATE); empty = any crate, including crates added later. */
    public List<String> crates = new ArrayList<>();
    /** Card kinds (USE_CARD); empty = any. */
    public List<String> cards = new ArrayList<>();

    public List<DailyRewardEntry> rewards = new ArrayList<>();
    public String rewardLabel = "";
    public boolean autoRewardLabel = true;
    /** {@code direct}: credited on claim; {@code mail}: a claim sends a mailbox letter. */
    public String delivery = "direct";

    public DailyTaskType taskType() {
        return DailyTaskType.fromId(type);
    }

    public DailyTaskDefinition copy() {
        DailyTaskDefinition c = new DailyTaskDefinition();
        c.id = id; c.enabled = enabled; c.pinned = pinned; c.title = title; c.description = description;
        c.autoDescription = autoDescription; c.type = type; c.target = target; c.singleMatch = singleMatch;
        c.roleMode = roleMode; c.factions = new ArrayList<>(nn(factions)); c.roles = new ArrayList<>(nn(roles));
        c.matchResult = matchResult; c.deathReasons = new ArrayList<>(nn(deathReasons));
        c.victimMode = victimMode; c.victimFactions = new ArrayList<>(nn(victimFactions));
        c.quests = new ArrayList<>(nn(quests)); c.items = new ArrayList<>(nn(items));
        c.actions = new ArrayList<>(nn(actions)); c.crates = new ArrayList<>(nn(crates));
        c.cards = new ArrayList<>(nn(cards));
        c.rewards = new ArrayList<>();
        for (DailyRewardEntry reward : nn(rewards)) if (reward != null) c.rewards.add(reward.copy());
        c.rewardLabel = rewardLabel; c.autoRewardLabel = autoRewardLabel; c.delivery = delivery;
        return c;
    }

    private static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : list;
    }
}
