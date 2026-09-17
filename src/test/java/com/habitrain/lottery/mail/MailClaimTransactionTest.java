package com.habitrain.lottery.mail;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MailClaimTransactionTest {
    @Test void failedClaimSaveNeverGrantsRewards() {
        assertEquals(MailClaimTransaction.Result.SAVE_FAILED,
                MailClaimTransaction.run(() -> false, () -> fail("reward issued"),
                        () -> { fail("rollback called"); return false; }, () -> false));
    }

    @Test void failedSelfSelectRollbackStillRestoresOtherAssetsAndKeepsClaimLocked() {
        List<String> restored = new ArrayList<>();
        boolean[] locked = {false};
        var result = MailClaimTransaction.run(() -> { locked[0] = true; return true; },
                () -> { throw new IllegalStateException("mixed attachment write failed"); },
                () -> MailClaimTransaction.restoreAll(
                        () -> { throw new IllegalStateException("self-select storage failed"); },
                        () -> restored.add("coins and draws"),
                        () -> restored.add("faction cards")),
                () -> { locked[0] = false; return true; });
        assertEquals(List.of("coins and draws", "faction cards"), restored);
        assertTrue(locked[0]);
        assertEquals(MailClaimTransaction.Result.RECOVERY_REQUIRED, result);
    }

    @Test void successfulRollbackReopensClaimOnlyAfterAllAssetsAreRestored() {
        List<String> order = new ArrayList<>();
        var result = MailClaimTransaction.run(() -> true,
                () -> { throw new IllegalStateException("write failed"); },
                () -> MailClaimTransaction.restoreAll(
                        () -> order.add("self"), () -> order.add("economy"), () -> order.add("faction")),
                () -> { order.add("reopen"); return true; });
        assertEquals(List.of("self", "economy", "faction", "reopen"), order);
        assertEquals(MailClaimTransaction.Result.ROLLED_BACK, result);
    }

    @Test void failedClaimReopenRequiresRecoveryAndSuccessfulGrantNeverRollsBack() {
        assertEquals(MailClaimTransaction.Result.RECOVERY_REQUIRED,
                MailClaimTransaction.run(() -> true, () -> { throw new IllegalStateException(); },
                        () -> true, () -> false));
        assertEquals(MailClaimTransaction.Result.SUCCESS,
                MailClaimTransaction.run(() -> true, () -> {},
                        () -> { fail("unexpected rollback"); return false; }, () -> false));
    }
}
