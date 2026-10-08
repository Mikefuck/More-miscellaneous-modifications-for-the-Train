package com.habitrain.lottery.client;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.client.SkinPreviewContext;
import com.habitrain.lottery.client.render.BackSkinLayer;
import com.habitrain.lottery.skin.BackSkinNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Client-thread back appearance cache; wardrobe preview overrides never mutate saved state. */
@Environment(EnvType.CLIENT)
public final class BackSkinClient {
    private static final Map<UUID, String> EQUIPPED = new HashMap<>();
    private BackSkinClient() { }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BackSkinNetwork.Appearance.TYPE, (p, ctx) -> ctx.client().execute(() -> {
            if (p.skin().isEmpty()) EQUIPPED.remove(p.player());
            else EQUIPPED.put(p.player(), p.skin());
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> EQUIPPED.clear());
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof PlayerRenderer playerRenderer) helper.register(new BackSkinLayer(playerRenderer));
        });
    }

    public static Optional<SkinDefinition> appearanceFor(LivingEntity player) {
        var preview = SkinPreviewContext.currentFor(player);
        if (preview != null && preview.entry().startsWith("back/")) {
            // back/default intentionally resolves to empty, suppressing the worn cosmetic.
            return HabiSkinApi.fromEntry(preview.entry());
        }
        return HabiSkinApi.find("back", EQUIPPED.get(player.getUUID()));
    }
}
