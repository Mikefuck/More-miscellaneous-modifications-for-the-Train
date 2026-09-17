package com.habitrain.lottery.grant;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** Reuses existing faction slots; never adds players or special-faction slots. */
final class RoleSlotAllocator {
    private RoleSlotAllocator() {}

    static <P, R, K> boolean assign(Map<P, R> assignments, P player, R desired,
            Function<R, K> key, Function<R, Integer> faction, Predicate<P> protectedPlayer) {
        if (!assignments.containsKey(player) || desired == null) return false;
        R current = assignments.get(player);
        if (current == null) return false;
        if (Objects.equals(key.apply(current), key.apply(desired))) return true;

        // Reuse an existing copy before replacing a faction slot, avoiding duplicates.
        for (var entry : assignments.entrySet()) {
            if (entry.getValue() != null && Objects.equals(key.apply(entry.getValue()), key.apply(desired))) {
                if (protectedPlayer.test(entry.getKey())) return false;
                assignments.put(entry.getKey(), current);
                assignments.put(player, desired);
                return true;
            }
        }
        if (Objects.equals(faction.apply(current), faction.apply(desired))) {
            assignments.put(player, desired);
            return true;
        }
        for (var entry : assignments.entrySet()) {
            if (!protectedPlayer.test(entry.getKey()) && entry.getValue() != null
                    && Objects.equals(faction.apply(entry.getValue()), faction.apply(desired))) {
                assignments.put(entry.getKey(), current);
                assignments.put(player, desired);
                return true;
            }
        }
        return false;
    }
}
