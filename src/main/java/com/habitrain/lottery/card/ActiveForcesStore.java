package com.habitrain.lottery.card;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Durable crash-recovery mirror for active lobby card reservations ({@code I-12}).
 *
 * <p>Both {@link ActiveCardForces} and {@link SelfSelectForces} debit cards and daily
 * quotas immediately in the lobby and settle them when a match starts or aborts.
 * Clean server shutdown already refunds pending reservations via {@code SERVER_STOPPING},
 * whereas an abrupt process termination ({@code kill -9} / power loss) skips shutdown
 * hooks. Mirroring active reservations to {@code <world>/habitrain_lottery/cards/active_forces.json}
 * allows the next {@code SERVER_STARTED} to refund any unhonored reservation idempotently
 * using its persisted {@code refundId}.
 */
public final class ActiveForcesStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ThreadLocal<Boolean> RECOVERING = ThreadLocal.withInitial(() -> false);

    public static final class FileRoot {
        public int version = 1;
        public Map<String, FactionEntry> factionForces = new LinkedHashMap<>();
        public Map<String, SelfSelectEntry> selfSelectForces = new LinkedHashMap<>();
    }

    public static final class FactionEntry {
        public int roleType;
        public String refundId;
        public long epochDay;

        public FactionEntry() {
        }

        public FactionEntry(int roleType, String refundId, long epochDay) {
            this.roleType = roleType;
            this.refundId = refundId;
            this.epochDay = epochDay;
        }
    }

    public static final class SelfSelectEntry {
        public String roleId;
        public String refundId;
        public long epochDay;

        public SelfSelectEntry() {
        }

        public SelfSelectEntry(String roleId, String refundId, long epochDay) {
            this.roleId = roleId;
            this.refundId = refundId;
            this.epochDay = epochDay;
        }
    }

    private ActiveForcesStore() {
    }

    public static Path filePath() {
        if (!WorldLotteryPaths.ready() || WorldLotteryPaths.root() == null) {
            return null;
        }
        return WorldLotteryPaths.root().resolve("cards").resolve("active_forces.json");
    }

    /**
     * Atomically writes the current in-memory reservations of {@link ActiveCardForces}
     * and {@link SelfSelectForces} to {@code cards/active_forces.json} when world paths
     * are ready.
     */
    public static synchronized void sync() {
        if (!WorldLotteryPaths.ready() || Boolean.TRUE.equals(RECOVERING.get())) {
            return;
        }
        Path file = filePath();
        if (file == null) {
            return;
        }
        try {
            var existing = AtomicJsonFiles.readJson(file, FileRoot.class, GSON);
            if (existing.corrupt()) {
                HabiLotteryMod.LOGGER.error("Corrupt active_forces.json at {}; refusing to overwrite", file);
                return;
            }
            FileRoot root = new FileRoot();
            for (var entry : ActiveCardForces.reservationsSnapshot().entrySet()) {
                UUID uid = entry.getKey();
                ActiveCardForces.Reservation res = entry.getValue();
                if (uid != null && res != null && res.refundId() != null
                        && !CardActivationJournal.contains(uid, res.refundId())) {
                    root.factionForces.put(uid.toString(),
                            new FactionEntry(res.roleType(), res.refundId().toString(), res.epochDay()));
                }
            }
            for (var entry : SelfSelectForces.reservationsSnapshot().entrySet()) {
                UUID uid = entry.getKey();
                SelfSelectForces.Reservation res = entry.getValue();
                if (uid != null && res != null && res.roleId() != null && res.refundId() != null
                        && !CardActivationJournal.contains(uid, res.refundId())) {
                    root.selfSelectForces.put(uid.toString(),
                            new SelfSelectEntry(res.roleId().toString(), res.refundId().toString(), res.epochDay()));
                }
            }
            if (!AtomicJsonFiles.writeJson(file, root, GSON, false, !existing.usedBackup())) {
                HabiLotteryMod.LOGGER.error("Failed persisting active_forces.json at {}", file);
            }
        } catch (RuntimeException e) {
            HabiLotteryMod.LOGGER.error("Failed syncing active_forces.json", e);
        }
    }

    /**
     * Startup recovery hook: loads any unhonored lobby reservations left behind by an
     * unclean shutdown and refunds them idempotently. Entries whose refund cannot be
     * durably written remain in {@code active_forces.json} for a subsequent retry.
     */
    public static synchronized void recoverOnServerStarted() {
        if (!WorldLotteryPaths.ready()) {
            return;
        }
        Path file = filePath();
        if (file == null) {
            return;
        }
        AtomicJsonFiles.JsonLoad<FileRoot> load = AtomicJsonFiles.readJson(file, FileRoot.class, GSON);
        if (load.isMissing()) {
            return;
        }
        if (load.corrupt() || load.value() == null) {
            HabiLotteryMod.LOGGER.error("Cannot recover active card reservations from corrupt {}", file);
            return;
        }
        FileRoot root = load.value();
        boolean hadEntries = (root.factionForces != null && !root.factionForces.isEmpty())
                || (root.selfSelectForces != null && !root.selfSelectForces.isEmpty());
        if (!hadEntries) {
            if (load.usedBackup()) {
                AtomicJsonFiles.writeJson(file, root, GSON, false, false);
            }
            return;
        }

        long fallbackDay = LoginRewardService.todayEpochDayUtc();
        RECOVERING.set(true);
        try {
            if (root.factionForces != null) {
                for (Map.Entry<String, FactionEntry> e : root.factionForces.entrySet()) {
                    UUID uid = parseUuid(e.getKey());
                    FactionEntry val = e.getValue();
                    if (uid == null || val == null) {
                        continue;
                    }
                    UUID refundId = parseUuid(val.refundId);
                    if (refundId == null) {
                        refundId = UUID.nameUUIDFromBytes(("faction-crash:" + uid + ":" + val.roleType + ":" + val.epochDay)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    long day = val.epochDay > 0 ? val.epochDay : fallbackDay;
                    ActiveCardForces.restore(uid, val.roleType, refundId, day);
                }
            }
            if (root.selfSelectForces != null) {
                for (Map.Entry<String, SelfSelectEntry> e : root.selfSelectForces.entrySet()) {
                    UUID uid = parseUuid(e.getKey());
                    SelfSelectEntry val = e.getValue();
                    if (uid == null || val == null || val.roleId == null || val.roleId.isBlank()) {
                        continue;
                    }
                    ResourceLocation rl = ResourceLocation.tryParse(val.roleId);
                    if (rl == null) {
                        continue;
                    }
                    UUID refundId = parseUuid(val.refundId);
                    if (refundId == null) {
                        refundId = UUID.nameUUIDFromBytes(("self-crash:" + uid + ":" + rl + ":" + val.epochDay)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    long day = val.epochDay > 0 ? val.epochDay : fallbackDay;
                    SelfSelectForces.restore(uid, rl, refundId, day);
                }
            }
        } finally {
            RECOVERING.remove();
        }

        for (UUID uid : ActiveCardForces.snapshot().keySet()) {
            try {
                if (!CardUseService.refundForcedCard(uid)) {
                    HabiLotteryMod.LOGGER.warn("Crash-recovery faction-card refund remains pending for {}", uid);
                }
            } catch (RuntimeException ex) {
                HabiLotteryMod.LOGGER.error("Crash-recovery faction-card refund failed for {}", uid, ex);
            }
        }
        for (UUID uid : SelfSelectForces.snapshot().keySet()) {
            try {
                if (CardUseService.refundSelfSelect(uid, FactionCardType.NONE)) {
                    SelfSelectForces.remove(uid);
                } else {
                    HabiLotteryMod.LOGGER.warn("Crash-recovery self-select refund remains pending for {}", uid);
                }
            } catch (RuntimeException ex) {
                HabiLotteryMod.LOGGER.error("Crash-recovery self-select refund failed for {}", uid, ex);
            }
        }
        // Clear any unparseable leftover records when all valid entries finished refunding.
        if (ActiveCardForces.isEmpty() && SelfSelectForces.isEmpty()) {
            sync();
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
