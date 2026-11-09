package com.habitrain.lottery.api.skin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** One registrar invocation, including replay, with an undo log for all contributed registries. */
public final class SkinRegistrationTransaction {
    private static final ThreadLocal<List<Runnable>> UNDO = new ThreadLocal<>();

    private SkinRegistrationTransaction() { }

    /** Record deletions too: unregister-then-register is the supported replacement operation. */
    static <K, V> V remove(Map<K, V> map, K key) {
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            V previous = map.remove(key);
            changed(map, key, previous, null);
            return previous;
        }
    }

    static <K, V> int removeIf(Map<K, V> map, Predicate<V> predicate) {
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            int removed = 0;
            for (var entry : new ArrayList<>(map.entrySet())) {
                if (predicate.test(entry.getValue()) && map.remove(entry.getKey(), entry.getValue())) {
                    changed(map, entry.getKey(), entry.getValue(), null);
                    removed++;
                }
            }
            return removed;
        }
    }

    static void recordUndo(Runnable undoAction) {
        List<Runnable> undo = UNDO.get();
        if (undo != null && undoAction != null) {
            undo.add(undoAction);
        }
    }

    static <K, V> void changed(Map<K, V> map, K key, V before, V after) {
        List<Runnable> undo = UNDO.get();
        if (undo == null || Objects.equals(before, after)) return;
        undo.add(() -> {
            synchronized (HabiSkinApi.REGISTRY_LOCK) {
                // Do not replace a later contribution made by a different registration.
                if (!Objects.equals(map.get(key), after)) return;
                if (before == null) map.remove(key); else map.put(key, before);
            }
        });
    }

    public static void run(String provider, Runnable registrar) {
        synchronized (HabiSkinApi.REGISTRY_LOCK) {
            String previousProvider = HabiSkinApi.beginProvider(provider);
            List<Runnable> previous = UNDO.get();
            List<Runnable> changes = new ArrayList<>();
            UNDO.set(changes);
            try {
                registrar.run();
                if (previous != null) previous.addAll(changes);
            } catch (RuntimeException | Error failure) {
                for (int i = changes.size() - 1; i >= 0; i--) {
                    try { changes.get(i).run(); }
                    catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
                }
                throw failure;
            } finally {
                if (previous == null) UNDO.remove(); else UNDO.set(previous);
                HabiSkinApi.endProvider(previousProvider);
            }
        }
    }
}
