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

package org.almostrealism.graph.model.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Factor;
import io.almostrealism.uml.Signature;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.KernelBindingListener;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Compiles the same index-projection layer once for inference and once for back-propagation
 * in one JVM, in both orders, and checks that both compiled models produce correct results.
 *
 * <p>The inference model reads its input through the model's input container, while the
 * training model reads the copy of that input its back-propagation cell keeps, which is a
 * provided collection. The two compiled projections are structurally identical but bind their
 * input differently, so they must either compile to separate kernels or share one kernel that
 * binds both correctly; neither may be refused by the instruction cache.</p>
 *
 * <p>Each order uses its own input shape, so the instruction cache holds nothing for the
 * computations of that order when it starts, whichever test method runs first.</p>
 *
 * <p>Every projection here maps each output element to at most one input element. The expected
 * output and input gradient are derived from that map, computed directly in the test: an output
 * element is the input element it maps to (or zero), and the gradient of an input element is the
 * sum of the output gradients of the elements that map to it.</p>
 */
public class IndexProjectionLayerReuseTest extends TestSuiteBase {

	/** The 2x2 max-pooling layer compiled for inference and then for training. */
	@Test(timeout = 120000)
	public void pool2dInferenceThenTraining() {
		TraversalPolicy shape = shape(1, 1, 4, 4);
		verifyProjection(() -> pool2d(shape, 2), in -> poolSources(shape, 2, in), true);
	}

	/** The 2x2 max-pooling layer compiled for training and then for inference. */
	@Test(timeout = 120000)
	public void pool2dTrainingThenInference() {
		TraversalPolicy shape = shape(1, 2, 4, 4);
		verifyProjection(() -> pool2d(shape, 2), in -> poolSources(shape, 2, in), false);
	}

	/** A subset layer compiled for inference and then for training. */
	@Test(timeout = 120000)
	public void subsetInferenceThenTraining() {
		verifyProjection(() -> subsetLayer(4, 5), in -> subsetSources(4, 5), true);
	}

	/** A subset layer compiled for training and then for inference. */
	@Test(timeout = 120000)
	public void subsetTrainingThenInference() {
		verifyProjection(() -> subsetLayer(5, 4), in -> subsetSources(5, 4), false);
	}

	/** A repeat layer compiled for inference and then for training. */
	@Test(timeout = 120000)
	public void repeatInferenceThenTraining() {
		verifyProjection(() -> repeatLayer(4, 5), in -> repeatSources(4, 5), true);
	}

	/** A repeat layer compiled for training and then for inference. */
	@Test(timeout = 120000)
	public void repeatTrainingThenInference() {
		verifyProjection(() -> repeatLayer(5, 4), in -> repeatSources(5, 4), false);
	}

	/** A permute layer compiled for inference and then for training. */
	@Test(timeout = 120000)
	public void permuteInferenceThenTraining() {
		verifyProjection(() -> permuteLayer(4, 5), in -> permuteSources(4, 5), true);
	}

	/** A permute layer compiled for training and then for inference. */
	@Test(timeout = 120000)
	public void permuteTrainingThenInference() {
		verifyProjection(() -> permuteLayer(5, 4), in -> permuteSources(5, 4), false);
	}

	/** A pad layer compiled for inference and then for training. */
	@Test(timeout = 120000)
	public void padInferenceThenTraining() {
		verifyProjection(() -> padLayer(4, 5), in -> padSources(4, 5), true);
	}

	/** A pad layer compiled for training and then for inference. */
	@Test(timeout = 120000)
	public void padTrainingThenInference() {
		verifyProjection(() -> padLayer(5, 4), in -> padSources(5, 4), false);
	}

