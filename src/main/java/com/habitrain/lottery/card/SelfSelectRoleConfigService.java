package com.habitrain.lottery.card;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.List;

/** Validates and persists before publishing a new server-authoritative policy. */
public final class SelfSelectRoleConfigService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final String FILE = "self_select_roles.json";
    private static Path boundFile;
    private static SelfSelectRoleConfig config = new SelfSelectRoleConfig();
    private static boolean readable;
    private static boolean writable;
    private static String loadError = "not_ready";

    private SelfSelectRoleConfigService() { }

    public record Snapshot(SelfSelectRoleConfig config, List<CardUseService.RoleCandidate> roles, boolean writable) { }

    public static synchronized void reset() {
        boundFile = null;
        config = new SelfSelectRoleConfig();
        readable = writable = false;
        loadError = "not_ready";
    }

    public static synchronized void load() {
        Path file = WorldLotteryPaths.configFile(FILE);
        if (file == null || file.equals(boundFile)) return;
        reset();
        boundFile = file;
        var loaded = AtomicJsonFiles.readJson(file, SelfSelectRoleConfig.class, GSON);
        if (loaded.corrupt()) {
            loadError = "corrupt";
            HabiLotteryMod.LOGGER.error("Self-select role config is corrupt; self-selection is blocked until repaired");
            return;
        }
        try {
            SelfSelectRoleConfig next = loaded.ok() ? loaded.value() : new SelfSelectRoleConfig();
            next.validate();
            config = next;
            readable = true;
            writable = loaded.ok() || AtomicJsonFiles.writeJson(file, config, GSON, true);
            loadError = writable ? "" : "write_failed";
        } catch (RuntimeException invalid) {
            loadError = "corrupt";
            HabiLotteryMod.LOGGER.error("Invalid self-select role config; original file retained", invalid);
        }
    }

    public static synchronized boolean isAllowed(ResourceLocation role) {
        load();
        return WorldLotteryPaths.ready() && readable && config.allows(role, SelfSelectForces::canonicalize);
    }

    public static synchronized String snapshotJson() {
        load();
        List<CardUseService.RoleCandidate> roles = CardUseService.listUnrestrictedCandidates(FactionCardType.NONE)
                .stream().map(CardUseService.RoleCandidate::new).toList();
        // Display restrictions written against an old alias on the current canonical row too.
        SelfSelectRoleConfig view = config.copy();
        view.disabledRoles.clear();
        for (String id : config.disabledRoles) {
            view.disabledRoles.add(SelfSelectForces.canonicalize(ResourceLocation.parse(id)).toString());
        }
        return GSON.toJson(new Snapshot(view, roles, writable));
    }

    public static synchronized String loadError() {
        load();
        return loadError;
    }

    /** Empty result means saved; all failures leave both the draft and live policy intact. */
    public static synchronized String apply(String json) {
        load();
        if (!writable) return loadError.isEmpty() ? "not_ready" : loadError;
        SelfSelectRoleConfig next;
        try {
            next = GSON.fromJson(json, SelfSelectRoleConfig.class);
            if (next == null) return "malformed";
            next.validate();
        } catch (RuntimeException invalid) {
            return "malformed";
        }
        if (next.revision != config.revision) return "conflict";
        next.revision++;
        if (!AtomicJsonFiles.writeJson(boundFile, next, GSON, true)) return "write_failed";
        config = next;
        return "";
    }
}
