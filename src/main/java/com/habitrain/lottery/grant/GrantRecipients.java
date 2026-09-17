package com.habitrain.lottery.grant;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Participate-grant recipient set. Never expands to "everyone in the dimension".
 */
public final class GrantRecipients {
    private GrantRecipients() {
    }

    /**
     * Participate set is the union of blackout role history and assigned match
     * roles (入局 UUID). {@code roundEnd.players} is only used when both of those
     * are empty — spectators can sit in roundEnd and must not be preferred.
     * An empty result means skip participate grants (do not use {@code level.players()}).
     */
    public static Set<UUID> matchParticipants(
            Collection<UUID> roundEndPlayers,
            Collection<UUID> blackoutRoleHistory,
            Collection<UUID> assignedGameRoles) {
        Set<UUID> participate = union(blackoutRoleHistory, assignedGameRoles);
        if (!participate.isEmpty()) {
            return participate;
        }
        return copyNonNull(roundEndPlayers);
    }

    public static Set<UUID> union(Collection<UUID> a, Collection<UUID> b) {
        Set<UUID> out = copyNonNull(a);
        if (b != null) {
            for (UUID id : b) {
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static Set<UUID> copyNonNull(Collection<UUID> src) {
        Set<UUID> out = new LinkedHashSet<>();
        if (src == null) {
            return out;
        }
        for (UUID id : src) {
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }
}
