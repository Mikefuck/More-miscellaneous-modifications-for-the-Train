package com.habitrain.lottery.client;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.skin.SkinNetwork;
import com.habitrain.lottery.skin.SkinComponents;
import com.habitrain.lottery.client.gui.SkinWardrobeScreen;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SkinClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_lottery_skins");
    private static List<SkinNetwork.Entry> entries = List.of();
    private SkinClient() {}
    public static List<SkinNetwork.Entry> entries() { return entries; }
    public static void register() {
        ModelLoadingPlugin.register(ctx -> {
            // One registration per distinct id; the catalogue is de-duplicated here
            // because addModels only accepts a collection of ids.
            Set<ResourceLocation> requested = new LinkedHashSet<>();
            for (var skin : HabiSkinApi.registrations()) {
                requested.add(skin.model());
                requested.add(skin.model(true));
                ResourceLocation legacy = skin.legacyModel(false);
                if (legacy != null) {
                    requested.add(legacy);
                    requested.add(skin.legacyModel(true));
                }
            }
            requested.forEach(ctx::addModels);
            // Wrappers point at baked models, so they must be dropped whenever models are
            // (re)baked; otherwise a resource reload would animate yesterday's model.
            SkinAnimationModels.invalidate();
            warnAboutMissingModels();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> entries = List.of());
        ClientPlayNetworking.registerGlobalReceiver(SkinNetwork.Snapshot.TYPE, (p, ctx) -> ctx.client().execute(() -> {
            entries = p.entries();
            if (ctx.client().screen instanceof SkinWardrobeScreen screen) screen.refresh();
            else if (p.open()) {
                var screen = new SkinWardrobeScreen(ctx.client().screen);
                ctx.client().setScreen(screen);
                screen.refresh();
            }
        }));
    }

    /**
     * Resolves the model for a skin-carrying stack.
     *
     * <p>Vanilla and Fabric both express "no such model" with a baked stand-in that
     * carries the {@code minecraft:missingno} particle sprite — and, crucially, the
     * model loader caches per {@code (location, rotation, uvLock)}, so the stand-in
     * returned for one id is a <em>different instance</em> from
     * {@code ModelManager#getMissingModel()}. Comparing references therefore never
     * matched, which turned "missing model" into a purple-and-black render instead of
     * the documented fallback. Availability is decided by the sprite instead.</p>
     */
    public static BakedModel model(ItemStack stack, boolean inHand, BakedModel fallback) {
        var skin = HabiSkinApi.fromEntry(stack.get(SkinComponents.SKIN));
        if (skin.isEmpty()) return fallback;
        SkinDefinition definition = skin.get();
        BakedModel model = bakedModel(definition.model(inHand));
        // The in-hand model is optional: fall back to the base model, then the
        // original item, exactly as docs/skin-api.md promises.
        if (model == null && inHand) model = bakedModel(definition.model());
        if (model == null && definition.usesDefaultModel()) {
            // v1 resolved the same default model under starrailexpress; try it so
            // extensions that have not been recompiled keep working (audit F-04).
            model = legacyLookup(definition, inHand);
        }
        if (model == null) return fallback;
        // Last step: hand the model to the skin-effects half, which wraps it when the
        // extension registered a SkinAnimation for this skin. A skin without one — every
        // v2-era skin — comes back untouched, so this cannot change existing looks.
        return SkinAnimationModels.animate(definition, model);
    }

    /** The v1 {@code starrailexpress} model, preferring the requested variant over the base one. */
    private static BakedModel legacyLookup(SkinDefinition definition, boolean inHand) {
        BakedModel legacy = bakedModel(definition.legacyModel(inHand));
        return legacy == null && inHand ? bakedModel(definition.legacyModel(false)) : legacy;
    }

    /** True when the base or the optional in-hand model exists for {@code type/id}. */
    public static boolean hasModel(String type, String id) {
        return HabiSkinApi.find(type, id)
                .map(definition -> bakedModel(definition.model()) != null
                        || bakedModel(definition.model(true)) != null)
                .orElse(false);
    }

    /**
     * The baked model for {@code id}, or {@code null} when the id is unknown to the
     * bakery (never added, or registered after the last resource reload) or baked to
     * the missing-model stand-in.
     */
    public static BakedModel bakedModel(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        BakedModel model;
        try {
            // FabricBakedModelManager.getModel(ResourceLocation): a bare map lookup that
            // returns null for an id that is not in the baked table.
            model = Minecraft.getInstance().getModelManager().getModel(id);
        } catch (RuntimeException e) {
            LOGGER.debug("Model lookup failed for {}", id, e);
            return null;
        }
        return isMissing(model) ? null : model;
    }

    /** True for {@code null} and for the missing-model stand-in (missingno particle sprite). */
    public static boolean isMissing(BakedModel model) {
        if (model == null) {
            return true;
        }
        TextureAtlasSprite particle = model.getParticleIcon();
        if (particle == null) {
            return false;
        }
        ResourceLocation sprite = particle.contents().name();
        return MissingTextureAtlasSprite.getLocation().equals(sprite);
    }

    /**
     * One-shot diagnostic after each reload: a skin whose model is baked to the
     * missing stand-in is almost always a path/namespace mistake in the extension
     * (a v1 extension that omitted {@code .model(...)} resolves to
     * {@code habitrain_lottery:item/skins/<type>/<id>}, which this mod ships no
     * assets for). The wardrobe reports those as "missing" instead of letting them
     * be equipped and rendered as a purple-black model.
     */
    private static void warnAboutMissingModels() {
        for (SkinDefinition definition : HabiSkinApi.registrations()) {
            if (bakedModel(definition.model()) != null) {
                continue;
            }
            LOGGER.warn(
                    "Skin {} declares model {} but no such model could be baked; the wardrobe "
                            + "will report it as missing. Pin an explicit model namespace "
                            + "(.model(\"yourmod\", \"item/skins/...\")) so the extension owns its assets",
                    definition.type() + "/" + definition.id(), definition.model());
        }
    }
}