	/**
	 * An isolated input read through a provider and the same input read through a container bind
	 * differently (the provided memory may be folded into a kernel's aggregate argument), so their
	 * signatures differ; isolated inputs of two separately allocated providers of the same layout,
	 * and of two containers of the same shape, still share a signature.
	 */
	@Test(timeout = 60000)
	public void isolatedInputSignatures() {
		TraversalPolicy shape = shape(1, 1, 3, 3);

		String first = Signature.of(isolated(cp(new PackedCollection(shape))));
		String second = Signature.of(isolated(cp(new PackedCollection(shape))));
		String container = Signature.of(isolated(c(container(shape))));
		String otherContainer = Signature.of(isolated(c(container(shape))));

		Assert.assertNotNull(first);
		Assert.assertNotNull(container);
		Assert.assertEquals(first, second);
		Assert.assertEquals(container, otherContainer);
		Assert.assertNotEquals(first, container);
	}

	/**
	 * Identical models still share their compiled pooling kernels: a second inference model and
	 * a second training model, each identical to a first one compiled before it, reuse the
	 * enumeration kernels the first compiled instead of compiling their own.
	 */
	@Test(timeout = 120000)
	public void identicalPoolModelsReuseKernels() {
		TraversalPolicy shape = shape(1, 3, 4, 4);

		verifyInference(pool2d(shape, 2), in -> poolSources(shape, 2, in));
		assertEnumerationReused(() ->
				verifyInference(pool2d(shape, 2), in -> poolSources(shape, 2, in)));

		verifyTraining(pool2d(shape, 2), in -> poolSources(shape, 2, in));
		assertEnumerationReused(() ->
				verifyTraining(pool2d(shape, 2), in -> poolSources(shape, 2, in)));
	}

	/**
	 * Runs the given work and asserts that every enumeration kernel it loads is reused from the
	 * instruction cache, and that it loads at least one.
	 *
	 * @param work the work to observe
	 */
	protected void assertEnumerationReused(Runnable work) {
		AtomicInteger compiled = new AtomicInteger();
		AtomicInteger reused = new AtomicInteger();

		KernelBindingListener previous = AcceleratedOperation.bindingListener;
		AcceleratedOperation.bindingListener = new KernelBindingListener() {
			@Override
			public void compiled(OperationMetadata metadata, String signature,
								 int kernelArguments, int foldedArguments, int aggregateLength) {
				if (isEnumeration(metadata)) compiled.incrementAndGet();
			}

			@Override
			public void reused(OperationMetadata metadata, String signature, int kernelArguments) {
				if (isEnumeration(metadata)) reused.incrementAndGet();
			}
		};

		try {
			work.run();
		} finally {
			AcceleratedOperation.bindingListener = previous;
		}

		Assert.assertTrue("no enumeration kernel was reused", reused.get() > 0);
		Assert.assertEquals("enumeration kernels compiled again", 0, compiled.get());
	}

	/**
	 * Returns whether the operation described by the given metadata is an enumeration.
	 *
	 * @param metadata the metadata of a compiled or reused operation
	 * @return true if it describes an enumeration
	 */
	protected boolean isEnumeration(OperationMetadata metadata) {
		String name = metadata.getDisplayName();
		return name != null && name.toLowerCase().contains("enumerate");
	}

	/**
	 * Wraps the given producer as an isolated process, as the optimizer does for a kernel input.
	 *
	 * @param producer the producer to isolate
	 * @return the isolated process
	 */
	protected CollectionProducerComputation.IsolatedProcess isolated(CollectionProducer producer) {
		return new CollectionProducerComputation.IsolatedProcess(producer.traverse(2));
	}

	/**
	 * Builds the layer twice, compiles one copy for inference and the other for back-propagation
	 * (in the given order), and verifies the outputs of both and the input gradient of the
	 * training model against the source map.
	 *
	 * @param layer          creates a fresh copy of the layer under test
	 * @param sources        the input index each output element reads for a given input, or -1
	 * @param inferenceFirst whether the inference model is compiled and run first
	 */
	protected void verifyProjection(Supplier<CellularLayer> layer,
									Function<PackedCollection, int[]> sources,
									boolean inferenceFirst) {
		if (inferenceFirst) {
			verifyInference(layer.get(), sources);
			verifyTraining(layer.get(), sources);
		} else {
			verifyTraining(layer.get(), sources);
			verifyInference(layer.get(), sources);
		}
	}

