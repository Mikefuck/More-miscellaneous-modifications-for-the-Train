package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle-managed single-thread background executor for OP mail batch dispatch ({@code B-07}).
 *
 * <ul>
 *   <li>Single-flight guard ({@link #isBusy()}) prevents concurrent bulk-mail clicks from stacking
 *       multiple full-server disk storms.</li>
 *   <li>Lazily re-creates its worker after {@link #shutdownAndAwait()} so integrated servers
 *       switching worlds in the same JVM remain functional.</li>
 * </ul>
 */
public final class MailDispatchExecutor {
    private static final Object LOCK = new Object();
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static ExecutorService executor;
    private static boolean shuttingDown;

    private MailDispatchExecutor() {
    }

    /** Returns {@code true} when a mail dispatch batch is currently running. */
    public static boolean isBusy() {
        return IN_FLIGHT.get();
    }

    /**
     * Submits a single-flight mail dispatch batch.
     *
     * @return {@code true} if accepted for background execution; {@code false} if another batch
     *         is already in flight or the server is currently shutting down
     */
    public static boolean submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return false;
        }
        ExecutorService target;
        synchronized (LOCK) {
            if (shuttingDown) {
                if (executor != null && executor.isTerminated()) {
                    executor = null;
                    shuttingDown = false;
                } else {
                    IN_FLIGHT.set(false);
                    return false;
                }
            }
            if (executor == null || executor.isShutdown()) {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "habitrain-lottery-mail-dispatch");
                    t.setDaemon(true);
                    return t;
                });
            }
            target = executor;
        }
        try {
            target.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    HabiLotteryMod.LOGGER.error("Background mail dispatch task failed", t);
                } finally {
                    IN_FLIGHT.set(false);
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            IN_FLIGHT.set(false);
            return false;
        }
    }

    /**
     * Drains any in-flight mail dispatch batch during {@code SERVER_STOPPING} before world paths
     * are cleared. Safe to call repeatedly.
     */
    public static void shutdownAndAwait() {
        shutdownAndAwait(10L, TimeUnit.SECONDS);
    }

    /** Package-visible / testable overload with configurable timeout. */
    public static boolean shutdownAndAwait(long timeout, TimeUnit unit) {
        ExecutorService current;
        synchronized (LOCK) {
            shuttingDown = true;
            current = executor;
        }
        boolean clean = true;
        try {
            if (current != null) {
                current.shutdown();
                try {
                    if (!current.awaitTermination(Math.max(1L, timeout), unit)) {
                        clean = false;
                        HabiLotteryMod.LOGGER.warn(
                                "MailDispatchExecutor did not finish within {} {}; forcing shutdownNow",
                                timeout, unit);
                        current.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    clean = false;
                    current.shutdownNow();
                }
            }
        } finally {
            synchronized (LOCK) {
                // shutdownNow only interrupts; an in-flight filesystem operation may
                // still be running. Keep ownership until that worker really exits.
                if (current == null || current.isTerminated()) {
                    if (executor == current) executor = null;
                    shuttingDown = false;
                    IN_FLIGHT.set(false);
                }
            }
        }
        return clean;
    }
}
