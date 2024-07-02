/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.PropagationCell;
import org.almostrealism.stats.DistributionFeatures;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

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
				System.out.println("LayerTest[" + h + "] " + x[i] + " vs " + actual);
				assertEquals(x[i], actual);
			}
		}
	}

	@Test
	public void logSoftmaxDelta() {
		PackedCollection<?> input = new PackedCollection(4).fill(2.0);

		CollectionProducer<PackedCollection<?>> softmax = cp(input).traverse(1).subtract(
				cp(input).traverse(1).exp().traverse(0).sum().log());
		CollectionProducer<PackedCollection<?>> delta = softmax.delta(cp(input));

		PackedCollection<?> result = delta.get().evaluate();
		result.print();

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				Assert.assertEquals(i == j ? 0.75 : -0.25, result.valueAt(i, j), 1e-5);
			}
		}
	}

	@Test
	public void softmaxBackwards() {
		PackedCollection<?> input = new PackedCollection(10);
		IntStream.range(0, 10).forEach(i -> input.setMem(i, i + 1.0));

		PackedCollection<?> gradient = new PackedCollection<>(10);
		gradient.setMem(3, 1.0);

		System.out.println("Input: " + Arrays.toString(input.toArray(0, input.getMemLength())));
		System.out.println("Gradient: " + Arrays.toString(gradient.toArray(0, gradient.getMemLength())));

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
		((PropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().push(p(gradient)).get().run();

		double expected[] = new double[] { -1.22242448e-07, -3.32289424e-07, -9.03256303e-07,  1.56203074e-03,
				-6.67421149e-06, -1.81423878e-05, -4.93161231e-05, -1.34055121e-04,
				-3.64399601e-04, -9.90540812e-04 };

		for (int i = 0; i < result.length; i++) {
			Assert.assertEquals(expected[i], result[i], 1e-5);
		}
	}

	@Test
	public void logSoftmaxBackwards() {
		PackedCollection<?> input = new PackedCollection(10).fill(0.1);
		// IntStream.range(0, 10).forEach(i -> input.setMem(i, i + 1.0));

		PackedCollection<?> gradient = new PackedCollection<>(10);
		gradient.setMem(3, 1.0);

		System.out.println("Input: " + Arrays.toString(input.toArray(0, input.getMemLength())));
		System.out.println("Gradient: " + Arrays.toString(gradient.toArray(0, gradient.getMemLength())));

		double tot = input.doubleStream().map(Math::exp).sum();

		double result[] = new double[10];

		CellularLayer layer = logSoftmax(10);
		layer.getBackward().setReceptor(grad -> () -> {
			Evaluable<PackedCollection<?>> gr = grad.get();

			return () -> {
				PackedCollection<?> out = gr.evaluate();
				System.out.println(Arrays.toString(out.toArray(0, out.getMemLength())));

				out.getMem(0, result, 0, result.length);
			};
		});
		((PropagationCell) layer.getBackward()).setForwardInput(input);
		layer.getBackward().push(p(gradient)).get().run();

		double expected[] = IntStream.range(0, 10).mapToDouble(i -> -Math.exp(input.valueAt(i)) / tot).toArray();
		expected[gradient.argmax()] += 1.0;

		for (int i = 0; i < result.length; i++) {
			Assert.assertEquals(expected[i], result[i], 1e-5);
		}
	}

	@Test
	public void softmaxTest() {
		int heads = 1;
		int seqLength = 20;

		int h = 0;

		PackedCollection<?> originalInput = new PackedCollection<>(shape(heads, seqLength));
		originalInput.fill(pos -> Math.random());
		PackedCollection<?> input = copy(originalInput);

		HardwareOperator.verboseLog(() -> {
			Producer<PackedCollection<?>> in = traverseEach(p(input));
			CollectionProducer<PackedCollection<?>> subset = c(subset(shape(1, seqLength), in, h, 0)).traverseEach();
			CollectionProducer<PackedCollection<?>> p = subset.exp().divide(subset.exp().traverse(0).sum());

			double values[] = originalInput.toArray(0, originalInput.getShape().getTotalSize());
			softmax(values, h * seqLength, seqLength);

			compare(a(subset, p), input, values);
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

		for (int i = 0; i < size; i++) {
			x[i + offset] = Math.exp(x[i + offset] - max);
			sum += x[i + offset];
		}

		for (int i = 0; i < size; i++) {
			x[i + offset] /= sum;
		}
	}

	protected void compare(Supplier<Runnable> op, PackedCollection<?> dest, double values[]) {
		op.get().run();

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
