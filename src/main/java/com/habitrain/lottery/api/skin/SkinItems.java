package com.habitrain.lottery.api.skin;

import com.habitrain.lottery.skin.SkinComponents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Item bindings use registry ids or data-pack tags, never upstream Java item classes. */
public final class SkinItems {
    private static final List<String> TYPES = SkinDefinition.SUPPORTED_TYPES.stream().sorted().toList();
    /**
     * The {@code habitrain_lottery:skin_items/<type>} tags, created once. They were
     * rebuilt per inventory slot per tick by the server-side skin mirror, i.e. 5
     * {@link TagKey#create} calls per slot per player per tick.
     */
    private static final Map<String, TagKey<Item>> TYPE_TAGS = buildTypeTags();
    /** Copy-on-write immutable snapshot so render/tick lookups in {@link #typeOf} are lock-free. */
    private static volatile Map<ResourceLocation, String> BINDINGS = Map.of();
    private static final Map<ResourceLocation, String> BINDING_PROVIDERS = new LinkedHashMap<>();
    /**
     * Vanilla stand-ins used when the data-pack tag lists no installed item, so a preview
     * never degrades to a blank paper icon. The tag stays authoritative whenever it resolves.
     */
    private static final Map<String, List<String>> FALLBACK_ITEMS = Map.of(
            "knife", List.of("minecraft:iron_sword"),
            "revolver", List.of("minecraft:crossbow"),
            "bat", List.of("minecraft:stick"),
            "grenade", List.of("minecraft:snowball"),
            "hat", List.of("minecraft:leather_helmet"),
            "back", List.of("minecraft:elytra"));
    private SkinItems() {}

