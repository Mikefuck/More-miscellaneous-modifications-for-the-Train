package com.habitrain.lottery.record;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies every upstream Mixin target used by match-detail recording and verifies that
 * {@code habitrain_lottery}'s record packages depend only on public {@code com.habitrain.core.api.*}.
 */
class MatchDetailRecordMixinsTargetTest {

    private static final String SRE_JAR = "libs/star_rail_express-4.3.0.jar";
    private static final String CORE_JAR = "libs/habitrain_core-2.0.19.jar";

    @Test
    void recordServiceAndPayloadTargetsExist() throws Exception {
        ClassNode service = readJarClass(SRE_JAR, "net/exmo/sre/record/MatchRecordService");
        assertNotNull(findMethod(service, "recordFinishedMatch"), "recordFinishedMatch missing");
        assertNotNull(findMethod(service, "build"), "build missing");

        ClassNode payload = readJarClass(SRE_JAR, "net/exmo/sre/record/network/RecordReplayS2CPayload");
        MethodNode write = findMethod(payload, "write");
        assertNotNull(write, "RecordReplayS2CPayload#write missing");
        int writeUtfCalls = 0;
        for (AbstractInsnNode insn : write.instructions) {
            if (insn instanceof MethodInsnNode m
                    && m.owner.startsWith("net/minecraft/")
                    && ("writeUtf".equals(m.name) || "method_10788".equals(m.name))
                    && m.desc != null && m.desc.startsWith("(Ljava/lang/String;I)")) {
                writeUtfCalls++;
            }
        }
        assertTrue(writeUtfCalls >= 2, "Expected at least 2 writeUtf calls in RecordReplayS2CPayload#write, got " + writeUtfCalls);
    }

    @Test
    void finalizeGameCallsOnEndGameBeforeRecordFinishedMatch() throws Exception {
        ClassNode gameUtils = readJarClass(SRE_JAR, "io/wifi/starrailexpress/game/GameUtils");
        MethodNode finalizeGame = findMethod(gameUtils, "finalizeGame");
        assertNotNull(finalizeGame, "GameUtils#finalizeGame missing");

        int onEndGameIdx = -1;
        int recordMatchIdx = -1;
        int idx = 0;
        for (AbstractInsnNode insn : finalizeGame.instructions) {
            if (insn instanceof MethodInsnNode m) {
                if ("io/wifi/starrailexpress/api/RoleMethodDispatcher".equals(m.owner) && "onEndGame".equals(m.name)) {
                    onEndGameIdx = idx;
                } else if ("net/exmo/sre/record/MatchRecordService".equals(m.owner) && "recordFinishedMatch".equals(m.name)) {
                    recordMatchIdx = idx;
                }
            }
            idx++;
        }
        assertTrue(onEndGameIdx >= 0, "finalizeGame no longer calls RoleMethodDispatcher.onEndGame");
        assertTrue(recordMatchIdx >= 0, "finalizeGame no longer calls MatchRecordService.recordFinishedMatch");
        assertTrue(onEndGameIdx < recordMatchIdx,
                "Expected onEndGame before recordFinishedMatch so pre-reset flush still captures shop balances");
    }

