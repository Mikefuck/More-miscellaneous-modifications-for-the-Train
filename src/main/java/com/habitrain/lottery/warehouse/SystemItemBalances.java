package com.habitrain.lottery.warehouse;

import java.util.Map;

/** Checked arithmetic for account items; never touches a Minecraft inventory. */
public final class SystemItemBalances {
    public static final int MAX_TYPES = 4096;
    private SystemItemBalances() {}

    public static boolean change(Map<String, Integer> balances, String id, int delta) {
        if (balances == null || id == null || delta == 0) return false;
        long next = (long) balances.getOrDefault(id, 0) + delta;
        if (next < 0 || next > Integer.MAX_VALUE) return false;
        if (next > 0 && !balances.containsKey(id) && balances.size() >= MAX_TYPES) return false;
        if (next == 0) balances.remove(id); else balances.put(id, (int) next);
        return true;
    }
}
