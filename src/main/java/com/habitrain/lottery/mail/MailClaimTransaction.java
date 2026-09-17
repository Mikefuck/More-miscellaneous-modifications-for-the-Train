package com.habitrain.lottery.mail;

import java.util.function.BooleanSupplier;

/** Claim lock remains durable until either all rewards or all compensations succeed. */
final class MailClaimTransaction {
    enum Result { SUCCESS, SAVE_FAILED, ROLLED_BACK, RECOVERY_REQUIRED }

    private MailClaimTransaction() {}

    static Result run(BooleanSupplier saveClaim, Runnable apply,
                      BooleanSupplier rollback, BooleanSupplier reopen) {
        if (!saveClaim.getAsBoolean()) return Result.SAVE_FAILED;
        try {
            apply.run();
            return Result.SUCCESS;
        } catch (RuntimeException failure) {
            if (!rollback.getAsBoolean()) return Result.RECOVERY_REQUIRED;
            return reopen.getAsBoolean() ? Result.ROLLED_BACK : Result.RECOVERY_REQUIRED;
        }
    }

    /** One failed asset store must not stop compensation of the other assets. */
    static boolean restoreAll(Runnable... steps) {
        boolean success = true;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException failure) {
                com.habitrain.lottery.HabiLotteryMod.LOGGER.error("Mail compensation failed", failure);
                success = false;
            }
        }
        return success;
    }
}
