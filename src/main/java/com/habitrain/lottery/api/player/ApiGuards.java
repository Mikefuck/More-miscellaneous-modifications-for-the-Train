package com.habitrain.lottery.api.player;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.minecraft.server.MinecraftServer;

/**
 * Shared server-thread, world-readiness and deferred-flush guards for public player and daily APIs.
 */
public final class ApiGuards {
    private static volatile Thread serverThreadOverrideForTest;

    private ApiGuards() {
    }

    /**
     * True when invoked on the active {@link MinecraftServer} thread, or when no server instance
     * is bound (such as headless unit tests).
     */
    public static boolean isOnServerThread() {
        Thread override = serverThreadOverrideForTest;
        if (override != null) {
            return Thread.currentThread() == override;
        }
        MinecraftServer server = HabiLotteryMod.getServer();
        return server == null || server.isSameThread();
    }

    /**
     * Validates that the caller is on the server thread, world paths are initialised, and
     * {@link PlayerLotteryStore} is not inside a deferred-flush window.
     *
     * @return {@code null} when ready for durable writes, or the failure reason otherwise
     */
    public static HabiFailure checkServerThreadAndReady() {
        if (!isOnServerThread()) {
            return HabiFailure.INVALID_OPERATION;
        }
        if (!WorldLotteryPaths.ready() || PlayerLotteryStore.get().isFlushDeferred()) {
            return HabiFailure.NOT_READY;
        }
        return null;
    }

    public static boolean isServerThreadAndReady() {
        return checkServerThreadAndReady() == null;
    }

    public static void setServerThreadForTest(Thread expectedServerThread) {
        serverThreadOverrideForTest = expectedServerThread;
    }
}
