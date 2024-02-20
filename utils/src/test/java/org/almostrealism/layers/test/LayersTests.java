/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.List;

public class LayersTests implements LayerFeatures, TestFeatures {
	private static final int SIZE = 768;

	private float cpuOut[];
	private float gpuOut[];
	private double cpuSum = -1.0;
	private double gpuSum = -1.0;

	@Test
	public void exponent() {
		PackedCollection<?> in = new PackedCollection<>(SIZE).traverseEach();
		in.fill(pos -> Math.random());

		PackedCollection<?> weights = new PackedCollection<>(SIZE).traverseEach();
		weights.fill(pos -> Math.random());

		PackedCollection<?> cpuOut = new PackedCollection<>(SIZE);
		PackedCollection<?> gpuOut = new PackedCollection<>(SIZE);

		HardwareOperator.verboseLog(() -> {
			OperationList cop = new OperationList();
			cop.setComputeRequirements(List.of(ComputeRequirement.CPU));
			cop.add(a(p(cpuOut), pow(p(in), p(weights))));

			OperationList gop = new OperationList();
			gop.setComputeRequirements(List.of(ComputeRequirement.GPU));
			gop.add(a(p(gpuOut), pow(p(in), p(weights))));

			cop.get().run();
			gop.get().run();

			float cpu[] = new float[SIZE];
			cpuOut.getMem(0, cpu, 0, SIZE);

			float gpu[] = new float[SIZE];
			gpuOut.getMem(0, gpu, 0, SIZE);

			for (int i = 0; i < SIZE; i++) {
				if (gpu[i] != cpu[i]) {
					// throw new RuntimeException("Mismatch at " + i + ": " + gpu[i] + " vs " + cpu[i]);
				}
			}
		});
	}

	@Test
	public void rmsnorm() {
		PackedCollection<?> in = new PackedCollection<>(shape(SIZE));
		in.fill(pos -> Math.random());

		PackedCollection<?> weights = new PackedCollection<>(shape(SIZE));
		weights.fill(pos -> Math.random());

		SequentialBlock cpuModel = new SequentialBlock(shape(SIZE));
		cpuModel.add(rmsnorm(weights));
		cpuModel.getForward().setReceptor(out -> () -> () -> {
			PackedCollection<?> o = out.get().evaluate();
			cpuOut = new float[SIZE];
			o.getMem(0, cpuOut, 0, SIZE);
		});

		SequentialBlock gpuModel = new SequentialBlock(shape(SIZE));
		gpuModel.add(rmsnorm(weights, ComputeRequirement.GPU));
		gpuModel.getForward().setReceptor(out -> () -> () -> {
			PackedCollection<?> o = out.get().evaluate();
			gpuOut = new float[SIZE];
			o.getMem(0, gpuOut, 0, SIZE);
		});

		HardwareOperator.verboseLog(() -> {
			OperationList cop = ((OperationList) cpuModel.getForward().push(p(in)));
			cop = cop.flatten();
			cop = (OperationList) cop.optimize();
			cop.get().run();

			OperationList gop = ((OperationList) gpuModel.getForward().push(p(in)));
			gop = gop.flatten();
			gop = (OperationList) gop.optimize();
			gop.get().run();
		});

		System.out.println("CPU vs GPU = " + cpuSum + " vs " + gpuSum);

		for (int i = 0; i < SIZE; i++) {
			assertEquals(gpuOut[i], cpuOut[i]);
		}

		for (int i = 0; i < SIZE; i++) {
			if (gpuOut[i] != cpuOut[i]) {
				// throw new RuntimeException("Mismatch at " + i + ": " + gpuOut[i] + " vs " + cpuOut[i]);
			}
		}
	}


	@Test
	public void softmaxComputation() {
		int heads = 12;
		int len = KernelPreferences.isPreferLoops() ? 1024 : 8;
		int l = KernelPreferences.isPreferLoops() ? 64 : 4;

		PackedCollection<?> in = new PackedCollection<>(heads, len).randFill().traverseEach();
//		PackedCollection<?> subtractMax = new PackedCollection<>(heads, len);
//		PackedCollection<?> exp = new PackedCollection<>(heads, len);
//		PackedCollection<?> norm = new PackedCollection<>(heads, len);

		for (int h = 0; h < heads; h++) {
			for (int i = l; i < len; i++) {
				in.setMem(in.getShape().index(h, i), 0.0);
			}
		}

		Producer<PackedCollection<?>> input = p(in);
		boolean subtractMax = true;

		HardwareOperator.verboseLog(() -> {
//			cp(in).traverse(2).subtract(cp(in).traverse(1).max().expand(len, v -> v.repeat(len))).get().into(subtractMax.traverseEach()).evaluate();
//			cp(subtractMax).exp().get().into(exp).evaluate();
//			cp(exp).traverse(1).divide(cp(exp).traverse(1).sum().expand(len, v -> v.repeat(len))).get().into(norm.traverse(1)).evaluate();

			CollectionProducer<PackedCollection<?>> o = traverse(1, input);

			if (subtractMax) {
				o = o.max();
				o = o.expand(len, v -> v.repeat(len));
				o = traverse(2, input).subtractIgnoreZero(o);
			}

			o = o.expIgnoreZero().traverse(1);
//			o = o.divide(o.sum().expand(len, v -> v.repeat(len)));
			o = o.divide(o.sum().repeat(len).consolidate());

			// PackedCollection<?> output = o.get().evaluate();

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
		});
	}
}
