/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.VectorProducer;
import io.almostrealism.code.ProducerComputation;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;

public interface TriangleDataProducer extends ProducerComputation<TriangleData>, KernelizedProducer<TriangleData>, TriangleDataFeatures {

	@Override
	default KernelizedEvaluable<TriangleData> get() {
		DefaultTriangleDataEvaluable ev = new DefaultTriangleDataEvaluable(this);
		ev.compile();
		return ev;
	}

	default VectorProducer abc() { return abc(this); }

	default VectorProducer def() { return def(this); }

	default VectorProducer jkl() { return jkl(this); }

	default VectorProducer normal() { return normal(this); }
}
