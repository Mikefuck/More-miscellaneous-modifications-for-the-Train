package com.habitrain.lottery.client.render;

import com.habitrain.lottery.block.LoginCalendarBlock;
import com.habitrain.lottery.block.LoginCalendarGroup;
import com.habitrain.lottery.network.LotteryNetwork;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-tile login calendar face renderer.
 * Drawn in LAST with its own immediate buffer so the batch is always flushed —
 * relying on AFTER_TRANSLUCENT + shared MultiBufferSource leaves quads unflushed
 * while the camera is still (only motion/other renders force a flush).
 *
 * Layout (full-calendar UV 0..1):
 * <pre>
 *  0.00-0.16  header: date/time left, streak right
 *  0.17-0.24  weekday labels 一..日
 *  0.26-0.98  7×6 date grid
 * </pre>
 */
@Environment(EnvType.CLIENT)
public final class LoginCalendarWorldRender {
    /** Approximate Minecraft font glyph height in font units. */
    private static final float FONT_PX = 9f;
    private static final String[] WEEKDAYS = {"一", "二", "三", "四", "五", "六", "日"};
    private static final String[] DAY_STRINGS = new String[32];
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<Long> IN_RANGE = new ArrayList<>();

    private static long clockEpochSec = Long.MIN_VALUE;
    private static String cachedTimeLine = "";
    private static String cachedDateTimeLine = "";
    private static int cachedDateYear = Integer.MIN_VALUE;
    private static int cachedDateMonth = Integer.MIN_VALUE;
    private static String cachedDateLine = "";
    private static int cachedStreak = Integer.MIN_VALUE;
    private static int cachedReward = Integer.MIN_VALUE;
    private static String cachedStreakText = "";

    static {
        for (int d = 1; d <= 31; d++) {
            DAY_STRINGS[d] = String.valueOf(d);
        }
    }

    /**
     * Push the whole calendar panel off the block face enough to beat vanilla depth fighting.
     * Combined with polygon offset this stays visible even when the camera is still.
     */
    private static final float FACE_Z_OFFSET = 0.5025f;
    /**
     * Layered face-local Z so coplanar quads do not z-fight when the camera moves.
     * Background panel sits closest to the block; gray header/cell decorations sit above it;
     * text is pushed further via {@link #TEXT_Z_OFFSET}.
     */
    private static final float PANEL_Z = 0.001f;
    private static final float DECOR_Z = 0.004f;
    /** Extra Z for text over the panel. */
    private static final float TEXT_Z_OFFSET = 0.012f;

    // Full-UV layout constants
    private static final float HEADER_Y1 = 0.028f;
    private static final float HEADER_Y2 = 0.088f;
    private static final float WEEKDAY_Y = 0.190f;
    private static final float GRID_TOP = 0.265f;
    private static final float GRID_BOTTOM = 0.970f;
    private static final float GRID_LEFT = 0.040f;
    private static final float GRID_RIGHT = 0.960f;

