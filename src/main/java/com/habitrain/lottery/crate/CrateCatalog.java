package com.habitrain.lottery.crate;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.skin.SkinQuality;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single source of truth for every crate and its matching key.
 *
 * <p>The crate opener, warehouse registration, mail attachments and admin
 * mail composer all consume this catalogue. Add a new crate here once and it
 * becomes available to each of those systems.</p>
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
    private static final Map<String, Entry> BY_ID = ENTRIES.stream()
            .collect(Collectors.toUnmodifiableMap(Entry::id, Function.identity()));

    private CrateCatalog() {
    }

    public record Entry(String id, String nameKey, String keyId, String icon, int color,
                        SkinQuality primary, boolean prism) {
        public Entry {
            if (id == null || id.isBlank() || nameKey == null || nameKey.isBlank()
                    || keyId == null || keyId.isBlank() || icon == null || icon.isBlank()) {
                throw new IllegalArgumentException("Invalid crate catalogue entry");
            }
        }

        public String crateItemId() {
            return HabiLotteryMod.MOD_ID + ":crate_" + id;
        }

        public String keyItemId() {
            return HabiLotteryMod.MOD_ID + ":" + keyId;
        }
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Entry find(String id) {
        return id == null ? null : BY_ID.get(id.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean isCrateItem(String itemId) {
        return itemId != null && ENTRIES.stream().anyMatch(e -> e.crateItemId().equals(itemId));
    }

    public static boolean isKeyItem(String itemId) {
        return itemId != null && ENTRIES.stream().anyMatch(e -> e.keyItemId().equals(itemId));
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
