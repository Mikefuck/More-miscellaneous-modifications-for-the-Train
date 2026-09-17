package com.habitrain.lottery.card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

/**
 * Card-group ids used by single-select rotation consumers.
 *
 * <p>{@code ForcePlayerTeam} stores role-type ids (1=civilian, 2=neutral,
 * 3=neutral-for-killer, 4=killer). Consumers expect card-group ids
 * (0=killer, 1=neutral, 2=civilian, 3=neutral-for-killer, -1=no card).
 */
public final class RotationCardGroups {
    public static final int NONE = -1;
    public static final int KILLER = 0;
    public static final int NEUTRAL = 1;
    public static final int CIVILIAN = 2;
    public static final int NEUTRAL_FOR_KILLER = 3;

    private RotationCardGroups() {
    }

    /**
     * Maps a forced role-type id to a card-group id.
     * {@code null} and unknown types are no-card ({@link #NONE}).
     */
    public static int fromForcedType(Integer forcedType) {
        if (forcedType == null) {
            return NONE;
        }
        return switch (forcedType) {
            case 4 -> KILLER;
            case 2 -> NEUTRAL;
            case 1 -> CIVILIAN;
            case 3 -> NEUTRAL_FOR_KILLER;
            default -> NONE;
        };
    }

    /**
     * Whether assigner {@code roleType} belongs to {@code cardGroup}.
     */
    public static boolean matchesRoleType(int cardGroup, int roleType) {
        return switch (cardGroup) {
            case KILLER -> roleType == 4;
            case NEUTRAL -> roleType == 2;
            case CIVILIAN -> roleType == 1;
            case NEUTRAL_FOR_KILLER -> roleType == 3;
            default -> false;
        };
    }

    /**
     * {@code SingleSelectDraftState.assignRotationOrder} plus card-group 3
     * in the killer band (40%–50%). Order: neutral, killer, killer-neutral,
     * civilian, no-card. Group 3 is never dumped into no-card.
     *
     * <p>{@code playerOrder} is pre-seeded with 0 (1-based after assignment),
     * so occupancy is {@code value > 0}, not {@code containsKey}.
     */
    public static void assignRotationOrder(Map<UUID, Integer> playerOrder, Function<UUID, Integer> forcedTypeOf) {
        List<UUID> sortedPlayers = new ArrayList<>(playerOrder.keySet());
        int n = sortedPlayers.size();
        Random random = new Random();

        List<UUID> killerCardUsers = new ArrayList<>();
        List<UUID> killerNeutralCardUsers = new ArrayList<>();
        List<UUID> neutralCardUsers = new ArrayList<>();
        List<UUID> civilianCardUsers = new ArrayList<>();
        List<UUID> noCardUsers = new ArrayList<>();

        for (UUID uuid : sortedPlayers) {
            int card = fromForcedType(forcedTypeOf.apply(uuid));
            switch (card) {
                case KILLER -> killerCardUsers.add(uuid);
                case NEUTRAL -> neutralCardUsers.add(uuid);
                case CIVILIAN -> civilianCardUsers.add(uuid);
                case NEUTRAL_FOR_KILLER -> killerNeutralCardUsers.add(uuid);
                default -> noCardUsers.add(uuid);
            }
        }

        Collections.shuffle(killerCardUsers, random);
        Collections.shuffle(killerNeutralCardUsers, random);
        Collections.shuffle(neutralCardUsers, random);
        Collections.shuffle(civilianCardUsers, random);
        Collections.shuffle(noCardUsers, random);

        int killerStart = (int) Math.floor(n * 0.4);
        int killerEnd = Math.min((int) Math.ceil(n * 0.5), n);
        int neutralStart = 0;
        int neutralEnd = Math.min((int) Math.ceil(n * 0.2), n);
        int civilianStart = (int) Math.floor(n * 0.7);
        int civilianEnd = n;

        Integer[] slots = new Integer[n];

        placeInBandThenNearest(neutralCardUsers, slots, playerOrder, neutralStart, neutralEnd, n);
        placeInBandThenNearest(killerCardUsers, slots, playerOrder, killerStart, killerEnd, n);
        placeInBandThenNearest(killerNeutralCardUsers, slots, playerOrder, killerStart, killerEnd, n);
        placeInBandThenNearest(civilianCardUsers, slots, playerOrder, civilianStart, civilianEnd, n);

        for (UUID uuid : noCardUsers) {
            fillNearestSlot(slots, playerOrder, uuid, n);
        }
    }

    private static void placeInBandThenNearest(
            List<UUID> users,
            Integer[] slots,
            Map<UUID, Integer> playerOrder,
            int bandStart,
            int bandEnd,
            int n) {
        int nextSlot = bandStart;
        for (UUID uuid : users) {
            while (nextSlot < bandEnd && slots[nextSlot] != null) {
                nextSlot++;
            }
            if (nextSlot < bandEnd) {
                slots[nextSlot] = 1;
                playerOrder.put(uuid, nextSlot + 1);
                nextSlot++;
            }
        }
        for (UUID uuid : users) {
            Integer order = playerOrder.get(uuid);
            if (order != null && order > 0) {
                continue;
            }
            fillNearestSlot(slots, playerOrder, uuid, n);
        }
    }

    private static void fillNearestSlot(Integer[] slots, Map<UUID, Integer> playerOrder, UUID uuid, int n) {
        for (int i = 0; i < n; i++) {
            if (slots[i] == null) {
                slots[i] = 1;
                playerOrder.put(uuid, i + 1);
                return;
            }
        }
    }
}
