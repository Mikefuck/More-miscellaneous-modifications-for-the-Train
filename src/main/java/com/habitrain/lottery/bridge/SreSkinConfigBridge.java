package com.habitrain.lottery.bridge;

import com.habitrain.lottery.HabiLotteryMod;

/**
 * Ensures StarRailExpress item skin sync flags are properly enabled on server start
 * so that unlocked skins are included in CCA network sync packets (isItemSkinManagementEnabled=true)
 * without enabling remote MySQL sync.
 *
 * <h2>审核 N-03：写入时机晚于消费者</h2>
 * <p>{@link #ensureServerFlags()} 目前从抽奖模组自己的 {@code SERVER_STARTED} 调用
 * （见 {@code HabiLotteryMod}），但上游 SRE 在<b>它自己的</b> {@code SERVER_STARTED}
 * （{@code SkinsNetworkSyncInitializer}）里就已经读过这些字段；Fabric 按注册顺序分发，
 * SRE 的监听器先执行。{@code SREConfig} 是单例，因此<b>每个 JVM 的首次启动</b>里
 * 这次写入不生效，只有后续启动才有效。
 *
 * <p>当前该问题被本模组抑制皮肤 MySQL 同步的 mixin 掩盖，但「启用皮肤管理 / 关闭远程同步」
 * 的意图在首次启动确实没有达成。
 *
 * <h2>本次修复做了什么 / 还需要什么</h2>
 * <ul>
 *   <li>{@link #ensureServerFlags()} 现在<b>幂等</b>，并且只在<b>真的改变了</b>某个字段时
 *       打印 INFO（列出字段名），使「首次启动写入其实是空操作」这件事变成可观测事实
 *       （审核 N-04 要求的可观测点）。</li>
 *   <li>新增 {@link #reapplyAfterConsumers()}：语义与 {@link #ensureServerFlags()} 相同的
 *       重复施加入口。<b>调用方需要把本模组的 {@code SERVER_STARTED} 注册顺序提前</b>
 *       （或在本方法之后让 SRE 重新读取），该改动位于 {@code HabiLotteryMod}，不在本文件范围内，
 *       因此在这里显式留出入口，而不是偷偷改时序。</li>
 * </ul>
 *
 * <h2>按文件核实的时序事实（供 lead 接线，2026-xx 核对 jar star_rail_express-4.3.0）</h2>
 * <ul>
 *   <li>本模组目前<b>只有一处</b>调用：{@code HabiLotteryMod} 的 {@code SERVER_STARTED}
 *       （{@code ensureServerFlags()}）。{@code onInitialize()} 阶段<b>没有任何</b>提前调用，
 *       所以「ModInitializer 期就写」这条更早的安全时机目前是空的，必须由 lead 在
 *       {@code HabiLotteryMod.onInitialize()} 里加一行 {@code SreSkinConfigBridge.ensureServerFlags();}
 *       （模组初始化早于任何 {@code SERVER_STARTED} 分发）。</li>
 *   <li>{@code isItemSkinEnabled}/{@code isItemSkinManagementEnabled} 只在
 *       {@code SREPlayerSkinsComponent} 写同步包/NBT 时惰性读取，因此对本模组
 *       {@code SERVER_STARTED} 的写入已经有效；真正受首次启动时序影响的是
 *       {@code itemSkinSyncServerEnabled}——{@code SkinsNetworkSyncInitializer.SERVER_STARTED}
 *       会用它算一次 {@code isEnabled}。</li>
 *   <li>该 {@code isEnabled} 只在上游 {@code SERVER_STARTED} 里计算一次，<b>事后</b>用
 *       {@link #reapplyAfterConsumers()} 改字段<b>不会</b>重算它；要彻底关掉 SRE 的皮肤 MySQL
 *       同步，必须靠上面的「模组初始化期提前调用」，而不是靠晚到的 re-apply。</li>
 *   <li>字段值是内存态、不落盘；管理员 {@code /sre config reload} 会重新读文件，晚到的
 *       re-apply（或下一次启动）会再恢复强制的值。</li>
 * </ul>
 */
public final class SreSkinConfigBridge {

    /** 已确认写入生效的字段名，仅用于日志去重。 */
    private static final java.util.Set<String> APPLIED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private SreSkinConfigBridge() {
    }

    /** 确保 SRE 皮肤同步标志正确（幂等；可在任意启动阶段重复调用）。 */
    public static void ensureServerFlags() {
        apply(false);
    }

    /**
     * 审核 N-03：在所有消费者（上游 SRE 的 {@code SERVER_STARTED}）已经跑完之后，
     * 再强制重算一次这些标志。
     *
     * <p>与 {@link #ensureServerFlags()} 的唯一区别是日志级别：本入口存在的意义就是
     * 「时序上晚到的补救」，因此把「本次是否真的改变了字段」的结果打成 INFO/WARN，
     * 便于在日志里确认补救是否奏效。
     */
    public static void reapplyAfterConsumers() {
        apply(true);
    }

    private static void apply(boolean late) {
        try {
            Class<?> cls = Class.forName("io.wifi.starrailexpress.SREConfig");
            Object config = cls.getMethod("instance").invoke(null);
            java.util.List<String> changed = new java.util.ArrayList<>(3);
            changed.addAll(setFlag(cls, config, "isItemSkinEnabled", true));
            changed.addAll(setFlag(cls, config, "isItemSkinManagementEnabled", true));
            changed.addAll(setFlag(cls, config, "itemSkinSyncServerEnabled", false));
            if (changed.isEmpty()) {
                HabiLotteryMod.LOGGER.debug(
                        "[habitrain_lottery] SRE skin sync flags already correct (late={})", late);
                return;
            }
            for (String name : changed) {
                APPLIED.add(name);
            }
            HabiLotteryMod.LOGGER.info(
                    "[habitrain_lottery] SRE skin sync flags applied (late={}): {} "
                            + "— if this only ever appears on the 2nd JVM start, the ordering problem "
                            + "described in audit N-03 is still present",
                    late, changed);
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("Failed ensuring SRE skin sync flags: {}", t.toString());
        }
    }

    /** 只在值真的不同时写入，返回被改动的字段名（空列表表示本来就是对的）。 */
    private static java.util.List<String> setFlag(Class<?> cls, Object config, String field, boolean value) {
        try {
            java.lang.reflect.Field f = cls.getField(field);
            if (f.getBoolean(config) == value) {
                return java.util.List.of();
            }
            f.setBoolean(config, value);
            return java.util.List.of(field);
        } catch (NoSuchFieldException e) {
            // SRE 改了字段名：只记 debug，不要刷屏也不要抛。
            HabiLotteryMod.LOGGER.debug("SREConfig has no boolean field '{}' (upstream changed?)", field);
            return java.util.List.of();
        } catch (Throwable t) {
            HabiLotteryMod.LOGGER.warn("Failed setting SREConfig.{}: {}", field, t.toString());
            return java.util.List.of();
        }
    }

    /** 诊断：已经确认写入生效过的字段名（只读快照）。 */
    public static java.util.Set<String> appliedFlagNames() {
        return java.util.Set.copyOf(APPLIED);
    }
}
