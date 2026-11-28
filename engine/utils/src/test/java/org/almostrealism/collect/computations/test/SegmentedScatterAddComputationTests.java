/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.collect.computations.test;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.computations.SegmentedScatterAddComputation;
import org.almostrealism.collect.CompressedRowTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests the event-driven product of a sparse matrix with a binary activity vector,
 * {@link MatrixFeatures#scatterProduct}, which scatters the outgoing entries of each active
 * source into its targets through an atomic accumulation, against the deterministic segmented
 * sum {@link MatrixFeatures#matmul} computes for the same matrix, and against a reference
 * product computed from the index arrays of the matrix.
 *
 * <p>Each matrix is a connectome-like {@code W[target, source]}, stored twice in
 * compressed-sparse-row form: as {@code W} itself, whose rows list incoming entries and which the
 * forward product reads, and as {@code transpose(W)}, whose rows list outgoing entries and which
 * the scatter reads. The structure of the matrix is chosen on the host; its weights and the
 * activity are produced on the device, and the weights of the transpose are gathered from those
 * of {@code W} by a product with a permutation matrix.</p>
 *
 * <p>Every weight is an integer in {@code [-5, 4]} and the activity is binary, so every
 * contribution is an integer of magnitude at most 5 and every partial sum of a target is bounded
 * in magnitude by {@code 5 * sources}, far below {@code 2^24}: every partial sum is exactly
 * representable in single precision, so the result does not depend on the order in which
 * concurrent additions land, and the comparisons are exact.</p>
 */
public class SegmentedScatterAddComputationTests extends TestSuiteBase implements MatrixFeatures {

	/**
	 * The structure of a sparse matrix in compressed-sparse-row form.
	 *
	 * @param rows    the number of rows
	 * @param cols    the number of columns
	 * @param offsets the {@code rows + 1} row boundaries
	 * @param columns the column of each entry
	 */
	private record Structure(int rows, int cols, int[] offsets, int[] columns) {
		/**
		 * Returns the ordering that reads a collection of entries as this matrix.
		 *
		 * @return the compressed-sparse-row ordering
		 */
		CompressedRowTraversalOrdering ordering() {
			return CompressedRowTraversalOrdering.of(cols, offsets, columns);
		}

		/**
		 * Returns the number of stored entries.
		 *
		 * @return the entry count
		 */
		int entries() { return columns.length; }

		/**
		 * Returns, for every entry of the transpose in its compressed-sparse-row order, the
		 * position in this matrix of the same element.
		 *
		 * @return the position in this matrix of each entry of the transpose
		 */
		int[] transposePositions() {
			int[] next = columnStarts();
			int[] positions = new int[entries()];
			for (int r = 0; r < rows; r++) {
				for (int p = offsets[r]; p < offsets[r + 1]; p++) positions[next[columns[p]]++] = p;
			}

			return positions;
		}

		/**
		 * Returns the structure of the transpose.
		 *
		 * @return the transpose, whose rows are the columns of this matrix
		 */
		Structure transpose() {
			int[] next = columnStarts();
			int[] tOffsets = next.clone();
			int[] tColumns = new int[entries()];
			for (int r = 0; r < rows; r++) {
				for (int p = offsets[r]; p < offsets[r + 1]; p++) tColumns[next[columns[p]]++] = r;
			}

			return new Structure(cols, rows, tOffsets, tColumns);
		}

		/**
		 * Returns the position at which the entries of each column of this matrix begin in the
		 * transpose, with the total entry count appended.
		 *
		 * @return the {@code cols + 1} column boundaries
		 */
		private int[] columnStarts() {
			int[] starts = new int[cols + 1];
			for (int c : columns) starts[c + 1]++;
			for (int c = 0; c < cols; c++) starts[c + 1] += starts[c];
			return starts;
		}

		/**
		 * Returns the product of this matrix, holding the given entry values, with a vector,
		 * computed directly from the index arrays as the reference the evaluated results must
		 * equal.
		 *
		 * @param values the value of each entry
		 * @param x      the vector
		 * @return the product
		 */
		double[] times(double[] values, double[] x) {
			double[] y = new double[rows];
			for (int r = 0; r < rows; r++) {
				for (int p = offsets[r]; p < offsets[r + 1]; p++) y[r] += values[p] * x[columns[p]];
			}

			return y;
		}

		/**
		 * Returns a random structure with the given density, in which the first row is empty, the
		 * second column is empty, and the last column is full, so that empty rows, empty columns
		 * and a very long column (a source reaching every target) are all present.
		 *
		 * @param random  the source of randomness
		 * @param rows    the number of rows (targets)
		 * @param cols    the number of columns (sources)
		 * @param density the probability that an element is stored
		 * @return the structure
		 */
		static Structure random(Random random, int rows, int cols, double density) {
			int[] offsets = new int[rows + 1];
			List<Integer> columns = new ArrayList<>();

			for (int r = 0; r < rows; r++) {
				offsets[r] = columns.size();
				if (r == 0) continue;

				for (int c = 0; c < cols; c++) {
					boolean full = c == cols - 1;
					boolean empty = c == 1 && !full;
					if (!empty && (full || random.nextDouble() < density)) columns.add(c);
				}
			}

			offsets[rows] = columns.size();
			return new Structure(rows, cols, offsets, columns.stream().mapToInt(Integer::intValue).toArray());
		}

		/**
		 * Returns a random square structure in which every row has the same number of entries, in
		 * distinct random columns, as a connectome with a fixed fan-in has.
		 *
		 * @param random  the source of randomness
		 * @param neurons the number of rows and columns
		 * @param perRow  the number of entries of each row
		 * @return the structure
		 */
		static Structure fixedFanIn(Random random, int neurons, int perRow) {
			int[] offsets = new int[neurons + 1];
			int[] columns = new int[neurons * perRow];

			for (int r = 0; r < neurons; r++) {
				offsets[r] = r * perRow;
				Set<Integer> chosen = new HashSet<>();
				while (chosen.size() < perRow) chosen.add(random.nextInt(neurons));

				int p = r * perRow;
				for (int c : chosen) columns[p++] = c;
			}

			offsets[neurons] = neurons * perRow;
			return new Structure(neurons, neurons, offsets, columns);
		}
	}

	/**
	 * A sparse matrix {@code W[target, source]} and its transpose, holding the same integer
	 * weights produced on the device.
	 */
	private final class Connectome implements AutoCloseable {
		/** The structure of {@code W}. */
		private final Structure structure;

		/** The structure of {@code transpose(W)}. */
		private final Structure transposed;

		/** The entry values of {@code W}. */
		private final PackedCollection weights;

		/** The entry values of {@code transpose(W)}, the same weights in its order. */
		private final PackedCollection transposedWeights;

		/**
		 * Produces integer weights in {@code [-5, 4]} for the given structure, and gathers the
		 * weights of its transpose from them by a product with the permutation matrix that maps
		 * each entry of the transpose to the same element of the matrix.
		 *
		 * @param structure the structure of {@code W}
		 */
		Connectome(Structure structure) {
			this.structure = structure;
			this.transposed = structure.transpose();

			int entries = structure.entries();
			this.weights = integers(0, entries).multiply(7.0).mod(c(10.0)).subtract(c(5.0)).evaluate();

			int[] identity = new int[entries + 1];
			for (int q = 0; q <= entries; q++) identity[q] = q;
			Structure permutation = new Structure(entries, entries, identity, structure.transposePositions());

			try (PackedCollection ones = integers(0, entries).multiply(0.0).add(c(1.0)).evaluate()) {
				PackedCollection gather = new PackedCollection(shape(entries, entries), 0, ones, 0,
						permutation.ordering());
				this.transposedWeights = matmul(cp(gather), cp(weights)).reshape(shape(entries)).evaluate();
			}
		}

		/**
		 * Returns {@code W}, the target-major matrix the forward product reads.
		 *
		 * @return the matrix
		 */
		PackedCollection incoming() {
			return new PackedCollection(shape(structure.rows(), structure.cols()), 0, weights, 0,
					structure.ordering());
		}

		/**
		 * Returns {@code transpose(W)}, the source-major matrix the scatter reads.
		 *
		 * @return the transpose
		 */
		PackedCollection outgoing() {
			return new PackedCollection(shape(transposed.rows(), transposed.cols()), 0, transposedWeights, 0,
					transposed.ordering());
		}

		/**
		 * Returns the reference product of {@code W} with the given activity.
		 *
		 * @param activity the activity of each source
		 * @return the product
		 */
		double[] times(double[] activity) {
			return structure.times(weights.toArray(0, structure.entries()), activity);
		}

		/** Releases the weights. */
		@Override
		public void close() {
			weights.destroy();
			transposedWeights.destroy();
		}
	}

	/**
	 * Produces a random binary activity vector on the device, each source active with the given
	 * probability.
	 *
	 * @param random   the source of randomness
	 * @param sources  the number of sources
	 * @param fraction the probability that a source is active
	 * @return the activity
	 */
	private PackedCollection activity(Random random, int sources, double fraction) {
		return greaterThan(rand(shape(sources), random), c(1.0 - fraction), c(1.0), c(0.0)).evaluate();
	}

	/**
	 * Returns the backends to evaluate on: the CPU, and the GPU when one is available.
	 *
	 * @return the compute requirements selecting each backend
	 */
	private List<ComputeRequirement> backends() {
		return Stream.of(ComputeRequirement.CPU, ComputeRequirement.GPU)
				.filter(r -> Hardware.getLocalHardware().isAvailable(r))
				.collect(Collectors.toList());
	}

	/**
	 * Runs the given operation with the given backend required.
	 *
	 * @param operation the operation
	 * @param backend   the backend
	 */
	private void run(Supplier<Runnable> operation, ComputeRequirement backend) {
		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(backend));

		try {
			operation.get().run();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}
	}

	/**
	 * Evaluates the forward product of the matrix with the activity on the given backend.
	 *
	 * @param w        the matrix
	 * @param activity the activity
	 * @param backend  the backend
	 * @return the product
	 */
	private double[] forwardProduct(Connectome w, PackedCollection activity, ComputeRequirement backend) {
		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(backend));

		try (PackedCollection result = matmul(cp(w.incoming()), cp(activity)).evaluate()) {
			return result.toArray(0, w.structure.rows());
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}
	}

	/**
	 * Asserts that the event-driven product of the transpose of a matrix of the given structure
	 * with a random binary activity equals, exactly, both the segmented-sum product of the matrix
	 * and the reference product, on every available backend.
	 *
	 * @param random    the source of randomness
	 * @param structure the structure of the matrix {@code W[target, source]}
	 * @param fraction  the fraction of active sources
	 */
	private void assertParity(Random random, Structure structure, double fraction) {
		try (Connectome w = new Connectome(structure);
			 PackedCollection activity = activity(random, structure.cols(), fraction)) {
			double[] expected = w.times(activity.toArray(0, structure.cols()));

			for (ComputeRequirement backend : backends()) {
				try (PackedCollection destination = new PackedCollection(structure.rows())) {
					double[] forward = forwardProduct(w, activity, backend);
					run(scatterProduct(p(destination), cp(w.outgoing()), cp(activity)), backend);
					double[] scattered = destination.toArray(0, structure.rows());

					for (int i = 0; i < expected.length; i++) {
						String at = "target " + i + " of " + structure.rows() + "x" + structure.cols() + " on " + backend;
						assertEquals("forward " + at, expected[i], forward[i]);
						assertEquals("scatter " + at, expected[i], scattered[i]);
					}
				}
			}
		}
	}

	/** A square matrix of moderate density. */
	@Test(timeout = 120000)
	public void paritySquare() {
		Random random = new Random(11);
		assertParity(random, Structure.random(random, 64, 64, 0.1), 0.3);
	}

	/**
	 * A matrix with more targets than sources: the kernel range (sources) is smaller than the
	 * destination, so a kernel sized by its output would visit sources that do not exist.
	 */
	@Test(timeout = 120000)
	public void parityTall() {
		Random random = new Random(12);
		assertParity(random, Structure.random(random, 150, 23, 0.2), 0.5);
	}

	/**
	 * A matrix with more sources than targets: the kernel range (sources) exceeds the destination,
	 * so a kernel sized by its output would skip sources.
	 */
	@Test(timeout = 120000)
	public void parityWide() {
		Random random = new Random(13);
		assertParity(random, Structure.random(random, 19, 211, 0.15), 0.4);
	}

	/** A very sparse matrix at a low activity, where most sources do nothing. */
	@Test(timeout = 120000)
	public void paritySparseLowActivity() {
		Random random = new Random(14);
		assertParity(random, Structure.random(random, 300, 257, 0.01), 0.05);
	}

	/** A dense matrix with every source active, where colliding additions are most frequent. */
	@Test(timeout = 120000)
	public void parityDenseFullActivity() {
		Random random = new Random(15);
		assertParity(random, Structure.random(random, 41, 97, 0.9), 1.0);
	}

	/**
	 * With {@link ScopeSettings#enableInstanceReferenceMasking} set, every array element reference
	 * is wrapped in a {@link io.almostrealism.expression.Mask}; the scatter still builds, and still
	 * agrees exactly with the reference product, accumulating into the masked element.
	 */
	@Test(timeout = 120000)
	public void parityWithMaskedReferences() {
		boolean masking = ScopeSettings.enableInstanceReferenceMasking;
		ScopeSettings.enableInstanceReferenceMasking = true;

		try {
			Random random = new Random(17);
			assertParity(random, Structure.random(random, 37, 53, 0.2), 0.5);
		} finally {
			ScopeSettings.enableInstanceReferenceMasking = masking;
		}
	}

	/**
	 * Every one of many active sources adds one into the same single target, so every kernel
	 * thread collides with every other: the result counts the sources exactly only if no addition
	 * is lost, which a plain read-modify-write would not guarantee on a backend that runs its
	 * threads concurrently.
	 */
	@Test(timeout = 120000)
	public void collidingAdditionsAreAllKept() {
		int sources = 8192;
		int[] offsets = new int[sources + 1];
		for (int j = 0; j <= sources; j++) offsets[j] = j;
		Structure outgoing = new Structure(sources, 1, offsets, new int[sources]);

		for (ComputeRequirement backend : backends()) {
			try (PackedCollection ones = integers(0, sources).multiply(0.0).add(c(1.0)).evaluate();
				 PackedCollection destination = new PackedCollection(1)) {
				PackedCollection matrix = new PackedCollection(shape(sources, 1), 0, ones, 0, outgoing.ordering());
				run(scatterProduct(p(destination), cp(matrix), cp(ones)), backend);
				assertEquals("colliding additions on " + backend, sources, destination.toDouble(0));
			}
		}
	}

	/**
	 * The product resets its destination in place before every run: a destination holding
	 * unrelated values, run twice, holds exactly the product after each run.
	 */
	@Test(timeout = 120000)
	public void productResetsReusedDestination() {
		Random random = new Random(16);
		Structure structure = Structure.random(random, 33, 45, 0.2);

		try (Connectome w = new Connectome(structure);
			 PackedCollection activity = activity(random, structure.cols(), 0.5)) {
			double[] expected = w.times(activity.toArray(0, structure.cols()));

			for (ComputeRequirement backend : backends()) {
				try (PackedCollection destination = new PackedCollection(structure.rows())) {
					destination.fill(7.0);
					OperationList product = scatterProduct(p(destination), cp(w.outgoing()), cp(activity));

					for (int pass = 0; pass < 2; pass++) {
						run(product, backend);
						double[] result = destination.toArray(0, structure.rows());
						for (int i = 0; i < expected.length; i++) {
							assertEquals("target " + i + " after pass " + pass + " on " + backend,
									expected[i], result[i]);
						}
					}
				}
			}
		}
	}

	/**
	 * The scatter-add alone accumulates into what its destination holds, rather than replacing
	 * it: a destination of ones receives one plus the product.
	 */
	@Test(timeout = 120000)
	public void scatterAddAccumulates() {
		Random random = new Random(17);
		Structure structure = Structure.random(random, 27, 31, 0.3);

		try (Connectome w = new Connectome(structure);
			 PackedCollection activity = activity(random, structure.cols(), 0.5)) {
			double[] expected = w.times(activity.toArray(0, structure.cols()));

			for (ComputeRequirement backend : backends()) {
				try (PackedCollection destination = new PackedCollection(structure.rows())) {
					destination.fill(1.0);
					OperationList op = new OperationList("Scatter-Add Test", false);
					op.add(scatterAdd(p(destination), cp(w.outgoing()), cp(activity)));
					run(op, backend);

					double[] result = destination.toArray(0, structure.rows());
					for (int i = 0; i < expected.length; i++) {
						assertEquals("target " + i + " on " + backend, 1.0 + expected[i], result[i]);
					}
				}
			}
		}
	}

	/**
	 * The reset and the scatter of the product are dispatched as two operations, never compiled
	 * into one kernel in which a reset could follow an addition, and the scatter launches one
	 * thread per source.
	 */
	@Test(timeout = 60000)
	public void productIsTwoOrderedOperations() {
		Random random = new Random(18);
		Structure structure = Structure.random(random, 12, 30, 0.3);

		try (Connectome w = new Connectome(structure);
			 PackedCollection activity = activity(random, structure.cols(), 0.5);
			 PackedCollection destination = new PackedCollection(structure.rows())) {
			OperationList product = scatterProduct(p(destination), cp(w.outgoing()), cp(activity));
			assertFalse("the product must not compile into one kernel", product.isComputation());
			assertEquals(2, product.size());
			assertTrue(product.get(1) instanceof SegmentedScatterAddComputation);
			assertEquals(30, ((SegmentedScatterAddComputation) product.get(1)).getCountLong());
		}
	}

	/**
	 * The scatter owns its kernel: it is an isolation target, so optimizing a compiling list
	 * into which the product has been flattened isolates the scatter rather than fusing it with
	 * the reset of the same destination.
	 */
	@Test(timeout = 60000)
	public void scatterIsIsolationTarget() {
		Random random = new Random(19);
		Structure structure = Structure.random(random, 16, 16, 0.3);

		try (Connectome w = new Connectome(structure);
			 PackedCollection activity = activity(random, structure.cols(), 0.5);
			 PackedCollection destination = new PackedCollection(structure.rows())) {
			SegmentedScatterAddComputation scatter = scatterAdd(p(destination), cp(w.outgoing()), cp(activity));
			assertTrue(scatter.isIsolationTarget(ProcessContext.base()));

			OperationList parent = new OperationList("Compiling Parent");
			parent.add(scatterProduct(p(destination), cp(w.outgoing()), cp(activity)));
			OperationList flat = parent.flatten();
			assertEquals(2, flat.size());
			assertTrue("flattening inlines both steps into the compiling parent", flat.isComputation());

			ParallelProcess<?, Runnable> optimized = flat.optimize();
			assertTrue(optimized instanceof OperationList);
			assertFalse("the optimized parent must not fuse the reset and the scatter",
					((OperationList) optimized).isComputation());
		}
	}

	/**
	 * A square product (as many sources as targets, so the reset and the scatter have the same
	 * kernel range and could otherwise be fused), flattened into a compiling parent and then
	 * optimized, still resets its destination before scattering: a destination of stale values,
	 * run twice, holds exactly the product after each run.
	 */
	@Test(timeout = 120000)
	public void flattenedProductInCompilingParentIsCorrect() {
		Random random = new Random(20);
		Structure structure = Structure.random(random, 48, 48, 0.2);

		try (Connectome w = new Connectome(structure);
			 PackedCollection activity = activity(random, structure.cols(), 0.6)) {
			double[] expected = w.times(activity.toArray(0, structure.cols()));

			for (ComputeRequirement backend : backends()) {
				try (PackedCollection destination = new PackedCollection(structure.rows())) {
					destination.fill(-3.0);
					OperationList parent = new OperationList("Compiling Parent");
					parent.add(scatterProduct(p(destination), cp(w.outgoing()), cp(activity)));
					Supplier<Runnable> step = parent.flatten().optimize();

					for (int pass = 0; pass < 2; pass++) {
						run(step, backend);
						double[] result = destination.toArray(0, structure.rows());
						for (int i = 0; i < expected.length; i++) {
							assertEquals("target " + i + " after pass " + pass + " on " + backend,
									expected[i], result[i]);
						}
					}
				}
			}
		}
	}

	/** A matrix whose ordering does not enumerate its rows cannot be scattered. */
	@Test(timeout = 60000)
	public void rejectsDenseMatrix() {
		try (PackedCollection destination = new PackedCollection(3)) {
			try {
				scatterAdd(p(destination), cp(pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0).reshape(shape(2, 3))),
						cp(pack(1.0, 0.0)));
				Assert.fail("a dense matrix should be rejected");
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage().contains("does not enumerate its rows"));
			}
		}
	}

	/**
	 * Measures the per-step time of the event-driven product and of the segmented-sum product of
	 * a connectome-sized matrix (50,000 neurons with 400 synapses each, 20M entries) at a range of
	 * activity fractions, records both and the fraction at which the event-driven path stops being
	 * faster, checks the two results agree at every fraction, and requires the event-driven path
	 * to be faster at the lowest activity. A much smaller matrix is dominated by the cost of
	 * dispatching the kernels, which hides the difference. The matrix stores more than {@code 2^24}
	 * entries, so the entry positions the scatter reads exceed the range of exact single-precision
	 * integers.
	 *
	 * <p>The benchmark measures the Metal kernels: both products are compiled and run with
	 * {@link ComputeRequirement#MTL} required, so the timing comparison never measures whichever
	 * backend the default selection policy would pick. A host without Metal cannot run it, and
	 * reports the benchmark as skipped rather than passed.</p>
	 */
	@Test(timeout = 1200000)
	@TestDepth(2)
	public void lowActivityBenchmark() {
		if (skipLongTests) return;
		Assume.assumeTrue("the benchmark measures the Metal kernels and Metal is not available",
				Hardware.getLocalHardware().isAvailable(ComputeRequirement.MTL));

		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(ComputeRequirement.MTL));

		try {
			assertFasterAtLowActivity();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}
	}

	/**
	 * Runs the measurements of {@link #lowActivityBenchmark()} with whatever backend the caller
	 * has required, asserting that both products agree at every fraction and that the
	 * event-driven product is faster at the lowest one.
	 */
	private void assertFasterAtLowActivity() {
		int neurons = 50000;
		int synapses = 400;
		int warmup = 3;
		int steps = 20;
		double[] fractions = { 0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1.0 };

		Random random = new Random(19);
		Structure structure = Structure.fixedFanIn(random, neurons, synapses);
		log("benchmarkEntries=" + structure.entries() + " neurons=" + neurons);

		try (Connectome w = new Connectome(structure);
			 PackedCollection activity = new PackedCollection(neurons);
			 PackedCollection forwardOut = new PackedCollection(neurons);
			 PackedCollection scatterOut = new PackedCollection(neurons)) {
			Evaluable<PackedCollection> forward = matmul(cp(w.incoming()), cp(activity)).get();
			Runnable scatter = scatterProduct(p(scatterOut), cp(w.outgoing()), cp(activity)).get();

			Double crossover = null;
			double lowestRatio = Double.NaN;

			for (double fraction : fractions) {
				a(p(activity), greaterThan(rand(shape(neurons), random), c(1.0 - fraction), c(1.0), c(0.0)))
						.get().run();

				for (int s = 0; s < warmup; s++) {
					forward.into(forwardOut).evaluate();
					scatter.run();
				}

				long start = System.nanoTime();
				for (int s = 0; s < steps; s++) forward.into(forwardOut).evaluate();
				double forwardMs = (System.nanoTime() - start) / 1e6 / steps;

				start = System.nanoTime();
				for (int s = 0; s < steps; s++) scatter.run();
				double scatterMs = (System.nanoTime() - start) / 1e6 / steps;

				log("activityFraction=" + fraction + " segmentedSumMs=" + forwardMs + " eventDrivenMs=" + scatterMs);
				if (Double.isNaN(lowestRatio)) lowestRatio = scatterMs / forwardMs;
				if (crossover == null && scatterMs >= forwardMs) crossover = fraction;

				double[] expected = forwardOut.toArray(0, neurons);
				double[] actual = scatterOut.toArray(0, neurons);
				for (int i = 0; i < neurons; i++) {
					assertEquals("target " + i + " at fraction " + fraction, expected[i], actual[i]);
				}
			}

			log("crossoverActivityFraction=" + (crossover == null ? "none" : crossover));
			assertTrue("the event-driven product should be faster at the lowest activity, ratio=" + lowestRatio,
					lowestRatio < 1.0);
		}
	}
}
