package com.habitrain.lottery.client.gui;

/**
 * 开箱动画的时间轴、运镜与卡片转盘的纯数学部分。
 *
 * <p>本类刻意不引用任何 Minecraft 类型，也不持有渲染状态：它只把「界面什么时候出现、
 * 玩家什么时候确认、服务端结果什么时候到」映射成一组 0→1 的进度与转盘坐标，
 * 因此可以在 JUnit 里逐帧验证（见 {@code CrateStageTest}）。
 * {@code CrateOpenScreen} 负责把这些进度画出来。</p>
 *
 * <h2>时间轴（全部来自参考视频的逐帧实测，不做压缩）</h2>
 *
 * <p>以「界面出现」为零点的入场段，对应参考视频 1.367s 处硬切之后的那几秒：</p>
 * <table>
 *   <tr><th>时刻</th><th>参考帧</th><th>动作</th></tr>
 *   <tr><td>0</td><td>f042</td><td>硬切进场，开始 533ms 合焦（失焦 24px → 4px）</td></tr>
 *   <tr><td>600</td><td>f060</td><td>箱子从画面上缘线性落下，400ms，约 1320px/s，无过冲</td></tr>
 *   <tr><td>733</td><td>f064</td><td>底部物品条淡入 + 上滑 28px，333ms 缓出</td></tr>
 *   <tr><td>1000</td><td>f072</td><td>箱子自 65° 侧倾、−40° 偏航扶正，900ms 缓出；镜头同时推近</td></tr>
 *   <tr><td>1266</td><td>f080</td><td>确认弹窗淡入，233ms 缓出，画在箱子之后</td></tr>
 * </table>
 *
 * <p>玩家确认之后以 {@code openedAt} 为零点的段落：</p>
 * <table>
 *   <tr><th>时刻</th><th>参考帧</th><th>动作</th></tr>
 *   <tr><td>0</td><td>f100</td><td>弹窗与物品条淡出 333ms；物品条同时 scaleX 1.0 → 0.72</td></tr>
 *   <tr><td>333</td><td>f110</td><td>箱子大字持握、盖子仍关闭，镜头继续推近到 1.75×</td></tr>
 *   <tr><td>1967</td><td>f159</td><td>开盖 + 转盘由黄线向两侧擦入 + 圆形暗角收拢；转盘以 7 件/秒起手</td></tr>
 *   <tr><td>1967→8000</td><td>f159→f340</td><td>三次速度曲线减速：{@code v(u)=V0(1−u)³+V1}，V0=7.0、V1=0.02</td></tr>
 *   <tr><td>8000</td><td>f340</td><td>速度归零，中奖卡片停在黄线正中，保持 100ms</td></tr>
 *   <tr><td>8100</td><td>f343</td><td>一帧撤卡 + 近黑暗场 100ms + 133ms 淡回（无白光）</td></tr>
 *   <tr><td>8366</td><td>f348</td><td>选中物品登场：0→1.52× 放大、偏航解开 25°，1533ms 缓出</td></tr>
 *   <tr><td>9899</td><td>f394</td><td>落位，之后只剩 ±4px／0.5Hz 的呼吸</td></tr>
 * </table>
 */
public final class CrateStage {

    /** 开箱流程所处的阶段。 */
    public enum Phase {
        /** 界面刚出现：箱子落下扶正、物品条与确认弹窗淡入，等待玩家确认。 */
        IDLE,
        /** 已确认：弹窗与物品条淡出。 */
        DISMISS,
        /** 箱子大字持握、盖子关闭，镜头继续推近。 */
        HOLD,
        /** 开盖、转盘擦入并滚动；结果到达后按三次曲线减速锁定。 */
        CAROUSEL,
        /** 卡片已撤、画面在近黑暗场里。 */
        BRIDGE,
        /** 选中物品登场放大展示。 */
        REVEAL,
        /** 本轮失败（缺钥匙、配额用尽等）。 */
        ERROR
    }

