package com.habitrain.lottery.card;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class SelfSelectRoleConfigTest {
    private static final ResourceLocation EXTERNAL = ResourceLocation.parse("external_roles:detective");

    @Test void newAndExternalRolesAreAllowedByDefault() {
        var config = new SelfSelectRoleConfig();
        assertTrue(config.allows(EXTERNAL, UnaryOperator.identity()));
        config.disabledRoles.add("starrailexpress:killer");
        assertTrue(config.allows(EXTERNAL, UnaryOperator.identity()));
        assertFalse(config.allows(ResourceLocation.parse("starrailexpress:killer"), UnaryOperator.identity()));
    }

    @Test void masterSwitchRetainsRestrictionsForReenabling() {
        var config = new SelfSelectRoleConfig();
        config.disabledRoles.add(EXTERNAL.toString());
        assertFalse(config.allows(EXTERNAL, UnaryOperator.identity()));
        config.restrictionsEnabled = false;
        assertTrue(config.allows(EXTERNAL, UnaryOperator.identity()));
        config.restrictionsEnabled = true;
        assertFalse(config.allows(EXTERNAL, UnaryOperator.identity()));
    }

    @Test void oldAliasAndReplacementIdentityCannotBypassEitherRestriction() {
        var old = ResourceLocation.parse("starrailexpress:detective");
        UnaryOperator<ResourceLocation> canonical = id -> id.equals(old) ? EXTERNAL : id;
        var config = new SelfSelectRoleConfig();
        config.disabledRoles.add(old.toString());
        assertFalse(config.allows(EXTERNAL, canonical));
        config.disabledRoles.clear();
        config.disabledRoles.add(EXTERNAL.toString());
        assertFalse(config.allows(old, canonical));
    }

    @Test void unavailableProvidersRemainRestrictedAndCopiesAreIndependent() {
        var config = new SelfSelectRoleConfig();
        config.disabledRoles.add(EXTERNAL.toString());
        config.validate();
        var copy = config.copy();
        copy.disabledRoles.clear();
        assertFalse(config.allows(EXTERNAL, UnaryOperator.identity()));
        assertTrue(copy.allows(EXTERNAL, UnaryOperator.identity()));
    }

    @Test void invalidIdsAndUnknownSchemaAreRejected() {
        var config = new SelfSelectRoleConfig();
        config.disabledRoles.add("missing_namespace");
        assertThrows(IllegalArgumentException.class, config::validate);
        config.disabledRoles.clear();
        config.schemaVersion++;
        assertThrows(IllegalArgumentException.class, config::validate);
    }
}
