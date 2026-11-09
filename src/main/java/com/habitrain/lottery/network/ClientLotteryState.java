package com.habitrain.lottery.network;

import java.util.Map;

/**
 * Top-level client-side lottery &amp; admin state snapshot ({@code N-01}).
 *
 * <p>Contains pure data fields with zero client-only class references so common/server tests and
 * shared network helpers can inspect or reset state safely.
 */
public final class ClientLotteryState {
    public static boolean op;
    public static PlayerAdminModels.PlayerListSnapshot playerList = new PlayerAdminModels.PlayerListSnapshot();
    public static int playerListPage;
    public static int playerListTotalPages = 1;
    public static int playerListTotalMatched;
    public static String playerListQuery = "";
    public static int playerListVersion;
    public static String lastBackupJson = "";
    public static int lastBackupVersion;
    public static String lastAdminMessage = "";
    /** Full title catalog JSON from last {@link TitleSnapshotS2C}. */
    public static String titleCatalogJson = "";
    /** Compact paginated players map JSON: {@code uuid -> {name,online,owned,current,revision}}. */
    public static String titlePlayersJson = "";
    public static int titlePlayersPage;
    public static int titlePlayersTotalPages = 1;
    public static int titlePlayersTotalMatched;
    public static String titlePlayersQuery = "";
    public static int titleVersion;
    /** Mailbox list JSON from last {@code MailboxPages.Snapshot} / {@link MailboxListS2C}. */
    public static String mailboxJson = "";
    public static int mailboxVersion;
    public static int mailboxOffset;
    public static int mailboxNext = -1;
    public static int mailboxTotal;
    public static int mailboxUnclaimed = -1;
    public static int greenApples;
    /** Card-use menu state from {@link CardUseMenuS2C}. */
    public static String cardUseMenuQuestKey = "";
    public static int cardUseRemainingUses;
    public static int cardUseRemainingSelfUses;
    public static Map<String, Integer> cardBalances = Map.of();
    public static int cardInventoryVersion;
    public static String cardUseCandidatesJson = "";
    public static int cardUseMenuVersion;

    private ClientLotteryState() {
    }

    public static void resetAll() {
        op = false;
        playerList = new PlayerAdminModels.PlayerListSnapshot();
        playerListPage = 0;
        playerListTotalPages = 1;
        playerListTotalMatched = 0;
        playerListQuery = "";
        playerListVersion++;
        lastBackupJson = "";
        lastBackupVersion++;
        lastAdminMessage = "";
        titleCatalogJson = "";
        titlePlayersJson = "";
        titlePlayersPage = 0;
        titlePlayersTotalPages = 1;
        titlePlayersTotalMatched = 0;
        titlePlayersQuery = "";
        titleVersion++;
        mailboxJson = "";
        mailboxOffset = 0;
        mailboxNext = -1;
        mailboxTotal = 0;
        mailboxUnclaimed = -1;
        mailboxVersion++;
        greenApples = 0;
        cardUseMenuQuestKey = "";
        cardUseRemainingUses = 0;
        cardUseRemainingSelfUses = 0;
        cardBalances = Map.of();
        cardInventoryVersion++;
        cardUseCandidatesJson = "";
        cardUseMenuVersion++;
        ClientLoginState.reset();
    }
}
