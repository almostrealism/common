/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.layers.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.BackPropagationCell;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.DefaultBlock;
import org.almostrealism.model.Model;
import org.almostrealism.stats.DistributionFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Tests for softmax operations including computation and backward pass.
 */
public class SoftmaxTests extends TestSuiteBase implements LayerFeatures, DistributionFeatures {
	/**
	 * Tests basic softmax computation across multiple heads.
	 */
	@Test(timeout = 60000)
	public void softmaxComputation() {
		int heads = 12;
		int len = 8; // 1024;
		int l = 4; // 64;

		PackedCollection in = new PackedCollection(heads, len).randFill().traverseEach();

		for (int h = 0; h < heads; h++) {
			for (int i = l; i < len; i++) {
				in.setMem(in.getShape().index(h, i), 0.0);
			}
		}

		boolean subtractMax = true;
		Producer<PackedCollection> input = p(in);

		CollectionProducer o = softmax(traverse(1, input));

		PackedCollection output = new PackedCollection(heads, len);

		OperationList op = new OperationList();
		op.add(a(traverse(1, p(output)), o));
		op.optimize().get().run();

		for (int h = 0; h < heads; h++) {
			double max = in.valueAt(h, 0);
			for (int i = 1; i < l; i++) {
				if (in.valueAt(h, i) > max) {
					max = in.valueAt(h, i);
				}
			}

			double[] x = new double[len];
			double sum = 0.0;
			for (int i = 0; i < l; i++) {
				x[i] = subtractMax ? Math.exp(in.valueAt(h, i) - max) : Math.exp(in.valueAt(h, i));
				sum += x[i];
			}

			for (int i = 0; i < l; i++) {
				x[i] /= sum;
				double actual = output.valueAt(h, i);
				if (verboseLogs)
					log("[" + h + "] " + x[i] + " vs " + actual);
				assertEquals(x[i], actual);
			}
		}
	}

	/**
	 * Tests log softmax delta computation.
	 */
	@Test(timeout = 60000)
	public void logSoftmaxDelta() {
		logSoftmaxDelta(false);
	}

	/**
	 * Tests log softmax delta computation with optimization.
	 */
	@Test(timeout = 60000)
	public void logSoftmaxDeltaOptimized() {
		logSoftmaxDelta(true);
	}

	/**
	 * Helper for log softmax delta testing.
	 */
	protected void logSoftmaxDelta(boolean optimize) {
		PackedCollection input = new PackedCollection(4).fill(2.0);

		CollectionProducer softmax = cp(input).traverse(1).subtract(
				cp(input).traverse(1).exp().traverse(0).sum().log());
		CollectionProducer delta = softmax.delta(cp(input));

		PackedCollection result = (optimize ? Process.optimized(delta) : delta).get().evaluate();
		result.print();

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				Assert.assertEquals(i == j ? 0.75 : -0.25, result.valueAt(i, j), 1e-5);
			}
		}
	}

	/**
	 * Tests softmax backward pass with gradient computation.
	 */
	@Test(timeout = 60000)
	public void softmaxBackwards() {
		PackedCollection input = new PackedCollection(10);
		integers(1, 11).into(input.traverseEach()).evaluate();

		PackedCollection gradient = new PackedCollection(10);
		gradient.setMem(3, 1.0);

		log("Input = " + Arrays.toString(input.toArray(0, input.getMemLength())));
		log("Gradient = " + Arrays.toString(gradient.toArray(0, gradient.getMemLength())));

		double[] result = new double[10];

		CellularLayer layer = softmax(10);
		layer.getBackward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection> gr = grad.get();

			return () -> {
				PackedCollection out = gr.evaluate();
				log(Arrays.toString(out.toArray(0, out.getMemLength())));

				out.getMem(0, result, 0, result.length);
			};
		});
		((BackPropagationCell) layer.getBackward()).setForwardInput(input);

		HardwareOperator.verboseLog(() -> {
			layer.getBackward().push(p(gradient)).get().run();
		});

		double[] expected = new double[] { -1.22242448e-07, -3.32289424e-07, -9.03256303e-07,  1.56203074e-03,
				-6.67421149e-06, -1.81423878e-05, -4.93161231e-05, -1.34055121e-04,
				-3.64399601e-04, -9.90540812e-04 };

		for (int i = 0; i < result.length; i++) {
			// Use 1% relative tolerance - acceptable for ML gradient computations
			// Hardware-accelerated code may produce slightly different results due to
			// floating-point operation ordering differences
			Assert.assertEquals(expected[i], result[i], Math.abs(expected[i]) * 0.01);
		}
	}

	/**
	 * Tests softmax backward pass with large input shape.
	 */
	@Test(timeout = 2 * 60000)
	@TestDepth(1)
	public void softmaxBackwardsLarge() throws IOException {
		TraversalPolicy shape = shape(1, 4, 25088);

		PackedCollection input = new PackedCollection(shape);
		integers(1, shape.getTotalSize() + 1).into(input.traverseEach()).evaluate();

		PackedCollection gradient = new PackedCollection(shape);
		gradient.setMem(100, 1.0);

		double[] result = new double[shape.getTotalSize()];

		CellularLayer layer = softmax(shape, true);
		layer.getBackward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection> gr = grad.get();

			return () -> {
				PackedCollection out = gr.evaluate();
				out.getMem(0, result, 0, result.length);
			};
		});
		((BackPropagationCell) layer.getBackward()).setForwardInput(input);

		Supplier<Runnable> op = Process.optimized(layer.getBackward().push(p(gradient)));
		profile("softmaxBackwardsLarge", op)
				.save(new File("results/softmaxBackwardsLarge.xml"));