    @Test
    void replayEconomyAndTaskHooksExist() throws Exception {
        ClassNode replayMgr = readJarClass(SRE_JAR, "io/wifi/starrailexpress/api/replay/GameReplayManager");
        assertNotNull(findMethod(replayMgr, "resetReplay"), "resetReplay missing");
        assertNotNull(findMethod(replayMgr, "recordStoreBuy"), "recordStoreBuy missing");
        assertNotNull(findMethod(replayMgr, "buildTimelineEvent"), "buildTimelineEvent missing");

        ClassNode shop = readJarClass(SRE_JAR, "io/wifi/starrailexpress/cca/SREPlayerShopComponent");
        assertNotNull(findField(shop, "balance", "I"), "SREPlayerShopComponent.balance missing");
        assertNotNull(findMethod(shop, "init"), "SREPlayerShopComponent#init missing");
        assertNotNull(findMethod(shop, "sync"), "SREPlayerShopComponent#sync missing");
        assertNotNull(findMethod(shop, "getPlayer"), "SREPlayerShopComponent#getPlayer missing");
        assertNotNull(findMethod(shop, "addToBalance", "(I)V"), "SREPlayerShopComponent#addToBalance missing");

        ClassNode dispatcher = readJarClass(SRE_JAR, "io/wifi/starrailexpress/api/RoleMethodDispatcher");
        assertNotNull(findMethod(dispatcher, "callOnFinishQuest"), "callOnFinishQuest missing");
        assertNotNull(findMethod(dispatcher, "onEndGame"), "onEndGame missing");

        ClassNode minigame = readJarClass(SRE_JAR, "io/wifi/starrailexpress/cca/SREPlayerMinigameTaskComponent");
        assertNotNull(findMethod(minigame, "onMinigameBlockCompleted"), "onMinigameBlockCompleted missing");

        ClassNode murderMode = readJarClass(SRE_JAR, "io/wifi/starrailexpress/game/modes/SREMurderGameMode");
        MethodNode tick = findMethod(murderMode, "tickServerGameLoop");
        assertNotNull(tick, "tickServerGameLoop missing");
        boolean callsAddToBalance = false;
        for (AbstractInsnNode insn : tick.instructions) {
            if (insn instanceof MethodInsnNode m
                    && "io/wifi/starrailexpress/cca/SREPlayerShopComponent".equals(m.owner)
                    && "addToBalance".equals(m.name)
                    && "(I)V".equals(m.desc)) {
                callsAddToBalance = true;
                break;
            }
        }
        assertTrue(callsAddToBalance, "tickServerGameLoop must invoke SREPlayerShopComponent#addToBalance(int)");
    }

    @Test
    void passiveRoleModifierKillAndPickupTargetsExist() throws Exception {
        ClassNode magnate = readJarClass(SRE_JAR, "pro/fazeclan/river/stupid_express/modifier/magnate/MagnatePassiveIncomeHandler");
        assertNotNull(findMethod(magnate, "onServerTick"), "MagnatePassiveIncomeHandler#onServerTick missing");

        ClassNode accountant = readJarClass(SRE_JAR, "org/agmas/noellesroles/game/roles/innocence/accountant/AccountantPlayerComponent");
        assertNotNull(findMethod(accountant, "givePassiveIncome"), "AccountantPlayerComponent#givePassiveIncome missing");

        ClassNode avaricious = readJarClass(SRE_JAR, "pro/fazeclan/river/stupid_express/role/avaricious/AvariciousGoldHandler");
        assertNotNull(findMethod(avaricious, "playerServerTick"), "AvariciousGoldHandler#playerServerTick missing");

        ClassNode modifiers = readJarClass(SRE_JAR, "org/agmas/noellesroles/role/ModifierEffects");
        assertNotNull(findMethod(modifiers, "onServerTick"), "ModifierEffects#onServerTick missing");
        assertNotNull(findMethod(modifiers, "onBigEaterTaskComplete"), "ModifierEffects#onBigEaterTaskComplete missing");
        assertNotNull(findMethod(modifiers, "onStingyPurchase"), "ModifierEffects#onStingyPurchase missing");

        ClassNode gameMode = readJarClass(SRE_JAR, "io/wifi/starrailexpress/api/GameMode");
        assertNotNull(findMethod(gameMode, "killPlayer"), "GameMode#killPlayer missing");

        ClassNode goldEvent = readJarClass(SRE_JAR, "io/wifi/starrailexpress/cca/MurderTimeEventComponent");
        assertNotNull(findMethod(goldEvent, "getMurderGoldAmount"), "MurderTimeEventComponent#getMurderGoldAmount missing");
    }

