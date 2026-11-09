package com.habitrain.lottery.time;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.lottery.HabiLotteryMod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Injectable and configurable daily-reset clock service ({@code N-03} / {@code I-25}).
 *
 * <p>Defaults to {@link ZoneOffset#UTC} so existing world saves preserve identical reset boundaries
 * out-of-the-box, while server operators can configure {@code dailyResetZone} (e.g. {@code Asia/Shanghai})
 * in {@code <world>/habitrain_lottery/config/clock.json} and unit tests can pin deterministic instants
 * via {@link #setTestClock(Clock)} to eliminate UTC-midnight test flakiness.
 */
public final class LotteryDayClock {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile ZoneId configuredZone = ZoneOffset.UTC;
    private static volatile Clock testClock;

    private LotteryDayClock() {
    }

    public static Clock clock() {
        Clock override = testClock;
        if (override != null) {
            return override;
        }
        return Clock.system(configuredZone);
    }

    public static ZoneId zone() {
        Clock override = testClock;
        return override != null ? override.getZone() : configuredZone;
    }

    public static Instant instant() {
        return clock().instant();
    }

    public static LocalDate today() {
        return LocalDate.now(clock());
    }

    public static long todayEpochDay() {
        return today().toEpochDay();
    }

    /**
     * Epoch second when {@code effectiveEpochDay} rolls over to the next day in {@link #zone()}.
     */
    public static long nextResetEpochSecond(long effectiveEpochDay) {
        return LocalDate.ofEpochDay(effectiveEpochDay)
                .plusDays(1)
                .atStartOfDay(zone())
                .toEpochSecond();
    }

    public static long nextResetEpochSecond() {
        return nextResetEpochSecond(todayEpochDay());
    }

    public static ZoneId parseZoneOrUtc(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(zoneId.trim());
        } catch (RuntimeException invalid) {
            return ZoneOffset.UTC;
        }
    }

    /**
     * Monotonic day guard when an operator shifts {@code dailyResetZone} westward (e.g. Asia/Shanghai -> UTC):
     * prevents {@code effectiveDay} from stepping backward below an already-settled {@code lastRecordedEpochDay}
     * within a 1-day timezone offset window, avoiding broken streaks or double-reset anomalies.
     */
    public static long monotonicEpochDay(long lastRecordedEpochDay) {
        long current = todayEpochDay();
        if (lastRecordedEpochDay != Long.MIN_VALUE && lastRecordedEpochDay > 0
                && current == lastRecordedEpochDay - 1) {
            return lastRecordedEpochDay;
        }
        return current;
    }

    public static void setZone(ZoneId zone) {
        configuredZone = Objects.requireNonNull(zone, "zone");
    }

    public static void setTestClock(Clock clock) {
        testClock = clock;
    }

    public static void resetTestClock() {
        testClock = null;
    }

    public static void resetAll() {
        testClock = null;
        configuredZone = ZoneOffset.UTC;
    }

    /** Loads optional {@code config/clock.json} ({@code {"dailyResetZone": "UTC"}}) on world startup. */
    public static void loadWorldConfig(Path configFile) {
        configuredZone = ZoneOffset.UTC;
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return;
        }
        try {
            String raw = Files.readString(configFile, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            if (obj != null && obj.has("dailyResetZone") && !obj.get("dailyResetZone").isJsonNull()) {
                String zoneStr = obj.get("dailyResetZone").getAsString().trim();
                if (!zoneStr.isEmpty()) {
                    configuredZone = ZoneId.of(zoneStr);
                    HabiLotteryMod.LOGGER.info("Loaded dailyResetZone={} from {}", configuredZone, configFile);
                }
            }
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.warn("Invalid dailyResetZone in {}, falling back to UTC: {}", configFile, e.toString());
            configuredZone = ZoneOffset.UTC;
        }
    }

    public static String toConfigJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("dailyResetZone", configuredZone.getId());
        return GSON.toJson(obj);
    }
}