    private static Map<String, TagKey<Item>> buildTypeTags() {
        Map<String, TagKey<Item>> tags = new LinkedHashMap<>();
        for (String type : TYPES) {
            tags.put(type, TagKey.create(Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "skin_items/" + type)));
        }
        return Map.copyOf(tags);
    }

    public static void bindItem(ResourceLocation item, String type) {
        String canonical = SkinDefinition.normalizeType(type);
        Objects.requireNonNull(item, "item");
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            String provider = HabiSkinApi.currentProvider();
            String previous = BINDINGS.get(item);
            if (previous != null && !previous.equals(canonical)) {
                throw new IllegalStateException("Conflicting skin item: " + item);
            }
            if (previous == null) {
                Map<ResourceLocation, String> next = new LinkedHashMap<>(BINDINGS);
                next.put(item, canonical);
                BINDINGS = Collections.unmodifiableMap(next);
                String prevProvider = provider == null
                        ? BINDING_PROVIDERS.remove(item)
                        : BINDING_PROVIDERS.put(item, provider);
                SkinRegistrationTransaction.recordUndo(() -> {
                    if (canonical.equals(BINDINGS.get(item))) {
                        Map<ResourceLocation, String> restored = new LinkedHashMap<>(BINDINGS);
                        restored.remove(item);
                        BINDINGS = Collections.unmodifiableMap(restored);
                    }
                    if (prevProvider == null) {
                        BINDING_PROVIDERS.remove(item);
                    } else {
                        BINDING_PROVIDERS.put(item, prevProvider);
                    }
                });
            } else if (provider != null && !BINDING_PROVIDERS.containsKey(item)) {
                BINDING_PROVIDERS.put(item, provider);
                SkinRegistrationTransaction.recordUndo(() -> BINDING_PROVIDERS.remove(item));
            }
        }
    }

    static void removeProvider(String providerId) {
        if (providerId == null) {
            return;
        }
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            List<ResourceLocation> owned = new java.util.ArrayList<>();
            for (Map.Entry<ResourceLocation, String> entry : BINDING_PROVIDERS.entrySet()) {
                if (providerId.equals(entry.getValue())) {
                    owned.add(entry.getKey());
                }
            }
            if (owned.isEmpty()) {
                return;
            }
            Map<ResourceLocation, String> next = new LinkedHashMap<>(BINDINGS);
            for (ResourceLocation item : owned) {
                String prevType = next.remove(item);
                String prevProvider = BINDING_PROVIDERS.remove(item);
                if (prevType != null) {
                    SkinRegistrationTransaction.recordUndo(() -> {
                        Map<ResourceLocation, String> restored = new LinkedHashMap<>(BINDINGS);
                        restored.put(item, prevType);
                        BINDINGS = Collections.unmodifiableMap(restored);
                        if (prevProvider != null) {
                            BINDING_PROVIDERS.put(item, prevProvider);
                        }
                    });
                }
            }
            BINDINGS = Collections.unmodifiableMap(next);
        }
    }

    static void clearForTest() {
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            BINDINGS = Map.of();
            BINDING_PROVIDERS.clear();
        }
    }

    /** Immutable snapshot of explicitly registered item-to-skin-type bindings in registration order. */
    public static Map<ResourceLocation, String> bindings() {
        return BINDINGS;
    }

    static String boundType(ResourceLocation item) {
        return item == null ? null : BINDINGS.get(item);
    }

    public static String typeOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String bound = BINDINGS.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (bound != null) return bound;
        for (String type : TYPES) {
            TagKey<Item> tag = TYPE_TAGS.get(type);
            if (tag != null && stack.is(tag)) return type;
        }
        return null;
    }

    /**
     * The raw {@code type/id} skin component carried by {@code stack}, or {@code null}
     * when the stack carries none.
     *
     * <p>This is the same value {@code SkinDefinition} registrations and mail
     * attachments are keyed by, so an integration that has a stack in hand (a flying
     * projectile, an item entity, a custom renderer) can resolve its skin without
     * reaching into this mod's internals.</p>
     */
    public static String entryOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.get(SkinComponents.SKIN);
    }

    public static ItemStack preview(String entry) {
        var definition = HabiSkinApi.fromEntry(entry);
        if (definition.isEmpty()) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(SkinComponents.SKIN, definition.get().type() + "/" + definition.get().id());
        return stack;
    }

    /**
     * The item a skin type is carried by, in priority order: an explicit
     * {@link #bindItem} registration, the first installed entry of the
     * {@code habitrain_lottery:skin_items/<type>} tag, then a vanilla stand-in. Never
     * air, so the wardrobe can render a real "default appearance" icon and a real
     * third-person base item instead of an unrelated placeholder.
     *
     * <p>{@code bindItem} used to affect recognition only, so a mod that bound its own
     * item with {@code SkinItems.bindItem(...)} still previewed and dressed the vanilla
     * stand-in (audit F-09). API bindings now win over the tag here too, matching the
     * documented "API 绑定优先于标签" rule; the tag remains the fallback for skins
     * supplied purely through a data pack.</p>
     */
    public static Item defaultItem(String type) {
        String canonical = SkinDefinition.normalizeType(type);
        Item bound = firstBoundItem(canonical);
        if (bound != null) return bound;
        TagKey<Item> tag = TYPE_TAGS.get(canonical);
        if (tag != null) {
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                Item item = holder.value();
                if (item != null && item != Items.AIR) return item;
            }
        }
        for (String id : FALLBACK_ITEMS.getOrDefault(canonical, List.of())) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            if (item != Items.AIR) return item;
        }
        return Items.PAPER;
    }

    /**
     * The first still-installed item explicitly bound to {@code canonicalType}, or
     * {@code null} when the type has no usable binding. Bindings are kept in
     * registration order so the first extension to bind an item owns the preview base.
     */
    private static Item firstBoundItem(String canonicalType) {
        for (Map.Entry<ResourceLocation, String> binding : BINDINGS.entrySet()) {
            if (!canonicalType.equals(binding.getValue())) continue;
            Item item = BuiltInRegistries.ITEM.get(binding.getKey());
            if (item != null && item != Items.AIR) return item;
        }
        return null;
    }

    /** The type's unskinned item, i.e. what "default appearance" resolves to. */
    public static ItemStack defaultStack(String type) {
        return new ItemStack(defaultItem(type));
    }

    /**
     * The type's real item carrying the requested skin component, i.e. what other players
     * would receive. Returns an empty stack for an unregistered {@code type/id} pair.
     */
    public static ItemStack styled(String type, String id) {
        var definition = HabiSkinApi.find(type, id);
        if (definition.isEmpty()) return ItemStack.EMPTY;
        String canonical = definition.get().type();
        ItemStack stack = new ItemStack(defaultItem(canonical));
        stack.set(SkinComponents.SKIN, canonical + "/" + definition.get().id());
        return stack;
    }
}
