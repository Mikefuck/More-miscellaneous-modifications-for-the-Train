package com.habitrain.lottery.daily.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.daily.HabiDailyTaskApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server authority for {@code config/daily_tasks.json}: loads it at world start, validates every
 * editor save, and installs the enabled tasks on the daily board ({@link HabiDailyTaskApi}).
 */
public final class DailyTaskConfigService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String FILE = "daily_tasks.json";

    private static volatile DailyTaskConfig config = DailyTaskConfig.defaults();
    /** False while the file is corrupt: the board keeps working from memory but saves are refused. */
    private static boolean writable;
    private static String lastError = "";

    private DailyTaskConfigService() { }

    // Reads (board, tracker, claims) take the volatile snapshot without locking; the lock only
    // serialises load/save. Callers are on the server thread, the lock is defence in depth.

    /** Editor payload: the config draft plus the role roster the pickers need. */
    public record Snapshot(DailyTaskConfig config, List<RoleOption> roles, long epochDay, boolean writable) { }

    public record RoleOption(String id, String faction, boolean visible) { }

    public static synchronized void onServerStarted() {
        config = DailyTaskConfig.defaults();
        writable = false;
        if (WorldLotteryPaths.ready()) {
            var load = AtomicJsonFiles.readJson(WorldLotteryPaths.configFile(FILE), DailyTaskConfig.class, GSON);
            if (load.corrupt()) {
                HabiLotteryMod.LOGGER.error("Daily task config is corrupt; using built-in defaults until it is repaired");
                install();
                return;
            }
            if (load.ok() && load.value() != null) {
                DailyTaskConfig loaded = load.value();
                try {
                    // Lenient: a removed skin provider must not wipe the whole task list on boot;
                    // such a reward fails gracefully at claim time instead.
                    loaded.normalize(DailyTaskConfig.Checks.LENIENT);
                    config = loaded;
                } catch (IllegalArgumentException invalid) {
                    HabiLotteryMod.LOGGER.error("Daily task config is invalid ({}); using defaults, file left untouched",
                            invalid.getMessage());
                    install();
                    return;
                }
            } else {
                config.normalize(DailyTaskConfig.Checks.LENIENT);
                if (!save(config)) HabiLotteryMod.LOGGER.warn("Could not write default daily task config");
            }
            writable = true;
        }
        install();
    }

    public static synchronized void onServerStopping() {
        config = DailyTaskConfig.defaults();
        writable = false;
        HabiDailyTaskApi.setConfiguredTasks(List.of(), null);
    }

    public static synchronized DailyTaskConfig current() {
        return config;
    }

    public static synchronized String lastError() {
        return lastError;
    }

    public static synchronized String snapshotJson() {
        return GSON.toJson(new Snapshot(config.copy(), roles(), LoginRewardService.todayEpochDayUtc(), writable));
    }

    /**
     * Applies an editor save. Rejects stale revisions (someone else saved first) and anything the
     * strict validator refuses; on success the new tasks are live immediately.
     *
     * @return true when accepted; otherwise {@link #lastError()} carries the reason code
     */
    public static synchronized boolean apply(String json) {
        lastError = "";
        if (!writable) {
            lastError = "not_writable";
            return false;
        }
        DailyTaskConfig proposed;
        try {
            proposed = GSON.fromJson(json == null ? "" : json, DailyTaskConfig.class);
        } catch (RuntimeException malformed) {
            lastError = "malformed";
            return false;
        }
        if (proposed == null || proposed.schemaVersion != DailyTaskConfig.SCHEMA_VERSION) {
            lastError = "outdated";
            return false;
        }
        if (proposed.revision != config.revision) {
            lastError = "conflict";
            return false;
        }
        try {
            proposed.normalize(strictChecks());
        } catch (IllegalArgumentException invalid) {
            lastError = invalid.getMessage();
            return false;
        }
        proposed.revision = config.revision + 1;
        if (!save(proposed)) {
            lastError = "write_failed";
            return false;
        }
        config = proposed;
        install();
        return true;
    }

    /** Enabled definitions on this player's board today, in configured order. */
    public static List<DailyTaskDefinition> activeFor(ServerPlayer player) {
        if (player == null) return List.of();
        return config.activeFor(player.getUUID(), LoginRewardService.todayEpochDayUtc(), id -> touched(player, id));
    }

    /** True when the task is on this player's board by today's per-player draw (not pinned for everyone). */
    public static boolean isRandomPick(ResourceLocation id) {
        if (id == null || !HabiLotteryMod.MOD_ID.equals(id.getNamespace())) return false;
        return config.isRandomPool(config.find(id.getPath()));
    }

    /** Progressed or claimed today: such a task stays on the board even if the pool changes. */
    private static boolean touched(ServerPlayer player, String taskId) {
        ResourceLocation id = boardId(taskId);
        return HabiDailyTaskApi.progress(player, id) > 0 || HabiDailyTaskApi.claimed(player, id);
    }

    public static ResourceLocation boardId(String taskId) {
        return ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, taskId);
    }

    private static DailyTaskConfig.Checks strictChecks() {
        return new DailyTaskConfig.Checks() {
            public boolean skinExists(String entry) { return HabiSkinApi.fromEntry(entry).isPresent(); }
            public boolean crateExists(String crateId) {
                CrateCatalog.Entry entry = CrateCatalog.find(crateId);
                return entry != null && !entry.archived();
            }
        };
    }

    private static boolean save(DailyTaskConfig value) {
        return WorldLotteryPaths.ready()
                && AtomicJsonFiles.writeJson(WorldLotteryPaths.configFile(FILE), value, GSON, true);
    }

    private static void install() {
        List<HabiDailyTaskApi.Task> tasks = new ArrayList<>();
        for (DailyTaskDefinition def : config.tasks) {
            if (!def.enabled) continue;
            String taskId = def.id;
            String description = def.description.isEmpty() ? DailyTaskText.fallbackDescription(def) : def.description;
            try {
                tasks.add(new HabiDailyTaskApi.Task(boardId(taskId), def.title, description, def.target,
                        def.rewardLabel, (player, id, day) -> {
                            DailyTaskDefinition live = liveDefinition(taskId);
                            return live != null && DailyRewardService.grant(player, live, day);
                        }));
            } catch (IllegalArgumentException invalid) {
                HabiLotteryMod.LOGGER.warn("Skipping daily task {}: {}", taskId, invalid.getMessage());
            }
        }
        HabiDailyTaskApi.setConfiguredTasks(tasks, DailyTaskConfigService::isActive);
    }

    private static DailyTaskDefinition liveDefinition(String id) {
        DailyTaskDefinition def = config.find(id);
        return def != null && def.enabled ? def : null;
    }

    private static boolean isActive(ServerPlayer player, ResourceLocation id) {
        if (id == null || !HabiLotteryMod.MOD_ID.equals(id.getNamespace())) return false;
        List<DailyTaskDefinition> active = player == null
                ? config.activeFor(null, LoginRewardService.todayEpochDayUtc())
                : activeFor(player);
        for (DailyTaskDefinition def : active) {
            if (def.id.equals(id.getPath())) return true;
        }
        return false;
    }

    private static List<RoleOption> roles() {
        List<RoleOption> out = new ArrayList<>();
        try {
            Set<SRERole> visible = new HashSet<>(com.habitrain.core.api.role.v2.RoleVisibilityApi.filterVisible(TMMRoles.ROLES.values()));
            for (SRERole role : TMMRoles.ROLES.values()) {
                String id = DailyRoleFacts.id(role);
                if (id != null) out.add(new RoleOption(id, DailyRoleFacts.faction(role), visible.contains(role)));
            }
        } catch (RuntimeException | LinkageError unavailable) {
            HabiLotteryMod.LOGGER.warn("Role roster unavailable for the daily task editor", unavailable);
        }
        return out;
    }
}
