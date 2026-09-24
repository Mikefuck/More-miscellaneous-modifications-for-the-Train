package com.habitrain.lottery.test;

import com.habitrain.lottery.client.gui.WarehouseScreen;
import com.habitrain.lottery.client.gui.WarehouseRole;
import com.habitrain.lottery.network.WarehouseNetwork;
import com.habitrain.lottery.network.LotteryNetwork.ClientLotteryState;
import com.habitrain.lottery.warehouse.WarehouseEntry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.Util;
import java.util.*;

/** Test-only fixtures rendered by the actual production Screen, widgets and item pipeline. */
public final class WarehouseSmoke implements ClientModInitializer {
    int ticks;
    WarehouseScreen screen;
    static Object get(Object o, String name) throws Exception {
        var f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    static void set(Object o, String name, Object v) throws Exception {
        var f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, v);
    }
    static void invoke(Object o, String name, Class<?> type, Object arg) throws Exception {
        var m = o.getClass().getDeclaredMethod(name, type); m.setAccessible(true); m.invoke(o, arg);
    }
    void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), message ->
                com.habitrain.lottery.HabiLotteryMod.LOGGER.info("WAREHOUSE_CAPTURE {} {}", name, message.getString()));
    }
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.getOverlay() != null || mc.screen == null) return;
            try {
                // No world is running in this visual fixture; emulate the lobby state only here.
                var guard=Class.forName("com.habitrain.lottery.client.gui.CardGuiGameState");
                var cached=guard.getDeclaredField("cachedValue"); cached.setAccessible(true); cached.setBoolean(null,false);
                var at=guard.getDeclaredField("cachedAtNanos"); at.setAccessible(true); at.setLong(null,System.nanoTime());
                ++ticks;
                if (ticks == 30) {
                    mc.options.languageCode = "zh_cn";
                    mc.getLanguageManager().setSelected("zh_cn");
                    mc.reloadResourcePacks();
                }
                if (ticks == 70) {
                    mc.options.guiScale().set(2);
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 1280, 720);
                    mc.resizeDisplay();
                    screen = new WarehouseScreen(mc.screen); mc.setScreen(screen);
                    List<WarehouseEntry> rows = new ArrayList<>();
                    rows.add(new WarehouseEntry("currency","coins", "金币", "通过系统奖励与对局结算获得，可兑换抽奖次数。", "minecraft:gold_ingot", 2680, 0xFFCABB85, false));
                    rows.add(new WarehouseEntry("currency","draws", "抽奖次数", "账户剩余抽奖次数。", "minecraft:paper", 16, 0xFF85B4C8, false));
                    var balances = new LinkedHashMap<String,Integer>();
                    String[] ids={"civilian","neutral","neutral_for_killer","killer","self_select","limit_break"};
                    for (int i=0;i<ids.length;i++) {
                        balances.put(ids[i], i==5 ? 0 : i+2);
                        rows.add(new WarehouseEntry("card",ids[i], "screen.habitrain_lottery.config.cards."+ids[i], "screen.habitrain_lottery.warehouse.card_hint."+ids[i], "minecraft:paper", balances.get(ids[i]), 0, false));
                    }
                    String[] items={"amethyst_shard","echo_shard","emerald","book","firework_star","heart_of_the_sea","prismarine_crystals","nether_star","trial_key","experience_bottle","clock","compass"};
                    String[] names={"周年纪念晶石","回声碎片","活动兑换券","列车补给凭证","星芒徽记","深海纪念章","虹光碎晶","星之勋章","特典钥匙","经验加成券","限时挑战券","探索纪念章"};
                    for(int i=0;i<items.length;i++) rows.add(new WarehouseEntry("special","test:"+items[i],names[i],"系统发放的特殊道具。保存在账户仓库，可用于对应活动。", "minecraft:"+items[i],i+1,0xFF84A6AD + i*500,false));
                    rows.add(new WarehouseEntry("title","pioneer","列车先驱","已拥有的称号，前往衣柜装备。","minecraft:name_tag",1,0xFFC7B780,true));
                    set(screen,"loading",true); set(screen,"requestId",73); set(screen,"expectedTotal",-1);
                    screen.receive(new WarehouseNetwork.Snapshot(73,0,rows.size(),"",rows.subList(0,5)));
                    if ((boolean)get(screen,"known")) throw new AssertionError("Partial inventory became visible");
                    screen.receive(new WarehouseNetwork.Snapshot(72,5,rows.size(),"",rows.subList(5,rows.size())));
                    if ((boolean)get(screen,"known")) throw new AssertionError("Stale request replaced current inventory");
                    screen.receive(new WarehouseNetwork.Snapshot(73,5,rows.size(),"",rows.subList(5,rows.size())));
                    ClientLotteryState.cardBalances=balances;
                    ClientLotteryState.cardUseRemainingUses=3; ClientLotteryState.cardUseRemainingSelfUses=2;
                    set(screen,"cardsKnown",true); set(screen,"error","");
                }
                if (ticks == 95) shot(mc,"warehouse-wide");
                if (ticks == 96) {
                    Class<?> p = Class.forName("com.habitrain.lottery.client.gui.WarehouseScreen$Page");
                    invoke(screen,"switchPage",p,Enum.valueOf((Class)p,"CARDS"));
                }
                if (ticks == 115) shot(mc,"warehouse-cards");
                if (ticks == 116) {
                    var tiles = (List<?>)get(screen,"tiles");
                    invoke(screen,"openDetail",tiles.get(0).getClass(),tiles.get(0));
                }
                if (ticks == 135) shot(mc,"warehouse-card-detail");
                if (ticks == 136) {
                    var m=screen.getClass().getDeclaredMethod("closeDetailImmediately");m.setAccessible(true);m.invoke(screen);
                    mc.options.guiScale().set(3);
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),960,720);
                    mc.resizeDisplay();
                }
                if (ticks == 156) shot(mc,"warehouse-small");
                if (ticks == 157) {
                    var tiles = (List<?>)get(screen,"tiles");
                    invoke(screen,"openDetail",tiles.get(0).getClass(),tiles.get(0));
                }
                if (ticks == 178) shot(mc,"warehouse-small-detail");
                if (ticks == 180) screen.mouseScrolled(900 / 3d, 360 / 3d, 0, -6);
                if (ticks == 190) shot(mc,"warehouse-small-detail-scrolled");
                if (ticks == 191) {
                    var m=screen.getClass().getDeclaredMethod("closeDetailImmediately");m.setAccessible(true);m.invoke(screen);
                    mc.options.guiScale().set(2);
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),1280,720);
                    mc.resizeDisplay();
                    var candidates = new ArrayList<WarehouseRole>();
                    for(int i=0;i<16;i++) {
                        var r=new WarehouseRole(); r.id="test:role_"+i; r.name="职业 "+(i+1);
                        r.color=0xFFD4B869; r.bound=i%3==0?"test:companion":""; r.taken=i%4==0;
                        candidates.add(r);
                    }
                    set(screen,"roles",candidates);
                    Class<?> p=Class.forName("com.habitrain.lottery.client.gui.WarehouseScreen$Page");
                    invoke(screen,"switchPage",p,Enum.valueOf((Class)p,"ROLES"));
                }
                if (ticks == 211) shot(mc,"warehouse-role-select");
                if (ticks == 212) {
                    screen.keyPressed(264,0,0);
                    screen.keyPressed(264,0,0);
                    screen.keyPressed(257,0,0);
                    if (get(screen,"detail")==null) throw new AssertionError("Keyboard detail activation failed");
                }
                if (ticks == 232) shot(mc,"warehouse-role-detail");
                if (ticks == 233) {
                    screen.keyPressed(256,0,0);
                }
                if (ticks == 240 && get(screen,"detail")!=null) throw new AssertionError("Escape did not close detail");
                if (ticks == 245) { com.habitrain.lottery.HabiLotteryMod.LOGGER.info("WAREHOUSE_SMOKE_OK: chunk correlation, keyboard, scroll, seven-column and compact layouts"); mc.stop(); }
            } catch (Exception e) { throw new RuntimeException("Warehouse GUI smoke failed",e); }
        });
    }
}
