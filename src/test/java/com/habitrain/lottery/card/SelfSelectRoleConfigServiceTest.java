package com.habitrain.lottery.card;

import com.google.gson.Gson;
import com.habitrain.lottery.backpack.ActiveCardForces;
import com.habitrain.lottery.backpack.DailySelfSelectService;
import com.habitrain.lottery.backpack.LocalBackpackStore;
import com.habitrain.lottery.storage.AtomicJsonFiles;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SelfSelectRoleConfigServiceTest {
    private static final Gson GSON = new Gson();
    private static final ResourceLocation EXTERNAL = ResourceLocation.parse("external_roles:detective");
    @TempDir Path temp;

    @BeforeEach void setup() {
        WorldLotteryPaths.initForTests(temp.resolve("first"));
        SelfSelectForces.clear();
        ActiveCardForces.clear();
    }

    @AfterEach void cleanup() {
        WorldLotteryPaths.clear();
        SelfSelectForces.clear();
        ActiveCardForces.clear();
    }

    private static SelfSelectRoleConfig blocked() {
        var config = new SelfSelectRoleConfig();
        config.disabledRoles.add(EXTERNAL.toString());
        return config;
    }

    @Test void savedRestrictionsSurviveReloadAndWorldSwitchDoesNotLeakThem() {
        assertEquals("", SelfSelectRoleConfigService.apply(GSON.toJson(blocked())));
        assertFalse(SelfSelectRoleConfigService.isAllowed(EXTERNAL));
        WorldLotteryPaths.initForTests(temp.resolve("second"));
        assertTrue(SelfSelectRoleConfigService.isAllowed(EXTERNAL));
        WorldLotteryPaths.initForTests(temp.resolve("first"));
        assertFalse(SelfSelectRoleConfigService.isAllowed(EXTERNAL));
    }

    @Test void staleAdministratorCannotOverwriteANewerSave() {
        assertEquals("", SelfSelectRoleConfigService.apply(GSON.toJson(blocked())));
        assertEquals("conflict", SelfSelectRoleConfigService.apply(GSON.toJson(new SelfSelectRoleConfig())));
        assertFalse(SelfSelectRoleConfigService.isAllowed(EXTERNAL));
    }

    @Test void failedWriteDoesNotPublishUnsavedPolicy() throws Exception {
        assertTrue(SelfSelectRoleConfigService.isAllowed(EXTERNAL));
        Path file = WorldLotteryPaths.configFile(SelfSelectRoleConfigService.FILE);
        Path backup = AtomicJsonFiles.bakPath(file);
        Files.createDirectory(backup);
        Files.writeString(backup.resolve("blocker"), "prevent replacement");
        String previous = Files.readString(file);
        assertEquals("write_failed", SelfSelectRoleConfigService.apply(GSON.toJson(blocked())));
        assertTrue(SelfSelectRoleConfigService.isAllowed(EXTERNAL));
        assertEquals(previous, Files.readString(file));
    }

    @Test void corruptConfigurationBlocksSelectionAndIsNotSilentlyOverwritten() throws Exception {
        Path file = WorldLotteryPaths.configFile(SelfSelectRoleConfigService.FILE);
        Files.writeString(file, "{invalid");
        assertFalse(SelfSelectRoleConfigService.isAllowed(EXTERNAL));
        assertEquals("corrupt", SelfSelectRoleConfigService.apply(GSON.toJson(new SelfSelectRoleConfig())));
        assertFalse(Files.exists(file));
        try (var paths = Files.list(file.getParent())) {
            assertTrue(paths.anyMatch(p -> p.getFileName().toString().startsWith(file.getFileName() + ".corrupt-")));
        }
        WorldLotteryPaths.initForTests(temp.resolve("first"));
        assertFalse(SelfSelectRoleConfigService.isAllowed(EXTERNAL));
    }

    @Test void backupRestoresRestrictionsAfterDamagedPrimary() throws Exception {
        assertEquals("", SelfSelectRoleConfigService.apply(GSON.toJson(blocked())));
        Path file = WorldLotteryPaths.configFile(SelfSelectRoleConfigService.FILE);
        Files.copy(file, AtomicJsonFiles.bakPath(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(file, "broken");
        WorldLotteryPaths.initForTests(temp.resolve("first"));
        assertFalse(SelfSelectRoleConfigService.isAllowed(EXTERNAL));
        assertEquals("", SelfSelectRoleConfigService.loadError());
    }

    @Test void rejectedSelectionDoesNotConsumeCardOrQuotaAndDoesNotCancelExistingReservation() {
        UUID rejected = UUID.randomUUID();
        UUID reserved = UUID.randomUUID();
        ResourceLocation reservedRole = ResourceLocation.parse("external_roles:reserved");
        assertTrue(LocalBackpackStore.setSelfSelectCards(rejected, 2));
        assertTrue(LocalBackpackStore.setSelfSelectCards(reserved, 2));
        assertTrue(CardUseService.consumeSelfSelect(reserved, reservedRole));
        SelfSelectRoleConfig policy = blocked();
        policy.disabledRoles.add(reservedRole.toString());
        assertEquals("", SelfSelectRoleConfigService.apply(GSON.toJson(policy)));
        assertFalse(CardUseService.consumeSelfSelect(rejected, EXTERNAL));
        assertEquals(2, LocalBackpackStore.selfSelectCards(rejected));
        assertEquals(DailySelfSelectService.MAX_DAILY_USES, DailySelfSelectService.remaining(rejected));
        assertFalse(SelfSelectForces.has(rejected));
        assertTrue(SelfSelectForces.has(reserved));
        assertEquals(1, LocalBackpackStore.selfSelectCards(reserved));
    }

    @Test void malformedSavePreservesTheCurrentPolicy() {
        assertEquals("", SelfSelectRoleConfigService.apply(GSON.toJson(blocked())));
        assertEquals("malformed", SelfSelectRoleConfigService.apply("{broken"));
        assertFalse(SelfSelectRoleConfigService.isAllowed(EXTERNAL));
    }
}
