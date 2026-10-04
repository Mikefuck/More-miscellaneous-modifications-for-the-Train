package com.habitrain.lottery.prelaunch.late;

/**
 * One row of the picker.
 *
 * @param address         address as typed in the multiplayer list (or AutoModpack's stored one)
 * @param installedName   AutoModpack modpack installed for this server, or null
 * @param secretTimestamp seconds since epoch when the stored secret was issued, or -1 when missing
 */
record PickerOption(String title, String address, String installedName, long secretTimestamp, boolean selected) {

    /** AutoModpack's default server-side {@code secretLifetime} (336 hours). */
    static final long DEFAULT_SECRET_LIFETIME_SECONDS = 336L * 3600L;

    enum State { READY, NEEDS_LOGIN, NOT_INSTALLED }

    State state(long nowSeconds) {
        if (installedName == null) {
            return State.NOT_INSTALLED;
        }
        return secretUsable(nowSeconds) ? State.READY : State.NEEDS_LOGIN;
    }

    boolean secretUsable(long nowSeconds) {
        // Keep an hour of margin so the secret does not expire mid-download.
        return secretTimestamp > 0 && nowSeconds - secretTimestamp < DEFAULT_SECRET_LIFETIME_SECONDS - 3600L;
    }
}
