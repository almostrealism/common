package org.almostrealism.ml;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Test for dynamic causal mask generation using Producer operations.
 */
public class DynamicCausalMaskTest extends TestSuiteBase implements AttentionFeatures {

	@Test
	public void testDynamicMaskAtPosition2() {
		int seqLen = 10;
		int currentPosition = 2;

		// Create position as a PackedCollection
		PackedCollection positionValue = new PackedCollection(shape(1));
		positionValue.setMem(0, currentPosition);

		// Create the dynamic mask using Producer operations
		// integers(0, seqLen) creates [0, 1, 2, ..., seqLen-1]
		// greaterThan(index, position, trueValue, falseValue) returns trueValue if index > position, else falseValue

		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer position = cp(positionValue);

		// Create mask: if index > position then -10000 else 0
		// Use 5-parameter version: greaterThan(a, b, trueValue, falseValue, includeEqual)
		CollectionProducer mask = greaterThan(indices, position, c(-10000.0), c(0.0), false);

		// Evaluate the mask
		PackedCollection result = mask.get().evaluate();

		System.out.println("Position: " + currentPosition);
		System.out.println("Mask values:");
		for (int i = 0; i < seqLen; i++) {
			double value = result.toDouble(i);
			System.out.printf("  [%d]: %.1f\n", i, value);
		}

		// Verify: positions 0-2 should be 0.0, positions 3-9 should be -10000.0
		for (int i = 0; i <= currentPosition; i++) {
			assertEquals("Position " + i + " should be unmasked",
					0.0, result.toDouble(i), 1e-6);
		}

		for (int i = currentPosition + 1; i < seqLen; i++) {
			assertEquals("Position " + i + " should be masked",
					-10000.0, result.toDouble(i), 1e-6);
		}
	}

	@Test
	public void testDynamicMaskAtDifferentPositions() {
		int seqLen = 8;

		for (int pos = 0; pos < 5; pos++) {
			PackedCollection positionValue = new PackedCollection(shape(1));
			positionValue.setMem(0, pos);

			CollectionProducer indices = integers(0, seqLen);
			CollectionProducer position = cp(positionValue);
			CollectionProducer mask = greaterThan(indices, position, c(-10000.0), c(0.0), false);

			PackedCollection result = mask.get().evaluate();

			System.out.println("\nPosition " + pos + ":");
			for (int i = 0; i < seqLen; i++) {
				System.out.printf("  [%d]: %.1f\n", i, result.toDouble(i));
			}

			// Verify mask pattern
			for (int i = 0; i <= pos; i++) {
				assertEquals("At position " + pos + ", index " + i + " should be unmasked",
						0.0, result.toDouble(i), 1e-6);
			}
			for (int i = pos + 1; i < seqLen; i++) {
				assertEquals("At position " + pos + ", index " + i + " should be masked",
						-10000.0, result.toDouble(i), 1e-6);
			}
		}
	}

	@Test
	public void testDynamicMaskWithHeads() {
		int heads = 14;
		int seqLen = 32768;
		int currentPosition = 5;

		PackedCollection positionValue = new PackedCollection(shape(1));
		positionValue.setMem(0, currentPosition);

		// Create mask for all heads
		// Shape should be (heads, seqLen)
		CollectionProducer indices = integers(0, seqLen);
		CollectionProducer position = cp(positionValue);
		CollectionProducer maskRow = greaterThan(indices, position, c(-10000.0), c(0.0), false);

		// Repeat for all heads using the same pattern as AttentionFeatures:
		// reshape to (1, seqLen), repeat to get (heads, seqLen)
		CollectionProducer mask = maskRow.reshape(1, seqLen).repeat(heads).reshape(heads, seqLen);

		PackedCollection result = mask.get().evaluate();

		System.out.println("\nMask with heads:");
		System.out.println("  Shape: " + result.getShape());
		System.out.println("  Total size: " + result.getMemLength());

		// Verify shape
		assertEquals("Should have correct total size", heads * seqLen, result.getMemLength());

		// Verify a few values from first head
		assertEquals("Head 0, position 0 should be unmasked", 0.0, result.toDouble(0), 1e-6);
		assertEquals("Head 0, position 5 should be unmasked", 0.0, result.toDouble(5), 1e-6);
		assertEquals("Head 0, position 6 should be masked", -10000.0, result.toDouble(6), 1e-6);
		assertEquals("Head 0, position 100 should be masked", -10000.0, result.toDouble(100), 1e-6);

		// Verify a few values from last head
		int lastHeadOffset = (heads - 1) * seqLen;
		assertEquals("Last head, position 0 should be unmasked", 0.0, result.toDouble(lastHeadOffset), 1e-6);
		assertEquals("Last head, position 5 should be unmasked", 0.0, result.toDouble(lastHeadOffset + 5), 1e-6);
		assertEquals("Last head, position 6 should be masked", -10000.0, result.toDouble(lastHeadOffset + 6), 1e-6);
	}
}
