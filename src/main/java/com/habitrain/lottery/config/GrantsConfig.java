package com.habitrain.lottery.config;

import java.util.ArrayList;
import java.util.List;

public final class GrantsConfig {
    public List<GrantEvent> events = new ArrayList<>();

    public static final class GrantEvent {
        public String id = "";
        public int amount = 0;
        public List<String> modes = new ArrayList<>();
        public boolean enabled = true;
        public Integer everyLevels;
    }

    public GrantEvent find(String id) {
        if (id == null) {
            return null;
        }
        for (GrantEvent e : events) {
            if (id.equals(e.id)) {
                return e;
            }
        }
        return null;
    }
}
