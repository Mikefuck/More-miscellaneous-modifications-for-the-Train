package com.habitrain.lottery.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「游戏内不再产出账户金币」这条策略的<b>目标守卫</b>。
 *
 * <p>{@link AccountCoinRewardMixin} 把闸门设在 {@code PlayerEconomyManager.addCoinNum} 上：
 * 只要所有正向发放都仍然汇聚到这个方法，一条 HEAD 取消就覆盖全部奖励；一旦上游把某个奖励改成
 * <b>直接写</b> {@code EconomyState.coinNum}（或换用别的方法），闸门就会漏，而 mixin 的
 * {@code "required": false} 策略意味着这种漏不会让游戏报错——只会让金币悄悄回来。</p>
 *
 * <p>因此本测试直接读运行期依赖 {@code libs/star_rail_express-4.3.0.jar} 的字节码，把三件事钉死：</p>
 * <ol>
 *   <li><b>闸门</b>：{@code addCoinNum(Player,int)} 仍存在，且余额写入与客户端镜像都在它体内
 *       （在 HEAD 取消才能两者都不发生）；</li>
 *   <li><b>覆盖</b>：Mike 点名取消的每个奖励，其调用点仍然经过这个闸门（{@code PlayerEconomyManager}
 *       直调，或经 {@code ItemSkinManager} 这层包装）；</li>
 *   <li><b>放行</b>：维修模式职业解锁的扣费仍是<b>负</b>增量——{@code delta > 0} 的过滤条件正是
 *       靠它才不能写成「无条件取消」。</li>
 * </ol>
 *
 * <p>注解里的类名在打包后会被 Loom 重映射成 intermediary（{@code net/minecraft/class_1657}），
 * 而 {@code io.wifi.*} / {@code org.agmas.*} 这类模组自有名两个命名空间一致；本测试因此只比对
 * <b>模组自有 owner + 成员名</b>，不比对描述符里的 Minecraft 类型。</p>
 */
class AccountCoinRewardMixinsTargetTest {
    /** 模组自身编译依赖、同时也是运行期的 SRE 产物（AGENTS.md：libs 下的 JAR 不可随意替换）。 */
    private static final String SRE_JAR = "libs/star_rail_express-4.3.0.jar";
    private static final String ECONOMY = "io/wifi/starrailexpress/data/PlayerEconomyManager";
    private static final String ECONOMY_STATE = ECONOMY + "$EconomyState";
    private static final String ITEM_SKIN_MANAGER = "io/wifi/starrailexpress/util/ItemSkinManager";
    private static final String SKINS_COMPONENT = "io/wifi/starrailexpress/cca/SREPlayerSkinsComponent";
    private static final String PROGRESSION = "io/wifi/starrailexpress/progression/ProgressionDataManager";
    private static final String PROGRESSION_STATE = "io/wifi/starrailexpress/progression/ProgressionState";
    private static final String ADD_COIN = "addCoinNum";
    private static final String GRANT_EXPERIENCE = "grantExperience";

    /** 被取消的游戏内金币来源 → 承载调用的类（源码取自上游 4.3.0）。 */
    private static final Map<String, String> REWARD_CALLERS = new LinkedHashMap<>();

    static {
        REWARD_CALLERS.put("巫师：诅咒目标在诅咒期内死亡 +40",
                "org/agmas/noellesroles/game/roles/killer/warlock/WarlockPlayerComponent");
        REWARD_CALLERS.put("亡灵之主：感染注入 +25（数值可配置）",
                "org/agmas/noellesroles/game/roles/killer/undead_lord/UndeadLordPlayerComponent");
        REWARD_CALLERS.put("操纵师：成功标记 +15（数值可配置）",
                "org/agmas/noellesroles/game/roles/killer/manipulator/ManipulatorPlayerComponent");
        REWARD_CALLERS.put("操纵师：被操纵目标死亡 +75（数值可配置）",
                "org/agmas/noellesroles/init/events/NRDeathEvents");
        REWARD_CALLERS.put("抽奖重复皮肤折算（经 ItemSkinManager 包装）",
                "org/agmas/noellesroles/utils/lottery/LotteryManager$LotteryPool");
        REWARD_CALLERS.put("对局结算胜利 +20 / 升级奖励 20+等级×2", PROGRESSION);
        REWARD_CALLERS.put("休眠组件：任务与等级金币（无调用方，一并封口）",
                "io/wifi/starrailexpress/cca/SREPlayerProgressionComponent");
    }

