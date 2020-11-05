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

public interface PairProducer extends Producer<Pair> {
	default ScalarProducer x() { return x(this); }
	default ScalarProducer y() { return y(this); }

	static ScalarProducer x(Producer<Pair> p) { return new DefaultScalarProducer(new ScalarFromPair(p, ScalarFromPair.X)); }
	static ScalarProducer y(Producer<Pair> p) { return new DefaultScalarProducer(new ScalarFromPair(p, ScalarFromPair.Y)); }

	static PairProducer fromScalars(Producer<Scalar> x, Producer<Scalar> y) {
		return new DefaultPairProducer(new PairFromScalars(x, y));
	}
}
