package com.habitrain.lottery.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Encodes structured rewards into claimCommands with prefix {@code hltmail:v1:}.
 */
public final class MailCommandsCodec {
    public static final String PREFIX = "hltmail:v1:";

    private MailCommandsCodec() {
    }

    public static boolean isStructured(String command) {
        return command != null && command.startsWith(PREFIX);
    }

    public static List<String> encode(List<MailReward> rewards) {
        List<String> out = new ArrayList<>();
        if (rewards == null) {
            return out;
        }
        for (MailReward r : rewards) {
            if (r == null || r.amount() == 0) {
                continue;
            }
            switch (r.kind()) {
                case SKIN -> out.add(PREFIX + "SKIN:" + MailReward.skinEntry(r.factionType()).factionType());
                case DRAWS -> out.add(PREFIX + "DRAWS:" + r.amount());
                case COINS -> out.add(PREFIX + "COINS:" + r.amount());
                case FACTION_CARD -> {
                    String key = r.factionType() == null ? "" : r.factionType().toLowerCase(Locale.ROOT);
                    if (!key.isBlank()) {
                        out.add(PREFIX + "FACTION_CARD:" + key + ":" + r.amount());
                    }
                }
                case SELF_SELECT_CARD -> out.add(PREFIX + "SELF_SELECT_CARD:" + r.amount());
                case LIMIT_BREAK_CARD -> out.add(PREFIX + "LIMIT_BREAK_CARD:" + r.amount());
            }
        }
        return out;
    }

    public static List<MailReward> decode(List<String> commands) {
        List<MailReward> out = new ArrayList<>();
        if (commands == null) {
            return out;
        }
        for (String cmd : commands) {
            if (!isStructured(cmd)) {
                continue;
            }
            String body = cmd.substring(PREFIX.length());
            String[] parts = body.split(":");
            if (parts.length < 2) {
                continue;
            }
            try {
                switch (parts[0].toUpperCase(Locale.ROOT)) {
                    case "SKIN" -> out.add(MailReward.skinEntry(parts[1]));
                    case "DRAWS" -> out.add(MailReward.draws(Integer.parseInt(parts[1])));
                    case "COINS" -> out.add(MailReward.coins(Integer.parseInt(parts[1])));
                    case "FACTION_CARD" -> {
                        if (parts.length >= 3) {
                            out.add(MailReward.factionCard(parts[1], Integer.parseInt(parts[2])));
                        }
                    }
                    case "SELF_SELECT_CARD" -> out.add(MailReward.selfSelectCard(Integer.parseInt(parts[1])));
                    case "LIMIT_BREAK_CARD" -> out.add(MailReward.limitBreakCard(Integer.parseInt(parts[1])));
                    default -> {
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }
}
