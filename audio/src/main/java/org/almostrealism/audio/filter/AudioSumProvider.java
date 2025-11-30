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

package org.almostrealism.audio.filter;

import io.almostrealism.code.ComputationBase;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.kernel.KernelPreferences;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.io.TimingMetric;

import java.util.List;

public class AudioSumProvider implements CellFeatures {
	public static TimingMetric timing = CellFeatures.console.timing("audioSumProvider");

	private final boolean parallel;
	private final Evaluable<PackedCollection> sum;
	private final Evaluable<PackedCollection> scaleVolume;

	public AudioSumProvider() {
		this(KernelPreferences.isPreferKernels());
	}

	public AudioSumProvider(boolean parallel) {
		if (Heap.getDefault() != null) {
			throw new RuntimeException();
		}

		this.parallel = parallel;

		CollectionProducer sum = add(v(shape(-1), 0), v(shape(-1), 1));
		((ComputationBase) sum).setComputeRequirements(List.of(ComputeRequirement.CPU));

		CollectionProducer scaleVolume = multiply(v(shape(-1), 0), v(shape(1), 1));

		if (parallel) {
			this.sum = sum.get();
			this.scaleVolume = scaleVolume.get();
		} else {
			this.sum = ((CollectionProducerComputationBase) sum).toRepeated().get();
			this.scaleVolume = ((CollectionProducerComputationBase) scaleVolume).toRepeated().get();
		}
	}

	public PackedCollection sum(PackedCollection dest, PackedCollection in) {
		long start = System.nanoTime();

		try {
			if (parallel) {
				sum.into(dest.traverse(1)).evaluate(dest.traverse(1), in.traverse(1));
			} else {
				sum.into(dest.traverse(0)).evaluate(dest.traverse(0), in.traverse(0));
			}

			return dest;
		} finally {
			timing.addEntry("sum", System.nanoTime() - start);
		}
	}

	public PackedCollection adjustVolume(PackedCollection dest, PackedCollection volume) {
		long start = System.nanoTime();

		try {
			if (parallel) {
				scaleVolume.into(dest.traverse(1)).evaluate(dest.traverse(1), volume);
			} else {
				scaleVolume.into(dest.traverse(0)).evaluate(dest.traverse(0), volume);
			}

			return dest;
		} finally {
			timing.addEntry("adjustVolume", System.nanoTime() - start);
		}
	}
}
