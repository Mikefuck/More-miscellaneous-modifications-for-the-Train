package com.habitrain.lottery.card;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.UnaryOperator;

/** World-owned self-select restrictions. Unlisted roles, including new providers, are allowed. */
public final class SelfSelectRoleConfig {
    public static final int SCHEMA_VERSION = 1;
    public int schemaVersion = SCHEMA_VERSION;
    public long revision;
    public boolean restrictionsEnabled = true;
    public Set<String> disabledRoles = new LinkedHashSet<>();

    public SelfSelectRoleConfig copy() {
        SelfSelectRoleConfig copy = new SelfSelectRoleConfig();
        copy.schemaVersion = schemaVersion;
        copy.revision = revision;
        copy.restrictionsEnabled = restrictionsEnabled;
        copy.disabledRoles = new LinkedHashSet<>(disabledRoles);
        return copy;
    }

    public void validate() {
        if (schemaVersion != SCHEMA_VERSION || revision < 0) throw new IllegalArgumentException("outdated");
        if (disabledRoles == null || disabledRoles.size() > 4096) throw new IllegalArgumentException("malformed");
        for (String id : disabledRoles) {
            if (id == null || id.length() > 256 || !id.contains(":") || ResourceLocation.tryParse(id) == null)
                throw new IllegalArgumentException("malformed");
        }
    }

    /** Resolve both sides so a provider's alias/replacement cannot bypass a stored restriction. */
    public boolean allows(ResourceLocation role, UnaryOperator<ResourceLocation> canonicalize) {
        if (role == null) return false;
        if (!restrictionsEnabled || disabledRoles.isEmpty()) return true;
        ResourceLocation canonical = canonicalize.apply(role);
        for (String disabled : disabledRoles) {
            ResourceLocation id = ResourceLocation.parse(disabled);
            if (id.equals(role) || canonicalize.apply(id).equals(canonical)) return false;
        }
        return true;
    }
}
