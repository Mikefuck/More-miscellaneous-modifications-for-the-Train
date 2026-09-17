package com.habitrain.lottery.api.player;

/**
 * Outcome of one player-asset mutation.
 *
 * @param ok       whether the change was durably persisted
 * @param failure  failure reason, {@link HabiFailure#NONE} when {@code ok}
 * @param newValue post-mutation value for numeric assets; {@code 0} for
 *                 operations without a numeric result (and the previous value
 *                 on some failures)
 */
public record HabiAssetResult(boolean ok, HabiFailure failure, int newValue) {
    public HabiAssetResult {
        failure = failure == null ? HabiFailure.NONE : failure;
    }

    /** Successful mutation without a numeric result. */
    public static HabiAssetResult success() {
        return new HabiAssetResult(true, HabiFailure.NONE, 0);
    }

    /** Successful mutation reporting the post-mutation numeric value. */
    public static HabiAssetResult success(int newValue) {
        return new HabiAssetResult(true, HabiFailure.NONE, newValue);
    }

    public static HabiAssetResult fail(HabiFailure failure) {
        return new HabiAssetResult(false, failure, 0);
    }

    public static HabiAssetResult fail(HabiFailure failure, int currentValue) {
        return new HabiAssetResult(false, failure, currentValue);
    }

    public boolean failed() {
        return !ok;
    }

    /** Convenience: the failure description, or {@code ""} when {@link #ok()}. */
    public String message() {
        return failure.message();
    }
}
