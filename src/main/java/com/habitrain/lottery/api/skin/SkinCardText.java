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
    public static boolean register(String type, String id, Component text) {
        String key = key(type, id);
        Objects.requireNonNull(text, "text");
        String value = text.getString();
        if (value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Skin card text must be a nonblank single line");
        }
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            Declaration current = TEXTS.get(key);
            if (current != null) {
                if (current.text().equals(text)) return false;
                throw new IllegalStateException("Conflicting skin card text for " + key);
            }
            if (TEXTS.size() >= 4096) throw new IllegalStateException("Skin card text limit is 4096");
            Declaration next = new Declaration(copyText(text), HabiSkinApi.currentProvider());
            TEXTS.put(key, next);
            SkinRegistrationTransaction.changed(TEXTS, key, null, next);
            return true;
        }
    }

    /** Returns a defensive copy; absent labels leave the wardrobe card unchanged. */
    public static Optional<Component> find(String type, String id) {
        try {
            String k = key(type, id);
            synchronized (HabiSkinApi.REGISTRY_LOCK) {
                Declaration declaration = TEXTS.get(k);
                return declaration == null ? Optional.empty() : Optional.of(copyText(declaration.text()));
            }
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /** Removes this skin's label. The open wardrobe observes the change on its next render. */
    public static boolean unregister(String type, String id) {
        try {
            String k = key(type, id);
            synchronized (HabiSkinApi.REGISTRY_LOCK) {
                return SkinRegistrationTransaction.remove(TEXTS, k) != null;
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Rolls back declarations made by a failed skin registrar, including labels for other mods' skins. */
    public static int removeProvider(String providerId) {
        if (providerId == null) return 0;
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            return SkinRegistrationTransaction.removeIf(TEXTS, declaration -> providerId.equals(declaration.provider()));
        }
    }

    static void removeEntry(String entry) {
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            SkinRegistrationTransaction.remove(TEXTS, entry);
        }
    }

    static void removeEntryIfOwnedOrUnowned(String entry, String providerId) {
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            Declaration current = TEXTS.get(entry);
            if (current != null && (current.provider() == null || current.provider().equals(providerId))) {
                SkinRegistrationTransaction.remove(TEXTS, entry);
            }
        }
    }

    static void clearForTest() {
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            TEXTS.clear();
        }
    }

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
