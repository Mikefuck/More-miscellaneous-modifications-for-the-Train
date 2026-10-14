package com.habitrain.lottery.client;

import com.habitrain.lottery.api.skin.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.resources.model.BakedModel;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class SkinAnimationModelsTest {
    @Test void bobConvertsModelUnitsToBlocksAndKeepsPhasePastOldClockWrap() throws Exception {
        Class<?> transform = Class.forName(SkinAnimationModels.class.getName() + "$AnimatedItemTransform");
        var constructor = transform.getDeclaredConstructor(ItemTransform.class, SkinAnimation.class, float.class);
        constructor.setAccessible(true);
        var apply = transform.getDeclaredMethod("applyAt", boolean.class, PoseStack.class, double.class);
        apply.setAccessible(true);
        Object animated = constructor.newInstance(ItemTransform.NO_TRANSFORM,
                SkinAnimation.builder().bob(0.6F, 80).build(), 1.0F);
        PoseStack pose = new PoseStack();
        apply.invoke(animated, false, pose, 20.0);
        assertEquals(0.6 / 16.0, pose.last().pose().m31(), 0.00001);
        pose = new PoseStack();
        apply.invoke(animated, false, pose, 20_000_020.0);
        assertEquals(0.6 / 16.0, pose.last().pose().m31(), 0.00001);
    }

    @Test void replacingAnimationCreatesNewModelWrapper() {
        var skin = SkinDefinition.builder("knife", "animation_cache_test", 0).build();
        HabiSkinApi.register(skin);
        BakedModel model = (BakedModel) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BakedModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTransforms" -> ItemTransforms.NO_TRANSFORMS;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        SkinEffects.registerAnimation("knife", skin.id(), SkinAnimation.builder().bob(1, 80).build());
        BakedModel first = SkinAnimationModels.animate(skin, model);
        assertSame(first, SkinAnimationModels.animate(skin, model));
        SkinEffects.registerAnimation("knife", skin.id(), SkinAnimation.builder().bob(2, 80).build());
        assertNotSame(first, SkinAnimationModels.animate(skin, model));
        SkinAnimationModels.invalidate();
    }
}
