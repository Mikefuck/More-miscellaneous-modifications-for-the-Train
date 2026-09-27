package com.habitrain.lottery.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CrateNetworkTest {
    @Test void resultRetainsRequestIdAndCompleteRewards() {
        var packet = new CrateNetwork.OpenResultS2C("00000000-0000-0000-0000-000000000001",
                true, "festival_2026", "habitrain_lottery:key_festival_2026", "knife", "emerald_claw",
                "gold", "crates.opened", 20, 100,
                "[{\"kind\":\"skin\",\"id\":\"knife/emerald_claw\",\"amount\":1},"
                        + "{\"kind\":\"green_apples\",\"id\":\"green_apples\",\"amount\":80}]", 42);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            CrateNetwork.OpenResultS2C.CODEC.encode(buffer, packet);
            assertEquals(packet, CrateNetwork.OpenResultS2C.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
