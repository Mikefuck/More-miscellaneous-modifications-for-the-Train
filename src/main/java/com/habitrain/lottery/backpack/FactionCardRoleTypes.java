package com.habitrain.lottery.backpack;

import io.wifi.starrailexpress.progression.ProgressionState.FactionCardType;

/**
 * Maps faction cards to {@link org.agmas.harpymodloader.modded_murder.PlayerRoleWeightManager}
 * role-type ids used by {@code ForcePlayerTeam} during assignment.
 *
 * <p>SRE's enum stores broken ids (KILLER=1, CIVILIAN=2, NEUTRAL=3) which do not match the
 * assigner:
 * <ul>
 *   <li>1 = innocent / civilian</li>
 *   <li>2 = neutral (not for killer)</li>
 *   <li>3 = neutral for killer</li>
 *   <li>4 = killer</li>
 *   <li>5 = vigilante</li>
 * </ul>
 */
public final class FactionCardRoleTypes {
    private FactionCardRoleTypes() {
    }

    public static int roleTypeId(FactionCardType type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case CIVILIAN -> 1;
            case NEUTRAL -> 2;
            case NEUTRAL_FOR_KILLER -> 3;
            case KILLER -> 4;
            case NONE -> 0;
        };
    }

    public static FactionCardType fromRoleTypeId(int roleType) {
        return switch (roleType) {
            case 1 -> FactionCardType.CIVILIAN;
            case 2 -> FactionCardType.NEUTRAL;
            case 3 -> FactionCardType.NEUTRAL_FOR_KILLER;
            case 4 -> FactionCardType.KILLER;
            default -> FactionCardType.NONE;
        };
    }
}
