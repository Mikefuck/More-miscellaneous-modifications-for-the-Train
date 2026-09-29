package com.habitrain.lottery.daily.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DailyTaskConfigTest {

    private static DailyTaskDefinition task(String id, DailyTaskType type, int target) {
        DailyTaskDefinition t = new DailyTaskDefinition();
        t.id = id;
        t.title = "T " + id;
        t.type = type.id();
        t.target = target;
        t.rewards.add(new DailyRewardEntry(DailyRewardEntry.GREEN_APPLES, "", 10));
        return t;
    }

    private static DailyTaskConfig config(DailyTaskDefinition... tasks) {
        DailyTaskConfig c = new DailyTaskConfig();
        c.tasks.addAll(List.of(tasks));
        return c;
    }

    private static String code(Runnable r) {
        return assertThrows(IllegalArgumentException.class, r::run).getMessage();
    }

    @Test
    void defaultsAreValidAndKeepClassicLoginReward() {
        DailyTaskConfig defaults = DailyTaskConfig.defaults();
        assertDoesNotThrow(() -> defaults.normalize(DailyTaskConfig.Checks.LENIENT));
        DailyTaskDefinition login = defaults.find(DailyTaskConfig.LOGIN_TASK_ID);
        assertNotNull(login);
        assertTrue(login.enabled);
        assertEquals(160, login.rewards.getFirst().amount);
        // Every other shipped example is a disabled template so updates never change the economy.
        assertEquals(1, defaults.tasks.stream().filter(t -> t.enabled).count());
    }

    @Test
    void validatorRejectsBrokenTasks() {
        assertTrue(code(() -> config(task("Bad Id", DailyTaskType.KILL, 1)).normalize(null)).startsWith("bad_id"));
        assertTrue(code(() -> config(task("a", DailyTaskType.KILL, 0)).normalize(null)).startsWith("bad_target"));
        assertTrue(code(() -> config(task("a", DailyTaskType.KILL, 101)).normalize(null)).startsWith("bad_target"));
        assertTrue(code(() -> config(task("a", DailyTaskType.KILL, 1), task("a", DailyTaskType.KILL, 1)).normalize(null))
                .startsWith("duplicate_id"));
        DailyTaskDefinition noReward = task("a", DailyTaskType.KILL, 1);
        noReward.rewards.clear();
        assertTrue(code(() -> config(noReward).normalize(null)).startsWith("no_rewards"));
        DailyTaskDefinition emptyFaction = task("a", DailyTaskType.KILL, 1);
        emptyFaction.roleMode = "faction";
        assertTrue(code(() -> config(emptyFaction).normalize(null)).startsWith("empty_factions"));
        DailyTaskDefinition badCrate = task("a", DailyTaskType.KILL, 1);
        badCrate.rewards.add(new DailyRewardEntry(DailyRewardEntry.CRATE, "missing", 1));
        DailyTaskConfig.Checks noCrates = new DailyTaskConfig.Checks() {
            public boolean skinExists(String entry) { return true; }
            public boolean crateExists(String crateId) { return false; }
        };
        assertTrue(code(() -> config(badCrate).normalize(noCrates)).startsWith("unknown_crate"));
    }

    @Test
    void randomCrateRewardNeedsNoCatalogueEntry() {
        DailyTaskDefinition t = task("a", DailyTaskType.KILL, 1);
        t.rewards.add(new DailyRewardEntry(DailyRewardEntry.KEY, "*", 2));
        DailyTaskConfig.Checks noCrates = new DailyTaskConfig.Checks() {
            public boolean skinExists(String entry) { return true; }
            public boolean crateExists(String crateId) { return false; }
        };
        assertDoesNotThrow(() -> config(t).normalize(noCrates));
    }

    @Test
    void normalizeDropsFiltersTheTypeIgnores() {
        DailyTaskDefinition login = task("login", DailyTaskType.LOGIN, 5);
        login.roleMode = "faction";
        login.factions.add(DailyFactions.KILLER);
        login.deathReasons.add("starrailexpress:knife_stab");
        login.singleMatch = true;
        DailyTaskDefinition skin = task("skin", DailyTaskType.PLAY_MATCH, 1);
        skin.rewards.add(new DailyRewardEntry(DailyRewardEntry.SKIN, "knife/x", 7));
        config(login, skin).normalize(null);
        assertEquals(1, login.target);
        assertEquals("any", login.roleMode);
        assertTrue(login.factions.isEmpty());
        assertTrue(login.deathReasons.isEmpty());
        assertFalse(login.singleMatch);
        assertEquals(1, skin.rewards.get(1).amount, "skins unlock once");
        assertFalse(skin.rewardLabel.isEmpty(), "server fills an empty reward label");
    }

    @Test
    void killFiltersMatchFactionWeaponAndVictim() {
        DailyTaskDefinition t = task("k", DailyTaskType.KILL, 3);
        t.roleMode = "faction";
        t.factions.add(DailyFactions.KILLER);
        t.deathReasons.add("starrailexpress:knife_stab");
        t.victimMode = "enemy";
        config(t).normalize(null);
        DailyTaskEvent knifeCivilian = new DailyTaskEvent(DailyTaskType.KILL, 1, "starrailexpress:killer",
                DailyFactions.KILLER, "starrailexpress:knife_stab", DailyFactions.CIVILIAN, false, false);
        assertTrue(knifeCivilian.matches(t));
        assertFalse(new DailyTaskEvent(DailyTaskType.KILL, 1, "x:y", DailyFactions.KILLER,
                "starrailexpress:revolver_shot", DailyFactions.CIVILIAN, false, false).matches(t), "wrong weapon");
        assertFalse(new DailyTaskEvent(DailyTaskType.KILL, 1, "x:y", DailyFactions.KILLER,
                "starrailexpress:knife_stab", DailyFactions.NEUTRAL_FOR_KILLER, false, false).matches(t), "teammate");
        assertFalse(new DailyTaskEvent(DailyTaskType.KILL, 1, "x:y", DailyFactions.CIVILIAN,
                "starrailexpress:knife_stab", DailyFactions.KILLER, false, false).matches(t), "wrong faction");
        t.enabled = false;
        assertFalse(knifeCivilian.matches(t), "disabled tasks never match");
    }

    @Test
    void roleAndMatchResultFilters() {
        DailyTaskDefinition t = task("w", DailyTaskType.PLAY_MATCH, 1);
        t.roleMode = "role";
        t.roles.add("noellesroles:doctor");
        t.matchResult = "win";
        config(t).normalize(null);
        assertTrue(new DailyTaskEvent(DailyTaskType.PLAY_MATCH, 1, "noellesroles:doctor", DailyFactions.CIVILIAN,
                null, null, true, false).matches(t));
        assertFalse(new DailyTaskEvent(DailyTaskType.PLAY_MATCH, 1, "noellesroles:doctor", DailyFactions.CIVILIAN,
                null, null, false, true).matches(t));
        assertFalse(new DailyTaskEvent(DailyTaskType.PLAY_MATCH, 1, "starrailexpress:civilian", DailyFactions.CIVILIAN,
                null, null, true, true).matches(t));
    }

    @Test
    void emptyListFiltersAcceptFutureContent() {
        DailyTaskDefinition crate = task("c", DailyTaskType.OPEN_CRATE, 2);
        config(crate).normalize(null);
        assertTrue(DailyTaskEvent.simple(DailyTaskType.OPEN_CRATE, 1, "brand_new_crate").matches(crate));
        crate.crates.add("woodland");
        assertFalse(DailyTaskEvent.simple(DailyTaskType.OPEN_CRATE, 1, "brand_new_crate").matches(crate));
        assertTrue(DailyTaskEvent.simple(DailyTaskType.OPEN_CRATE, 1, "woodland").matches(crate));
    }

    @Test
    void randomPoolIsStablePerPlayerAndDay() {
        DailyTaskDefinition pinned = task("pinned", DailyTaskType.LOGIN, 1);
        DailyTaskConfig c = config(pinned);
        for (int i = 0; i < 6; i++) {
            DailyTaskDefinition r = task("pool_" + i, DailyTaskType.KILL, 1);
            r.pinned = false;
            c.tasks.add(r);
        }
        c.randomEnabled = true;
        c.randomPick = 2;
        c.normalize(null);
        UUID player = UUID.randomUUID();
        List<DailyTaskDefinition> today = c.activeFor(player, 20_000);
        assertEquals(3, today.size());
        assertTrue(today.contains(pinned));
        assertEquals(today, c.activeFor(player, 20_000), "same player and day, same draw");
        c.randomPick = 0;
        assertEquals(List.of(pinned), c.activeFor(player, 20_000), "0 draws nothing, pinned tasks stay");
        c.randomEnabled = false;
        assertEquals(7, c.activeFor(player, 20_000).size(), "draw off shows every enabled task");
    }

    @Test
    void randomDrawDiffersBetweenPlayersAndKeepsTouchedTasks() {
        DailyTaskConfig c = config();
        for (int i = 0; i < 12; i++) {
            DailyTaskDefinition r = task("pool_" + i, DailyTaskType.KILL, 1);
            r.pinned = false;
            c.tasks.add(r);
        }
        c.randomEnabled = true;
        c.randomPick = 3;
        c.normalize(null);
        java.util.Set<List<DailyTaskDefinition>> draws = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) draws.add(c.activeFor(UUID.randomUUID(), 20_000));
        assertTrue(draws.size() > 1, "players are not synchronised");

        UUID player = UUID.randomUUID();
        DailyTaskDefinition outside = c.tasks.stream()
                .filter(t -> !c.activeFor(player, 20_000).contains(t)).findFirst().orElseThrow();
        List<DailyTaskDefinition> kept = c.activeFor(player, 20_000, outside.id::equals);
        assertEquals(3, kept.size());
        assertTrue(kept.contains(outside), "a task with progress today stays on the board");
    }

    @Test
    void legacyRandomPickTurnsTheDrawOn() {
        DailyTaskConfig legacy = config();
        legacy.randomEnabled = null;
        legacy.randomPick = 2;
        legacy.normalize(null);
        assertTrue(legacy.randomOn());
        DailyTaskConfig showAll = config();
        showAll.randomEnabled = null;
        showAll.randomPick = 0;
        showAll.normalize(null);
        assertFalse(showAll.randomOn());
        assertEquals(DailyTaskConfig.DEFAULT_RANDOM_PICK, showAll.randomPick);
    }

    @Test
    void survivalConvertsSecondsToMinutesAndSingleMatchTracksBest() {
        UUID player = UUID.randomUUID();
        DailyTaskDefinition cumulative = task("s", DailyTaskType.SURVIVE_TIME, 10);
        int minutes = 0;
        for (int second = 0; second < 150; second++) {
            minutes += DailyTaskTracker.progressDelta(player, cumulative,
                    DailyTaskEvent.inMatch(DailyTaskType.SURVIVE_TIME, 1, null, null, null), minutes);
        }
        assertEquals(2, minutes, "150 s = 2 whole minutes, 30 s carried");

        DailyTaskDefinition single = task("k", DailyTaskType.KILL, 3);
        single.singleMatch = true;
        DailyTaskEvent kill = DailyTaskEvent.inMatch(DailyTaskType.KILL, 1, null, null, null);
        assertEquals(1, DailyTaskTracker.progressDelta(player, single, kill, 0));
        assertEquals(1, DailyTaskTracker.progressDelta(player, single, kill, 1));
        assertEquals(1, DailyTaskTracker.progressDelta(player, single, kill, 2));
        assertEquals(0, DailyTaskTracker.progressDelta(player, single, kill, 3), "capped at target");
    }

    @Test
    void hostileSides() {
        assertTrue(DailyFactions.hostile(DailyFactions.KILLER, DailyFactions.VIGILANTE));
        assertFalse(DailyFactions.hostile(DailyFactions.CIVILIAN, DailyFactions.VIGILANTE));
        assertFalse(DailyFactions.hostile(DailyFactions.KILLER, DailyFactions.NEUTRAL_FOR_KILLER));
        assertTrue(DailyFactions.hostile(DailyFactions.NEUTRAL, DailyFactions.NEUTRAL));
    }
}
