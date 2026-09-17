package com.habitrain.lottery.card;

/**
 * Shared reject predicate for occupation-card Request and Confirm.
 */
public final class CardUseGates {
    public static final String REJECT_MESSAGE = "§c[职业卡] 对局进行中不能使用职业卡";

    private CardUseGates() {
    }

    /**
     * Reject when the player is a spectator, dead, in the eliminated rest area,
     * or any scanned dimension is not INACTIVE (STARTING/ACTIVE/STOPPING).
     */
    public static boolean shouldReject(boolean spectator, boolean resting, boolean dead, boolean gameNotInactive) {
        return spectatorRestOrDead(spectator, resting, dead) || gameNotInactive;
    }

    public static boolean spectatorRestOrDead(boolean spectator, boolean resting, boolean dead) {
        return spectator || resting || dead;
    }

    /** lookupFailed → treat as running (reject). */
    public static boolean gameNotInactive(boolean lookupFailed, boolean notInactive) {
        return lookupFailed || notInactive;
    }

    /**
     * True when any per-level "not INACTIVE" flag is true.
     * Resting players may sit in an INACTIVE overworld while MATCH is still running.
     */
    public static boolean anyLevelNotInactive(boolean... perLevelNotInactive) {
        if (perLevelNotInactive == null) {
            return false;
        }
        for (boolean notInactive : perLevelNotInactive) {
            if (notInactive) {
                return true;
            }
        }
        return false;
    }
}
