package com.habitrain.lottery.grant;

import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
        K desiredKey = key.apply(desired);
        if (Objects.equals(key.apply(current), desiredKey)) return true;

        List<Map.Entry<P, R>> sortedEntries = new ArrayList<>(assignments.entrySet());
        sortedEntries.sort((e1, e2) -> compareKeys(e1.getKey(), e2.getKey()));

        // Reuse an existing unprotected copy before replacing a faction slot (I-14):
        // only fail when >= 1 holder already has desired and ALL such holders are protected.
        boolean anyDesiredHolder = false;
        for (var entry : sortedEntries) {
            if (entry.getValue() != null && Objects.equals(key.apply(entry.getValue()), desiredKey)) {
                anyDesiredHolder = true;
                if (!protectedPlayer.test(entry.getKey())) {
                    assignments.put(entry.getKey(), current);
                    assignments.put(player, desired);
                    return true;
                }
            }
        }
        if (anyDesiredHolder) {
            return false;
        }

        Integer desiredFaction = faction.apply(desired);
        if (Objects.equals(faction.apply(current), desiredFaction)) {
            assignments.put(player, desired);
            return true;
        }
        for (var entry : sortedEntries) {
            if (!protectedPlayer.test(entry.getKey()) && entry.getValue() != null
                    && Objects.equals(faction.apply(entry.getValue()), desiredFaction)) {
                assignments.put(entry.getKey(), current);
                assignments.put(player, desired);
                return true;
            }
        }
        return false;
    }

    private static <P> int compareKeys(P a, P b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Entity ea && b instanceof Entity eb) {
            return ea.getUUID().compareTo(eb.getUUID());
        }
        if (a instanceof UUID ua && b instanceof UUID ub) {
            return ua.compareTo(ub);
        }
        if (a.getClass() == b.getClass() && a instanceof Comparable<?> ca) {
            @SuppressWarnings("unchecked")
            int cmp = ((Comparable<Object>) ca).compareTo(b);
            return cmp;
        }
        return Objects.toString(a, "").compareTo(Objects.toString(b, ""));
    }
}
