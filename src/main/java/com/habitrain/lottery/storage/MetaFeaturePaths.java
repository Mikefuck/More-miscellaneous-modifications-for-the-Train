package com.habitrain.lottery.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * World-local paths for hub meta features (mail / match records / backpack cards).
 *
 * <p><b>Not-ready contract (audit B-23).</b> {@link WorldLotteryPaths#root()} is
 * {@code null} until {@link WorldLotteryPaths#init(net.minecraft.server.MinecraftServer)}
 * (or one of the test hooks) has run, so every accessor of this class is <em>total</em>:
 * while {@link WorldLotteryPaths#ready()} is {@code false} each accessor returns
 * {@code null} instead of dereferencing that {@code null} root.
 *
 * <p>{@code null} therefore means "the world lottery root is not initialised, there is
 * no such path" — never "the directory does not exist yet". Callers that already guard
 * with {@link WorldLotteryPaths#ready()} keep working unchanged, and callers that do not
 * guard observe {@code null}, which every storage helper of this mod already treats as
 * "nothing to read/write" (see {@link AtomicJsonFiles}: a {@code null} path yields
 * {@code false} on write and {@code MISSING} on read).
 *
 * <p>Readiness is decided by {@link #requireReady()}; see that method for why the guard
 * returns {@code null} rather than throwing.
 */
public final class MetaFeaturePaths {
    private MetaFeaturePaths() {
    }

    /**
     * The single readiness guard of this class.
     *
     * <p>Returns the world lottery root, or {@code null} when
     * {@link WorldLotteryPaths#ready()} is {@code false}. Deliberately <em>does not
     * throw</em>: making this class total is what keeps the existing "check
     * {@code ready()} first, then build a path" callers from being turned into
     * {@link NullPointerException}s by a path accessor, and it lets an early or late
     * call degrade to "no path" instead of failing a mail/match/backpack operation.
     * The name is the one requested by the audit finding, so it stays greppable; the
     * contract is null-on-not-ready, not "throw".
     */
    private static Path requireReady() {
        return WorldLotteryPaths.ready() ? WorldLotteryPaths.root() : null;
    }

    public static Path root() {
        return requireReady();
    }

    public static Path mailDir() {
        Path r = requireReady();
        return r == null ? null : r.resolve("mail").resolve("players");
    }

    public static Path mailPlayer(UUID id) {
        Path dir = mailDir();
        return dir == null || id == null ? null : dir.resolve(id + ".json");
    }

    public static Path recordsDir() {
        Path r = requireReady();
        return r == null ? null : r.resolve("records");
    }

    public static Path recordsIndex() {
        Path dir = recordsDir();
        return dir == null ? null : dir.resolve("index.jsonl");
    }

    public static Path matchesDir() {
        Path dir = recordsDir();
        return dir == null ? null : dir.resolve("matches");
    }

    public static Path matchFile(String matchId) {
        Path r = requireReady();
        if (r == null) {
            return null;
        }
        return matchFile(r, matchId);
    }

    /**
     * {@code <lotteryRoot>/records/matches/<matchId>.json} after rejecting {@code ..} and
     * separators. Null if the id is unsafe or the resolved path would leave {@code matches/}.
     */
    public static Path matchFile(Path lotteryRoot, String matchId) {
        if (lotteryRoot == null) {
            return null;
        }
        return resolveMatchFile(lotteryRoot.resolve("records").resolve("matches"), matchId);
    }

    public static Path resolveMatchFile(Path matchesDir, String matchId) {
        if (matchesDir == null || matchId == null || matchId.isBlank() || !isSafeMatchId(matchId)) {
            return null;
        }
        try {
            Path dirAbs = matchesDir.toAbsolutePath().normalize();
            Path file = dirAbs.resolve(matchId + ".json").normalize();
            if (!file.startsWith(dirAbs)) {
                return null;
            }
            Path parent = file.getParent();
            if (parent == null || !parent.equals(dirAbs)) {
                return null;
            }
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isSafeMatchId(String matchId) {
        if (matchId == null || matchId.isBlank() || matchId.length() > 128) {
            return false;
        }
        if (matchId.equals(".") || matchId.equals("..") || matchId.contains("..")) {
            return false;
        }
        for (int i = 0; i < matchId.length(); i++) {
            char c = matchId.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || c == ':') {
                return false;
            }
        }
        return true;
    }

    public static Path backpackDir() {
        Path r = requireReady();
        return r == null ? null : r.resolve("backpack").resolve("players");
    }

    public static Path backpackPlayer(UUID id) {
        Path dir = backpackDir();
        return dir == null || id == null ? null : dir.resolve(id + ".json");
    }

    /**
     * Creates the world-local meta feature directories. No-op (not an error) while the
     * world lottery root is not initialised, because there is no directory to create.
     */
    public static void ensureDirs() throws IOException {
        Path mail = mailDir();
        Path matches = matchesDir();
        Path backpack = backpackDir();
        if (mail == null || matches == null || backpack == null) {
            return;
        }
        Files.createDirectories(mail);
        Files.createDirectories(matches);
        Files.createDirectories(backpack);
    }
}