	/**
	 * Compiles the layer in an inference-only model and verifies its output.
	 *
	 * @param layer   the layer under test
	 * @param sources the source map of the projection
	 */
	protected void verifyInference(CellularLayer layer, Function<PackedCollection, int[]> sources) {
		Model model = new Model(layer.getInputShape());
		model.add(layer);

		PackedCollection input = input(layer.getInputShape());
		PackedCollection output = model.compile(false).forward(input);
		assertProjected("inference output", sources.apply(input), input, output);
	}

	/**
	 * Compiles the layer in a model with back-propagation and an input gradient, and verifies
	 * its output and the gradient with respect to its input.
	 *
	 * @param layer   the layer under test
	 * @param sources the source map of the projection
	 */
	protected void verifyTraining(CellularLayer layer, Function<PackedCollection, int[]> sources) {
		Model model = new Model(layer.getInputShape(), 1e-12);
		model.add(layer);
		CompiledModel compiled = model.compile(true, true);

		PackedCollection input = input(layer.getInputShape());
		int[] map = sources.apply(input);
		PackedCollection output = compiled.forward(input);
		assertProjected("training output", map, input, output);

		TraversalPolicy outputShape = layer.getOutputShape();
		PackedCollection gradient = integers(0, outputShape.getTotalSize())
				.add(0.5).evaluate().reshape(outputShape);

		PackedCollection inputGradient = compiled.backward(gradient);
		Assert.assertEquals(input.getMemLength(), inputGradient.getMemLength());

		double[] expected = new double[input.getMemLength()];
		for (int i = 0; i < map.length; i++) {
			if (map[i] >= 0) expected[map[i]] += gradient.toDouble(i);
		}

		for (int j = 0; j < expected.length; j++) {
			Assert.assertEquals("input gradient " + j, expected[j], inputGradient.toDouble(j), 1e-6);
		}
	}

	/**
	 * Asserts that every output element equals the input element it maps to, or zero.
	 *
	 * @param label  identifies the checked result in failure messages
	 * @param map    the input index of each output element, or -1
	 * @param input  the input the result was computed from
	 * @param output the computed result
	 */
	protected void assertProjected(String label, int[] map, PackedCollection input, PackedCollection output) {
		Assert.assertEquals(label + " length", map.length, output.getMemLength());
		for (int i = 0; i < map.length; i++) {
			double expected = map[i] < 0 ? 0.0 : input.toDouble(map[i]);
			Assert.assertEquals(label + " " + i, expected, output.toDouble(i), 1e-6);
		}
	}

	/**
	 * Creates an input whose elements are distinct, unordered values, so that every pooling
	 * window has a unique maximum at a position that varies between windows.
	 *
	 * @param shape the shape of the input
	 * @return the input
	 */
	protected PackedCollection input(TraversalPolicy shape) {
		int n = shape.getTotalSize();
		int step = n % 7 == 0 ? 5 : 7;
		return integers(0, n).multiply(step).mod(n)
				.add(0.25 - n / 2.0).evaluate().reshape(shape);
	}

	/**
	 * Returns, for each output of a max-pooling of the given input, the index of the input
	 * element holding the maximum of its window.
	 *
	 * @param shape the {@code (n, c, h, w)} input shape
	 * @param size  the pooling window size
	 * @param input the pooled input
	 * @return the source map
	 */
	protected int[] poolSources(TraversalPolicy shape, int size, PackedCollection input) {
		int nc = shape.length(0) * shape.length(1);
		int h = shape.length(2);
		int w = shape.length(3);
		int oh = h / size;
		int ow = w / size;

		int[] map = new int[nc * oh * ow];
		for (int p = 0; p < nc; p++) {
			for (int i = 0; i < oh; i++) {
				for (int j = 0; j < ow; j++) {
					int best = -1;
					for (int a = 0; a < size; a++) {
						for (int b = 0; b < size; b++) {
							int idx = p * h * w + (i * size + a) * w + (j * size + b);
							if (best < 0 || input.toDouble(idx) > input.toDouble(best)) best = idx;
						}
					}

					map[(p * oh + i) * ow + j] = best;
				}
			}
		}

		return map;
	}

