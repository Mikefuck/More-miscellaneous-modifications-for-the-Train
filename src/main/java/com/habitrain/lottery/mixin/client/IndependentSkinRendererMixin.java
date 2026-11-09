package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.api.skin.SkinItems;
import com.habitrain.lottery.api.skin.client.SkinPreviewContext;
import com.habitrain.lottery.bridge.DisguiseIdentityBridge;
import com.habitrain.lottery.bridge.SkinItemPolicy;
import com.habitrain.lottery.client.SkinClient;
import com.habitrain.lottery.skin.SkinComponents;
import com.habitrain.lottery.skin.SkinNetwork;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Applies our component at the vanilla renderer boundary, including held and dropped items. */
@Mixin(ItemRenderer.class)
public abstract class IndependentSkinRendererMixin {

    @Unique
    private static final ThreadLocal<List<WeakReference<LivingEntity>>> HABI_HOLDER_STACK =
            ThreadLocal.withInitial(ArrayList::new);

    @Inject(
            method = "renderStatic(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;III)V",
            at = @At("HEAD"),
            require = 0
    )
    private void habi$pushRenderHolder(
            LivingEntity entity,
            ItemStack itemStack,
            ItemDisplayContext displayContext,
            boolean leftHand,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            Level level,
            int combinedLight,
            int combinedOverlay,
            int seed,
            CallbackInfo ci) {
        List<WeakReference<LivingEntity>> stack = HABI_HOLDER_STACK.get();
        if (stack.size() >= 16) {
            stack.clear();
        }
        stack.add(entity == null ? null : new WeakReference<>(entity));
    }

    @Inject(
            method = "renderStatic(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;III)V",
            at = @At("RETURN"),
            require = 0
    )
    private void habi$popRenderHolder(
            LivingEntity entity,
            ItemStack itemStack,
            ItemDisplayContext displayContext,
            boolean leftHand,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            Level level,
            int combinedLight,
            int combinedOverlay,
            int seed,
            CallbackInfo ci) {
        List<WeakReference<LivingEntity>> stack = HABI_HOLDER_STACK.get();
        if (!stack.isEmpty()) {
            stack.remove(stack.size() - 1);
        }
    }

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private BakedModel habi$skinModel(BakedModel model, ItemStack stack, ItemDisplayContext context,
            boolean leftHand, PoseStack poses, MultiBufferSource buffers, int light, int overlay, BakedModel original) {
        boolean inHand = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        LivingEntity holder = habi$currentHolder();
        if (holder != null && SkinPreviewContext.currentFor(holder) == null) {
            DisguiseIdentityBridge.Resolution identity = DisguiseIdentityBridge.Client.resolve(holder);
            if (identity.kind() == DisguiseIdentityBridge.Kind.HIDDEN_ANONYMOUS) {
                return model;
            }
            if (identity.kind() == DisguiseIdentityBridge.Kind.DISGUISED_AS) {
                return habi$resolveDisguisedModel(holder, identity.targetUuid(), stack, inHand, model);
            }
        }
        return SkinClient.model(stack, inHand, model);
    }

    @Unique
    private static LivingEntity habi$currentHolder() {
        List<WeakReference<LivingEntity>> stack = HABI_HOLDER_STACK.get();
        if (stack.isEmpty()) {
            return null;
        }
        WeakReference<LivingEntity> ref = stack.get(stack.size() - 1);
        return ref == null ? null : ref.get();
    }

    @Unique
    private static BakedModel habi$resolveDisguisedModel(
            LivingEntity holder,
            UUID targetUuid,
            ItemStack stack,
            boolean inHand,
            BakedModel fallback) {
        if (targetUuid == null || stack == null || stack.isEmpty() || SkinItemPolicy.excludesSkin(stack)) {
            return fallback;
        }
        String type = SkinItems.typeOf(stack);
        if (type == null) {
            return fallback;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && targetUuid.equals(mc.player.getUUID())) {
            for (SkinNetwork.Entry entry : SkinClient.entries()) {
                if (entry.equipped() && type.equals(entry.type())) {
                    ItemStack synthetic = stack.copyWithCount(1);
                    synthetic.set(SkinComponents.SKIN, type + "/" + entry.id());
                    return SkinClient.model(synthetic, inHand, fallback);
                }
            }
            return fallback;
        }
        Level level = holder.level();
        Player target = level == null ? null : level.getPlayerByUUID(targetUuid);
        if (target != null) {
            ItemStack main = target.getMainHandItem();
            if (type.equals(SkinItems.typeOf(main))) {
                return SkinClient.model(main, inHand, fallback);
            }
            ItemStack off = target.getOffhandItem();
            if (type.equals(SkinItems.typeOf(off))) {
                return SkinClient.model(off, inHand, fallback);
            }
        }
        return fallback;
    }
}
