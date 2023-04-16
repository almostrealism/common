/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.color;

import io.almostrealism.code.OperationAdapter;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.color.computations.DefaultRGBEvaluable;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.DefaultComputer;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;

import java.util.function.Supplier;

@Deprecated
public interface RGBProducer extends ProducerComputation<RGB>, KernelizedProducer<RGB>, RGBFeatures {
	@Override
	default KernelizedEvaluable<RGB> get() {
		AcceleratedComputationEvaluable ev = new DefaultRGBEvaluable(this);
		ev.compile();
		return ev;
	}
}
