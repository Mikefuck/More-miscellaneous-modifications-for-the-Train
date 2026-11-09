package com.habitrain.lottery.card;

import com.habitrain.lottery.PlayerStateGate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@code B-09}: unified multi-dimension fail-closed match gate
 * ({@link ServerMatchGate}) and spectator/rest-area/dead gate ({@link PlayerStateGate}).
 */
class ServerMatchGateFailClosedTest {

    @AfterEach
    void tearDown() {
        ServerMatchGate.clearTestOverride();
        PlayerStateGate.clearTestOverride();
    }

    @Test
    void rotationStartAndEveryLaterPhaseBlockFurtherCardActivation() {
        for (var phase : com.habitrain.core.api.match.MatchPhase.values()) {
            if (phase == com.habitrain.core.api.match.MatchPhase.INACTIVE) assertFalse(phase.hasLeftLobby());
            else assertTrue(phase.hasLeftLobby(), phase + " must refuse new activations");
        }
    }

    @Test
    void serverMatchGateHandlesNullServerOfflineAndHonorsOverrides() {
        assertFalse(ServerMatchGate.hasActiveMatch((MinecraftServer) null),
                "Offline/unit-test context without a live MinecraftServer should report no active match");
        assertFalse(ServerMatchGate.hasActiveMatch((ServerPlayer) null));

        ServerMatchGate.setTestOverride(true);
        assertTrue(ServerMatchGate.hasActiveMatch((MinecraftServer) null));
        assertTrue(ServerMatchGate.hasActiveMatch((ServerPlayer) null));

        ServerMatchGate.setTestOverride(false);
        assertFalse(ServerMatchGate.hasActiveMatch((MinecraftServer) null));
        assertFalse(ServerMatchGate.hasActiveMatch((ServerPlayer) null));
    }

    @Test
    void playerStateGateFailsClosedOnNullPlayerAndHonorsOverrides() {
        assertTrue(PlayerStateGate.spectatorRestOrDead(null),
                "Null player must fail closed (blocked = true)");

        PlayerStateGate.setTestOverride(false);
        assertFalse(PlayerStateGate.spectatorRestOrDead(null));

        PlayerStateGate.setTestOverride(true);
        assertTrue(PlayerStateGate.spectatorRestOrDead(null));
    }

    @Test
    void multiDimensionAndLookupFailurePredicatesRemainFailClosed() {
        // Resting player in overworld (false) while match dimension is active (true) MUST be blocked
        assertTrue(CardUseGates.anyLevelNotInactive(false, true, false));
        assertFalse(CardUseGates.anyLevelNotInactive(false, false, false));

        // Exception / lookup failure MUST fail closed (gameNotInactive == true)
        assertTrue(CardUseGates.gameNotInactive(true, false));
        assertTrue(CardUseGates.gameNotInactive(false, true));
        assertFalse(CardUseGates.gameNotInactive(false, false));

        assertTrue(CardUseGates.spectatorRestOrDead(true, false, false));
        assertTrue(CardUseGates.spectatorRestOrDead(false, true, false));
        assertTrue(CardUseGates.spectatorRestOrDead(false, false, true));
        assertFalse(CardUseGates.spectatorRestOrDead(false, false, false));
    }
}
