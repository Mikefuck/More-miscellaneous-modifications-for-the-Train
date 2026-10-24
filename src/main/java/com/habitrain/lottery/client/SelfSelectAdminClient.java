package com.habitrain.lottery.client;

import com.habitrain.lottery.client.gui.SelfSelectRoleConfigScreen;
import com.habitrain.lottery.network.SelfSelectAdminNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

@Environment(EnvType.CLIENT)
public final class SelfSelectAdminClient {
    private SelfSelectAdminClient() { }
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(SelfSelectAdminNetwork.ConfigS2C.TYPE, (p, ctx) ->
                ctx.client().execute(() -> {
                    if (ctx.client().screen instanceof SelfSelectRoleConfigScreen screen) screen.receive(p);
                }));
    }
    public static boolean connected() {
        return ClientPlayNetworking.canSend(SelfSelectAdminNetwork.RequestC2S.TYPE)
                && ClientPlayNetworking.canSend(SelfSelectAdminNetwork.SaveC2S.TYPE);
    }
    public static void request(String requestId) {
        ClientPlayNetworking.send(new SelfSelectAdminNetwork.RequestC2S(requestId));
    }
    public static void save(String requestId, String json) {
        ClientPlayNetworking.send(new SelfSelectAdminNetwork.SaveC2S(requestId, json));
    }
}
