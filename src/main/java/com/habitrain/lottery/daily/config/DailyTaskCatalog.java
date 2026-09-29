package com.habitrain.lottery.daily.config;

import java.util.List;

/**
 * Known vocabularies offered by the editor pickers. Ids match what upstream reports at runtime;
 * the editor still accepts custom ids for quests, items and death reasons that addon mods add.
 */
public final class DailyTaskCatalog {
    private DailyTaskCatalog() { }

    /** Special actions recorded by the match replay, plus revolver hits from {@code OnRevolverUsed}. */
    public static final List<String> ACTIONS = List.of(
            "gun_hit", "bomb_defuse", "bomb_detonate", "trap_triggered", "disguise",
            "door_pry", "door_seal", "rope_pull");

    /** Card kinds a USE_CARD task can count. */
    public static final List<String> CARD_USES = List.of(
            "civilian", "killer", "neutral", "neutral_for_killer", "self_select", "limit_break");

    /** Quest names passed to {@code RoleMethodDispatcher.callOnFinishQuest}. */
    public static final List<String> QUESTS = List.of(
            "sleep", "eat", "drink", "read_book", "exercise", "meditate", "bathe", "toilet", "chair",
            "note_block", "be_alone", "outside", "breathe", "light_stove", "clean_dust", "transport",
            "pray", "prune_bush", "harvest_crop", "scene_task", "task.manic.name");

    /** Death reasons from {@code GameConstants.DeathReasons} that a player can cause. */
    public static final List<String> DEATH_REASONS = List.of(
            "starrailexpress:knife_stab", "starrailexpress:revolver_shot", "starrailexpress:derringer_shot",
            "starrailexpress:gun_shot", "starrailexpress:sniper_rifle", "starrailexpress:zero_one_five_shot",
            "starrailexpress:grenade", "starrailexpress:bat_hit", "starrailexpress:nunchuck_hit",
            "starrailexpress:poison", "starrailexpress:arrow", "starrailexpress:trident",
            "starrailexpress:execute", "starrailexpress:general_attack", "starrailexpress:fell_out_of_train",
            "starrailexpress:flamethrower_burned", "starrailexpress:generic");

    /** Item namespaces offered first by the item picker. */
    public static final List<String> ITEM_NAMESPACES = List.of(
            "starrailexpress", "noellesroles", "stupid_express", "harpymodloader", "wathe");
}
