package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Test for causal masking functionality used in attention mechanisms.
 */
public class CausalMaskTest implements AttentionFeatures {

    @Test
    public void testCausalMaskPosition0Shape() {
        int heads = 14;
        int seqLen = 32768;

        PackedCollection<?> mask = createCausalMaskPosition0(heads, seqLen);

        // Verify shape is (heads, seqLen)
        TraversalPolicy expectedShape = shape(heads, seqLen);
        assertEquals("Mask should have correct shape", expectedShape, mask.getShape());
        assertEquals("Mask should have correct total size", heads * seqLen, mask.getMemLength());
    }

    @Test
    public void testCausalMaskPosition0Values() {
        int heads = 14;
        int seqLen = 32768;

        PackedCollection<?> mask = createCausalMaskPosition0(heads, seqLen);

        // Verify position 0 is unmasked (0.0) for all heads
        for (int h = 0; h < heads; h++) {
            double value = mask.toDouble(h * seqLen);
            assertEquals("Position 0 should be unmasked for head " + h,
                0.0, value, 1e-9);
        }

        // Verify positions 1+ are masked (-10000.0) for all heads
        for (int h = 0; h < heads; h++) {
            // Check position 1
            double value1 = mask.toDouble(h * seqLen + 1);
            assertEquals("Position 1 should be masked for head " + h,
                -10000.0, value1, 1e-6);

            // Check position 100 (sample from middle)
            double value100 = mask.toDouble(h * seqLen + 100);
            assertEquals("Position 100 should be masked for head " + h,
                -10000.0, value100, 1e-6);

            // Check last position
            double valueLast = mask.toDouble(h * seqLen + (seqLen - 1));
            assertEquals("Last position should be masked for head " + h,
                -10000.0, valueLast, 1e-6);
        }
    }

    @Test
    public void testCausalMaskPosition0SmallExample() {
        // Test with smaller dimensions for easier verification
        int heads = 2;
        int seqLen = 5;

        PackedCollection<?> mask = createCausalMaskPosition0(heads, seqLen);

        // Expected pattern for 2 heads x 5 positions:
        // Head 0: [0, -10000, -10000, -10000, -10000]
        // Head 1: [0, -10000, -10000, -10000, -10000]

        double[] expected = new double[10];
        expected[0] = 0.0;      // Head 0, pos 0
        expected[1] = -10000.0; // Head 0, pos 1
        expected[2] = -10000.0; // Head 0, pos 2
        expected[3] = -10000.0; // Head 0, pos 3
        expected[4] = -10000.0; // Head 0, pos 4
        expected[5] = 0.0;      // Head 1, pos 0
        expected[6] = -10000.0; // Head 1, pos 1
        expected[7] = -10000.0; // Head 1, pos 2
        expected[8] = -10000.0; // Head 1, pos 3
        expected[9] = -10000.0; // Head 1, pos 4

        for (int i = 0; i < 10; i++) {
            assertEquals("Position " + i + " should have correct mask value",
                expected[i], mask.toDouble(i), 1e-6);
        }
    }

    @Test
    public void testMaskEffectAfterSoftmax() {
        // Demonstrate that -10000 effectively becomes 0 after softmax
        // Simulating: softmax([5.0, 3.0 + (-10000), 1.0 + (-10000)])
        //           = softmax([5.0, -9997, -9999])

        double[] scores = {5.0, -9997.0, -9999.0};

        // Compute softmax manually
        double max = 5.0;
        double[] exp = new double[3];
        double sum = 0.0;

        for (int i = 0; i < 3; i++) {
            exp[i] = Math.exp(scores[i] - max);
            sum += exp[i];
        }

        double[] probs = new double[3];
        for (int i = 0; i < 3; i++) {
            probs[i] = exp[i] / sum;
        }

        // Position 0 should get essentially all probability
        assertTrue("Position 0 should get ~100% probability", probs[0] > 0.999);

        // Masked positions should get essentially 0 probability
        assertTrue("Position 1 should get ~0% probability", probs[1] < 0.001);
        assertTrue("Position 2 should get ~0% probability", probs[2] < 0.001);

        System.out.println("Softmax with causal mask:");
        System.out.printf("  Position 0 (unmasked): %.6f\n", probs[0]);
        System.out.printf("  Position 1 (masked):   %.6f\n", probs[1]);
        System.out.printf("  Position 2 (masked):   %.6f\n", probs[2]);
    }
}
