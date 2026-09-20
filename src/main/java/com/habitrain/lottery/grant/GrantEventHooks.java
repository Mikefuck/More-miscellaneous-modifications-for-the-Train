package com.habitrain.lottery.grant;

import com.habitrain.core.api.GameModeIds;
import com.habitrain.core.api.match.MatchEvents;
import com.habitrain.core.api.match.MatchSettlement;
import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.role.v2.EffectiveRoleProfile;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import io.wifi.starrailexpress.api.CustomWinnerRole;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Participate and faction win grants. Subscribes to core {@link MatchEvents#ROUND_ENDED}
 * instead of SRE {@code OnGameEnd} / round-end CCA.
 *
 * <p>Win grants:
 * <ul>
 *   <li>乘客 / innocent → 1 抽 ({@code win_passenger})</li>
 *   <li>杀手 / killer team → 2 抽 ({@code win_killer})</li>
 *   <li>中立 / custom / independent → 5 抽 ({@code win_neutral})</li>
 * </ul>
 *
 * <h2>本类的审核约定（B-08 / B-09 / B-10 / B-11 / B-18）</h2>
 * <ul>
 *   <li><b>B-08</b>：{@code winners()} 与 {@code participants()} <b>同时为空</b>的 settlement
 *       是 core 注册表模式（{@code GameModeRegistry#stop}）的产物，只带模式 ID 与结果原因，
 *       按 core 契约<b>不得</b>据此发奖——在处理器最开头直接 return。</li>
 *   <li><b>B-09</b>：对局幂等不再只靠 64 条 {@code recentGrants} LRU。每个玩家额外持久化
 *       {@link PlayerLotteryData#recentSettledMatches}（对局维度、cap 32），
 *       因此长会话挤爆 LRU 之后重放的 ROUND_ENDED 也不会重复发奖。</li>
 *   <li><b>B-10</b>：「已结算」标记只在<b>该玩家奖励确实入账之后</b>才写；
 *       标记与奖励由同一次原子 JSON 写入落盘（见 B-11），不存在「标记烧掉、奖励没发」的状态。</li>
 *   <li><b>B-11</b>：整批发奖包在 {@link PlayerLotteryStore#beginDeferredFlush()} /
 *       {@link PlayerLotteryStore#endDeferredFlush()} 之间，批内不做任何 fsync；
 *       随后在<b>下一个 tick</b> 统一 {@link PlayerLotteryStore#flushAll()} 一次。</li>
 *   <li><b>B-18</b>：未知模式不再「只跳过参与奖、照发胜利奖」。模式只解析一次，
 *       参与奖与胜利奖共用同一个模式串，因此 {@code RatesConfig} 的倍率查表也共用同一项；
 *       未知模式只 WARN 一次并使用文档化默认倍率 1.0。</li>
 * </ul>
 */
public final class GrantEventHooks {
    private static final java.util.concurrent.atomic.AtomicBoolean REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 审核 B-09：内存「已结算对局」快路径的容量（对局键唯一，见 {@link PlayerLotteryData}）。 */
    private static final int SETTLED_MATCH_CAP = 32;

    /**
     * 审核 B-09：同一 matchKey 只结算一次的<b>内存快路径</b>（跨玩家、有界）。
     *
     * <p>权威判据是每个玩家档案里的 {@link PlayerLotteryData#recentSettledMatches}
     * （durable，重启后仍有效）；这里只省掉「重放时把每个玩家档案都从磁盘读一遍」的开销。
     * 只有在<b>所有</b>收款人本局都处理完（没有异常）时才写入。</p>
     */
    private static final Set<String> SETTLED_MATCH_KEYS =
            Collections.synchronizedSet(new LinkedHashSet<String>(SETTLED_MATCH_CAP + 4) {
                @Override
                public boolean add(String key) {
                    boolean added = super.add(key);
                    while (size() > SETTLED_MATCH_CAP) {
                        var it = iterator();
                        if (!it.hasNext()) {
                            break;
                        }
                        it.next();
                        it.remove();
                    }
                    return added;
                }
            });

    /** 审核 B-18：未知模式只 WARN 一次，避免每局刷屏。 */
    private static final Set<String> WARNED_UNKNOWN_MODES = ConcurrentHashMap.newKeySet();

    public enum WinFaction {
        PASSENGER("win_passenger"),
        KILLER("win_killer"),
        NEUTRAL("win_neutral");

        public final String eventId;

        WinFaction(String eventId) {
            this.eventId = eventId;
        }

        static WinFaction from(MatchWinFaction faction) {
            if (faction == null) {
                return null;
            }
            return switch (faction) {
                case KILLER -> KILLER;
                case NEUTRAL -> NEUTRAL;
                case PASSENGER -> PASSENGER;
            };
        }
    }

    private GrantEventHooks() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            HabiLotteryMod.LOGGER.debug("GrantEventHooks.register skipped (already registered)");
            return;
        }
        try {
            MatchEvents.ROUND_ENDED.register(GrantEventHooks::onRoundEnded);
            HabiLotteryMod.LOGGER.info("Registered MatchEvents.ROUND_ENDED grant hooks");
        } catch (Throwable t) {
            REGISTERED.set(false);
            HabiLotteryMod.LOGGER.warn("MatchEvents.ROUND_ENDED hook unavailable: {}", t.toString());
        }
    }

    private static void onRoundEnded(ServerLevel level, MatchSettlement settlement) {
        if (level == null || settlement == null) {
            return;
        }
        // 审核 B-08 —— 引用 core 契约：
        // MatchEvents javadoc「覆盖范围（审核 A7）」与 GameModeRegistry#stop 的注释都写明，
        // 经 GameModeRegistry 启动/停止（非上游 SRE 原生结束）的对局，其 settlement
        // 「只携带模式 ID 与结果原因，winners/participants 均为空集——请用它做跨局状态重置，
        // 不要据此发奖」。
        // 因此这里在<b>最早处</b>短路：既不做去重记账，也不做任何 grant，
        // 更不会把这种空集 settlement 的 matchKey 写进「已结算」集合（否则会毒化后续判定）。
        if (settlement.winners().isEmpty() && settlement.participants().isEmpty()) {
            HabiLotteryMod.LOGGER.info(
                    "Skip grants: registry-mode settlement carries no award data "
                            + "(winners and participants are both empty) match={} mode={} winKind={} — "
                            + "core MatchEvents / GameModeRegistry#stop contract",
                    settlement.matchKey(), settlement.modeId(), settlement.winKind());
            return;
        }
        if (!PlayerLotteryStore.get().isTakeoverActive()) {
            HabiLotteryMod.LOGGER.warn("Skip grants: lottery takeover inactive match={}", settlement.matchKey());
            return;
        }
        grantRound(level, settlement);
    }

    /**
     * 一局结算的发奖批处理（审核 B-09 / B-10 / B-11）。
     *
     * <p>顺序：幂等检查 → {@code beginDeferredFlush} → 参与奖 → 胜利奖 →
     * {@code endDeferredFlush} → 标记内存「已结算」→ 下一个 tick 统一落盘。</p>
     */
    private static void grantRound(ServerLevel level, MatchSettlement settlement) {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        String matchKey = settlement.matchKey();
        // 空 matchKey 无法做幂等（键必须是唯一的对局指纹）。宁可不写标记也不能把空串写进集合，
        // 否则之后所有「空 key 的结算」都会被判成重复而全部不发奖。core 的工厂永远给非空 key。
        boolean durable = matchKey != null && !matchKey.isBlank();
        if (!durable) {
            HabiLotteryMod.LOGGER.warn(
                    "ROUND_ENDED without a usable matchKey (blank): idempotency unavailable, "
                            + "awarding anyway mode={}", settlement.modeId());
        } else if (SETTLED_MATCH_KEYS.contains(matchKey)) {
            HabiLotteryMod.LOGGER.info("Skip duplicate ROUND_ENDED matchKey={} (already settled)", matchKey);
            return;
        }

        // 审核 B-18：模式（= 倍率查表的键）只解析一次，参与奖与胜利奖共用同一个值。
        String mode = resolveGrantMode(settlement.modeId());

        RoundGrantResult result = new RoundGrantResult();
        boolean deferred = false;
        try {
            // 审核 B-11：批内所有 store.flush(uuid) 都被 store 的 deferred 标记短路
            // （返回 true 且不写盘），因此这里不会有 N 次主线程 fsync。
            store.beginDeferredFlush();
            deferred = true;
            grantParticipation(store, level, settlement, mode, durable, result);
            grantFactionWins(store, level, settlement, mode, durable, result);
        } catch (Throwable t) {
            result.aborted = true;
            HabiLotteryMod.LOGGER.error(
                    "Round grant batch failed for match={} mode={}; recipients already paid keep their "
                            + "recorded marker and the rest stay retryable", matchKey, mode, t);
        } finally {
            if (deferred) {
                store.endDeferredFlush();
            }
        }

        if (durable && result.allHandled()) {
            SETTLED_MATCH_KEYS.add(matchKey);
        }
        if (result.recipients > 0 || result.aborted) {
            HabiLotteryMod.LOGGER.info(
                    "Round grants done: match={} mode={} recipients={} granted={} unpaid={} aborted={} "
                            + "(deferred flush scheduled)",
                    matchKey, mode, result.recipients, result.granted, result.unpaid, result.aborted);
        }
        // 审核 B-11：离开 finalizeGame 之后（下一 tick）统一落盘一次。
        scheduleDeferredFlush(level);
    }

    /**
     * 参与奖（审核 B-18：未知模式不再被单独跳过）。
     *
     * <p>旧实现在 {@code classified == "unknown"} 时<b>硬编码</b>跳过参与奖却照发胜利奖，
     * 于是同一个未知模式「一边不发、一边按默认倍率发」。现在两边共用 {@code mode}：
     * 未知模式只 WARN 一次，倍率统一走 {@code RatesConfig.modeMultiplier} 的文档化默认值 1.0，
     * 是否真的发由 {@code grants.json} 里各事件自己的 {@code modes} 过滤决定（配置是唯一开关）。</p>
     */
    private static void grantParticipation(
            PlayerLotteryStore store,
            ServerLevel level,
            MatchSettlement settlement,
            String mode,
            boolean durable,
            RoundGrantResult result) {
        Set<UUID> matchPlayers = participateSet(level, settlement);
        String participateEvent = GameModeIds.isBlackout(mode) ? "blackout_participate" : "sre_participate";
        String matchKey = settlement.matchKey();
        for (UUID id : matchPlayers) {
            if (durable && isPlayerSettled(store, id, matchKey)) {
                HabiLotteryMod.LOGGER.debug(
                        "Skip participate grant for {} match={} (already settled)", id, matchKey);
                continue;
            }
            result.recipients++;
            if (grantOne(store, id, participateEvent, mode, matchKey)) {
                result.granted++;
                if (durable) {
                    markPlayerSettled(store, id, matchKey);
                }
            } else {
                result.unpaid++;
            }
        }
    }

    /**
     * 单个收款人的一次 grant，返回「奖励是否真的写进内存账本」。
     *
     * <p>审核 B-10：{@link LotteryGrantService#grantEvent} 没有返回值，因此在调用前后对比
     * {@code lootChance}：配置里没有该 event / event 被禁用 / 该模式不匹配 /
     * 金额算出来是 0 / 该 grant key 已消费过 / 玩家存档不可读 —— 这些情况账本不变。
     * 只有真的加上了抽数才返回 {@code true}，调用方（也<b>只有</b>那时）才写「本局已结算」标记，
     * 因此失败的玩家保持可重试。</p>
     *
     * <p>注意：本方法只对<b>内存账本</b>负责。批内的落盘由 deferred flush 统一做，
     * 标记与奖励在同一次原子 JSON 写入里落盘，所以不存在「标记已烧、奖励没落」的中间态。</p>
     */
    private static boolean grantOne(
            PlayerLotteryStore store, UUID id, String eventId, String mode, String grantKey) {
        if (id == null) {
            return false;
        }
        int before = store.getLootChance(id);
        LotteryGrantService.grantEvent(id, eventId, mode, grantKey);
        int after = store.getLootChance(id);
        return after != before;
    }

    /** 该玩家是否已经结算过这个对局范围键（参与奖 {@code matchKey} / 胜利奖 {@code matchKey:win}）。 */
    private static boolean isPlayerSettled(PlayerLotteryStore store, UUID id, String settleKey) {
        return PlayerLotteryData.hasSettledMatch(store.getOrLoad(id), settleKey);
    }

    /**
     * 审核 B-09 / B-10：奖励已入账之后才写 durable 标记。
     *
     * <p>标记与 {@code lootChance} 同属一个 {@link PlayerLotteryData}，
     * 由同一个原子 JSON 写入落盘；{@link PlayerLotteryStore#update} 会置 dirty，
     * 所以即便随后进程退出，停服路径（{@code onServerStopping → flushAll}）也会把它写下去。</p>
     */
    private static void markPlayerSettled(PlayerLotteryStore store, UUID id, String settleKey) {
        if (id == null || settleKey == null || settleKey.isBlank()) {
            return;
        }
        store.update(id, data -> PlayerLotteryData.markSettledMatch(data, settleKey));
    }

    /**
     * 审核 B-11：把落盘挪出 {@code finalizeGame}。
     *
     * <p>批处理期间 {@code beginDeferredFlush()} 已经让每个玩家的 {@code store.flush(uuid)}
     * 直接返回（不 fsync）；这里在<b>下一个 server tick</b> 调一次
     * {@link PlayerLotteryStore#flushAll()}，把这一批脏玩家一次写完。</p>
     *
     * <p><b>落盘保证</b>（不依赖本方法也能落地）：脏标记留在 {@link PlayerLotteryStore} 里，
     * {@code PlayerLotteryStore.onServerStopping()}（{@code HabiLotteryMod} 的
     * {@code SERVER_STOPPING} 处理器调用）会 {@code flushAll()}，玩家退出走
     * {@code onPlayerQuit() → flush(uuid)}；管理员手动备份也会 {@code flushAll()}。
     * 因此即使这个下一 tick 任务在停服过程中被丢弃，数据仍然会被停服路径写盘。</p>
     */
    private static void scheduleDeferredFlush(ServerLevel level) {
        PlayerLotteryStore store = PlayerLotteryStore.get();
        MinecraftServer server = level != null ? level.getServer() : HabiLotteryMod.getServer();
        Runnable flush = () -> {
            try {
                if (!store.flushAll()) {
                    HabiLotteryMod.LOGGER.error(
                            "Deferred round-end lottery flush reported failures; dirty entries stay "
                                    + "marked for the next flush (quit/stop/backup)");
                }
            } catch (Throwable t) {
                HabiLotteryMod.LOGGER.error("Deferred round-end lottery flush threw", t);
            }
        };
        if (server == null) {
            // 没有 server 句柄就没有「下一个 tick」可用：退化成同步批量落盘，正确性优先。
            HabiLotteryMod.LOGGER.debug("No server handle for deferred flush; flushing inline");
            flush.run();
            return;
        }
        try {
            server.execute(flush);
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn(
                    "Cannot schedule deferred lottery flush ({}); flushing inline", t.toString());
            flush.run();
        }
    }

    /** 一次结算批处理的聚合结果（审核 B-10 要求的「全部收款人都发成功」判据）。 */
    static final class RoundGrantResult {
        /** 需要发奖的收款人次（不含已带标记而跳过的）。 */
        int recipients;
        /** 奖励确实入账的收款人次。 */
        int granted;
        /** 走到了 grant 但没有入账（配置禁用 / 模式过滤 / 额度为 0 / key 已消费 / 存档不可读）。 */
        int unpaid;
        /** 批处理抛异常（剩余收款人未被尝试）。 */
        boolean aborted;

        /** @return 所有收款人都被处理过（没有异常中断），可以记「本局已结算」 */
        boolean allHandled() {
            return !aborted && granted + unpaid == recipients;
        }
    }

    /**
     * 审核 B-18：把结算模式解析成<b>唯一</b>一个用于发奖的模式串，参与奖与胜利奖共用它。
     *
     * <ul>
     *   <li>可识别模式 → 规范短 id（{@code blackout} / {@code murder} / {@code repair}），
     *       于是 {@code RatesConfig.modeMultipliers} 里配置的倍率对<b>两类</b>奖励同时生效。</li>
     *   <li>不可识别模式 → 原始 {@code modeId}（保留管理员在 {@code grants.json} 的
     *       {@code modes} 里按原始 id 登记的机会），WARN 一次，倍率按
     *       {@code RatesConfig.modeMultiplier(String)} 的文档化默认值 {@code 1.0}，
     *       <b>参与奖与胜利奖都</b>按这个默认值走——不再只跳过参与奖。</li>
     * </ul>
     *
     * <p>倍率算术仍在 {@link LotteryGrantService#grantEvent} 内完成（那里才有 event.amount），
     * 本方法保证的是「两边解析出的是同一个键」，因此不可能出现两边用不同倍率的情形。</p>
     */
    static String resolveGrantMode(String modeId) {
        String classified = classifyModeId(modeId);
        if (!"unknown".equals(classified)) {
            return classified;
        }
        String raw = modeId == null || modeId.isBlank() ? "unknown" : modeId.trim();
        if (WARNED_UNKNOWN_MODES.add(raw)) {
            HabiLotteryMod.LOGGER.warn(
                    "Unknown game mode '{}' has no rates config entry: participate and win grants both use "
                            + "the documented default multiplier 1.0; whether an event pays is decided only by "
                            + "its own modes filter in grants.json",
                    raw);
        }
        return raw;
    }

    /**
     * Classify a settlement / CCA mode id into short lottery mode names.
     * Unknown must not default to murder (wrong participate event).
     */
    static String classifyModeId(String modeId) {
        String fromNames = detectModeFromNames(modeId, modeId);
        if (fromNames != null) {
            return fromNames;
        }
        if (modeId != null) {
            if (GameModeIds.isBlackout(modeId)) {
                return "blackout";
            }
            if (GameModeIds.isRepair(modeId)) {
                return "repair";
            }
            if (GameModeIds.isMurder(modeId)) {
                return "murder";
            }
        }
        return fallbackMode(false);
    }

    /** @return mode id, or {@code null} when names do not identify a known mode */
    static String detectModeFromNames(String classSimpleName, String idString) {
        if (classSimpleName != null) {
            String name = classSimpleName.toLowerCase();
            if (name.contains("blackout")) {
                return "blackout";
            }
            if (name.contains("repair")) {
                return "repair";
            }
            if (name.contains("murder") || name.contains("kill")) {
                return "murder";
            }
        }
        if (idString != null && idString != classSimpleName) {
            String lower = idString.toLowerCase();
            if (lower.contains("blackout")) {
                return "blackout";
            }
            if (lower.contains("repair")) {
                return "repair";
            }
            if (lower.contains("murder") || lower.contains("kill")) {
                return "murder";
            }
        }
        return null;
    }

    static String fallbackMode(boolean blackoutRoundKnown) {
        return blackoutRoundKnown ? "blackout" : "unknown";
    }

    /** Durable match key: dimension + start world tick + start millis (not a process SEQ). */
    static String newMatchKey(String dimensionLocation, long startWorldTick, long startMillis) {
        return formatRoundFingerprint(dimensionLocation, startWorldTick, startMillis);
    }

    static String formatRoundFingerprint(String dimensionLocation, long startWorldTick, long startMillis) {
        String dim = dimensionLocation == null || dimensionLocation.isBlank() ? "unknown" : dimensionLocation;
        return dim + "|" + startWorldTick + "|" + startMillis;
    }

    private static void grantFactionWins(
            PlayerLotteryStore store,
            ServerLevel level,
            MatchSettlement settlement,
            String mode,
            boolean durable,
            RoundGrantResult result) {
        Set<UUID> winners = settlement.winners();
        String matchKey = settlement.matchKey();
        if (winners.isEmpty()) {
            HabiLotteryMod.LOGGER.info(
                    "No winners for match {} mode={} winKind={}", matchKey, mode, settlement.winKind());
            return;
        }
        // 审核 B-09/B-10：胜利奖用独立范围键（matchKey:win），否则先拿参与奖的玩家会被
        // 「本局已结算」标记误挡掉胜利奖。
        String settleKey = matchKey + ":win";
        int skipped = 0;
        int paidWins = 0;
        for (UUID id : winners) {
            if (isUnassignedForWinGrant(id, level, settlement)) {
                HabiLotteryMod.LOGGER.info(
                        "Skip win grant for {} (no role / no blackout faction)", id);
                continue;
            }
            WinFaction faction = classifyWinFaction(id, level, settlement);
            if (faction == null) {
                HabiLotteryMod.LOGGER.info(
                        "Skip win grant for {} (unclassified faction)", id);
                continue;
            }
            if (durable && isPlayerSettled(store, id, settleKey)) {
                skipped++;
                HabiLotteryMod.LOGGER.debug(
                        "Skip win grant for {} match={} (already settled)", id, matchKey);
                continue;
            }
            result.recipients++;
            if (!grantOne(store, id, faction.eventId, mode, settleKey)) {
                result.unpaid++;
                continue;
            }
            result.granted++;
            paidWins++;
            if (durable) {
                markPlayerSettled(store, id, settleKey);
            }
            ServerPlayer online = findOnline(level, id);
            HabiLotteryMod.LOGGER.info(
                    "Win grant {} -> {} ({}) match={} mode={} winKind={}",
                    faction.eventId,
                    online != null ? online.getGameProfile().getName() : id,
                    faction.name().toLowerCase(),
                    matchKey,
                    mode,
                    settlement.winKind());
        }
        HabiLotteryMod.LOGGER.info(
                "Faction win grants done: match={} mode={} winKind={} winners={} grantedWins={} skippedSettled={}",
                matchKey, mode, settlement.winKind(), winners.size(), paidWins, skipped);
    }

    /**
     * Prefer 入局 UUID (blackout faction snapshot ∪ assigned roles). {@code settlement.participants()}
     * may still contain roundEnd spectators and is only the fallback when both sets are empty.
     */
    static Set<UUID> participateSet(ServerLevel level, MatchSettlement settlement) {
        Set<UUID> roundEnd = settlement == null ? Set.of() : settlement.participants();
        Set<UUID> history = new LinkedHashSet<>();
        Set<UUID> assigned = new LinkedHashSet<>();
        collectHistoryAndAssigned(level, settlement, history, assigned);
        return GrantRecipients.matchParticipants(roundEnd, history, assigned);
    }

    static boolean isUnassignedForWinGrant(UUID id, ServerLevel level, MatchSettlement settlement) {
        return WinFactionRules.isUnassignedForWinGrant(
                hasAssignedRole(id, level),
                hasBlackoutFactionHistory(id, settlement));
    }

    private static void collectHistoryAndAssigned(
            ServerLevel level, MatchSettlement settlement, Set<UUID> history, Set<UUID> assigned) {
        if (settlement != null && GameModeIds.isBlackout(settlement.modeId())) {
            // core 已移除 BlackoutRoleManager；停电局的历史参与者直接取自结算快照的阵营表。
            history.addAll(settlement.factions().keySet());
        }
        if (level == null) {
            return;
        }
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game != null && game.getRoles() != null) {
                assigned.addAll(game.getRoles().keySet());
            }
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.debug("game role map unavailable: {}", t.toString());
        }
    }

    /**
     * Blackout uses the core settlement faction snapshot; murder uses the
     * ended-round frozen {@link EffectiveRoleProfile}, not live {@code SRERole} flags.
     *
     * <p>2.0.11: {@code com.habitrain.core.game.blackout.BlackoutRoleManager} no longer
     * exists, so the blackout branch reads {@link MatchSettlement#factionOfOrEmpty(UUID)}.</p>
     */
    static WinFaction classifyWinFaction(UUID id, ServerLevel level, MatchSettlement settlement) {
        if (id == null) {
            return null;
        }
        String mode = settlement == null ? null : settlement.modeId();
        if (GameModeIds.isBlackout(mode)) {
            WinFaction blackout = classifyBlackout(id, settlement);
            if (blackout != null) {
                return blackout;
            }
        }
        SRERole raw = assignedRole(id, level);
        return classifyFromCatalog(raw, GrantRoleSnapshot.endedProfile(raw).orElse(null));
    }

    static WinFaction classifyFromCatalog(SRERole raw, EffectiveRoleProfile profile) {
        boolean customWinner = raw instanceof CustomWinnerRole;
        if (profile != null) {
            return WinFactionRules.fromProfile(profile, customWinner);
        }
        if (raw == null) {
            return null;
        }
        return WinFactionRules.fromRoleFlags(
                raw.isNeutrals(),
                customWinner,
                raw.isNeutralForInnocent(),
                raw.isNeutralForKiller(),
                raw.isKiller(),
                raw.isKillerTeam(),
                raw.canUseKiller(),
                raw.isMafiaTeam());
    }

    /**
     * 停电局的结算阵营直接来自 core 的 {@link MatchSettlement} 快照
     * （{@code BlackoutRoleManager} 已在 core 2.0.8 前后移除，2.0.11 起官方口径就是
     * {@code MatchSettlement.factions()}）。不在快照里的玩家返回 {@code null}，
     * 交由上游角色路径判定。
     */
    private static WinFaction classifyBlackout(UUID id, MatchSettlement settlement) {
        if (settlement == null) {
            return null;
        }
        return settlement.factionOfOrEmpty(id).map(WinFaction::from).orElse(null);
    }

    private static boolean hasAssignedRole(UUID id, ServerLevel level) {
        return assignedRole(id, level) != null;
    }

    private static SRERole assignedRole(UUID id, ServerLevel level) {
        if (id == null || level == null) {
            return null;
        }
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            if (game == null) {
                return null;
            }
            return game.getRole(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 停电局阵营历史：以结算快照的阵营表为准（core 已移除 BlackoutRoleManager）。 */
    private static boolean hasBlackoutFactionHistory(UUID id, MatchSettlement settlement) {
        if (id == null || settlement == null) {
            return false;
        }
        return settlement.factionOfOrEmpty(id).isPresent();
    }

    private static ServerPlayer findOnline(ServerLevel level, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            MinecraftServer server = level != null ? level.getServer() : HabiLotteryMod.getServer();
            if (server == null) {
                return null;
            }
            return server.getPlayerList().getPlayer(uuid);
        } catch (Throwable t) {
            return null;
        }
    }
}
