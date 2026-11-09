package com.habitrain.lottery.mail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.AtomicJsonFiles.JsonLoad;
import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.time.LotteryDayClock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player mailbox JSON under {@code {world}/habitrain_lottery/mail/players/}.
 * Item attachments are not persisted in v1 (structured command rewards only).
 *
 * <p>v2 of the store is fully self-contained: the SRE {@code Mail} /
 * {@code MailboxComponent} classes were removed upstream, so the JSON schema
 * ({@link MailJson}) is now the single source of truth. The on-disk schema is
 * unchanged — existing mail files load without migration.
 */
public final class LocalMailboxStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final long EXPIRED_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final Set<Path> BACKUP_RECOVERED_PATHS = ConcurrentHashMap.newKeySet();

    private LocalMailboxStore() {
    }

    public static final class MailJson {
        public String id;
        public String sender;
        public String title;
        public String content;
        public boolean claimed;
        public boolean read;
        public long sentAt;
        public long expiresAt;
        public List<String> commands = new ArrayList<>();
    }

    public static final class FileRoot {
        public int version = 1;
        public List<MailJson> mails = new ArrayList<>();
    }

    public static final class MailLoad {
        private final JsonLoad.Status status;
        private final List<MailJson> mails;
        private final Path quarantined;

        private MailLoad(JsonLoad.Status status, List<MailJson> mails, Path quarantined) {
            this.status = status;
            this.mails = mails;
            this.quarantined = quarantined;
        }

        public JsonLoad.Status status() {
            return status;
        }

        public List<MailJson> mails() {
            return mails;
        }

        public Path quarantined() {
            return quarantined;
        }

        public boolean ok() {
            return status == JsonLoad.Status.OK || status == JsonLoad.Status.OK_BACKUP;
        }

        public boolean usedBackup() {
            return status == JsonLoad.Status.OK_BACKUP;
        }

        public boolean missing() {
            return status == JsonLoad.Status.MISSING;
        }

        public boolean corrupt() {
            return status == JsonLoad.Status.CORRUPT;
        }
    }

    /**
     * Legacy binary-compatible mailbox view used by older integrations.
     *
     * <p>This descriptor intentionally remains {@code (UUID) -> List}: the
     * western-cowboy mail bridge was compiled against it. New lottery code
     * must use {@link #loadWithStatus(UUID)} so corrupt files remain
     * distinguishable from an empty inbox.</p>
     */
    public static List<MailJson> load(UUID uuid) {
        MailLoad loaded = loadWithStatus(uuid);
        return loaded.mails() == null ? new ArrayList<>() : loaded.mails();
    }

    public static MailLoad loadWithStatus(UUID uuid) {
        if (!WorldLotteryPaths.ready() || uuid == null) {
            return new MailLoad(JsonLoad.Status.MISSING, new ArrayList<>(), null);
        }
        return loadFrom(MetaFeaturePaths.mailPlayer(uuid));
    }

    public static MailLoad loadFrom(Path path) {
        if (path == null) {
            return new MailLoad(JsonLoad.Status.MISSING, new ArrayList<>(), null);
        }
        Path norm = path.toAbsolutePath().normalize();
        JsonLoad<FileRoot> load = AtomicJsonFiles.readJson(path, FileRoot.class, GSON);
        if (load.corrupt()) {
            BACKUP_RECOVERED_PATHS.remove(norm);
            HabiLotteryMod.LOGGER.error("Corrupt mailbox JSON {}, not treating as empty inbox", path);
            return new MailLoad(JsonLoad.Status.CORRUPT, null, load.quarantined());
        }
        if (load.isMissing()) {
            BACKUP_RECOVERED_PATHS.remove(norm);
            return new MailLoad(JsonLoad.Status.MISSING, new ArrayList<>(), null);
        }
        FileRoot root = load.value();
        List<MailJson> mails = root == null || root.mails == null
                ? new ArrayList<>()
                : new ArrayList<>(root.mails);
        if (load.usedBackup()) {
            BACKUP_RECOVERED_PATHS.add(norm);
            return new MailLoad(JsonLoad.Status.OK_BACKUP, mails, load.quarantined());
        }
        BACKUP_RECOVERED_PATHS.remove(norm);
        return new MailLoad(JsonLoad.Status.OK, mails, load.quarantined());
    }

    public static boolean save(UUID uuid, List<MailJson> mails) {
        if (!WorldLotteryPaths.ready() || uuid == null) {
            return false;
        }
        return saveTo(MetaFeaturePaths.mailPlayer(uuid), mails);
    }

    public static boolean save(UUID uuid, List<MailJson> mails, boolean backup) {
        if (!WorldLotteryPaths.ready() || uuid == null) {
            return false;
        }
        return saveTo(MetaFeaturePaths.mailPlayer(uuid), mails, backup);
    }

    public static boolean saveTo(Path path, List<MailJson> mails) {
        if (path == null) {
            return false;
        }
        boolean backup = !BACKUP_RECOVERED_PATHS.contains(path.toAbsolutePath().normalize());
        return saveTo(path, mails, backup);
    }

    public static boolean saveTo(Path path, List<MailJson> mails, boolean backup) {
        if (path == null) {
            return false;
        }
        Path norm = path.toAbsolutePath().normalize();
        boolean effectiveBackup = backup && !BACKUP_RECOVERED_PATHS.contains(norm);
        FileRoot root = new FileRoot();
        root.mails = pruneExpiredMails(mails, LotteryDayClock.instant().toEpochMilli());
        // Audit B-13 + I-05: pass the backup flag explicitly (as PlayerLotteryStore does),
        // skipping .bak overwrite when recovering from .bak so an un-quarantined corrupt
        // primary can never clobber the sole valid .bak.
        boolean saved = AtomicJsonFiles.writeJson(path, root, GSON, true, effectiveBackup);
        if (saved) {
            BACKUP_RECOVERED_PATHS.remove(norm);
        } else {
            HabiLotteryMod.LOGGER.error("Failed saving mailbox {}", path);
        }
        return saved;
    }

    static List<MailJson> pruneExpiredMails(List<MailJson> mails, long nowMs) {
        if (mails == null || mails.isEmpty()) {
            return new ArrayList<>();
        }
        List<MailJson> kept = new ArrayList<>(mails.size());
        for (MailJson m : mails) {
            if (m == null) {
                continue;
            }
            if (shouldPruneExpired(m, nowMs)) {
                continue;
            }
            kept.add(m);
        }
        return kept;
    }

    private static boolean shouldPruneExpired(MailJson m, long nowMs) {
        if (m.expiresAt <= 0L || nowMs <= m.expiresAt || (nowMs - m.expiresAt) <= EXPIRED_RETENTION_MS) {
            return false;
        }
        if (MailService.hasClaimingMarker(m) || MailService.hasClaimFailedMarker(m)) {
            return false;
        }
        return m.claimed || !hasRewardAttachments(m);
    }

    private static boolean hasRewardAttachments(MailJson m) {
        if (m.commands == null || m.commands.isEmpty()) {
            return false;
        }
        try {
            return !MailCommandsCodec.decodeStrict(m.commands).isEmpty();
        } catch (RuntimeException invalid) {
            return true;
        }
    }

    /**
     * Builds a new mail from a compose draft, encoding structured rewards as
     * the {@code hltmail:} command tokens stored in {@link MailJson#commands}.
     */
    public static MailJson fromDraft(MailDraft draft) {
        MailJson j = new MailJson();
        j.id = UUID.randomUUID().toString();
        j.sender = draft.sender() == null ? "系统" : draft.sender();
        j.title = draft.title() == null ? "" : draft.title();
        j.content = draft.content() == null ? "" : draft.content();
        j.claimed = false;
        j.read = false;
        j.sentAt = LotteryDayClock.instant().toEpochMilli();
        j.expiresAt = draft.expiresAt();
        j.commands = new ArrayList<>(MailCommandsCodec.encode(draft.rewards()));
        return j;
    }

    public static boolean isExpired(MailJson m) {
        return m != null && m.expiresAt > 0 && LotteryDayClock.instant().toEpochMilli() > m.expiresAt;
    }
}
