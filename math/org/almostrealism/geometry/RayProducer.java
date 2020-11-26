/*
 * Copyright 2020 Michael Murray
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

import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.computations.DirectionDotDirection;
import org.almostrealism.algebra.computations.OriginDotDirection;
import org.almostrealism.algebra.computations.OriginDotOrigin;
import org.almostrealism.relation.ProducerComputation;
import org.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public interface RayProducer extends ProducerComputation<Ray>, RayFeatures {
	@Override
	default Evaluable<Ray> get() { return new DefaultRayEvaluable(this); }

	default VectorProducer origin() { return origin(this); }

	default VectorProducer direction() { return direction(this); }

	default ScalarProducer oDoto() { return oDoto(this); }

	default ScalarProducer dDotd() { return dDotd(this); }

	default ScalarProducer oDotd() { return oDotd(this); }
}
