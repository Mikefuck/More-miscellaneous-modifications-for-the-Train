package com.habitrain.lottery.network;

import com.habitrain.lottery.warehouse.WarehouseEntry;
import com.habitrain.lottery.api.skin.SkinQuality;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseNetworkTest {
    @Test void preservesSkinQualitiesAcrossMultipleRows() {
        var rows = java.util.Arrays.stream(SkinQuality.values()).map(q ->
                new WarehouseEntry("skin", "knife/" + q.id(), "皮肤", "说明", "minecraft:iron_sword",
                        1, 0xFF34E29C, q == SkinQuality.RED, q)).toList();
        var packet = new WarehouseNetwork.Snapshot(72, 0, rows.size(), "", rows);
        var b = buffer();
        try {
            WarehouseNetwork.Snapshot.CODEC.encode(b, packet);
            assertEquals(packet, WarehouseNetwork.Snapshot.CODEC.decode(b));
            assertFalse(b.isReadable());
        } finally { b.release(); }
    }

    private RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }
    @Test void preservesAccountMetadataAndFullIntegerBalances() {
        var row = new WarehouseEntry("special", "event:token", "测试道具", "奖励说明", "minecraft:diamond", Integer.MAX_VALUE, 0xFF83D564, false);
        var packet = new WarehouseNetwork.Snapshot(71, 0, 1, "", List.of(row));
        var b = buffer();
        try {
            WarehouseNetwork.Snapshot.CODEC.encode(b, packet);
            assertEquals(packet, WarehouseNetwork.Snapshot.CODEC.decode(b));
        } finally { b.release(); }
    }
    @Test void refusesNegativeOversizedAndOutOfRangeChunksBeforeAllocatingRows() {
        for (int[] values : new int[][]{{0,-1,0},{0,16385,0},{-1,1,1},{0,100,49},{9,10,2}}) {
            var b = buffer();
            try {
                b.writeVarInt(1); b.writeVarInt(values[0]); b.writeVarInt(values[1]); b.writeUtf(""); b.writeVarInt(values[2]);
                assertThrows(DecoderException.class, () -> WarehouseNetwork.Snapshot.CODEC.decode(b));
            } finally { b.release(); }
        }
    }
}
