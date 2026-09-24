package com.habitrain.lottery.client.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DailyBoardLayoutTest {
    private static final int[] WIDTHS = {320, 400, 427, 480, 560, 640, 854, 1280, 1920};
    private static final int[] HEIGHTS = {240, 270, 300, 360, 480, 720, 1080};

    private void inside(BoardRect inner, BoardRect outer) {
        assertTrue(inner.x() >= outer.x() && inner.y() >= outer.y(), inner.toString());
        assertTrue(inner.right() <= outer.right() && inner.bottom() <= outer.bottom(), inner.toString());
    }

    @Test void responsiveNavigationSummaryAndTasksStayReadable() {
        for (int w : WIDTHS) for (int h : HEIGHTS) for (int n : new int[]{0,1,4,12,32}) {
            DailyBoardLayout l = DailyBoardLayout.of(w,h,n);
            inside(l.panel(), new BoardRect(0,0,w,h));
            inside(l.content(), l.panel());
            inside(l.close(), l.panel());
            assertTrue(l.tabFits());
            for (int i=0;i<3;i++) {
                inside(l.tab(i),l.rail());
                assertTrue(l.tab(i).right() <= l.close().x());
                if (i>0) {
                    if(l.sidebar()) assertTrue(l.tab(i-1).bottom() < l.tab(i).y());
                    else assertTrue(l.tab(i-1).right() < l.tab(i).x());
                }
                inside(l.filter(i),l.toolbar());
            }
            if(l.sidebar()) assertTrue(l.rail().right() < l.content().x());
            else assertTrue(l.rail().bottom() < l.content().y());
            for(int i=0;i<DailyBoardLayout.FILTERS;i++) {
                inside(l.filter(i),l.toolbar());
                assertTrue(l.filter(i).w() >= 60);
                if(i>0) assertTrue(l.filter(i-1).right() < l.filter(i).x());
            }
            inside(l.summary(),l.content());
            inside(l.body(),l.content());
            assertTrue(l.summary().bottom() < l.body().y());
            inside(l.title(),l.summary());
            inside(l.subtitle(),l.summary());
            inside(l.clock(),l.summary());
            assertTrue(l.title().right() <= l.clock().x());
            inside(l.list(),l.body());
            assertTrue(l.toolbar().bottom() < l.list().y());
            assertTrue(l.list().w() >= 280);
            assertTrue(l.list().h() >= l.rowH());
            if(l.showFooter()) {
                assertTrue(l.list().bottom() < l.footer().y());
                inside(l.footerAction(),l.footer());
            }
            if(n>0) assertTrue(l.rowY(n-1,l.maxScroll(n))+l.rowH() <= l.list().bottom());
            else assertEquals(0,l.maxScroll(n));
            assertEquals(l.list().y()-10,l.rowY(0,10));
        }
    }
}
