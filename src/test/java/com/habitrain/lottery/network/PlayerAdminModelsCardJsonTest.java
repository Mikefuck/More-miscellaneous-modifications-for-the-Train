package com.habitrain.lottery.network;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerAdminModelsCardJsonTest {
    @Test
    void playerRowJsonCarriesFourCardsAndStoreStatus() {
        PlayerAdminModels.PlayerRow row = new PlayerAdminModels.PlayerRow();
        row.cardStatus = "STORED";
        row.cards.put("civilian", 1);
        row.cards.put("neutral", 2);
        row.cards.put("neutral_for_killer", 3);
        row.cards.put("killer", 4);

        PlayerAdminModels.PlayerRow copy = new Gson().fromJson(
                new Gson().toJson(row), PlayerAdminModels.PlayerRow.class);

        assertEquals("STORED", copy.cardStatus);
        assertEquals(1, copy.cards.get("civilian"));
        assertEquals(2, copy.cards.get("neutral"));
        assertEquals(3, copy.cards.get("neutral_for_killer"));
        assertEquals(4, copy.cards.get("killer"));
    }
}