	/**
	 * A layer reading the {@code (rows - 2, cols - 1)} block at position {@code (1, 1)} of a
	 * {@code (rows, cols)} input.
	 *
	 * @param rows the input rows
	 * @param cols the input columns
	 * @return the layer
	 */
	protected CellularLayer subsetLayer(int rows, int cols) {
		TraversalPolicy out = shape(rows - 2, cols - 1);
		Factor<PackedCollection> op = in -> c(in).subset(out, 1, 1);
		return layer("subset", shape(rows, cols), out, op);
	}

	/**
	 * The source map of {@link #subsetLayer(int, int)}.
	 *
	 * @param rows the input rows
	 * @param cols the input columns
	 * @return the source map
	 */
	protected int[] subsetSources(int rows, int cols) {
		int r = rows - 2;
		int c = cols - 1;
		int[] map = new int[r * c];
		for (int i = 0; i < r; i++) {
			for (int j = 0; j < c; j++) {
				map[i * c + j] = (i + 1) * cols + (j + 1);
			}
		}

		return map;
	}

	/**
	 * A layer repeating each row of a {@code (rows, cols)} input three times, producing
	 * {@code (rows, 3, cols)}.
	 *
	 * @param rows the input rows
	 * @param cols the input columns
	 * @return the layer
	 */
	protected CellularLayer repeatLayer(int rows, int cols) {
		Factor<PackedCollection> op = in -> c(in).traverse(1).repeat(3);
		return layer("repeat", shape(rows, cols), shape(rows, 3, cols), op);
	}

	/**
	 * The source map of {@link #repeatLayer(int, int)}.
	 *
	 * @param rows the input rows
	 * @param cols the input columns
	 * @return the source map
	 */
	protected int[] repeatSources(int rows, int cols) {
		int[] map = new int[rows * 3 * cols];
		for (int i = 0; i < rows; i++) {
			for (int k = 0; k < 3; k++) {
				for (int j = 0; j < cols; j++) {
					map[(i * 3 + k) * cols + j] = i * cols + j;
				}
			}
		}

		return map;
	}

	/**
	 * A layer transposing a {@code (rows, cols)} input.
	 *
	 * @param rows the input rows
	 * @param cols the input columns
	 * @return the layer
	 */
	protected CellularLayer permuteLayer(int rows, int cols) {
		Factor<PackedCollection> op = in -> c(in).permute(1, 0);
		return layer("permute", shape(rows, cols), shape(cols, rows), op);
	}

	/**
	 * The source map of {@link #permuteLayer(int, int)}.
	 *
	 * @param rows the input rows
	 * @param cols the input columns
	 * @return the source map
	 */
	protected int[] permuteSources(int rows, int cols) {
		int[] map = new int[rows * cols];
		for (int j = 0; j < cols; j++) {
			for (int i = 0; i < rows; i++) {
				map[j * rows + i] = i * cols + j;
			}
		}

		return map;
	}

	/**
	 * A layer placing a {@code (rows, cols)} input at position {@code (1, 2)} of a zero
	 * {@code (rows + 2, cols + 3)} output.
	 *
	 * @param rows the input rows
	 * @param cols the input columns
	 * @return the layer
	 */
	protected CellularLayer padLayer(int rows, int cols) {
		TraversalPolicy out = shape(rows + 2, cols + 3);
		Factor<PackedCollection> op = in -> pad(out, c(in), 1, 2);
		return layer("pad", shape(rows, cols), out, op);
	}

	/**
	 * The source map of {@link #padLayer(int, int)}.
	 *
	 * @param rows the input rows
	 * @param cols the input columns
	 * @return the source map
	 */
	protected int[] padSources(int rows, int cols) {
		int r = rows + 2;
		int c = cols + 3;
		int[] map = new int[r * c];
		for (int i = 0; i < r; i++) {
			for (int j = 0; j < c; j++) {
				int si = i - 1;
				int sj = j - 2;
				map[i * c + j] = si >= 0 && si < rows && sj >= 0 && sj < cols ? si * cols + sj : -1;
			}
		}

		return map;
	}
}
