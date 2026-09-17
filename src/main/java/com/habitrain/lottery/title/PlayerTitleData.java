package com.habitrain.lottery.title;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-player owned titles + current equip ({@code titles/players/<uuid>.json}).
 */
public final class PlayerTitleData {
    public int version = 1;
    public List<String> owned = new ArrayList<>();
    public String current = "";
    public long updatedAt;

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
