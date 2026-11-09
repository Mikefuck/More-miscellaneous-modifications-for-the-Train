package com.habitrain.lottery.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies 100% key parity, zero duplicate JSON keys, and format-specifier ({@code %s} / {@code %1$s})
 * alignment between {@code zh_cn.json} and {@code en_us.json} ({@code N-02}).
 */
class LocalizationKeysAlignmentTest {
    private static final String ZH_RESOURCE = "assets/habitrain_lottery/lang/zh_cn.json";
    private static final String EN_RESOURCE = "assets/habitrain_lottery/lang/en_us.json";

    /**
     * Matches literal {@code %%} OR format placeholders like {@code %s}, {@code %1$s}, {@code %d}, {@code %.2f}.
     */
    private static final Pattern FORMAT_TOKEN = Pattern.compile("%%|%(\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([sfd])");

    @Test
    void zhCnAndEnUsHaveNoDuplicateJsonKeys() throws IOException {
        String zhRaw = readResourceText(ZH_RESOURCE);
        String enRaw = readResourceText(EN_RESOURCE);

        List<String> zhDuplicates = findDuplicateTopLevelKeys(zhRaw);
        List<String> enDuplicates = findDuplicateTopLevelKeys(enRaw);

        assertTrue(zhDuplicates.isEmpty(), () -> "Duplicate keys in zh_cn.json: " + zhDuplicates);
        assertTrue(enDuplicates.isEmpty(), () -> "Duplicate keys in en_us.json: " + enDuplicates);
    }

    @Test
    void zhCnAndEnUsHaveIdenticalKeySets() throws IOException {
        Map<String, String> zh = parseLangMap(readResourceText(ZH_RESOURCE), ZH_RESOURCE);
        Map<String, String> en = parseLangMap(readResourceText(EN_RESOURCE), EN_RESOURCE);

        Set<String> onlyInZh = new TreeSet<>(zh.keySet());
        onlyInZh.removeAll(en.keySet());

        Set<String> onlyInEn = new TreeSet<>(en.keySet());
        onlyInEn.removeAll(zh.keySet());

        assertTrue(onlyInZh.isEmpty() && onlyInEn.isEmpty(),
                () -> "Localization key mismatch!\nMissing in en_us.json: " + onlyInZh
                        + "\nMissing in zh_cn.json: " + onlyInEn);
        assertFalse(zh.isEmpty(), "Localization maps must not be empty");
    }

    @Test
    void zhCnAndEnUsHaveMatchingFormatPlaceholdersForEveryKey() throws IOException {
        Map<String, String> zh = parseLangMap(readResourceText(ZH_RESOURCE), ZH_RESOURCE);
        Map<String, String> en = parseLangMap(readResourceText(EN_RESOURCE), EN_RESOURCE);

        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> entry : zh.entrySet()) {
            String key = entry.getKey();
            String zhVal = entry.getValue();
            String enVal = en.get(key);
            if (enVal == null) {
                continue; // Reported by zhCnAndEnUsHaveIdenticalKeySets
            }
            Set<String> zhSpecs = extractNormalizedSpecifiers(zhVal);
            Set<String> enSpecs = extractNormalizedSpecifiers(enVal);
            if (!zhSpecs.equals(enSpecs)) {
                mismatches.add(key + " -> zh=" + zhSpecs + " (\"" + zhVal + "\") vs en="
                        + enSpecs + " (\"" + enVal + "\")");
            }
        }

