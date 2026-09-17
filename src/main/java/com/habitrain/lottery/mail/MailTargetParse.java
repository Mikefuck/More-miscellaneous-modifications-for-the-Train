package com.habitrain.lottery.mail;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MailTargetParse {
    public static final int MAX_TARGETS = 256;

    private MailTargetParse() {
    }

    public static List<String> splitNames(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("[,;\\s\\n\\r]+");
        Set<String> seen = new LinkedHashSet<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (!t.isEmpty()) {
                seen.add(t);
            }
            if (seen.size() >= MAX_TARGETS) {
                break;
            }
        }
        return new ArrayList<>(seen);
    }
}
