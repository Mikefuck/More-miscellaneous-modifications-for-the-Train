package com.habitrain.lottery.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** DTOs shared by player-admin network packets (no Minecraft classes). */
public final class PlayerAdminModels {
    private PlayerAdminModels() {
    }

    public static final class PlayerRow {
        public String name = "";
        public String uuid = "";
        public int lootChance;
        public int coinNum;
        public int unlockCount;
        public boolean online;
        public String cardStatus = "MISSING";
        public Map<String, Integer> cards = new LinkedHashMap<>();

        public PlayerRow() {
        }

        public PlayerRow(String name, UUID uuid, int lootChance, int coinNum, int unlockCount, boolean online) {
            this.name = name == null ? "" : name;
            this.uuid = uuid == null ? "" : uuid.toString();
            this.lootChance = lootChance;
            this.coinNum = coinNum;
            this.unlockCount = unlockCount;
            this.online = online;
        }
    }

    public static final class PlayerListSnapshot {
        public List<PlayerRow> players = new ArrayList<>();
        public long serverTime;
    }
}
