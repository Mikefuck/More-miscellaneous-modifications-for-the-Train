package com.habitrain.lottery.api.skin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.world.entity.LivingEntity;

import java.util.Objects;

/** Identifies a temporary wardrobe render, independently of the player's actual held item. */
@Environment(EnvType.CLIENT)
public final class SkinPreviewContext {
    private static final ThreadLocal<Preview> CURRENT = new ThreadLocal<>();
    private SkinPreviewContext() { }

    public record Preview(Session session, LivingEntity player, String entry, long revision, long startedAt) { }

    /** Null outside a preview, or when the requested entity is not the preview player. */
    public static Preview currentFor(LivingEntity player) {
        Preview current = CURRENT.get();
        return current != null && current.player() == player ? current : null;
    }

    /** One instance per screen. Selection changes restart its clock; normal game ticks do not. */
    public static final class Session {
        private String entry;
        private long revision, startedAt;

        public void render(LivingEntity player, String selectedEntry, Runnable draw) {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(selectedEntry, "selectedEntry");
            Objects.requireNonNull(draw, "draw");
            if (!selectedEntry.equals(entry)) {
                entry = selectedEntry;
                revision++;
                startedAt = Util.getMillis();
            }
            Preview previous = CURRENT.get();
            CURRENT.set(new Preview(this, player, entry, revision, startedAt));
            try { draw.run(); }
            finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        }
    }
}