        assertTrue(mismatches.isEmpty(),
                () -> "Format placeholder mismatch between zh_cn.json and en_us.json:\n"
                        + String.join("\n", mismatches));
    }

    @Test
    void newlyMigratedUiAndCalendarKeysArePresentInBothLocales() throws IOException {
        Map<String, String> zh = parseLangMap(readResourceText(ZH_RESOURCE), ZH_RESOURCE);
        Map<String, String> en = parseLangMap(readResourceText(EN_RESOURCE), EN_RESOURCE);

        List<String> requiredKeys = List.of(
                "block.habitrain_lottery.login_calendar.weekday.1",
                "block.habitrain_lottery.login_calendar.weekday.7",
                "block.habitrain_lottery.login_calendar.streak",
                "message.habitrain_lottery.map_introduce.empty",
                "screen.habitrain_lottery.config.locked.subtitle",
                "screen.habitrain_lottery.config.locked.hint",
                "screen.habitrain_lottery.config.players.page_info",
                "screen.habitrain_lottery.config.titles.header_catalog",
                "screen.habitrain_lottery.mail_compose.title",
                "screen.habitrain_lottery.mail_compose.confirm_all.title",
                "screen.habitrain_lottery.mail_compose.confirm_all.body",
                "screen.habitrain_lottery.mail_compose.action.send_cooldown"
        );

        for (String key : requiredKeys) {
            assertTrue(zh.containsKey(key), () -> "Missing required key in zh_cn.json: " + key);
            assertTrue(en.containsKey(key), () -> "Missing required key in en_us.json: " + key);
            assertFalse(zh.get(key).isBlank(), () -> "Blank zh_cn translation for: " + key);
            assertFalse(en.get(key).isBlank(), () -> "Blank en_us translation for: " + key);
        }
    }

    /**
     * Normalizes positional ({@code %s}) and indexed ({@code %1$s}) placeholders into canonical
     * {@code "<index>:<type>"} tokens (ignoring escaped {@code %%}), allowing natural word-order
     * reordering across languages while enforcing 100% argument count and type parity.
     */
    static Set<String> extractNormalizedSpecifiers(String text) {
        Set<String> result = new TreeSet<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        Matcher matcher = FORMAT_TOKEN.matcher(text);
        int sequentialIndex = 0;
        while (matcher.find()) {
            String token = matcher.group();
            if ("%%".equals(token)) {
                continue;
            }
            String explicitIndex = matcher.group(1);
            String convType = matcher.group(2);
            int argIndex;
            if (explicitIndex != null && !explicitIndex.isEmpty()) {
                argIndex = Integer.parseInt(explicitIndex.substring(0, explicitIndex.length() - 1));
            } else {
                sequentialIndex++;
                argIndex = sequentialIndex;
            }
            result.add(argIndex + ":" + convType);
        }
        return result;
    }

    private static List<String> findDuplicateTopLevelKeys(String rawJson) throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        try (JsonReader reader = new JsonReader(new StringReader(rawJson))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!seen.add(name)) {
                    duplicates.add(name);
                }
                if (reader.peek() == JsonToken.STRING) {
                    reader.nextString();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        }
        return duplicates;
    }

    private static Map<String, String> parseLangMap(String rawJson, String sourceName) {
        JsonElement element = JsonParser.parseString(rawJson);
        assertTrue(element.isJsonObject(), () -> sourceName + " root must be a JSON object");
        JsonObject obj = element.getAsJsonObject();
        Map<String, String> map = new LinkedHashMap<>(obj.size());
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement val = entry.getValue();
            assertNotNull(val, () -> "Null value in " + sourceName + " for key " + entry.getKey());
            assertTrue(val.isJsonPrimitive() && val.getAsJsonPrimitive().isString(),
                    () -> "Non-string value in " + sourceName + " for key " + entry.getKey());
            map.put(entry.getKey(), val.getAsString());
        }
        return map;
    }

    private static String readResourceText(String resourcePath) throws IOException {
        try (InputStream in = LocalizationKeysAlignmentTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Path fallback = Path.of("src", "main", "resources").resolve(resourcePath);
        assertEquals(true, Files.isRegularFile(fallback),
                () -> "Could not locate resource on classpath or at " + fallback.toAbsolutePath());
        return Files.readString(fallback, StandardCharsets.UTF_8);
    }
}
