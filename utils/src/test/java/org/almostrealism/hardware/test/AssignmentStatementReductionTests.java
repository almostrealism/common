/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.hardware.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests for verifying the statement reduction feature in {@link org.almostrealism.collect.CollectionFeatures#a}.
 *
 * <p>When large tensor shapes are used in assignments, the number of statements in the
 * compiled {@link io.almostrealism.scope.Scope} can exceed practical limits. The statement reduction feature
 * adjusts the traversal axis to reduce the number of statements per kernel invocation
 * while increasing the number of kernel invocations.</p>
 *
 * <p>Key relationships tested:</p>
 * <ul>
 *   <li>Assignment memLength = shape(result).getSize()</li>
 *   <li>Scope statements = memLength statements in Assignment.getScope()</li>
 *   <li>When size > preferredStatements, traversal axis increases to reduce size</li>
 * </ul>
 *
 * @see Assignment
 * @see ScopeSettings#preferredStatements
 */
public class AssignmentStatementReductionTests extends TestSuiteBase {

	/**
	 * Extracts memLength from Assignment.describe() output.
	 * Format: "{shortDescription} ({count}x{memLength})"
	 */
	private int extractMemLength(Assignment<?> assignment) {
		String description = assignment.describe();
		// Pattern matches "({count}x{memLength})" at the end
		Pattern pattern = Pattern.compile("\\((\\d+)x(\\d+)\\)$");
		Matcher matcher = pattern.matcher(description);
		if (matcher.find()) {
			return Integer.parseInt(matcher.group(2));
		}
		throw new IllegalArgumentException("Could not extract memLength from: " + description);
	}

	/**
	 * Extracts count from Assignment.describe() output.
	 * Format: "{shortDescription} ({count}x{memLength})"
	 */
	private int extractCount(Assignment<?> assignment) {
		String description = assignment.describe();
		Pattern pattern = Pattern.compile("\\((\\d+)x(\\d+)\\)$");
		Matcher matcher = pattern.matcher(description);
		if (matcher.find()) {
			return Integer.parseInt(matcher.group(1));
		}
		throw new IllegalArgumentException("Could not extract count from: " + description);
	}

	/**
	 * Verifies that shapes smaller than preferredStatements are not adjusted.
	 */
	@Test
	public void testSmallShapeNoAdjustment() {
		int size = 64;
		assertTrue("Test assumes size < preferredStatements",
				size < ScopeSettings.preferredStatements);

		PackedCollection input = tensor(shape(size)).pack();
		PackedCollection output = new PackedCollection(shape(size));

		Assignment<PackedCollection> assignment = a(p(output), p(input));

		int memLength = extractMemLength(assignment);
		int count = extractCount(assignment);

		log("Small shape (" + size + ") - memLength: " + memLength + ", count: " + count);
		log("Assignment describe: " + assignment.describe());

		assertEquals("memLength should equal shape size for small shapes", size, memLength);
		assertEquals("count should be 1 for small shapes", 1, count);
	}

	/**
	 * Verifies that large shapes trigger traversal adjustment to reduce memLength.
	 */
	@Test
	public void testLargeShapeAdjustment() {
		int size = 1024;
		assertTrue("Test assumes size > preferredStatements",
				size > ScopeSettings.preferredStatements);

		PackedCollection input = tensor(shape(size)).pack();
		PackedCollection output = new PackedCollection(shape(size));

		Assignment<PackedCollection> assignment = a(p(output), p(input));

		int memLength = extractMemLength(assignment);
		int count = extractCount(assignment);

		log("Large shape (" + size + ") - memLength: " + memLength + ", count: " + count);
		log("Preferred statements limit: " + ScopeSettings.preferredStatements);
		log("Assignment describe: " + assignment.describe());

		assertTrue("memLength should be <= preferredStatements for large shapes",
				memLength <= ScopeSettings.preferredStatements);
		assertTrue("count * memLength should equal total size",
				count * memLength == size);
	}

	/**
	 * Tests shape exactly at the preferredStatements boundary.
	 */
	@Test
	public void testExactBoundary() {
		int size = ScopeSettings.preferredStatements;

		PackedCollection input = tensor(shape(size)).pack();
		PackedCollection output = new PackedCollection(shape(size));

		Assignment<PackedCollection> assignment = a(p(output), p(input));

		int memLength = extractMemLength(assignment);
		int count = extractCount(assignment);

		log("Exact boundary shape (" + size + ") - memLength: " + memLength + ", count: " + count);

		// At exact boundary, no adjustment should be needed
		assertEquals("memLength should equal shape size at exact boundary", size, memLength);
		assertEquals("count should be 1 at exact boundary", 1, count);
	}

	/**
	 * Tests shape just over the boundary triggers adjustment.
	 */
	@Test
	public void testJustOverBoundary() {
		int size = ScopeSettings.preferredStatements + 1;

		PackedCollection input = tensor(shape(size)).pack();
		PackedCollection output = new PackedCollection(shape(size));

		Assignment<PackedCollection> assignment = a(p(output), p(input));

		int memLength = extractMemLength(assignment);
		int count = extractCount(assignment);

		log("Just over boundary shape (" + size + ") - memLength: " + memLength + ", count: " + count);

		assertTrue("memLength should be <= preferredStatements when just over boundary",
				memLength <= ScopeSettings.preferredStatements);
	}

	/**
	 * Tests multi-dimensional shapes for correct axis selection.
	 */
	@Test
	public void testMultiDimensionalShape() {
		// Shape (4, 256) at axis=0 has size=1024, at axis=1 has size=256
		int dim0 = 4;
		int dim1 = 256;
		int totalSize = dim0 * dim1;

		TraversalPolicy shape = shape(dim0, dim1);
		log("Multi-dimensional shape: " + shape + " total size: " + totalSize);

		PackedCollection input = tensor(shape).pack();
		PackedCollection output = new PackedCollection(shape);

		Assignment<PackedCollection> assignment = a(p(output), p(input));

		int memLength = extractMemLength(assignment);
		int count = extractCount(assignment);

		log("Multi-dimensional shape - memLength: " + memLength + ", count: " + count);
		log("Assignment describe: " + assignment.describe());

		assertTrue("memLength should be <= preferredStatements for multi-dimensional shapes",
				memLength <= ScopeSettings.preferredStatements);
		assertTrue("count * memLength should equal total size",
				count * memLength == totalSize);
	}

	/**
	 * Tests deep multi-dimensional shapes requiring multiple axis adjustments.
	 */
	@Test
	public void testDeepMultiDimensionalShape() {
		// Shape (2, 4, 256):
		// at axis=0 -> size=2048
		// at axis=1 -> size=1024
		// at axis=2 -> size=256
		int dim0 = 2;
		int dim1 = 4;
		int dim2 = 256;
		int totalSize = dim0 * dim1 * dim2;

		TraversalPolicy shape = shape(dim0, dim1, dim2);
		log("Deep multi-dimensional shape: " + shape + " total size: " + totalSize);

		PackedCollection input = tensor(shape).pack();
		PackedCollection output = new PackedCollection(shape);

		Assignment<PackedCollection> assignment = a(p(output), p(input));

		int memLength = extractMemLength(assignment);
		int count = extractCount(assignment);

		log("Deep multi-dimensional shape - memLength: " + memLength + ", count: " + count);
		log("Assignment describe: " + assignment.describe());

		assertTrue("memLength should be <= preferredStatements for deep shapes",
				memLength <= ScopeSettings.preferredStatements);
		assertTrue("count * memLength should equal total size",
				count * memLength == totalSize);
	}

	/**
	 * Verifies functional correctness for small shapes.
	 */
	@Test
	public void testFunctionalCorrectnessSmall() {
		int size = 64;

		PackedCollection input = tensor(shape(size)).pack();
		PackedCollection output = new PackedCollection(shape(size));

		Runnable assign = a(p(output), p(input)).get();
		assign.run();

		for (int i = 0; i < size; i++) {
			assertEquals("Output[" + i + "] should match input",
					input.toDouble(i), output.toDouble(i));
		}
	}

	/**
	 * Verifies functional correctness for large shapes with statement reduction.
	 */
	@Test
	public void testFunctionalCorrectnessLarge() {
		int size = 1024;

		PackedCollection input = tensor(shape(size)).pack();
		PackedCollection output = new PackedCollection(shape(size));

		Runnable assign = a(p(output), p(input)).get();
		assign.run();

		for (int i = 0; i < size; i++) {
			assertEquals("Output[" + i + "] should match input",
					input.toDouble(i), output.toDouble(i));
		}
	}

	/**
	 * Verifies functional correctness for multi-dimensional shapes.
	 */
	@Test
	public void testFunctionalCorrectnessMultiDimensional() {
		TraversalPolicy shape = shape(8, 128);
		int totalSize = shape.getTotalSize();

		PackedCollection input = tensor(shape).pack();
		PackedCollection output = new PackedCollection(shape);

		Runnable assign = a(p(output), p(input)).get();
		assign.run();

		for (int i = 0; i < totalSize; i++) {
			assertEquals("Output[" + i + "] should match input",
					input.toDouble(i), output.toDouble(i));
		}
	}

	/**
	 * Tests very large shapes that would far exceed limits.
	 */
	@Test
	public void testVeryLargeShape() {
		int size = 8192;
		assertTrue("Test assumes size >> preferredStatements",
				size > ScopeSettings.preferredStatements * 4);

		PackedCollection input = tensor(shape(size)).pack();
		PackedCollection output = new PackedCollection(shape(size));

		Assignment<PackedCollection> assignment = a(p(output), p(input));

		int memLength = extractMemLength(assignment);
		int count = extractCount(assignment);

		log("Very large shape (" + size + ") - memLength: " + memLength + ", count: " + count);
		log("Assignment describe: " + assignment.describe());

		assertTrue("memLength should be <= preferredStatements for very large shapes",
				memLength <= ScopeSettings.preferredStatements);
		assertTrue("count * memLength should equal total size",
				count * memLength == size);

		// Also verify functional correctness
		Runnable assign = assignment.get();
		assign.run();

		for (int i = 0; i < size; i += size / 10) {
			assertEquals("Output[" + i + "] should match input",
					input.toDouble(i), output.toDouble(i));
		}
	}

	/**
	 * Verifies feature respects preferredStatements configuration.
	 */
	@Test
	public void testPreferredStatementsConfiguration() {
		int originalPreferred = ScopeSettings.preferredStatements;

		try {
			// Lower threshold to 64
			ScopeSettings.preferredStatements = 64;

			// Shape (128) now exceeds threshold
			int size = 128;

			PackedCollection input = tensor(shape(size)).pack();
			PackedCollection output = new PackedCollection(shape(size));

			Assignment<PackedCollection> assignment = a(p(output), p(input));

			int memLength = extractMemLength(assignment);
			int count = extractCount(assignment);

			log("With preferredStatements=64, shape (" + size + ") - memLength: " + memLength + ", count: " + count);

			assertTrue("memLength should be <= custom preferredStatements",
					memLength <= 64);

		} finally {
			ScopeSettings.preferredStatements = originalPreferred;
		}
	}

	/**
	 * Compares memLength before and after adjustment threshold.
	 */
	@Test
	public void testMemLengthComparison() {
		int smallSize = ScopeSettings.preferredStatements / 2;
		int largeSize = ScopeSettings.preferredStatements * 4;

		// Small shape - should have exact memLength
		PackedCollection smallInput = tensor(shape(smallSize)).pack();
		PackedCollection smallOutput = new PackedCollection(shape(smallSize));
		Assignment<PackedCollection> smallAssignment = a(p(smallOutput), p(smallInput));
		int smallMemLength = extractMemLength(smallAssignment);

		// Large shape - should have reduced memLength
		PackedCollection largeInput = tensor(shape(largeSize)).pack();
		PackedCollection largeOutput = new PackedCollection(shape(largeSize));
		Assignment<PackedCollection> largeAssignment = a(p(largeOutput), p(largeInput));
		int largeMemLength = extractMemLength(largeAssignment);
		int largeCount = extractCount(largeAssignment);

		log("Small shape (" + smallSize + ") memLength: " + smallMemLength);
		log("Large shape (" + largeSize + ") memLength: " + largeMemLength + ", count: " + largeCount);
		log("Reduction ratio: " + (largeSize / (double) largeMemLength));

		assertEquals("Small shape should have exact memLength", smallSize, smallMemLength);
		assertTrue("Large shape memLength should be reduced", largeMemLength < largeSize);
		assertTrue("Large shape memLength should be <= preferredStatements",
				largeMemLength <= ScopeSettings.preferredStatements);
	}

	/**
	 * Verifies that the product of count and memLength always equals the total size.
	 */
	@Test
	public void testCountMemLengthProduct() {
		int[] sizes = {64, 128, 256, 512, 1024, 2048, 4096};

		for (int size : sizes) {
			PackedCollection input = tensor(shape(size)).pack();
			PackedCollection output = new PackedCollection(shape(size));

			Assignment<PackedCollection> assignment = a(p(output), p(input));

			int memLength = extractMemLength(assignment);
			int count = extractCount(assignment);
			int product = count * memLength;

			log("Size " + size + " -> count=" + count + ", memLength=" + memLength + ", product=" + product);

			assertEquals("count * memLength should equal size for shape " + size,
					size, product);
		}
	}
}