    // =====================================================================
    // 入场段（零点是界面出现，对应参考视频 1.367s）
    // =====================================================================

    /** 合焦窗口：与 {@link ScreenSwap#ARRIVE_MS} 同源。 */
    public static final long FOCUS_MS = ScreenSwap.ARRIVE_MS;
    /** 箱子开始下落的延迟（参考视频 1.967 − 1.367）。 */
    public static final long DROP_DELAY_MS = 600L;
    /** 下落时长，线性、无过冲。 */
    public static final long DROP_MS = 400L;
    /** 底部物品条开始淡入的延迟（2.100 − 1.367）。 */
    public static final long STRIP_DELAY_MS = 733L;
    /** 物品条淡入 + 上滑的时长。 */
    public static final long STRIP_MS = 333L;
    /** 扶正动作开始延迟（2.367 − 1.367）。 */
    public static final long RIGHT_DELAY_MS = 1000L;
    /** 扶正时长：65° 侧倾与 −40° 偏航一起收敛到 0。 */
    public static final long RIGHT_MS = 900L;
    /** 确认弹窗开始淡入的延迟（2.633 − 1.367）。 */
    public static final long MODAL_DELAY_MS = 1266L;
    /** 弹窗淡入时长。 */
    public static final long MODAL_MS = 233L;

    /** 起始侧倾角（度）。 */
    public static final float START_ROLL = 65.0F;
    /** 起始偏航角（度）。 */
    public static final float START_YAW = -40.0F;
    /** 底部物品条上滑的距离（像素，参考 1080p 实测）。 */
    public static final float STRIP_RISE_PX = 28.0F;
    /** 物品条退场时的横向压扁比例（pivot 在左端）。 */
    public static final float STRIP_COLLAPSE = 0.72F;

    // =====================================================================
    // 确认之后（零点是玩家按下确认，对应参考视频 3.300s）
    // =====================================================================

    /** 弹窗与物品条淡出的时长（f100→f110）。 */
    public static final long DISMISS_MS = 333L;
    /** 淡出之后到开盖之间的持握时长（3.633 → 5.267）。 */
    public static final long HOLD_MS = 1634L;
    /** 转盘减速锁定的时长（f159→f340，6.033s）。 */
    public static final long SPIN_MS = 6033L;
    /** 减速到零后保持的时长（f340→f343）。 */
    public static final long STOP_HOLD_MS = 100L;
    /** 暗场总时长（一帧撤卡 + 100ms 暗场 + 133ms 淡回）。 */
    public static final long BRIDGE_MS = ScreenSwap.SWAP_MS;
    /** 选中物品放大到位的时长（f348→f385）。 */
    public static final long REVEAL_MS = 1533L;

    /** 开盖时长：从 {@link #spinAt} 起算，与转盘擦入同时发生。 */
    public static final long LID_MS = 200L;
    /** 箱盖完全掀开的角度。 */
    public static final float LID_OPEN_DEGREES = 112.0F;
    /** 转盘由黄线向两侧擦入的时长。 */
    public static final long WIPE_MS = 167L;
    /** 圆形暗角收拢的时长。 */
    public static final long VIGNETTE_MS = 167L;

    /** 等待服务端结果的上限；超时后停下转盘，允许玩家重试。 */
    public static final long SPIN_TIMEOUT_MS = 12000L;

    // =====================================================================
    // 运镜（参考视频：箱子从 327px 推到 571px，再用圆形暗角框住）
    // =====================================================================

    /** 推镜开始延迟：与扶正同时开始。 */
    public static final long DOLLY_DELAY_MS = RIGHT_DELAY_MS;
    /** 推镜持续到参考视频 f130（4.333s），即入场后 2966ms。 */
    public static final long DOLLY_MS = 1966L;
    /** 箱子入场尺寸 327px → 571px = 1.75×。 */
    public static final float DOLLY_FROM = 1.0F, DOLLY_TO = 1.75F;
    /** 转盘阶段箱子退成背景时的缩放。 */
    public static final float BACKDROP_SCALE = 2.10F;
    /** 退成背景的过渡时长。 */
    public static final long BACKDROP_MS = 167L;

