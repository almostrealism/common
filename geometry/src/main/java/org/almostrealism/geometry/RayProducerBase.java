/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.geometry;

import io.almostrealism.code.ProducerComputation;
import org.almostrealism.algebra.VectorProducerBase;
import org.almostrealism.algebra.computations.ScalarExpressionComputation;
import org.almostrealism.hardware.KernelizedProducer;

public interface RayProducerBase extends ProducerComputation<Ray>, KernelizedProducer<Ray>, RayFeatures {

	default VectorProducerBase origin() { return origin(this); }

	default VectorProducerBase direction() { return direction(this); }

	default ScalarExpressionComputation oDoto() { return oDoto(this); }

	default ScalarExpressionComputation dDotd() { return dDotd(this); }

	default ScalarExpressionComputation oDotd() { return oDotd(this); }
}
