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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Evaluation of a computation on each available backend, for tests that require a result to
 * be the same on the CPU and, when one is present, the GPU.
 */
public interface BackendTestFeatures {
	/**
	 * Returns the backends to evaluate on: the CPU, and the GPU when one is available.
	 *
	 * @return the compute requirements selecting each backend
	 */
	default List<ComputeRequirement> backends() {
		return Stream.of(ComputeRequirement.CPU, ComputeRequirement.GPU)
				.filter(r -> Hardware.getLocalHardware().isAvailable(r))
				.collect(Collectors.toList());
	}

	/**
	 * Evaluates the given computation with the given backend required.
	 *
	 * @param producer the computation
	 * @param backend  the backend
	 * @return the result
	 */
	default PackedCollection evaluate(CollectionProducer producer, ComputeRequirement backend) {
		Hardware.getLocalHardware().getComputer().pushRequirements(List.of(backend));

		try {
			return producer.evaluate();
		} finally {
			Hardware.getLocalHardware().getComputer().popRequirements();
		}
	}
}
