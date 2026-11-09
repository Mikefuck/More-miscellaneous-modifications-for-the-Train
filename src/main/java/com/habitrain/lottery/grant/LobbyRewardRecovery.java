package com.habitrain.lottery.grant;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Server-thread queue: game-end listeners run before upstream resets the lobby. */
final class LobbyRewardRecovery {
    private final Set<UUID> pending = new LinkedHashSet<>();
    private int notBeforeTick;

    void schedule(int tick, Collection<UUID> players) {
        notBeforeTick = tick + 1;
        pending.addAll(players);
    }

    void recover(int tick, Predicate<UUID> ready, Consumer<UUID> delivery) {
        if (tick < notBeforeTick) return;
        for (UUID uuid : Set.copyOf(pending)) {
            if (!ready.test(uuid)) continue;
            delivery.accept(uuid);
            pending.remove(uuid);
        }
    }

    void clear() {
        pending.clear();
        notBeforeTick = 0;
    }
}