    // =====================================================================
    // 转盘
    // =====================================================================

    /** 转盘上的卡片数量。参考视频里一路滚了 10.7 张才停下，取 24 张足以避免图案重复。 */
    public static final int SLOTS = 24;
    /** 起手速度，单位为「卡片/毫秒」：参考视频实测 7.0 件/秒。 */
    public static final float REEL_SPEED = 0.0070F;
    /** 三次曲线的渐近速度：0.02 件/秒 = 2e-5 件/ms。 */
    public static final float REEL_TAIL_SPEED = 0.00002F;
    /** 光标两侧最多画到第几张卡片；圆形暗角内大约能看到 5 张。 */
    public static final float VISIBLE_SPAN = 3.0F;
    /** 卡片间距占卡片宽度的比例：参考视频相邻卡片几乎严丝合缝，只留一点点缝。 */
    /** 相邻卡片无缝相接；参考视频的井宽与 pitch 都是 360px。 */
    public static final float CARD_PITCH = 1.0F;
    /** 卡片宽占屏幕宽度的比例：参考 360/1920 = 0.1875。 */
    public static final float CARD_WIDTH_RATIO = 0.1875F;
    /** 卡片宽高比：参考 360×280。 */
    public static final float CARD_ASPECT = 280.0F / 360.0F;
    /** 卡片带中心占屏幕高度的比例：参考 505/1080。 */
    public static final float CARD_BAND_CENTER = 0.468F;
    /** 圆形暗角内径占屏幕高度的比例：参考 400/1080。 */
    public static final float VIGNETTE_INNER = 400.0F / 1080.0F;
    /** 圆形暗角外径占屏幕高度的比例：参考 460/1080。 */
    public static final float VIGNETTE_OUTER = 460.0F / 1080.0F;
    /** 选中物品的展示尺寸占屏幕高度的比例：参考约 0.31。 */
    public static final float REVEAL_SIZE_RATIO = 0.50F;
    /** 选中物品登场时解开的偏航角（度）。 */
    public static final float REVEAL_YAW = 25.0F;

    private final long openedAt;
    private final long resultAt;
    private final boolean failed;

    private CrateStage(long openedAt, long resultAt, boolean failed) {
        this.openedAt = openedAt;
        this.resultAt = resultAt;
        this.failed = failed;
    }

    public static CrateStage idle() { return new CrateStage(-1L, -1L, false); }

    /** 玩家确认开箱；{@code now} 之后进入弹窗退场。 */
    public static CrateStage opened(long now) { return new CrateStage(now, -1L, false); }

    /** 服务端结果到达；保留原始确认时间，动画不会因为网络抖动而重播。 */
    public CrateStage result(long now) {
        return new CrateStage(openedAt, now, false);
    }

    /** 本轮失败：保留时间轴，用于播完退场再走失败提示。 */
    public CrateStage failure() { return new CrateStage(openedAt, resultAt, true); }

    public boolean active() { return openedAt >= 0; }
    public boolean hasResult() { return resultAt >= 0; }
    public long openedAt() { return openedAt; }
    public long resultAt() { return resultAt; }

    // ---------------------------------------------------------------------
    // 关键时刻
    // ---------------------------------------------------------------------

    /** 弹窗与物品条淡出结束的时刻。 */
    public long holdAt() { return openedAt < 0 ? Long.MAX_VALUE : openedAt + DISMISS_MS; }

    /** 开盖 + 转盘擦入的时刻（参考 5.267s）。 */
    public long spinAt() { return openedAt < 0 ? Long.MAX_VALUE : holdAt() + HOLD_MS; }

