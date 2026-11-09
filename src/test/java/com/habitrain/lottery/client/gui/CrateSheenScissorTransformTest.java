package com.habitrain.lottery.client.gui;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateSheenScissorTransformTest {

    private static Matrix4f canvasPose(float guiWidth, float guiHeight) {
        float refW = 1920.0F;
        float refH = 1080.0F;
        float unit = Math.min(guiWidth / refW, guiHeight / refH);
        float canvasX = (guiWidth - refW * unit) * 0.5F;
        float canvasY = (guiHeight - refH * unit) * 0.5F;
        return new Matrix4f().translate(canvasX, canvasY, 0.0F).scale(unit, unit, 1.0F);
    }

    @Test
    void identityPosePreservesExactCardBounds() {
        Matrix4f identity = new Matrix4f();
        int[] bounds = CrateArt.transformScissorBounds(identity, 150.0F, 822.0F, 170.0F, 164.0F);
        assertArrayEquals(new int[]{150, 822, 320, 986}, bounds);
    }

    @Test
    void transforms1920x1080ReferenceCanvasAcrossGuiScales1To4() {
        // GUI Scale 1 on 1920x1080 -> 1920x1080 GUI coords (unit = 1.0)
        assertArrayEquals(
                new int[]{150, 822, 320, 986},
                CrateArt.transformScissorBounds(canvasPose(1920.0F, 1080.0F), 150.0F, 822.0F, 170.0F, 164.0F)
        );

        // GUI Scale 2 on 1920x1080 -> 960x540 GUI coords (unit = 0.5)
        assertArrayEquals(
                new int[]{75, 411, 160, 493},
                CrateArt.transformScissorBounds(canvasPose(960.0F, 540.0F), 150.0F, 822.0F, 170.0F, 164.0F)
        );

        // GUI Scale 3 on 1920x1080 -> 640x360 GUI coords (unit = 1/3)
        assertArrayEquals(
                new int[]{50, 274, 107, 329},
                CrateArt.transformScissorBounds(canvasPose(640.0F, 360.0F), 150.0F, 822.0F, 171.0F, 165.0F)
        );

        // GUI Scale 4 on 1920x1080 -> 480x270 GUI coords (unit = 0.25)
        assertArrayEquals(
                new int[]{40, 205, 82, 246},
                CrateArt.transformScissorBounds(canvasPose(480.0F, 270.0F), 160.0F, 820.0F, 168.0F, 164.0F)
        );
    }

    @Test
    void accountsForLetterboxAndPillarboxCanvasOffsets() {
        // 16:10 GUI (960x600): unit = 0.5, canvasX = 0, canvasY = 30
        assertArrayEquals(
                new int[]{75, 441, 160, 523},
                CrateArt.transformScissorBounds(canvasPose(960.0F, 600.0F), 150.0F, 822.0F, 170.0F, 164.0F)
        );

        // 21:9 GUI (1280x540): unit = 0.5, canvasX = 160, canvasY = 0
        assertArrayEquals(
                new int[]{235, 411, 320, 493},
                CrateArt.transformScissorBounds(canvasPose(1280.0F, 540.0F), 150.0F, 822.0F, 170.0F, 164.0F)
        );
    }

    @Test
    void negativeScaleStillProducesOrderedScissorCoordinates() {
        Matrix4f mirrored = new Matrix4f().translate(500.0F, 300.0F, 0.0F).scale(-0.5F, -0.5F, 1.0F);
        int[] bounds = CrateArt.transformScissorBounds(mirrored, 100.0F, 40.0F, 200.0F, 100.0F);
        assertTrue(bounds[0] <= bounds[2]);
        assertTrue(bounds[1] <= bounds[3]);
        assertArrayEquals(new int[]{350, 230, 450, 280}, bounds);
    }
}
