package com.habitrain.lottery.network;

import com.habitrain.lottery.mixin.StoredUserEntryAccessor;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure local-memory lookup for player {@link UUID}s and names without ever invoking
 * {@code GameProfileCache#get(String)} (which performs blocking Mojang Yggdrasil HTTPS
 * requests on cache misses and can stall the server tick thread into a Watchdog crash).
 *
 * <p>Uses type-based reflection ({@link Map} fields + {@link GameProfile}-returning
 * accessors) cached per class so it works identically under both Mojang dev mappings
 * and production Intermediary mappings.
 */
public final class ProfileCacheLookup {
    private static final Map<Class<?>, List<Field>> CACHED_MAP_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<ProfileExtractor>> CACHED_EXTRACTORS = new ConcurrentHashMap<>();

    private ProfileCacheLookup() {
    }

    @FunctionalInterface
    private interface ProfileExtractor {
        GameProfile extract(Object entry) throws ReflectiveOperationException;
    }

    /**
     * Resolves a player's {@link UUID} by name using only in-memory server state:
     * online player list, whitelist entries, and {@code GameProfileCache}'s in-memory map.
     * Never performs network or disk I/O.
     */
    public static Optional<UUID> lookupCachedUuid(MinecraftServer server, String rawName) {
        if (server == null || rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String name = rawName.trim();
        try {
            var playerList = server.getPlayerList();
            if (playerList != null) {
                ServerPlayer online = playerList.getPlayerByName(name);
                if (online != null && online.getUUID() != null) {
                    return Optional.of(online.getUUID());
                }
                var whiteList = playerList.getWhiteList();
                if (whiteList != null) {
                    for (var entry : whiteList.getEntries()) {
                        if (entry instanceof StoredUserEntryAccessor accessor
                                && accessor.habitrain$getUser() instanceof GameProfile profile
                                && profile.getId() != null
                                && profile.getName() != null
                                && profile.getName().equalsIgnoreCase(name)) {
                            return Optional.of(profile.getId());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return lookupCachedUuidFromCache(server.getProfileCache(), name);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    /**
     * Resolves a player's display name by {@link UUID} using only in-memory server state:
     * online player list, whitelist entries, and {@code GameProfileCache}'s in-memory map.
     * Never performs network or disk I/O.
     */
    public static Optional<String> lookupCachedName(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return Optional.empty();
        }
        try {
            var playerList = server.getPlayerList();
            if (playerList != null) {
                ServerPlayer online = playerList.getPlayer(uuid);
                if (online != null) {
                    String name = online.getGameProfile().getName();
                    if (name != null && !name.isBlank()) {
                        return Optional.of(name);
                    }
                }
                var whiteList = playerList.getWhiteList();
                if (whiteList != null) {
                    for (var entry : whiteList.getEntries()) {
                        if (entry instanceof StoredUserEntryAccessor accessor
                                && accessor.habitrain$getUser() instanceof GameProfile profile
                                && uuid.equals(profile.getId())
                                && profile.getName() != null
                                && !profile.getName().isBlank()) {
                            return Optional.of(profile.getName());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return lookupCachedNameFromCache(server.getProfileCache(), uuid);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    /**
     * Package-visible & unit-testable helper: inspects {@code profileCache}'s in-memory {@link Map}
     * fields without calling any {@code get(String)} method on {@code profileCache}.
     */
    public static Optional<UUID> lookupCachedUuidFromCache(Object profileCache, String rawName) {
        if (profileCache == null || rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawName.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (Field field : mapFieldsOf(profileCache.getClass())) {
            try {
                Object mapObj = field.get(profileCache);
                if (!(mapObj instanceof Map<?, ?> map) || map.isEmpty()) {
                    continue;
                }
                GameProfile found = getProfileByKey(map, lower);
                if (found == null && !lower.equals(trimmed)) {
                    found = getProfileByKey(map, trimmed);
                }
                if (found != null && found.getId() != null
                        && (found.getName() == null || found.getName().equalsIgnoreCase(trimmed))) {
                    return Optional.of(found.getId());
                }
            } catch (Throwable ignored) {
            }
        }
        return Optional.empty();
    }

    /**
     * Package-visible & unit-testable helper: inspects {@code profileCache}'s in-memory {@link Map}
     * fields by {@link UUID} key without calling {@code GameProfileCache#get(UUID)}.
     */
    public static Optional<String> lookupCachedNameFromCache(Object profileCache, UUID uuid) {
        if (profileCache == null || uuid == null) {
            return Optional.empty();
        }
        for (Field field : mapFieldsOf(profileCache.getClass())) {
            try {
                Object mapObj = field.get(profileCache);
                if (!(mapObj instanceof Map<?, ?> map) || map.isEmpty()) {
                    continue;
                }
                GameProfile found = getProfileByKey(map, uuid);
                if (found != null && uuid.equals(found.getId())
                        && found.getName() != null && !found.getName().isBlank()) {
                    return Optional.of(found.getName());
                }
            } catch (Throwable ignored) {
            }
        }
        return Optional.empty();
    }

    private static GameProfile getProfileByKey(Map<?, ?> map, Object key) {
        Object value;
        try {
            value = map.get(key);
        } catch (RuntimeException ignored) {
            // e.g. TreeMap key ClassCastException or concurrent view edge case
            return null;
        }
        return extractProfile(value);
    }

    private static GameProfile extractProfile(Object entry) {
        if (entry == null) {
            return null;
        }
        if (entry instanceof GameProfile gp) {
            return gp;
        }
        Optional<ProfileExtractor> extractor = CACHED_EXTRACTORS.computeIfAbsent(
                entry.getClass(), ProfileCacheLookup::discoverExtractor);
        if (extractor.isEmpty()) {
            return null;
        }
        try {
            return extractor.get().extract(entry);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<Field> mapFieldsOf(Class<?> type) {
        return CACHED_MAP_FIELDS.computeIfAbsent(type, ProfileCacheLookup::discoverMapFields);
    }

    private static List<Field> discoverMapFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> cur = type; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Field f : cur.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (Map.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        fields.add(f);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        return Collections.unmodifiableList(fields);
    }

    private static Optional<ProfileExtractor> discoverExtractor(Class<?> entryType) {
        for (Class<?> cur = entryType; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
                    continue;
                }
                if (GameProfile.class.isAssignableFrom(m.getReturnType())) {
                    try {
                        m.setAccessible(true);
                        return Optional.of(obj -> (GameProfile) m.invoke(obj));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            for (Field f : cur.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (GameProfile.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return Optional.of(obj -> (GameProfile) f.get(obj));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        return Optional.empty();
    }
}
