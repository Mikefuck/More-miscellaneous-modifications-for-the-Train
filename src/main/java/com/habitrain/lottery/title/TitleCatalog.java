package com.habitrain.lottery.title;

import java.util.ArrayList;
import java.util.List;

/**
 * Global title template library ({@code titles/catalog.json}).
 */
public final class TitleCatalog {
    public int version = 1;
    public List<TitleEntry> titles = new ArrayList<>();

    public static final class TitleEntry {
        public String id = "";
        public String display = "";
        public boolean enabled = true;

        public TitleEntry() {
        }

        public TitleEntry(String id, String display, boolean enabled) {
            this.id = id == null ? "" : id;
            this.display = display == null ? "" : display;
            this.enabled = enabled;
        }
    }
}
