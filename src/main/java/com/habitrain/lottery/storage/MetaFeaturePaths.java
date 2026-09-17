package com.habitrain.lottery.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * World-local paths for hub meta features (mail / match records / backpack cards).
 */
public final class MetaFeaturePaths {
    private MetaFeaturePaths() {
    }

    public static Path root() {
        return WorldLotteryPaths.root();
    }

    public static Path mailDir() {
        return root().resolve("mail").resolve("players");
    }

    public static Path mailPlayer(UUID id) {
        return mailDir().resolve(id + ".json");
    }

    public static Path recordsDir() {
        return root().resolve("records");
    }

    public static Path recordsIndex() {
        return recordsDir().resolve("index.jsonl");
    }

    public static Path matchesDir() {
        return recordsDir().resolve("matches");
    }

    public static Path matchFile(String matchId) {
        Path r = root();
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
        return root().resolve("backpack").resolve("players");
    }

    public static Path backpackPlayer(UUID id) {
        return backpackDir().resolve(id + ".json");
    }

    public static void ensureDirs() throws IOException {
        if (!WorldLotteryPaths.ready()) {
            return;
        }
        Files.createDirectories(mailDir());
        Files.createDirectories(matchesDir());
        Files.createDirectories(backpackDir());
    }
}