    // ------------------------------------------------------------------ 闸门

    @Test
    void addCoinNumIsStillTheSingleFaucet() throws Exception {
        ClassNode economy = readJarClass(ECONOMY);
        MethodNode faucet = method(economy, ADD_COIN);
        Type[] args = Type.getArgumentTypes(faucet.desc);
        assertEquals(2, args.length, ADD_COIN + " 的参数个数变了：" + faucet.desc);
        assertTrue(args[0].getInternalName().startsWith("net/minecraft/"),
                ADD_COIN + " 的第一个参数不再是 Minecraft 玩家类型：" + faucet.desc);
        assertEquals(Type.INT_TYPE, args[1], ADD_COIN + " 的第二个参数不再是 int：" + faucet.desc);
        assertEquals(Type.VOID_TYPE, Type.getReturnType(faucet.desc), ADD_COIN + " 不再返回 void：" + faucet.desc);
        assertTrue((faucet.access & Opcodes.ACC_STATIC) != 0, ADD_COIN + " 不再是静态方法");

        assertEquals(1, countFieldWrites(faucet, ECONOMY_STATE, "coinNum"),
                "余额写入必须仍在 addCoinNum 体内：否则在 HEAD 取消就挡不住金币");
        assertTrue(classCalls(economy, SKINS_COMPONENT, ADD_COIN),
                "客户端镜像（SREPlayerSkinsComponent.addCoinNum）必须仍由 addCoinNum 触发："
                        + "在 HEAD 取消才能同时挡住余额与镜像");
    }

    // ---------------------------------------------------------------- 覆盖

    @Test
    void everyCancelledRewardStillGoesThroughTheFaucet() throws Exception {
        REWARD_CALLERS.forEach((label, className) -> {
            try {
                ClassNode caller = readJarClass(className);
                boolean direct = classCalls(caller, ECONOMY, ADD_COIN);
                boolean wrapped = classCalls(caller, ITEM_SKIN_MANAGER, ADD_COIN);
                assertTrue(direct || wrapped,
                        "闸门漏了：" + label + " 所在的 " + className + " 已不再调用 "
                                + "PlayerEconomyManager.addCoinNum（也未走 ItemSkinManager 包装）——"
                                + "它可能改成了直写 EconomyState.coinNum，需要为它单独加注入");
            } catch (IOException error) {
                throw new AssertionError("读取 " + className + " 失败（源：" + label + "）", error);
            }
        });
    }

    // ---------------------------------------------------------------- 放行

    @Test
    void repairUnlockDeductionIsStillNegative() throws Exception {
        ClassNode repair = readJarClass("net/exmo/sre/repair/network/RepairRoleShopPurchaseC2SPacket");
        MethodNode target = null;
        int callIndex = -1;
        for (MethodNode candidate : repair.methods) {
            int index = indexOfCall(candidate, ITEM_SKIN_MANAGER, ADD_COIN);
            if (index >= 0) {
                target = candidate;
                callIndex = index;
                break;
            }
        }
        assertNotNull(target, "维修模式职业解锁不再调用 ItemSkinManager.addCoinNum："
                + "请确认扣费路径，delta > 0 的过滤条件需要重新评估");

        Integer delta = precedingIntConstant(target, callIndex);
        assertNotNull(delta, "无法在 " + target.name + " 里解析扣费增量："
                + "若改成非常量或非取负写法，请人工确认它是负数");
        assertTrue(delta < 0,
                "维修模式职业解锁的增量变成了 " + delta + "（非负）：AccountCoinRewardMixin 的 delta > 0 "
                        + "过滤会把它一起吞掉，购买职业将变成免费");
    }

    // -------------------------------------------------------- mixin 注入点

