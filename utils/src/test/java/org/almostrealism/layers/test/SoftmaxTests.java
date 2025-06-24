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
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.BackPropagationCell;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.DefaultBlock;
import org.almostrealism.model.Model;
import org.almostrealism.stats.DistributionFeatures;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class SoftmaxTests implements LayerFeatures, DistributionFeatures, TestFeatures {
	@Test
	public void softmaxComputation() {
		int heads = 12;
		int len = 8; // 1024;
		int l = 4; // 64;

		PackedCollection<?> in = new PackedCollection<>(heads, len).randFill().traverseEach();

		for (int h = 0; h < heads; h++) {
			for (int i = l; i < len; i++) {
				in.setMem(in.getShape().index(h, i), 0.0);
			}
		}

		boolean subtractMax = true;
		Producer<PackedCollection<?>> input = p(in);

		CollectionProducer<PackedCollection<?>> o = softmax(traverse(1, input));

		PackedCollection<?> output = new PackedCollection<>(heads, len);

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

			double x[] = new double[len];
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

	@Test
	public void logSoftmaxDelta() {
		logSoftmaxDelta(false);
	}

	@Test
	public void logSoftmaxDeltaOptimized() {
		logSoftmaxDelta(true);
	}

	protected void logSoftmaxDelta(boolean optimize) {
		PackedCollection<?> input = new PackedCollection(4).fill(2.0);

		CollectionProducer<PackedCollection<?>> softmax = cp(input).traverse(1).subtract(
				cp(input).traverse(1).exp().traverse(0).sum().log());
		CollectionProducer<PackedCollection<?>> delta = softmax.delta(cp(input));

		PackedCollection<?> result = (optimize ? Process.optimized(delta) : delta).get().evaluate();
		result.print();

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				Assert.assertEquals(i == j ? 0.75 : -0.25, result.valueAt(i, j), 1e-5);
			}
		}
	}

	@Test
	public void softmaxBackwards() {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		PackedCollection<?> input = new PackedCollection<>(10);
		IntStream.range(0, 10).forEach(i -> input.setMem(i, i + 1.0));

		PackedCollection<?> gradient = new PackedCollection<>(10);
		gradient.setMem(3, 1.0);

		log("Input = " + Arrays.toString(input.toArray(0, input.getMemLength())));
		log("Gradient = " + Arrays.toString(gradient.toArray(0, gradient.getMemLength())));

		double result[] = new double[10];

		CellularLayer layer = softmax(10);
		layer.getBackward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection<?>> gr = grad.get();

			return () -> {
				PackedCollection<?> out = gr.evaluate();
				System.out.println(Arrays.toString(out.toArray(0, out.getMemLength())));

				out.getMem(0, result, 0, result.length);
			};
		});
		((BackPropagationCell) layer.getBackward()).setForwardInput(input);

		HardwareOperator.verboseLog(() -> {
			layer.getBackward().push(p(gradient)).get().run();
		});

		double expected[] = new double[] { -1.22242448e-07, -3.32289424e-07, -9.03256303e-07,  1.56203074e-03,
				-6.67421149e-06, -1.81423878e-05, -4.93161231e-05, -1.34055121e-04,
				-3.64399601e-04, -9.90540812e-04 };

		for (int i = 0; i < result.length; i++) {
			assertEquals(expected[i], result[i]);
		}
	}

	@Test
	public void softmaxBackwardsLarge() throws IOException {
		TraversalPolicy shape = shape(1, 4, 25088);

		PackedCollection<?> input = new PackedCollection<>(shape);
		IntStream.range(0, shape.getTotalSize()).forEach(i -> input.setMem(i, i + 1.0));

		PackedCollection<?> gradient = new PackedCollection<>(shape);
		gradient.setMem(100, 1.0);

		double result[] = new double[shape.getTotalSize()];

		CellularLayer layer = softmax(shape, true);
		layer.getBackward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection<?>> gr = grad.get();

			return () -> {
				PackedCollection<?> out = gr.evaluate();
				// System.out.println(Arrays.toString(out.toArray(0, out.getMemLength())));

				out.getMem(0, result, 0, result.length);
			};
		});
		((BackPropagationCell) layer.getBackward()).setForwardInput(input);

		Supplier<Runnable> op = Process.optimized(layer.getBackward().push(p(gradient)));
		String projection = String.valueOf(CollectionFeatures.isEnableIndexProjectionDeltaAlt());
		log("enableIndexProjectionDeltaAlt = " + projection);
		profile("softmaxBackwardsLarge", op)
				.save(new File("results/softmaxBackwardsLarge-" + projection + ".xml"));

