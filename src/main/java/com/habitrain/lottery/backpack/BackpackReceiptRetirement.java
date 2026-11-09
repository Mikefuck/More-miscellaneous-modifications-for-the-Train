package com.habitrain.lottery.backpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.WorldLotteryPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Retires only receipts whose source cannot replay, including through its .bak.
 * Unknown integrations and unreadable source files keep their evidence indefinitely.
 * This is not a FIFO cap: any number of genuinely pending transactions stays pinned.
 */
final class BackpackReceiptRetirement {
    private static final int COMPACT_AT = 64;
    private static final String DAILY_PREFIX = "habitrain_lottery:daily_task:";
    private final UUID player;
    private final Map<Path, List<JsonObject>> sources = new HashMap<>();

    private BackpackReceiptRetirement(UUID player) { this.player = player; }

    static Set<String> retained(UUID player, Set<String> receipts) {
        if (receipts.size() < COMPACT_AT) return receipts;
        BackpackReceiptRetirement retirement = new BackpackReceiptRetirement(player);
        Set<String> retained = new HashSet<>(receipts);
        retained.removeIf(retirement::canRetire);
        return retained;
    }

    private boolean canRetire(String receipt) {
        try {
            if (receipt.startsWith("card-debit:") || receipt.startsWith("card-refund:")) {
                String activation = receipt.substring(receipt.indexOf(':') + 1);
                UUID.fromString(activation);
                return account().stream().allMatch(data -> !objectContains(data, "pendingCardUses", activation));
            }
            if (receipt.startsWith("crate:")) {
                String openId = receipt.substring("crate:".length());
                return account().stream().allMatch(data -> !arrayContains(data, "pendingCrateDebits", openId))
                        && copies(WorldLotteryPaths.configFile("crates.json")).stream()
                        .allMatch(data -> !objectContains(data, "pendingOpens", openId));
            }
            if (receipt.startsWith("faction-refund:")) {
                String activation = receipt.substring("faction-refund:".length());
                UUID.fromString(activation);
                Path file = WorldLotteryPaths.root().resolve("faction-refunds").resolve(player.toString())
                        .resolve(activation + ".json");
                return copies(file).stream().allMatch(data -> data.has("complete") && data.get("complete").getAsBoolean());
            }
            if (!receipt.startsWith("mail:") && !receipt.startsWith("shop:") && !receipt.startsWith(DAILY_PREFIX)) {
                return false;
            }
            Path intent = WorldLotteryPaths.root().resolve("reward-deliveries").resolve(player.toString())
                    .resolve(UUID.nameUUIDFromBytes(receipt.getBytes(StandardCharsets.UTF_8)) + ".json");
            if (Files.exists(intent) || Files.exists(AtomicJsonFiles.bakPath(intent))) return false;
            if (account().stream().anyMatch(data -> arrayContains(data, "pendingRewardReceipts", receipt))) return false;
            if (receipt.startsWith("shop:")) {
                return account().stream().noneMatch(data -> arrayContains(data, "paidShopPurchases", receipt));
            }
            if (receipt.startsWith("mail:")) {
                String mailId = receipt.substring("mail:".length());
                for (JsonObject source : copies(MetaFeaturePaths.mailPlayer(player))) {
                    if (!source.has("mails") || !source.get("mails").isJsonArray()) return false;
                    for (var element : source.getAsJsonArray("mails")) {
                        JsonObject mail = element.getAsJsonObject();
                        if (mailId.equals(mail.get("id").getAsString())
                                && (!mail.has("claimed") || !mail.get("claimed").getAsBoolean())) return false;
                    }
                }
                return true;
            }
            int lastColon = receipt.lastIndexOf(':');
            long day = Long.parseLong(receipt.substring(lastColon + 1));
            String task = "habitrain_lottery:" + receipt.substring(DAILY_PREFIX.length(), lastColon);
            return account().stream().allMatch(data -> {
                long sourceDay = data.has("dailyTaskEpochDay") ? data.get("dailyTaskEpochDay").getAsLong() : -1;
                return sourceDay > day || sourceDay == day && arrayContains(data, "dailyTaskClaims", task);
            });
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private List<JsonObject> account() throws IOException {
        return copies(WorldLotteryPaths.playerFile(player));
    }

    /** No healing or quarantine here: compaction must never change a source transaction. */
    private List<JsonObject> copies(Path primary) throws IOException {
        if (sources.containsKey(primary)) {
            List<JsonObject> cached = sources.get(primary);
            if (cached == null) throw new IOException("Unreadable receipt source");
            return cached;
        }
        sources.put(primary, null);
        List<JsonObject> copies = new ArrayList<>();
        copies.add(read(primary)); // Missing primary is not proof that a source completed.
        Path backup = AtomicJsonFiles.bakPath(primary);
        if (Files.exists(backup)) copies.add(read(backup));
        List<JsonObject> result = List.copyOf(copies);
        sources.put(primary, result);
        return result;
    }

    private static JsonObject read(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static boolean arrayContains(JsonObject data, String name, String key) {
        if (!data.has(name)) return false;
        for (var element : data.getAsJsonArray(name)) if (key.equals(element.getAsString())) return true;
        return false;
    }

    private static boolean objectContains(JsonObject data, String name, String key) {
        return data.has(name) && data.getAsJsonObject(name).has(key);
    }
}
