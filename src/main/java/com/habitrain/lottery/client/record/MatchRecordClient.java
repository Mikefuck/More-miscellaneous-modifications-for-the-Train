package com.habitrain.lottery.client.record;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.network.ChunkedJsonTransfer;
import com.habitrain.lottery.network.MatchRecordNetwork;
import net.exmo.sre.record.MatchRecord;
import net.exmo.sre.record.client.ClientMatchRecordCache;
import net.exmo.sre.record.client.MatchRecordsScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class MatchRecordClient {
    private static final ChunkedJsonTransfer.Assembler<String> ASSEMBLER = MatchRecordNetwork.newAssembler();

    private MatchRecordClient() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MatchRecordNetwork.RecordChunk.TYPE, (part, context) ->
                context.client().execute(() -> accept(context.client(), part)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ASSEMBLER.reset();
            ClientMatchRecordCache.resetWindows();
        });
    }

    private static void accept(Minecraft client, MatchRecordNetwork.RecordChunk part) {
        var result = ASSEMBLER.accept(part.json(), part.transferId(), part.index(), part.count(), part.matchId());
        if (result == null && ASSEMBLER.hasActiveTransfer()) return;
        MatchRecord record = null;
        if (result != null && !result.json().isBlank()) {
            try {
                record = MatchRecord.fromJson(result.json());
            } catch (RuntimeException e) {
                HabiLotteryMod.LOGGER.warn("Could not read match record {}", part.matchId(), e);
            }
        }
        if (record == null || !part.matchId().equals(record.matchId)) {
            if (client.screen instanceof Status status) {
                status.habitrain$recordUnavailable(part.matchId());
            } else if (client.player != null) {
                client.player.displayClientMessage(Component.translatable("screen.sre.records.replay_missing"), false);
            }
            return;
        }
        if (client.screen instanceof Status status) {
            status.habitrain$acceptRecord(record);
        } else {
            ClientMatchRecordCache.resetWindows();
            client.setScreen(new MatchRecordsScreen(record));
        }
    }

    public interface Status {
        void habitrain$acceptRecord(MatchRecord record);
        void habitrain$recordUnavailable(String matchId);
    }
}
