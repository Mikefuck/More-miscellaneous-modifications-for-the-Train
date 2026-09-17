package com.habitrain.lottery.api.player;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable point-in-time view of every per-player asset owned by this mod.
 *
 * <p>Obtain one through {@link HabiLotteryApi#snapshot(UUID)}. All maps and
 * lists are defensively copied and unmodifiable.</p>
 */
public record HabiPlayerAssets(
        UUID uuid,
        String name,
        boolean online,
        int coins,
        int draws,
        Map<String, Integer> factionCards,
        int selfSelectCards,
        int limitBreakCards,
        Map<String, List<String>> unlockedSkins,
        Map<String, String> equippedSkins,
        List<String> titles,
        String currentTitle,
        int loginStreak,
        long lastLoginEpochDay) {

    public HabiPlayerAssets {
        name = name == null ? "" : name;
        factionCards = immutableOrdered(factionCards);
        selfSelectCards = Math.max(0, selfSelectCards);
        limitBreakCards = Math.max(0, limitBreakCards);
        if (unlockedSkins == null) {
            unlockedSkins = Map.of();
        } else {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            unlockedSkins.forEach((type, skins) -> {
                if (type != null) {
                    copy.put(type, skins == null ? List.of() : List.copyOf(skins));
                }
            });
            unlockedSkins = Collections.unmodifiableMap(copy);
        }
        equippedSkins = immutableOrderedStrings(equippedSkins);
        titles = titles == null ? List.of() : List.copyOf(titles);
        currentTitle = currentTitle == null ? "" : currentTitle;
    }

    /** An all-zero snapshot used before the world store is ready. */
    public static HabiPlayerAssets empty(UUID uuid) {
        return new HabiPlayerAssets(uuid, "", false, 0, 0, Map.of(), 0, 0,
                Map.of(), Map.of(), List.of(), "", 0, -1L);
    }

    /** Balance of one card kind; {@code 0} when the key was absent. */
    public int card(HabiCardKind kind) {
        if (kind == null) {
            return 0;
        }
        return factionCards.getOrDefault(kind.id(), 0);
    }

    /** Sorted owned skins for one canonical type; empty when unknown. */
    public List<String> skins(String type) {
        if (type == null) {
            return List.of();
        }
        List<String> out = unlockedSkins.get(type);
        return out == null ? List.of() : out;
    }

    /** Equipped skin id for one canonical type; {@code "default"} when none. */
    public String equipped(String type) {
        if (type == null) {
            return "default";
        }
        String value = equippedSkins.get(type);
        return value == null || value.isBlank() ? "default" : value;
    }

    private static Map<String, Integer> immutableOrdered(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, String> immutableOrderedStrings(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
