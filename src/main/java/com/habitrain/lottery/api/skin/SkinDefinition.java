package com.habitrain.lottery.api.skin;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable definition of one independent item skin. */
public record SkinDefinition(
        String type,
        String id,
        int color,
        ResourceLocation model,
        List<LotteryPlacement> lotteryPlacements) {

    public static final Set<String> SUPPORTED_TYPES = Set.of("knife", "revolver", "bat", "grenade", "hat");
    /** Namespace used when a definition does not pin an explicit model. */
    public static final String DEFAULT_MODEL_NAMESPACE = "habitrain_lottery";
    /** Namespace v1 of this API used for the same default model; kept for migration only. */
    public static final String LEGACY_MODEL_NAMESPACE = "starrailexpress";
    private static final Pattern SKIN_ID = Pattern.compile("[a-z0-9_.-]+");

    public SkinDefinition {
        type = normalizeType(type, false);
        id = normalizeId(id);
        model = Objects.requireNonNull(model, "model");
        lotteryPlacements = lotteryPlacements == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(lotteryPlacements));

    }

    public static Builder builder(String type, String id, int color) {
        return new Builder(type, id, color);
    }

    /** SRE's pool format uses {@code gun/} for the {@code revolver} skin type. */
    public String lotteryEntry() {
        String poolPrefix = "revolver".equals(type) ? "gun" : type;
        return poolPrefix + "/" + id;
    }

    /**
     * Resolves the model used by the independent model loader.
     *
     * <p>When no model was supplied, the base id is
     * {@code habitrain_lottery:item/skins/<type>/<id>}. Version 1 of this API used
     * {@code starrailexpress:<type>/<id>} instead, and v2 keeps that id available
     * through {@link #legacyModel(boolean)} so an extension that has not been
     * recompiled yet still resolves its old resource pack assets.</p>
     */
    public ResourceLocation model(boolean inHand) {
        if (!inHand) {
            return model;
        }
        return model.withPath(model.getPath() + "_in_hand");
    }

    /**
     * The same model expressed in the v1 {@code starrailexpress} namespace, or
     * {@code null} when this definition pinned an explicit model (in which case the
     * extension already controls its own paths and there is no legacy id to try).
     */
    public ResourceLocation legacyModel(boolean inHand) {
        if (!usesDefaultModel()) {
            return null;
        }
        ResourceLocation legacy = ResourceLocation.fromNamespaceAndPath(
                LEGACY_MODEL_NAMESPACE, type + "/" + id);
        return inHand ? legacy.withPath(legacy.getPath() + "_in_hand") : legacy;
    }

    /** True when {@link #model} is the API default rather than an explicit override. */
    public boolean usesDefaultModel() {
        return DEFAULT_MODEL_NAMESPACE.equals(model.getNamespace())
                && ("item/skins/" + type + "/" + id).equals(model.getPath());
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
        String id = normalizeSkinId(raw);
        if (id == null) {
            throw new IllegalArgumentException("Skin id must not be blank");
        }
        return id;
    }

    /**
     * The single normalisation every entry point shares: {@code trim + lower case},
     * then the catalogue id rules. Mail attachments and player equipment used to
     * validate the raw string instead, so a value that registered fine could throw
     * only when it was first mailed; routing all of them through here keeps
     * registration, unlock and mail attachments consistent.
     *
     * @return the canonical id, or {@code null} when {@code raw} is blank
     * @throws IllegalArgumentException when the id is reserved or malformed
     */
    public static String normalizeSkinId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.length() > 48 || !SKIN_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Skin id must match [a-z0-9_.-]{1,48} and cannot contain a namespace or slash: " + raw);
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
                    DEFAULT_MODEL_NAMESPACE, "item/skins/" + this.type + "/" + this.id);
        }

        /**
         * Sets the base item-model id. The in-hand model is resolved by appending
         * {@code _in_hand} to this path.
         *
         * <p>Extensions are expected to point this at their own namespace, e.g.
         * {@code .model("example", "item/skins/knife/crystal")}. Leaving it unset
         * resolves to {@code habitrain_lottery:item/skins/<type>/<id>}, which this
         * mod ships no assets for, so the wardrobe reports the skin as missing
         * instead of silently rendering a purple-and-black model.</p>
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
