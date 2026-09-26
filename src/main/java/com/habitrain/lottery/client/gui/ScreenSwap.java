package com.habitrain.lottery.client.gui;

/**
 * 「仓库 ↔ 开箱」之间共用的转场规范，也是整个模组里唯一的转场语汇来源。
 *
 * <p>参考视频里只存在两种转场手法，本类把它们固化下来，两个界面都必须遵守：</p>
 *
 * <ol>
 *   <li><b>屏幕之间的切换是硬切</b>：仓库格栅 → 三维开箱场景发生在 f041→f042，
 *       时长 0ms，没有白光、没有淡出、没有缩放、没有划像（实测整帧平均亮度
 *       75.8 → 50.9，危险黄像素从 160467 掉到 29229，是纯粹的一帧替换）。
 *       因此 {@link #DEPART_MS} = 0，{@link #bridge} 在屏幕切换方向恒为 0。</li>
 *   <li><b>硬切之后的「合焦」是到达动画</b>：f042→f058 的 533ms 里，失焦半径
 *       从约 24px 收到约 4px，同时 f050→f060 顶部 HUD 淡入。这套动作由
 *       {@link #arrive}／{@link #defocus}／{@link #arriveFade} 描述，
 *       <b>两个方向共用</b>：从仓库进开箱是合焦，从开箱回仓库是同一组曲线
 *       驱动仓库自身的落位，所以来回切换的风格是一致的。</li>
 * </ol>
 *
 * <p>另有一个只用于「界面内部内容互换」的暗场语汇（转盘 → 选中物品登场，
 * f343→f348）：一帧撤掉旧内容 → 约 100ms 近黑暗场 → 133ms 淡回，
 * 见 {@link #contentBridge}。参考视频全程没有任何白光或泛光，最亮的一次
 * 明暗事件反而是这 100ms 的<b>变暗</b>，所以这里只用压暗、绝不往白色混。</p>
 *
 * <p>纯数学、无 Minecraft 依赖，便于 {@code ScreenSwapTest} 逐帧校验单调性与端点。</p>
 */
public final class ScreenSwap {

    // =====================================================================
    // 屏幕之间的切换：硬切 + 合焦到达
    // =====================================================================

    /** 离场时长。参考视频是 0ms 硬切，因此这里没有任何离场窗口。 */
    public static final long DEPART_MS = 0L;
    /** 合焦窗口：f042→f058 的 533ms。 */
    public static final long ARRIVE_MS = 533L;
    /** HUD 淡入窗口：f050→f060 的 333ms，落在合焦窗口的后半段。 */
    public static final long HUD_MS = 333L;
    /** 兼容旧调用点：HUD 开始淡入之前的那一段（合焦窗口的前 200ms）。 */
    public static final long HOLD_MS = ARRIVE_MS - HUD_MS;

    /** 到达瞬间画面略微放大，随合焦收回 1.0；这就是「拉焦」里那点位移感。 */
    public static final float ARRIVE_SCALE_FROM = 1.055F;
    public static final float ARRIVE_SCALE_TO = 1.0F;
    /**
     * 到达瞬间的失焦强度峰值。真正的高斯模糊由原版菜单背景模糊提供，
     * 这里只再叠一层同色薄雾把「还没对上焦」的那几帧压糊。
     */
    public static final float DEFOCUS_PEAK = 0.92F;

    /** 转场本身不带缩放（参考视频的硬切没有任何缩放）。 */
    public static final float DEPART_ZOOM = 1.0F, ARRIVE_ZOOM = ARRIVE_SCALE_TO;

    /** 暗场颜色：参考视频的暗场是近黑而不是纯黑，留一点层次。 */
    public static final int BRIDGE_COLOR = 0xFF06070A;

    // =====================================================================
    // 界面内部内容互换的暗场：一帧撤 → 100ms 全黑 → 133ms 淡回
    // =====================================================================

    /** 旧内容被撤掉、画面同时压黑所需的时间：参考视频只用了一帧。 */
    public static final long SWAP_REMOVE_MS = 33L;
    /** 保持近黑的时长。 */
    public static final long SWAP_HOLD_MS = 100L;
    /** 从近黑淡回的时长。 */
    public static final long SWAP_FADE_MS = 133L;
    /** 整段暗场的总时长：33 + 100 + 133 = 266ms。 */
    public static final long SWAP_MS = SWAP_REMOVE_MS + SWAP_HOLD_MS + SWAP_FADE_MS;
    /** 压黑开始到淡回开始之间的时长。 */
    public static final long SWAP_BLACK_MS = SWAP_REMOVE_MS + SWAP_HOLD_MS;

