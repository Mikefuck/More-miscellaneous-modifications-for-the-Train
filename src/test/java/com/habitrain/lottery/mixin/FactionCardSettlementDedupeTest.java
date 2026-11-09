package com.habitrain.lottery.mixin;

import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.card.CardUseService;
import com.habitrain.lottery.grant.CardForceGuaranteeHook;
import com.habitrain.lottery.grant.LoginRewardService;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.habitrain.lottery.time.LotteryDayClock;
import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for {@code B-12} (double faction-card refund & draft voluntary skip)
 * and {@code I-16} (upstream MySQL bypass targets).
 */
class FactionCardSettlementDedupeTest {

    private static final String SRE_JAR = "libs/star_rail_express-4.3.0.jar";
    private static final String PROGRESSION = "io/wifi/starrailexpress/progression/ProgressionDataManager";

    @TempDir
    Path temp;

    private final PlayerLotteryStore store = PlayerLotteryStore.get();

    @BeforeEach
    void setUp() {
        LotteryDayClock.setTestClock(Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC));
        store.reset();
        ActiveCardForces.clear();
        WorldLotteryPaths.initForTests(temp);
    }

    @AfterEach
    void tearDown() {
        ActiveCardForces.clear();
        store.reset();
        WorldLotteryPaths.clear();
        LotteryDayClock.resetTestClock();
    }

    @Test
    void upstreamTargetsExistInSreJarForRefundAndMysqlMixins() throws Exception {
        ClassNode murder = readJarClass("io/wifi/starrailexpress/game/modes/SREMurderGameMode");
        MethodNode assignRoles = findMethod(murder, "assignRolesToPlayers");
        assertEquals(2, Type.getArgumentTypes(assignRoles.desc).length,
                "SREMurderGameMode.assignRolesToPlayers param count changed: " + assignRoles.desc);
        assertEquals("java/util/Map", Type.getReturnType(assignRoles.desc).getInternalName());
        assertTrue(callsMethod(assignRoles, PROGRESSION, "addFactionCard"),
                "SREMurderGameMode.assignRolesToPlayers must still call ProgressionDataManager.addFactionCard");
        MethodNode assignRole = findMethod(murder, "assignRole");
        assertEquals(3, Type.getArgumentTypes(assignRole.desc).length,
                "SREMurderGameMode.assignRole param count changed: " + assignRole.desc);
        assertEquals(Type.VOID_TYPE, Type.getReturnType(assignRole.desc));

        ClassNode singleSelect = readJarClass("io/wifi/starrailexpress/game/modes/funny/rotation/SingleSelectDraftState");
        assertNotNull(findField(singleSelect, "selectedRoles"),
                "SingleSelectDraftState must still declare selectedRoles");
        MethodNode adjustRemaining = findMethod(singleSelect, "adjustRemainingRoles");
        assertEquals(1, Type.getArgumentTypes(adjustRemaining.desc).length,
                "SingleSelectDraftState.adjustRemainingRoles param count changed: " + adjustRemaining.desc);
        assertEquals(Type.VOID_TYPE, Type.getReturnType(adjustRemaining.desc));

        ClassNode lightning = readJarClass("io/wifi/starrailexpress/game/modes/funny/rotation/LightningDraftState");
        assertNotNull(findField(lightning, "roundCandidates"),
                "LightningDraftState must still declare roundCandidates");
        assertNotNull(findField(lightning, "selectedRoles"),
                "LightningDraftState must still declare selectedRoles");
        MethodNode adjustRoles = findMethod(lightning, "adjustRoles");
        assertEquals(1, Type.getArgumentTypes(adjustRoles.desc).length,
                "LightningDraftState.adjustRoles param count changed: " + adjustRoles.desc);
        assertEquals(Type.VOID_TYPE, Type.getReturnType(adjustRoles.desc));
        assertTrue(callsMethod(findMethod(lightning, "initializeCardTracking"), PROGRESSION, "addFactionCard"),
                "LightningDraftState.initializeCardTracking must still call ProgressionDataManager.addFactionCard");
        assertTrue(callsMethod(findMethod(lightning, "startNextRound"), PROGRESSION, "addFactionCard"),
                "LightningDraftState.startNextRound must still call ProgressionDataManager.addFactionCard");

        ClassNode economy = readJarClass("io/wifi/starrailexpress/data/PlayerEconomyManager");
        assertEquals("()Z", findMethod(economy, "isDatabaseEnabled").desc);
        assertNotNull(findMethod(economy, "reloadFromDatabase"));

        ClassNode equippedSync = readJarClass("net/exmo/sre/sync/EquippedSkinsDatabaseSync");
        assertEquals("()Z", findMethod(equippedSync, "isUploadEnabled").desc);
        MethodNode queueUpload = findMethod(equippedSync, "queueUpload");
        assertEquals(1, Type.getArgumentTypes(queueUpload.desc).length);
        assertEquals(Type.VOID_TYPE, Type.getReturnType(queueUpload.desc));
        MethodNode flushAll = findMethod(equippedSync, "flushAllBlocking");
        assertEquals(1, Type.getArgumentTypes(flushAll.desc).length);
        assertEquals(Type.VOID_TYPE, Type.getReturnType(flushAll.desc));
    }

    @Test
    void killerNeutralOverflowRefundsExactCardTypeOnceWithoutDoubleRefund() throws Exception {
        UUID player = UUID.randomUUID();
        assertTrue(store.applyToPlayerWithRollback(player, d -> {
            d.lastFactionCardUseEpochDay = LoginRewardService.todayEpochDayUtc();
            d.factionCardUsesToday = 1;
        }));
        ActiveCardForces.record(player, FactionCardType.NEUTRAL_FOR_KILLER);

        // Step 1: Upstream overflow / cap triggers redirected CardUseService.refundForcedCard(player).
        assertTrue(CardUseService.refundForcedCard(player));
        assertNull(ActiveCardForces.get(player), "Reservation must be cleared immediately on upstream refund");
        assertEquals(1, LocalBackpackStore.load(player).getOrDefault(FactionCardType.NEUTRAL_FOR_KILLER.questKey, 0));
        assertEquals(0, LocalBackpackStore.load(player).getOrDefault(FactionCardType.NEUTRAL.questKey, 0),
                "Killer-neutral (type 3) must not fold into plain neutral (type 2)");
        assertEquals(0, store.getOrLoad(player).factionCardUsesToday);

        // Step 2: OnGameTrueStarted runs later and sees civilian role (type 1) — must NOT refund a second card!
        invokeSettleCard(player, 1);
        CardForceGuaranteeHook.refundPendingCards();
        assertFalse(CardUseService.refundForcedCard(player));
        assertEquals(1, LocalBackpackStore.load(player).getOrDefault(FactionCardType.NEUTRAL_FOR_KILLER.questKey, 0));
        assertEquals(0, store.getOrLoad(player).factionCardUsesToday);
    }

    @Test
    void draftVoluntarySkipConsumesCardWhenMatchingCandidateWasOffered() throws Exception {
        UUID player = UUID.randomUUID();
        assertTrue(store.applyToPlayerWithRollback(player, d -> {
            d.lastFactionCardUseEpochDay = LoginRewardService.todayEpochDayUtc();
            d.factionCardUsesToday = 1;
        }));
        ActiveCardForces.record(player, FactionCardType.KILLER);
        ActiveCardForces.markCandidateOffered(player);
        assertTrue(ActiveCardForces.isCandidateOffered(player));

        // Player voluntarily chose a civilian role (type 1) despite being offered killer candidates.
        invokeSettleCard(player, 1);

        assertNull(ActiveCardForces.get(player));
        assertFalse(ActiveCardForces.isCandidateOffered(player));
        assertEquals(0, LocalBackpackStore.load(player).getOrDefault(FactionCardType.KILLER.questKey, 0),
                "Voluntary skip after receiving matching draft candidates must NOT refund the card");
        assertEquals(1, store.getOrLoad(player).factionCardUsesToday,
                "Daily quota must remain consumed after voluntary skip");
    }

    @Test
    void draftAbortedBeforeTrueStartStillRefundsOfferedCard() {
        UUID player = UUID.randomUUID();
        assertTrue(store.applyToPlayerWithRollback(player, d -> {
            d.lastFactionCardUseEpochDay = LoginRewardService.todayEpochDayUtc();
            d.factionCardUsesToday = 1;
        }));
        ActiveCardForces.record(player, FactionCardType.KILLER);
        ActiveCardForces.markCandidateOffered(player);

        // Match cancelled during draft before OnGameTrueStarted fires OnGameEnd -> refundPendingCards().
        CardForceGuaranteeHook.refundPendingCards();

        assertNull(ActiveCardForces.get(player));
        assertEquals(1, LocalBackpackStore.load(player).getOrDefault(FactionCardType.KILLER.questKey, 0));
        assertEquals(0, store.getOrLoad(player).factionCardUsesToday);
    }

    @Test
    void postDraftBalancerOverwritingMatchedCivilianPickRefundsCard() throws Exception {
        UUID player = UUID.randomUUID();
        assertTrue(store.applyToPlayerWithRollback(player, d -> {
            d.lastFactionCardUseEpochDay = LoginRewardService.todayEpochDayUtc();
            d.factionCardUsesToday = 1;
        }));
        ActiveCardForces.record(player, FactionCardType.CIVILIAN);
        ActiveCardForces.markCandidateOffered(player);
        // Player genuinely selected a Civilian role during draft before adjustRemainingRoles/adjustRoles ran:
        ActiveCardForces.clearCandidateOffered(player);

        // Balancer forcibly overwrote their Civilian pick with an underfilled Killer role (type 4):
        invokeSettleCard(player, 4);

        assertNull(ActiveCardForces.get(player));
        assertEquals(1, LocalBackpackStore.load(player).getOrDefault(FactionCardType.CIVILIAN.questKey, 0),
                "Card must be refunded when post-draft balancer overwrites a genuinely matched pick");
        assertEquals(0, store.getOrLoad(player).factionCardUsesToday);
    }

    private static void invokeSettleCard(UUID uid, Integer assignedType) throws Exception {
        Method m = CardForceGuaranteeHook.class.getDeclaredMethod("settleCard", UUID.class, Integer.class);
        m.setAccessible(true);
        m.invoke(null, uid, assignedType);
    }

    private static ClassNode readJarClass(String internalName) throws IOException {
        Path jar = Path.of(SRE_JAR);
        assertTrue(Files.isRegularFile(jar), "Missing runtime dependency " + SRE_JAR);
        ClassNode node = new ClassNode();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(internalName + ".class");
            assertNotNull(entry, "Missing " + internalName + " in " + SRE_JAR);
            try (InputStream in = zip.getInputStream(entry)) {
                new ClassReader(in).accept(node, 0);
            }
        }
        return node;
    }

    private static MethodNode findMethod(ClassNode owner, String name) {
        for (MethodNode m : owner.methods) {
            if (name.equals(m.name)) {
                return m;
            }
        }
        throw new AssertionError("Missing method " + owner.name + "." + name);
    }

    private static FieldNode findField(ClassNode owner, String name) {
        for (FieldNode f : owner.fields) {
            if (name.equals(f.name)) {
                return f;
            }
        }
        return null;
    }

    private static boolean callsMethod(MethodNode method, String targetOwner, String targetName) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && targetOwner.equals(call.owner)
                    && targetName.equals(call.name)) {
                return true;
            }
        }
        return false;
    }
}