    /** 转盘开始减速的时刻：结果到达之前保持匀速，因此不会出现「空转等待」。 */
    public long decayAt() {
        if (openedAt < 0 || resultAt < 0) return Long.MAX_VALUE;
        return Math.max(spinAt(), resultAt);
    }

    /** 速度归零的时刻（参考 11.300s）。 */
    public long stopAt() { return decayAt() == Long.MAX_VALUE ? Long.MAX_VALUE : decayAt() + SPIN_MS; }

    /** 开始插入暗场的时刻（参考 11.400s）。 */
    public long swapAt() { return stopAt() == Long.MAX_VALUE ? Long.MAX_VALUE : stopAt() + STOP_HOLD_MS; }

    /** 选中物品开始登场的时刻（参考 11.567s）。 */
    public long revealAt() { return swapAt() == Long.MAX_VALUE ? Long.MAX_VALUE : swapAt() + ScreenSwap.SWAP_BLACK_MS; }

    /** 整段动画结束的时刻。 */
    public long finishAt() { return revealAt() == Long.MAX_VALUE ? Long.MAX_VALUE : revealAt() + REVEAL_MS; }

    public Phase phase(long now) {
        if (openedAt < 0) return Phase.IDLE;
        if (failed && now >= holdAt()) return Phase.ERROR;
        if (now < openedAt + DISMISS_MS) return Phase.DISMISS;
        if (now < spinAt()) return Phase.HOLD;
        // 转盘一直算 CAROUSEL，包括速度归零后停在黄线上的那 100ms
        if (resultAt < 0 || now < swapAt()) return Phase.CAROUSEL;
        if (now < revealAt()) return Phase.BRIDGE;
        return Phase.REVEAL;
    }

    /** 失败是否已经可以显示提示。 */
    public boolean failedVisible(long now) { return failed && now >= holdAt(); }

    // ---------------------------------------------------------------------
    // 入场进度
    // ---------------------------------------------------------------------

    /** 箱子下落进度 0→1（线性，参考视频实测约 1320px/s 且无过冲）。 */
    public static float dropProgress(long now, long enteredAt) {
        return progress(now, enteredAt + DROP_DELAY_MS, DROP_MS);
    }

    /** 扶正进度 0→1（缓出：65°→45° 用 0.20s，最后 0.40s 才收到 0）。 */
    public static float righting(long now, long enteredAt) {
        return easeOutCubic(progress(now, enteredAt + RIGHT_DELAY_MS, RIGHT_MS));
    }

    /** 入场侧倾角（度）：{@link #START_ROLL} → 0。 */
    public static float entryRoll(long now, long enteredAt) {
        return START_ROLL * (1.0F - righting(now, enteredAt));
    }

    /** 入场偏航角（度）：{@link #START_YAW} → 0，与侧倾同时收敛。 */
    public static float entryYaw(long now, long enteredAt) {
        return START_YAW * (1.0F - righting(now, enteredAt));
    }

    /** 底部物品条淡入进度 0→1，缓出。 */
    public static float stripIn(long now, long enteredAt) {
        return easeOutCubic(progress(now, enteredAt + STRIP_DELAY_MS, STRIP_MS));
    }

    /** 确认弹窗淡入进度 0→1，缓出。 */
    public static float modalIn(long now, long enteredAt) {
        return easeOutCubic(progress(now, enteredAt + MODAL_DELAY_MS, MODAL_MS));
    }

    /** 物品条上滑的剩余像素：{@link #STRIP_RISE_PX} → 0。 */
    public static float stripOffset(long now, long enteredAt) {
        return STRIP_RISE_PX * (1.0F - stripIn(now, enteredAt));
    }

    // ---------------------------------------------------------------------
    // 确认之后的进度
    // ---------------------------------------------------------------------

    /** 弹窗与物品条淡出进度 0→1（线性，模型里直接当不透明度用）。 */
    public float dismiss(long now) {
        return openedAt < 0 ? 0.0F : progress(now, openedAt, DISMISS_MS);
    }