    @Test
    void clientMatchRecordsScreenTargetsExist() throws Exception {
        ClassNode screen = readJarClass(SRE_JAR, "net/exmo/sre/record/client/MatchRecordsScreen");
        assertNotNull(findField(screen, "selectedMatchId", "Ljava/lang/String;"), "selectedMatchId missing");
        assertNotNull(findField(screen, "selectedRecord", "Lnet/exmo/sre/record/MatchRecord;"), "selectedRecord missing");
        assertNotNull(findField(screen, "detailLines", "Ljava/util/List;"), "detailLines missing");
        assertNotNull(findField(screen, "rightX", "I"), "rightX missing");
        assertNotNull(findField(screen, "rightW", "I"), "rightW missing");
        assertNotNull(findField(screen, "panelY", "I"), "panelY missing");
        assertNotNull(findField(screen, "detailScrollOffset", "I"), "detailScrollOffset missing");
        assertNotNull(findField(screen, "maxDetailScroll", "I"), "maxDetailScroll missing");

        assertNotNull(findMethod(screen, "rebuildDetailLines"), "rebuildDetailLines missing");
        assertNotNull(findMethod(screen, "detailContentH"), "detailContentH missing");
        // In production intermediary jar, vanilla override names are obfuscated; verify non-empty method table and declared detail builder.
        assertEquals("rebuildDetailLines", findMethod(screen, "rebuildDetailLines").name);
    }

    @Test
    void corePublicTaskApiExistsAndRecordClassesDoNotTouchCoreInternals() throws Exception {
        ClassNode taskReg = readJarClass(CORE_JAR, "com/habitrain/core/api/TaskRegistry");
        assertNotNull(findMethod(taskReg, "get"), "TaskRegistry#get missing");
        assertNotNull(findMethod(taskReg, "getAll"), "TaskRegistry#getAll missing");

        ClassNode taskDef = readJarClass(CORE_JAR, "com/habitrain/core/api/TaskDefinition");
        assertNotNull(findMethod(taskDef, "isPoolEligible"), "TaskDefinition#isPoolEligible missing");
        assertNotNull(findMethod(taskDef, "getDisplayName"), "TaskDefinition#getDisplayName missing");

        List<Path> classFiles = new ArrayList<>();
        for (String sub : List.of("build/classes/java/main/com/habitrain/lottery/record",
                "build/classes/java/main/com/habitrain/lottery/client/record")) {
            Path dir = Path.of(sub);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.walk(dir)) {
                    s.filter(p -> p.toString().endsWith(".class")).forEach(classFiles::add);
                }
            }
        }
        assertFalse(classFiles.isEmpty(), "Expected compiled record class files under build/classes/java/main");

        for (Path cf : classFiles) {
            byte[] bytes = Files.readAllBytes(cf);
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            for (MethodNode m : node.methods) {
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof MethodInsnNode call) {
                        assertNoCoreInternal(cf, call.owner);
                    } else if (insn instanceof FieldInsnNode field) {
                        assertNoCoreInternal(cf, field.owner);
                    } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String str) {
                        assertFalse(str.contains("com/habitrain/core/game/") || str.contains("com/habitrain/core/config/"),
                                cf + " must not reference core internal strings: " + str);
                    }
                }
            }
        }
    }

    private static void assertNoCoreInternal(Path source, String owner) {
        assertFalse(owner.startsWith("com/habitrain/core/game/") || owner.startsWith("com/habitrain/core/config/"),
                source + " referenced disallowed core internal class: " + owner);
    }

    private static ClassNode readJarClass(String jarPath, String internalName) throws IOException {
        Path path = Path.of(jarPath);
        assertTrue(Files.isRegularFile(path), "Missing dependency jar: " + path.toAbsolutePath());
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry(internalName + ".class");
            assertNotNull(entry, "Missing class " + internalName + " in " + jarPath);
            try (InputStream in = zip.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(in.readAllBytes()).accept(node, 0);
                return node;
            }
        }
    }

    private static MethodNode findMethod(ClassNode node, String name) {
        for (MethodNode m : node.methods) {
            if (name.equals(m.name)) {
                return m;
            }
        }
        return null;
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        for (MethodNode m : node.methods) {
            if (name.equals(m.name) && desc.equals(m.desc)) {
                return m;
            }
        }
        return null;
    }

    private static FieldNode findField(ClassNode node, String name, String desc) {
        for (FieldNode f : node.fields) {
            if (name.equals(f.name) && desc.equals(f.desc)) {
                return f;
            }
        }
        return null;
    }
}
