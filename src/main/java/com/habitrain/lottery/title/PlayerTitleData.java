package com.habitrain.lottery.title;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-player owned titles + current equip ({@code titles/players/<uuid>.json}).
 */
public final class PlayerTitleData {
    public static final int REWARD_RECEIPTS_CAP = 256;

    public int version = 1;
    public long revision = 0L;
    public List<String> owned = new ArrayList<>();
    public String current = "";
    public long updatedAt;
    public Set<String> rewardReceipts = new BoundedReceipts();

    public PlayerTitleData copy() {
        PlayerTitleData copy = new PlayerTitleData();
        copy.version = version;
        copy.revision = revision;
        copy.owned = owned == null ? new ArrayList<>() : new ArrayList<>(owned);
        copy.current = current == null ? "" : current;
        copy.updatedAt = updatedAt;
        copy.rewardReceipts = asBoundedReceipts(rewardReceipts);
        return copy;
    }

    public static Set<String> asBoundedReceipts(Collection<String> src) {
        BoundedReceipts out = new BoundedReceipts();
        if (src != null) {
            for (String item : src) {
                if (item != null && !item.isBlank()) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    /** Insertion-order receipt set that evicts the oldest receipt once {@link #REWARD_RECEIPTS_CAP} is exceeded. */
    public static final class BoundedReceipts extends LinkedHashSet<String> {
        public BoundedReceipts() {
            super(REWARD_RECEIPTS_CAP + 4);
        }

        @Override
        public boolean add(String s) {
            if (s == null || s.isBlank()) {
                return false;
            }
            boolean added = super.add(s);
            while (size() > REWARD_RECEIPTS_CAP) {
                var it = iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
            return added;
        }
    }

    /** Owned set + current title, ignoring {@code updatedAt} and blank owned entries. */
    public boolean contentEquals(PlayerTitleData other) {
        if (other == null) {
            return false;
        }
        String a = current == null ? "" : current;
        String b = other.current == null ? "" : other.current;
        if (!a.equals(b)) {
            return false;
        }
        return ownedSet(owned).equals(ownedSet(other.owned));
    }

    private static Set<String> ownedSet(List<String> owned) {
        Set<String> out = new HashSet<>();
        if (owned == null) {
            return out;
        }
        for (String t : owned) {
            if (t != null && !t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }
}