    @Test
    void policyMixinHooksTheFaucetAtHead() throws Exception {
        List<Injection> injections = injections(AccountCoinRewardMixin.class,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(1, injections.size(), "策略 mixin 应当只声明一个注入");
        Injection inject = injections.get(0);
        assertTrue(inject.method().startsWith(ADD_COIN + "("),
                "策略 mixin 必须钩住 addCoinNum，实际为 " + inject.method());
        assertEquals("HEAD", inject.atValue(),
                "必须注入 HEAD：只有整个方法体都不执行，余额、脏标记与组件镜像才都不会被写入");
        assertEquals(Boolean.TRUE, inject.cancellable(),
                "注入必须是 cancellable，否则无法取消发放");
    }

    @Test
    void progressionCoinStatIsSuppressedAtExactlyTwoWrites() throws Exception {
        ClassNode progression = readJarClass(PROGRESSION);
        List<String> writers = new ArrayList<>();
        int total = 0;
        for (MethodNode method : progression.methods) {
            int writes = countFieldWrites(method, PROGRESSION_STATE, "claimedCoinRewards");
            if (writes > 0) {
                writers.add(method.name);
                total += writes;
            }
        }
        assertEquals(2, total,
                "ProgressionDataManager 中 claimedCoinRewards 的写入次数变了：" + writers
                        + " —— ProgressionCoinRewardStatMixin 需要同步");
        assertEquals(Set.of("onRoundSettled", GRANT_EXPERIENCE), new LinkedHashSet<>(writers),
                "统计写入的位置变了：" + writers);
    }

    @Test
    void statMixinRedirectsBothWritesSoftly() throws Exception {
        List<Injection> injections = injections(ProgressionCoinRewardStatMixin.class,
                "Lorg/spongepowered/asm/mixin/injection/Redirect;");
        assertEquals(2, injections.size(), "统计 mixin 应当只声明两个重定向");

        Set<String> methods = new LinkedHashSet<>();
        for (Injection redirect : injections) {
            assertEquals("FIELD", redirect.atValue(),
                    "统计修正应重定向字段写入，实际为 " + redirect.atValue());
            assertEquals(PROGRESSION_STATE + ".claimedCoinRewards", memberOf(redirect.atTarget()),
                    "重定向目标已偏离：应当恰好是 ProgressionState.claimedCoinRewards");
            assertEquals(0, redirect.require(),
                    "统计修正只是显示口径，必须保持 require = 0：失配时不得牵连策略闸门");
            methods.add(redirect.method().substring(0, redirect.method().indexOf('(')));
        }
        assertEquals(Set.of("onRoundSettled", GRANT_EXPERIENCE), methods,
                "两个统计写入必须都被覆盖，实际覆盖 " + methods);
    }

    // ---------------------------------------------------------------- helpers

    private static ClassNode readJarClass(String className) throws IOException {
        Path jar = Path.of(SRE_JAR);
        assertTrue(Files.isRegularFile(jar),
                "找不到运行期依赖 " + SRE_JAR + "（当前目录 " + System.getProperty("user.dir") + "）："
                        + "本测试需要它来核对 mixin 注入点");
        ClassNode node = new ClassNode();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(className + ".class");
            assertNotNull(entry, SRE_JAR + " 中找不到 " + className);
            try (InputStream in = zip.getInputStream(entry)) {
                new ClassReader(in).accept(node, 0);
            }
        }
        return node;
    }

