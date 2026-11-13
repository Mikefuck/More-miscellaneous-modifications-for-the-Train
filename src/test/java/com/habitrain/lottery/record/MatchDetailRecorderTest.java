package com.habitrain.lottery.record;

import com.google.gson.JsonObject;
import com.habitrain.lottery.network.MatchRecordNetwork;
import net.exmo.sre.record.MatchRecord;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MatchDetailRecorder.Ledger}, {@link MatchReplayEventFormatter},
 * and full record transfer.
 */
class MatchDetailRecorderTest {

    private static final class RecordingSink implements MatchDetailRecorder.EventSink {
        final List<String> events = new ArrayList<>();

        @Override
        public void emitHiddenEvent(UUID playerId, Component text) {
            events.add(playerId + "|" + text.getString());
        }

        @Override
        public Component formatPlayer(UUID playerId) {
            return Component.literal("P-" + playerId.toString().substring(0, 4));
        }

        @Override
        public Component formatTaskName(String quest) {
            return MatchReplayEventFormatter.resolveTaskDisplayName(quest);
        }

        @Override
        public String serializeComponent(Component text, RegistryAccess access) {
            JsonObject obj = new JsonObject();
            obj.addProperty("text", text.getString());
            return obj.toString();
        }
    }

    private static String literalJson(String text) {
        JsonObject obj = new JsonObject();
        obj.addProperty("text", text);
        return obj.toString();
    }

    @Test
    void ledgerAttributesEconomyTasksMinigamesKillsPickupsStoreAndFreezesOnEndGame() {
        RecordingSink sink = new RecordingSink();
        MatchDetailRecorder.Ledger ledger = new MatchDetailRecorder.Ledger(sink);
        ledger.beginTracking();

        UUID p1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID p2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID killer = UUID.fromString("00000000-0000-0000-0000-000000000003");

        // 1. 初始资金
        ledger.recordInitialBalance(p1, "Alice", "sre:civilian", 50);
        ledger.recordInitialBalance(p2, "Bob", "sre:doctor", 0);
        ledger.recordInitialBalance(killer, "Kira", "sre:killer", 100);

        // 2. 自然经济（重复 sync 相同余额必须幂等）
        ledger.pushSimple(MatchDetailRecorder.CoinReason.NATURAL_ECONOMY);
        ledger.onBalanceObserved(p1, 65);
        ledger.onBalanceObserved(p1, 65);
        ledger.popSimple();

        // 3. 普通任务 +40（触发另一玩家任务结算分红 +20）
        ledger.beginTaskScope(p1, "sleep", false);
        ledger.onBalanceObserved(p1, 105);
        ledger.onBalanceObserved(killer, 120);
        ledger.endTaskScope(p1, "sleep", true);

        // 4. 0 金币任务触发他人完成任务分红 +10
        ledger.beginTaskScope(p2, "sre:read_book", false);
        ledger.onBalanceObserved(killer, 130);
        ledger.endTaskScope(p2, "sre:read_book", true);

        // 5. 小游戏任务 +25
        ledger.beginTaskScope(p1, "dance", true);
        ledger.onBalanceObserved(p1, 130);
        ledger.endTaskScope(p1, "dance", true);

        // 6. 击杀金币 + 死亡掉币 + 拾取金币
        ledger.beginKillScope(p2, killer);
        ledger.onBalanceObserved(killer, 205);
        ledger.onBalanceObserved(p2, 0);
        ledger.endKillScope(p2, killer);

        ledger.pushSimple(MatchDetailRecorder.CoinReason.GOLD_PICKUP);
        ledger.onBalanceObserved(p1, 145);
        ledger.popSimple();

        // 7. 商店购买 -80 & 吝啬词条返还 +20
        ledger.onStoreBuy(killer, 80);
        ledger.pushSimple(MatchDetailRecorder.CoinReason.STINGY_REFUND);
        ledger.onBalanceObserved(killer, 145);
        ledger.popSimple();

        // 8. 结束冻结后，ShopComponent.init() 归零不得记负收益
        ledger.stopTrackingOnly();
        ledger.syncSuppressedBalance(p1, 0);
        ledger.onBalanceObserved(p1, 0);

        MatchDetailRecorder.PlayerAccumulator a1 = ledger.snapshot(p1);
        assertNotNull(a1);
        assertEquals(50, a1.initialCoins);
        assertEquals(15, a1.naturalCoins);
        assertEquals(2, a1.taskCount);
        assertEquals(65, a1.taskCoins); // 40 sleep + 25 dance
        assertEquals(15, a1.killAndPickupCoins);
        assertEquals(0, a1.shopSpentCoins);
        assertEquals(0, a1.otherSpentCoins);
        assertEquals(145, a1.finalBalance);

        MatchDetailRecorder.PlayerAccumulator ak = ledger.snapshot(killer);
        assertNotNull(ak);
        assertEquals(100, ak.initialCoins);
        assertEquals(75, ak.killAndPickupCoins);
        assertEquals(50, ak.otherGainCoins); // 20 + 10 task income + 20 stingy refund
        assertEquals(1, ak.shopBuyCount);
        assertEquals(80, ak.shopSpentCoins);
        assertEquals(145, ak.finalBalance);

        // 检查事件文本关键片段
        String joined = String.join("\n", sink.events);
        assertTrue(joined.contains("通过自然经济获得 15 金币"), joined);
        assertTrue(joined.contains("通过做「睡一觉」任务获得 40 金币"), joined);
        assertTrue(joined.contains("在任务结算中获得 20 金币"), joined);
        assertTrue(joined.contains("完成了「读书」任务"), joined);
        assertTrue(joined.contains("因他人完成任务获得 10 金币"), joined);
        assertTrue(joined.contains("通过完成小游戏任务「跳舞」获得 25 金币"), joined);
        assertTrue(joined.contains("通过击杀"), joined);
        assertTrue(joined.contains("通过拾取金币获得 15 金币"), joined);
        assertTrue(joined.contains("通过词条「吝啬」返还 20 金币"), joined);
        assertFalse(joined.contains("消耗 145"), joined);

        // 检查 ECON_SUMMARY 生成（不触发 Minecraft Bootstrap）
        MatchRecord record = new MatchRecord();
        record.events = new ArrayList<>();
        record.players = new ArrayList<>();
        MatchRecord.MatchPlayer mp1 = new MatchRecord.MatchPlayer();
        mp1.uuid = p1.toString();
        mp1.name = "Alice";
        record.players.add(mp1);

        ledger.appendPlayerSummaryEvents(record, RegistryAccess.EMPTY);
        long summaryCount = record.events.stream()
                .filter(e -> MatchDetailRecorder.ECON_SUMMARY_TYPE.equals(e.type) && e.hidden)
                .count();
        assertEquals(3, summaryCount);
    }