    /** 箱子大字持握进度 0→1；箱盖仍关闭，镜头继续推近。 */
    public float hold(long now) {
        return openedAt < 0 ? 0.0F : progress(now, holdAt(), HOLD_MS);
    }

    /** 箱盖翻转进度 0→1，从 {@link #spinAt} 起算，带一点过冲回弹。 */
    public float lid(long now) {
        if (openedAt < 0) return 0.0F;
        return easeOutBack(progress(now, spinAt(), LID_MS), 1.2F);
    }

    /** 箱盖角度（度）：0 关闭，约 {@link #LID_OPEN_DEGREES} 向后掀开。 */
    public float lidAngle(long now) {
        return LID_OPEN_DEGREES * lid(now);
    }

    /** 转盘由黄线向两侧擦入的进度 0→1。 */
    public float wipe(long now) {
        if (openedAt < 0) return 0.0F;
        return easeOutCubic(progress(now, spinAt(), WIPE_MS));
    }

    /** 圆形暗角收拢的进度 0→1。 */
    public float vignette(long now) {
        if (openedAt < 0) return 0.0F;
        return easeOutCubic(progress(now, spinAt(), VIGNETTE_MS));
    }

    /** 转盘减速锁定进度 0→1。 */
    public float settling(long now) {
        long decay = decayAt();
        return decay == Long.MAX_VALUE ? 0.0F : progress(now, decay, SPIN_MS);
    }

    /** 选中物品登场进度 0→1。 */
    public float revealing(long now) {
        long reveal = revealAt();
        return reveal == Long.MAX_VALUE ? 0.0F : progress(now, reveal, REVEAL_MS);
    }

    /** 暗场强度 0→1→0（一帧撤卡 + 100ms 近黑 + 133ms 淡回）。 */
    public float bridge(long now) {
        long swap = swapAt();
        return swap == Long.MAX_VALUE ? 0.0F : ScreenSwap.contentBridge(now, swap);
    }

    /** 卡片是否应该在这一帧被撤掉（暗场开始的瞬间）。 */
    public boolean cardsGone(long now) {
        return swapAt() != Long.MAX_VALUE && now >= swapAt();
    }

    /**
     * 箱子整体缩放。入场段按参考视频把箱子从 327px 推到 571px（1.75×，缓入缓出）；
     * 转盘开始后箱子退成背景，{@link #BACKDROP_SCALE}。
     */
    public float crateScale(long now, long enteredAt) {
        float dolly = easeInOutCubic(progress(now, enteredAt + DOLLY_DELAY_MS, DOLLY_MS));
        float base = lerp(DOLLY_FROM, DOLLY_TO, dolly);
        if (openedAt < 0) return base;
        float back = easeInOutCubic(progress(now, spinAt(), BACKDROP_MS));
        return lerp(base, BACKDROP_SCALE, back);
    }

    /** 合焦结束后箱子是否已经落位。 */
    public boolean finished(long now) { return openedAt >= 0 && now >= finishAt(); }

    // ---------------------------------------------------------------------
    // 转盘位置
    // ---------------------------------------------------------------------

    /**
     * 三次速度曲线在 {@code u}（0→1）处的累计位移，单位为「卡片」：
     * {@code displacement(u) = T · [ V0·(1 − (1−u)⁴)/4 + V1·u ]}，
     * 即对 {@code v(u) = V0(1−u)³ + V1} 求积分。参考视频的实测拟合是
     * {@code (V0−V1)(1−u)³ + V1}，两者在 V1 很小时等价。
     */
    public static float reelTravel(float u) {
        float x = clamp01(u);
        float rest = 1.0F - x;
        float rest4 = rest * rest * rest * rest;
        return SPIN_MS * ((REEL_SPEED - REEL_TAIL_SPEED) * (1.0F - rest4) * 0.25F + REEL_TAIL_SPEED * x);
    }

