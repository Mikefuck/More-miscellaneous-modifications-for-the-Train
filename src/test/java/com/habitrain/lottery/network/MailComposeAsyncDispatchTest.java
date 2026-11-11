package com.habitrain.lottery.network;

import com.habitrain.lottery.mail.LocalMailboxStore;
import com.habitrain.lottery.mail.MailDraft;
import com.habitrain.lottery.mail.MailReward;
import com.habitrain.lottery.mail.MailService;
import com.habitrain.lottery.storage.MetaFeaturePaths;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import com.mojang.authlib.GameProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@code B-07}:
 * <ul>
 *   <li>Case-insensitive target deduplication via {@link MailComposeC2SPayload#deduplicateTargets}.</li>
 *   <li>Zero main-thread HTTP in {@link ProfileCacheLookup}: reads in-memory {@code Map} entries
 *       without ever invoking {@code get(String)} / {@code get(UUID)}.</li>
 *   <li>Background execution on {@link MailDispatchExecutor} with single-flight storm protection
 *       and clean {@link MailDispatchExecutor#shutdownAndAwait()} draining.</li>
 * </ul>
 */
class MailComposeAsyncDispatchTest {

    @TempDir
    Path tempDir;

    /**
     * Mock {@code GameProfileCache} whose {@code get(...)} methods throw immediately if called,
     * proving {@link ProfileCacheLookup} never triggers blocking Yggdrasil HTTPS lookups.
     */
    static final class TrapProfileCache {
        private final Map<String, TrapEntry> profilesByName = new ConcurrentHashMap<>();
        private final Map<UUID, TrapEntry> profilesByUUID = new ConcurrentHashMap<>();

        void put(GameProfile profile) {
            TrapEntry entry = new TrapEntry(profile);
            profilesByName.put(profile.getName().toLowerCase(java.util.Locale.ROOT), entry);
            profilesByUUID.put(profile.getId(), entry);
        }

        public Optional<GameProfile> get(String name) {
            throw new AssertionError("Blocking GameProfileCache.get(String) MUST NOT be called!");
        }

        public Optional<GameProfile> get(UUID id) {
            throw new AssertionError("GameProfileCache.get(UUID) MUST NOT be called!");
        }
    }

    static final class TrapEntry {
        private final GameProfile profile;

        TrapEntry(GameProfile profile) {
            this.profile = profile;
        }

        public GameProfile getProfile() {
            return profile;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        WorldLotteryPaths.initForTest(tempDir);
        MetaFeaturePaths.ensureDirs();
    }

    @AfterEach
    void tearDown() {
        MailDispatchExecutor.shutdownAndAwait(5L, TimeUnit.SECONDS);
        MailService.onServerStopping();
        WorldLotteryPaths.reset();
    }

    @Test
    void timedOutShutdownKeepsSingleFlightOwnershipUntilWorkerActuallyExits() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            assertTrue(MailDispatchExecutor.submit(() -> {
                started.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException ignored) { /* model a non-interruptible disk operation */ }
                }
                finished.countDown();
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(MailDispatchExecutor.shutdownAndAwait(1, TimeUnit.MILLISECONDS));
            assertTrue(MailDispatchExecutor.isBusy());
            assertFalse(MailDispatchExecutor.submit(() -> failUnexpectedWorker()),
                    "A timed-out old-world task must retain its executor ownership");
        } finally {
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertTrue(MailDispatchExecutor.shutdownAndAwait(2, TimeUnit.SECONDS));
        }
        assertTrue(MailDispatchExecutor.submit(() -> {}), "New world can submit after old worker exits");
    }

    private static void failUnexpectedWorker() {
        throw new AssertionError("Unexpected second worker");
    }

    @Test
    void capturedMailboxPathCannotWriteIntoTheNextIntegratedWorld() throws Exception {
        UUID recipient = UUID.randomUUID();
        Path oldMailbox = MetaFeaturePaths.mailPlayer(recipient);
        WorldLotteryPaths.initForTest(tempDir.resolve("next-world"));
        Path nextMailbox = MetaFeaturePaths.mailPlayer(recipient);
        MailDraft draft = new MailDraft("system", "old-world", "content", 0L, List.of());
        assertTrue(MailService.sendOfflineTo(oldMailbox, recipient, "recipient", draft));
        assertEquals(1, LocalMailboxStore.loadFrom(oldMailbox).mails().size());
        assertTrue(LocalMailboxStore.loadFrom(nextMailbox).missing());
    }

    @Test
    void deduplicateTargetsPreservesFirstSeenOrderCaseInsensitively() {
        List<String> raw = List.of("Alice", "  alice ", "BOB", "ALICE", "", "   ", "bob", "Charlie");
        assertEquals(List.of("Alice", "BOB", "Charlie"), MailComposeC2SPayload.deduplicateTargets(raw));
    }

    @Test
    void profileCacheLookupReadsInMemoryMapsWithoutCallingBlockingGet() {
        TrapProfileCache cache = new TrapProfileCache();
        UUID aliceId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        cache.put(new GameProfile(aliceId, "AliceWonder"));

        assertEquals(Optional.of(aliceId),
                ProfileCacheLookup.lookupCachedUuidFromCache(cache, "alicewonder"));
        assertEquals(Optional.of(aliceId),
                ProfileCacheLookup.lookupCachedUuidFromCache(cache, "ALICEWONDER"));
        assertEquals(Optional.empty(),
                ProfileCacheLookup.lookupCachedUuidFromCache(cache, "NonExistentUser"));

        assertEquals(Optional.of("AliceWonder"),
                ProfileCacheLookup.lookupCachedNameFromCache(cache, aliceId));
        assertEquals(Optional.empty(),
                ProfileCacheLookup.lookupCachedNameFromCache(cache, UUID.randomUUID()));
    }

    @Test
    void dispatchResolvedBatchWritesMailboxesOffThreadAndEnforcesSingleFlightGuard() throws Exception {
        UUID u1 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID u2 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
        Map<UUID, String> recipients = new LinkedHashMap<>();
        recipients.put(u1, "PlayerOne");
        recipients.put(u2, "PlayerTwo");

        MailDraft draft = new MailDraft(
                "列车长",
                "维护补偿",
                "感谢配合维护",
                System.currentTimeMillis() + 86_400_000L,
                List.of(MailReward.greenApples(320))
        );

        CountDownLatch callbackLatch = new CountDownLatch(1);
        AtomicReference<MailComposeC2SPayload.DispatchSummary> summaryRef = new AtomicReference<>();
        AtomicReference<String> callbackThreadName = new AtomicReference<>();

        boolean accepted = MailComposeC2SPayload.dispatchResolvedBatch(
                recipients,
                1,
                draft,
                Runnable::run,
                summary -> {
                    callbackThreadName.set(Thread.currentThread().getName());
                    summaryRef.set(summary);
                    callbackLatch.countDown();
                }
        );

        assertTrue(accepted, "First batch should be accepted by MailDispatchExecutor");
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS), "Background mail batch should finish promptly");

        assertEquals("habitrain-lottery-mail-dispatch", callbackThreadName.get(),
                "Mailbox writes must run on the dedicated background mail dispatch thread");
        MailComposeC2SPayload.DispatchSummary summary = summaryRef.get();
        assertNotNull(summary);
        assertEquals(2, summary.okCount());
        assertEquals(1, summary.failCount());
        assertEquals(List.of(u1, u2), summary.succeededRecipients());

        var m1 = LocalMailboxStore.loadWithStatus(u1);
        var m2 = LocalMailboxStore.loadWithStatus(u2);
        assertTrue(m1.ok());
        assertTrue(m2.ok());
        assertEquals(1, m1.mails().size());
        assertEquals("维护补偿", m1.mails().get(0).title);
        assertEquals(1, m2.mails().size());

        // Verify single-flight guard rejects overlapping bulk-mail storms
        CountDownLatch holdWorker = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        assertTrue(MailDispatchExecutor.submit(() -> {
            workerStarted.countDown();
            try {
                holdWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
        assertTrue(MailDispatchExecutor.isBusy());
        assertFalse(MailDispatchExecutor.submit(() -> {}),
                "Concurrent batch submission must be rejected while a batch is already in flight");

        holdWorker.countDown();
        assertTrue(MailDispatchExecutor.shutdownAndAwait(5L, TimeUnit.SECONDS));
        assertFalse(MailDispatchExecutor.isBusy());
    }
}