//		Runnable r = op.get();
//
//		for (int i = 0; i < 20; i++) {
//			r.run();
//		}
	}

	/**
	 * Tests log softmax backward pass with small input.
	 */
	@Test(timeout = 60000)
	public void logSoftmaxBackwards1() {
		int size = 2;

		PackedCollection input = new PackedCollection(shape(1, size));
		integers(1, size + 1).divide(10.0).into(input.traverseEach()).evaluate();

		PackedCollection gradient = new PackedCollection(shape(1, size)).fill(0.0, -1.0);

		log("Input: " + Arrays.toString(input.toArray(0, input.getMemLength())));
		log("Gradient: " + Arrays.toString(gradient.toArray(0, gradient.getMemLength())));

		double tot = input.doubleStream().map(Math::exp).sum();

		double[] result = new double[size];

		CellularLayer layer = logSoftmax(shape(size));
		layer.getBackward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection> gr = grad.get();

			return () -> {
				PackedCollection out = gr.evaluate();
				out.print();

				out.getMem(0, result, 0, result.length);
			};
		});
		((BackPropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().push(p(gradient)).get().run();

		double[] expected = IntStream.range(0, size).mapToDouble(i -> Math.exp(input.valueAt(0, i)) / tot).toArray();
		expected[1] -= 1.0;

		for (int i = 0; i < result.length; i++) {
			assertEquals(expected[i], result[i]);
		}
	}

	/**
	 * Tests log softmax backward pass with gradient at specific index.
	 */
	@Test(timeout = 60000)
	public void logSoftmaxBackwards2() {
		int size = 10;

		PackedCollection input = new PackedCollection(1, size);
		integers(1, size + 1).divide(10.0).into(input.traverseEach()).evaluate();

		PackedCollection gradient = new PackedCollection(1, size);
		gradient.setMem(3, 1.0);

		// log("Input = " + Arrays.toString(input.toArray(0, input.getMemLength())));
		// log("Gradient = " + Arrays.toString(gradient.toArray(0, gradient.getMemLength())));

		double tot = input.doubleStream().map(Math::exp).sum();

		double[] result = new double[10];

		CellularLayer layer = logSoftmax(shape(size));
		layer.getBackward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection> gr = grad.get();

			return () -> {
				PackedCollection out = gr.evaluate();
				log("out = " + out.toArrayString());

				out.getMem(0, result, 0, result.length);
			};
		});
		((BackPropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().push(p(gradient)).get().run();

		double[] expected = IntStream.range(0, size)
				.mapToDouble(i -> -Math.exp(input.valueAt(0, i)) / tot)
				.toArray();
		int idx = gradient.argmax();
		log("Gradient max idx = " + idx);

		expected[gradient.argmax()] += 1.0;

		for (int i = 0; i < result.length; i++) {
			log("result[" + i + "] = " + result[i] +
					" (expected " + expected[i] + ")");
			assertEquals(expected[i], result[i]);
		}
	}

	/**
	 * Tests the log softmax backward pass over a multi-row {@code (rows, size)} input: each row
	 * is normalized independently, so a gradient arriving in one row produces the log-softmax
	 * input gradient {@code g - softmax(x) * sum(g)} in that row and exactly zero in every other
	 * row.
	 */
	@Test(timeout = 60000)
	public void logSoftmaxBackwardsMultiRow() {
		int rows = 3;
		int size = 6;
		int active = 1;

		PackedCollection input = new PackedCollection(shape(rows, size));
		integers(0, rows * size).multiply(0.37).subtract(2.0).into(input.traverseEach()).evaluate();

		PackedCollection gradient = new PackedCollection(shape(rows, size));
		gradient.clear();
		gradient.setMem(active * size + 2, -1.0);
		gradient.setMem(active * size + 4, 0.5);

		Model model = new Model(shape(rows, size));
		model.add(logSoftmax(shape(rows, size)));
		CompiledModel compiled = model.compile(true, true);
		compiled.forward(input);
		PackedCollection result = compiled.backward(gradient);

		for (int r = 0; r < rows; r++) {
			double total = 0.0;
			double gradientSum = 0.0;
			for (int i = 0; i < size; i++) {
				total += Math.exp(input.valueAt(r, i));
				gradientSum += gradient.valueAt(r, i);
			}

			for (int i = 0; i < size; i++) {
				double softmax = Math.exp(input.valueAt(r, i)) / total;
				double expected = gradient.valueAt(r, i) - softmax * gradientSum;
				Assert.assertEquals("row " + r + " column " + i, expected,
						result.toDouble(r * size + i), 1e-5);
			}
		}
	}

	/**
	 * Tests log softmax as part of a model.
	 */
	@Test(timeout = 60000)
	public void logSoftmaxModel() throws IOException {
		int size = 2;

		PackedCollection input = new PackedCollection(size);
		integers(1, size + 1).divide(10.0).into(input.traverseEach()).evaluate();

		PackedCollection gradient = new PackedCollection(size).fill(0.0, -1.0);

		log("Input: " + Arrays.toString(input.toArray(0, input.getMemLength())));
		log("Gradient: " + Arrays.toString(gradient.toArray(0, gradient.getMemLength())));

		double[] result = new double[size];

		Model model = new Model(shape(size));
		model.add(new DefaultBlock(shape(2), shape(2),
				Cell.of((in, next) -> next.push(in)),
				Cell.of((grad, next) -> () -> {
					Evaluable<PackedCollection> gr = grad.get();

					return () -> {
						PackedCollection out = gr.evaluate();
						out.print();

						out.getMem(0, result, 0, result.length);
					};
				})));
		model.add(logSoftmax(size));

		OperationProfileNode profile = initKernelMetrics(new OperationProfileNode("logSoftmaxModel"));
		try {
			CompiledModel compiled = model.compile(profile);
			compiled.forward(input.reshape(compiled.getInputShape()));
			compiled.backward(gradient.reshape(compiled.getOutputShape()));

			double tot = input.doubleStream().map(Math::exp).sum();
			double[] expected = IntStream.range(0, size).mapToDouble(i -> Math.exp(input.valueAt(i)) / tot).toArray();
			expected[1] -= 1.0;

			for (int i = 0; i < result.length; i++) {
				Assert.assertEquals(expected[i], result[i], 1e-5);
			}
		} finally {
			profile.save("results/logSoftmaxModel.xml");
		}
	}

	/**
	 * Tests softmax computation with sequence input.
	 */
	@Test(timeout = 60000)
	public void softmaxTest() {
		int seqLen = 20;

		PackedCollection originalInput = new PackedCollection(shape(1, seqLen)).randFill();
		CollectionProducer input = cp(originalInput.clone());

		double[] values = originalInput.toArray();
		softmax(values, 0, seqLen);

		int axis = input.getShape().getDimensions() - 1;

		CollectionProducer max = traverse(axis, input).max();
		CollectionProducer stable =
				traverse(axis + 1, input).subtract(max.expand(seqLen));
		CollectionProducer logSum =
				stable.exp().traverse(axis).sum().log().expand(seqLen);
		CollectionProducer result = stable.subtract(logSum).exp();

		compare(null, result.evaluate(), values);
	}

	/**
	 * Tests softmax layer computation.
	 */
	@Test(timeout = 60000)
	public void softmaxLayer() {
		int seqLength = 20;

		PackedCollection originalInput = new PackedCollection(shape(1, seqLength));
		originalInput.randFill();
		PackedCollection input = originalInput.clone();

		Producer<PackedCollection> p = softmax(input.getShape(), true).apply(cp(input));
		PackedCollection destination = p.get().evaluate();

		double[] values = originalInput.toArray();
		softmax(values, 0, seqLength);

		compare(null, destination, values);
	}

	/**
	 * Tests softmax computation on a subset of input.
	 */
	@Test(timeout = 60000)
	public void softmaxSubset() {
		int heads = 1;
		int seqLength = 20;

		int h = 0;

		PackedCollection originalInput = new PackedCollection(shape(heads, seqLength));
		originalInput.randFill();
		PackedCollection input = originalInput.clone();

		verboseLog(() -> {
			Producer<PackedCollection> in = traverseEach(p(input));
			CollectionProducer subset = c(subset(shape(1, seqLength), in, h, 0));

			Producer<PackedCollection> p = softmax(subset.getShape(), true).apply(subset);

			// CollectionProducer<PackedCollection> p = subset.exp().divide(subset.exp().traverse(0).sum());

			double[] values = originalInput.toArray(0, originalInput.getShape().getTotalSize());
			softmax(values, h * seqLength, seqLength);

			compare(a(subset.each(), p), input, values);
		});
	}

	/**
	 * The flat softmax layer must stay finite and correct when a logit is far above the
	 * point where {@code exp} overflows in single precision (about 88.7), when every logit
	 * is far below the point where it underflows, and must still match for small logits.
	 */
	@Test(timeout = 60000)
	public void softmaxLayerLargeLogits() {
		assertLayerOutput(softmax(), pack(100.0, 0.0, 99.0),
				pack(0.7310586, 0.0, 0.2689414));
		assertLayerOutput(softmax(), pack(-1000.0, -1001.0, -1002.0),
				pack(0.6652410, 0.2447285, 0.0900306));
		assertLayerOutput(softmax(), pack(-1.0, 0.0, -2.0),
				pack(0.2447285, 0.6652410, 0.0900306));
	}

	/**
	 * The log-softmax layer must stay finite and correct for large positive and large
	 * negative logits, for a single row and for a batch of rows normalized independently.
	 */
	@Test(timeout = 60000)
	public void logSoftmaxLayerLargeLogits() {
		assertLayerOutput(logSoftmax(), pack(100.0, 0.0, 99.0).reshape(shape(1, 3)),
				pack(-0.3132617, -100.3132617, -1.3132617));
		assertLayerOutput(logSoftmax(), pack(-1000.0, -1001.0, -1002.0).reshape(shape(1, 3)),
				pack(-0.4076059, -1.4076059, -2.4076059));
		assertLayerOutput(logSoftmax(),
				pack(100.0, 0.0, 99.0, -1.0, 0.0, -2.0, -1000.0, -1001.0, -1002.0).reshape(shape(3, 3)),
				pack(-0.3132617, -100.3132617, -1.3132617,
						-1.4076059, -0.4076059, -2.4076059,
						-0.4076059, -1.4076059, -2.4076059));
	}

	/**
	 * The two-dimensional softmax layer with max subtraction normalizes each row
	 * independently, including a row with large logits.
	 */
	@Test(timeout = 60000)
	public void softmax2dLayerLargeLogits() {
		assertLayerOutput(softmax(true),
				pack(-1.0, 0.0, -2.0, 100.0, 0.0, 99.0).reshape(shape(2, 3)),
				pack(0.2447285, 0.6652410, 0.0900306, 0.7310586, 0.0, 0.2689414));
	}

	/**
	 * The input gradient of the flat softmax layer matches central differences of its
	 * forward output; subtracting the maximum must not change the gradient.
	 */
	@Test(timeout = 2 * 60000)
	public void softmaxLayerGradientMatchesFiniteDifferences() {
		assertGradientMatchesFiniteDifferences(softmax(),
				pack(0.3, -1.2, 2.0, 0.7),
				pack(0.5, -1.0, 0.25, 2.0));
	}

	/**
	 * The input gradient of the log-softmax layer matches central differences of its
	 * forward output for a batch of rows.
	 */
	@Test(timeout = 2 * 60000)
	public void logSoftmaxLayerGradientMatchesFiniteDifferences() {
		assertGradientMatchesFiniteDifferences(logSoftmax(),
				pack(0.3, -1.2, 2.0, 0.7, 1.1, -0.4).reshape(shape(2, 3)),
				pack(0.5, -1.0, 0.25, 2.0, 0.0, -0.75).reshape(shape(2, 3)));
	}

	/**
	 * The input gradient of the two-dimensional softmax layer matches central
	 * differences of its forward output for a batch of rows.
	 */
	@Test(timeout = 2 * 60000)
	public void softmax2dLayerGradientMatchesFiniteDifferences() {
		assertGradientMatchesFiniteDifferences(softmax(true),
				pack(0.3, -1.2, 2.0, 0.7, 1.1, -0.4).reshape(shape(2, 3)),
				pack(0.5, -1.0, 0.25, 2.0, 0.0, -0.75).reshape(shape(2, 3)));
	}

	/**
	 * Runs a single-layer inference model forward and compares its output to the expected
	 * values, requiring every output to be finite.
	 *
	 * @param layer    factory for the layer under test
	 * @param input    the input, whose shape is the input shape of the model
	 * @param expected the expected output values
	 */
	protected void assertLayerOutput(Function<TraversalPolicy, CellularLayer> layer,
									 PackedCollection input, PackedCollection expected) {
		Model model = new Model(input.getShape());
		model.add(layer);
		double[] actual = model.compile(false).forward(input).toArray();
		double[] values = expected.toArray();

		log("output = " + Arrays.toString(actual));
		Assert.assertEquals(values.length, actual.length);
		for (int i = 0; i < values.length; i++) {
			Assert.assertTrue("output " + i + " is not finite: " + actual[i], Double.isFinite(actual[i]));
			Assert.assertEquals("output " + i, values[i], actual[i], 1e-4);
		}
	}

	/**
	 * Back-propagates {@code gradient} through a single-layer model and compares the
	 * input gradient with the central differences of {@code sum(gradient * f(x))}.
	 *
	 * @param layer    factory for the layer under test
	 * @param input    the input, whose shape is the input shape of the model
	 * @param gradient the gradient arriving at the output of the layer
	 */
	protected void assertGradientMatchesFiniteDifferences(Function<TraversalPolicy, CellularLayer> layer,
														  PackedCollection input,
														  PackedCollection gradient) {
		TraversalPolicy shape = input.getShape();
		Model model = new Model(shape);
		model.add(layer);
		CompiledModel training = model.compile(true, true);
		training.forward(input);
		double[] actual = training.backward(gradient).toArray();

		CompiledModel inference = model.compile(false);
		double h = 1e-2;

		for (int i = 0; i < shape.getTotalSize(); i++) {
			PackedCollection step = new PackedCollection(shape);
			step.clear();
			step.setMem(i, 1.0);

			PackedCollection fPlus = inference.forward(
					cp(input).add(cp(step).multiply(h)).evaluate()).clone();
			PackedCollection fMinus = inference.forward(
					cp(input).subtract(cp(step).multiply(h)).evaluate()).clone();

			double expected = cp(gradient).multiply(cp(fPlus).subtract(cp(fMinus)))
					.traverse(0).sum().divide(2 * h).evaluate().toDouble(0);

			log("gradient[" + i + "] = " + actual[i] + " (expected " + expected + ")");
			Assert.assertEquals("gradient " + i, expected, actual[i], 1e-3);
		}
	}

	/**
	 * Applies softmax to an array in-place.
	 *
	 * @param x Array to transform
	 * @param offset Starting offset in array
	 * @param size Number of elements to process
	 */
	protected static void softmax(double[] x, int offset, int size) {
		double max = x[offset];
		for (int i = 1; i < size; i++) {
			if (x[i + offset] > max) {
				max = x[i + offset];
			}
		}

		double sum = 0.0;
		double[] stable = new double[size];

		for (int i = 0; i < size; i++) {
			stable[i] = x[i + offset] - max;
			x[i + offset] = Math.exp(stable[i]);
			sum += x[i + offset];
		}

		if (enableLogStability) {
			double logSum = Math.log(sum);
			for (int i = 0; i < size; i++) {
				x[i + offset] = Math.exp(stable[i] - logSum);
			}
		} else {
			for (int i = 0; i < size; i++) {
				x[i + offset] /= sum;
			}
		}
	}

	/**
	 * Compares computed softmax output against expected values.
	 *
	 * @param op Optional operation to run before comparison
	 * @param dest Destination collection with computed values
	 * @param values Expected values
	 */
	protected void compare(Supplier<Runnable> op, PackedCollection dest, double[] values) {
		if (op != null) {
			op.get().run();
		}

		double[] out = dest.toArray(0, values.length);
		for (int i = 0; i < values.length; i++) {
			if (Math.abs(out[i] - values[i]) > 1e-5) {
				throw new AssertionError("Mismatch at " + i + ": " + out[i] + " != " + values[i]);
			}
		}
	}

}
