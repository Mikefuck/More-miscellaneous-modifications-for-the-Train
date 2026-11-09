package com.habitrain.lottery.command;

import com.habitrain.lottery.PlayerStateGate;
import com.habitrain.lottery.api.player.HabiLotteryApi;
import com.habitrain.lottery.api.player.HabiSystemItemApi;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.crate.CrateService;
import com.habitrain.lottery.meta.MenuGateServerBridge;
import com.habitrain.lottery.network.AdminAuth;
import com.habitrain.lottery.network.CrateNetwork;
import com.habitrain.lottery.network.LotteryNetwork;
import com.habitrain.lottery.skin.SkinContentBootstrap;
import com.habitrain.lottery.skin.SkinNetwork;
import com.habitrain.lottery.storage.PlayerLotteryData;
import com.habitrain.lottery.storage.PlayerLotteryStore;
import com.habitrain.lottery.storage.SkinTypeKeys;
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
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.stream.Stream;

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

    /** Console is OP-only break-glass; in-game admins align with AdminAuth + MenuGate. */
    private static boolean adminRequires(CommandSourceStack source) {
        if (source == null) {
            return false;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return source.hasPermission(opLevel());
        }
        return AdminAuth.isAuthorizedOp(player) && !MenuGateServerBridge.isBlocked(player, source.getServer());
    }

    private static boolean ensureAdminAuthorized(CommandSourceStack source) {
        if (!adminRequires(source)) {
            if (source != null) {
                source.sendFailure(Component.translatable("message.habitrain_lottery.admin.unauthorized"));
            }
            return false;
        }
        return true;
    }

    private static int reloadCrates(CommandSourceStack source) {
        if (!ensureAdminAuthorized(source)) {
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player != null && MenuGateServerBridge.isBlocked(player, source.getServer())) {
            source.sendFailure(Component.translatable("message.habitrain_lottery.admin.unauthorized"));
            return 0;
        }
        CrateService.onServerStarted();
        CrateNetwork.broadcastReload(source.getServer());
        source.sendSuccess(() -> Component.translatable("command.habitrain_lottery.crate.reloaded"), true);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name) {
        return Commands.literal(name)
                .then(Commands.literal("crate")
                        .requires(LotteryCommands::adminRequires)
                        .then(Commands.literal("reload")
                                .requires(LotteryCommands::adminRequires)
                                .executes(ctx -> reloadCrates(ctx.getSource()))))
                .then(Commands.literal("system_item")
                        .requires(LotteryCommands::adminRequires)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    if (!ensureAdminAuthorized(ctx.getSource())) return 0;
                                                    var player = EntityArgument.getPlayer(ctx, "player");
                                                    var id = ResourceLocationArgument.getId(ctx, "id");
                                                    var result = HabiSystemItemApi.grant(
                                                            player.getUUID(), id, IntegerArgumentType.getInteger(ctx, "amount"));
                                                    if (!result.ok()) {
                                                        ctx.getSource().sendFailure(result.failureComponent());
                                                        return 0;
                                                    }
                                                    ctx.getSource().sendSuccess(() -> Component.translatable(
                                                            "command.habitrain_lottery.system_item.granted",
                                                            id.toString(), result.newValue()), true);
                                                    return 1;
                                                })))))
                .then(Commands.literal("crates")
                        .requires(LotteryCommands::adminRequires)
                        .then(Commands.literal("reload")
                                .requires(LotteryCommands::adminRequires)
                                .executes(ctx -> reloadCrates(ctx.getSource())))
                        .then(Commands.literal("give")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("item", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        Stream.concat(
                                                                        CrateCatalog.publishedEntries().stream().map(d -> "crate_" + d.id()),
                                                                        CrateCatalog.publishedEntries().stream().map(CrateCatalog.Entry::keyId))
                                                                .sorted(), builder))
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100000))
                                                         .executes(ctx -> {
                                                            if (!ensureAdminAuthorized(ctx.getSource())) return 0;
                                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                                            String raw = StringArgumentType.getString(ctx, "item");
                                                            String id = raw.startsWith("crate_") || raw.startsWith("key_") ? raw : "crate_" + raw;
                                                            String crateId = id.startsWith("crate_") ? id.substring(6) : id.substring(4);
                                                            var entry = CrateCatalog.find(crateId);
                                                            if (entry == null || entry.archived()) {
                                                                ctx.getSource().sendFailure(Component.translatable(
                                                                        "command.habitrain_lottery.crate.archived_or_missing"));
                                                                return 0;
                                                            }
                                                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                                            var result = HabiSystemItemApi.grant(
                                                                    target.getUUID(), ResourceLocation.fromNamespaceAndPath(
                                                                            CrateService.NAMESPACE, id), amount);
                                                            if (!result.ok()) {
                                                                ctx.getSource().sendFailure(result.failureComponent());
                                                                return 0;
                                                            }
                                                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                                                    "command.habitrain_lottery.crate.given", id, amount), true);
                                                            return 1;
                                                        }))))))
                .then(Commands.literal("green_apples")
                        .requires(LotteryCommands::adminRequires)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(-100000, 100000))
                                        .executes(ctx -> {
                                            if (!ensureAdminAuthorized(ctx.getSource())) return 0;
                                            ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                            var result = HabiLotteryApi.addGreenApples(p.getUUID(), amount);
                                            if (!result.ok()) {
                                                ctx.getSource().sendFailure(result.failureComponent());
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                                    "command.habitrain_lottery.green_apples.adjusted",
                                                    p.getGameProfile().getName(), amount, result.newValue()), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("mail")
                        .requires(LotteryCommands::adminRequires)
                        .executes(ctx -> {
                            if (!ensureAdminAuthorized(ctx.getSource())) return 0;
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            LotteryNetwork.sendOpenMailCompose(player);
                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                    "command.habitrain_lottery.mail.opened"), false);
                            return 1;
                        }))
                .then(Commands.literal("mailbox")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (PlayerStateGate.spectatorRestOrDead(player)) {
                                ctx.getSource().sendFailure(PlayerStateGate.mailBlockedComponent());
                                return 0;
                            }
                            LotteryNetwork.sendOpenMailbox(player);
                            return 1;
                        }))
                .then(Commands.literal("inspect")
                        .requires(LotteryCommands::adminRequires)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> {
                                    if (!ensureAdminAuthorized(ctx.getSource())) return 0;
                                    ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                                    PlayerLotteryData d = PlayerLotteryStore.get().getOrLoad(p);
                                    int unlocks = d.unlocked.values().stream().mapToInt(m -> m.size()).sum();
                                    ctx.getSource().sendSuccess(() -> Component.translatable(
                                            "command.habitrain_lottery.inspect.summary",
                                            p.getGameProfile().getName(),
                                            d.greenApples,
                                            unlocks,
                                            d.equipped.size()), false);
                                    return 1;
                                })))
                .then(Commands.literal("skins")
                        .then(buildSkinAccess("unlock", true))
                        .then(buildSkinAccess("lock", false))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (PlayerStateGate.spectatorRestOrDead(player)) {
                                ctx.getSource().sendFailure(PlayerStateGate.actionBlockedComponent());
                                return 0;
                            }
                            SkinNetwork.open(player);
                            return 1;
                        })
                        .then(Commands.literal("reregister")
                                .requires(LotteryCommands::adminRequires)
                                .executes(ctx -> {
                                    if (!ensureAdminAuthorized(ctx.getSource())) return 0;
                                    int total = SkinContentBootstrap.reRegister();
                                    var skipped = SkinContentBootstrap.getSkippedProviders();
                                    if (skipped.isEmpty()) {
                                        ctx.getSource().sendSuccess(() -> Component.translatable(
                                                "command.habitrain_lottery.skins.reregistered", total), true);
                                        return 1;
                                    }
                                    // Never report a success count when a provider was rolled back.
                                    ctx.getSource().sendFailure(Component.translatable(
                                            "command.habitrain_lottery.skins.reregister_partial",
                                            total, skipped.size(), String.join(", ", skipped)));
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
        if (!ensureAdminAuthorized(source)) {
            return 0;
        }
        String type = SkinTypeKeys.canonical(StringArgumentType.getString(ctx, "type"));
        String skin = PlayerLotteryStore.normalizeEquippedSkin(StringArgumentType.getString(ctx, "skin"));
        if ("default".equals(skin)) {
            source.sendFailure(Component.translatable("command.habitrain_lottery.skins.default_immutable"));
            return 0;
        }
        if (!HabiSkinApi.getSkins(type).containsKey(skin)) {
            source.sendFailure(Component.translatable(
                    "command.habitrain_lottery.skins.unregistered", type, skin));
            return 0;
        }
        PlayerLotteryStore store = PlayerLotteryStore.get();
        if (!store.isTakeoverActive()) {
            source.sendFailure(Component.translatable("command.habitrain_lottery.skins.storage_not_ready"));
            return 0;
        }
        int count = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(ctx, "players")) {
            String playerName = player.getGameProfile().getName();
            if (!store.commitSkinAccess(player.getUUID(), type, skin, unlocked)) {
                source.sendFailure(Component.translatable(
                        "command.habitrain_lottery.skins.save_failed", playerName));
                continue;
            }
            boolean synced = SkinNetwork.syncAccess(player, store.getOrLoad(player), type, skin, unlocked);
            count++;
            source.sendSuccess(() -> Component.translatable(
                    unlocked
                            ? "command.habitrain_lottery.skins.unlocked"
                            : "command.habitrain_lottery.skins.locked",
                    playerName, type, skin), true);
            if (!synced) {
                source.sendFailure(Component.translatable(
                        "command.habitrain_lottery.skins.sync_failed", playerName));
            }
        }
        return count;
    }
}