    private ScreenSwap() {
    }

    // ---------------------------------------------------------------------
    // 硬切
    // ---------------------------------------------------------------------

    /** 离场进度：硬切只有一个状态，请求离场即为 1。{@code at < 0} 表示没有请求离场。 */
    public static float depart(long now, long at) {
        return at < 0 ? 0.0F : 1.0F;
    }

    /** 入场（合焦）进度 0→1；{@code at < 0} 视为已经落位。 */
    public static float arrive(long now, long at) {
        return at < 0 ? 1.0F : CrateStage.clamp01((now - at) / (float) ARRIVE_MS);
    }

    /** 硬切没有离场窗口：请求之后外界只需等一帧就能安全换屏。 */
    public static boolean departing(long now, long at) {
        return false;
    }

    /** 是否仍在合焦窗口内（这段时间内界面控件尚未落位，应锁住输入）。 */
    public static boolean arriving(long now, long at) {
        return at >= 0 && now - at < ARRIVE_MS;
    }

    /** 离场是否已经可以换屏：硬切下请求离场即成立。 */
    public static boolean departed(long now, long at) {
        return at < 0 || now >= at;
    }

    /** 离场缩放：硬切不带缩放。 */
    public static float departZoom(float p) {
        return DEPART_ZOOM;
    }

    /** 离场位移：硬切不带位移。 */
    public static float departShift(float p) {
        return 0.0F;
    }

    /** 合焦期间的画面缩放：{@link #ARRIVE_SCALE_FROM} → 1.0，缓入缓出。 */
    public static float arriveZoom(float p) {
        return CrateStage.lerp(ARRIVE_SCALE_FROM, ARRIVE_SCALE_TO, CrateStage.easeInOutCubic(p));
    }

    /** 合焦期间的失焦强度：峰值 → 0，与 {@link #arrive} 用同一条曲线。 */
    public static float defocus(float p) {
        return DEFOCUS_PEAK * (1.0F - CrateStage.easeInOutCubic(CrateStage.clamp01(p)));
    }

    /** 界面内容的整体不透明度：合焦过半后才开始淡入，缓出。 */
    public static float arriveFade(float p) {
        float delay = HOLD_MS / (float) ARRIVE_MS;
        return CrateStage.easeOutCubic(CrateStage.clamp01((p - delay) / (1.0F - delay)));
    }

    /**
     * 屏幕之间的暗场强度。硬切是参考视频里唯一的换屏手法，因此这个方向恒为 0；
     * 保留本方法只是为了两个界面能共用同一套调用点，语义上永远「没有暗场」。
     */
    public static float bridge(long now, long departAt, long arriveAt) {
        return 0.0F;
    }

    // ---------------------------------------------------------------------
    // 界面内部内容互换
    // ---------------------------------------------------------------------

    /**
     * 暗场强度 0→1→0：{@code at} 之后的头 {@link #SWAP_BLACK_MS}（33 + 100ms）保持近乎全黑，
     * 再用 {@link #SWAP_FADE_MS} 淡回。旧内容应在 {@code at} 那一帧就被撤掉，
     * 于是玩家看到的正是参考视频里的「一帧闪黑 + 100ms 暗场 + 133ms 淡回」。
     *
     * <p>{@code at < 0}（还没有发生互换）返回 0，方便调用点无条件绘制。</p>
     */
    public static float contentBridge(long now, long at) {
        if (at < 0 || now < at) return 0.0F;
        long elapsed = now - at;
        if (elapsed < SWAP_BLACK_MS) return 1.0F;
        long fade = elapsed - SWAP_BLACK_MS;
        if (fade >= SWAP_FADE_MS) return 0.0F;
        return 1.0F - CrateStage.easeOutCubic(fade / (float) SWAP_FADE_MS);
    }

    /** 暗场开始淡回的时刻。 */
    public static long contentBridgeFadeAt(long at) {
        return at < 0 ? Long.MAX_VALUE : at + SWAP_BLACK_MS;
    }
}
