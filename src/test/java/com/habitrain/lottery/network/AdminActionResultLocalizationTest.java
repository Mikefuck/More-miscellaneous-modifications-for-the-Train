package com.habitrain.lottery.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.api.player.HabiFailure;
import com.habitrain.lottery.card.CardUseGates;
import com.habitrain.lottery.card.ServerMatchGate;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@code N-02}: bilingual server-to-client localization via
 * {@link AdminActionResultS2C}, {@link HabiFailure}, {@link DailyShopNetwork}, and server gates.
 */
class AdminActionResultLocalizationTest {

    @Test
    void adminActionResultCodecRoundTripsTranslationKeyAndNestedArgs() {
        AdminActionResultS2C original = AdminActionResultS2C.translatedWithRefresh(
                "message.habitrain_lottery.admin.cards.added",
                "fallback text",
                true,
                "@screen.habitrain_lottery.config.cards.killer",
                3,
                12);

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            AdminActionResultS2C.CODEC.encode(buf, original);
            AdminActionResultS2C decoded = AdminActionResultS2C.CODEC.decode(buf);
            assertEquals("fallback text", decoded.message());
            assertTrue(decoded.refreshPlayers());
            assertEquals("message.habitrain_lottery.admin.cards.added", decoded.translationKey());
            assertEquals(List.of("@screen.habitrain_lottery.config.cards.killer", "3", "12"),
                    decoded.translationArgs());
            assertNotNull(decoded.toComponent());
        } finally {
            buf.release();
        }
    }

    @Test
    void allFailureEnumsShopCodesAndGateKeysExistInBothZhCnAndEnUs() throws IOException {
        JsonObject zh = loadLang("assets/habitrain_lottery/lang/zh_cn.json");
        JsonObject en = loadLang("assets/habitrain_lottery/lang/en_us.json");

        for (HabiFailure failure : HabiFailure.values()) {
            if (failure == HabiFailure.NONE) {
                assertEquals("", failure.translationKey());
                continue;
            }
            assertLocalizedInBoth(zh, en, failure.translationKey());
        }

        for (String shopCode : List.of(
                "gone", "price_changed", "sold_out", "insufficient",
                "owned", "in_match", "broken", "unavailable", "unknown_other")) {
            assertLocalizedInBoth(zh, en, DailyShopNetwork.failKey(shopCode));
        }

        for (String gateKey : List.of(
                PlayerStateGate.MAIL_BLOCKED_KEY,
                PlayerStateGate.ACTION_BLOCKED_KEY,
                ServerMatchGate.MATCH_ACTIVE_BLOCKED_KEY,
                CardUseGates.REJECT_KEY,
                CrateNetwork.RATE_LIMITED_KEY,
                "screen.habitrain_lottery.config.titles.status.sync_required")) {
            assertLocalizedInBoth(zh, en, gateKey);
        }
    }

    private static void assertLocalizedInBoth(JsonObject zh, JsonObject en, String key) {
        assertFalse(key == null || key.isBlank(), "Translation key must not be blank");
        JsonElement zhEl = zh.get(key);
        JsonElement enEl = en.get(key);
        assertNotNull(zhEl, () -> "Missing zh_cn key: " + key);
        assertNotNull(enEl, () -> "Missing en_us key: " + key);
        assertFalse(zhEl.getAsString().isBlank(), () -> "Blank zh_cn value for: " + key);
        assertFalse(enEl.getAsString().isBlank(), () -> "Blank en_us value for: " + key);
    }

    private static JsonObject loadLang(String resourcePath) throws IOException {
        try (InputStream in = AdminActionResultLocalizationTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in != null) {
                return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
        Path file = Path.of("src", "main", "resources").resolve(resourcePath);
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
