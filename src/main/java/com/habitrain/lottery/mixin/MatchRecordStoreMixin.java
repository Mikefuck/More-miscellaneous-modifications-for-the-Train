package com.habitrain.lottery.mixin;

import com.habitrain.lottery.record.LocalMatchRecordStore;
import com.habitrain.lottery.storage.WorldLotteryPaths;
import net.exmo.sre.record.MatchRecord;
import net.exmo.sre.record.MatchRecordStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(value = MatchRecordStore.class, remap = false)
public class MatchRecordStoreMixin {

    @Inject(method = "isAvailable", at = @At("HEAD"), cancellable = true)
    private static void habi$avail(CallbackInfoReturnable<Boolean> cir) {
        if (WorldLotteryPaths.ready()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "saveAsync", at = @At("HEAD"), cancellable = true)
    private static void habi$save(MatchRecord record, CallbackInfoReturnable<CompletableFuture<Boolean>> cir) {
        Path capturedRoot = WorldLotteryPaths.root();
        if (!WorldLotteryPaths.ready() || capturedRoot == null) {
            return;
        }
        // Capture root on this thread; run on the match-record executor (not commonPool).
        cir.setReturnValue(LocalMatchRecordStore.saveAsync(capturedRoot, record));
    }

    @Inject(method = "listWindowAsync", at = @At("HEAD"), cancellable = true)
    private static void habi$list(int offset, int limit, CallbackInfoReturnable<CompletableFuture<MatchRecordStore.MatchPage>> cir) {
        Path capturedRoot = WorldLotteryPaths.root();
        if (!WorldLotteryPaths.ready() || capturedRoot == null) {
            return;
        }
        cir.setReturnValue(LocalMatchRecordStore.listWindowAsync(capturedRoot, offset, limit));
    }

    @Inject(method = "loadAsync", at = @At("HEAD"), cancellable = true)
    private static void habi$load(String matchId, CallbackInfoReturnable<CompletableFuture<Optional<MatchRecord>>> cir) {
        Path capturedRoot = WorldLotteryPaths.root();
        if (!WorldLotteryPaths.ready() || capturedRoot == null) {
            return;
        }
        cir.setReturnValue(LocalMatchRecordStore.loadAsync(capturedRoot, matchId));
    }
}
