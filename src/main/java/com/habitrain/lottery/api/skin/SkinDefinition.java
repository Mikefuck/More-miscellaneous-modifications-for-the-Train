package com.habitrain.lottery.api.skin;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable definition of one SRE weapon skin. */
public record SkinDefinition(
        String type,
        String id,
        int color,
        ResourceLocation model,
        List<LotteryPlacement> lotteryPlacements) {

    public static final Set<String> SUPPORTED_TYPES = Set.of("knife", "revolver", "bat", "grenade", "hat");
    private static final Pattern SKIN_ID = Pattern.compile("[a-z0-9_.-]+");

    public SkinDefinition {
        type = normalizeType(type, false);
        id = normalizeId(id);
        model = Objects.requireNonNull(model, "model");
        lotteryPlacements = lotteryPlacements == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(lotteryPlacements));
        if ("hat".equals(type) && !lotteryPlacements.isEmpty()) {
            throw new IllegalArgumentException("Hat skins cannot be placed in SRE weapon lottery pools");
        }
    }

    public static Builder builder(String type, String id, int color) {
        return new Builder(type, id, color);
    }

    /** SRE's pool format uses {@code gun/} for the {@code revolver} skin type. */
    public String lotteryEntry() {
        String poolPrefix = "revolver".equals(type) ? "gun" : type;
        return poolPrefix + "/" + id;
    }

    /** Resolves the model used by SRE's general skin model loader. */
    public ResourceLocation model(boolean inHand) {
        if (!inHand) {
            return model;
        }
        return model.withPath(model.getPath() + "_in_hand");
    }

    static String normalizeType(String raw, boolean allowAll) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Skin type must not be blank");
        }
        String type = raw.trim().toLowerCase(Locale.ROOT);
        int colon = type.indexOf(':');
        if (colon >= 0 && colon < type.length() - 1) {
            type = type.substring(colon + 1);
        }
        if ("gun".equals(type)) {
            type = "revolver";
        }
        if ((allowAll && "all".equals(type)) || SUPPORTED_TYPES.contains(type)) {
            return type;
        }
        throw new IllegalArgumentException("Unsupported skin type: " + raw);
    }

    private static String normalizeId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Skin id must not be blank");
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (!SKIN_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Skin id must match [a-z0-9_.-]+ and cannot contain a namespace or slash: " + raw);
        }
        if ("default".equals(id) || "coin".equals(id)) {
            throw new IllegalArgumentException("Reserved skin id: " + id);
        }
        return id;
    }

    /** Places a skin in one zero-based quality band of every matching pool type. */
    public record LotteryPlacement(String poolType, int qualityBand) {
        public LotteryPlacement {
            poolType = normalizeType(poolType, true);
            if (qualityBand < 0) {
                throw new IllegalArgumentException("Lottery quality band must be >= 0");
            }
        }
    }

    public static final class Builder {
        private final String type;
        private final String id;
        private final int color;
        private ResourceLocation model;
        private final List<LotteryPlacement> placements = new ArrayList<>();

        private Builder(String type, String id, int color) {
            this.type = normalizeType(type, false);
            this.id = normalizeId(id);
            this.color = color;
            this.model = ResourceLocation.fromNamespaceAndPath(
                    "starrailexpress", "item/skins/" + this.type + "/" + this.id);
        }

        /**
         * Sets the base item-model id. The in-hand model is resolved by appending
         * {@code _in_hand} to this path.
         */
        public Builder model(ResourceLocation model) {
            this.model = Objects.requireNonNull(model, "model");
            return this;
        }

        public Builder model(String namespace, String path) {
            return model(ResourceLocation.fromNamespaceAndPath(namespace, path));
        }

        /** Adds the skin to band 0 of its matching type pools and all-random pools. */
        public Builder includeInDefaultPools() {
            placements.add(new LotteryPlacement(type, 0));
            placements.add(new LotteryPlacement("all", 0));
            return this;
        }

        /** Adds the skin to a specific band of every pool with the supplied pool type. */
        public Builder addToPool(String poolType, int qualityBand) {
            placements.add(new LotteryPlacement(poolType, qualityBand));
            return this;
        }

        public SkinDefinition build() {
            return new SkinDefinition(type, id, color, model, placements);
        }
    }
}
