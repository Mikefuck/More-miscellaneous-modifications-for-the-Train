package com.habitrain.lottery.grant;

/** Shared wire limit and server-side affordability calculation for skin batches. */
public final class LootBatchPolicy {
    public static final int MAX_ROLLS = 50;

    private LootBatchPolicy() {}

    public static int affordableRolls(int requested, int chance, int cost) {
        if (requested <= 0 || chance <= 0 || cost <= 0) return 0;
        return Math.min(Math.min(requested, MAX_ROLLS), chance / cost);
    }
}
