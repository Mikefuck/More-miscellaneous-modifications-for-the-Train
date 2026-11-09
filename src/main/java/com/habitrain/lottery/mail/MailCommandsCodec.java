package com.habitrain.lottery.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Encodes structured rewards into {@code hltmail:v1:} claimCommands.
 * Plain IDs retain the saved {@code <kind>:<id>:<amount>} order, including numeric IDs;
 * namespaced IDs use {@code <kind>:<amount>:<extra>}.
 *
 * <p>Decoding splits each token with {@code token.split(":", 5)} so the 5th segment
 * preserves embedded colons in namespaced IDs (e.g. {@code modid:entry_id}),
 * while remaining backward-compatible with legacy {@code v1.1.63} saved mailbox tokens.
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
                case SKIN -> {
                    String skinEntry = MailReward.skinEntry(r.factionType()).factionType();
                    if (r.amount() <= 1 && !skinEntry.contains(":")) {
                        out.add(PREFIX + "SKIN:" + skinEntry);
                    } else {
                        out.add(PREFIX + "SKIN:" + Math.max(1, r.amount()) + ":" + skinEntry);
                    }
                }
                case GREEN_APPLES -> out.add(PREFIX + "GREEN_APPLES:" + r.amount());
                case FACTION_CARD -> {
                    String key = r.factionType() == null ? "" : r.factionType().trim().toLowerCase(Locale.ROOT);
                    if (!key.isEmpty()) {
                        out.add(key.contains(":")
                                ? PREFIX + "FACTION_CARD:" + r.amount() + ":" + key
                                : PREFIX + "FACTION_CARD:" + key + ":" + r.amount());
                    }
                }
                case SELF_SELECT_CARD -> out.add(PREFIX + "SELF_SELECT_CARD:" + r.amount());
                case LIMIT_BREAK_CARD -> out.add(PREFIX + "LIMIT_BREAK_CARD:" + r.amount());
                case CRATE -> {
                    String extra = r.factionType() == null ? "" : r.factionType().trim();
                    if (!extra.isEmpty()) {
                        out.add(extra.contains(":")
                                ? PREFIX + "CRATE:" + r.amount() + ":" + extra
                                : PREFIX + "CRATE:" + extra + ":" + r.amount());
                    }
                }
                case KEY -> {
                    String extra = r.factionType() == null ? "" : r.factionType().trim();
                    if (!extra.isEmpty()) {
                        out.add(extra.contains(":")
                                ? PREFIX + "KEY:" + r.amount() + ":" + extra
                                : PREFIX + "KEY:" + extra + ":" + r.amount());
                    }
                }
            }
        }
        return out;
    }

    public static List<MailReward> decode(List<String> commands) {
        return decode(commands, false);
    }

    public static List<MailReward> decodeStrict(List<String> commands) {
        return decode(commands, true);
    }

    private static List<MailReward> decode(List<String> commands, boolean strict) {
        List<MailReward> out = new ArrayList<>();
        if (commands == null) {
            return out;
        }
        for (String cmd : commands) {
            if (!isStructured(cmd)) {
                continue;
            }
            // Limit split to 5 segments: ["hltmail", "v1", "<kind>", "<amount>", "<extra>"]
            // so colons inside <extra> (such as "modid:entry_id") stay intact in parts[4].
            String[] parts = cmd.split(":", 5);
            if (parts.length < 4) {
                if (strict) throw new IllegalArgumentException("Incomplete attachment");
                continue;
            }
            try {
                switch (parts[2].toUpperCase(Locale.ROOT)) {
                    case "SKIN" -> {
                        if (parts.length == 5) {
                            int amount = Integer.parseInt(parts[3]);
                            if (amount <= 0) {
                                throw new IllegalArgumentException("Invalid skin amount: " + amount);
                            }
                            MailReward normalized = MailReward.skinEntry(parts[4]);
                            out.add(amount == 1
                                    ? normalized
                                    : new MailReward(MailReward.Kind.SKIN, amount, normalized.factionType()));
                        } else {
                            // Legacy v1.1.63 format: hltmail:v1:SKIN:<type/id>
                            out.add(MailReward.skinEntry(parts[3]));
                        }
                    }
                    case "GREEN_APPLES" -> {
                        if (parts.length != 4) throw new IllegalArgumentException("Malformed green apples attachment");
                        out.add(MailReward.greenApples(Integer.parseInt(parts[3])));
                    }
                    case "SELF_SELECT_CARD" -> {
                        if (parts.length != 4) throw new IllegalArgumentException("Malformed self-select attachment");
                        out.add(MailReward.selfSelectCard(Integer.parseInt(parts[3])));
                    }
                    case "LIMIT_BREAK_CARD" -> {
                        if (parts.length != 4) throw new IllegalArgumentException("Malformed limit-break attachment");
                        out.add(MailReward.limitBreakCard(Integer.parseInt(parts[3])));
                    }
                    case "FACTION_CARD" -> {
                        AmountAndExtra parsed = parseAmountAndExtra(parts, "Incomplete card");
                        out.add(MailReward.factionCard(parsed.extra().toLowerCase(Locale.ROOT), parsed.amount()));
                    }
                    case "CRATE" -> {
                        AmountAndExtra parsed = parseAmountAndExtra(parts, "Incomplete crate");
                        out.add(new MailReward(MailReward.Kind.CRATE, parsed.amount(), parsed.extra()));
                    }
                    case "KEY" -> {
                        AmountAndExtra parsed = parseAmountAndExtra(parts, "Incomplete key");
                        out.add(new MailReward(MailReward.Kind.KEY, parsed.amount(), parsed.extra()));
                    }
                    case "LOOT_CHANCE", "COIN", "COIN_NUM" -> { /* Retired currencies. */ }
                    default -> {
                        if (strict) throw new IllegalArgumentException("Unknown attachment");
                    }
                }
            } catch (IllegalArgumentException invalid) {
                if (strict) throw invalid;
            }
        }
        return out;
    }

    private record AmountAndExtra(int amount, String extra) {
    }

    private static AmountAndExtra parseAmountAndExtra(String[] parts, String errorMsg) {
        if (parts.length < 5) {
            throw new IllegalArgumentException(errorMsg);
        }
        // v1's encoder uses id:amount for plain IDs, including numeric IDs. Only
        // namespaced IDs use amount:extra, so prefer the legacy order when both are numbers.
        if (!parts[3].trim().isEmpty() && isSignedInteger(parts[4])) {
            return new AmountAndExtra(Integer.parseInt(parts[4]), parts[3].trim());
        }
        if (isSignedInteger(parts[3])) {
            int amount = Integer.parseInt(parts[3]);
            String extra = parts[4].trim();
            if (extra.isEmpty()) {
                throw new IllegalArgumentException(errorMsg);
            }
            return new AmountAndExtra(amount, extra);
        }
        throw new IllegalArgumentException(errorMsg);
    }

    private static boolean isSignedInteger(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int start = (s.charAt(0) == '-' || s.charAt(0) == '+') ? 1 : 0;
        if (start == s.length()) {
            return false;
        }
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