//		Runnable r = op.get();
//
//		for (int i = 0; i < 20; i++) {
//			r.run();
//		}
	}

	@Test
	public void logSoftmaxBackwards1() {
		int size = 2;

		PackedCollection<?> input = new PackedCollection(shape(1, size));
		IntStream.range(0, size).forEach(i -> input.setMem(i, (i + 1.0) / 10.0));

		PackedCollection<?> gradient = new PackedCollection<>(shape(1, size)).fill(0.0, -1.0);

		System.out.println("Input: " + Arrays.toString(input.toArray(0, input.getMemLength())));
		System.out.println("Gradient: " + Arrays.toString(gradient.toArray(0, gradient.getMemLength())));

		double tot = input.doubleStream().map(Math::exp).sum();

		double result[] = new double[size];

		CellularLayer layer = logSoftmax(shape(size));
		layer.getBackward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection<?>> gr = grad.get();

			return () -> {
				PackedCollection<?> out = gr.evaluate();
				out.print();

				out.getMem(0, result, 0, result.length);
			};
		});
		((BackPropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().push(p(gradient)).get().run();

		double expected[] = IntStream.range(0, size).mapToDouble(i -> Math.exp(input.valueAt(0, i)) / tot).toArray();
		expected[1] -= 1.0;

		for (int i = 0; i < result.length; i++) {
			assertEquals(expected[i], result[i]);
		}
	}

	@Test
	public void logSoftmaxBackwards2() {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		int size = 10;

		PackedCollection<?> input = new PackedCollection(1, size);
		IntStream.range(0, size).forEach(i -> input.setMem(i, (i + 1.0) / 10.0));

		PackedCollection<?> gradient = new PackedCollection<>(1, size);
		gradient.setMem(3, 1.0);

		// log("Input = " + Arrays.toString(input.toArray(0, input.getMemLength())));
		// log("Gradient = " + Arrays.toString(gradient.toArray(0, gradient.getMemLength())));

		double tot = input.doubleStream().map(Math::exp).sum();

		double result[] = new double[10];

		CellularLayer layer = logSoftmax(shape(size));
		layer.getBackward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection<?>> gr = grad.get();

			return () -> {
				PackedCollection<?> out = gr.evaluate();
				log("out = " + out.toArrayString());

				out.getMem(0, result, 0, result.length);
			};
		});
		((BackPropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().push(p(gradient)).get().run();

		double expected[] = IntStream.range(0, size)
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

	@Test
	public void logSoftmaxModel() throws IOException {
		int size = 2;

		PackedCollection<?> input = new PackedCollection(size);
		IntStream.range(0, size).forEach(i -> input.setMem(i, (i + 1.0) / 10.0));

		PackedCollection<?> gradient = new PackedCollection<>(size).fill(0.0, -1.0);

		System.out.println("Input: " + Arrays.toString(input.toArray(0, input.getMemLength())));
		System.out.println("Gradient: " + Arrays.toString(gradient.toArray(0, gradient.getMemLength())));

		double result[] = new double[size];

		Model model = new Model(shape(size));
		model.add(new DefaultBlock(shape(2), shape(2),
				Cell.of((in, next) -> next.push(in)),
				Cell.of((grad, next) -> () -> {
					Evaluable<PackedCollection<?>> gr = grad.get();

					return () -> {
						PackedCollection<?> out = gr.evaluate();
						out.print();

						out.getMem(0, result, 0, result.length);
					};
				})));
		model.add(logSoftmax(size));

		OperationProfileNode profile = initKernelMetrics(new OperationProfileNode("logSoftmaxModel"));
		try {
			CompiledModel compiled = model.compile(profile);
			compiled.forward(input);
			compiled.backward(gradient);

			double tot = input.doubleStream().map(Math::exp).sum();
			double expected[] = IntStream.range(0, size).mapToDouble(i -> Math.exp(input.valueAt(i)) / tot).toArray();
			expected[1] -= 1.0;

			for (int i = 0; i < result.length; i++) {
				Assert.assertEquals(expected[i], result[i], 1e-5);
			}
		} finally {
			profile.save("results/logSoftmaxModel.xml");
		}
	}

	@Test
	public void softmaxTest() {
		int seqLen = 20;

		PackedCollection<?> originalInput = new PackedCollection<>(shape(1, seqLen)).randFill();
		CollectionProducer<PackedCollection<?>> input = cp(copy(originalInput));

		double values[] = originalInput.toArray();
		softmax(values, 0, seqLen);

		int axis = input.getShape().getDimensions() - 1;

		CollectionProducer<PackedCollection<?>> max = traverse(axis, input).max();
		CollectionProducer<PackedCollection<?>> stable =
				traverse(axis + 1, input).subtract(max.expand(seqLen));
		CollectionProducer<PackedCollection<?>> logSum =
				stable.exp().traverse(axis).sum().log().expand(seqLen);
		CollectionProducer<PackedCollection<?>> result = stable.subtract(logSum).exp();

		compare(null, result.evaluate(), values);
	}

	@Test
	public void softmaxLayer() {
		int seqLength = 20;

		PackedCollection<?> originalInput = new PackedCollection<>(shape(1, seqLength));
		originalInput.fill(pos -> Math.random());
		PackedCollection<?> input = copy(originalInput);

		Producer<PackedCollection<?>> p = softmax(input.getShape(), true).apply(cp(input));
		PackedCollection<?> destination = p.get().evaluate();

		double values[] = originalInput.toArray();
		softmax(values, 0, seqLength);

		compare(null, destination, values);
	}

	@Test
	public void softmaxSubset() {
		int heads = 1;
		int seqLength = 20;

		int h = 0;

		PackedCollection<?> originalInput = new PackedCollection<>(shape(heads, seqLength));
		originalInput.fill(pos -> Math.random());
		PackedCollection<?> input = copy(originalInput);

		verboseLog(() -> {
			Producer<PackedCollection<?>> in = traverseEach(p(input));
			CollectionProducer<PackedCollection<?>> subset = c(subset(shape(1, seqLength), in, h, 0));

			Producer<PackedCollection<?>> p = softmax(subset.getShape(), true).apply(subset);

			// CollectionProducer<PackedCollection<?>> p = subset.exp().divide(subset.exp().traverse(0).sum());

			double values[] = originalInput.toArray(0, originalInput.getShape().getTotalSize());
			softmax(values, h * seqLength, seqLength);

			compare(a(subset.each(), p), input, values);
		});
	}

	protected static void softmax(double[] x, int offset, int size) {
		double max = x[0 + offset];
		for (int i = 1; i < size; i++) {
			if (x[i + offset] > max) {
				max = x[i + offset];
			}
		}

		double sum = 0.0;
		double stable[] = new double[size];

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

	protected void compare(Supplier<Runnable> op, PackedCollection<?> dest, double values[]) {
		if (op != null) {
			op.get().run();
		}

		double out[] = dest.toArray(0, values.length);
		for (int i = 0; i < values.length; i++) {
			if (Math.abs(out[i] - values[i]) > 1e-5) {
				throw new AssertionError("Mismatch at " + i + ": " + out[i] + " != " + values[i]);
			}
		}
	}

	private PackedCollection<?> copy(PackedCollection<?> input) {
		PackedCollection<?> output = new PackedCollection<>(input.getShape());
		output.fill(pos -> input.valueAt(pos));
		return output;
	}
}
