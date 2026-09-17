package com.habitrain.lottery.backpack;

import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static com.habitrain.lottery.backpack.PlayerCardAdminModels.CardOperation.SET;
import static com.habitrain.lottery.backpack.PlayerCardAdminModels.CardStoreStatus.ONLINE_LIVE;
import static org.junit.jupiter.api.Assertions.*;

class PlayerCardAdminServiceTest {
    @Test
    void onlineSetUsesDeltaPersistsAndResends() {
        FakeOnlineCardAccess live = new FakeOnlineCardAccess(2, true);

        var result = PlayerCardAdminService.mutateOnline(
                live, FactionCardType.KILLER, SET, 5);

        assertTrue(result.ok());
        assertEquals(ONLINE_LIVE, result.status());
        assertEquals(5, result.newCount());
        assertEquals(3, live.lastDelta);
        assertEquals(5, live.count(FactionCardType.KILLER));
        assertEquals(1, live.persistCalls);
        assertTrue(live.resent);
    }

    @Test
    void persistenceFailureRollsBackLiveCountAndDoesNotResendSuccess() {
        FakeOnlineCardAccess live = new FakeOnlineCardAccess(2, false);

        var result = PlayerCardAdminService.mutateOnline(
                live, FactionCardType.KILLER, SET, 5);

        assertFalse(result.ok());
        assertEquals(2, live.count(FactionCardType.KILLER));
        assertEquals(0, live.lastDelta);
        assertFalse(live.resent);
    }

    @Test
    void corruptOnlineStorageIsRejectedBeforeLiveMemoryChanges() {
        FakeOnlineCardAccess live = new FakeOnlineCardAccess(2, true);
        live.storageCorrupt = true;

        var result = PlayerCardAdminService.mutateOnline(
                live, FactionCardType.KILLER, SET, 5);

        assertFalse(result.ok());
        assertEquals(2, live.count(FactionCardType.KILLER));
        assertEquals(0, live.persistCalls);
        assertFalse(live.resent);
    }

    private static final class FakeOnlineCardAccess implements PlayerCardAdminService.OnlineCardAccess {
        private final Map<FactionCardType, Integer> cards = new EnumMap<>(FactionCardType.class);
        private final boolean persistResult;
        private int lastDelta;
        private int persistCalls;
        private boolean resent;
        private boolean storageCorrupt;

        private FakeOnlineCardAccess(int killerCount, boolean persistResult) {
            cards.put(FactionCardType.KILLER, killerCount);
            this.persistResult = persistResult;
        }

        @Override
        public int count(FactionCardType type) {
            return cards.getOrDefault(type, 0);
        }

        @Override
        public void add(FactionCardType type, int delta) {
            lastDelta += delta;
            cards.put(type, Math.max(0, count(type) + delta));
        }

        @Override
        public boolean persist() {
            persistCalls++;
            return persistResult;
        }

        @Override
        public void resend() {
            resent = true;
        }

        @Override
        public Map<FactionCardType, Integer> cards() {
            return new EnumMap<>(cards);
        }

        @Override
        public boolean storageCorrupt() {
            return storageCorrupt;
        }
    }
}
