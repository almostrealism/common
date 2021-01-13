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

package org.almostrealism.algebra;

import org.almostrealism.algebra.computations.DefaultPairEvaluable;
import org.almostrealism.algebra.computations.DefaultScalarEvaluable;
import org.almostrealism.algebra.computations.PairFromScalars;
import org.almostrealism.algebra.computations.ScalarFromPair;
import org.almostrealism.algebra.computations.AcceleratedStaticPairComputation;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public interface PairFeatures {

	default PairProducer pair(double x, double y) { return value(new Pair(x, y)); }

	default PairProducer v(Pair value) { return value(value); }

	default PairProducer value(Pair value) {
		return new AcceleratedStaticPairComputation(value);
	}

	default ScalarEvaluable l(Evaluable<Pair> p) {
		return new DefaultScalarEvaluable(l(() -> p));
	}

	default ScalarProducer l(Supplier<Evaluable<? extends Pair>> p) {
		return new ScalarFromPair(p, ScalarFromPair.X);
	}

	default ScalarEvaluable r(Evaluable<Pair> p) {
		return new DefaultScalarEvaluable(r(() -> p));
	}

	default ScalarProducer r(Supplier<Evaluable<? extends Pair>> p) {
		return new ScalarFromPair(p, ScalarFromPair.Y);
	}

	default PairEvaluable fromScalars(Evaluable<Scalar> x, Evaluable<Scalar> y) {
		return new DefaultPairEvaluable(fromScalars(() -> x, () -> y));
	}

	default PairProducer fromScalars(Supplier<Evaluable<? extends Scalar>> x, Supplier<Evaluable<? extends Scalar>> y) {
		return new PairFromScalars(x, y);
	}
}