    @Test
    void formatterResolvesTaskNamesWithoutLeadingPrefixes() {
        assertEquals("sleep", MatchReplayEventFormatter.normalizeTaskKey("sre:task.sleep"));
        assertEquals("睡一觉", MatchReplayEventFormatter.resolveTaskDisplayName("sleep").getString());
        assertEquals("读书", MatchReplayEventFormatter.resolveTaskDisplayName("sre:read_book").getString());
        assertEquals("自定义任务", MatchReplayEventFormatter.resolveTaskDisplayName("自定义任务").getString());
    }

    @Test
    void longMatchRecordRoundTripsWithoutDroppingEvents() {
        MatchRecord record = new MatchRecord();
        record.matchId = "test-large";
        record.createdAt = 123456L;
        record.playerCount = 2;
        record.players = new ArrayList<>();
        record.events = new ArrayList<>();

        MatchRecord.MatchEvent killEvent = new MatchRecord.MatchEvent();
        killEvent.type = "PLAYER_KILL";
        killEvent.hidden = false;
        killEvent.textJson = literalJson("Killer killed Victim");
        record.events.add(killEvent);

        MatchRecord.MatchEvent buyEvent = new MatchRecord.MatchEvent();
        buyEvent.type = "STORE_BUY";
        buyEvent.hidden = true;
        buyEvent.textJson = literalJson("Killer bought Knife");
        record.events.add(buyEvent);

        // Add >1.3 MB of hidden economy noise
        String padding = "自然经济🚂".repeat(90);
        for (int i = 0; i < 2800; i++) {
            MatchRecord.MatchEvent ev = new MatchRecord.MatchEvent();
            ev.type = "CUSTOM";
            ev.hidden = true;
            ev.textJson = literalJson("Detail #" + i + " " + padding);
            record.events.add(ev);
        }

        MatchRecord.MatchEvent summaryEvent = new MatchRecord.MatchEvent();
        summaryEvent.type = MatchDetailRecorder.ECON_SUMMARY_TYPE;
        summaryEvent.hidden = true;
        summaryEvent.textJson = literalJson("Alice: total +200");
        record.events.add(summaryEvent);

        String raw = record.toJson();
        assertTrue(raw.getBytes(StandardCharsets.UTF_8).length > 1_048_576);
        var chunks = MatchRecordNetwork.split(record.matchId, raw);
        assertTrue(chunks.size() > 1);
        var assembler = MatchRecordNetwork.newAssembler();
        for (var chunk : chunks) {
            // Cross the UTF-8 wire boundary rather than handing the same string object to the receiver.
            byte[] wire = chunk.json().getBytes(StandardCharsets.UTF_8);
            assertTrue(wire.length < 1_048_576);
            var complete = assembler.accept(new String(wire, StandardCharsets.UTF_8), chunk.transferId(),
                    chunk.index(), chunk.count(), chunk.matchId());
            if (chunk.index() + 1 == chunk.count()) {
                assertNotNull(complete);
                assertEquals(record.matchId, complete.metadata());
                assertEquals(raw, complete.json());
                assertEquals(record.events.size(), MatchRecord.fromJson(complete.json()).events.size());
            } else {
                assertNull(complete);
            }
        }
    }
}
