package com.habitrain.lottery.api.player;

import java.util.Locale;

/** Additive ({@code ADD}) or absolute ({@code SET}) numeric mutation. */
public enum HabiAssetOperation {
    /** {@code new = clamp(current + value)}. */
    ADD,
    /** {@code new = clamp(value)}. */
    SET;

    /** Lenient parser; returns {@code null} for unknown input. */
    public static HabiAssetOperation parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
