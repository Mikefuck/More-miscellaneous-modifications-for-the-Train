package com.habitrain.lottery.storage;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Normalizes skin item-type keys used across SRE economy, CCA, and world store.
 * UI may send full ids ({@code trainmurdermystery:knife}) while lottery unlocks
 * use abstract types ({@code knife}).
 */
public final class SkinTypeKeys {
    private SkinTypeKeys() {
    }

    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return "default";
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        int colon = t.indexOf(':');
        if (colon >= 0 && colon < t.length() - 1) {
            t = t.substring(colon + 1);
        }
        // SRE lottery uses gun/ for revolvers in pool entries; registry path is revolver
        if ("gun".equals(t)) {
            return "revolver";
        }
        return t;
    }

    /** Keys to write when dual-storing for maximum compatibility. */
    public static Set<String> writeKeys(String raw) {
        Set<String> keys = new LinkedHashSet<>();
        String c = canonical(raw);
        keys.add(c);
        if (raw != null && !raw.isBlank()) {
            String t = raw.trim().toLowerCase(Locale.ROOT);
            keys.add(t);
            int colon = t.indexOf(':');
            if (colon >= 0 && colon < t.length() - 1) {
                keys.add(t.substring(colon + 1));
            }
        }
        if ("revolver".equals(c)) {
            keys.add("gun");
            keys.add("revolver");
        }
        if ("gun".equals(c) || "gun".equals(raw)) {
            keys.add("revolver");
            keys.add("gun");
        }
        return keys;
    }
}
