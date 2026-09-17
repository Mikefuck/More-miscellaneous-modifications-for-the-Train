package com.habitrain.lottery.config;

import java.util.HashMap;
import java.util.Map;

public final class RatesConfig {
    public double drawCostMultiplier = 1.0;
    public double duplicateCoinMultiplier = 1.0;
    /**
     * Flat coin refund when rolling a skin the player already owns.
     * When &gt; 0, overrides the SRE quality-scaled formula (after multiplier).
     */
    public int duplicateCoinFlat = 60;
    /** Coins required to buy one loot chance via coin2lottery. */
    public int coinPerDraw = 160;
    /** Max consecutive-login reward amount (min(streak, cap)). */
    public int loginRewardCap = 10;
    public double[] qualityWeightBias = new double[]{0, 0, 0, 0, 0, 0};
    public Map<String, Double> modeMultipliers = new HashMap<>();
    public int opPermissionLevel = 2;

    public RatesConfig() {
        modeMultipliers.put("blackout", 1.0);
        modeMultipliers.put("murder", 1.0);
        modeMultipliers.put("repair", 1.0);
    }

    public double modeMultiplier(String mode) {
        if (mode == null) {
            return 1.0;
        }
        return modeMultipliers.getOrDefault(mode.toLowerCase(), 1.0);
    }

    public int coinPerDraw() {
        return Math.max(1, coinPerDraw);
    }

    public int loginRewardCap() {
        return Math.max(1, loginRewardCap);
    }

    /** Flat duplicate refund; 0 means fall back to multiplier-scaled SRE base. */
    public int duplicateCoinFlat() {
        return Math.max(0, duplicateCoinFlat);
    }

    public int opPermissionLevel() {
        return Math.min(4, Math.max(1, opPermissionLevel));
    }
}
