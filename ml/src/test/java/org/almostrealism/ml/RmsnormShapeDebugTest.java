/*
 * Copyright 2025 Michael Murray
 */
package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import io.almostrealism.relation.Producer;
import org.junit.Test;

/**
 * Debug test to understand shape propagation in rmsnorm computation.
 */
public class RmsnormShapeDebugTest implements CollectionFeatures, LayerFeatures {

    @Test
    public void testRmsnormShapePropagation() {
        TraversalPolicy inputShape = shape(1, 64);
        int axis = inputShape.getDimensions() - 1;
        int size = 64;

        System.out.println("=== RMSNorm Shape Debug ===");
        System.out.println("Input shape: " + inputShape);
        System.out.println("Axis: " + axis);

        // Create test input with the input shape
        Producer<PackedCollection> testInput = func(inputShape, args -> null);
        System.out.println("Test input shape: " + shape(testInput));

        // Step by step computation
        CollectionProducer in = c(testInput);
        System.out.println("After c(input): " + shape(in));

        CollectionProducer squared = pow(traverseEach(in), c(2.0));
        System.out.println("After pow: " + shape(squared));

        CollectionProducer traversed = squared.traverse(axis);
        System.out.println("After traverse: " + shape(traversed));

        CollectionProducer summed = traversed.sum();
        System.out.println("After sum: " + shape(summed));

        CollectionProducer ss = summed.divide(c(size)).add(c(1e-5));
        System.out.println("After divide/add: " + shape(ss));

        ss = c(1.0).divide(ss.pow(c(0.5)));
        System.out.println("After 1/sqrt: " + shape(ss));

        // Create weights
        PackedCollection weights = new PackedCollection(shape(64)).fill(1.0);
        System.out.println("Weights shape: " + weights.getShape());

        // The problematic multiplication
        CollectionProducer weightedInput = multiply(traverseEach(cp(weights)), traverseEach(in));
        System.out.println("After weights*input multiply: " + shape(weightedInput));
        System.out.println("  Class: " + weightedInput.getClass().getSimpleName());

        CollectionProducer result = weightedInput.multiply(ss);
        System.out.println("After multiply by ss: " + shape(result));
        System.out.println("  Class: " + result.getClass().getSimpleName());

        // The reshape
        System.out.println("\n=== Reshape Tests ===");
        System.out.println("Target shape extent: " + java.util.Arrays.toString(inputShape.extent()));

        CollectionProducer reshaped = result.reshape(inputShape.extent());
        System.out.println("After reshape(extent): " + shape(reshaped));
        System.out.println("  Class: " + reshaped.getClass().getSimpleName());

        // Also try reshape with TraversalPolicy
        CollectionProducer reshaped2 = result.reshape(inputShape);
        System.out.println("After reshape(TraversalPolicy): " + shape(reshaped2));
        System.out.println("  Class: " + reshaped2.getClass().getSimpleName());

        System.out.println("\n=== Validation ===");
        System.out.println("Expected shape: " + inputShape);
        System.out.println("Actual shape: " + shape(reshaped));
        System.out.println("Compatible: " + isShapeCompatible(shape(reshaped), inputShape));
    }

    /**
     * Tests the exact code path used in actual rmsnorm - without c() wrapper.
     */
    @Test
    public void testRmsnormExactCodePath() {
        TraversalPolicy inputShape = shape(1, 64);
        int axis = inputShape.getDimensions() - 1;
        int size = 64;

        System.out.println("\n=== RMSNorm Exact Code Path (no c() wrapper) ===");
        System.out.println("Input shape: " + inputShape);

        // This is exactly what the validation code does
        Producer<PackedCollection> input = func(inputShape, args -> null);
        System.out.println("Test input shape: " + shape(input));

        // This is exactly what rmsnorm does - using input directly
        CollectionProducer ss = pow(traverseEach(input), c(2.0)).traverse(axis).sum();
        System.out.println("After pow/traverse/sum: " + shape(ss));

        ss = ss.divide(c(size)).add(c(1e-5));
        System.out.println("After divide/add: " + shape(ss));

        ss = c(1.0).divide(ss.pow(c(0.5)));
        System.out.println("After 1/sqrt: " + shape(ss));

        // Create weights
        PackedCollection weights = new PackedCollection(shape(64)).fill(1.0);

        // This is the exact multiplication from rmsnorm
        ss = multiply(traverseEach(cp(weights)), traverseEach(input)).multiply(ss);
        System.out.println("After multiply: " + shape(ss));
        System.out.println("  Class: " + ss.getClass().getSimpleName());

        // The reshape
        CollectionProducer result = ss.reshape(inputShape.extent());
        System.out.println("After reshape: " + shape(result));
        System.out.println("  Class: " + result.getClass().getSimpleName());

        System.out.println("\nExpected: " + inputShape);
        System.out.println("Actual: " + shape(result));
        System.out.println("Compatible: " + isShapeCompatible(shape(result), inputShape));
    }

    /**
     * Tests calling the actual rmsnorm layer method.
     */
    @Test
    public void testActualRmsnormLayer() {
        TraversalPolicy inputShape = shape(1, 64);
        PackedCollection weights = new PackedCollection(shape(64)).fill(1.0);

        System.out.println("\n=== Actual rmsnorm layer() call ===");
        System.out.println("Input shape: " + inputShape);

        try {
            CellularLayer layer = rmsnorm(inputShape, weights);
            System.out.println("SUCCESS: Layer created");
            System.out.println("Layer output shape: " + layer.getOutputShape());
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            e.printStackTrace(System.out);
        }
    }
}
