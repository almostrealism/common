package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Model;
import org.almostrealism.model.CompiledModel;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Test to verify that the causal mask lambda approach works correctly
 * in a minimal model without any transformer complexity.
 */
public class CausalMaskIsolationTest implements AttentionFeatures {

    @Test
    public void testCausalMaskInMinimalModel() {
        System.out.println("\n=== Causal Mask Isolation Test ===\n");

        int heads = 4;
        int seqLen = 8;

        // Create a position tracker
        PackedCollection<?> position = new PackedCollection<>(shape(1));
        position.setMem(0, 0.0); // Start at position 0

        // Create a simple model that just adds the causal mask to the input
        Model model = new Model(shape(heads, seqLen));

        // Generate causal mask using the same approach as in attention()
        CollectionProducer<?> indices = integers(0, seqLen);
        CollectionProducer<PackedCollection<?>> maskRow =
            greaterThan(indices, cp(position), c(-10000.0), c(0.0), false);
        CollectionProducer<PackedCollection<?>> causalMask = maskRow.reshape(1, seqLen).repeat(heads);

        // Add the mask using the layer approach
        TraversalPolicy maskShape = shape(heads, seqLen);
        model.add(layer("causal_mask", maskShape, maskShape, input -> add(input, causalMask)));

        // Compile the model
        CompiledModel compiled = model.compile(false);

        // Test at position 0
        System.out.println("Testing at position 0:");
        position.setMem(0, 0.0);

        PackedCollection<?> input0 = new PackedCollection<>(shape(heads, seqLen));
        input0.fill(Math::random); // Random input

        PackedCollection<?> output0 = compiled.forward(input0);

        System.out.println("Input[0,0]: " + input0.toDouble(0));
        System.out.println("Output[0,0]: " + output0.toDouble(0));
        System.out.println("Expected: input[0,0] + 0 = " + input0.toDouble(0));

        // Position 0 should be unmasked (input + 0)
        assertEquals("Position 0 should be unmasked",
            input0.toDouble(0), output0.toDouble(0), 1e-6);

        System.out.println("\nInput[0,1]: " + input0.toDouble(1));
        System.out.println("Output[0,1]: " + output0.toDouble(1));
        System.out.println("Expected: input[0,1] + (-10000) = " + (input0.toDouble(1) - 10000.0));

        // Position 1 should be masked (input + -10000)
        assertEquals("Position 1 should be masked",
            input0.toDouble(1) - 10000.0, output0.toDouble(1), 1e-6);

        // Test at position 2
        System.out.println("\n\nTesting at position 2:");
        position.setMem(0, 2.0);

        PackedCollection<?> input2 = new PackedCollection<>(shape(heads, seqLen));
        input2.fill(Math::random);

        PackedCollection<?> output2 = compiled.forward(input2);

        // Positions 0, 1, 2 should be unmasked
        for (int i = 0; i <= 2; i++) {
            System.out.println("Position " + i + ": input=" + input2.toDouble(i) +
                             ", output=" + output2.toDouble(i) +
                             ", expected=" + input2.toDouble(i));
            assertEquals("Position " + i + " should be unmasked at position 2",
                input2.toDouble(i), output2.toDouble(i), 1e-6);
        }

        // Position 3 should be masked
        System.out.println("Position 3: input=" + input2.toDouble(3) +
                         ", output=" + output2.toDouble(3) +
                         ", expected=" + (input2.toDouble(3) - 10000.0));
        assertEquals("Position 3 should be masked at position 2",
            input2.toDouble(3) - 10000.0, output2.toDouble(3), 1e-6);

        System.out.println("\n[OK] Causal mask lambda approach works correctly!");
    }

    @Test
    public void testCausalMaskDynamicPositionUpdates() {
        System.out.println("\n=== Testing Dynamic Position Updates ===\n");

        int heads = 2;
        int seqLen = 5;

        // Create a position tracker
        PackedCollection<?> position = new PackedCollection<>(shape(1));
        position.setMem(0, 0.0);

        // Create model with causal mask
        Model model = new Model(shape(heads, seqLen));

        CollectionProducer<?> indices = integers(0, seqLen);
        CollectionProducer<PackedCollection<?>> maskRow =
            greaterThan(indices, cp(position), c(-10000.0), c(0.0), false);
        CollectionProducer<PackedCollection<?>> causalMask = maskRow.reshape(1, seqLen).repeat(heads);

        TraversalPolicy maskShape = shape(heads, seqLen);
        model.add(layer("causal_mask", maskShape, maskShape, input -> add(input, causalMask)));

        CompiledModel compiled = model.compile(false);

        // Create input (all ones for easy verification)
        PackedCollection<?> input = new PackedCollection<>(shape(heads, seqLen));
        input.fill(pos -> 1.0);

        // Test at different positions
        for (int pos = 0; pos < seqLen; pos++) {
            System.out.println("\nPosition " + pos + ":");
            position.setMem(0, (double) pos);

            PackedCollection<?> output = compiled.forward(input);

            // Check first head
            for (int i = 0; i < seqLen; i++) {
                double expected = (i <= pos) ? 1.0 : (1.0 - 10000.0);
                double actual = output.toDouble(i);

                System.out.printf("  [%d]: expected=%.1f, actual=%.1f %s\n",
                    i, expected, actual, (i <= pos) ? "OK" : "MASKED");

                assertEquals("At position " + pos + ", index " + i,
                    expected, actual, 1e-6);
            }
        }

        System.out.println("\n[OK] Dynamic position updates work correctly!");
    }
}
