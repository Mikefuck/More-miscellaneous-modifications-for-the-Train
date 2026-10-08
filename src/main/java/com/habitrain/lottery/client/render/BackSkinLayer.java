package com.habitrain.lottery.client.render;

import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.client.BackSkinClient;
import com.habitrain.lottery.client.SkinAnimationModels;
import com.habitrain.lottery.client.SkinClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;

/** Independent cosmetic anchored to the torso, following crouching, swimming and body rotation. */
@Environment(EnvType.CLIENT)
public final class BackSkinLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    public BackSkinLayer(PlayerRenderer renderer) { super(renderer); }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (player.isInvisible() || player.isSpectator()) return;
        var skin = BackSkinClient.appearanceFor(player).orElse(null);
        if (skin == null) return;
        var model = SkinClient.bakedModel(skin.model());
        if (model == null) return;
        var stack = SkinItems.preview("back/" + skin.id());
        pose.pushPose();
        try {
            getParentModel().body.translateAndRotate(pose);
            // Torso centre (6 pixels down), just behind its rear surface (2 pixels).
            // FIXED in the extension's model JSON controls size, offset and orientation.
            pose.translate(0.0F, 0.375F, 0.1875F);
            pose.scale(1.0F, -1.0F, -1.0F);
            Minecraft.getInstance().getItemRenderer().render(stack, ItemDisplayContext.FIXED, false,
                    pose, buffers, light, OverlayTexture.NO_OVERLAY, SkinAnimationModels.animate(skin, model));
        } finally { pose.popPose(); }
    }
}
