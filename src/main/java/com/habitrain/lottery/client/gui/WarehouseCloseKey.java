package com.habitrain.lottery.client.gui;

/** 背包键关闭仓库；长按打开时先等松键，搜索输入和连发不会误关。 */
final class WarehouseCloseKey {
    private boolean waitingForRelease;

    WarehouseCloseKey(boolean waitingForRelease) {
        this.waitingForRelease = waitingForRelease;
    }

    boolean press(boolean inventoryKey, boolean searching) {
        if (!inventoryKey) return false;
        boolean close = !waitingForRelease && !searching;
        waitingForRelease = true;
        return close;
    }

    void release(boolean inventoryKey) {
        if (inventoryKey) waitingForRelease = false;
    }
}
