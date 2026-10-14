package com.habitrain.lottery.client;

import com.habitrain.lottery.api.skin.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.resources.model.BakedModel;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SkinAnimationModelsTest {
    @Test void existingSkinPackBobMixinStillFindsItsRequiredInjectionPoint() throws IOException {
        // habitrain_skins 1.0.65 wraps this exact invocation in apply, with require=1.
        // Moving the call into a helper breaks the installed skin pack at class load.
        String className = SkinAnimationModels.class.getName().replace('.', '/') + "$AnimatedItemTransform";
        ClassNode transform = new ClassNode();
        try (var input = SkinAnimationModels.class.getResourceAsStream("/" + className + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(transform, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        String descriptor = Type.getMethodDescriptor(Type.VOID_TYPE, Type.BOOLEAN_TYPE, Type.getType(PoseStack.class));
        var apply = transform.methods.stream()
                .filter(method -> method.name.equals("apply") && method.desc.equals(descriptor))
                .findFirst().orElseThrow();
        long matches = Arrays.stream(apply.instructions.toArray())
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals(Type.getInternalName(PoseStack.class))
                        && call.name.equals("translate") && call.desc.equals("(FFF)V"))
                .count();
        assertEquals(1L, matches,
                "Keep the bob translation inside apply for habitrain_skins SkinAnimationBobMixin");
    }

    @Test void bobConvertsModelUnitsToBlocksAndKeepsPhasePastOldClockWrap() throws Exception {
        Class<?> transform = Class.forName(SkinAnimationModels.class.getName() + "$AnimatedItemTransform");
        var constructor = transform.getDeclaredConstructor(ItemTransform.class, SkinAnimation.class, float.class);
        constructor.setAccessible(true);
        var offset = transform.getDeclaredMethod("bobOffsetAt", double.class);
        offset.setAccessible(true);
        Object animated = constructor.newInstance(ItemTransform.NO_TRANSFORM,
                SkinAnimation.builder().bob(0.6F, 80).build(), 1.0F);
        assertEquals(0.6 / 16.0, (float) offset.invoke(animated, 20.0), 0.00001);
        assertEquals(0.6 / 16.0, (float) offset.invoke(animated, 20_000_020.0), 0.00001);
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
