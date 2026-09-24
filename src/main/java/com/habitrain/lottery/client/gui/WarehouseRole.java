package com.habitrain.lottery.client.gui;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import org.agmas.noellesroles.utils.RoleUtils;

/** Role metadata shared by the warehouse and its integrated selection grid. */
public final class WarehouseRole {
    public String id, name, bound;
    public int color;
    public boolean taken;
    public String displayName() { return resolveRoleDisplayName(id, name); }
    public boolean isBound() { return bound != null && !bound.isBlank(); }
    public String boundDisplayName() { return resolveBoundRoleDisplayName(bound); }
    public String cardArt() {
        try {
            var key = parseRoleId(id);
            var role = key == null ? null : RoleCatalogApi.instance().find(RoleKey.of(key)).orElse(null);
            if (role != null && role.profile() != null) {
                var p = role.profile();
                if (p.canUseKiller()) return "killer";
                if (p.innocent() && !p.neutral() || p.vigilanteTeam()) return "civilian";
                return p.neutralForKiller() ? "neutral_for_killer" : "neutral";
            }
        } catch (RuntimeException | LinkageError ignored) { }
        return "self_select";
    }
    private static ResourceLocation parseRoleId(String roleIdentifier) {
        ResourceLocation roleId = ResourceLocation.tryParse(roleIdentifier);
        if (roleId == null && !roleIdentifier.contains(":")) {
            roleId = ResourceLocation.tryParse("starrailexpress:" + roleIdentifier);
        }
        return roleId;
    }

    public static String resolveRoleDisplayName(String roleIdentifier, String rawName) {
        if (roleIdentifier != null && !roleIdentifier.isBlank()) {
            ResourceLocation roleId = parseRoleId(roleIdentifier);
            if (roleId != null) {
                // 1. habitrain_core RoleCatalogApi
                try {
                    EffectiveRole effective = RoleCatalogApi.instance().find(RoleKey.of(roleId)).orElse(null);
                    if (effective != null && effective.role() != null && effective.role().getName() != null) {
                        String effName = effective.role().getName().getString();
                        if (isMeaningfulRoleName(effName, roleId)) {
                            return effName;
                        }
                    }
                } catch (Throwable e) {
                    // catalog unavailable in unit tests / classload
                }

                // 2. SRE RoleUtils
                try {
                    Component ruName = RoleUtils.getRoleName(roleId);
                    if (ruName != null) {
                        String s = ruName.getString();
                        if (isMeaningfulRoleName(s, roleId)) {
                            return s;
                        }
                    }
                } catch (Throwable ignored) {
                }

                // 4. Client Language dictionary
                Language lang = Language.getInstance();
                String key1 = "announcement.star.role." + roleId.getPath();
                if (lang.has(key1)) {
                    return lang.getOrDefault(key1);
                }
                String key2 = "announcement.star.role." + roleId.toString();
                if (lang.has(key2)) {
                    return lang.getOrDefault(key2);
                }
                String key3 = "role." + roleId.getNamespace() + "." + roleId.getPath();
                if (lang.has(key3)) {
                    return lang.getOrDefault(key3);
                }
                String key4 = "role." + roleId.getPath();
                if (lang.has(key4)) {
                    return lang.getOrDefault(key4);
                }
                String key5 = "role." + roleId.toString();
                if (lang.has(key5)) {
                    return lang.getOrDefault(key5);
                }
                String key6 = "sre.role." + roleId.getPath();
                if (lang.has(key6)) {
                    return lang.getOrDefault(key6);
                }
            }
        }

        // 5. Check rawName
        if (rawName != null && !rawName.isBlank()) {
            Language lang = Language.getInstance();
            if (lang.has(rawName)) {
                return lang.getOrDefault(rawName);
            }
            if (!rawName.startsWith("announcement.") && !rawName.startsWith("role.") && !rawName.startsWith("sre.")) {
                return rawName;
            }
        }

        // 6. Fallback to path or id
        if (roleIdentifier != null && !roleIdentifier.isBlank()) {
            int colon = roleIdentifier.indexOf(':');
            return colon >= 0 && colon + 1 < roleIdentifier.length()
                    ? roleIdentifier.substring(colon + 1)
                    : roleIdentifier;
        }
        return Component.translatable("screen.habitrain_lottery.role_select.unknown").getString();
    }

    public static String resolveBoundRoleDisplayName(String bound) {
        if (bound == null || bound.isBlank()) {
            return "";
        }
        return resolveRoleDisplayName(bound, null);
    }

    private static boolean isMeaningfulRoleName(String text, ResourceLocation roleId) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        if (roleId != null) {
            if (trimmed.equalsIgnoreCase(roleId.toString()) || trimmed.equalsIgnoreCase(roleId.getPath())) {
                return false;
            }
        }
        if (trimmed.startsWith("announcement.star.role.") || trimmed.startsWith("role.")
                || trimmed.startsWith("sre.role.")) {
            return false;
        }
        return true;
    }

}
