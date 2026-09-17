package com.habitrain.lottery.client.render;

import com.habitrain.lottery.block.LoginCalendarBlock;
import com.habitrain.lottery.block.LoginCalendarGroup;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client-only index of placed login-calendar tiles.
 * Filled from chunk load and client block place/remove; never cube-scans every frame.
 */
@Environment(EnvType.CLIENT)
public final class LoginCalendarIndex {
    /** Render / fallback radius in blocks (Euclidean). Spec max is 16. */
    public static final int RENDER_RANGE = 12;
    public static final int RENDER_RANGE_SQ = RENDER_RANGE * RENDER_RANGE;
    private static final long FALLBACK_INTERVAL_MS = 1000L;
    private static final double FALLBACK_MOVE_SQ = 4.0;

    private static final Set<Long> POSITIONS = new HashSet<>();
    private static final Map<Long, Set<Long>> BY_CHUNK = new HashMap<>();

    private static long lastFallbackMs;
    private static double lastCamX = Double.NaN;
    private static double lastCamY;
    private static double lastCamZ;

    private LoginCalendarIndex() {
    }

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register(LoginCalendarIndex::onChunkLoad);
        ClientChunkEvents.CHUNK_UNLOAD.register(LoginCalendarIndex::onChunkUnload);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> rescanLoadedChunks(client)));
    }

    public static void clear() {
        POSITIONS.clear();
        BY_CHUNK.clear();
        lastFallbackMs = 0L;
        lastCamX = Double.NaN;
        LoginCalendarGroup.invalidateCache();
    }

    public static boolean isEmpty() {
        return POSITIONS.isEmpty();
    }

    public static int size() {
        return POSITIONS.size();
    }

    public static void add(BlockPos pos) {
        addPacked(pos.asLong(), true);
    }

    public static void remove(BlockPos pos) {
        removePacked(pos.asLong());
    }

    public static void removePacked(long packed) {
        if (!POSITIONS.remove(packed)) {
            return;
        }
        long chunkKey = chunkKey(packed);
        Set<Long> inChunk = BY_CHUNK.get(chunkKey);
        if (inChunk != null) {
            inChunk.remove(packed);
            if (inChunk.isEmpty()) {
                BY_CHUNK.remove(chunkKey);
            }
        }
        LoginCalendarGroup.invalidateCache();
    }

    public static boolean isInRenderRange(int x, int y, int z, int ox, int oy, int oz) {
        long dx = (long) x - ox;
        long dy = (long) y - oy;
        long dz = (long) z - oz;
        return dx * dx + dy * dy + dz * dz <= (long) RENDER_RANGE_SQ;
    }

    public static boolean isInRenderRangePacked(long packed, int ox, int oy, int oz) {
        return isInRenderRange(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed), ox, oy, oz);
    }

    /**
     * Nearby indexed tiles only (player chunk ±1, then distance² filter).
     * RANGE 12 cannot reach beyond one chunk on each axis.
     */
    public static void collectInRange(int px, int py, int pz, Collection<Long> out) {
        int pcx = px >> 4;
        int pcz = pz >> 4;
        for (int cx = pcx - 1; cx <= pcx + 1; cx++) {
            for (int cz = pcz - 1; cz <= pcz + 1; cz++) {
                Set<Long> inChunk = BY_CHUNK.get(ChunkPos.asLong(cx, cz));
                if (inChunk == null) {
                    continue;
                }
                for (long packed : inChunk) {
                    if (isInRenderRangePacked(packed, px, py, pz)) {
                        out.add(packed);
                    }
                }
            }
        }
    }

    /**
     * If the index is empty, discover already-placed calendars around the player
     * at 1 Hz or when the camera moved more than 2 blocks. Never a per-frame cube.
     */
    public static void maybeDiscoverNearby(Level level, Vec3 cam, BlockPos playerPos) {
        if (!POSITIONS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean moved = Double.isNaN(lastCamX)
                || distSq(cam.x - lastCamX, cam.y - lastCamY, cam.z - lastCamZ) > FALLBACK_MOVE_SQ;
        if (!moved && now - lastFallbackMs < FALLBACK_INTERVAL_MS) {
            return;
        }
        lastFallbackMs = now;
        lastCamX = cam.x;
        lastCamY = cam.y;
        lastCamZ = cam.z;

        int px = playerPos.getX();
        int py = playerPos.getY();
        int pz = playerPos.getZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean changed = false;
        for (int dx = -RENDER_RANGE; dx <= RENDER_RANGE; dx++) {
            for (int dy = -RENDER_RANGE; dy <= RENDER_RANGE; dy++) {
                int ySq = dy * dy;
                if (dx * dx + ySq > RENDER_RANGE_SQ) {
                    continue;
                }
                for (int dz = -RENDER_RANGE; dz <= RENDER_RANGE; dz++) {
                    if (dx * dx + ySq + dz * dz > RENDER_RANGE_SQ) {
                        continue;
                    }
                    cursor.set(px + dx, py + dy, pz + dz);
                    if (level.getBlockState(cursor).getBlock() instanceof LoginCalendarBlock) {
                        if (addPacked(cursor.asLong(), false)) {
                            changed = true;
                        }
                    }
                }
            }
        }
        if (changed) {
            LoginCalendarGroup.invalidateCache();
        }
    }

    static void onChunkLoad(ClientLevel world, LevelChunk chunk) {
        scanChunk(chunk);
    }

    static void onChunkUnload(ClientLevel world, LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        Set<Long> inChunk = BY_CHUNK.remove(key);
        if (inChunk == null || inChunk.isEmpty()) {
            return;
        }
        POSITIONS.removeAll(inChunk);
        LoginCalendarGroup.invalidateCache();
    }

    private static void rescanLoadedChunks(Minecraft client) {
        clear();
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            return;
        }
        int view = Math.max(2, client.options.getEffectiveRenderDistance());
        ChunkPos center = client.player.chunkPosition();
        for (int cx = center.x - view; cx <= center.x + view; cx++) {
            for (int cz = center.z - view; cz <= center.z + view; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null || chunk instanceof EmptyLevelChunk) {
                    continue;
                }
                scanChunk(chunk);
            }
        }
    }

    private static void scanChunk(LevelChunk chunk) {
        if (chunk instanceof EmptyLevelChunk) {
            return;
        }
        ChunkPos cp = chunk.getPos();
        int originX = cp.getMinBlockX();
        int originZ = cp.getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = chunk.getMinSection();
        boolean changed = false;
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int originY = SectionPos.sectionToBlockCoord(minSection + si);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.getBlock() instanceof LoginCalendarBlock) {
                            long packed = BlockPos.asLong(originX + x, originY + y, originZ + z);
                            if (addPacked(packed, false)) {
                                changed = true;
                            }
                        }
                    }
                }
            }
        }
        if (changed) {
            LoginCalendarGroup.invalidateCache();
        }
    }

    private static boolean addPacked(long packed, boolean invalidate) {
        if (!POSITIONS.add(packed)) {
            if (invalidate) {
                LoginCalendarGroup.invalidateCache();
            }
            return false;
        }
        BY_CHUNK.computeIfAbsent(chunkKey(packed), k -> new HashSet<>()).add(packed);
        if (invalidate) {
            LoginCalendarGroup.invalidateCache();
        }
        return true;
    }

    private static long chunkKey(long packed) {
        return ChunkPos.asLong(BlockPos.getX(packed) >> 4, BlockPos.getZ(packed) >> 4);
    }

    private static double distSq(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz;
    }
}
