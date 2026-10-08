package com.habitrain.lottery.api.skin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Optional text in the bottom-right corner of a wardrobe skin card. Since 1.1.52. */
public final class SkinCardText {
    public static final int API_VERSION = 1;
    private static final Map<String, Declaration> TEXTS = new LinkedHashMap<>();

    private record Declaration(Component text, String provider) { }

    private SkinCardText() { }

    /** Convenience overload for literal text. */
    public static boolean register(String type, String id, String text) {
        return register(type, id, Component.literal(Objects.requireNonNull(text, "text")));
    }

    /**
     * Declares one single-line label for exactly one type/id. Components may use
     * translations and styles. Call from {@link SkinRegistrar#registerSkins()} on
     * both sides; the label is local metadata, not a server-synchronized player asset.
     * The skin may be registered later, so declarations are independent of mod order.
     *
     * @return true for a new declaration, false for an identical replay
     * @throws IllegalStateException if different text already occupies this skin's corner
     */
    public static synchronized boolean register(String type, String id, Component text) {
        String key = key(type, id);
        Objects.requireNonNull(text, "text");
        String value = text.getString();
        if (value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Skin card text must be a nonblank single line");
        }
        Declaration current = TEXTS.get(key);
        if (current != null) {
            if (current.text().equals(text)) return false;
            throw new IllegalStateException("Conflicting skin card text for " + key);
        }
        if (TEXTS.size() >= 4096) throw new IllegalStateException("Skin card text limit is 4096");
        TEXTS.put(key, new Declaration(copyText(text), HabiSkinApi.currentProvider()));
        return true;
    }

    /** Returns a defensive copy; absent labels leave the wardrobe card unchanged. */
    public static synchronized Optional<Component> find(String type, String id) {
        try {
            Declaration declaration = TEXTS.get(key(type, id));
            return declaration == null ? Optional.empty() : Optional.of(copyText(declaration.text()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /** Removes this skin's label. The open wardrobe observes the change on its next render. */
    public static synchronized boolean unregister(String type, String id) {
        try {
            return TEXTS.remove(key(type, id)) != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Rolls back declarations made by a failed skin registrar, including labels for other mods' skins. */
    public static synchronized int removeProvider(String providerId) {
        if (providerId == null) return 0;
        int before = TEXTS.size();
        TEXTS.values().removeIf(declaration -> providerId.equals(declaration.provider()));
        return before - TEXTS.size();
    }

    static synchronized void removeEntry(String entry) { TEXTS.remove(entry); }

    // MutableComponent.copy() shares its children and translation arguments.
    private static MutableComponent copyText(Component text) {
        MutableComponent copy;
        if (text.getContents() instanceof TranslatableContents translation) {
            Object[] args = translation.getArgs().clone();
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Component argument) args[i] = copyText(argument);
            }
            copy = MutableComponent.create(new TranslatableContents(
                    translation.getKey(), translation.getFallback(), args));
        } else copy = MutableComponent.create(text.getContents());
        copy.setStyle(text.getStyle());
        for (Component sibling : text.getSiblings()) copy.append(copyText(sibling));
        return copy;
    }

    private static String key(String type, String id) {
        String normalizedType = SkinDefinition.normalizeType(type);
        String normalizedId = SkinDefinition.normalizeSkinId(id);
        if (normalizedId == null) throw new IllegalArgumentException("Skin id must not be blank");
        return normalizedType + "/" + normalizedId;
    }
}
