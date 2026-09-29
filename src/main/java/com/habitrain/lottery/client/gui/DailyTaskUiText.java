package com.habitrain.lottery.client.gui;

import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.crate.CrateCatalog;
import com.habitrain.lottery.daily.config.DailyFactions;
import com.habitrain.lottery.daily.config.DailyRewardEntry;
import com.habitrain.lottery.daily.config.DailyTaskCatalog;
import com.habitrain.lottery.daily.config.DailyTaskConfigService;
import com.habitrain.lottery.daily.config.DailyTaskDefinition;
import com.habitrain.lottery.daily.config.DailyTaskType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Client vocabulary of the daily-task editor: picker options for every filter / reward type and
 * the localised auto-generated description and reward label written into the saved task.
 */
final class DailyTaskUiText {
    static final String KEY = "screen.habitrain_lottery.daily_admin.";

    private DailyTaskUiText() { }

    static net.minecraft.network.chat.MutableComponent tr(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    static String s(String key, Object... args) {
        return tr(key, args).getString();
    }

    static int factionColor(String faction) {
        return switch (faction == null ? "" : faction) {
            case DailyFactions.KILLER -> 0xFFE05555;
            case DailyFactions.VIGILANTE -> 0xFF5B9BE0;
            case DailyFactions.NEUTRAL -> 0xFFD7B24A;
            case DailyFactions.NEUTRAL_FOR_KILLER -> 0xFFD07A3A;
            default -> 0xFF6CC56C;
        };
    }

    static String factionName(String faction) {
        return s("faction." + faction);
    }

    static String roleName(String id) {
        return WarehouseRole.resolveRoleDisplayName(id, null);
    }

    static String typeName(DailyTaskType type) {
        return Component.translatable(type.translationKey()).getString();
    }

    static String unit(DailyTaskType type) {
        return s("unit." + type.id());
    }

    static String questName(String quest) {
        String own = KEY + "quest." + quest.replace('.', '_');
        Language lang = Language.getInstance();
        if (lang.has(own)) return lang.getOrDefault(own);
        if (lang.has("task_type." + quest)) return lang.getOrDefault("task_type." + quest);
        return quest;
    }

    static String deathReasonName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Language lang = Language.getInstance();
        if (rl != null && lang.has("death_reason." + rl.getNamespace() + "." + rl.getPath())) {
            return lang.getOrDefault("death_reason." + rl.getNamespace() + "." + rl.getPath());
        }
        String own = KEY + "reason." + (rl == null ? id : rl.getPath());
        return lang.has(own) ? lang.getOrDefault(own) : id;
    }

