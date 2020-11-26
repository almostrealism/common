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
import org.almostrealism.algebra.VectorEvaluable;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.computations.DefaultVectorEvaluable;
import org.almostrealism.algebra.computations.DirectionDotDirection;
import org.almostrealism.algebra.computations.OriginDotDirection;
import org.almostrealism.algebra.computations.OriginDotOrigin;
import org.almostrealism.algebra.computations.RayDirection;
import org.almostrealism.algebra.computations.RayOrigin;
import org.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public interface RayFeatures {
	default VectorEvaluable origin(Evaluable<Ray> r) {
		return new DefaultVectorEvaluable(origin(() -> r));
	}

	default VectorProducer origin(Supplier<Evaluable<? extends Ray>> r) {
		return new RayOrigin(r);
	}

	default VectorEvaluable direction(Evaluable<Ray> r) {
		return new DefaultVectorEvaluable(direction(() -> r));
	}

	default VectorProducer direction(Supplier<Evaluable<? extends Ray>> r) {
		return new RayDirection(r);
	}

	default ScalarProducer oDoto(Supplier<Evaluable<? extends Ray>> r) { return new OriginDotOrigin(r); }

	default ScalarProducer dDotd(Supplier<Evaluable<? extends Ray>> r) { return new DirectionDotDirection(r); }

	default ScalarProducer oDotd(Supplier<Evaluable<? extends Ray>> r) { return new OriginDotDirection(r); }
}
