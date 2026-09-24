package com.habitrain.lottery.skin;

import com.habitrain.lottery.api.skin.SkinQuality;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SkinNetworkTest {
    @Test void snapshotPreservesAllQualitiesAndOwnership() {
        var entries = Arrays.stream(SkinQuality.values())
                .map(q -> new SkinNetwork.Entry("knife", q.id(), q != SkinQuality.BLUE, q == SkinQuality.RED, q)).toList();
        var packet = new SkinNetwork.Snapshot(true, entries);
        var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            SkinNetwork.Snapshot.CODEC.encode(b, packet);
            assertEquals(packet, SkinNetwork.Snapshot.CODEC.decode(b));
            assertFalse(b.isReadable());
        } finally { b.release(); }
    }

    @Test void snapshotRejectsInvalidEntryCounts() {
        for (int count : new int[]{-1, SkinNetwork.MAX_ENTRIES + 1}) {
            var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                b.writeBoolean(false); b.writeVarInt(count);
                assertThrows(DecoderException.class, () -> SkinNetwork.Snapshot.CODEC.decode(b));
            } finally { b.release(); }
        }
    }
}
