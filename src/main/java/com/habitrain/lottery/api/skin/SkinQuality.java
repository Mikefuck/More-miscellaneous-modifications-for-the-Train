package com.habitrain.lottery.api.skin;

/** Provider-declared skin quality. Independent of model tint and lottery pool bands. */
public enum SkinQuality {
    WHITE("white", 0xFFE1E5EB),
    BLUE("blue", 0xFF65AAFF),
    PURPLE("purple", 0xFFBD85F5),
    GOLD("gold", 0xFFF4C45F),
    RED("red", 0xFFFF707A);

    private final String id;
    private final int color;

    SkinQuality(String id, int color) {
        this.id = id;
        this.color = color;
    }

    /** Stable serialized id; never serialize enum ordinals. */
    public String id() { return id; }
    /** Opaque ARGB display color shared by the wardrobe and warehouse. */
    public int color() { return color; }
    public String translationKey() { return "skin.habitrain_lottery.quality." + id; }

    /** Missing or unrecognized metadata from older/newer catalogs falls back to white. */
    public static SkinQuality fromId(String id) {
        for (SkinQuality quality : values()) {
            if (quality.id.equals(id)) return quality;
        }
        return WHITE;
    }
}
