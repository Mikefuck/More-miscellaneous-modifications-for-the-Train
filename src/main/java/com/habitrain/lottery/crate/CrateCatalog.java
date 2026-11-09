package com.habitrain.lottery.crate;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.skin.SkinQuality;
import com.habitrain.lottery.storage.WorldLotteryPaths;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single source of truth for every crate and its matching key.
 *
 * <p>The crate opener, warehouse registration, mail attachments and admin
 * mail composer all consume this catalogue. Add a new crate here once and it
 * becomes available to each of those systems.</p>
 *
 * <p>Server-side world entries and client-side S2C synced entries are stored in
 * separate maps so client network packets or {@code DISCONNECT} events on an
 * integrated server can never clobber live server authority ({@code I-04}).</p>
 */
public final class CrateCatalog {
    private static final List<Entry> ENTRIES = List.of(
            new Entry("woodland", "screen.habitrain_lottery.crate.woodland", "key_woodland",
                    "minecraft:oak_planks", 0xFF9A6B3F, SkinQuality.WHITE, false),
            new Entry("cobalt", "screen.habitrain_lottery.crate.cobalt", "key_cobalt",
                    "minecraft:lapis_block", 0xFF397DE2, SkinQuality.BLUE, false),
            new Entry("amethyst", "screen.habitrain_lottery.crate.amethyst", "key_amethyst",
                    "minecraft:amethyst_block", 0xFFB26BDA, SkinQuality.PURPLE, false),
            new Entry("gilded", "screen.habitrain_lottery.crate.gilded", "key_gilded",
                    "minecraft:gold_block", 0xFFF0BE45, SkinQuality.GOLD, false),
            new Entry("crimson", "screen.habitrain_lottery.crate.crimson", "key_crimson",
                    "minecraft:redstone_block", 0xFFE14C59, SkinQuality.RED, false),
            new Entry("prismatic", "screen.habitrain_lottery.crate.prismatic", "key_prismatic",
                    "minecraft:prismarine", 0xFF6FE4D7, SkinQuality.WHITE, true)
    );
    private static final Map<String, Entry> BUILTINS;
    static {
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Entry entry : ENTRIES) entries.put(entry.id(), entry);
        BUILTINS = Collections.unmodifiableMap(entries);
    }

    private static volatile Map<String, Entry> serverCurrent = BUILTINS;
    private static volatile boolean serverActive = false;
    private static volatile Map<String, Entry> clientCurrent = BUILTINS;

    private CrateCatalog() {
    }

    public record Entry(String id, String nameKey, String keyId, String icon, int color,
                        SkinQuality primary, boolean prism, String description, String keyName,
                        String keyIcon, String tier, boolean enabled, boolean archived,
                        String rewardMode, int skinDrawCount, int rollCount, int minimumSkinCount,
                        List<String> extraKinds, String appearancePreset, String badge,
                        List<RewardPreview> rewards) {
        public Entry(String id, String nameKey, String keyId, String icon, int color,
                     SkinQuality primary, boolean prism, String description, String keyName,
                     String keyIcon, String tier, boolean enabled, boolean archived,
                     String rewardMode, int skinDrawCount, int rollCount, int minimumSkinCount,
                     List<String> extraKinds, String appearancePreset, String badge) {
            this(id, nameKey, keyId, icon, color, primary, prism, description, keyName, keyIcon,
                    tier, enabled, archived, rewardMode, skinDrawCount, rollCount, minimumSkinCount,
                    extraKinds, appearancePreset, badge, List.of());
        }

        public Entry(String id, String nameKey, String keyId, String icon, int color,
                     SkinQuality primary, boolean prism) {
            this(id, nameKey, keyId, icon, color, primary, prism, nameKey + ".hint",
                    "screen.habitrain_lottery.crate.key." + id, "minecraft:tripwire_hook",
                    primary.id(), true, false, "skin_plus_bonus", 1, 1, 0, List.of(), id, "star", List.of());
        }
        public Entry {
            if (id == null || id.isBlank() || nameKey == null || nameKey.isBlank()
                    || keyId == null || keyId.isBlank() || icon == null || icon.isBlank()) {
                throw new IllegalArgumentException("Invalid crate catalogue entry");
            }
            extraKinds = extraKinds == null ? List.of() : List.copyOf(extraKinds);
            rewards = rewards == null ? List.of() : List.copyOf(rewards);
        }

        public String crateItemId() {
            return HabiLotteryMod.MOD_ID + ":crate_" + id;
        }

        public String keyItemId() {
            return HabiLotteryMod.MOD_ID + ":" + keyId;
        }
    }

    /** Public candidates only; never exposes player balances, quotas or transaction data. */
    public record RewardPreview(String kind, String id, int amount, String quality) {}

    private static Map<String, Entry> activeMap() {
        return (serverActive || WorldLotteryPaths.ready()) ? serverCurrent : clientCurrent;
    }

    private static Map<String, Entry> buildImmutableMap(List<Entry> entries) {
        Map<String, Entry> next = new LinkedHashMap<>();
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry != null && entry.id().matches("[a-z0-9_.-]{1,48}")) {
                    next.put(entry.id(), entry);
                }
            }
        }
        return Collections.unmodifiableMap(next);
    }

    public static List<Entry> entries() {
        return List.copyOf(activeMap().values());
    }

    public static List<Entry> builtins() { return ENTRIES; }

    public static List<Entry> publishedEntries() {
        return activeMap().values().stream().filter(e -> !e.archived()).toList();
    }

    /** Replaces the authoritative server world catalogue. */
    public static synchronized void replaceServerEntries(List<Entry> entries) {
        serverCurrent = buildImmutableMap(entries);
        serverActive = true;
    }

    /** Resets the authoritative server world catalogue on server stop. */
    public static synchronized void resetServer() {
        serverCurrent = BUILTINS;
        serverActive = false;
    }

    /** Replaces the client-only S2C synced catalogue without touching server authority. */
    public static synchronized void replaceClientEntries(List<Entry> entries) {
        clientCurrent = buildImmutableMap(entries);
    }

    /** Resets the client-only S2C synced catalogue. */
    public static synchronized void resetClient() {
        clientCurrent = BUILTINS;
    }

    /** Replaces the world catalogue on the server, or the public catalogue on a client. */
    public static synchronized void replaceEntries(List<Entry> entries) {
        if (serverActive || WorldLotteryPaths.ready()) {
            replaceServerEntries(entries);
        } else {
            replaceClientEntries(entries);
        }
    }

    /**
     * Resets client state unconditionally, and resets server state only when no world is loaded
     * so client {@code DISCONNECT} on an integrated server never wipes live server authority.
     */
    public static synchronized void reset() {
        clientCurrent = BUILTINS;
        if (!WorldLotteryPaths.ready()) {
            serverCurrent = BUILTINS;
            serverActive = false;
        }
    }

    public static Entry builtin(String id) {
        return id == null ? null : BUILTINS.get(id);
    }

    public static Entry find(String id) {
        return id == null ? null : activeMap().get(id.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isCrateItem(String itemId) {
        return itemId != null && activeMap().values().stream().anyMatch(e -> e.crateItemId().equals(itemId));
    }

    public static boolean isKeyItem(String itemId) {
        return itemId != null && activeMap().values().stream().anyMatch(e -> e.keyItemId().equals(itemId));
    }

    public static String crateItemId(String id) {
        Entry entry = find(id);
        return entry == null ? null : entry.crateItemId();
    }

    public static String keyItemId(String id) {
        Entry entry = find(id);
        return entry == null ? null : entry.keyItemId();
    }
}
