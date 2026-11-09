package com.habitrain.lottery.network;

/**
 * Top-level client cache for login calendar BER ({@code N-01}).
 */
public final class ClientLoginState {
    public static boolean hasData;
    public static long epochDay;
    public static int year;
    public static int month;
    public static int dayOfMonth;
    public static int streak;
    /** Bit {@code i} set =&gt; day-of-month {@code (i + 1)} logged in this month. */
    public static int loginDaysMask;
    public static String resetZoneId = "UTC";
    public static int version;

    private ClientLoginState() {
    }

    public static void reset() {
        hasData = false;
        epochDay = 0L;
        year = 0;
        month = 0;
        dayOfMonth = 0;
        streak = 0;
        loginDaysMask = 0;
        resetZoneId = "UTC";
        version++;
    }

    public static boolean loggedIn(int dayOfMonth) {
        if (dayOfMonth < 1 || dayOfMonth > 31) {
            return false;
        }
        return (loginDaysMask & (1 << (dayOfMonth - 1))) != 0;
    }
}
