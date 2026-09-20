package com.habitrain.lottery.mail;

import com.habitrain.lottery.HabiLotteryMod;

import java.util.function.BooleanSupplier;

/**
 * Claim lock remains durable until either all rewards or all compensations succeed.
 *
 * <p><b>Audit B-14.</b> {@link #run} used to catch only {@link RuntimeException}. An
 * {@link Error} (or a mid-transaction kill) therefore escaped <em>before</em> the rollback,
 * which could leave the mailbox durably marked "claimed" while no reward had ever been
 * delivered — with no path to recovery. Now every {@link Throwable} is compensated first:
 * {@code rollback} (restore every reward store to its pre-claim snapshot) and, when the
 * restore succeeded, {@code reopen} (persist the mail as claimable again) both run before
 * the result is reported. An {@link Error} is re-thrown <b>after</b> that compensation, so
 * fatal-error semantics are preserved while the on-disk state stays consistent.
 *
 * <p>A hard kill cannot run any of this; that window is covered by the durable in-flight
 * claim marker persisted by {@link MailService} and reconciled on the next mail load.
 */
final class MailClaimTransaction {
    enum Result { SUCCESS, SAVE_FAILED, ROLLED_BACK, RECOVERY_REQUIRED }

    private MailClaimTransaction() {}

    /**
     * Persist the claim lock, grant the rewards, and compensate on any failure.
     *
     * @param saveClaim durable pre-grant lock write (must return {@code false} on failure)
     * @param apply     the reward grant
     * @param rollback  restores every reward store to its pre-claim snapshot
     * @param reopen    clears the claim lock again; only called once {@code rollback} succeeded
     * @return the outcome; {@link Error}s are re-thrown after compensation has been attempted
     */
    static Result run(BooleanSupplier saveClaim, Runnable apply,
                      BooleanSupplier rollback, BooleanSupplier reopen) {
        try {
            if (!saveClaim.getAsBoolean()) {
                return Result.SAVE_FAILED;
            }
        } catch (Throwable lockFailure) {
            // Nothing durable was written yet, so there is nothing to compensate: the
            // mailbox is still in its pre-claim state.
            HabiLotteryMod.LOGGER.error("Mail claim lock could not be persisted", lockFailure);
            if (lockFailure instanceof Error error) {
                throw error;
            }
            return Result.SAVE_FAILED;
        }
        try {
            apply.run();
            return Result.SUCCESS;
        } catch (Throwable failure) {
            Result result = compensate(rollback, reopen);
            if (failure instanceof Error error) {
                throw error;
            }
            return result;
        }
    }

    /** Roll back, then reopen only when every reward store was restored. Never throws. */
    private static Result compensate(BooleanSupplier rollback, BooleanSupplier reopen) {
        boolean restored;
        try {
            restored = rollback.getAsBoolean();
        } catch (Throwable rollbackFailure) {
            HabiLotteryMod.LOGGER.error("Mail reward rollback failed", rollbackFailure);
            restored = false;
        }
        if (!restored) {
            return Result.RECOVERY_REQUIRED;
        }
        try {
            return reopen.getAsBoolean() ? Result.ROLLED_BACK : Result.RECOVERY_REQUIRED;
        } catch (Throwable reopenFailure) {
            HabiLotteryMod.LOGGER.error("Mail claim reopen failed", reopenFailure);
            return Result.RECOVERY_REQUIRED;
        }
    }

    /**
     * One failed asset store must not stop compensation of the other assets.
     *
     * <p>Catches {@link Throwable} (audit B-14), including {@link Error}s: compensation is
     * the last chance to keep the stores and the mailbox consistent, so an error inside one
     * step is recorded and the remaining steps still run. The caller re-throws the original
     * grant failure afterwards, so fatal errors are not lost.
     */
    static boolean restoreAll(Runnable... steps) {
        boolean success = true;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (Throwable failure) {
                HabiLotteryMod.LOGGER.error("Mail compensation failed", failure);
                success = false;
            }
        }
        return success;
    }
}
