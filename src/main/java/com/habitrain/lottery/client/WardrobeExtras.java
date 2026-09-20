package com.habitrain.lottery.client;

import io.wifi.starrailexpress.SREClientConfig;
import io.wifi.starrailexpress.client.SREClient;
import io.wifi.starrailexpress.network.UpdateNameTagSelectedPayload;
import net.exmo.sre.nametag.NameTagInventoryComponent;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.List;

/** Data/protocol adapter for the non-skin controls formerly hosted in the upstream screen. */
public final class WardrobeExtras {
    private WardrobeExtras() {}

    public static List<String> titles() {
        var player = Minecraft.getInstance().player;
        return player == null ? List.of() : List.copyOf(NameTagInventoryComponent.KEY.get(player).nameTags);
    }

    public static String equippedTitle() {
        var player = Minecraft.getInstance().player;
        String title = player == null ? "" : NameTagInventoryComponent.KEY.get(player).getCurrentNameTag();
        return title == null ? "" : title;
    }

    public static boolean canEquipTitle() { return ClientPlayNetworking.canSend(UpdateNameTagSelectedPayload.ID); }

    public static boolean matchesOpenKey(int key, int scan) {
        return SREClient.skinsKeybind != null && SREClient.skinsKeybind.matches(key, scan);
    }

    public static void equipTitle(String id) {
        ClientPlayNetworking.send(new UpdateNameTagSelectedPayload(id));
    }

    public static int hatMode() {
        var config = SREClientConfig.instance();
        return config.hideAllHats ? 2 : config.showOwnHatOnly ? 1 : 0;
    }

    public static void cycleHatMode() {
        int next = (hatMode() + 1) % 3;
        var config = SREClientConfig.instance();
        config.hideAllHats = next == 2;
        config.showOwnHatOnly = next == 1;
        SREClientConfig.HANDLER.save();
    }
}
