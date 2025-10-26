package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.PackedCollection;
import org.junit.Test;

/**
 * Test if PackedCollection.clear() works correctly.
 */
public class TestClearMethod {

    @Test
    public void testClearWorks() {
        System.out.println("\n=== Testing PackedCollection.clear() ===");

        // Create a PackedCollection
        PackedCollection<?> cache = new PackedCollection<>(10, 5, 64);

        System.out.println("Before clear:");
        boolean hasNonZero = cache.doubleStream().anyMatch(v -> v != 0.0);
        System.out.println("  Has non-zero values: " + hasNonZero);
        if (hasNonZero) {
            for (int i = 0; i < Math.min(10, cache.getMemLength()); i++) {
                if (cache.toDouble(i) != 0.0) {
                    System.out.println("  cache[" + i + "] = " + cache.toDouble(i));
                }
            }
        }

        // Call clear
        cache.clear();

        System.out.println("\nAfter clear:");
        hasNonZero = cache.doubleStream().anyMatch(v -> v != 0.0);
        System.out.println("  Has non-zero values: " + hasNonZero);
        if (hasNonZero) {
            System.out.println("  WARNING: clear() did not zero the memory!");
            for (int i = 0; i < Math.min(10, cache.getMemLength()); i++) {
                if (cache.toDouble(i) != 0.0) {
                    System.out.println("  cache[" + i + "] = " + cache.toDouble(i));
                }
            }
        } else {
            System.out.println("  [OK] cache.clear() successfully zeroed all values");
        }
    }
}
