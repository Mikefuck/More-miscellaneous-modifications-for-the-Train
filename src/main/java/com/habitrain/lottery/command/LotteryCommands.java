package com.habitrain.lottery.command;

import com.habitrain.lottery.api.player.HabiLotteryApi;
import com.habitrain.lottery.meta.MenuGateServerBridge;
import com.habitrain.lottery.network.LotteryNetwork;
import com.habitrain.lottery.skin.SkinContentBootstrap;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.SkinTypeKeys;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.crate.CrateService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class LotteryCommands {
    private LotteryCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess,
                                Commands.CommandSelection environment) {
        dispatcher.register(buildRoot("habitrain_lottery"));
        dispatcher.register(buildRoot("hlt"));
    }

    private static int opLevel() {
        return 2;
    }

    /** Console is OP-only break-glass; in-game admins also need MenuGate on dedicated. */
    private static boolean adminRequires(CommandSourceStack source) {
        if (!source.hasPermission(opLevel())) {
            return false;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }
        return !MenuGateServerBridge.isBlocked(player, source.getServer());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name) {
        return Commands.literal(name)
                .then(Commands.literal("system_item")
                        .requires(LotteryCommands::adminRequires)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("id", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    var player = EntityArgument.getPlayer(ctx, "player");
                                                    var id = net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "id");
                                                    var result = com.habitrain.lottery.api.player.HabiSystemItemApi.grant(
                                                            player.getUUID(), id, IntegerArgumentType.getInteger(ctx, "amount"));
                                                    if (!result.ok()) { ctx.getSource().sendFailure(Component.literal(result.message())); return 0; }
                                                    ctx.getSource().sendSuccess(() -> Component.literal("系统道具已存入账户：" + id + " × " + result.newValue()), true);
                                                    return 1;
                                                })))))
                .then(Commands.literal("crates")
                        .requires(LotteryCommands::adminRequires)
                        .then(Commands.literal("give")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("item", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        java.util.stream.Stream.concat(
                                                                        CrateService.definitions().stream().map(d -> "crate_" + d.id()),
                                                                        CrateService.definitions().stream().map(CrateService.Definition::keyId))
                                                                .sorted(), builder))
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100000))
                                                        .executes(ctx -> {
                                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                                            String raw = StringArgumentType.getString(ctx, "item");
                                                            String id = raw.startsWith("crate_") || raw.startsWith("key_") ? raw : "crate_" + raw;
                                                            var result = com.habitrain.lottery.api.player.HabiSystemItemApi.grant(
                                                                    target.getUUID(), net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                                                            CrateService.NAMESPACE, id), IntegerArgumentType.getInteger(ctx, "amount"));
                                                            if (!result.ok()) { ctx.getSource().sendFailure(Component.literal(result.message())); return 0; }
                                                            ctx.getSource().sendSuccess(() -> Component.literal("已发放 " + id + " × " + IntegerArgumentType.getInteger(ctx, "amount")), true);
                                                            return 1;
                                                        }))))))
                .then(Commands.literal("green_apples")
                        .requires(LotteryCommands::adminRequires)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(-100000, 100000))
                                        .executes(ctx -> {
                                            ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                            var result = HabiLotteryApi.addGreenApples(p.getUUID(), amount);
                                            if (!result.ok()) {
                                                ctx.getSource().sendFailure(Component.literal(result.message()));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "已给 " + p.getGameProfile().getName() + " 调整绿苹果 " + amount
                                                            + "，现有 " + result.newValue()), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("mail")
                        .requires(LotteryCommands::adminRequires)
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            LotteryNetwork.sendOpenMailCompose(player);
                            ctx.getSource().sendSuccess(() -> Component.literal("已打开邮件撰写界面"), false);
                            return 1;
                        }))
                .then(Commands.literal("mailbox")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            LotteryNetwork.sendOpenMailbox(player);
                            return 1;
                        }))
                .then(Commands.literal("inspect")
                        .requires(LotteryCommands::adminRequires)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                                    PlayerLotteryData d = PlayerLotteryStore.get().getOrLoad(p);
                                    int unlocks = d.unlocked.values().stream().mapToInt(m -> m.size()).sum();
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            p.getGameProfile().getName()
                                                    + " greenApples=" + d.greenApples
                                                    + " unlocks=" + unlocks
                                                    + " equipped=" + d.equipped.size()), false);
                                    return 1;
                                })))
                .then(Commands.literal("skins")
                        .then(buildSkinAccess("unlock", true))
                        .then(buildSkinAccess("lock", false))
                        .executes(ctx -> {
                            com.habitrain.lottery.skin.SkinNetwork.open(ctx.getSource().getPlayerOrException());
                            return 1;
                        })
                        .then(Commands.literal("reregister")
                                .requires(LotteryCommands::adminRequires)
                                .executes(ctx -> {
                                    int total = SkinContentBootstrap.reRegister();
                                    var skipped = SkinContentBootstrap.getSkippedProviders();
                                    if (skipped.isEmpty()) {
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "重新注册完成，皮肤目录共 " + total + " 项；"
                                                        + "客户端需刷新资源（F3+T）后生效"), true);
                                        return 1;
                                    }
                                    // Never report a success count when a provider was rolled back.
                                    ctx.getSource().sendFailure(Component.literal(
                                            "重新注册部分失败：目录共 " + total + " 项，"
                                                    + skipped.size() + " 个扩展被回滚并跳过（"
                                                    + String.join(", ", skipped)
                                                    + "），详见服务器日志"));
                                    return 0;
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildSkinAccess(String name, boolean unlocked) {
        return Commands.literal(name)
                .requires(LotteryCommands::adminRequires)
                .then(Commands.argument("players", EntityArgument.players())
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        HabiSkinApi.types().stream().sorted(), builder))
                                .then(Commands.argument("skin", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                HabiSkinApi.getSkins(SkinTypeKeys.canonical(
                                                        StringArgumentType.getString(ctx, "type")))
                                                        .keySet().stream().filter(id -> !"default".equals(id)).sorted(), builder))
                                        .executes(ctx -> changeSkinAccess(ctx, unlocked)))));
    }

    private static int changeSkinAccess(CommandContext<CommandSourceStack> ctx, boolean unlocked)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String type = SkinTypeKeys.canonical(StringArgumentType.getString(ctx, "type"));
        String skin = PlayerLotteryStore.normalizeEquippedSkin(StringArgumentType.getString(ctx, "skin"));
        if ("default".equals(skin)) {
            source.sendFailure(Component.literal("默认皮肤始终可用，无需解锁，也不能撤销解锁"));
            return 0;
        }
        if (!HabiSkinApi.getSkins(type).containsKey(skin)) {
            source.sendFailure(Component.literal("未注册的皮肤：" + type + "/" + skin + "，请使用 Tab 补全"));
            return 0;
        }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (!store.isTakeoverActive()) {
            source.sendFailure(Component.literal("皮肤存档尚未就绪，请稍后重试"));
            return 0;
        }
        int count = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(ctx, "players")) {
            String playerName = player.getGameProfile().getName();
            if (!store.commitSkinAccess(player.getUUID(), type, skin, unlocked)) {
                source.sendFailure(Component.literal(playerName + " 的皮肤保存失败，未修改解锁状态"));
                continue;
            }
            boolean synced = com.habitrain.lottery.skin.SkinNetwork.syncAccess(player, store.getOrLoad(player), type, skin, unlocked);
            count++;
            source.sendSuccess(() -> Component.literal("已为 " + playerName
                    + (unlocked ? " 解锁皮肤 " : " 撤销皮肤解锁 ") + type + "/" + skin), true);
            if (!synced) {
                source.sendFailure(Component.literal(playerName + " 的存档已保存，但实时同步失败，请重试指令"));
            }
        }
        return count;
    }
}