    private static final java.util.concurrent.atomic.AtomicBoolean REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private LoginCalendarWorldRender() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        LoginCalendarIndex.register();
        // LAST runs after all world geometry is drawn; we flush our own buffer here so
        // the panel is not left pending in a shared MultiBufferSource.
        WorldRenderEvents.LAST.register(LoginCalendarWorldRender::onRender);
    }

    private static void onRender(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null || ctx.matrixStack() == null) {
            return;
        }
        BlockPos playerPos = mc.player.blockPosition();
        PoseStack pose = ctx.matrixStack();
        Vec3 cam = ctx.camera().getPosition();

        if (LoginCalendarIndex.isEmpty()) {
            LoginCalendarIndex.maybeDiscoverNearby(level, cam, playerPos);
        }
        IN_RANGE.clear();
        LoginCalendarIndex.collectInRange(playerPos.getX(), playerPos.getY(), playerPos.getZ(), IN_RANGE);
        if (IN_RANGE.isEmpty()) {
            return;
        }

        int year;
        int month;
        int today;
        int streak;
        int reward;
        int mask;
        if (LotteryNetwork.ClientLoginState.hasData) {
            year = LotteryNetwork.ClientLoginState.year;
            month = LotteryNetwork.ClientLoginState.month;
            today = LotteryNetwork.ClientLoginState.dayOfMonth;
            streak = LotteryNetwork.ClientLoginState.streak;
            reward = LotteryNetwork.ClientLoginState.rewardToday;
            mask = LotteryNetwork.ClientLoginState.loginDaysMask;
        } else {
            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            year = now.getYear();
            month = now.getMonthValue();
            today = now.getDayOfMonth();
            streak = 0;
            reward = 0;
            mask = 0;
        }
        YearMonth ym = YearMonth.of(year, Math.max(1, Math.min(12, month)));
        int daysInMonth = ym.lengthOfMonth();
        // Monday = 0 .. Sunday = 6
        int firstDow = ym.atDay(1).getDayOfWeek().getValue() - 1;

        Font font = mc.font;
        MultiBufferSource.BufferSource textBuffers = mc.renderBuffers().bufferSource();
        refreshClockStrings(year, month, streak, reward);

        RenderSystem.enableDepthTest();
        RenderSystem.enablePolygonOffset();
        // Negative factor/units pulls the calendar toward the camera vs the block face.
        // Units bias helps more than factor when surfaces are nearly parallel to the view.
        RenderSystem.polygonOffset(-1.0f, -20.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        BufferBuilder buffer = null;
        boolean built = false;
        try {
            buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            boolean any = false;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int i = 0, n = IN_RANGE.size(); i < n; i++) {
                long packed = IN_RANGE.get(i);
                cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
                BlockState state = level.getBlockState(cursor);
                if (!(state.getBlock() instanceof LoginCalendarBlock)) {
                    LoginCalendarIndex.removePacked(packed);
                    continue;
                }
                any = true;
                renderOne(level, cursor.immutable(), state, pose, buffer, textBuffers, cam,
                        today, mask, daysInMonth, firstDow, font);
            }
            if (any) {
                BufferUploader.drawWithShader(buffer.buildOrThrow());
                built = true;
                textBuffers.endBatch();
            }
        } finally {
            if (buffer != null && !built) {
                try {
                    discardBegun(buffer);
                } catch (Throwable ignored) {
                    // already built or empty
                }
            }
            RenderSystem.disablePolygonOffset();
            RenderSystem.polygonOffset(0f, 0f);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private static void discardBegun(BufferBuilder buffer) {
        MeshData mesh = buffer.build();
        if (mesh != null) {
            mesh.close();
        }
    }

    /** Header clock uses UTC so it matches login settle / S2C day cells. */
    private static void refreshClockStrings(int year, int month, int streak, int reward) {
        long epochSec = System.currentTimeMillis() / 1000L;
        if (epochSec != clockEpochSec) {
            clockEpochSec = epochSec;
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            cachedTimeLine = now.format(TIME_FMT) + " UTC";
            cachedDateTimeLine = now.format(DATE_TIME_FMT) + " UTC";
        }
        if (year != cachedDateYear || month != cachedDateMonth) {
            cachedDateYear = year;
            cachedDateMonth = month;
            cachedDateLine = String.format("%04d-%02d", year, month);
        }
        if (streak != cachedStreak || reward != cachedReward) {
            cachedStreak = streak;
            cachedReward = reward;
            cachedStreakText = "连登" + streak + " 今日+" + reward;
        }
    }

    private static void renderOne(
            Level level, BlockPos pos, BlockState state,
            PoseStack pose, BufferBuilder buffer, MultiBufferSource textBuffers, Vec3 cam,
            int today, int mask, int daysInMonth, int firstDow, Font font
    ) {
        LoginCalendarGroup group = LoginCalendarGroup.resolve(level, pos, state);
        Direction facing = state.getValue(LoginCalendarBlock.FACING);

        pose.pushPose();
        pose.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        pose.translate(0.5, 0.5, 0.5);
        switch (facing) {
            case NORTH -> pose.mulPose(Axis.YP.rotationDegrees(180));
            case SOUTH -> {
            }
            case WEST -> pose.mulPose(Axis.YP.rotationDegrees(90));
            case EAST -> pose.mulPose(Axis.YP.rotationDegrees(-90));
            default -> {
            }
        }
        // Push whole panel slightly off the block face.
        pose.translate(0, 0, FACE_Z_OFFSET);

        float u0 = (float) group.localU / group.sizeU;
        float v0 = (float) group.localV / group.sizeV;
        float u1 = (float) (group.localU + 1) / group.sizeU;
        float v1 = (float) (group.localV + 1) / group.sizeV;

        Matrix4f m = pose.last().pose();

        // Full-tile dark panel. Use full face extent so motion doesn't expose coplanar
        // block-face edges that cause the gray decorations to flash.
        quad(buffer, m, -0.5f, -0.5f, 0.5f, 0.5f, 0xFF0A0A0A, PANEL_Z);

        // Header band background — raised above the dark panel to avoid z-fighting.
        fillUv(buffer, m, 0.02f, 0.015f, 0.98f, 0.155f, 0xFF161616, u0, v0, u1, v1, DECOR_Z);

        // Line 1: full date-time (left) — this is the "current time" display
        drawTextUv(pose, textBuffers, font, cachedDateTimeLine,
                0.05f, HEADER_Y1, 0.042f, false,
                group, u0, v0, u1, v1, 0xFFFFFF);
        // Line 2: month label left-ish, streak right
        drawTextUv(pose, textBuffers, font, cachedDateLine,
                0.05f, HEADER_Y2, 0.034f, false,
                group, u0, v0, u1, v1, 0xAAAAAA);
        drawTextUv(pose, textBuffers, font, cachedStreakText,
                0.95f, HEADER_Y2, 0.034f, true,
                group, u0, v0, u1, v1, 0xFFD700);
        // Also put big clock on the right of line 1 for multi-tile walls
        drawTextUv(pose, textBuffers, font, cachedTimeLine,
                0.95f, HEADER_Y1, 0.042f, true,
                group, u0, v0, u1, v1, 0x7CFF7C);

        // Weekday labels
        float cellW = (GRID_RIGHT - GRID_LEFT) / 7f;
        for (int col = 0; col < 7; col++) {
            float cx = GRID_LEFT + col * cellW + cellW * 0.5f;
            int color = (col == 5 || col == 6) ? 0xFF8888 : 0xCCCCCC; // weekend tint
            drawTextUv(pose, textBuffers, font, WEEKDAYS[col],
                    cx, WEEKDAY_Y, 0.030f, false,
                    group, u0, v0, u1, v1, color);
        }

        // Date grid
        float cellH = (GRID_BOTTOM - GRID_TOP) / 6f;
        for (int d = 1; d <= daysInMonth; d++) {
            int idx = firstDow + (d - 1);
            int col = idx % 7;
            int row = idx / 7;
            if (row >= 6) {
                continue;
            }
            float cx0 = GRID_LEFT + col * cellW;
            float cy0 = GRID_TOP + row * cellH;
            float cx1 = cx0 + cellW * 0.92f;
            float cy1 = cy0 + cellH * 0.88f;
            boolean logged = (mask & (1 << (d - 1))) != 0;
            boolean isToday = d == today;
            int bg = isToday ? 0xFF245C24 : (logged ? 0xFF1A3C66 : 0xFF202020);
            // Cell fills sit on DECOR_Z so they never coplanar-fight the dark panel.
            fillUv(buffer, m, cx0, cy0, cx1, cy1, bg, u0, v0, u1, v1, DECOR_Z);

            // Center the day number in the cell
            float cx = (cx0 + cx1) * 0.5f;
            float cy = cy0 + cellH * 0.18f;
            drawTextUv(pose, textBuffers, font, DAY_STRINGS[d],
                    cx, cy, 0.038f, false,
                    group, u0, v0, u1, v1,
                    isToday ? 0x90FF90 : 0xEEEEEE);
        }

        pose.popPose();
    }

    private static void quad(BufferBuilder buffer, Matrix4f m, float x0, float y0, float x1, float y1, int argb, float z) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        buffer.addVertex(m, x0, y0, z).setColor(r, g, b, a);
        buffer.addVertex(m, x1, y0, z).setColor(r, g, b, a);
        buffer.addVertex(m, x1, y1, z).setColor(r, g, b, a);
        buffer.addVertex(m, x0, y1, z).setColor(r, g, b, a);
    }

    /** Fill a rect in full-calendar UV, clipped to this tile. */
    private static void fillUv(
            BufferBuilder buffer, Matrix4f m,
            float cx0, float cy0, float cx1, float cy1, int argb,
            float u0, float v0, float u1, float v1, float z
    ) {
        float ix0 = Math.max(cx0, u0);
        float iy0 = Math.max(cy0, v0);
        float ix1 = Math.min(cx1, u1);
        float iy1 = Math.min(cy1, v1);
        if (ix0 >= ix1 || iy0 >= iy1) {
            return;
        }
        float lx0 = map(ix0, u0, u1, -0.5f, 0.5f);
        float lx1 = map(ix1, u0, u1, -0.5f, 0.5f);
        // v increases downward in layout; face Y increases upward
        float lyTop = map(iy0, v0, v1, 0.5f, -0.5f);
        float lyBot = map(iy1, v0, v1, 0.5f, -0.5f);
        quad(buffer, m, lx0, Math.min(lyTop, lyBot), lx1, Math.max(lyTop, lyBot), argb, z);
    }

    private static float map(float v, float a0, float a1, float b0, float b1) {
        float t = (v - a0) / Math.max(1e-6f, a1 - a0);
        return b0 + t * (b1 - b0);
    }

    /**
     * Draw text at full-calendar UV (cx, cy).
     * @param rightAlign if true, cx is the right edge of the text
     * @param fullUvHeight desired text height in full-calendar UV units (0..1)
     */
    private static void drawTextUv(
            PoseStack pose, MultiBufferSource buffers, Font font, String s,
            float cx, float cy, float fullUvHeight, boolean rightAlign,
            LoginCalendarGroup group, float u0, float v0, float u1, float v1,
            int color
    ) {
        // Skip if the text anchor is clearly outside this tile (with small margin)
        float margin = fullUvHeight;
        if (cx < u0 - margin || cx > u1 + margin || cy < v0 - margin || cy > v1 + margin) {
            return;
        }

        // Map full-UV → this tile's face-local coords
        float lx = map(cx, u0, u1, -0.5f, 0.5f);
        float ly = map(cy, v0, v1, 0.5f, -0.5f);

        // Convert desired full-UV height into face-local scale.
        float scaleX = (fullUvHeight * group.sizeU) / FONT_PX;
        float scaleY = (fullUvHeight * group.sizeV) / FONT_PX;
        if (scaleX <= 0f || scaleY <= 0f) {
            return;
        }

        pose.pushPose();
        pose.translate(lx, ly, TEXT_Z_OFFSET);
        // Flip Y only so text is upright; do NOT flip X (that was shifting Saturday leftward).
        pose.scale(scaleX, -scaleY, scaleX);

        float x = 0f;
        if (rightAlign) {
            x = -font.width(s);
        } else {
            // Center short labels (day numbers / weekday chars)
            if (s.length() <= 2) {
                x = -font.width(s) * 0.5f;
            }
        }
        font.drawInBatch(s, x, 0, color, false, pose.last().pose(), buffers,
                Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        pose.popPose();
    }
}
