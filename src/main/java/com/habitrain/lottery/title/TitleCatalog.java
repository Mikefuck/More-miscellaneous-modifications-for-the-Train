package com.habitrain.lottery.title;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Global title template library ({@code titles/catalog.json}).
 */
public final class TitleCatalog {
    public static final int MAX_TITLES = 512;
    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_DISPLAY_LENGTH = 256;

    public int version = 1;
    /** Optimistic concurrency control counter; defaults to {@code 0} when absent in legacy JSON. */
    public long revision = 0L;
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

    /**
     * Validates bounds, uniqueness, and normalizes fields in place.
     *
     * @return {@code null} when valid; otherwise a user-facing error reason
     */
    public String validateAndNormalize() {
        if (titles == null) {
            titles = new ArrayList<>();
        } else {
            titles.removeIf(Objects::isNull);
        }
        if (titles.size() > MAX_TITLES) {
            return "称号模板数量超出上限（最多 " + MAX_TITLES + " 条）";
        }
        Set<String> seen = new HashSet<>(titles.size());
        for (TitleEntry entry : titles) {
            String id = entry.id == null ? "" : entry.id.trim();
            if (id.isEmpty()) {
                return "称号模板 id 不能为空";
            }
            if (id.length() > MAX_ID_LENGTH) {
                return "称号模板 id 过长（最多 " + MAX_ID_LENGTH + " 字符）";
            }
            if (!seen.add(id)) {
                return "称号模板 id 重复: " + id;
            }
            String display = entry.display == null ? "" : entry.display;
            if (display.length() > MAX_DISPLAY_LENGTH) {
                return "称号展示文本过长（最多 " + MAX_DISPLAY_LENGTH + " 字符）: " + id;
            }
            entry.id = id;
            entry.display = display;
        }
        if (version <= 0) {
            version = 1;
        }
        if (revision < 0L) {
            revision = 0L;
        }
        return null;
    }
}
