package com.habitrain.lottery.client.gui.config;

/** Stable navigation ids and translation keys for the nine console sections. */
public enum ConfigSectionId {
    POOLS(Group.LOTTERY, "pools"),
    RATES(Group.LOTTERY, "rates"),
    GRANTS(Group.LOTTERY, "grants"),
    THEME(Group.LOTTERY, "theme"),
    PLAYERS(Group.PLAYERS_AND_CONTENT, "players"),
    TITLES(Group.PLAYERS_AND_CONTENT, "titles"),
    SKINS(Group.PLAYERS_AND_CONTENT, "skins"),
    MAIL(Group.OPERATIONS, "mail"),
    JSON(Group.OPERATIONS, "json");

    public enum Group {
        LOTTERY("lottery"),
        PLAYERS_AND_CONTENT("players_and_content"),
        OPERATIONS("operations");

        private final String key;

        Group(String key) {
            this.key = key;
        }

        public String translationKey() {
            return "screen.habitrain_lottery.config.group." + key;
        }
    }

    private final Group group;
    private final String key;

    ConfigSectionId(Group group, String key) {
        this.group = group;
        this.key = key;
    }

    public Group group() {
        return group;
    }

    public String titleKey() {
        return "screen.habitrain_lottery.config.section." + key;
    }

    public String descriptionKey() {
        return titleKey() + ".description";
    }
}
