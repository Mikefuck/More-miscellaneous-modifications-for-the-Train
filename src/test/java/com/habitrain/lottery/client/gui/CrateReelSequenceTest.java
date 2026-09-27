package com.habitrain.lottery.client.gui;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CrateReelSequenceTest {
    @Test void usesEverySkinBeforeRepeatingAndChangesBetweenOpens() {
        List<Integer> pool = java.util.stream.IntStream.range(0, 24).boxed().toList();
        List<Integer> first = CrateReelSequence.sample(pool, 24, new Random(1));
        List<Integer> second = CrateReelSequence.sample(pool, 24, new Random(2));

        assertEquals(24, new HashSet<>(first).size());
        assertEquals(new HashSet<>(pool), new HashSet<>(first));
        assertNotEquals(first, second);
    }

    @Test void smallPoolsCycleWithoutAdjacentDuplicates() {
        for (int poolSize : new int[]{2, 3, 5, 7, 14}) {
            List<Integer> pool = java.util.stream.IntStream.range(0, poolSize).boxed().toList();
            List<Integer> sequence = CrateReelSequence.sample(pool, 24, new Random(poolSize));
            assertEquals(24, sequence.size());
            for (int i = 1; i < sequence.size(); i++) {
                assertNotEquals(sequence.get(i - 1), sequence.get(i));
            }
            for (int start = 0; start + poolSize <= sequence.size(); start += poolSize) {
                assertEquals(poolSize, new HashSet<>(sequence.subList(start, start + poolSize)).size());
            }
        }
    }
}