    /**
     * 转盘位置，单位为「卡片」。结果到达前保持 {@link #REEL_SPEED} 匀速；
     * 到达后按 {@link #reelTravel} 的三次曲线减速，并把落点补到整数位，
     * 于是中奖卡片恰好停在光标正中（C1 连续，不会「顿一下」）。
     */
    public float reelPosition(long now) {
        if (openedAt < 0) return 0.0F;
        long spin = spinAt();
        if (now <= spin) return 0.0F;
        long decay = decayAt();
        // 结果还没到时保持匀速；结果到达后按三次曲线减速
        if (decay == Long.MAX_VALUE || now <= decay) {
            return REEL_SPEED * (now - spin);
        }
        float base = REEL_SPEED * (decay - spin);
        float total = base + reelTravel(1.0F);
        float target = (float) Math.ceil(total);
        float u = clamp01((now - decay) / (float) SPIN_MS);
        return base + reelTravel(u) + (target - total) * u * u * (3 - 2 * u);
    }

    /**
     * 第 {@code index} 张卡片相对光标的位置，单位为「卡片宽度」：
     * 0 表示正好在光标下，正数在右、负数在左。超出 {@link #SLOTS} 一半即环绕，
     * 于是有限张卡片就能铺出一条无限延伸的转盘。
     */
    public float reelOffset(long now, int index) {
        float raw = index - reelPosition(now);
        float half = SLOTS * 0.5F;
        float wrapped = raw % SLOTS;
        if (wrapped > half) wrapped -= SLOTS;
        else if (wrapped < -half) wrapped += SLOTS;
        return wrapped;
    }

    /** 转盘锁定时光标下的卡片下标：结果物品就放在这里，从而停在黄线正中。 */
    public int cursorSlot(long now) {
        return Math.floorMod(Math.round(reelPosition(now)), SLOTS);
    }

    /** 卡片距光标的距离，绘制时用来推算缩放、亮度与景深虚化。 */
    public static float distance(float offset) { return Math.abs(offset); }

    /** 距光标越远越小：光标 1.0，两侧线性衰减到 0.82。 */
    public static float cardScale(float offset) {
        return 1.0F;
    }

    /** 距光标越远越暗。 */
    public static float cardTint(float offset) {
        return 1.0F;
    }

    /**
     * 景深虚化强度 0→1。GUI 里没有模糊通道，回调方用同色薄雾叠加来近似失焦，
     * 因此这里只暴露「该有多虚」这一个量。
     */
    public static float cardHaze(float offset) {
        return 0.0F;
    }

    // =====================================================================
    // 缓动（刻意与 GuiFx 解耦，保证本类可被纯 JUnit 覆盖）
    // =====================================================================

    public static float progress(long now, long start, long duration) {
        if (start < 0 || duration <= 0L) return start < 0 ? 0.0F : 1.0F;
        return clamp01((now - start) / (float) duration);
    }

    public static float clamp01(float value) {
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    public static float lerp(float from, float to, float t) {
        return from + (to - from) * clamp01(t);
    }

    public static float easeOutCubic(float t) {
        float x = clamp01(t) - 1.0F;
        return x * x * x + 1.0F;
    }

    public static float easeInCubic(float t) {
        float x = clamp01(t);
        return x * x * x;
    }

    public static float easeInOutCubic(float t) {
        float x = clamp01(t);
        return x < 0.5F ? 4.0F * x * x * x : 1.0F - (float) Math.pow(-2.0F * x + 2.0F, 3.0D) / 2.0F;
    }

    /** 回弹缓出：用于箱盖翻开后的轻微过冲。{@code overshoot} 约 1.0–1.7。 */
    public static float easeOutBack(float t, float overshoot) {
        float x = clamp01(t) - 1.0F;
        float c = 1.70158F * overshoot;
        return 1.0F + (c + 1.0F) * x * x * x + c * x * x;
    }
}
