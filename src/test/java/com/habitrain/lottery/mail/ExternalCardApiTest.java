package com.habitrain.lottery.mail;

import com.habitrain.lottery.api.player.*;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExternalCardApiTest {
    @TempDir Path root;
    private final UUID player = UUID.randomUUID();

    @BeforeEach void setup() { WorldLotteryPaths.initForTests(root); }
    @AfterEach void cleanup() { WorldLotteryPaths.clear(); }

    @ParameterizedTest @EnumSource(HabiCardKind.class)
    void incrementAfterLargeMailGrantPreservesEveryCardKind(HabiCardKind kind) {
        var reward = reward(kind, 100_000);
        var draft = HabiMailApi.draft("test", "large grant", "test", List.of(reward, reward));
        assertTrue(HabiMailApi.send(player, draft));
        var letter = LocalMailboxStore.loadWithStatus(player).mails().getFirst();
        String key = "mail:" + letter.id;
        assertTrue(RewardDelivery.deliver(player, key, () -> new RewardDelivery.Plan(
                MailCommandsCodec.decodeStrict(letter.commands), List.of(), null)));
        assertEquals(200_000, HabiCardApi.get(player, kind));
        var added = HabiCardApi.add(player, kind, 1);
        assertTrue(added.ok());
        assertEquals(200_001, added.newValue());
        WorldLotteryPaths.initForTests(root);
        assertEquals(200_001, HabiCardApi.get(player, kind));
        assertEquals(199_001, HabiCardApi.add(player, kind, -1000).newValue());
        assertTrue(RewardDelivery.deliver(player, key, () -> { fail("Receipt must prevent a regrant"); return null; }));
        assertEquals(199_001, HabiCardApi.get(player, kind));
    }

    @ParameterizedTest @EnumSource(HabiCardKind.class)
    void overflowIsRejectedWithoutChangingStoredBalance(HabiCardKind kind) {
        assertTrue(LocalBackpackStore.applyOnce(player, "fixture:max", data -> {
            switch (kind) {
                case SELF_SELECT -> data.selfSelectCards = Integer.MAX_VALUE;
                case LIMIT_BREAK -> data.limitBreakCards = Integer.MAX_VALUE;
                default -> data.cards.put(kind.id(), Integer.MAX_VALUE);
            }
        }));
        var result = HabiCardApi.add(player, kind, 1);
        assertEquals(HabiFailure.INVALID_VALUE, result.failure());
        assertEquals(Integer.MAX_VALUE, result.newValue());
        assertFalse(RewardDelivery.deliver(player, "mail:overflow", () -> new RewardDelivery.Plan(
                List.of(reward(kind, 1)), List.of(), null)));
        assertEquals(Integer.MAX_VALUE, HabiCardApi.get(player, kind));
        WorldLotteryPaths.initForTests(root);
        assertEquals(Integer.MAX_VALUE, HabiCardApi.get(player, kind));
        assertEquals(Integer.MAX_VALUE - 1, HabiCardApi.add(player, kind, -1).newValue());
        assertTrue(RewardDelivery.deliver(player, "mail:overflow", () -> null));
        assertEquals(Integer.MAX_VALUE, HabiCardApi.get(player, kind));
    }

    private MailReward reward(HabiCardKind kind, int amount) {
        return switch (kind) {
            case SELF_SELECT -> HabiMailApi.selfSelectCard(amount);
            case LIMIT_BREAK -> HabiMailApi.limitBreakCard(amount);
            default -> HabiMailApi.factionCard(kind, amount);
        };
    }
}
