package com.habitrain.lottery.client;

import com.habitrain.lottery.client.gui.SelfSelectRoleConfigScreen;
import com.habitrain.lottery.network.ChunkedJsonTransfer;
import com.habitrain.lottery.network.SelfSelectAdminNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.concurrent.atomic.AtomicBoolean;

@Environment(EnvType.CLIENT)
public final class SelfSelectAdminClient {
    private record ConfigMeta(String requestId, String message) {
    }

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static final ChunkedJsonTransfer.Assembler<ConfigMeta> CONFIG_ASSEMBLER =
            ChunkedJsonTransfer.newClientS2CAssembler();

    private SelfSelectAdminClient() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            ClientPlayNetworking.registerGlobalReceiver(SelfSelectAdminNetwork.ConfigS2C.TYPE, (p, ctx) ->
                    ctx.client().execute(() -> {
                        var complete = CONFIG_ASSEMBLER.accept(
                                p.json(),
                                p.transferId(),
                                p.chunkIndex(),
                                p.chunkCount(),
                                new ConfigMeta(
                                        p.requestId() == null ? "" : p.requestId(),
                                        p.message() == null ? "" : p.message()));
                        if (complete == null) return;
                        if (ctx.client().screen instanceof SelfSelectRoleConfigScreen screen) {
                            screen.receive(new SelfSelectAdminNetwork.ConfigS2C(
                                    complete.metadata().requestId(),
                                    complete.json(),
                                    complete.metadata().message()));
                        }
                    }));
        } catch (Throwable t) {
            REGISTERED.set(false);
        }
    }

    public static boolean connected() {
        try {
            return ClientPlayNetworking.canSend(SelfSelectAdminNetwork.RequestC2S.TYPE)
                    && ClientPlayNetworking.canSend(SelfSelectAdminNetwork.SaveC2S.TYPE);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void request(String requestId) {
        ClientPlayNetworking.send(new SelfSelectAdminNetwork.RequestC2S(requestId));
    }

    public static void save(String requestId, String json) {
        for (SelfSelectAdminNetwork.SaveC2S chunk : new SelfSelectAdminNetwork.SaveC2S(requestId, json).toWireChunks()) {
            ClientPlayNetworking.send(chunk);
        }
    }

    /** Clears transient client-side self-select admin editor state on world join/disconnect. */
    public static void reset() {
        CONFIG_ASSEMBLER.reset();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.screen instanceof SelfSelectRoleConfigScreen screen) {
                screen.resetSessionState();
            }
        } catch (Throwable ignored) {
            // Safe when called from unit tests without an initialized Minecraft client.
        }
    }
}
