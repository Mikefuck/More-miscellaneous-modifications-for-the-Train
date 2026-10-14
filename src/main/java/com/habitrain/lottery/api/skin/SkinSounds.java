package com.habitrain.lottery.api.skin;

import com.habitrain.lottery.skin.SkinComponents;
import net.minecraft.world.item.ItemStack;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Declares which skins supply optional custom weapon sounds. Safe on both physical sides. */
public final class SkinSounds {
    public static final int API_VERSION = 1;
    private static final Map<String, String> PROVIDERS = new ConcurrentHashMap<>();

    private SkinSounds() { }

    /** Register after the skin definition, from the same skin registrar. Replays are idempotent. */
    public static void register(String type, String id) {
        var skin = HabiSkinApi.find(type, id).orElseThrow(() ->
                new IllegalArgumentException("Custom sounds require a registered skin: " + type + "/" + id));
        String key = skin.type() + "/" + skin.id();
        String provider = HabiSkinApi.currentProvider();
        String value = provider == null ? "" : provider;
        String previous = PROVIDERS.putIfAbsent(key, value);
        if (previous == null) SkinRegistrationTransaction.changed(PROVIDERS, key, null, value);
    }

    public static boolean supports(String type, String id) {
        return HabiSkinApi.find(type, id)
                .map(skin -> PROVIDERS.containsKey(skin.type() + "/" + skin.id())).orElse(false);
    }

    public static boolean supportsEntry(String entry) {
        var skin = HabiSkinApi.fromEntry(entry);
        return skin.isPresent() && supports(skin.get().type(), skin.get().id());
    }

    /**
     * Whether this stack's owner enabled the skin's custom sounds. The server writes the
     * preference to the stack; vanilla equipment networking delivers it to every listener.
     * Missing preferences default to enabled for compatibility with existing stacks.
     */
    public static boolean isEnabled(ItemStack stack) {
        return stack != null && supportsEntry(SkinItems.entryOf(stack))
                && !Boolean.FALSE.equals(stack.get(SkinComponents.CUSTOM_SOUND));
    }

    /** Rolls back sound declarations from a failed registrar alongside its skin definitions. */
    public static int removeProvider(String provider) {
        if (provider == null) return 0;
        return SkinRegistrationTransaction.removeIf(PROVIDERS, provider::equals);
    }

    static void removeEntry(String entry) { SkinRegistrationTransaction.remove(PROVIDERS, entry); }
}