    private static MethodNode method(ClassNode owner, String name) {
        List<MethodNode> matches = new ArrayList<>();
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name)) {
                matches.add(method);
            }
        }
        assertEquals(1, matches.size(), owner.name + " 应当只有恰好一个 " + name + "，实际 " + matches.size());
        return matches.get(0);
    }

    private static boolean classCalls(ClassNode owner, String targetOwner, String targetName) {
        for (MethodNode method : owner.methods) {
            if (indexOfCall(method, targetOwner, targetName) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static int indexOfCall(MethodNode method, String targetOwner, String targetName) {
        List<AbstractInsnNode> instructions = instructions(method);
        for (int i = 0; i < instructions.size(); i++) {
            if (instructions.get(i) instanceof MethodInsnNode call
                    && targetOwner.equals(call.owner) && targetName.equals(call.name)) {
                return i;
            }
        }
        return -1;
    }

    private static int countFieldWrites(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && owner.equals(field.owner) && name.equals(field.name)) {
                count++;
            }
        }
        return count;
    }

    /** 从调用点向前找最近的整型常量（跳过 {@code Integer.valueOf} 装箱）。 */
    private static Integer precedingIntConstant(MethodNode method, int callIndex) {
        List<AbstractInsnNode> instructions = instructions(method);
        for (int i = callIndex - 1; i >= 0 && i >= callIndex - 4; i--) {
            AbstractInsnNode instruction = instructions.get(i);
            if (instruction instanceof IntInsnNode push
                    && (push.getOpcode() == Opcodes.BIPUSH || push.getOpcode() == Opcodes.SIPUSH)) {
                return push.operand;
            }
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) {
                return value;
            }
        }
        return null;
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            out.add(instruction);
        }
        return out;
    }

    private static List<Injection> injections(Class<?> mixin, String annotationDesc) throws IOException {
        List<Injection> out = new ArrayList<>();
        String resource = "/" + mixin.getName().replace('.', '/') + ".class";
        try (InputStream in = mixin.getResourceAsStream(resource)) {
            assertNotNull(in, "无法读取自身 class 文件 " + resource);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            for (MethodNode method : node.methods) {
                for (AnnotationNode annotation : allAnnotations(method)) {
                    if (annotationDesc.equals(annotation.desc)) {
                        out.add(parseInjection(annotation));
                    }
                }
            }
        }
        return out;
    }

    private static List<AnnotationNode> allAnnotations(MethodNode method) {
        List<AnnotationNode> out = new ArrayList<>();
        if (method.visibleAnnotations != null) {
            out.addAll(method.visibleAnnotations);
        }
        if (method.invisibleAnnotations != null) {
            out.addAll(method.invisibleAnnotations);
        }
        return out;
    }

    private static Injection parseInjection(AnnotationNode annotation) {
        String method = "";
        String atValue = "";
        String atTarget = "";
        int require = 1;
        Boolean cancellable = null;
        if (annotation.values != null) {
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                String key = String.valueOf(annotation.values.get(i));
                Object value = annotation.values.get(i + 1);
                switch (key) {
                    case "method" -> method = firstString(value);
                    case "require" -> require = value instanceof Integer number ? number : require;
                    case "cancellable" -> cancellable = value instanceof Boolean flag ? flag : cancellable;
                    case "at" -> {
                        // @Inject.at() 是数组（At[]），@Redirect.at() 是单值，ASM 分别给 List / AnnotationNode。
                        Object atNode = value instanceof List<?> list && !list.isEmpty() ? list.get(0) : value;
                        if (atNode instanceof AnnotationNode at && at.values != null) {
                            for (int j = 0; j + 1 < at.values.size(); j += 2) {
                                String atKey = String.valueOf(at.values.get(j));
                                Object atValueObject = at.values.get(j + 1);
                                if ("value".equals(atKey)) {
                                    atValue = firstString(atValueObject);
                                } else if ("target".equals(atKey)) {
                                    atTarget = firstString(atValueObject);
                                }
                            }
                        }
                    }
                    default -> { }
                }
            }
        }
        return new Injection(method, atValue, atTarget, require, cancellable);
    }

    /** 把 {@code Lowner;name(desc)ret} / {@code Lowner;field:I} 归一成 {@code owner.name}。 */
    private static String memberOf(String atTarget) {
        String body = atTarget.startsWith("L") ? atTarget.substring(1) : atTarget;
        int split = body.indexOf(';');
        assertTrue(split > 0, "无法解析重定向目标 " + atTarget);
        String owner = body.substring(0, split);
        String member = body.substring(split + 1);
        int paren = member.indexOf('(');
        int colon = member.indexOf(':');
        String name = paren >= 0 ? member.substring(0, paren)
                : colon >= 0 ? member.substring(0, colon) : member;
        return owner + "." + name;
    }

    private static String firstString(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String text) {
            return text;
        }
        return String.valueOf(value);
    }

    /** 一条注入的关键参数。 */
    private record Injection(String method, String atValue, String atTarget, int require, Boolean cancellable) { }
}
