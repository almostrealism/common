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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.Arrays;

public class LayersTests implements LayerFeatures, TestFeatures {
	private static final int SIZE = 10; // 768;

	private float cpuOut[];
	private float gpuOut[];
	private double cpuSum = -1.0;
	private double gpuSum = -1.0;

	@Test
	public void rmsnorm() {
		KernelPreferences.optimizeForMetal();

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
			// System.out.println("CPU: " + Arrays.toString(o.toArray(0, 10)));
			// cpuSum = o.traverseEach().stream().mapToDouble(d -> d.toDouble(0)).sum();
		});

		SequentialBlock gpuModel = new SequentialBlock(shape(SIZE));
		gpuModel.add(rmsnorm(weights, ComputeRequirement.GPU));
		gpuModel.getForward().setReceptor(out -> () -> () -> {
			PackedCollection<?> o = out.get().evaluate();
			gpuOut = new float[SIZE];
			o.getMem(0, gpuOut, 0, SIZE);
			// System.out.println("GPU: " + Arrays.toString(o.toArray(0, 10)));
			// gpuSum = o.traverseEach().stream().mapToDouble(d -> d.toDouble(0)).sum();
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
				throw new RuntimeException("Mismatch at " + i + ": " + gpuOut[i] + " vs " + cpuOut[i]);
			}
		}
	}
}
