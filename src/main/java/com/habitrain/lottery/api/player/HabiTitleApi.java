package com.habitrain.lottery.api.player;

import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.title.LocalTitleStore;
import com.habitrain.lottery.title.PlayerTitleData;
import com.habitrain.lottery.title.TitleService;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Public API for one player's owned titles (称号) and the equipped title.
 *
 * <p>Titles are stored per player under {@code titles/players/<uuid>.json} and
 * mirrored into SRE's {@code NameTagInventoryComponent}. Online mutations use
 * the component path (which also pushes tab-list and display-name updates);
 * offline mutations write the JSON directly.</p>
 *
 * <p>Server thread only.</p>
 */
public final class HabiTitleApi {
    private HabiTitleApi() {
    }

    /** Owned title display strings in storage order; empty on missing/corrupt data. */
    public static List<String> owned(UUID uuid) {
        if (uuid == null) {
            return List.of();
        }
        LocalTitleStore.PlayerLoad load = LocalTitleStore.get().loadPlayerDetailed(uuid);
        if (load.corrupt() || load.data() == null) {
            return List.of();
        }
        PlayerTitleData data = load.data();
        if (data.owned == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String title : data.owned) {
            if (title != null && !title.isBlank()) {
                out.add(title);
            }
        }
        return List.copyOf(out);
    }

    /** Currently equipped title, or {@code ""} when none. */
    public static String current(UUID uuid) {
        if (uuid == null) {
            return "";
        }
        LocalTitleStore.PlayerLoad load = LocalTitleStore.get().loadPlayerDetailed(uuid);
        if (load.corrupt() || load.data() == null || load.data().current == null) {
            return "";
        }
        return load.data().current;
    }

    /** Grants a title. Idempotent: an already-owned title returns {@code ok}. */
    public static HabiAssetResult grant(UUID uuid, String display) {
        if (uuid == null) {
            return HabiAssetResult.fail(HabiFailure.NOT_FOUND);
        }
        if (!WorldLotteryPaths.ready()) {
            return HabiAssetResult.fail(HabiFailure.NOT_READY);
        }
        if (display == null || display.isBlank()) {
            return HabiAssetResult.fail(HabiFailure.INVALID_VALUE);
        }
        LocalTitleStore.PlayerLoad load = LocalTitleStore.get().loadPlayerDetailed(uuid);
        if (load.corrupt()) {
            return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        }
        if (load.data() != null && load.data().owned != null && load.data().owned.contains(display)) {
            return HabiAssetResult.success();
        }
        ServerPlayer online = HabiLotteryApi.online(uuid);
        boolean granted = online != null
                ? TitleService.grantOnline(online, display)
                : TitleService.grantOffline(uuid, display);
        return granted ? HabiAssetResult.success() : HabiAssetResult.fail(HabiFailure.WRITE_FAILED);
    }

    public static HabiAssetResult grant(ServerPlayer player, String display) {
        return grant(player == null ? null : player.getUUID(), display);
    }

    /** Revokes a title. Idempotent: a missing title returns {@code ok}. */
    public static HabiAssetResult revoke(UUID uuid, String display) {
        if (uuid == null) {
            return HabiAssetResult.fail(HabiFailure.NOT_FOUND);
        }
        if (!WorldLotteryPaths.ready()) {
            return HabiAssetResult.fail(HabiFailure.NOT_READY);
        }
        if (display == null || display.isBlank()) {
            return HabiAssetResult.fail(HabiFailure.INVALID_VALUE);
        }
        LocalTitleStore.PlayerLoad load = LocalTitleStore.get().loadPlayerDetailed(uuid);
        if (load.corrupt()) {
            return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        }
        if (load.data() == null || load.data().owned == null || !load.data().owned.contains(display)) {
            return HabiAssetResult.success();
        }
        ServerPlayer online = HabiLotteryApi.online(uuid);
        boolean revoked = online != null
                ? TitleService.revokeOnline(online, display)
                : TitleService.revokeOffline(uuid, display);
        return revoked ? HabiAssetResult.success() : HabiAssetResult.fail(HabiFailure.WRITE_FAILED);
    }

    public static HabiAssetResult revoke(ServerPlayer player, String display) {
        return revoke(player == null ? null : player.getUUID(), display);
    }

    /**
     * Equips an owned title; a {@code null}/blank display clears the current
     * title. Returns {@link HabiFailure#TITLE_NOT_OWNED} when the player does
     * not own it.
     */
    public static HabiAssetResult setCurrent(UUID uuid, String display) {
        if (uuid == null) {
            return HabiAssetResult.fail(HabiFailure.NOT_FOUND);
        }
        if (!WorldLotteryPaths.ready()) {
            return HabiAssetResult.fail(HabiFailure.NOT_READY);
        }
        String wanted = display == null ? "" : display;
        if (!wanted.isBlank()) {
            LocalTitleStore.PlayerLoad load = LocalTitleStore.get().loadPlayerDetailed(uuid);
            if (load.corrupt()) {
                return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
            }
            if (load.data() == null || load.data().owned == null || !load.data().owned.contains(wanted)) {
                return HabiAssetResult.fail(HabiFailure.TITLE_NOT_OWNED);
            }
        }
        ServerPlayer online = HabiLotteryApi.online(uuid);
        boolean ok = online != null
                ? TitleService.setCurrentOnline(online, wanted)
                : TitleService.setCurrentOffline(uuid, wanted);
        return ok ? HabiAssetResult.success() : HabiAssetResult.fail(HabiFailure.WRITE_FAILED);
    }

    public static HabiAssetResult setCurrent(ServerPlayer player, String display) {
        return setCurrent(player == null ? null : player.getUUID(), display);
    }

    /** Removes every owned title and clears the current one. */
    public static HabiAssetResult clear(UUID uuid) {
        if (uuid == null) {
            return HabiAssetResult.fail(HabiFailure.NOT_FOUND);
        }
        if (!WorldLotteryPaths.ready()) {
            return HabiAssetResult.fail(HabiFailure.NOT_READY);
        }
        LocalTitleStore.PlayerLoad load = LocalTitleStore.get().loadPlayerDetailed(uuid);
        if (load.corrupt()) {
            return HabiAssetResult.fail(HabiFailure.CORRUPT_STORAGE);
        }
        ServerPlayer online = HabiLotteryApi.online(uuid);
        if (online != null) {
            TitleService.clearOnline(online);
        } else {
            TitleService.clearOffline(uuid);
        }
        return HabiAssetResult.success();
    }

    public static HabiAssetResult clear(ServerPlayer player) {
        return clear(player == null ? null : player.getUUID());
    }
}