    static String itemName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
            return BuiltInRegistries.ITEM.get(rl).getDescription().getString();
        }
        return id;
    }

    static String crateName(String id) {
        if (DailyRewardEntry.RANDOM.equals(id)) return s("reward.random_crate");
        CrateCatalog.Entry entry = CrateCatalog.find(id);
        return entry == null ? id : Component.translatableWithFallback(entry.nameKey(), entry.nameKey()).getString();
    }

    static String skinName(String entry) {
        String[] pair = entry == null ? new String[0] : entry.split("/", 2);
        return pair.length == 2 ? SkinWardrobeScreen.skinName(pair[0], pair[1]).getString() : String.valueOf(entry);
    }

    static String cardName(String kind) {
        return Component.translatable("screen.habitrain_lottery.config.cards." + kind).getString();
    }

    // ------------------------------------------------------------------ picker options

    static List<DailyPickerScreen.Option> typeOptions() {
        List<DailyPickerScreen.Option> out = new ArrayList<>();
        for (DailyTaskType type : DailyTaskType.values()) {
            out.add(new DailyPickerScreen.Option(type.id(), typeName(type), s("type_hint." + type.id()),
                    type.scope() == DailyTaskType.Scope.ACCOUNT ? 0xFF8ED073 : 0xFF57C6D6,
                    s("scope." + type.scope().name().toLowerCase(Locale.ROOT))));
        }
        return out;
    }

    static List<DailyPickerScreen.Option> factionOptions() {
        List<DailyPickerScreen.Option> out = new ArrayList<>();
        for (String faction : DailyFactions.ALL) {
            out.add(new DailyPickerScreen.Option(faction, factionName(faction), s("faction_hint." + faction),
                    factionColor(faction)));
        }
        return out;
    }

    static List<DailyPickerScreen.Option> roleOptions(List<DailyTaskConfigService.RoleOption> roster) {
        List<DailyPickerScreen.Option> out = new ArrayList<>();
        for (DailyTaskConfigService.RoleOption role : roster) {
            String group = factionName(role.faction()) + (role.visible() ? "" : " · " + s("role_hidden"));
            out.add(new DailyPickerScreen.Option(role.id(), roleName(role.id()), role.id(),
                    factionColor(role.faction()), group));
        }
        out.sort(Comparator.comparing(DailyPickerScreen.Option::group).thenComparing(DailyPickerScreen.Option::label));
        return out;
    }

    static List<DailyPickerScreen.Option> deathReasonOptions() {
        List<DailyPickerScreen.Option> out = new ArrayList<>();
        for (String id : DailyTaskCatalog.DEATH_REASONS) {
            out.add(new DailyPickerScreen.Option(id, deathReasonName(id), id, 0xFFE05555));
        }
        return out;
    }

    static List<DailyPickerScreen.Option> questOptions() {
        List<DailyPickerScreen.Option> out = new ArrayList<>();
        for (String quest : DailyTaskCatalog.QUESTS) {
            out.add(new DailyPickerScreen.Option(quest, questName(quest), quest, 0xFF6CC56C));
        }
        return out;
    }

    static List<DailyPickerScreen.Option> actionOptions() {
        List<DailyPickerScreen.Option> out = new ArrayList<>();
        for (String action : DailyTaskCatalog.ACTIONS) {
            out.add(new DailyPickerScreen.Option(action, s("action." + action), s("action_hint." + action), 0xFFB26BDA));
        }
        return out;
    }

    static List<DailyPickerScreen.Option> cardUseOptions() {
        List<DailyPickerScreen.Option> out = new ArrayList<>();
        for (String card : DailyTaskCatalog.CARD_USES) {
            out.add(new DailyPickerScreen.Option(card, cardName(card), card, 0xFFE9B86E));
        }
        return out;
    }

    /** Game items first (known train-game namespaces), then every other modded item. Vanilla is custom-only. */
    static List<DailyPickerScreen.Option> itemOptions() {
        List<DailyPickerScreen.Option> preferred = new ArrayList<>();
        List<DailyPickerScreen.Option> others = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || "minecraft".equals(id.getNamespace())) continue;
            boolean game = DailyTaskCatalog.ITEM_NAMESPACES.contains(id.getNamespace());
            (game ? preferred : others).add(new DailyPickerScreen.Option(id.toString(),
                    item.getDescription().getString(), id.toString(), game ? 0xFF57C6D6 : 0xFF8899AA, id.getNamespace()));
        }
        preferred.addAll(others);
        return preferred;
    }

    static List<DailyPickerScreen.Option> crateOptions(boolean withRandom) {
        List<DailyPickerScreen.Option> out = new ArrayList<>();
        if (withRandom) out.add(new DailyPickerScreen.Option(DailyRewardEntry.RANDOM, s("reward.random_crate"),
                s("reward.random_crate_hint"), 0xFFFFFFFF));
        for (CrateCatalog.Entry entry : CrateCatalog.publishedEntries()) {
            out.add(new DailyPickerScreen.Option(entry.id(), crateName(entry.id()),
                    entry.id() + (entry.enabled() ? "" : " · " + s("crate_disabled")), entry.color()));
        }
        return out;
    }

    static List<DailyPickerScreen.Option> skinOptions() {
        List<DailyPickerScreen.Option> out = new ArrayList<>();
        for (SkinDefinition skin : HabiSkinApi.registrations()) {
            String entry = skin.type() + "/" + skin.id();
            out.add(new DailyPickerScreen.Option(entry, skinName(entry), entry, skin.quality().color(),
                    Component.translatable(skin.quality().translationKey()).getString()));
        }
        return out;
    }

    // ------------------------------------------------------------------ generated text

    /** Readable summary of what the task asks, e.g. "以杀手阵营身份用刀击杀 3 名敌对玩家". */
    static String describe(DailyTaskDefinition task) {
        DailyTaskType type = task.taskType();
        if (type == null) return "";
        String who = switch (task.roleMode) {
            case "faction" -> s("desc.as", joinNames(task.factions, DailyTaskUiText::factionName, 3));
            case "role" -> s("desc.as", joinNames(task.roles, DailyTaskUiText::roleName, 3));
            default -> "";
        };
        String what = switch (type) {
            case PLAY_MATCH -> s("desc.play_match." + task.matchResult, task.target);
            case KILL -> {
                String weapon = task.deathReasons.isEmpty() ? ""
                        : s("desc.with", joinNames(task.deathReasons, DailyTaskUiText::deathReasonName, 3));
                String victim = switch (task.victimMode) {
                    case "enemy" -> s("desc.victim_enemy");
                    case "faction" -> joinNames(task.victimFactions, DailyTaskUiText::factionName, 3);
                    default -> s("desc.victim_any");
                };
                yield weapon + s("desc.kill", task.target, victim);
            }
            case FINISH_TASK -> s("desc.finish_task", task.target,
                    filterSuffix(task.quests, DailyTaskUiText::questName));
            case USE_ITEM -> s("desc.use_item", filterSuffix(task.items, DailyTaskUiText::itemName), task.target);
            case SHOP_BUY -> s("desc.shop_buy", filterSuffix(task.items, DailyTaskUiText::itemName), task.target);
            case SPECIAL_ACTION -> s("desc.special_action",
                    filterSuffix(task.actions, a -> s("action." + a)), task.target);
            case OPEN_CRATE -> s("desc.open_crate", filterSuffix(task.crates, DailyTaskUiText::crateName), task.target);
            case USE_CARD -> s("desc.use_card", filterSuffix(task.cards, DailyTaskUiText::cardName), task.target);
            default -> s("desc." + type.id(), task.target);
        };
        String text = who + what + (task.singleMatch && type.supportsSingleMatch() ? s("desc.single_match") : "");
        return text.length() <= 120 ? text : text.substring(0, 119) + "…";
    }

    static String rewardLabel(List<DailyRewardEntry> rewards) {
        List<String> parts = new ArrayList<>();
        for (DailyRewardEntry reward : rewards) parts.add(rewardText(reward));
        String label = String.join("、", parts);
        return label.length() <= 64 ? label : label.substring(0, 63) + "…";
    }

    static String rewardText(DailyRewardEntry reward) {
        String name = switch (reward.kind) {
            case DailyRewardEntry.GREEN_APPLES -> Component.translatable("screen.habitrain_lottery.warehouse.green_apples").getString();
            case DailyRewardEntry.CARD -> cardName(reward.id.isEmpty() ? "civilian" : reward.id);
            case DailyRewardEntry.SELF_SELECT -> cardName("self_select");
            case DailyRewardEntry.LIMIT_BREAK -> cardName("limit_break");
            case DailyRewardEntry.SKIN -> reward.id.isEmpty() ? s("reward.kind.skin") : skinName(reward.id);
            case DailyRewardEntry.CRATE -> reward.id.isEmpty() ? s("reward.kind.crate") : crateName(reward.id);
            case DailyRewardEntry.KEY -> reward.id.isEmpty() ? s("reward.kind.key")
                    : DailyRewardEntry.RANDOM.equals(reward.id) ? s("reward.random_key") : s("reward.key_of", crateName(reward.id));
            case DailyRewardEntry.TITLE -> s("reward.title_of", reward.id);
            default -> reward.kind;
        };
        return DailyRewardEntry.isUnique(reward.kind) ? name : name + " ×" + reward.amount;
    }

    static String filterSuffix(List<String> ids, java.util.function.Function<String, String> names) {
        return ids.isEmpty() ? "" : joinNames(ids, names, 3);
    }

    static String joinNames(List<String> ids, java.util.function.Function<String, String> names, int max) {
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(max, ids.size()); i++) shown.add(names.apply(ids.get(i)));
        String joined = String.join("/", shown);
        return ids.size() > max ? s("desc.more", joined, ids.size()) : joined;
    }

    /** Translates a server validation code such as {@code bad_target:killer_kills}. */
    static String error(String code) {
        if (code == null || code.isEmpty()) return "";
        int colon = code.indexOf(':');
        String head = colon < 0 ? code : code.substring(0, colon);
        String detail = colon < 0 ? "" : code.substring(colon + 1);
        String key = KEY + "error." + head;
        return Language.getInstance().has(key) ? Component.translatable(key, detail).getString()
                : s("error.generic", code);
    }
}
