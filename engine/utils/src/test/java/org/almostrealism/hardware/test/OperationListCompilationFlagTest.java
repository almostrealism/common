/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.test;

import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Verifies that the lists {@link OperationList#optimize()} creates when it subdivides a list
 * keep that list's compilation setting, and that segmented lists of either setting still
 * compute their values. A list built with compilation
 * disabled promises that its operations are dispatched one at a time (the step contract of
 * {@code SimulationRunner} depends on it), so a segment of such a list must not become a
 * compilable, fusing sub-list; a segment of a compiled list must remain compilable.
 */
public class OperationListCompilationFlagTest extends TestSuiteBase implements ModelTestFeatures {

	/**
	 * Optimizing a non-compiled list with segmenting enabled still produces every value. The
	 * segments are isolated by the re-optimization of the segmented list, so only their results,
	 * not their compilation setting, are observable from outside.
	 */
	@Test(timeout = 120000)
	public void segmentedNonCompiledListComputes() {
		segment(false);
	}

	/**
	 * Optimizing a compiled list with segmenting enabled still produces every value.
	 */
	@Test(timeout = 120000)
	public void segmentedCompiledListComputes() {
		segment(true);
	}

	/**
	 * Subdividing a non-compiled list at a read-after-write hazard leaves every multi-member
	 * segment non-compiled, and the subdivided list still computes the correct value.
	 */
	@Test(timeout = 120000)
	public void subdivisionOfNonCompiledListStaysNonCompiled() {
		subdivide(false);
	}

	/**
	 * Subdividing a compiled list at a read-after-write hazard leaves every multi-member segment
	 * compilable, and the subdivided list still computes the correct value.
	 */
	@Test(timeout = 120000)
	public void subdivisionOfCompiledListStaysCompiled() {
		subdivide(true);
	}

	/**
	 * Optimizes, with segmenting enabled, a list of two four-element assignments followed by a
	 * one-element assignment, runs it and checks the values.
	 *
	 * @param compiled the compilation setting of the list
	 */
	private void segment(boolean compiled) {
		boolean segmenting = OperationList.enableSegmenting;
		OperationList.enableSegmenting = true;

		PackedCollection source = pack(1.0, 2.0, 3.0, 4.0);
		PackedCollection first = new PackedCollection(4);
		PackedCollection second = new PackedCollection(4);
		PackedCollection scalar = new PackedCollection(1);

		try {
			OperationList op = new OperationList("segment", compiled);
			op.add(a("first", cp(first.traverseEach()), cp(source.traverseEach()).multiply(2.0)));
			op.add(a("second", cp(second.traverseEach()), cp(source.traverseEach()).add(1.0)));
			op.add(a("scalar", cp(scalar), c(5.0)));

			Process<?, ?> optimized = op.optimize();
			assertTrue(optimized instanceof OperationList);
			((Supplier<Runnable>) optimized).get().run();

			assertEquals(2.0, first.toDouble(0));
			assertEquals(8.0, first.toDouble(3));
			assertEquals(2.0, second.toDouble(0));
			assertEquals(5.0, second.toDouble(3));
			assertEquals(5.0, scalar.toDouble(0));
		} finally {
			OperationList.enableSegmenting = segmenting;
		}
	}

	/**
	 * Optimizes the record-then-read composite of {@link OperationListSubdivisionTest} with the
	 * given compilation setting, checks that it was subdivided, that each multi-member segment
	 * carries the setting, and that it computes the expected value.
	 *
	 * @param compiled the compilation setting of the list
	 */
	private void subdivide(boolean compiled) {
		PackedCollection external = pack(2.0, 3.0);
		PackedCollection recorded = new PackedCollection(shape(2));
		PackedCollection weights = pack(0.5, -0.25).reshape(shape(1, 2));
		PackedCollection bias = pack(0.1);
		PackedCollection layerOut = new PackedCollection(shape(1));
		PackedCollection modelOut = new PackedCollection(shape(1));

		DynamicCollectionProducer input = new DynamicCollectionProducer(shape(2), args -> external);
		CollectionProducer dense = matmul(p(weights),
				reshape(shape(1, 2).traverse(1), p(recorded)))
				.add(traverse(1, p(bias)))
				.reshape(shape(1));

		OperationList forward = new OperationList("forward", compiled);
		forward.add(into("record", (Producer) input, p(recorded), false));
		forward.add(into("dense out", (Producer) dense, p(layerOut), false));
		forward.add(a("model out", p(modelOut), p(layerOut)));

		ParallelProcess<?, Runnable> optimized = (ParallelProcess<?, Runnable>) forward.optimize();
		assertTrue(optimized instanceof OperationList);

		List<OperationList> segments = createdSegments((OperationList) optimized);
		assertFalse(segments.isEmpty());
		segments.forEach(s -> assertEquals(compiled, s.isComputation()));

		optimized.get().run();
		assertEquals(0.35, modelOut.toDouble(0));
	}

	/**
	 * Returns the multi-member lists directly inside the given list: the segments that
	 * segmentation or subdivision creates (a single-member segment is unwrapped, so it never
	 * appears as a list of its own).
	 *
	 * @param list the optimized list
	 * @return the created segments
	 */
	private static List<OperationList> createdSegments(OperationList list) {
		List<OperationList> result = new ArrayList<>();
		for (Supplier<Runnable> member : list) {
			if (member instanceof OperationList && ((OperationList) member).size() > 1) {
				result.add((OperationList) member);
			}
		}

		return result;
	}
}
