package com.habitrain.lottery.client.gui;

import io.wifi.starrailexpress.SREClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Third-person preview of the local player inside a GUI box.
 *
 * <p>The model is drawn with the equipment the wardrobe wants to show, so a skin is displayed
 * exactly the way other clients receive it: the real item stack carrying the
 * {@code habitrain_lottery:skin} component, rendered through the normal item renderer and its
 * {@code _in_hand} model. Every mutated entity (and client config) field is restored in a
 * finally block, mirroring how {@link InventoryScreen} renders the player in the inventory.</p>
 */
public final class PlayerPreview {
    private static final float HEAD_OFFSET = 0.0625F;
    private static final float DEG = (float) (Math.PI / 180.0);

    private PlayerPreview() {}

    /** True when a local player exists and the model can be drawn at all. */
    public static boolean available() {
        return Minecraft.getInstance().player != null;
    }

    /**
     * Renders the local player centred in the given GUI rectangle.
     *
     * @param yaw   horizontal angle in radians, same convention as the vanilla skin preview
     *              ({@code 0} faces the viewer, unbounded so the model can be spun around)
     * @param pitch vertical angle in radians, same convention as {@code setXRot}
     * @param mainHand stack shown in the main hand, ignored when empty
     * @param head     stack shown on the head, ignored when empty
     * @param hideHats suppress the upstream hat layer while previewing so a worn hat cannot
     *                 overlap the hat being previewed
     */
    public static void render(GuiGraphics g, int x1, int y1, int x2, int y2, float yaw, float pitch,
                              ItemStack mainHand, ItemStack head, boolean hideHats) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || x2 - x1 <= 8 || y2 - y1 <= 8) return;
        float prevBody = player.yBodyRot, prevYaw = player.getYRot(), prevPitch = player.getXRot();
        float prevHeadO = player.yHeadRotO, prevHead = player.yHeadRot;
        ItemStack prevMain = player.getMainHandItem().copy();
        ItemStack prevHeadStack = player.getItemBySlot(EquipmentSlot.HEAD).copy();
        SREClientConfig config = hideHats ? SREClientConfig.instance() : null;
        boolean prevHideHats = config != null && config.hideAllHats;
        try {
            if (mainHand != null && !mainHand.isEmpty()) player.setItemSlot(EquipmentSlot.MAINHAND, mainHand);
            if (head != null && !head.isEmpty()) player.setItemSlot(EquipmentSlot.HEAD, head);
            if (config != null) config.hideAllHats = true;

            float entityScale = Math.max(0.01F, player.getScale());
            float modelHeight = Math.max(0.2F, player.getBbHeight() / entityScale);
            // Vanilla divides the GUI scale by the entity scale, so the on-screen height is
            // modelHeight * desired; keep the model inside the box with a small margin.
            float desired = Math.min((y2 - y1) * 0.85F / modelHeight, (x2 - x1) / 0.9F);
            Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf camera = new Quaternionf().rotateX(pitch * 20.0F * DEG);
            rotation.mul(camera);
            player.yBodyRot = 180.0F + yaw * 20.0F;
            player.setYRot(180.0F + yaw * 40.0F);
            player.setXRot(-pitch * 20.0F);
            player.yHeadRot = player.getYRot();
            player.yHeadRotO = player.getYRot();
            Vector3f translate = new Vector3f(0.0F,
                    player.getBbHeight() / 2.0F + HEAD_OFFSET * entityScale, 0.0F);
            g.enableScissor(x1, y1, x2, y2);
            InventoryScreen.renderEntityInInventory(g, (x1 + x2) / 2.0F, (y1 + y2) / 2.0F,
                    desired / entityScale, translate, rotation, camera, player);
            g.disableScissor();
        } finally {
            if (config != null) config.hideAllHats = prevHideHats;
            player.setItemSlot(EquipmentSlot.MAINHAND, prevMain);
            player.setItemSlot(EquipmentSlot.HEAD, prevHeadStack);
            player.yBodyRot = prevBody;
            player.setYRot(prevYaw);
            player.setXRot(prevPitch);
            player.yHeadRotO = prevHeadO;
            player.yHeadRot = prevHead;
        }
    }
}
