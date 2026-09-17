### Task 2: Config models + Rates default 160

**Files:**
- Create: `src/main/java/com/habitrain/lottery/config/ConfigMeta.java`
- Create: `src/main/java/com/habitrain/lottery/config/SkinsQualityConfig.java`
- Modify: `src/main/java/com/habitrain/lottery/config/RatesConfig.java`

**Interfaces:**
- Produces: `ConfigMeta.CURRENT_VERSION = 2`, `ConfigMeta` fields; `SkinsQualityConfig.qualities` map

- [ ] **Step 1: Add ConfigMeta**

```java
package com.habitrain.lottery.config;

public final class ConfigMeta {
    public static final int CURRENT_VERSION = 2;

    public int config_version = CURRENT_VERSION;
    public String migratedAt = "";
}
```

- [ ] **Step 2: Add SkinsQualityConfig**

```java
package com.habitrain.lottery.config;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SkinsQualityConfig {
    public int version = 1;
    public Map<String, String> qualities = new LinkedHashMap<>();
}
```

- [ ] **Step 3: Change RatesConfig default**

In `RatesConfig.java`, set:
```java
public int coinPerDraw = 160;
```

---
