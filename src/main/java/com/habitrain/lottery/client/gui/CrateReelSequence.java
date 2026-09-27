package com.habitrain.lottery.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

final class CrateReelSequence {
    private CrateReelSequence() {}

    static <T> List<T> sample(List<T> pool, int count, Random random) {
        if (pool.isEmpty() || count <= 0) return List.of();
        List<T> bag = new ArrayList<>(pool);
        List<T> result = new ArrayList<>(count);
        while (result.size() < count) {
            Collections.shuffle(bag, random);
            if (bag.size() > 1 && !result.isEmpty() && bag.get(0).equals(result.get(result.size() - 1))) {
                Collections.swap(bag, 0, 1 + random.nextInt(bag.size() - 1));
            }
            for (T entry : bag) {
                if (result.size() == count) break;
                result.add(entry);
            }
        }
        return List.copyOf(result);
    }
}
