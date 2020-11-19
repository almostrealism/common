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

import org.almostrealism.algebra.computations.DefaultPairProducer;
import org.almostrealism.algebra.computations.DefaultScalarProducer;
import org.almostrealism.algebra.computations.PairFromScalars;
import org.almostrealism.algebra.computations.ScalarFromPair;
import org.almostrealism.util.Producer;

import java.util.function.Supplier;

public interface PairFeatures {
	default ScalarProducer l(Producer<Pair> p) {
		return new DefaultScalarProducer(l(() -> p));
	}

	default ScalarSupplier l(Supplier<Producer<? extends Pair>> p) {
		return new ScalarFromPair(p, ScalarFromPair.X);
	}

	default ScalarProducer r(Producer<Pair> p) {
		return new DefaultScalarProducer(r(() -> p));
	}

	default ScalarSupplier r(Supplier<Producer<? extends Pair>> p) {
		return new ScalarFromPair(p, ScalarFromPair.Y);
	}

	default PairProducer fromScalars(Producer<Scalar> x, Producer<Scalar> y) {
		return new DefaultPairProducer(fromScalars(() -> x, () -> y));
	}

	default PairSupplier fromScalars(Supplier<Producer<? extends Scalar>> x, Supplier<Producer<? extends Scalar>> y) {
		return new PairFromScalars(x, y);
	}
}
