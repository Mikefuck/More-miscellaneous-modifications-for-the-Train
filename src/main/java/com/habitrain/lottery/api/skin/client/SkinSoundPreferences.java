package com.habitrain.lottery.api.skin.client;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinSounds;
import com.habitrain.lottery.client.SkinClient;
import com.habitrain.lottery.skin.SkinNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client-thread access to the local shooter's server-confirmed sound preferences. */
@Environment(EnvType.CLIENT)
public final class SkinSoundPreferences {
    private SkinSoundPreferences() { }

    public static boolean isEnabled(String type, String id) {
        return HabiSkinApi.find(type, id).filter(skin -> SkinSounds.supports(skin.type(), skin.id()))
                .map(skin -> SkinClient.customSoundEnabled(skin.type() + "/" + skin.id())).orElse(false);
    }

    /** Sends a request. True means sent, not saved; wait for the authoritative sound snapshot. */
    public static boolean requestEnabled(String type, String id, boolean enabled) {
        var skin = HabiSkinApi.find(type, id);
        if (skin.isEmpty() || !SkinSounds.supports(type, id)
                || !ClientPlayNetworking.canSend(SkinNetwork.SoundRequest.TYPE)) return false;
        ClientPlayNetworking.send(new SkinNetwork.SoundRequest(skin.get().type(), skin.get().id(), enabled));
        return true;
    }
}
